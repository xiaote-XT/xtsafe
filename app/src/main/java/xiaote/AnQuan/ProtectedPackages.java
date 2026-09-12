package xiaote.AnQuan;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 敏感应用包名列表（银行/支付等）
 * 支持：默认列表 + 自定义新增 + 移除默认（可加回）
 */
public class ProtectedPackages {

    // 敏感App列表由 App 启动时从 JSON 规则文件加载，此处置空
    public static String[] BUILTIN = {};

    public static void setBuiltin(String[] list) {
        if (list != null) {
            BUILTIN = list;
        }
    }

    private static final String PREFS = "dot_config";
    private static final String KEY_CUSTOM = "sensitive_custom";   // 用户自定义新增
    private static final String KEY_REMOVED = "sensitive_removed"; // 被移除的默认

    /** 判断是否是默认内置包名 */
    public static boolean isBuiltin(String pkg) {
        if (pkg == null) return false;
        for (String p : BUILTIN) {
            if (pkg.equals(p)) return true;
        }
        return false;
    }

    /** 获取完整敏感包名集合（默认 - 移除 + 自定义） */
    public static Set<String> getProtectedPackages(Context context) {
        Set<String> set = new HashSet<String>();
        for (String p : BUILTIN) {
            if (p != null && !p.isEmpty()) set.add(p);
        }
        set.removeAll(getListPrefs(context, KEY_REMOVED));
        set.addAll(getListPrefs(context, KEY_CUSTOM));
        return set;
    }

    /** 判断是否受保护（需包名在集合中） */
    public static boolean isProtected(Context context, String pkg) {
        return pkg != null && getProtectedPackages(context).contains(pkg);
    }

    /** 新增敏感包名（自定义） */
    public static void addProtected(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> custom = getListPrefs(context, KEY_CUSTOM);
        // 如果之前在 removed 中（默认被移除又加回），从 removed 去掉
        Set<String> removed = getListPrefs(context, KEY_REMOVED);
        removed.remove(pkg.trim());
        saveListPrefs(context, KEY_REMOVED, removed);
        custom.add(pkg.trim());
        saveListPrefs(context, KEY_CUSTOM, custom);
    }

    /** 移除敏感包名 */
    public static void removeProtected(Context context, String pkg) {
        if (pkg == null) return;
        if (isBuiltin(pkg)) {
            // 默认包名 → 标记为移除
            Set<String> removed = getListPrefs(context, KEY_REMOVED);
            removed.add(pkg);
            saveListPrefs(context, KEY_REMOVED, removed);
        } else {
            // 自定义包名 → 直接从自定义删除
            Set<String> custom = getListPrefs(context, KEY_CUSTOM);
            custom.remove(pkg);
            saveListPrefs(context, KEY_CUSTOM, custom);
        }
    }

    /** 恢复默认（从移除列表加回） */
    public static void restoreProtected(Context context, String pkg) {
        if (pkg == null) return;
        Set<String> removed = getListPrefs(context, KEY_REMOVED);
        removed.remove(pkg);
        saveListPrefs(context, KEY_REMOVED, removed);
    }

    /** 被移除的默认包名 */
    public static Set<String> getRemovedDefaults(Context context) {
        return getListPrefs(context, KEY_REMOVED);
    }

    /** 自定义新增包名 */
    public static Set<String> getCustomPackages(Context context) {
        return getListPrefs(context, KEY_CUSTOM);
    }

    private static Set<String> getListPrefs(Context context, String key) {
        Set<String> set = new HashSet<String>();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(key, "");
            if (raw != null && !raw.isEmpty()) {
                String[] arr = raw.split("[,，\\n]");
                for (String s : arr) {
                    String p = s.trim();
                    if (!p.isEmpty()) set.add(p);
                }
            }
        } catch (Exception e) {}
        return set;
    }

    private static void saveListPrefs(Context context, String key, Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String p : set) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, sb.toString()).apply();
    }
}