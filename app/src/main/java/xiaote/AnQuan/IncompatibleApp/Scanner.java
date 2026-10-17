package xiaote.AnQuan.IncompatibleApp;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 不兼容应用扫描。
 *
 * 不兼容不等于恶意：命中的都是与星特安全同类型的防护类软件，
 * 彼此可能互相拦截安全操作，导致系统卡死、防护失效或互相卸载。
 *
 * 两条判定口径，结果合并去重：
 *   A. 声明了无障碍服务（AccessibilityService）的应用，与内置不兼容包名清单取交集；
 *   B. 上述应用中 APK 体积不超过 50MB 的，读取其 AndroidManifest.xml，
 *      字节序列里出现 mio.lock.kill.MyApplication 即命中。
 *
 * quickScan 只做 A（快，供入口页后台检查），fullScan 做 A + B（详细扫描）。
 * 全部为阻塞调用，必须在工作线程执行。
 */
public final class Scanner {

    /** 已知与星特安全存在功能冲突的包名（争抢无障碍、互相卸载、锁机等） */
    public static final String[] INCOMPATIBLE_PKGS = {
            "com.youlong.hd",
            "com.baicai.app",
            "com.bintianqiu.owndroid",
            "mio.lock.kill",
            "mio.love.you"
    };

    /**
     * 已知不兼容包名的 MD5（32 位小写十六进制）。
     *
     * 部分不兼容应用把包名写成 QQ 号或随机串，明文清单拦不住；
     * 这里同时维护一份包名哈希，扫描时对每个包名算 MD5 再比对，
     * 清单里可以只放哈希，不必暴露原始包名。
     */
    public static final String[] INCOMPATIBLE_HASHES = {
    };

    /** 无障碍服务标准 action，与系统设置扫描口径一致 */
    private static final String ACTION_ACCESSIBILITY_SERVICE =
            "android.accessibilityservice.AccessibilityService";

    /**
     * GET_DISABLED_COMPONENTS / MATCH_DISABLED_COMPONENTS 的值。
     *
     * API 23 叫 GET_DISABLED_COMPONENTS，API 24 起改名 MATCH_DISABLED_COMPONENTS，
     * 数值都是 0x00000200。这里用字面量，避免新老常量名在不同 SDK 上的差异。
     * 作用：把清单里声明了但被禁用的无障碍服务也算进来。
     */
    private static final int FLAG_DISABLED_COMPONENTS = 0x00000200;

    /** 清单深度扫描只看 50MB 以下的 APK */
    private static final long MANIFEST_SCAN_MAX_BYTES = 50L * 1024L * 1024L;
    /** 单个 AndroidManifest.xml 最多读 8MB，防异常包 */
    private static final int MANIFEST_READ_LIMIT = 8 * 1024 * 1024;
    /**
     * AndroidManifest 中出现这些字符串即视为不兼容（冲突的 Application 类名等）。
     * 明文匹配，直接对 AXML 字节流做 UTF-8 / UTF-16LE 子串查找。
     */
    private static final String[] MANIFEST_MARKERS = {
            "mio.lock.kill.MyApplication"
    };

    /**
     * 不兼容标记的 MD5（32 位小写十六进制）。
     *
     * 与包名哈希同理：清单里可以只放哈希，不暴露原始类名，
     * 也避免有人把类名改成 QQ 号后拿星特安全做宣传时一眼看穿规则。
     * 扫描时枚举 AXML 字节流里的可打印 ASCII 片段，逐条算 MD5 比对。
     * 哈希清单为空时跳过该阶段，不做任何额外开销。
     */
    private static final String[] MANIFEST_MARKER_HASHES = {
    };

    /** 哈希枚举时单个片段的最大长度，超过则不参与比对（防超长噪声串） */
    private static final int HASH_RUN_MAX_CHARS = 512;

    /** 命中原因文案：统一一句，不区分具体来源 */
    public static final String REASON = "可能与星特安全不兼容";

    private Scanner() {}

    /** 扫描进度回调，在工作线程调用 */
    public interface Progress {
        void onProgress(int index, int total);
    }

    /** 扫描结果条目 */
    public static class Hit {
        public String pkg;
        public String name;
        public String reason;
        public Drawable icon;
    }

    // ==================== 扫描入口 ====================

