package xiaote.AnQuan;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import xiaote.xtui.XtDialog;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * 无障碍管理（含智能管理、批量管理）。
 *
 * 显示所有申请了无障碍服务的应用，支持：
 *   禁止 / 允许保活 / 10 分钟内允许 / 锁屏后禁止 / 智能管理 / 恢复默认。
 *
 * 已按 xtui 规范重写：骨架 XtUi.column，标题 XtUi.title，
 * 顶部批量按钮 XtUi.button，列表分隔线走 XtUi.colorBorder，
 * 模式色彩保留语义色（禁止红 / 允许绿 / 10 分钟橙 / 锁屏蓝 / 智能紫），
 * 弹窗走 XtDialog（单选列表已做深色适配），轻提示走 XtToast。
 *
 * 注意：本页需要列表撑满剩余空间，因此直接 setContentView(root)，
 * 不用 XtUi.scroll。
 */
public class AccessibilityManagerActivity extends BaseActivity {

    public static final int MODE_FORBIDDEN = 0;    // 禁止（强制拒绝）
    public static final int MODE_ALLOW = 1;        // 允许（自动保活）
    public static final int MODE_ALLOW_10MIN = 2;  // 10分钟内允许
    public static final int MODE_LOCK_FORBID = 3;  // 锁屏后禁止
    public static final int MODE_SMART = 4;        // 智能管理（进入保护应用时关闭）
    public static final int MODE_DEFAULT = -1;     // 默认

    private static final String PREFS = "dot_config";

    private SharedPreferences prefs;
    private PackageManager pm;
    private List<AccItem> items = new ArrayList<AccItem>();
    private AccAdapter adapter;
    private boolean batchMode = false;
    private Set<Integer> selected = new HashSet<Integer>();
    private TextView batchBtn;
    private TextView applyBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        pm = getPackageManager();

        // 界面模式分流：ui_mode=h5 时交给 H5 壳，原生界面不构建。默认 native。
        if ("h5".equals(prefs.getString("ui_mode", "native"))) {
            try { startActivity(new Intent(this, H5AccActivity.class)); } catch (Throwable ignored) {}
            finish();
            return;
        }

        LinearLayout root = XtUi.column(this);
        // 列表要撑满剩余空间，去掉 column 默认的整体 padding，只让标题区自带留白
        root.setPadding(0, 0, 0, 0);

