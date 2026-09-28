package xiaote.AnQuan;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 用户白名单包名。
 *
 * 语义：白名单应用
 *   1. 安全扫描时直接跳过（scanApk 开头判断）；
 *   2. 全屏覆盖检测时视为排除项（OverlayDetector.isExcluded 判断）。
 *
 * 与 scan_rules.json 里的 whitelist（包名 + 签名 MD5 双匹配）不是一回事，
 * 那个是规则层的官方白名单，这里是用户手动维护的免打扰列表，
 * 只看包名，不看签名。
 *
 * 存储：dot_config / whitelist_packages，逗号分隔。
 */
public final class WhitelistPackages {

    public static final String PREFS = "dot_config";
    public static final String KEY = "whitelist_packages";

    private WhitelistPackages() {}

    /** 获取全部白名单包名 */
    public static Set<String> get(Context context) {
        Set<String> set = new HashSet<String>();
        if (context == null) return set;
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = p.getString(KEY, "");
            if (raw != null && !raw.isEmpty()) {
                String[] arr = raw.split("[,，\\n]");
                for (String s : arr) {
                    String v = s.trim();
                    if (!v.isEmpty()) set.add(v);
                }
            }
        } catch (Exception ignored) {}
        return set;
    }

    /** 是否在白名单中 */
    public static boolean isWhitelisted(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (context == null) return false;
        try {
            return get(context).contains(pkg.trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** 加入白名单 */
    public static void add(Context context, String pkg) {
        if (context == null || pkg == null || pkg.trim().isEmpty()) return;
        Set<String> set = get(context);
        set.add(pkg.trim());
        save(context, set);
    }

    /** 移除白名单 */
    public static void remove(Context context, String pkg) {
        if (context == null || pkg == null) return;
        Set<String> set = get(context);
        set.remove(pkg.trim());
        save(context, set);
    }

    private static void save(Context context, Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String p : set) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, sb.toString()).apply();
        } catch (Exception ignored) {}
    }
}
