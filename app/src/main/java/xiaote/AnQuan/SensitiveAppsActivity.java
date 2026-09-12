package xiaote.AnQuan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.HashSet;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 敏感App管理：自定义新增包名、移除/恢复默认
 */
public class SensitiveAppsActivity extends BaseActivity {

    private PackageManager pm;
    private List<String> items = new ArrayList<String>();
    private List<String> filtered = new ArrayList<String>();
    private SetAdapter adapter;
    private EditText searchInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.sensitive_title);
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 24, 0, 8);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText(R.string.sensitive_tip);
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, 12);
        root.addView(tip);

        // 搜索+新增输入行
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(16, 8, 16, 8);

        searchInput = new EditText(this);
        searchInput.setHint(R.string.sensitive_hint);
        searchInput.setTextColor(getTextColor());
        searchInput.setHintTextColor(Color.argb(150, 120, 120, 120));
        searchInput.setTextSize(12);
        searchInput.setSingleLine(true);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        addRow.addView(searchInput, new LinearLayout.LayoutParams(0, -2, 1));

        Button addBtn = new Button(this);
        addBtn.setText(R.string.add);
        addBtn.setTextSize(14);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pkg = searchInput.getText().toString().trim();
                if (pkg.isEmpty()) {
                    Toast.makeText(SensitiveAppsActivity.this, R.string.enter_pkg, Toast.LENGTH_SHORT).show();
                    return;
                }
                ProtectedPackages.addProtected(SensitiveAppsActivity.this, pkg);
                searchInput.setText("");
                reload();
                Toast.makeText(SensitiveAppsActivity.this, getString(R.string.added_pkg, pkg), Toast.LENGTH_SHORT).show();
            }
        });
        addRow.addView(addBtn);
        root.addView(addRow);

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

        reload();
        adapter = new SetAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                final String pkg = filtered.get(position);
                if (pkg.startsWith("───")) return false; // 分隔行不处理
                boolean isBuiltin = ProtectedPackages.isBuiltin(pkg);
                final boolean isRemoved = ProtectedPackages.getRemovedDefaults(SensitiveAppsActivity.this).contains(pkg);
                if (isBuiltin) {
                    if (isRemoved) {
                        // 已移除的默认 → 恢复
                        new AlertDialog.Builder(SensitiveAppsActivity.this)
                                .setTitle(R.string.restore_default)
                                .setMessage(getString(R.string.restore_default_msg, pkg))
                                .setPositiveButton(getString(R.string.restore), new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        ProtectedPackages.restoreProtected(SensitiveAppsActivity.this, pkg);
                                        reload();
                                    }
                                })
                                .setNegativeButton(getString(R.string.cancel), null).show();
                    } else {
                        // 默认包名 → 移除
                        new AlertDialog.Builder(SensitiveAppsActivity.this)
                                .setTitle(getString(R.string.remove))
                                .setMessage(getString(R.string.remove_sensitive_msg, pkg))
                                .setPositiveButton(getString(R.string.remove), new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        ProtectedPackages.removeProtected(SensitiveAppsActivity.this, pkg);
                                        reload();
                                    }
                                })
                                .setNegativeButton(getString(R.string.cancel), null).show();
                    }
                } else {
                    // 自定义包名 → 直接删除
                    new AlertDialog.Builder(SensitiveAppsActivity.this)
                            .setTitle(getString(R.string.delete))
                            .setMessage(getString(R.string.delete_custom_msg, pkg))
                            .setPositiveButton(getString(R.string.delete), new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    ProtectedPackages.removeProtected(SensitiveAppsActivity.this, pkg);
                                    reload();
                                }
                            })
                            .setNegativeButton(getString(R.string.cancel), null).show();
                }
                return true;
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

    private void reload() {
        items.clear();
        Set<String> all = ProtectedPackages.getProtectedPackages(this);
        items.addAll(all);
        Set<String> removed = ProtectedPackages.getRemovedDefaults(this);
        if (!removed.isEmpty()) {
            items.add(getString(R.string.removed_defaults_header));
            items.addAll(removed);
        }
        applyFilter();
    }

    private void applyFilter() {
        filtered.clear();
        String q = searchInput != null ? searchInput.getText().toString().toLowerCase() : "";
        for (String item : items) {
            if (q.isEmpty() || item.toLowerCase().contains(q)) {
                filtered.add(item);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private String displayName(String pkg) {
        try {
            ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(app).toString() + "  " + pkg;
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

    class SetAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public Object getItem(int position) { return filtered.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            String item = filtered.get(position);
            if (item.startsWith("───")) {
                TextView divider = new TextView(SensitiveAppsActivity.this);
                divider.setText(item);
                divider.setTextSize(12);
                divider.setTextColor(Color.argb(150, 120, 120, 120));
                divider.setGravity(Gravity.CENTER);
                divider.setPadding(0, 12, 0, 12);
                return divider;
            }
            // 应用名（粗体）+ 包名（淡一点）
            LinearLayout row = new LinearLayout(SensitiveAppsActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(20, 12, 20, 12);

            String appName = item;
            String pkgName = item;
            try {
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(item, 0);
                appName = pm.getApplicationLabel(ai).toString();
            } catch (Exception e) {}

            TextView nameView = new TextView(SensitiveAppsActivity.this);
            nameView.setText(appName);
            nameView.setTextSize(15);
            nameView.setTextColor(getTextColor());
            nameView.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(nameView);

            TextView pkgView = new TextView(SensitiveAppsActivity.this);
            pkgView.setText(pkgName);
            pkgView.setTextSize(11);
            pkgView.setTextColor(getSecondaryTextColor());
            row.addView(pkgView);

            return row;
        }
    }
}