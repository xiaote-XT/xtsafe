package xiaote.AnQuan;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 冻结应用名单（用户手动维护）。
 *
 * 与「管控列表」「敏感App」「白名单」并列，存于 dot_config/frozen_packages，
 * 逗号分隔。
 *
 * 语义：
 *   1. 加入名单时立即执行 pm disable --user 0 冻结；
 *   2. 移出名单时执行 pm enable --user 0 解冻；
 *   3. 执行「音量触发安全操作」时，名单内的应用会一并纳入冻结目标；
 *   4. 白名单应用不参与冻结（SafeActionManager 统一过滤）。
 */
public final class FrozenPackages {

    public static final String PREFS = "dot_config";
    public static final String KEY = "frozen_packages";

    private FrozenPackages() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 全部冻结包名 */
    public static Set<String> get(Context ctx) {
        Set<String> set = new HashSet<String>();
        if (ctx == null) return set;
        try {
            String raw = prefs(ctx).getString(KEY, "");
            if (raw != null && !raw.isEmpty()) {
                String[] arr = raw.split("[,，\\n]");
                for (String s : arr) {
                    String v = s.trim();
                    if (!v.isEmpty()) set.add(v);
                }
            }
        } catch (Throwable ignored) {}
        return set;
    }

    public static boolean isFrozen(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return get(ctx).contains(pkg.trim());
    }

    /** 加入冻结名单（不执行冻结，冻结由调用方负责） */
    public static void add(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.trim().isEmpty()) return;
        Set<String> set = get(ctx);
        set.add(pkg.trim());
        save(ctx, set);
    }

    /** 移出冻结名单（不解冻，解冻由调用方负责） */
    public static void remove(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return;
        Set<String> set = get(ctx);
        set.remove(pkg.trim());
        save(ctx, set);
    }

    private static void save(Context ctx, Set<String> set) {
        if (ctx == null) return;
        StringBuilder sb = new StringBuilder();
        for (String p : set) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        try { prefs(ctx).edit().putString(KEY, sb.toString()).apply(); } catch (Throwable ignored) {}
    }
}
