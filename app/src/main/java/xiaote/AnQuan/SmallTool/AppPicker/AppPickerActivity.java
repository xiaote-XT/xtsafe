package xiaote.AnQuan.SmallTool.AppPicker;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import xiaote.AnQuan.SwipeBackHelper;

/**
 * 应用选择工具（无界面入口，仅供其他页面内部调用）。
 *
 * 用途：给「名单管理」等需要输入包名的地方提供图形化选取。
 *
 * 两种使用方式：
 *   1. startActivityForResult 调用，选中后回传包名
 *      Intent intent = new Intent(ctx, AppPickerActivity.class);
 *      startActivityForResult(intent, REQ);
 *      // onActivityResult 中取 data.getStringExtra("package_name")
 *   2. 直接在 Manifest 中不导出，MainActivity 不提供入口
 *
 * 三个功能区：
 *   1. 搜索框  —— 过滤已安装应用列表（名称或包名）
 *   2. 列表    —— 已安装第三方应用，点击即选中
 *   3. 包名输入 —— 手动输入包名，直接采用
 *
 * 缓存策略：
 *   已安装应用列表缓存在 filesDir/app_picker_cache.json。
 *   打开时先读缓存立即渲染，再在后台扫描一遍系统应用列表，
 *   只把新增的补进缓存、把已卸载的移除，写回文件并刷新。
 *   避免每次打开都做完整的 PackageManager 全量查询。
 *
 * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟。
 */
public class AppPickerActivity extends Activity {

    /** 回传结果的 key */
    public static final String EXTRA_RESULT_PACKAGE = "package_name";
    /** 可选：调用方传入的标题 */
    public static final String EXTRA_TITLE = "title";

    private static final String CACHE_FILE = "app_picker_cache.json";

    private PackageManager pm;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<AppEntry> allApps = new ArrayList<AppEntry>();
    private final List<AppEntry> shownApps = new ArrayList<AppEntry>();
    private AppAdapter adapter;
    private EditText searchBox;
    private TextView statusView;

