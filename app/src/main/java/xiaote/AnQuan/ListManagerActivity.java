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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
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
 * 顶部 tab 栏切换，四页：
 *   1. 敏感 App   —— 进入敏感应用时暂时停用智能管理模式的无障碍
 *   2. 管控列表   —— 单独标记管理，不禁止安装
 *   3. 白名单     —— 不参与安全扫描、全屏覆盖拦截，也不参与冻结
 *   4. 冻结应用   —— 手动冻结名单，加入即 pm disable，移出即 pm enable
 *
 * 包名统一按 xiaote/AnQuan/类名 这种斜杠路径形式展示。
 *
 * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟。
 */
public class ListManagerActivity extends BaseActivity {

    private static final int TAB_SENSITIVE = 0;
    private static final int TAB_MANAGED = 1;
    private static final int TAB_WHITELIST = 2;
    private static final int TAB_FROZEN = 3;

    /** 应用选择器请求码 */
    private static final int REQ_PICK_SENSITIVE = 4001;
    private static final int REQ_PICK_MANAGED = 4002;
    private static final int REQ_PICK_WHITELIST = 4003;
    private static final int REQ_PICK_FROZEN = 4004;

    private int currentTab = TAB_SENSITIVE;

    private Button tabSensitiveBtn;
    private Button tabManagedBtn;
    private Button tabWhitelistBtn;
    private Button tabFrozenBtn;
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

    // ===== 冻结应用 =====
    private final List<String> frozenItems = new ArrayList<String>();
    private FrozenAdapter frozenAdapter;

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
        tabBar.setPadding(dpToPx(8), 0, dpToPx(8), 0);

        tabSensitiveBtn = makeTab("敏感App", TAB_SENSITIVE);
        tabManagedBtn = makeTab("管控列表", TAB_MANAGED);
        tabWhitelistBtn = makeTab("白名单", TAB_WHITELIST);
        tabFrozenBtn = makeTab("冻结应用", TAB_FROZEN);
        tabBar.addView(tabSensitiveBtn);
        tabBar.addView(tabManagedBtn);
        tabBar.addView(tabWhitelistBtn);
        tabBar.addView(tabFrozenBtn);

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

