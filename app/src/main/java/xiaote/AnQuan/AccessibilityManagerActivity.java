package xiaote.AnQuan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import java.util.HashSet;
import xiaote.AnQuan.SwipeBackHelper;
import java.util.List;
import java.util.Set;

/**
 * 软件无障碍管理（含智能管理、批量管理）
 * 显示所有第三方应用，支持：禁止、允许保活、10分钟、锁屏禁止、智能管理
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
    private Button batchBtn;
    private Button applyBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        pm = getPackageManager();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.acc_mgr_title);
        title.setTextSize(20);
        title.setTextColor(itemTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 24, 0, 8);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText(R.string.acc_mgr_tip);
        tip.setTextColor(itemSubTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, 16);
        root.addView(tip);

        // 顶部批量管理按钮行
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER);
        topBar.setPadding(16, 4, 16, 8);

        batchBtn = new Button(this);
        batchBtn.setText(R.string.batch_manage);
        batchBtn.setTextSize(14);
        batchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                batchMode = !batchMode;
                selected.clear();
                if (batchMode) {
                    batchBtn.setText(R.string.batch_cancel);
                } else {
                    batchBtn.setText(R.string.batch_manage);
                }
                applyBtn.setEnabled(false);
                adapter.notifyDataSetChanged();
            }
        });
        topBar.addView(batchBtn, new LinearLayout.LayoutParams(-2, -2, 1));

        applyBtn = new Button(this);
        applyBtn.setText(R.string.batch_apply);
        applyBtn.setTextSize(14);
        applyBtn.setEnabled(false);
        applyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBatchDialog();
            }
        });
        topBar.addView(applyBtn, new LinearLayout.LayoutParams(-2, -2, 1));
        root.addView(topBar);

        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));

        Button backBtn = new Button(this);
        backBtn.setText(R.string.guide_back);
        backBtn.setTextSize(14);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(backBtn, new LinearLayout.LayoutParams(-1, -2));

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
                    applyBtn.setEnabled(!selected.isEmpty());
                    adapter.notifyDataSetChanged();
                } else {
                    showModeDialog(item);
                }
            }
        });

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
    }

    private void registerBackCallback() {
        getWindow().getDecorView().post(new java.lang.Runnable() {
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

    private int modeColor(int mode) {
        switch (mode) {
            case MODE_FORBIDDEN: return Color.argb(255, 255, 120, 120);
            case MODE_ALLOW: return Color.argb(255, 120, 255, 120);
            case MODE_ALLOW_10MIN: return Color.argb(255, 255, 200, 100);
            case MODE_LOCK_FORBID: return Color.argb(255, 150, 200, 255);
            case MODE_SMART: return Color.argb(255, 200, 150, 255);
            default: return Color.argb(180, 100, 100, 100);
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
        new AlertDialog.Builder(this)
                .setTitle(item.appName + "\n" + item.packageName)
                .setSingleChoiceItems(modes, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        saveMode(item, which == 5 ? MODE_DEFAULT : which);
                        dialog.dismiss();
                    }
                })
                .show();
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
        new AlertDialog.Builder(this)
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
                        if (applyBtn != null) applyBtn.setEnabled(false);
                        adapter.notifyDataSetChanged();
                        Toast.makeText(AccessibilityManagerActivity.this,
                                getString(R.string.batch_set_done, count), Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
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
        Toast.makeText(this, getString(R.string.mode_set_done, item.appName + " → " + modeText(mode)), Toast.LENGTH_SHORT).show();
    }

    private int itemTextColor() {
        return Color.argb(255, 30, 30, 30);
    }

    private int itemSubTextColor() {
        return Color.argb(150, 90, 90, 90);
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
                row.setPadding(20, 14, 20, 14);
            }
            AccItem item = items.get(position);
            boolean isSel = selected.contains(position);

            TextView nameView = new TextView(AccessibilityManagerActivity.this);
            nameView.setText((batchMode ? (isSel ? "☑ " : "☐ ") : "") + item.appName);
            nameView.setTextSize(15);
            nameView.setTextColor(itemTextColor());
            nameView.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(nameView);

            TextView subView = new TextView(AccessibilityManagerActivity.this);
            subView.setText(item.packageName + "\n" + getString(R.string.acc_strategy_hint, modeText(item.mode)));
            subView.setTextSize(11);
            subView.setTextColor(modeColor(item.mode));
            row.addView(subView);

            return row;
        }
    }
}