        TextView title = XtUi.title(this, getString(R.string.acc_mgr_title));
        title.setPadding(0, XtUi.dp(this, 24), 0, XtUi.dp(this, 4));
        root.addView(title);
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this, getString(R.string.acc_mgr_tip));
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(XtUi.dp(this, 16), 0, XtUi.dp(this, 16), XtUi.dp(this, 12));
        root.addView(tip);

        // 顶部批量管理按钮行
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER);
        topBar.setPadding(XtUi.dp(this, 12), 0, XtUi.dp(this, 12), XtUi.dp(this, 8));

        batchBtn = XtUi.button(this, getString(R.string.batch_manage), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                batchMode = !batchMode;
                selected.clear();
                batchBtn.setText(batchMode ? R.string.batch_cancel : R.string.batch_manage);
                applyBtn.setEnabled(false);
                applyBtn.setAlpha(0.4f);
                adapter.notifyDataSetChanged();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1f);
        blp.rightMargin = XtUi.dp(this, 4);
        topBar.addView(batchBtn, blp);

        applyBtn = XtUi.button(this, getString(R.string.batch_apply), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBatchDialog();
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, -2, 1f);
        alp.leftMargin = XtUi.dp(this, 4);
        topBar.addView(applyBtn, alp);
        applyBtn.setEnabled(false);
        applyBtn.setAlpha(0.4f);
        root.addView(topBar);

        ListView listView = new ListView(this);
        listView.setBackgroundColor(XtUi.colorBg(this));
        listView.setDivider(new ColorDrawable(XtUi.colorBorder(this)));
        listView.setDividerHeight(Math.max(1, XtUi.dp(this, XtUi.borderDp)));
        listView.setCacheColorHint(0x00000000);
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));

        loadItems();
        adapter = new AccAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                AccItem item = items.get(position);
                if (batchMode) {
                    // 多选模式：切换选中
                    if (selected.contains(position)) selected.remove(position);
                    else selected.add(position);
                    boolean has = !selected.isEmpty();
                    applyBtn.setEnabled(has);
                    applyBtn.setAlpha(has ? 1f : 0.4f);
                    adapter.notifyDataSetChanged();
                } else {
                    showModeDialog(item);
                }
            }
        });

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);
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

    // 只列出申请了无障碍服务的应用（注册了无障碍服务，无论是否已开启）
    private void loadItems() {
        items.clear();
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            java.util.List<android.accessibilityservice.AccessibilityServiceInfo> services =
                    am.getInstalledAccessibilityServiceList();
            Set<String> added = new HashSet<String>();
            if (services != null) {
                for (android.accessibilityservice.AccessibilityServiceInfo info : services) {
                    if (info == null || info.getResolveInfo() == null) continue;
                    String pkg = info.getResolveInfo().serviceInfo.packageName;
                    if (pkg == null || pkg.equals(getPackageName())) continue;
                    if (added.contains(pkg)) continue;
                    added.add(pkg);
                    AccItem item = new AccItem();
                    item.packageName = pkg;
                    try {
                        ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
                        item.appName = pm.getApplicationLabel(app).toString();
                    } catch (Exception e) {
                        item.appName = pkg;
                    }
                    item.mode = prefs.getInt("acc_mode_" + pkg, MODE_DEFAULT);
                    items.add(item);
                }
            }
        } catch (Exception e) {}
    }

    private String modeText(int mode) {
        switch (mode) {
            case MODE_FORBIDDEN: return getString(R.string.mode_forbidden);
            case MODE_ALLOW: return getString(R.string.mode_allow);
            case MODE_ALLOW_10MIN: return getString(R.string.mode_allow_10min);
            case MODE_LOCK_FORBID: return getString(R.string.mode_lock_forbid);
            case MODE_SMART: return getString(R.string.mode_smart);
            default: return getString(R.string.mode_default);
        }
    }

    /** 模式语义色：浅色模式下压暗一档，保证在浅底上可读 */
    private int modeColor(int mode) {
        boolean dark = XtUi.isDark(this);
        switch (mode) {
            case MODE_FORBIDDEN:
                return dark ? Color.argb(255, 255, 120, 120) : Color.argb(255, 200, 40, 40);
            case MODE_ALLOW:
                return dark ? Color.argb(255, 120, 255, 120) : Color.argb(255, 40, 150, 60);
            case MODE_ALLOW_10MIN:
                return dark ? Color.argb(255, 255, 200, 100) : Color.argb(255, 190, 130, 20);
            case MODE_LOCK_FORBID:
                return dark ? Color.argb(255, 150, 200, 255) : Color.argb(255, 40, 110, 190);
            case MODE_SMART:
                return dark ? Color.argb(255, 200, 150, 255) : Color.argb(255, 120, 70, 190);
            default:
                return XtUi.colorMuted(this);
        }
    }

    // 设置单个应用模式
    private void showModeDialog(final AccItem item) {
        final String[] modes = {
            getString(R.string.mode_forbidden),
            getString(R.string.mode_allow),
            getString(R.string.mode_allow_10min),
            getString(R.string.mode_lock_forbid),
            getString(R.string.mode_smart),
            getString(R.string.mode_default)
        };
        int checked = item.mode == MODE_DEFAULT ? 5 : item.mode;
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(item.appName + "\n" + item.packageName)
                .setSingleChoiceItems(modes, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        saveMode(item, which == 5 ? MODE_DEFAULT : which);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    // 批量设置模式
    private void showBatchDialog() {
        final String[] modes = {
            getString(R.string.mode_forbidden),
            getString(R.string.mode_allow),
            getString(R.string.mode_allow_10min),
            getString(R.string.mode_lock_forbid),
            getString(R.string.mode_smart),
            getString(R.string.batch_restore_default)
        };
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.batch_set_title, selected.size()))
                .setItems(modes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int mode = which == 5 ? MODE_DEFAULT : which;
                        for (Integer pos : selected) {
                            if (pos < 0 || pos >= items.size()) continue;
                            saveMode(items.get(pos), mode);
                        }
                        int count = selected.size();
                        selected.clear();
                        batchMode = false;
                        if (batchBtn != null) batchBtn.setText(R.string.batch_manage);
                        if (applyBtn != null) {
                            applyBtn.setEnabled(false);
                            applyBtn.setAlpha(0.4f);
                        }
                        adapter.notifyDataSetChanged();
                        XtToast.show(AccessibilityManagerActivity.this,
                                getString(R.string.batch_set_done, count));
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    private void saveMode(AccItem item, int mode) {
        SharedPreferences.Editor ed = prefs.edit();
        if (mode == MODE_DEFAULT) {
            ed.remove("acc_mode_" + item.packageName);
            ed.remove("acc_mode_" + item.packageName + "_start");
        } else {
            ed.putInt("acc_mode_" + item.packageName, mode);
            if (mode == MODE_ALLOW_10MIN) {
                ed.putLong("acc_mode_" + item.packageName + "_start", System.currentTimeMillis());
            }
        }
        ed.apply();
        item.mode = mode;
        adapter.notifyDataSetChanged();
        XtToast.show(this, getString(R.string.mode_set_done,
                item.appName + " → " + modeText(mode)));
    }

    class AccItem {
        String packageName;
        String appName;
        int mode;
    }

    class AccAdapter extends BaseAdapter {
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
                row = new LinearLayout(AccessibilityManagerActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(XtUi.dp(AccessibilityManagerActivity.this, 16),
                        XtUi.dp(AccessibilityManagerActivity.this, 14),
                        XtUi.dp(AccessibilityManagerActivity.this, 16),
                        XtUi.dp(AccessibilityManagerActivity.this, 14));
            }
            AccItem item = items.get(position);
            boolean isSel = selected.contains(position);
            if (batchMode && isSel) {
                row.setBackgroundColor(XtUi.isDark(AccessibilityManagerActivity.this)
                        ? 0x33FF6A00 : 0x1FFF6A00);
            } else {
                row.setBackgroundColor(Color.TRANSPARENT);
            }

            TextView nameView = new TextView(AccessibilityManagerActivity.this);
            nameView.setText((batchMode ? (isSel ? "☑ " : "☐ ") : "") + item.appName);
            nameView.setTextSize(15);
            nameView.setTextColor(XtUi.colorText(AccessibilityManagerActivity.this));
            nameView.setTypeface(null, Typeface.BOLD);
            row.addView(nameView);

            TextView subView = new TextView(AccessibilityManagerActivity.this);
            subView.setText(item.packageName + "\n"
                    + getString(R.string.acc_strategy_hint, modeText(item.mode)));
            subView.setTextSize(11);
            subView.setTextColor(modeColor(item.mode));
            row.addView(subView);

            return row;
        }
    }
}