    /**
     * 快速扫描：只查声明了无障碍服务的应用是否命中内置不兼容包名清单。
     * 一次 PackageManager 查询，几十毫秒量级，适合入口页打开时后台跑。
     */
    public static List<Hit> quickScan(Context ctx) {
        Map<String, Hit> map = new LinkedHashMap<String, Hit>();
        if (ctx == null) return new ArrayList<Hit>();
        try {
            PackageManager pm = ctx.getPackageManager();
            for (String pkg : accessibilityPackages(pm)) {
                if (isIncompatible(pkg)) addHit(map, pm, pkg);
            }
        } catch (Throwable ignored) {}
        return new ArrayList<Hit>(map.values());
    }

    /**
     * 详细扫描：快速扫描的全部口径，外加对无障碍应用中 50MB 以下 APK
     * 读取 AndroidManifest.xml 匹配不兼容类名。
     */
    public static List<Hit> fullScan(Context ctx, Progress progress) {
        Map<String, Hit> map = new LinkedHashMap<String, Hit>();
        if (ctx == null) return new ArrayList<Hit>();
        PackageManager pm = ctx.getPackageManager();

        Set<String> accPkgs = accessibilityPackages(pm);
        for (String pkg : accPkgs) {
            if (isIncompatible(pkg)) addHit(map, pm, pkg);
        }

        String self = "";
        try { self = ctx.getPackageName(); } catch (Throwable ignored) {}
        final int total = accPkgs.size();
        int index = 0;
        for (String pkg : accPkgs) {
            index++;
            if (pkg == null || pkg.equals(self)) continue;
            if (progress != null && index % 10 == 0) progress.onProgress(index, total);
            if (map.containsKey(pkg)) continue;

            String path = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                path = (ai == null) ? null : ai.sourceDir;
            } catch (Throwable t) {
                continue;
            }
            if (path == null || path.length() == 0) continue;
            try {
                File f = new File(path);
                if (!f.isFile()) continue;
                long len = f.length();
                if (len <= 0 || len > MANIFEST_SCAN_MAX_BYTES) continue;
            } catch (Throwable t) {
                continue;
            }
            if (manifestContains(path)) addHit(map, pm, pkg);
        }
        return new ArrayList<Hit>(map.values());
    }

    // ==================== 内部工具 ====================

    /** 明文包名集合，懒加载只建一次 */
    private static volatile Set<String> sTargets = null;

    private static Set<String> targetSet() {
        Set<String> t = sTargets;
        if (t != null) return t;
        Set<String> targets = new HashSet<String>();
        for (String p : INCOMPATIBLE_PKGS) targets.add(p);
        sTargets = targets;
        return targets;
    }

    /**
     * 某个包名是否属于不兼容清单。
     *
     * 先比明文包名，再对包名算 MD5 与哈希清单比对：
     * 这样清单里只放哈希也能识别，包名被写成 QQ 号或随机串一样命中。
     * 哈希比对忽略大小写。
     */
    private static boolean isIncompatible(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        if (targetSet().contains(pkg)) return true;
        if (INCOMPATIBLE_HASHES.length == 0) return false;
        String md5 = md5Hex(pkg);
        if (md5 == null) return false;
        for (String h : INCOMPATIBLE_HASHES) {
            if (h == null || h.length() == 0) continue;
            if (h.equalsIgnoreCase(md5)) return true;
        }
        return false;
    }

    /** 包名的 MD5（32 位小写十六进制），失败返回 null */
    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 全部在清单里声明了无障碍服务的包名（含被禁用的组件），按声明顺序去重。
     * 本应用 targetSdk 26，不受 Android 11 包可见性限制，能扫全。
     */
    private static Set<String> accessibilityPackages(PackageManager pm) {
        Set<String> out = new LinkedHashSet<String>();
        if (pm == null) return out;
        try {
            Intent it = new Intent(ACTION_ACCESSIBILITY_SERVICE);
            List<ResolveInfo> list = pm.queryIntentServices(it, FLAG_DISABLED_COMPONENTS);
            if (list == null) return out;
            for (ResolveInfo ri : list) {
                if (ri == null || ri.serviceInfo == null) continue;
                String pkg = ri.serviceInfo.packageName;
                if (pkg == null || pkg.length() == 0) continue;
                out.add(pkg);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 记录命中项：读应用名与图标，同包只出一条 */
    private static void addHit(Map<String, Hit> map, PackageManager pm, String pkg) {
        if (pkg == null || map.containsKey(pkg)) return;
        Hit h = new Hit();
        h.pkg = pkg;
        h.name = pkg;
        h.reason = REASON;
        h.icon = null;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null) h.name = label.toString();
            h.icon = pm.getApplicationIcon(ai);
        } catch (Throwable ignored) {}
        map.put(pkg, h);
    }

    /**
     * 在 APK 的 AndroidManifest.xml 里查找不兼容标记，明文与哈希各查一遍。
     *
     * 明文：AXML 是二进制格式，字符串存在字符串池里，池按 UTF-8 或 UTF-16LE 编码，
     *       两种编码各查一遍，命中任意一种即算命中。
     * 哈希：枚举字节流里的可打印 ASCII 片段，逐条算 MD5 与哈希清单比对，
     *       这样清单里可以只放哈希、不暴露原始类名。
     * 任何异常（不是 zip、条目缺失、读取失败）一律返回 false，不误报。
     */
    private static boolean manifestContains(String apkPath) {
        if (apkPath == null) return false;
        boolean needLiteral = MANIFEST_MARKERS.length > 0;
        boolean needHash = MANIFEST_MARKER_HASHES.length > 0;
        if (!needLiteral && !needHash) return false;
        ZipFile zf = null;
        try {
            zf = new ZipFile(apkPath);
            ZipEntry ze = zf.getEntry("AndroidManifest.xml");
            if (ze == null) return false;
            long sz = ze.getSize();
            if (sz > MANIFEST_READ_LIMIT) return false;
            byte[] data = readLimited(zf.getInputStream(ze), MANIFEST_READ_LIMIT);
            if (data == null || data.length == 0) return false;

            if (needLiteral) {
                for (String m : MANIFEST_MARKERS) {
                    if (m == null || m.length() == 0) continue;
                    if (indexOf(data, m.getBytes("UTF-8")) >= 0) return true;
                    if (indexOf(data, m.getBytes("UTF-16LE")) >= 0) return true;
                }
            }
            if (needHash && containsAnyHash(data)) return true;
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (zf != null) {
                try { zf.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 字节流里是否存在哈希命中的可打印片段。
     *
     * AXML 的字符串池对纯 ASCII 串按 UTF-8 存储，所以按 0x20~0x7E 切段即可
     * 还原出类名、包名这类候选；含非 ASCII 的串会被切成多段，匹配不到，
     * 这属于预期（哈希清单本来就是给纯 ASCII 的类名/包名准备的）。
     * 段长超过 HASH_RUN_MAX_CHARS 直接丢弃，避免对超长噪声串做无谓的哈希运算。
     */
    private static boolean containsAnyHash(byte[] data) {
        Set<String> set = new HashSet<String>();
        for (String h : MANIFEST_MARKER_HASHES) {
            if (h == null) continue;
            String v = h.trim().toLowerCase();
            if (v.length() > 0) set.add(v);
        }
        if (set.isEmpty()) return false;

        int n = data.length;
        int start = -1;
        for (int i = 0; i <= n; i++) {
            boolean printable = i < n && data[i] >= 0x20 && data[i] <= 0x7E;
            if (printable) {
                if (start < 0) start = i;
                else if (i - start + 1 > HASH_RUN_MAX_CHARS) {
                    // 超长，丢弃整段
                    start = -1;
                }
                continue;
            }
            if (start >= 0) {
                int len = i - start;
                if (len >= 4) {
                    try {
                        String s = new String(data, start, len, "UTF-8");
                        String md5 = md5Hex(s);
                        if (md5 != null && set.contains(md5)) return true;
                    } catch (Throwable ignored) {}
                }
                start = -1;
            }
        }
        return false;
    }

    /** 读取流，超过 limit 返回 null（顺带关闭流） */
    private static byte[] readLimited(InputStream is, int limit) {
        if (is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        try {
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > limit) return null;
                bos.write(buf, 0, n);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            try { is.close(); } catch (Throwable ignored) {}
        }
        return bos.toByteArray();
    }

    /** 朴素字节子串查找，找不到返回 -1 */
    private static int indexOf(byte[] data, byte[] pat) {
        if (data == null || pat == null || pat.length == 0) return -1;
        int n = data.length;
        int m = pat.length;
        if (m > n) return -1;
        outer:
        for (int i = 0; i <= n - m; i++) {
            for (int j = 0; j < m; j++) {
                if (data[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
