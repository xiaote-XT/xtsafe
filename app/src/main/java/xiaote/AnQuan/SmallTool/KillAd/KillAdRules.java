package xiaote.AnQuan.SmallTool.KillAd;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 广告域名规则。
 *
 * 规则只来自两个来源，合并使用：
 *   1. filesDir/killad_rules.txt
 *      —— KillAdRuleUpdater 从 AdGuard/uBlock 规则在线解析后按行写入。
 *         只加载「按当前解析格式版本生成」的文件；旧版本文件是旧解析逻辑
 *         写出来的，可能含被错误降级成域名匹配的精准规则，一律忽略。
 *   2. SharedPreferences 中的用户自定义域名
 *
 * 没有内置硬编码列表。两个来源都为空时不拦截任何域名，
 * 绝不会出现「规则没下下来反而拦一堆」的情况。
 *
 * 必应相关域永不拦截，优先级高于一切规则源。
 */
public final class KillAdRules {

    public static final String PREFS = "dot_config";
    public static final String KEY_ENABLED = "killad_enabled";
    public static final String KEY_CUSTOM = "killad_custom_domains";
    public static final String KEY_UPSTREAM = "killad_upstream";
    public static final String DEFAULT_UPSTREAM = "223.5.5.5";

    /** 在线规则文件（KillAdRuleUpdater 写入） */
    private static final String RULES_FILE = "killad_rules.txt";

    /** 缓存时长。在线规则动辄数万条，重读 + 归一化开销不小，
     *  规则更新与自定义域名保存都会调用 invalidate() 立即生效。 */
    private static final long CACHE_MS = 300000L;

    /** 永不拦截的域名后缀（含所有子域） */
    private static final String[] ALLOW_SUFFIX = {
            "bing.com",
            "bing.net",
            "bingapis.com",
            "bingj.com",
            "msn.com"
    };

    private static volatile Set<String> sCache = Collections.emptySet();
    private static volatile long sLoadAt = 0L;
    /** 是否已加载过一次。空规则集也算已加载，否则每次 DNS 查询都要重读文件 */
    private static volatile boolean sLoaded = false;

    private KillAdRules() {}

    /** 规则变更后调用，清掉缓存立即生效 */
    public static void invalidate() {
        sCache = Collections.emptySet();
        sLoadAt = 0L;
        sLoaded = false;
    }

    public static Set<String> getRules(Context ctx) {
        long now = System.currentTimeMillis();
        Set<String> cached = sCache;
        // 空集合也要走缓存：不能因为「没有规则」就每次查询都重读文件
        if (sLoaded && now - sLoadAt < CACHE_MS) return cached;

        Set<String> set = new HashSet<String>();

        // 1. AdGuard 在线规则（每行一条域名）
        //    只有按当前解析格式版本生成的文件才加载，
        //    旧格式文件可能含被误放大的精准规则，必须忽略。
        try {
            File online = new File(ctx.getFilesDir(), RULES_FILE);
            if (online.exists() && KillAdRuleUpdater.hasLocalRules(ctx)) {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        new FileInputStream(online), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    String d = normalize(line);
                    if (!d.isEmpty()) set.add(d);
                }
                r.close();
            }
        } catch (Exception ignored) {}

        // 2. 用户自定义域名
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = p.getString(KEY_CUSTOM, "");
            if (raw != null && !raw.isEmpty()) {
                String[] parts = raw.split("[,，;；\\s]+");
                for (String s : parts) {
                    String d = normalize(s);
                    if (!d.isEmpty()) set.add(d);
                }
            }
        } catch (Exception ignored) {}

        Set<String> frozen = Collections.unmodifiableSet(set);
        sCache = frozen;
        sLoadAt = now;
        sLoaded = true;
        return frozen;
    }

    /**
     * 域名是否命中拦截列表。
     * 命中规则：完全相等，或以「.规则」结尾（即该域名及其所有子域）。
     * 必应相关域一律放行，优先于任何规则源。
     */
    public static boolean isBlocked(Context ctx, String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase();
        if (n.isEmpty()) return false;
        if (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        if (isAlwaysAllowed(n)) return false;
        Set<String> rules = getRules(ctx);
        if (rules.isEmpty()) return false;
        if (rules.contains(n)) return true;
        int i = n.indexOf('.');
        while (i >= 0 && i + 1 < n.length()) {
            if (rules.contains(n.substring(i + 1))) return true;
            i = n.indexOf('.', i + 1);
        }
        return false;
    }

    /** 永不拦截的域名（含所有子域） */
    private static boolean isAlwaysAllowed(String n) {
        if (n == null || n.isEmpty()) return false;
        for (String s : ALLOW_SUFFIX) {
            if (n.equals(s)) return true;
            if (n.endsWith("." + s)) return true;
        }
        return false;
    }

    public static String getUpstream(Context ctx) {
        try {
            String v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_UPSTREAM, DEFAULT_UPSTREAM);
            if (v != null) {
                v = v.trim();
                if (!v.isEmpty()) return v;
            }
        } catch (Exception ignored) {}
        return DEFAULT_UPSTREAM;
    }

    public static String getCustomDomains(Context ctx) {
        try {
            String v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_CUSTOM, "");
            return v == null ? "" : v;
        } catch (Exception ignored) {
            return "";
        }
    }

    public static void setCustomDomains(Context ctx, String raw) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_CUSTOM, raw == null ? "" : raw.trim()).apply();
        } catch (Exception ignored) {}
        invalidate();
    }

    public static void setUpstream(Context ctx, String host) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_UPSTREAM, host == null ? DEFAULT_UPSTREAM : host.trim()).apply();
        } catch (Exception ignored) {}
    }

    // ==================== 内部 ====================

    /** 归一化：小写、去通配前缀、去首尾点；只保留至少两级域名的条目 */
    private static String normalize(String raw) {
        if (raw == null) return "";
        String d = raw.trim().toLowerCase();
        if (d.isEmpty()) return "";
        if (d.startsWith("*.")) d = d.substring(2);
        else if (d.startsWith("*")) d = d.substring(1);
        while (d.startsWith(".")) d = d.substring(1);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        if (d.isEmpty() || d.indexOf('.') < 0) return "";
        return d;
    }
}
