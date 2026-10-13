package xiaote.AnQuan.InternalApi;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全屏覆盖窗口检测。
 *
 * 可用通道：Shizuku → Root。
 *
 * ===== 为什么没有 Dhizuku / DeviceOwner =====
 *
 * 窗口栈信息只能从 dumpsys window 读，而 dumpsys 是 shell 命令：
 *
 *   Dhizuku     —— 服务端是普通应用进程，只多一个设备所有者身份，
 *                   跑不了 shell；
 *   DeviceOwner —— 公开 API 里没有任何「列出当前窗口」的能力，
 *                   getActiveAdmins / setApplicationHidden 那套都碰不到窗口栈。
 *
 * 所以本操作只做 shell 两级，不硬写后面两级。
 *
 * ===== 与 XTSafeMainService 里 OverlayDetector 的关系 =====
 *
 * 检测口径完全一致（窗口类型白名单、面积阈值 90%%、排除系统应用与白名单），
 * 只是把「取数据」这一步从 OverlayDetector 内部抽出来，交回这里统一走通道链。
 * 判定逻辑保留在调用方，避免本类膨胀成一个巨型检测器。
 *
 * 全部为阻塞调用，必须在工作线程使用。
 */
public final class OverlayApi {

    /** 系统窗口类型区间 */
    private static final int TYPE_SYS_MIN = 2000;
    private static final int TYPE_SYS_MAX = 2999;
    /*
     * 安全类型名单与类型名/数值映射已统一到 OverlayDetector：
     * 那边用反射自动建表，本类只调用，不再单独维护一份，避免口径分叉。
     */
    /** 面积占比阈值 */
    private static final float AREA_RATIO = 0.90f;