    /** 标记：列表是否已被系统扫描结果更新过 */
    private volatile boolean scanDone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        String titleText = "选择应用";
        try {
            String t = getIntent().getStringExtra(EXTRA_TITLE);
            if (t != null && !t.isEmpty()) titleText = t;
        } catch (Exception ignored) {}

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dpToPx(20), 0, dpToPx(8));
        root.addView(title);

        // ===== 搜索框 =====
        searchBox = new EditText(this);
        searchBox.setHint("搜索应用名称或包名");
        searchBox.setHintTextColor(Color.argb(150, 120, 120, 120));
        searchBox.setTextColor(getTextColor());
        searchBox.setTextSize(14);
        searchBox.setSingleLine(true);
        searchBox.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(searchBox);

        // ===== 状态行 =====
        statusView = new TextView(this);
        statusView.setTextSize(11);
        statusView.setTextColor(getSecondaryTextColor());
        statusView.setPadding(dpToPx(16), dpToPx(2), dpToPx(16), dpToPx(6));
        statusView.setText("正在加载...");
        root.addView(statusView);

        // ===== 列表 =====
        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        adapter = new AppAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= shownApps.size()) return;
                finishWithPackage(shownApps.get(position).pkg);
            }
        });
        root.addView(listView);

        // ===== 包名输入行 =====
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));

        final EditText pkgInput = new EditText(this);
        pkgInput.setHint("手动输入包名");
        pkgInput.setHintTextColor(Color.argb(150, 120, 120, 120));
        pkgInput.setTextColor(getTextColor());
        pkgInput.setTextSize(13);
        pkgInput.setSingleLine(true);
        pkgInput.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        inputRow.addView(pkgInput);

        Button useBtn = new Button(this);
        useBtn.setText("采用");
        useBtn.setTextSize(14);
        useBtn.setAllCaps(false);
        useBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pkg = pkgInput.getText().toString().trim();
                if (pkg.isEmpty()) {
                    Toast.makeText(AppPickerActivity.this, "请输入包名", Toast.LENGTH_SHORT).show();
                    return;
                }
                finishWithPackage(pkg);
            }
        });
        inputRow.addView(useBtn);
        root.addView(inputRow);

        // ===== 底部按钮 =====
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setPadding(dpToPx(12), 0, dpToPx(12), dpToPx(8));

        Button refreshBtn = new Button(this);
        refreshBtn.setText("重新扫描");
        refreshBtn.setTextSize(14);
        refreshBtn.setAllCaps(false);
        refreshBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanDone = false;
                scanInstalledAsync();
            }
        });
        bottomRow.addView(refreshBtn);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("取消");
        cancelBtn.setTextSize(14);
        cancelBtn.setAllCaps(false);
        cancelBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        bottomRow.addView(cancelBtn);
        root.addView(bottomRow);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);

        // 1. 先读缓存，立即出内容
        loadCacheIntoList();
        // 2. 后台扫描，增量更新缓存
        scanInstalledAsync();
    }

    private void registerBackCallback() {
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                try {
                    getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                            new android.window.OnBackInvokedCallback() {
                                @Override
                                public void onBackInvoked() { finish(); }
                            });
                } catch (Exception e) {}
            }
        });
    }

    /** 选中后回传包名并结束 */
    private void finishWithPackage(String pkg) {
        try {
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT_PACKAGE, pkg);
            setResult(RESULT_OK, data);
        } catch (Exception ignored) {}
        // 记一下最近选择，方便调用方无 result 时也能取到
        try { prefs.edit().putString("app_picker_last_pick", pkg).apply(); } catch (Exception ignored) {}
        finish();
    }

    // ==================== 缓存读写 ====================

    private File cacheFile() {
        return new File(getFilesDir(), CACHE_FILE);
    }

    /** 读缓存文件，直接渲染列表 */
    private void loadCacheIntoList() {
        try {
            File f = cacheFile();
            if (!f.exists() || f.length() == 0) {
                statusView.setText("首次使用，正在扫描已安装应用...");
                return;
            }
            FileInputStream fis = new FileInputStream(f);
            BufferedReader r = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            fis.close();

            JSONArray arr = new JSONArray(sb.toString());
            List<AppEntry> list = new ArrayList<AppEntry>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String pkg = o.optString("pkg", "");
                String name = o.optString("name", "");
                if (pkg.isEmpty()) continue;
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.name = name.isEmpty() ? pkg : name;
                list.add(e);
            }
            sortList(list);
            allApps.clear();
            allApps.addAll(list);
            applyFilter();
            statusView.setText("缓存 " + list.size() + " 个应用，正在检查新增...");
        } catch (Throwable t) {
            statusView.setText("缓存读取失败，正在重新扫描...");
        }
    }

    /** 写缓存文件 */
    private void saveCache(List<AppEntry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (AppEntry e : list) {
                JSONObject o = new JSONObject();
                o.put("pkg", e.pkg);
                o.put("name", e.name);
                arr.put(o);
            }
            FileOutputStream fos = new FileOutputStream(cacheFile());
            fos.write(arr.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }

    // ==================== 后台扫描（增量） ====================

    /**
     * 后台扫描已安装应用，与缓存做增量合并：
     *   · 缓存里没有的 → 补进去
     *   · 系统里已没有的 → 移除
     *   · 已有的保留（不重查 label，省一次跨进程调用）
     */
    private void scanInstalledAsync() {
        if (scanDone) return;
        final String selfPkg = getPackageName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> merged = new ArrayList<AppEntry>();
                final Set<String> installed = new HashSet<String>();
                try {
                    List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                    for (ApplicationInfo ai : apps) {
                        if (ai == null) continue;
                        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                        if (selfPkg.equals(ai.packageName)) continue;
                        installed.add(ai.packageName);
                    }
                } catch (Throwable ignored) {}

                // 缓存中仍存在的，保留（沿用缓存里的名字，不再查 label）
                final Set<String> seen = new HashSet<String>();
                synchronized (allApps) {
                    for (AppEntry old : allApps) {
                        if (installed.contains(old.pkg) && !seen.contains(old.pkg)) {
                            merged.add(old);
                            seen.add(old.pkg);
                        }
                    }
                }

                // 新增的补进去
                for (String pkg : installed) {
                    if (seen.contains(pkg)) continue;
                    AppEntry e = new AppEntry();
                    e.pkg = pkg;
                    e.name = resolveLabel(pkg);
                    merged.add(e);
                    seen.add(pkg);
                }

                sortList(merged);

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        allApps.clear();
                        allApps.addAll(merged);
                        applyFilter();
                        statusView.setText("共 " + merged.size() + " 个应用");
                        scanDone = true;
                    }
                });

                // 写回缓存（合并结果即最新）
                saveCache(merged);
            }
        }, "app-picker-scan").start();
    }

    private String resolveLabel(String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private static void sortList(List<AppEntry> list) {
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                String an = a.name == null ? "" : a.name;
                String bn = b.name == null ? "" : b.name;
                return an.compareToIgnoreCase(bn);
            }
        });
    }

    private void applyFilter() {
        shownApps.clear();
        String q = (searchBox != null) ? searchBox.getText().toString().trim().toLowerCase() : "";
        synchronized (allApps) {
            for (AppEntry e : allApps) {
                if (q.isEmpty()
                        || (e.name != null && e.name.toLowerCase().contains(q))
                        || (e.pkg != null && e.pkg.toLowerCase().contains(q))) {
                    shownApps.add(e);
                }
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // ==================== 数据结构与适配器 ====================

    private static class AppEntry {
        String name;
        String pkg;
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shownApps.size(); }
        @Override public Object getItem(int position) { return shownApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                row.removeAllViews();
            } else {
                row = new LinearLayout(AppPickerActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12));
            }
            AppEntry e = shownApps.get(position);

            TextView nameView = new TextView(AppPickerActivity.this);
            nameView.setText(e.name);
            nameView.setTextSize(15);
            nameView.setTextColor(getTextColor());
            nameView.setTypeface(null, Typeface.BOLD);
            row.addView(nameView);

            TextView pkgView = new TextView(AppPickerActivity.this);
            pkgView.setText(e.pkg);
            pkgView.setTextSize(11);
            pkgView.setTextColor(Color.argb(255, 40, 100, 200));
            row.addView(pkgView);

            return row;
        }
    }

    // ==================== 工具 ====================

    private int getTextColor() {
        return Color.argb(255, 30, 30, 30);
    }

    private int getSecondaryTextColor() {
        return Color.argb(150, 90, 90, 90);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
