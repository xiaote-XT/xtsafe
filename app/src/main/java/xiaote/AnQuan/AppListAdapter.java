package xiaote.AnQuan;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 应用列表适配器
 * 提取自 XTSafeMainService.AppListAdapter
 */
public class AppListAdapter extends ArrayAdapter<AppInfo> {
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
        TextView tv;
        if (convertView instanceof TextView) tv = (TextView) convertView;
        else tv = new TextView(context);

        AppInfo ai = filteredData.get(position);
        boolean isVirus = virusPackages != null && virusPackages.contains(ai.packageName);
        boolean isHighlight = highlightPkg != null && highlightPkg.equals(ai.packageName);
        String prefix = isVirus ? "\u26A0 " : "";
        String mgrMark = ai.isManaged ? " [\u7BA1\u63A7]" : "";
        String accMark = ai.hasAccessibility ? " [\u65E0\u969C\u788D]" : "";
        String riskMark = isHighlight ? context.getString(R.string.risk_mark) : ""; // "[风险应用]"
        tv.setText(prefix + ai.name + accMark + mgrMark + riskMark + "\n" + ai.packageName);

        if (isHighlight) {
            tv.setTextColor(Color.argb(255, 255, 60, 60));
            tv.setBackgroundColor(Color.argb(120, 255, 0, 0));
        } else if (isVirus) {
            tv.setTextColor(Color.argb(255, 255, 200, 100));
            tv.setBackgroundColor(Color.argb(80, 255, 0, 0));
        } else if (ai.isManaged) {
            tv.setTextColor(Color.argb(255, 150, 200, 255));
            tv.setBackgroundColor(Color.argb(40, 50, 130, 255));
        } else if (ai.hasAccessibility) {
            tv.setTextColor(Color.argb(255, 150, 255, 150));
            tv.setBackgroundColor(Color.argb(40, 0, 180, 0));
        } else {
            tv.setTextColor(colorHelper.getTextColor());
            tv.setBackgroundColor(colorHelper.getListItemBgColor());
        }
        tv.setTextSize(15);
        tv.setPadding(24, 16, 24, 16);
        tv.setLines(2);
        tv.setTypeface(null, isVirus || isHighlight || ai.hasAccessibility || ai.isManaged ? Typeface.BOLD : Typeface.NORMAL);
        return tv;
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