    private Button makeTab(String text, final int tab) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(dpToPx(4), 0, dpToPx(4), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { switchTab(tab); }
        });
        return b;
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
        else if (tab == TAB_WHITELIST) buildWhitelistPage();
        else buildFrozenPage();
    }

    private void updateTabStyle() {
        if (tabSensitiveBtn == null) return;
        int on = Color.argb(255, 30, 30, 30);
        int off = Color.argb(140, 120, 120, 120);
        Button[] btns = {tabSensitiveBtn, tabManagedBtn, tabWhitelistBtn, tabFrozenBtn};
        int[] tabs = {TAB_SENSITIVE, TAB_MANAGED, TAB_WHITELIST, TAB_FROZEN};
        for (int i = 0; i < btns.length; i++) {
            if (btns[i] == null) continue;
            boolean on2 = (currentTab == tabs[i]);
            btns[i].setTextColor(on2 ? on : off);
            btns[i].setTypeface(null, on2 ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    // ==================== 敏感 App 页 ====================

    private void buildSensitivePage() {
        addTip("进入敏感App时，智能管理模式的应用将暂时停止无障碍\n长按可移除，默认包名可恢复");

        Button pickBtn = new Button(this);
        pickBtn.setText("选择应用");
        pickBtn.setTextSize(14);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPicker("选择敏感应用", REQ_PICK_SENSITIVE);
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
                                .setMessage("将 " + formatPkg(pkg) + " 加回敏感列表？")
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
                                .setMessage("确定将 " + formatPkg(pkg) + " 从敏感列表移除？\n（可长按恢复）")
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
                            .setMessage("确定删除自定义包名 " + formatPkg(pkg) + "？")
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
            if (item != null && item.startsWith("───")) return buildDivider(item);
            return buildRow(item, "（长按移除 / 恢复）");
        }
    }

    // ==================== 管控列表页 ====================

    private void buildManagedPage() {
        addTip("管控应用不禁止安装，仅单独标记管理\n长按条目可移除管控");

        Button pickManagedBtn = new Button(this);
        pickManagedBtn.setText("选择应用");
        pickManagedBtn.setTextSize(14);
        pickManagedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPicker("选择管控应用", REQ_PICK_MANAGED);
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
                String label = appLabel(pkg);
                new AlertDialog.Builder(ListManagerActivity.this)
                        .setTitle("移除管控")
                        .setMessage("确定将 " + label + " 移除管控？")
                        .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                VirusPackages.removeManagedPackage(ListManagerActivity.this, pkg);
                                reloadManaged();
                                Toast.makeText(ListManagerActivity.this,
                                        "已移除管控: " + formatPkg(pkg), Toast.LENGTH_SHORT).show();
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
        List<String> list = new ArrayList<String>(VirusPackages.getManagedPackages(this));
        Collections.sort(list);
        managedItems.addAll(list);
        if (managedAdapter != null) managedAdapter.notifyDataSetChanged();
    }

    private class ManagedAdapter extends BaseAdapter {
        @Override public int getCount() { return managedItems.size(); }
        @Override public Object getItem(int position) { return managedItems.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return buildRow(managedItems.get(position), "（长按移除管控）");
        }
    }

    // ==================== 白名单页 ====================

    private void buildWhitelistPage() {
        addTip("白名单应用不参与安全扫描、全屏覆盖拦截，也不参与冻结\n长按条目可移除");

        Button pickBtn = new Button(this);
        pickBtn.setText("选择应用");
        pickBtn.setTextSize(14);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPicker("选择白名单应用", REQ_PICK_WHITELIST);
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
                String label = appLabel(pkg);
                new AlertDialog.Builder(ListManagerActivity.this)
                        .setTitle("移除白名单")
                        .setMessage("确定将 " + label + " 移出白名单？")
                        .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                WhitelistPackages.remove(ListManagerActivity.this, pkg);
                                reloadWhitelist();
                                Toast.makeText(ListManagerActivity.this,
                                        "已移出白名单: " + formatPkg(pkg), Toast.LENGTH_SHORT).show();
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
            return buildRow(whitelistItems.get(position), "（长按移出白名单）");
        }
    }

    // ==================== 冻结应用页 ====================

    private void buildFrozenPage() {
        addTip("列表展示系统内当前真实被冻结（pm disable）的全部应用\n加入即冻结、长按即解冻；白名单应用不会参与冻结");

        Button pickBtn = new Button(this);
        pickBtn.setText("选择应用（加入并冻结）");
        pickBtn.setTextSize(14);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPicker("选择要冻结的应用", REQ_PICK_FROZEN);
            }
        });
        contentContainer.addView(pickBtn, new LinearLayout.LayoutParams(-1, -2));

        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        frozenAdapter = new FrozenAdapter();
        listView.setAdapter(frozenAdapter);
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= frozenItems.size()) return false;
                final String pkg = frozenItems.get(position);
                String label = appLabel(pkg);
                new AlertDialog.Builder(ListManagerActivity.this)
                        .setTitle("解冻")
                        .setMessage("确定解冻 " + label + "？")
                        .setPositiveButton("解冻", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                unfreezePackage(pkg);
                            }
                        })
                        .setNegativeButton("取消", null).show();
                return true;
            }
        });
        contentContainer.addView(listView);

        reloadFrozen();
    }

    /**
     * 从系统真实状态加载冻结列表（pm list packages -d），
     * 而不是读星特安全本地记录，避免手动/其他工具冻结的应用不显示。
     */
    private void reloadFrozen() {
        frozenItems.clear();
        if (frozenAdapter != null) frozenAdapter.notifyDataSetChanged();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> list = new ArrayList<String>(
                        SafeActionManager.listDisabledPackages(ListManagerActivity.this));
                Collections.sort(list);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        frozenItems.clear();
                        frozenItems.addAll(list);
                        if (frozenAdapter != null) frozenAdapter.notifyDataSetChanged();
                    }
                });
            }
        }, "load-frozen").start();
    }

    private class FrozenAdapter extends BaseAdapter {
        @Override public int getCount() { return frozenItems.size(); }
        @Override public Object getItem(int position) { return frozenItems.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return buildRow(frozenItems.get(position), "（已冻结，长按解冻）");
        }
    }

    /** 加入冻结名单并立即执行冻结 */
    private void freezePackage(final String pkg) {
        if (WhitelistPackages.isWhitelisted(this, pkg)) {
            Toast.makeText(this, "该应用在白名单中，跳过冻结", Toast.LENGTH_LONG).show();
            return;
        }
        FrozenPackages.add(this, pkg);
        reloadFrozen();
        Toast.makeText(this, "已加入冻结: " + formatPkg(pkg), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = SafeActionManager.freezeOnePublic(pkg);
                SafeActionManager.appendLog(ListManagerActivity.this,
                        "手动冻结 " + pkg + " -> " + (ok ? "成功" : "失败"));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (!ok) Toast.makeText(ListManagerActivity.this,
                                "冻结失败，请检查 Shizuku 权限", Toast.LENGTH_LONG).show();
                        reloadFrozen();
                    }
                });
            }
        }, "freeze-one").start();
    }

    /** 移出冻结名单并解冻 */
    private void unfreezePackage(final String pkg) {
        FrozenPackages.remove(this, pkg);
        reloadFrozen();
        Toast.makeText(this, "已解冻: " + formatPkg(pkg), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = SafeActionManager.unfreezeOnePublic(pkg);
                SafeActionManager.appendLog(ListManagerActivity.this,
                        "手动解冻 " + pkg + " -> " + (ok ? "成功" : "失败"));
            }
        }, "unfreeze-one").start();
    }

    // ==================== 应用选择器 ====================

    private void openPicker(String title, int req) {
        try {
            Intent i = new Intent(ListManagerActivity.this,
                    xiaote.AnQuan.SmallTool.AppPicker.AppPickerActivity.class);
            i.putExtra("title", title);
            startActivityForResult(i, req);
        } catch (Throwable t) {
            Toast.makeText(ListManagerActivity.this, "无法打开应用选择器", Toast.LENGTH_SHORT).show();
        }
    }

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
            Toast.makeText(this, "已新增敏感应用: " + formatPkg(pkg), Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_MANAGED) {
            VirusPackages.addManagedPackage(this, pkg);
            reloadManaged();
            Toast.makeText(this, "已加入管控: " + formatPkg(pkg), Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_WHITELIST) {
            WhitelistPackages.add(this, pkg);
            reloadWhitelist();
            Toast.makeText(this, "已加入白名单: " + formatPkg(pkg), Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_FROZEN) {
            freezePackage(pkg);
        }
    }

    // ==================== 通用 UI ====================

    private void addTip(String text) {
        TextView tip = new TextView(this);
        tip.setText(text);
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        contentContainer.addView(tip);
    }

    private TextView buildDivider(String text) {
        TextView divider = new TextView(this);
        divider.setText(text);
        divider.setTextSize(12);
        divider.setTextColor(Color.argb(150, 120, 120, 120));
        divider.setGravity(Gravity.CENTER);
        divider.setPadding(0, dpToPx(12), 0, dpToPx(12));
        return divider;
    }

    /**
     * 统一列表行：应用名 + 包名（斜杠路径形式）+ 提示。
     * 包名展示统一走 formatPkg，例如 xiaote.AnQuan -> xiaote/AnQuan。
     */
    private LinearLayout buildRow(String pkg, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(12));

        TextView nameView = new TextView(this);
        nameView.setText(appLabel(pkg));
        nameView.setTextSize(15);
        nameView.setTextColor(getTextColor());
        nameView.setTypeface(null, Typeface.BOLD);
        row.addView(nameView);

        TextView pkgView = new TextView(this);
        pkgView.setText(formatPkg(pkg) + "\n" + hint);
        pkgView.setTextSize(11);
        pkgView.setTextColor(Color.argb(255, 40, 100, 200));
        row.addView(pkgView);
        return row;
    }

    /**
     * 包名转成便于定位的文件路径形式：
     *   com.example.app -> com/example/app/MainActivity.java
     * 取不到启动 Activity 时退化为 com/example/app。
     */
    private String formatPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String base = pkg.replace('.', '/');
        String cls = null;
        try {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null && launch.getComponent() != null) {
                cls = launch.getComponent().getClassName();
            }
        } catch (Throwable ignored) {}
        // 被冻结/停用的应用 getLaunchIntentForPackage 返回 null，
        // 回退到清单里的 Activity（MATCH_DISABLED_COMPONENTS=512），
        // 否则冻结页只能显示到包路径、缺少 xxx.java。
        if (cls == null || cls.isEmpty()) {
            try {
                android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, 1 | 512);
                if (pi != null && pi.activities != null) {
                    for (android.content.pm.ActivityInfo ai : pi.activities) {
                        if (ai != null && ai.name != null && !ai.name.isEmpty()) {
                            cls = ai.name;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (cls != null && !cls.isEmpty()) {
            int dot = cls.lastIndexOf('.');
            String simple = dot >= 0 ? cls.substring(dot + 1) : cls;
            if (!simple.isEmpty()) return base + "/" + simple + ".java";
        }
        return base;
    }

    private String appLabel(String pkg) {
        // 被冻结/停用的应用用默认 flag 查不到，需要带 MATCH_DISABLED_COMPONENTS(512)
        // 与 MATCH_UNINSTALLED_PACKAGES(8192) 才能取到 ApplicationInfo 和应用名，
        // 否则冻结页每行只能显示包名。
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 512 | 8192);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

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