    /** 窗口矩形：dumpsys 输出形如 mFrame=[l,t][r,b] */
    private static final Pattern P_FRAME =
            Pattern.compile("mFrame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");
    /**
     * 窗口类型：新版 dumpsys 输出常量名（ty=APPLICATION_OVERLAY），
     * 旧版输出数字（ty=2038），两种都要认。
     */
    private static final Pattern P_TYPE = Pattern.compile("\\bty=([A-Za-z0-9_]+)");
    /** 兼容新版：Frames 行里的 frame=[l,t][r,b]（新版不再输出 mFrame=） */
    private static final Pattern P_FRAME_LOOSE =
            Pattern.compile("\\bframe=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

    /**
     * 窗口类型 token → 数值。
     *
     * 数字直接解析；常量名去 TYPE_ 前缀后查表；
     * 认不出的系统窗口常量统一给 2500（仍在 2000-2999 区间内，
     * 按候选覆盖处理），由安全名单、面积、可见性、第三方应用继续过滤。
     */
    public static int typeFromToken(String token) {
        if (token == null || token.isEmpty()) return 0;
        char c0 = token.charAt(0);
        if (c0 >= '0' && c0 <= '9') {
            try { return Integer.parseInt(token); } catch (Exception ignored) { return 0; }
        }
        // 统一委托 OverlayDetector：类型名/数值映射只维护一份
        return xiaote.AnQuan.OverlayDetector.typeFromToken(token);
    }

    /** 旧映射实现已停用，仅作对照；新逻辑全部走 OverlayDetector 的反射表 */
    private static int legacyTypeFromToken(String token) {
        String t = token.toUpperCase(java.util.Locale.US);
        if (t.startsWith("TYPE_")) t = t.substring(5);
        if ("APPLICATION_OVERLAY".equals(t)) return 2038;
        if ("SYSTEM_ALERT".equals(t)) return 2003;
        if ("SYSTEM_OVERLAY".equals(t)) return 2006;
        if ("SYSTEM_ERROR".equals(t)) return 2010;
        if ("ACCESSIBILITY_OVERLAY".equals(t)) return 2032;
        if ("PRIORITY_PHONE".equals(t)) return 2007;
        if ("PHONE".equals(t)) return 2002;
        if ("TOAST".equals(t)) return 2005;
        if ("STATUS_BAR".equals(t)) return 2000;
        if ("NAVIGATION_BAR".equals(t)) return 2019;
        if ("NAVIGATION_BAR_PANEL".equals(t)) return 2024;
        if ("INPUT_METHOD".equals(t)) return 2011;
        if ("INPUT_METHOD_DIALOG".equals(t)) return 2012;
        if ("WALLPAPER".equals(t)) return 2013;
        if ("POINTER".equals(t)) return 2015;
        if ("SEARCH_BAR".equals(t)) return 2001;
        if ("VOLUME_OVERLAY".equals(t)) return 2020;
        if ("BOOT_PROGRESS".equals(t)) return 2021;
        if ("MAGNIFICATION_OVERLAY".equals(t)) return 2027;
        /*
         * 应用类窗口常量必须显式降为非系统值，否则 ty=BASE_APPLICATION
         * 会落到 2500 默认值，被当成全屏覆盖候选，把前台应用自己判成
         * 覆盖窗口。
         */
        if ("BASE_APPLICATION".equals(t)) return 1;
        if ("APPLICATION".equals(t)) return 2;
        if ("APPLICATION_STARTING".equals(t)) return 3;
        if ("DRAWN_APPLICATION".equals(t)) return 4;
        if (t.startsWith("APPLICATION_")) return 1000;
        if ("SUB_PANEL".equals(t)) return 1000;
        return 2500;
    }
    /** 可见性：mViewVisibility=0x0 为可见，0x8 为 GONE */
    private static final Pattern P_VIEW_VISIBILITY =
            Pattern.compile("mViewVisibility=0x([0-9a-fA-F]+)");
    /** 兜底可见性 */
    private static final Pattern P_HAS_SURFACE = Pattern.compile("mHasSurface=(true|false)");

    private OverlayApi() {}

    /** 本操作支持的通道，按优先级排列 */
    public static Capability[] chain() {
        return new Capability[]{ Capability.SHIZUKU, Capability.ROOT };
    }

    // ==================== 数据模型 ====================

    /** 一个覆盖窗口 */
    public static final class Window {
        public String pkg = "";
        public int type = 0;
        public int left, top, right, bottom;
        public boolean visible = false;
        public int viewVisibility = -1;
        public boolean hasSurface = false;

        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }

    /** 一次扫描结果 */
    public static final class Scan {
        public final ApiResult result;
        public final List<Window> windows;

        Scan(ApiResult result, List<Window> windows) {
            this.result = result;
            this.windows = windows;
        }
    }

    // ==================== 读取 ====================

    /**
     * 读取当前窗口栈。
     *
     * 返回原始窗口列表，不做任何业务判定 —— 调用方按自己的口径筛。
     * 无障碍不在线时本方法仍可用（只要 Shizuku 或 Root 在），
     * 这正是把它从无障碍服务里抽出来的意义。
     */
    public static Scan scanWindows(Context ctx) {
        StringBuilder err = new StringBuilder();
        Capability last = null;
        for (Capability c : chain()) {
            last = c;
            if (!c.isReady(ctx)) {
                err.append(c.label).append(" 未就绪; ");
                continue;
            }
            Exec.Out o = Exec.run(c, "dumpsys window windows");
            if (o == null || !o.ok || o.text == null || o.text.isEmpty()) {
                err.append(c.label).append(' ').append(o == null ? "不支持" : o.toString()).append("; ");
                continue;
            }
            return new Scan(ApiResult.success(c), parse(o.text));
        }
        return new Scan(ApiResult.failure(last, err.toString()), new ArrayList<Window>());
    }

    /**
     * 直接筛出「全屏覆盖的第三方应用」包名集合。
     *
     * 判定口径：
     *   · 窗口类型在 OVERLAY_TYPES 内；
     *   · 可见（mViewVisibility=0x0，缺失时退回 mHasSurface）；
     *   · 宽高都 ≥ 屏幕对应边 × 90%%；
     *   · 包名是第三方应用、不是自身、不在白名单。
     *
     * 屏幕尺寸从 window manager 取，取不到时用窗口自身的最大边界兜底 ——
     * 宁可漏判也不误杀：尺寸拿不准时当作不满足阈值。
     */
    public static ApiResult scanFullscreenThirdParty(Context ctx, List<String> outPackages) {
        if (ctx == null) return ApiResult.unsupported("ctx 为空");
        Scan s = scanWindows(ctx);
        if (!s.result.ok) return s.result;

        int screenW = 0, screenH = 0;
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
                screenW = dm.widthPixels;
                screenH = dm.heightPixels;
            }
        } catch (Throwable ignored) {}
        if (screenW <= 0 || screenH <= 0) {
            return ApiResult.failure(s.result.via, "取不到屏幕尺寸，无法判定面积占比");
        }

