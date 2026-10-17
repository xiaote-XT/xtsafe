package xiaote.AnQuan;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import xiaote.xtui.XtUi;

/**
 * 应用列表适配器（XTUI 版）。
 *
 * 每一行统一走 XtUi.itemRow：44dp 圆角图标 + 应用名 + 包名与状态标记，
 * 不再用单个 TextView 拼字符串 —— 那样行高参差、无图标、配色也不跟主题。
 *
 * 状态标记放在副标题里，用文字表达（病毒包名 / 管控 / 无障碍 / 风险应用），
 * 颜色只用在标题上（风险、病毒标红），其余保持正常文字色，避免整行花掉。
 *
 * 图标从 PackageManager 读取并按包名缓存：ListView 滚动时反复取图标开销不小，
 * 缓存后同一包名只取一次。targetSdk 26 不受包可见性限制，能取全。
 */
public class AppListAdapter extends ArrayAdapter<AppInfo> {

    /** 图标缓存：包名 → 图标，跨实例复用，卸载列表反复打开时不必重取 */
    private static final Map<String, Drawable> sIconCache = new HashMap<String, Drawable>();

    private final Context context;
    private final ColorHelper colorHelper;
    private final Set<String> virusPackages;
    private final List<AppInfo> originalData;
    private final List<AppInfo> filteredData;
    private final Filter filter;
    private boolean showManagedOnly = false;
    public String highlightPkg = null; // 需要醒目标记的风险应用包名

    public AppListAdapter(Context context, ColorHelper colorHelper, Set<String> virusPackages, List<AppInfo> data) {
        super(context, android.R.layout.simple_list_item_1, data);
        this.context = context;
        this.colorHelper = colorHelper;
        this.virusPackages = virusPackages;
        this.originalData = new ArrayList<AppInfo>(data);
        this.filteredData = new ArrayList<AppInfo>(data);
        this.filter = new AppFilter();
    }

    public void setShowManagedOnly(boolean managedOnly) {
        this.showManagedOnly = managedOnly;
        getFilter().filter("");
    }

    @Override
    public int getCount() { return filteredData.size(); }

    @Override
    public AppInfo getItem(int position) { return filteredData.get(position); }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        AppInfo ai = filteredData.get(position);
        boolean isVirus = virusPackages != null && virusPackages.contains(ai.packageName);
        boolean isHighlight = highlightPkg != null && highlightPkg.equals(ai.packageName);

        // 副标题：包名 + 状态标记，一行一条，用 · 分隔
        StringBuilder sub = new StringBuilder(ai.packageName);
        if (isHighlight) sub.append("\n风险应用");
        else if (isVirus) sub.append("\n病毒包名");
        if (ai.hasAccessibility) sub.append(" · 无障碍");
        if (ai.isManaged) sub.append(" · 管控");

        LinearLayout row = XtUi.itemRow(context, iconOf(ai.packageName), ai.name, sub.toString(), null);

        // 标题着色：风险 / 病毒标红，其余保持默认文字色
        if (isHighlight || isVirus) {
            try {
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                TextView title = (TextView) col.getChildAt(0);
                title.setTextColor(0xFFFF3C3C);
            } catch (Throwable ignored) {}
        }
        return row;
    }

    /** 取应用图标，带包名缓存；取不到返回 null（itemRow 会用卡色底板兜住） */
    private Drawable iconOf(String pkg) {
        if (pkg == null) return null;
        Drawable cached = sIconCache.get(pkg);
        if (cached != null) return cached;
        try {
            PackageManager pm = context.getPackageManager();
            Drawable d = pm.getApplicationIcon(pkg);
            if (d != null) sIconCache.put(pkg, d);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Filter getFilter() { return filter; }

    private class AppFilter extends Filter {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults results = new FilterResults();
            List<AppInfo> filtered = new ArrayList<AppInfo>();
            if (constraint == null || constraint.length() == 0) {
                if (showManagedOnly) {
                    for (AppInfo ai : originalData) {
                        if (ai.isManaged || (virusPackages != null && virusPackages.contains(ai.packageName))) filtered.add(ai);
                    }
                } else {
                    filtered.addAll(originalData);
                }
            } else {
                String q = constraint.toString().toLowerCase();
                for (AppInfo ai : originalData) {
                    boolean match = ai.name.toLowerCase().contains(q) || ai.packageName.toLowerCase().contains(q);
                    if (!match) continue;
                    if (showManagedOnly) {
                        if (ai.isManaged || (virusPackages != null && virusPackages.contains(ai.packageName))) filtered.add(ai);
                    } else {
                        filtered.add(ai);
                    }
                }
            }
            results.values = filtered;
            results.count = filtered.size();
            return results;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            filteredData.clear();
            filteredData.addAll((List<AppInfo>) results.values);
            notifyDataSetChanged();
        }
    }
}
