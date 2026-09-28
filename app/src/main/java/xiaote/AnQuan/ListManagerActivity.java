package xiaote.AnQuan;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 名单管理（独立 Activity，不放在 SmallTool 下）。
 *
 * 顶部 tab 栏切换，目前两页：
 *   1. 敏感 App   —— 原 SensitiveAppsActivity 的功能搬入
 *   2. 管控列表   —— 原 ManagedListActivity 的功能搬入
 *
 * 采用「一个 Activity + 顶部切换按钮 + 内容容器重建」的结构，
 * 不依赖 Fragment / ViewPager，避免引入额外依赖。
 *
 * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟。
 */
public class ListManagerActivity extends BaseActivity {

    private static final int TAB_SENSITIVE = 0;
    private static final int TAB_MANAGED = 1;
    private static final int TAB_WHITELIST = 2;

    /** 应用选择器请求码 */
    private static final int REQ_PICK_SENSITIVE = 4001;
    private static final int REQ_PICK_MANAGED = 4002;
    private static final int REQ_PICK_WHITELIST = 4003;

    private int currentTab = TAB_SENSITIVE;

    private Button tabSensitiveBtn;
    private Button tabManagedBtn;
    private Button tabWhitelistBtn;
    private LinearLayout contentContainer;

    private PackageManager pm;

    // ===== 敏感 App =====
    private final List<String> sensitiveItems = new ArrayList<String>();
    private final List<String> sensitiveFiltered = new ArrayList<String>();
    private SensitiveAdapter sensitiveAdapter;

    // ===== 管控列表 =====
    private final List<String> managedItems = new ArrayList<String>();
    private ManagedAdapter managedAdapter;