        int hit = 0;
        if (outPackages != null) {
            for (Window w : s.windows) {
                if (!isOverlayType(w.type)) continue;
                if (!isVisible(w)) continue;
                if (w.width() < screenW * AREA_RATIO) continue;
                if (w.height() < screenH * AREA_RATIO) continue;
                if (!isThirdParty(ctx, w.pkg)) continue;
                if (!outPackages.contains(w.pkg)) outPackages.add(w.pkg);
                hit++;
            }
        }
        return ApiResult.successCount(s.result.via, hit,
                "扫描 " + s.windows.size() + " 个窗口，命中 " + hit + " 个全屏覆盖");
    }

    // ==================== 判定辅助（调用方可直接复用） ====================

    /**
     * 是否属于需要关注的覆盖窗口类型。
     *
     * 改为「系统窗口区间内、且不在安全名单里」，与 OverlayDetector 口径一致，
     * 避免固定白名单漏掉 ROM 自定义的 2xxx 覆盖类型。
     */
    public static boolean isOverlayType(int type) {
        return xiaote.AnQuan.OverlayDetector.isOverlayType(type);
    }

    /**
     * 窗口是否可见。
     * 主判据 mViewVisibility（0 才是可见）；缺失时看 mHasSurface；
     * 两者都没有就按不可见处理 —— 新版 dumpsys 已不再输出 isVisible=，
     * 早期代码只认那个字段，导致所有窗口都被判成不可见。
     */
    public static boolean isVisible(Window w) {
        if (w == null) return false;
        if (w.viewVisibility >= 0) return w.viewVisibility == 0;
        return w.hasSurface;
    }

    /** 是否属于「该处理的第三方应用」：排除自身、系统应用、白名单 */
    public static boolean isThirdParty(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(ctx.getPackageName())) return false;
        try {
            if (xiaote.AnQuan.WhitelistPackages.isWhitelisted(ctx, pkg)) return false;
        } catch (Throwable ignored) {}
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return false;
            if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return false;
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    // ==================== 解析 ====================

    /** 把 dumpsys window windows 输出解析成窗口列表 */
    public static List<Window> parse(String out) {
        List<Window> list = new ArrayList<Window>();
        if (out == null) return list;
        Window cur = null;
        for (String line : out.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Window #")) {
                if (cur != null) list.add(cur);
                cur = new Window();
                // 窗口行形如 "Window #3 Window{c576cd9 u0 xiaote.AnQuan}:"
                // 手动切分而不写含花括号的正则，避免转义问题
                int wi = trimmed.indexOf("Window{");
                int braceEnd = trimmed.indexOf('}', wi);
                if (wi >= 0 && braceEnd > wi) {
                    String inner = trimmed.substring(wi + 7, braceEnd).trim();
                    String[] parts = inner.split("\\s+");
                    if (parts.length >= 3) cur.pkg = parts[2];
                    else if (parts.length == 2) cur.pkg = parts[1];
                    else if (parts.length == 1) cur.pkg = parts[0];
                }
                continue;
            }
            if (cur == null) continue;
            Matcher mp = Pattern.compile("package=([^ ]+)").matcher(line);
            if (mp.find()) { cur.pkg = mp.group(1); continue; }
            Matcher mt = P_TYPE.matcher(line);
            if (mt.find()) {
                // 新版 dumpsys 输出常量名（ty=APPLICATION_OVERLAY），旧版输出数字
                cur.type = typeFromToken(mt.group(1));
                continue;
            }
            /*
             * 窗口矩形两种写法：
             *   旧版 mFrame=[l,t][r,b] last=[l,t][r,b]；
             *   新版只在 Frames: 行里给 frame=[l,t][r,b]。
             * 只认前者会让所有窗口宽高为 0，面积判定直接失败。
             */
            Matcher mf = P_FRAME.matcher(line);
            if (!mf.find()) mf = P_FRAME_LOOSE.matcher(line);
            if (mf.find() && cur.right == 0 && cur.bottom == 0) {
                try {
                    cur.left = Integer.parseInt(mf.group(1));
                    cur.top = Integer.parseInt(mf.group(2));
                    cur.right = Integer.parseInt(mf.group(3));
                    cur.bottom = Integer.parseInt(mf.group(4));
                } catch (Exception ignored) {}
                continue;
            }
            Matcher mvv = P_VIEW_VISIBILITY.matcher(line);
            if (mvv.find()) {
                try { cur.viewVisibility = Integer.parseInt(mvv.group(1), 16); } catch (Exception ignored) {}
                continue;
            }
            Matcher mhs = P_HAS_SURFACE.matcher(line);
            if (mhs.find()) { cur.hasSurface = "true".equals(mhs.group(1)); continue; }
        }
        if (cur != null) list.add(cur);
        return list;
    }
}
