package xiaote.AnQuan;

import android.app.Activity;
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
import java.util.List;
import java.util.Set;

/**
 * 管控列表：展示被管控的应用，可移除管控
 */
public class ManagedListActivity extends BaseActivity {

    private PackageManager pm;
    private List<ManagedItem> items = new ArrayList<ManagedItem>();
    private ManagedAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.managed_title);
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 24, 0, 8);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText(R.string.managed_tip);
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, 16);
        root.addView(tip);

        // 新增管控按钮
        Button addBtn = new Button(this);
        addBtn.setText(R.string.add_managed);
        addBtn.setTextSize(14);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddManagedDialog();
            }
        });
        root.addView(addBtn, new LinearLayout.LayoutParams(-1, -2));

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
        adapter = new ManagedAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                final ManagedItem item = items.get(position);
                new android.app.AlertDialog.Builder(ManagedListActivity.this)
                        .setTitle(R.string.remove_managed_title)
                        .setMessage(getString(R.string.remove_managed_msg, item.appName))
                        .setPositiveButton(getString(R.string.remove), new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                VirusPackages.removeManagedPackage(ManagedListActivity.this, item.packageName);
                                items.remove(item);
                                adapter.notifyDataSetChanged();
                                Toast.makeText(ManagedListActivity.this, getString(R.string.removed_managed, item.appName), Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show();
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

    private void loadItems() {
        items.clear();
        Set<String> managed = VirusPackages.getManagedPackages(this);
        for (String pkg : managed) {
            ManagedItem item = new ManagedItem();
            item.packageName = pkg;
            try {
                ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
                item.appName = pm.getApplicationLabel(app).toString();
            } catch (Exception e) {
                item.appName = pkg;
            }
            items.add(item);
        }
    }

    // 弹出所有第三方应用供选择加入管控
    private void showAddManagedDialog() {
        try {
            final List<android.content.pm.ApplicationInfo> apps = new ArrayList<android.content.pm.ApplicationInfo>();
            final List<String> names = new ArrayList<String>();
            Set<String> managed = VirusPackages.getManagedPackages(this);
            Set<String> virus = VirusPackages.getVirusPackages(this);
            final List<android.content.pm.ApplicationInfo> appList = pm.getInstalledApplications(0);
            for (android.content.pm.ApplicationInfo app : appList) {
                if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (app.packageName.equals(getPackageName())) continue;
                if (managed.contains(app.packageName)) continue;
                if (virus.contains(app.packageName)) continue;
                apps.add(app);
                names.add(pm.getApplicationLabel(app).toString() + "  " + app.packageName);
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.select_managed_title)
                    .setItems(names.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            String pkg = apps.get(which).packageName;
                            VirusPackages.addManagedPackage(ManagedListActivity.this, pkg);
                            loadItems();
                            adapter.notifyDataSetChanged();
                            Toast.makeText(ManagedListActivity.this, getString(R.string.added_managed, pkg), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        } catch (Exception e) {}
    }

    private int getTextColor() {
        return Color.argb(255, 30, 30, 30);
    }

    private int getSecondaryTextColor() {
        return Color.argb(150, 90, 90, 90);
    }

    class ManagedItem {
        String packageName;
        String appName;
    }

    class ManagedAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(ManagedListActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(20, 14, 20, 14);
            }
            ManagedItem item = items.get(position);
            TextView nameView = new TextView(ManagedListActivity.this);
            nameView.setText(item.appName);
            nameView.setTextSize(15);
            nameView.setTextColor(getTextColor());
            nameView.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(nameView);

            TextView subView = new TextView(ManagedListActivity.this);
            subView.setText(item.packageName + "\n" + getString(R.string.managed_hint));
            subView.setTextSize(11);
            subView.setTextColor(Color.argb(255, 40, 100, 200));
            row.addView(subView);
            return row;
        }
    }
}