    // ===== 白名单 =====
    private final List<String> whitelistItems = new ArrayList<String>();
    private WhitelistAdapter whitelistAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // ===== 标题 =====
        TextView title = new TextView(this);
        title.setText("名单管理");
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dpToPx(20), 0, dpToPx(10));
        root.addView(title);

        // ===== 顶部 tab 栏 =====
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dpToPx(12), 0, dpToPx(12), 0);

        tabSensitiveBtn = new Button(this);
        tabSensitiveBtn.setText("敏感App");
        tabSensitiveBtn.setTextSize(14);
        tabSensitiveBtn.setAllCaps(false);
        tabSensitiveBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        tabSensitiveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { switchTab(TAB_SENSITIVE); }
        });
        tabBar.addView(tabSensitiveBtn);

        tabManagedBtn = new Button(this);
        tabManagedBtn.setText("管控列表");
        tabManagedBtn.setTextSize(14);
        tabManagedBtn.setAllCaps(false);
        tabManagedBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        tabManagedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { switchTab(TAB_MANAGED); }
        });
        tabBar.addView(tabManagedBtn);

        tabWhitelistBtn = new Button(this);
        tabWhitelistBtn.setText("白名单");
        tabWhitelistBtn.setTextSize(14);
        tabWhitelistBtn.setAllCaps(false);
        tabWhitelistBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        tabWhitelistBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { switchTab(TAB_WHITELIST); }
        });
        tabBar.addView(tabWhitelistBtn);

        root.addView(tabBar);

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(60, 0, 0, 0));
        divider.setLayoutParams(new LinearLayout.LayoutParams(-1, dpToPx(1)));
        root.addView(divider);

        // ===== 内容容器 =====
        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(contentContainer);

        // ===== 底部返回 =====
        Button backBtn = new Button(this);
        backBtn.setText("完成");
        backBtn.setTextSize(14);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        root.addView(backBtn, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);

        switchTab(TAB_SENSITIVE);
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

    // ==================== tab 切换 ====================

    private void switchTab(int tab) {
        currentTab = tab;
        updateTabStyle();
        contentContainer.removeAllViews();
        if (tab == TAB_SENSITIVE) buildSensitivePage();
        else if (tab == TAB_MANAGED) buildManagedPage();
        else buildWhitelistPage();
    }

    private void updateTabStyle() {
        if (tabSensitiveBtn == null || tabManagedBtn == null || tabWhitelistBtn == null) return;
        int on = Color.argb(255, 30, 30, 30);
        int off = Color.argb(140, 120, 120, 120);
        boolean s = (currentTab == TAB_SENSITIVE);
        boolean m = (currentTab == TAB_MANAGED);
        boolean w = (currentTab == TAB_WHITELIST);
        tabSensitiveBtn.setTextColor(s ? on : off);
        tabManagedBtn.setTextColor(m ? on : off);
        tabWhitelistBtn.setTextColor(w ? on : off);
        tabSensitiveBtn.setTypeface(null, s ? Typeface.BOLD : Typeface.NORMAL);
        tabManagedBtn.setTypeface(null, m ? Typeface.BOLD : Typeface.NORMAL);
        tabWhitelistBtn.setTypeface(null, w ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ==================== 敏感 App 页 ====================

    private void buildSensitivePage() {
        TextView tip = new TextView(this);
        tip.setText("进入敏感App时，智能管理模式的应用将暂时停止无障碍\n长按可移除，默认包名可恢复");
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        contentContainer.addView(tip);

        Button pickBtn = new Button(this);
        pickBtn.setText("选择应用");
        pickBtn.setTextSize(14);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(ListManagerActivity.this,
                            xiaote.AnQuan.SmallTool.AppPicker.AppPickerActivity.class);
                    i.putExtra("title", "选择敏感应用");
                    startActivityForResult(i, REQ_PICK_SENSITIVE);
                } catch (Throwable t) {
                    Toast.makeText(ListManagerActivity.this, "无法打开应用选择器", Toast.LENGTH_SHORT).show();
                }
            }
        });
        contentContainer.addView(pickBtn, new LinearLayout.LayoutParams(-1, -2));

        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        sensitiveAdapter = new SensitiveAdapter();
        listView.setAdapter(sensitiveAdapter);
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= sensitiveFiltered.size()) return false;
                final String pkg = sensitiveFiltered.get(position);
                if (pkg == null || pkg.startsWith("───")) return false;
                boolean isBuiltin = ProtectedPackages.isBuiltin(pkg);
                final boolean isRemoved = ProtectedPackages.getRemovedDefaults(ListManagerActivity.this).contains(pkg);
                if (isBuiltin) {
                    if (isRemoved) {
                        new AlertDialog.Builder(ListManagerActivity.this)
                                .setTitle("恢复默认")
                                .setMessage("将 " + pkg + " 加回敏感列表？")
                                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        ProtectedPackages.restoreProtected(ListManagerActivity.this, pkg);
                                        reloadSensitive();
                                    }
                                })
                                .setNegativeButton("取消", null).show();
                    } else {
                        new AlertDialog.Builder(ListManagerActivity.this)
                                .setTitle("移除")
                                .setMessage("确定将 " + pkg + " 从敏感列表移除？\n（可长按恢复）")
                                .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        ProtectedPackages.removeProtected(ListManagerActivity.this, pkg);
                                        reloadSensitive();
                                    }
                                })
                                .setNegativeButton("取消", null).show();
                    }
                } else {
                    new AlertDialog.Builder(ListManagerActivity.this)
                            .setTitle("删除")
                            .setMessage("确定删除自定义包名 " + pkg + "？")
                            .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    ProtectedPackages.removeProtected(ListManagerActivity.this, pkg);
                                    reloadSensitive();
                                }
                            })
                            .setNegativeButton("取消", null).show();
                }
                return true;
            }
        });
        contentContainer.addView(listView);

        reloadSensitive();
    }

    private void reloadSensitive() {
        sensitiveItems.clear();
        Set<String> all = ProtectedPackages.getProtectedPackages(this);
        sensitiveItems.addAll(all);
        Set<String> removed = ProtectedPackages.getRemovedDefaults(this);
        if (!removed.isEmpty()) {
            sensitiveItems.add("─── 已移除的默认 ───");
            sensitiveItems.addAll(removed);
        }
        applySensitiveFilter();
    }

    private void applySensitiveFilter() {
        sensitiveFiltered.clear();
        sensitiveFiltered.addAll(sensitiveItems);
        if (sensitiveAdapter != null) sensitiveAdapter.notifyDataSetChanged();
    }

    private class SensitiveAdapter extends BaseAdapter {
        @Override public int getCount() { return sensitiveFiltered.size(); }
        @Override public Object getItem(int position) { return sensitiveFiltered.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            String item = sensitiveFiltered.get(position);
            if (item != null && item.startsWith("───")) {
                TextView divider = new TextView(ListManagerActivity.this);
                divider.setText(item);
                divider.setTextSize(12);
                divider.setTextColor(Color.argb(150, 120, 120, 120));
                divider.setGravity(Gravity.CENTER);
                divider.setPadding(0, dpToPx(12), 0, dpToPx(12));
                return divider;
            }
            LinearLayout row = new LinearLayout(ListManagerActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12));

            String appName = item;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(item, 0);
                appName = pm.getApplicationLabel(ai).toString();
            } catch (Exception ignored) {}

            TextView nameView = new TextView(ListManagerActivity.this);
            nameView.setText(appName);
            nameView.setTextSize(15);
            nameView.setTextColor(getTextColor());
            nameView.setTypeface(null, Typeface.BOLD);
            row.addView(nameView);

            TextView pkgView = new TextView(ListManagerActivity.this);
            pkgView.setText(item);
            pkgView.setTextSize(11);
            pkgView.setTextColor(getSecondaryTextColor());
            row.addView(pkgView);
            return row;
        }
    }

    // ==================== 管控列表页 ====================

    private void buildManagedPage() {
        TextView tip = new TextView(this);
        tip.setText("管控应用不禁止安装，仅单独标记管理\n长按条目可移除管控");
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        contentContainer.addView(tip);

        Button pickManagedBtn = new Button(this);
        pickManagedBtn.setText("选择应用");
        pickManagedBtn.setTextSize(14);
        pickManagedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(ListManagerActivity.this,
                            xiaote.AnQuan.SmallTool.AppPicker.AppPickerActivity.class);
                    i.putExtra("title", "选择管控应用");
                    startActivityForResult(i, REQ_PICK_MANAGED);
                } catch (Throwable t) {
                    Toast.makeText(ListManagerActivity.this, "无法打开应用选择器", Toast.LENGTH_SHORT).show();
                }
            }
        });
        contentContainer.addView(pickManagedBtn, new LinearLayout.LayoutParams(-1, -2));

        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        managedAdapter = new ManagedAdapter();
        listView.setAdapter(managedAdapter);
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= managedItems.size()) return false;
                final String pkg = managedItems.get(position);
                String label = pkg;
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                } catch (Exception ignored) {}
                new AlertDialog.Builder(ListManagerActivity.this)
                        .setTitle("移除管控")
                        .setMessage("确定将 " + label + " 移除管控？")
                        .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                VirusPackages.removeManagedPackage(ListManagerActivity.this, pkg);
                                reloadManaged();
                                Toast.makeText(ListManagerActivity.this,
                                        "已移除管控: " + pkg, Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null).show();
                return true;
            }
        });
        contentContainer.addView(listView);

        reloadManaged();
    }

    private void reloadManaged() {
        managedItems.clear();
        managedItems.addAll(VirusPackages.getManagedPackages(this));
        if (managedAdapter != null) managedAdapter.notifyDataSetChanged();
    }

    private class ManagedAdapter extends BaseAdapter {
        @Override public int getCount() { return managedItems.size(); }
        @Override public Object getItem(int position) { return managedItems.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                row.removeAllViews();
            } else {
                row = new LinearLayout(ListManagerActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12));
            }
            String pkg = managedItems.get(position);
            String appName = pkg;
            try {
                appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ignored) {}

            TextView nameView = new TextView(ListManagerActivity.this);
            nameView.setText(appName);
            nameView.setTextSize(15);
            nameView.setTextColor(getTextColor());
            nameView.setTypeface(null, Typeface.BOLD);
            row.addView(nameView);

            TextView subView = new TextView(ListManagerActivity.this);
            subView.setText(pkg + "\n（长按移除管控）");
            subView.setTextSize(11);
            subView.setTextColor(Color.argb(255, 40, 100, 200));
            row.addView(subView);
            return row;
        }
    }

    // ==================== 白名单页 ====================

    private void buildWhitelistPage() {
        TextView tip = new TextView(this);
        tip.setText("白名单应用不参与安全扫描，也不参与全屏覆盖拦截\n长按条目可移除");
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        contentContainer.addView(tip);

        Button pickBtn = new Button(this);
        pickBtn.setText("选择应用");
        pickBtn.setTextSize(14);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(ListManagerActivity.this,
                            xiaote.AnQuan.SmallTool.AppPicker.AppPickerActivity.class);
                    i.putExtra("title", "选择白名单应用");
                    startActivityForResult(i, REQ_PICK_WHITELIST);
                } catch (Throwable t) {
                    Toast.makeText(ListManagerActivity.this, "无法打开应用选择器", Toast.LENGTH_SHORT).show();
                }
            }
        });
        contentContainer.addView(pickBtn, new LinearLayout.LayoutParams(-1, -2));

        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        whitelistAdapter = new WhitelistAdapter();
        listView.setAdapter(whitelistAdapter);
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= whitelistItems.size()) return false;
                final String pkg = whitelistItems.get(position);
                String label = pkg;
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                } catch (Exception ignored) {}
                new AlertDialog.Builder(ListManagerActivity.this)
                        .setTitle("移除白名单")
                        .setMessage("确定将 " + label + " 移出白名单？")
                        .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                WhitelistPackages.remove(ListManagerActivity.this, pkg);
                                reloadWhitelist();
                                Toast.makeText(ListManagerActivity.this,
                                        "已移出白名单: " + pkg, Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null).show();
                return true;
            }
        });
        contentContainer.addView(listView);

        reloadWhitelist();
    }

    private void reloadWhitelist() {
        whitelistItems.clear();
        List<String> list = new ArrayList<String>(WhitelistPackages.get(this));
        Collections.sort(list);
        whitelistItems.addAll(list);
        if (whitelistAdapter != null) whitelistAdapter.notifyDataSetChanged();
    }

    private class WhitelistAdapter extends BaseAdapter {
        @Override public int getCount() { return whitelistItems.size(); }
        @Override public Object getItem(int position) { return whitelistItems.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                row.removeAllViews();
            } else {
                row = new LinearLayout(ListManagerActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12));
            }
            String pkg = whitelistItems.get(position);
            String appName = pkg;
            try {
                appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ignored) {}

            TextView nameView = new TextView(ListManagerActivity.this);
            nameView.setText(appName);
            nameView.setTextSize(15);
            nameView.setTextColor(getTextColor());
            nameView.setTypeface(null, Typeface.BOLD);
            row.addView(nameView);

            TextView subView = new TextView(ListManagerActivity.this);
            subView.setText(pkg + "\n（长按移出白名单）");
            subView.setTextSize(11);
            subView.setTextColor(Color.argb(255, 40, 100, 200));
            row.addView(subView);
            return row;
        }
    }

    // ==================== 应用选择器回调 ====================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        String pkg = null;
        try {
            pkg = data.getStringExtra(
                    xiaote.AnQuan.SmallTool.AppPicker.AppPickerActivity.EXTRA_RESULT_PACKAGE);
        } catch (Throwable ignored) {}
        if (pkg == null || pkg.trim().isEmpty()) return;
        pkg = pkg.trim();

        if (requestCode == REQ_PICK_SENSITIVE) {
            ProtectedPackages.addProtected(this, pkg);
            reloadSensitive();
            Toast.makeText(this, "已新增敏感应用: " + pkg, Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_MANAGED) {
            VirusPackages.addManagedPackage(this, pkg);
            reloadManaged();
            Toast.makeText(this, "已加入管控: " + pkg, Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_WHITELIST) {
            WhitelistPackages.add(this, pkg);
            reloadWhitelist();
            Toast.makeText(this, "已加入白名单: " + pkg, Toast.LENGTH_SHORT).show();
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
