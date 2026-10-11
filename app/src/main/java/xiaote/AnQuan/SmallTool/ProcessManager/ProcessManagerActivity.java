package xiaote.AnQuan.SmallTool.ProcessManager;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import xiaote.AnQuan.R;
import xiaote.AnQuan.ShellExecutor;
import xiaote.AnQuan.SwipeBackHelper;
import xiaote.xtui.XtDialog;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * 进程管理。
 *
 * 通过 Shizuku 执行 ps 管道扫描进程，按常驻内存降序展示，
 * 每条可「打开应用」（monkey 拉起 LAUNCHER）或「强制停止」（am force-stop）。
 *
 * 安全约束：
 *   1. com.android.systemui 仅列出，不提供任何操作入口；
 *   2. 其余系统应用执行任何操作前都要二次确认；
 *   3. 普通第三方应用操作不变。
 *
 * 需要 Shizuku 已授权，否则列表为空并给出提示。
 *
 * 已按 xtui 规范重写：骨架 XtUi.column，标题 XtUi.title，
 * 刷新/过滤按钮 XtUi.button，列表分隔线走 XtUi.colorBorder，
 * 弹窗走 XtDialog（单选列表已做深色适配），轻提示走 XtToast。
 * 列表需撑满剩余空间，故直接 setContentView(root)。
 *
 * 继承 Activity 而非 BaseActivity：扫描与停止应用都涉及系统级操作，
 * 中途弹密码锁会打断。
 */
public class ProcessManagerActivity extends Activity {

    /** 仅列出、禁止任何操作的系统界面包名 */
    private static final String SYSTEM_UI_PKG = "com.android.systemui";

    private ListView listView;
    private TextView statusView;
    private List<ProcessItem> items = new ArrayList<ProcessItem>();
    private ProcAdapter adapter;
    private PackageManager pm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean loading = false;

    /** 进程类型过滤：0=全部 1=用户 2=系统 */
    private int filterType = 0;
    private static final String[] FILTER_NAMES = {"全部", "用户", "系统"};

    /** 扫描得到的完整进程列表（未过滤），items 是过滤后用于展示的 */
    private final List<ProcessItem> allItems = new ArrayList<ProcessItem>();
    private TextView filterBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();

        // 界面模式分流：ui_mode=h5 时交给 H5 壳，原生界面不构建。默认 native。
        if ("h5".equals(getSharedPreferences("dot_config", MODE_PRIVATE)
                .getString("ui_mode", "native"))) {
            try { startActivity(new Intent(this, xiaote.AnQuan.H5ProcessActivity.class)); } catch (Throwable ignored) {}
            finish();
            return;
        }

        LinearLayout root = XtUi.column(this);
        root.setPadding(0, 0, 0, 0);

