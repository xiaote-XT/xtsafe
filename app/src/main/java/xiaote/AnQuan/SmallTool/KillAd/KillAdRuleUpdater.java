package xiaote.AnQuan.SmallTool.KillAd;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/**
 * 在线规则更新器。
 *
 * 默认规则源：https://filters.adtidy.org/extension/ublock/filters/11.txt
 *
 * 解析原则：只提取「域名锚定」规则，绝不把精准匹配降级成域名匹配。
 *
 * 保留（纯域名，可安全做后缀匹配）：
 *   ||example.com^
 *   ||example.com^$third-party
 *   ||example.com
 *   example.com
 *   0.0.0.0 example.com
 *   127.0.0.1 example.com
 *
 * 丢弃（精准匹配，降级会误伤整域）：
 *   |http://example.com/ad.js        单竖线锚点，精确 URL 起始
 *   ||example.com/ads/*              带路径或通配符
 *   ||example.com^/path              域名结束锚后仍有路径分界
 *   ||example.com^ 后接通配与路径            同理
 *   /banner/*                        以斜杠开头的路径规则
 *   /regex/                          正则规则
 *   example.com##.ad                 元素隐藏
 *   @@||example.com^                 例外白名单
 *   ! 注释 / [ 元数据
 *
 * 结果按行写入 filesDir/killad_rules.txt，由 KillAdRules 读取生效。
 */
public final class KillAdRuleUpdater {

    /** 默认规则源：AdGuard Base filter（uBlock 语法版，filters/11.txt） */
    public static final String DEFAULT_RULE_URL =
            "https://filters.adtidy.org/extension/ublock/filters/11.txt";
    /** 旧默认源，已保存过该值的用户自动迁移到新默认 */
    private static final String OLD_DEFAULT_RULE_URL =
            "https://filters.adtidy.org/extension/ublock/filters/2.txt";

    public static final String KEY_RULE_URL = "killad_rule_url";
    public static final String KEY_LAST_UPDATE = "killad_rules_last_update";
    public static final String KEY_LAST_COUNT = "killad_rules_last_count";
    public static final String KEY_RULE_FORMAT = "killad_rules_format";
    public static final String RULES_FILE = "killad_rules.txt";

    /**
     * 规则解析格式版本。解析逻辑变更（如修正精准匹配被降级成域名匹配）时递增，
     * 旧版本生成的文件会被 KillAdRules 忽略，需重新更新一次规则。
     */
    public static final int RULE_FORMAT_VERSION = 2;

    /** 防御上限：规则文件行数 */
    private static final int MAX_LINES = 300000;
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 40000;

    public interface Callback {
        void onDone(boolean ok, int count, String message);
    }

    private KillAdRuleUpdater() {}

    // ==================== 配置 ====================

