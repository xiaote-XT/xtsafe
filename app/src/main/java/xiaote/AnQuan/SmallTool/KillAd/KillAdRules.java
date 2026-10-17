package xiaote.AnQuan.SmallTool.KillAd;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 广告域名规则。
 *
 * 规则来源三部分，合并使用：
 *
 *   1. assets/killad/ 内置规则 —— 本地随 APK 分发，不依赖网络，离线可用：
 *        blacklist.txt           精确域名，命中该域及其所有子域
 *        blacklist_wildcard.txt  通配域名，支持 * 与 ? 通配
 *        url_blacklist.txt       路径规则；DNS 层只能看到域名，看不到路径，
 *                                含斜杠的行无法匹配，只取其中不含斜杠的域名行
 *
 *   2. filesDir/killad_rules.txt
 *      —— KillAdRuleUpdater 从 AdGuard/uBlock 规则在线解析后按行写入。
 *         只加载「按当前解析格式版本生成」的文件；旧版本文件是旧解析逻辑
 *         写出来的，可能含被错误降级成域名匹配的精准规则，一律忽略。
 *
 *   3. SharedPreferences 中的用户自定义域名
 *
 * 三部分都为空时不拦截任何域名，绝不会出现「规则没下下来反而拦一堆」的情况。
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

    /** 导入规则文件（用户从本地 txt 导入，与在线规则、自定义规则并行生效） */
    private static final String IMPORTED_FILE = "killad_imported.txt";

    /** 内置规则目录（assets 下）与三份文件名 */
    private static final String ASSET_DIR = "killad";
    private static final String ASSET_EXACT = "blacklist.txt";
    private static final String ASSET_WILDCARD = "blacklist_wildcard.txt";
    private static final String ASSET_URL = "url_blacklist.txt";

    /** 缓存时长。规则动辄数万条，重读 + 归一化开销不小，
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

    private static volatile RuleSet sRules = null;
    private static volatile long sLoadAt = 0L;
    /** 是否已加载过一次。空规则集也算已加载，否则每次 DNS 查询都要重读文件 */
    private static volatile boolean sLoaded = false;

    private KillAdRules() {}

    /** 规则变更后调用，清掉缓存立即生效 */
    public static void invalidate() {
        sRules = null;
        sLoadAt = 0L;
        sLoaded = false;
    }

    /** 当前生效的全部规则条目（精确域名 + 通配模式 + 自定义），只读视图 */
    public static Set<String> getRules(Context ctx) {
        return load(ctx).flat();
    }

    /**
     * 域名是否命中拦截列表。
     *
     * 判定顺序：
     *   1. 必应相关域一律放行，优先于任何规则源；
     *   2. 精确域名：域名本身或它的任一级父域在集合里；
     *   3. 通配模式：对域名及其每一级父域做 glob 匹配。
     */
    public static boolean isBlocked(Context ctx, String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase();
        if (n.isEmpty()) return false;
        if (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        if (isAlwaysAllowed(n)) return false;

        RuleSet rs = load(ctx);
        if (rs.exact.isEmpty() && rs.wildcards.isEmpty()) return false;

        if (matchesExact(rs.exact, n)) return true;
        if (!rs.wildcards.isEmpty() && matchesWildcards(rs.wildcards, n)) return true;
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

    // ==================== 导入规则 ====================

    /** 是否已导入过规则文件 */
    public static boolean hasImportedRules(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), IMPORTED_FILE);
            return f.exists() && f.length() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 导入规则文件的原始内容，未导入时返回空串 */
    public static String getImportedRaw(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), IMPORTED_FILE);
            if (!f.exists() || f.length() <= 0) return "";
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 保存导入规则（整文件覆盖）。
     *
     * 内容一行一条，语法与自定义规则一致：纯域名、*.域名、通配、
     * ||域名^、0.0.0.0 域名 都识别，路径 / URL / 正则整条忽略。
     * 传入空内容视为清除导入规则。
     */
    public static void setImportedRaw(Context ctx, String raw) {
        try {
            File f = new File(ctx.getFilesDir(), IMPORTED_FILE);
            if (raw == null || raw.trim().isEmpty()) {
                if (f.exists()) f.delete();
                invalidate();
                return;
            }
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(f, false), "UTF-8"));
            try {
                w.write(raw);
                w.flush();
            } finally {
                w.close();
            }
        } catch (Exception ignored) {}
        invalidate();
    }

    /**
     * 统计一段规则文本能解析出的条数（精确 + 通配），供导入后提示与界面展示用。
     * 解析口径与加载时完全一致：一行一条，识别不了的整条丢弃。
     */
    public static int countRules(String raw) {
        RuleSet rs = new RuleSet();
        parseCustomRules(raw, rs);
        return rs.exact.size() + rs.wildcards.size();
    }

    /** 清除导入规则 */
    public static void clearImportedRules(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), IMPORTED_FILE);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
        invalidate();
    }

    // ==================== 规则加载 ====================

    /**
     * 规则集合：精确域名集合 + 通配模式列表，外加一份对外的扁平静态视图。
     *
     * 精确与通配分开存：精确命中走 HashSet + 父域回溯，通配命中才遍历模式列表，
     * 绝大多数查询在第一步就出结果，不会为通配规则付代价。
     */
    static final class RuleSet {
        final Set<String> exact = new HashSet<String>();
        final List<String> wildcards = new ArrayList<String>();
        private final Set<String> flat = new HashSet<String>();
        private Set<String> flatView = null;

        void addExact(String d) {
            if (d == null || d.isEmpty()) return;
            if (exact.add(d)) flat.add(d);
        }

        void addWildcard(String p) {
            if (p == null || p.isEmpty()) return;
            if (wildcards.contains(p)) return;
            wildcards.add(p);
            flat.add(p);
        }

        Set<String> flat() {
            if (flatView == null) flatView = Collections.unmodifiableSet(flat);
            return flatView;
        }
    }

    /**
     * 加载并缓存规则。首次调用读 assets + 在线文件 + 自定义，之后走 5 分钟缓存。
     * 由 DNS 处理线程调用，允许一次性的文件读取开销。
     */
    private static RuleSet load(Context ctx) {
        long now = System.currentTimeMillis();
        RuleSet cached = sRules;
        // 空集合也要走缓存：不能因为「没有规则」就每次查询都重读文件
        if (sLoaded && cached != null && now - sLoadAt < CACHE_MS) return cached;

        RuleSet rs = new RuleSet();

        if (ctx != null) {
            // 1. 内置规则（assets，本地，离线可用）
            loadAsset(ctx, ASSET_EXACT, rs, false);
            loadAsset(ctx, ASSET_WILDCARD, rs, true);
            loadAsset(ctx, ASSET_URL, rs, false);

            // 2. 在线规则文件（KillAdRuleUpdater 写入）
            //    只有按当前解析格式版本生成的文件才加载，旧格式文件可能含
            //    被错误降级成域名匹配的精准规则，一律忽略。
            //    解析与自定义规则共用同一套：一行一条，纯域名、通配、
            //    uBlock 锚、hosts 写法都能识别，识别不了的整条丢弃。
            try {
                File online = new File(ctx.getFilesDir(), RULES_FILE);
                if (online.exists() && KillAdRuleUpdater.hasLocalRules(ctx)) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            new FileInputStream(online), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        parseCustomRules(line, rs);
                    }
                    r.close();
                }
            } catch (Exception ignored) {}

            // 3. 导入规则文件（用户从本地 txt 导入，一行一条，语法同自定义规则）
            try {
                File imported = new File(ctx.getFilesDir(), IMPORTED_FILE);
                if (imported.exists() && imported.length() > 0) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            new FileInputStream(imported), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        parseCustomRules(line, rs);
                    }
                    r.close();
                }
            } catch (Exception ignored) {}

            // 4. 用户自定义规则（一行一条，支持域名 / 通配 / uBlock 语法）
            try {
                SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String raw = p.getString(KEY_CUSTOM, "");
                if (raw != null && !raw.isEmpty()) parseCustomRules(raw, rs);
            } catch (Exception ignored) {}
        }

        sRules = rs;
        sLoadAt = now;
        sLoaded = true;
        return rs;
    }

    /**
     * 读 assets/killad 下的一份规则文件。
     *
     * wildcard=true 时按通配规则解析：带 * ? 的进通配列表，
     * 形如 *.abc.com 的降级为精确域名 abc.com（父域回溯天然覆盖其子域）；
     * wildcard=false 时按精确域名解析，含斜杠的路径规则直接跳过 ——
     * DNS 层只拿得到域名，路径规则在这里无法匹配，留着只是白占内存。
     */
    private static void loadAsset(Context ctx, String name, RuleSet rs, boolean wildcard) {
        InputStream is = null;
        BufferedReader r = null;
        try {
            is = ctx.getAssets().open(ASSET_DIR + "/" + name);
            r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (wildcard) {
                    String w = normalizeWildcard(line);
                    if (w.isEmpty()) continue;
                    if (w.indexOf('*') >= 0 || w.indexOf('?') >= 0) rs.addWildcard(w);
                    else rs.addExact(w);
                } else {
                    if (line != null && line.indexOf('/') >= 0) continue;
                    String d = normalizeExact(line);
                    if (!d.isEmpty()) rs.addExact(d);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (r != null) r.close();
                else if (is != null) is.close();
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 解析用户自定义规则。
     *
     * 按行切分（同时兼容逗号、分号、空格分隔），一行一条，支持：
     *
     *   example.com            精确域名，命中该域及其所有子域
     *   *.example.com          同上（* 前缀被剥掉，父域回溯天然覆盖子域）
     *   p*-ad.example.com      通配域名，支持 * 与 ?
     *   ||example.com^         uBlock 写法，去锚后按域名处理
     *   0.0.0.0 example.com    hosts 写法，取域名部分
     *
     * 直接忽略（不报错、不截断成域名）：
     *   以 ! # [ ; 开头的注释行、@@ 开头的例外规则、/ 开头的正则规则、
     *   含 :// 或 / 的路径 / URL 规则 —— DNS 层只能看到域名，看不到路径，
     *   把 URL 截断成整域拦截会把只该拦一个路径的规则放大成误伤整个域。
     */
    static void parseCustomRules(String raw, RuleSet rs) {
        if (raw == null || raw.isEmpty() || rs == null) return;
        String[] lines = raw.split("[\\r\\n]+");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li] == null ? "" : lines[li].trim();
            if (line.isEmpty()) continue;
            // hosts 写法整行交给 addCustomRule：先按空白切会把「0.0.0.0 域名」拆坏
            if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                addCustomRule(line, rs);
                continue;
            }
            // 一行一条为主；同时兼容旧的逗号 / 分号 / 空格分隔写法
            String[] parts = line.split("[,，;；\\s]+");
            for (int pi = 0; pi < parts.length; pi++) {
                addCustomRule(parts[pi], rs);
            }
        }
    }

    /** 解析单条自定义规则，能识别就并入规则集，识别不了就丢弃 */
    static void addCustomRule(String raw, RuleSet rs) {
        if (raw == null) return;
        String s = raw.trim();
        if (s.isEmpty()) return;

        char c0 = s.charAt(0);
        // 注释与元数据
        if (c0 == '!' || c0 == '#' || c0 == '[' || c0 == ';') return;
        // 例外白名单不参与拦截
        if (s.startsWith("@@")) return;
        // 以斜杠开头是正则或路径规则
        if (c0 == '/') return;
        // 元素隐藏等 cosmetic 规则
        if (s.indexOf("##") >= 0 || s.indexOf("#?#") >= 0 || s.indexOf("#$#") >= 0) return;

        if (s.startsWith("0.0.0.0 ") || s.startsWith("127.0.0.1 ")) {
            // hosts 语法：地址 + 域名，域名后可能跟注释
            int sp = s.indexOf(' ');
            s = s.substring(sp + 1).trim();
            int sp2 = s.indexOf(' ');
            if (sp2 > 0) s = s.substring(0, sp2).trim();
            int hash = s.indexOf('#');
            if (hash >= 0) s = s.substring(0, hash).trim();
        } else {
            // 去 || 域名锚
            if (s.startsWith("||")) s = s.substring(2).trim();
            // 去 $options
            int dollar = s.indexOf('$');
            if (dollar >= 0) s = s.substring(0, dollar).trim();
            // 去末尾 ^ 结束锚（只认末尾，中间出现说明是精准匹配）
            int caret = s.indexOf('^');
            if (caret >= 0) {
                if (caret != s.length() - 1) return;
                s = s.substring(0, caret).trim();
            }
        }
        if (s.isEmpty()) return;
        // 含 :// 或 / 的是 URL / 路径规则，DNS 层无法匹配，整条丢弃
        if (s.indexOf("://") >= 0) return;
        if (s.indexOf('/') >= 0) return;

        if (s.indexOf('*') >= 0 || s.indexOf('?') >= 0) {
            String w = normalizeWildcard(s);
            if (w.isEmpty()) return;
            if (w.indexOf('*') >= 0 || w.indexOf('?') >= 0) rs.addWildcard(w);
            else rs.addExact(w);
        } else {
            String d = normalizeExact(s);
            if (!d.isEmpty()) rs.addExact(d);
        }
    }

    // ==================== 匹配 ====================

    /** 精确域名命中：域名本身或它的任一级父域在集合里 */
    private static boolean matchesExact(Set<String> exact, String n) {
        if (exact.contains(n)) return true;
        int i = n.indexOf('.');
        while (i >= 0 && i + 1 < n.length()) {
            if (exact.contains(n.substring(i + 1))) return true;
            i = n.indexOf('.', i + 1);
        }
        return false;
    }

    /** 通配模式命中：对域名自身及其每一级父域做 glob 匹配 */
    private static boolean matchesWildcards(List<String> pats, String n) {
        String cur = n;
        while (cur != null && cur.length() > 0) {
            for (int i = 0; i < pats.size(); i++) {
                if (globMatch(pats.get(i), cur)) return true;
            }
            int dot = cur.indexOf('.');
            if (dot < 0 || dot + 1 >= cur.length()) break;
            cur = cur.substring(dot + 1);
        }
        return false;
    }

    /**
     * 通配匹配，支持 *（任意长度）与 ?（单个字符）。
     *
     * 经典双指针回溯实现：只需 O(1) 额外空间，且模式与域名都很短，
     * 绝大多数不匹配在首字符就比较失败，成本可以忽略。
     */
    private static boolean globMatch(String pat, String s) {
        if (pat == null || s == null) return false;
        int p = 0, sp = 0, star = -1, mark = 0;
        int pl = pat.length(), sl = s.length();
        while (sp < sl) {
            if (p < pl && (pat.charAt(p) == '?' || pat.charAt(p) == s.charAt(sp))) {
                p++;
                sp++;
            } else if (p < pl && pat.charAt(p) == '*') {
                star = p++;
                mark = sp;
            } else if (star >= 0) {
                p = star + 1;
                sp = ++mark;
            } else {
                return false;
            }
        }
        while (p < pl && pat.charAt(p) == '*') p++;
        return p == pl;
    }

    // ==================== 归一化 ====================

    /** 去空白、去 || 锚点、去 $options、统一小写 */
    private static String baseClean(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return "";
        if (s.startsWith("||")) s = s.substring(2).trim();
        int dollar = s.indexOf('$');
        if (dollar >= 0) s = s.substring(0, dollar).trim();
        return s;
    }

    /** 精确域名归一化：去通配前缀与首尾点，只保留合法域名字符 */
    private static String normalizeExact(String raw) {
        String d = baseClean(raw);
        if (d.isEmpty()) return "";
        if (d.startsWith("*.")) d = d.substring(2);
        while (d.startsWith(".")) d = d.substring(1);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        return isValidPattern(d, false) ? d : "";
    }

    /**
     * 通配规则归一化。
     *
     * 形如 *.abc.com 的前缀被剥掉后不再含通配符，由调用方归入精确集合；
     * 其余保留 * ? 原样。
     */
    private static String normalizeWildcard(String raw) {
        String d = baseClean(raw);
        if (d.isEmpty()) return "";
        if (d.startsWith("*.")) d = d.substring(2);
        while (d.startsWith(".")) d = d.substring(1);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        boolean hasWild = d.indexOf('*') >= 0 || d.indexOf('?') >= 0;
        return isValidPattern(d, hasWild) ? d : "";
    }

    /**
     * 规则串是否合法：长度、首尾点、至少两级域名、字符集。
     *
     * allowWildcard=true 时额外放行 * 与 ?；false 时只接受纯域名字符，
     * 含 / = & % 空格等的一律判为非法并丢弃，避免把路径规则误当域名。
     */
    private static boolean isValidPattern(String d, boolean allowWildcard) {
        if (d == null || d.isEmpty() || d.length() > 253) return false;
        if (d.startsWith(".") || d.endsWith(".")) return false;
        if (d.indexOf('.') < 0) return false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_') continue;
            if (allowWildcard && (c == '*' || c == '?')) continue;
            return false;
        }
        int lastDot = d.lastIndexOf('.');
        if (lastDot <= 0 || lastDot >= d.length() - 1) return false;
        String tld = d.substring(lastDot + 1);
        for (int i = 0; i < tld.length(); i++) {
            char c = tld.charAt(i);
            if (c < '0' || c > '9') return true;   // 顶级域含字母，视为正常
        }
        return false;   // 顶级域是纯数字，判为非法
    }
}