        TextView title = XtUi.title(this, getString(R.string.pm_title));
        title.setPadding(0, XtUi.dp(this, 24), 0, XtUi.dp(this, 4));
        root.addView(title);
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this, getString(R.string.pm_tip));
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(XtUi.dp(this, 16), 0, XtUi.dp(this, 16), XtUi.dp(this, 10));
        root.addView(tip);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(XtUi.colorMuted(this));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(XtUi.dp(this, 16), XtUi.dp(this, 4), XtUi.dp(this, 16), XtUi.dp(this, 8));
        root.addView(statusView);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(XtUi.dp(this, 12), 0, XtUi.dp(this, 12), XtUi.dp(this, 8));

        TextView refreshBtn = XtUi.button(this, getString(R.string.pm_refresh), new View.OnClickListener() {
            @Override
            public void onClick(View v) { refresh(); }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1f);
        rlp.rightMargin = XtUi.dp(this, 4);
        btnRow.addView(refreshBtn, rlp);

        filterBtn = XtUi.button(this, "进程类型：全部", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showFilterMenu(); }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, -2, 1f);
        flp.leftMargin = XtUi.dp(this, 4);
        btnRow.addView(filterBtn, flp);

        root.addView(btnRow);

        listView = new ListView(this);
        listView.setBackgroundColor(XtUi.colorBg(this));
        listView.setDivider(new ColorDrawable(XtUi.colorBorder(this)));
        listView.setDividerHeight(Math.max(1, XtUi.dp(this, XtUi.borderDp)));
        listView.setCacheColorHint(0x00000000);
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));

        adapter = new ProcAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                showActions(items.get(position));
            }
        });

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
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

    /** 按 filterType 把 allItems 过滤进 items（0=全部 1=用户 2=系统） */
    private void applyFilter() {
        items.clear();
        for (ProcessItem it : allItems) {
            if (it == null) continue;
            if (filterType == 1 && it.isSystem) continue;      // 只要用户应用
            if (filterType == 2 && !it.isSystem) continue;     // 只要系统应用
            items.add(it);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (statusView != null) {
            statusView.setText(items.isEmpty()
                    ? getString(R.string.pm_empty)
                    : getString(R.string.pm_loaded, items.size()));
        }
    }

    /** 进程类型菜单：全部 / 用户 / 系统 */
    private void showFilterMenu() {
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("进程类型")
                .setSingleChoiceItems(FILTER_NAMES, filterType, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        filterType = which;
                        if (filterBtn != null) filterBtn.setText("进程类型：" + FILTER_NAMES[which]);
                        applyFilter();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    /** 后台扫描进程，回到主线程刷新列表 */
    private void refresh() {
        if (loading) return;
        if (!ProcessScanner.isAvailable()) {
            statusView.setText(R.string.pm_need_shizuku);
            items.clear();
            adapter.notifyDataSetChanged();
            return;
        }
        loading = true;
        statusView.setText(R.string.pm_loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ProcessItem> list = ProcessScanner.scan();
                // 填充可读应用名与系统应用标记（跨进程查询，放工作线程），
                // 同时屏蔽本应用自身进程（xiaote.AnQuan / xiaote.AnQuan:xxx），
                // 避免误操作自己。
                final String selfPkg = getPackageName();
                final List<ProcessItem> kept = new ArrayList<ProcessItem>();
                for (ProcessItem it : list) {
                    if (it == null || it.name == null) continue;
                    if (it.name.equals(selfPkg) || it.name.startsWith(selfPkg + ":")) continue;
                    fillAppInfo(it);
                    kept.add(it);
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        allItems.clear();
                        allItems.addAll(kept);
                        applyFilter();
                    }
                });
            }
        }, "pm-scan").start();
    }

    /**
     * 取应用名并判定系统应用 / 系统界面。
     *
     * 关键：查不到 ApplicationInfo 时必须按「系统/未知」保守处理。
     * system_server、surfaceflinger、shizuku_server 这类进程名不是包名的原生进程
     * 会被当成普通第三方应用，不弹二次确认就给「打开 / 强制停止」菜单，有误操作风险。
     * 现在未知进程一律标记 isSystem=true + isUnknown=true，只列出、可查看，
     * 操作前必须二次确认。
     */
    private void fillAppInfo(ProcessItem it) {
        if (it == null) return;
        String pkg = it.packageName;
        if (pkg == null || pkg.isEmpty()) return;
        it.isSystemUi = SYSTEM_UI_PKG.equals(pkg);
        it.isUnknown = false;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            it.appName = pm.getApplicationLabel(ai).toString();
            it.isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Exception e) {
            // 查不到应用信息：进程名不是已安装包名（system_server、surfaceflinger、
            // shizuku_server、zygote64、HAL 进程等）。按未知处理，视为系统级，禁止直接操作。
            it.appName = "";
            it.isSystem = true;
            it.isUnknown = true;
        }
        // 系统界面必然是系统应用
        if (it.isSystemUi) it.isSystem = true;
    }

    /** 点击条目：系统界面仅提示，系统应用二次确认，普通应用直接出操作菜单 */
    private void showActions(final ProcessItem it) {
        final String pkg = it.packageName;
        final String label = (it.appName == null || it.appName.isEmpty()) ? it.name : it.appName;

        // 系统界面：仅列出，不给任何操作
        if (it.isSystemUi) {
            XtDialog.show(this, getString(R.string.pm_systemui_title),
                    getString(R.string.pm_systemui_msg, it.name, it.pid, formatMem(it.rssKb)));
            return;
        }

        final String[] actions = {
                getString(R.string.pm_open),
                getString(R.string.pm_stop)
        };
        String tag = "";
        if (it.isUnknown) tag = "\n[未知进程·按系统级处理]";
        else if (it.isSystem) tag = "\n" + getString(R.string.pm_system_tag);
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(label + "\n" + it.name + "  (PID " + it.pid + ")" + tag)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            if (it.isSystem) confirmSystemAction(it, label, true);
                            else openApp(pkg, label);
                        } else {
                            if (it.isSystem) confirmSystemAction(it, label, false);
                            else stopApp(pkg, label);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    /**
     * 系统应用二次确认。
     *
     * @param open true=打开应用，false=强制停止
     */
    private void confirmSystemAction(final ProcessItem it, final String label, final boolean open) {
        final String actionName = open ? getString(R.string.pm_open) : getString(R.string.pm_stop);
        XtDialog.show(this, getString(R.string.pm_system_confirm_title),
                getString(R.string.pm_system_confirm_msg, label, it.name, actionName),
                getString(R.string.confirm_ok), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (open) openApp(it.packageName, label);
                        else stopApp(it.packageName, label);
                    }
                },
                getString(R.string.cancel), null);
    }

    /** 打开应用：优先用 monkey 拉起 LAUNCHER，失败回退 am start */
    private void openApp(String pkg, String label) {
        // 只对可启动的应用做处理
        Intent launch = null;
        try {
            launch = pm.getLaunchIntentForPackage(pkg);
        } catch (Exception ignored) {}
        if (launch == null) {
            XtToast.show(this, R.string.pm_open_fail);
            return;
        }
        // execShizukuSync 会等待返回码，拿得到真实成功/失败
        boolean ok = ShellExecutor.execShizukuSync(new String[]{"sh", "-c",
                "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1"});
        if (!ok) {
            // 回退：直接以应用身份启动（普通权限，无需 Shizuku）
            try {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            } catch (Exception e) {
                XtToast.show(this, R.string.pm_open_fail);
                return;
            }
        }
        XtToast.show(this, getString(R.string.pm_opened, label));
    }

    /** 强制停止：Shizuku → Root → 普通 shell */
    private void stopApp(String pkg, String label) {
        boolean ok = ShellExecutor.forceStopApp(pkg);
        if (ok) {
            XtToast.show(this, getString(R.string.pm_stopped, label));
            handler.postDelayed(new Runnable() {
                @Override
                public void run() { refresh(); }
            }, 600L);
        } else {
            XtToast.show(this, R.string.pm_stop_fail);
        }
    }

    /** 格式化常驻内存（ps 的 RSS 单位是 KB） */
    static String formatMem(int rssKb) {
        if (rssKb <= 0) return "-";
        long bytes = (long) rssKb * 1024L;
        if (bytes < 1024L * 1024L) return (rssKb / 1024.0 < 1 ? rssKb + "KB"
                : String.format(java.util.Locale.US, "%.1fMB", rssKb / 1024.0));
        return String.format(java.util.Locale.US, "%.0fMB", bytes / 1048576.0);
    }

    /** 列表适配器：应用名 + 进程名 / PID / 内存，系统应用带标记 */
    class ProcAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                row.removeAllViews();
            } else {
                row = new LinearLayout(ProcessManagerActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(XtUi.dp(ProcessManagerActivity.this, 20),
                        XtUi.dp(ProcessManagerActivity.this, 12),
                        XtUi.dp(ProcessManagerActivity.this, 20),
                        XtUi.dp(ProcessManagerActivity.this, 12));
            }
            ProcessItem it = items.get(position);

            TextView nameView = new TextView(ProcessManagerActivity.this);
            String title = (it.appName == null || it.appName.isEmpty()) ? it.name : it.appName;
            if (it.isSystemUi) title = title + "  " + getString(R.string.pm_systemui_tag);
            else if (it.isUnknown) title = title + "  [未知·系统级]";
            else if (it.isSystem) title = title + "  " + getString(R.string.pm_system_tag);
            nameView.setText(title);
            nameView.setTextSize(15);
            nameView.setTextColor(it.isSystem ? 0xFFB47814 : XtUi.colorText(ProcessManagerActivity.this));
            nameView.setTypeface(null, Typeface.BOLD);
            row.addView(nameView);

            TextView subView = new TextView(ProcessManagerActivity.this);
            subView.setText(it.name + "\nPID " + it.pid + "  |  内存 " + formatMem(it.rssKb)
                    + (it.isSystemUi ? "\n" + getString(R.string.pm_systemui_hint) : ""));
            subView.setTextSize(11);
            subView.setTextColor(XtUi.colorMuted(ProcessManagerActivity.this));
            row.addView(subView);

            return row;
        }
    }
}