    public static String getRuleUrl(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(KillAdRules.PREFS, Context.MODE_PRIVATE);
            String v = p.getString(KEY_RULE_URL, DEFAULT_RULE_URL);
            if (v != null) {
                v = v.trim();
                // 旧默认源自动迁移到新默认
                if (OLD_DEFAULT_RULE_URL.equals(v)) {
                    p.edit().putString(KEY_RULE_URL, DEFAULT_RULE_URL).apply();
                    return DEFAULT_RULE_URL;
                }
                if (!v.isEmpty()) return v;
            }
        } catch (Exception ignored) {}
        return DEFAULT_RULE_URL;
    }

    public static void setRuleUrl(Context ctx, String url) {
        String v = (url == null || url.trim().isEmpty()) ? DEFAULT_RULE_URL : url.trim();
        try {
            ctx.getSharedPreferences(KillAdRules.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_RULE_URL, v).apply();
        } catch (Exception ignored) {}
    }

    public static long getLastUpdateTime(Context ctx) {
        try {
            return ctx.getSharedPreferences(KillAdRules.PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_UPDATE, 0L);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public static int getLastCount(Context ctx) {
        try {
            return ctx.getSharedPreferences(KillAdRules.PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_LAST_COUNT, 0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** 本地规则文件是否已按当前格式版本生成 */
    public static boolean hasLocalRules(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), RULES_FILE);
            if (!f.exists() || f.length() <= 0) return false;
            int fmt = ctx.getSharedPreferences(KillAdRules.PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_RULE_FORMAT, 0);
            return fmt >= RULE_FORMAT_VERSION;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ==================== 更新 ====================

    /** 后台更新，回调在主线程执行 */
    public static void updateAsync(final Context ctx, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                int count = 0;
                String msg;
                try {
                    count = update(ctx);
                    ok = true;
                    msg = "OK";
                } catch (Exception e) {
                    ok = false;
                    msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                if (ok) {
                    KillAdLogger.log(ctx, "RULE", "规则更新成功，解析出 " + count + " 条域名，源=" + getRuleUrl(ctx));
                } else {
                    KillAdLogger.log(ctx, "RULE", "规则更新失败: " + msg + "，源=" + getRuleUrl(ctx));
                }
                final boolean fOk = ok;
                final int fCount = count;
                final String fMsg = msg;
                if (cb != null) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onDone(fOk, fCount, fMsg);
                        }
                    });
                }
            }
        }, "killad-rule-update").start();
    }

    /** 同步更新（必须在工作线程调用），返回写入的域名条数 */
    public static int update(Context ctx) throws Exception {
        String urlStr = getRuleUrl(ctx);
        HttpURLConnection conn = null;
        Set<String> set = new HashSet<String>();
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "XTsafe-KillAd/1.0");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);

            InputStream is = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            int lines = 0;
            while ((line = r.readLine()) != null) {
                if (++lines > MAX_LINES) break;
                String d = extractDomain(line);
                if (d != null) set.add(d);
            }
            r.close();
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }

        if (set.isEmpty()) throw new Exception("规则解析结果为空");

        File dst = new File(ctx.getFilesDir(), RULES_FILE);
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(dst, false), "UTF-8"));
        try {
            for (String d : set) {
                w.write(d);
                w.write('\n');
            }
            w.flush();
        } finally {
            w.close();
        }

        try {
            ctx.getSharedPreferences(KillAdRules.PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                    .putInt(KEY_LAST_COUNT, set.size())
                    .putInt(KEY_RULE_FORMAT, RULE_FORMAT_VERSION)
                    .apply();
        } catch (Exception ignored) {}

        KillAdRules.invalidate();
        return set.size();
    }

    // ==================== 解析 ====================

    /**
     * 从一行 uBlock/AdGuard 规则中抽取域名，抽不到或属于精准匹配则返回 null。
     *
     * 核心原则：只接受「整段就是域名」的规则。任何带路径、通配符、正则、
     * 单竖线锚点、域名后仍有分界的规则，都视为精准 URL 匹配而整条丢弃，
     * 绝不截断成域名——截断会把只该拦一个路径的规则放大成整域拦截。
     */
    static String extractDomain(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // 注释与元数据
        char c0 = s.charAt(0);
        if (c0 == '!' || c0 == '#' || c0 == '[' || c0 == ';') return null;
        // 例外白名单不拦截
        if (s.startsWith("@@")) return null;
        // 正则规则
        if (c0 == '/') return null;
        // 元素隐藏 / 脚本注入等 cosmetic 规则
        if (s.indexOf("##") >= 0 || s.indexOf("#@#") >= 0
                || s.indexOf("#?#") >= 0 || s.indexOf("#$#") >= 0) return null;

        boolean hostsForm = false;
        if (s.startsWith("0.0.0.0 ") || s.startsWith("127.0.0.1 ")) {
            // hosts 语法：地址 + 域名，域名后可能跟注释
            hostsForm = true;
            int sp = s.indexOf(' ');
            s = s.substring(sp + 1).trim();
            int sp2 = s.indexOf(' ');
            if (sp2 > 0) s = s.substring(0, sp2).trim();
            int hash = s.indexOf('#');
            if (hash >= 0) s = s.substring(0, hash).trim();
        } else {
            // 去 $options
            int dollar = s.indexOf('$');
            if (dollar >= 0) s = s.substring(0, dollar).trim();
            if (s.isEmpty()) return null;

            // 只有 || 双竖线才是域名锚定；单 | 是精确 URL 起始，直接丢弃
            if (s.startsWith("||")) {
                s = s.substring(2).trim();
            } else if (s.startsWith("|")) {
                return null;
            } else if (s.indexOf("://") >= 0) {
                return null;
            }

            // ^ 只允许作为域名结束锚出现在末尾；
            // 出现在中间或后面还有内容，说明该规则还带路径分界，属于精准匹配
            int caret = s.indexOf('^');
            if (caret >= 0) {
                if (caret != s.length() - 1) return null;
                s = s.substring(0, caret).trim();
            }
        }

        s = s.toLowerCase();
        if (s.isEmpty()) return null;
        if (s.length() > 253) return null;
        if (s.startsWith(".") || s.endsWith(".")) return null;

        // 只接受纯域名字符。含 / * ? = & % 空格等的规则整条丢弃，不截断
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            if (!ok) return null;
        }

        if (s.indexOf('.') < 0) return null;

        // 至少两级，顶级域不能是纯数字
        int lastDot = s.lastIndexOf('.');
        if (lastDot <= 0 || lastDot >= s.length() - 1) return null;
        String tld = s.substring(lastDot + 1);
        boolean hasAlpha = false;
        for (int i = 0; i < tld.length(); i++) {
            char c = tld.charAt(i);
            if (c < '0' || c > '9') { hasAlpha = true; break; }
        }
        if (!hasAlpha) return null;

        return s;
    }
}
