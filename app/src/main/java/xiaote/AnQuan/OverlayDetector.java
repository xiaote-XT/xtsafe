package xiaote.AnQuan;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全屏覆盖应用检测器。
 *
 * 原理：通过 Shizuku 执行 `dumpsys window windows`，解析窗口栈，
 * 找出「悬浮窗类型 + 全屏覆盖（面积占比≥90%）+ 可见 + 第三方应用」的窗口。
 *
 * 防误判措施：
 *   1. 仅统计悬浮窗/无障碍覆盖类窗口（ty=2002/2003/2006/2010/2032/2038），
 *      普通 Activity 窗口（无 ty）不参与判定；
 *   2. 排除系统应用（FLAG_SYSTEM）、本应用、输入法、桌面、SystemUI 等；
 *   3. 要求窗口面积占屏幕 ≥90%% 且宽高都达标，排除小悬浮球；
 *   4. 必须连续 N 次检测命中同一包名才确认（避免瞬时动画/切换误判）；
 *   5. 命中后需再次复检窗口仍然存在，才执行处置。
 */
public class OverlayDetector {

    /** 连续命中次数阈值 */
    public static final int CONFIRM_TIMES = 2;
    /** 面积占比阈值（0.90） */
    private static final float AREA_RATIO = 0.90f;

    /** 需要重点关注的窗口类型 */
    private static final Set<Integer> OVERLAY_TYPES = new HashSet<Integer>();
    static {
        OVERLAY_TYPES.add(2002); // TYPE_SYSTEM_OVERLAY
        OVERLAY_TYPES.add(2003); // TYPE_SYSTEM_ALERT
        OVERLAY_TYPES.add(2006); // TYPE_SYSTEM_ERROR
        OVERLAY_TYPES.add(2010); // TYPE_SYSTEM_OVERLAY (旧)
        OVERLAY_TYPES.add(2032); // TYPE_ACCESSIBILITY_OVERLAY
        OVERLAY_TYPES.add(2038); // TYPE_APPLICATION_OVERLAY
    }

    /** 系统/界面基础包名，永不判定 */
    private static final String[] EXCLUDE_PREFIX = {
            "android",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.settings",
            "com.android.inputmethod",
            "com.google.android.inputmethod",
            "com.android.permissioncontroller",
            "com.android.providers",
            "com.android.server",
    };

    // 注意：此处不再使用含花括号的窗口行正则（Java 正则中未转义的 } 会抛 PatternSyntaxException），
    // 窗口行改为纯字符串判断 + 手动切分提取包名。
    private static final Pattern P_PACKAGE = Pattern.compile("package=([^ ]+)");
    private static final Pattern P_TYPE = Pattern.compile("\\bty=(\\d+)\\b");
    /*
     * 窗口矩形：实际 dumpsys 输出是「mFrame=[l,t][r,b] last=[l,t][r,b]」，
     * 没有无前缀的 frame=。旧正则写 frame=\[...\] 恒不匹配，
     * 导致所有窗口 left/right/top/bottom 全为 0，面积判定直接失败。
     */
    private static final Pattern P_FRAME =
            Pattern.compile("mFrame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");
    /*
     * 可见性：新版 dumpsys 不再输出 isVisible=，改为 mViewVisibility=0x0（可见）/ 0x8（GONE）。
     * 旧正则只找 isVisible=，一条都匹配不到，visible 恒为 false，
     * scanConfirmed / stillCovering 的「仅统计可见窗口」这道门把所有候选全部滤掉。
     */
    private static final Pattern P_VIEW_VISIBILITY =
            Pattern.compile("mViewVisibility=0x([0-9a-fA-F]+)");
    /** 兜底：没有 mViewVisibility 行时用 mHasSurface=true 判定窗口已真正绘制 */
    private static final Pattern P_HAS_SURFACE = Pattern.compile("mHasSurface=(true|false)");
    /** 兼容旧版 dumpsys 的 isVisible= */
    private static final Pattern P_VISIBLE = Pattern.compile("isVisible=(true|false)");

    public static class OverlayWindow {
        public String packageName = "";
        public int type = 0;
        public int left, top, right, bottom;
        /** 最终可见性：由 mViewVisibility / mHasSurface / isVisible 三者综合得出 */
        public boolean visible = false;
        /** mViewVisibility 原始值：0x0=VISIBLE，0x4=INVISIBLE，0x8=GONE；-1 表示该行缺失 */
        public int viewVisibility = -1;
        /** mHasSurface：窗口是否已真正创建 Surface（未创建的窗口不参与判定） */
        public boolean hasSurface = false;

        public int width() { return right - left; }
        public int height() { return bottom - top; }

        @Override
        public String toString() {
            return packageName + " ty=" + type + " frame=[" + left + "," + top + "][" + right + "," + bottom + "]";
        }
    }

    private final Context context;
    private final PackageManager pm;
    private final int screenW;
    private final int screenH;

    /** 连续命中计数：包名 -> 次数 */
    private final Map<String, Integer> hitCounter = new HashMap<String, Integer>();

    public OverlayDetector(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
        int w = 0, h = 0;
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
                w = dm.widthPixels;
                h = dm.heightPixels;
            }
        } catch (Exception ignored) {}
        if (w <= 0 || h <= 0) {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        this.screenW = w;
        this.screenH = h;
    }

    public int getScreenWidth() { return screenW; }
    public int getScreenHeight() { return screenH; }

    /** Shizuku 是否可用（唯一前提，普通 shell 拿不到 dumpsys window） */
    public boolean isAvailable() {
        return ShellExecutor.isShizukuReady();
    }

    /** 拉取 dumpsys window windows 原始输出，失败返回 null */
    private String dumpWindows() {
        return ShellExecutor.execShizukuWithOutput(
                new String[]{"sh", "-c", "dumpsys window windows"});
    }

    /**
     * 扫描一次，返回本次全屏覆盖的第三方应用包名集合。
     * 结果已按连续命中次数过滤：只有达到 CONFIRM_TIMES 的包名才会返回。
     */
    public List<String> scanConfirmed() {
        List<String> confirmed = new ArrayList<String>();
        String out = dumpWindows();
        if (out == null || out.isEmpty()) {
            // 取不到数据时不清零，避免抖动导致永远无法累计
            return confirmed;
        }
        List<OverlayWindow> all = parse(out);
        Set<String> currentHit = new HashSet<String>();
        for (OverlayWindow w : all) {
            if (!w.visible) continue;
            if (!OVERLAY_TYPES.contains(w.type)) continue;
            if (w.width() <= 0 || w.height() <= 0) continue;
            // 面积占比 ≥ 90%%（宽高都需达标）
            if (w.width() < screenW * AREA_RATIO) continue;
            if (w.height() < screenH * AREA_RATIO) continue;
            if (isExcluded(w.packageName)) continue;
            currentHit.add(w.packageName);
        }

        // 累计连续命中
        for (String pkg : currentHit) {
            Integer c = hitCounter.get(pkg);
            hitCounter.put(pkg, c == null ? 1 : c + 1);
        }
        // 未命中的清零
        List<String> toRemove = new ArrayList<String>();
        for (String pkg : hitCounter.keySet()) {
            if (!currentHit.contains(pkg)) toRemove.add(pkg);
        }
        for (String pkg : toRemove) hitCounter.remove(pkg);

        for (Map.Entry<String, Integer> e : hitCounter.entrySet()) {
            if (e.getValue() >= CONFIRM_TIMES) confirmed.add(e.getKey());
        }
        return confirmed;
    }

    /** 命中后复检：该包名的全屏覆盖窗口是否仍然存在 */
    public boolean stillCovering(String pkg) {
        String out = dumpWindows();
        if (out == null) return true; // 取不到数据时按仍存在处理，避免漏拦截
        List<OverlayWindow> all = parse(out);
        for (OverlayWindow w : all) {
            if (!pkg.equals(w.packageName)) continue;
            if (!w.visible) continue;
            if (!OVERLAY_TYPES.contains(w.type)) continue;
            if (w.width() >= screenW * AREA_RATIO && w.height() >= screenH * AREA_RATIO) return true;
        }
        return false;
    }

    /** 清除某包名的累计计数（处置后调用，防止重复处置） */
    public void clear(String pkg) {
        hitCounter.remove(pkg);
    }

    public void clearAll() {
        hitCounter.clear();
    }

    /** 解析 dumpsys 输出为窗口列表 */
    public List<OverlayWindow> parse(String out) {
        List<OverlayWindow> list = new ArrayList<OverlayWindow>();
        if (out == null) return list;
        OverlayWindow cur = null;
        for (String line : out.split("\n")) {
            String trimmed = line.trim();
            // 窗口起始行：例如 "Window #3 Window{c576cd9 u0 xiaote.AnQuan}:"
            if (trimmed.startsWith("Window #")) {
                if (cur != null) list.add(cur);
                cur = new OverlayWindow();
                // 手动提取 Window{...} 内的包名（避免使用含花括号的正则）
                int wi = trimmed.indexOf("Window{");
                int braceEnd = trimmed.indexOf('}', wi);
                if (wi >= 0 && braceEnd > wi) {
                    String inner = trimmed.substring(wi + 7, braceEnd).trim();
                    String[] parts = inner.split("\\s+");
                    // 典型结构：hash u0 包名；无 uN 时取最后一段
                    if (parts.length >= 3) {
                        cur.packageName = parts[2];
                    } else if (parts.length == 2) {
                        cur.packageName = parts[1];
                    } else if (parts.length == 1) {
                        cur.packageName = parts[0];
                    }
                }
                continue;
            }
            if (cur == null) continue;
            Matcher mp = P_PACKAGE.matcher(line);
            if (mp.find()) { cur.packageName = mp.group(1); continue; }
            Matcher mt = P_TYPE.matcher(line);
            if (mt.find()) {
                try { cur.type = Integer.parseInt(mt.group(1)); } catch (Exception ignored) {}
                continue;
            }
            /* 窗口矩形：dumpsys 输出形如 mFrame=[l,t][r,b] last=[l,t][r,b] */
            Matcher mf = P_FRAME.matcher(line);
            if (mf.find() && cur.right == 0 && cur.bottom == 0) {
                try {
                    cur.left = Integer.parseInt(mf.group(1));
                    cur.top = Integer.parseInt(mf.group(2));
                    cur.right = Integer.parseInt(mf.group(3));
                    cur.bottom = Integer.parseInt(mf.group(4));
                } catch (Exception ignored) {}
                continue;
            }
            /*
             * 可见性三来源，优先级从高到低：
             *   1. mViewVisibility=0xN —— 新版 dumpsys 的主判据：
             *      0x0=VISIBLE、0x4=INVISIBLE、0x8=GONE；
             *   2. mHasSurface=true   —— 该行缺失时的兜底，窗口已真正创建 Surface；
             *   3. isVisible=true     —— 兼容旧版 dumpsys 输出。
             * 三者都拿不到时保持 false（宁可漏判也不误杀）。
             */
            Matcher mvv = P_VIEW_VISIBILITY.matcher(line);
            if (mvv.find()) {
                try { cur.viewVisibility = Integer.parseInt(mvv.group(1), 16); } catch (Exception ignored) {}
                continue;
            }
            Matcher mhs = P_HAS_SURFACE.matcher(line);
            if (mhs.find()) { cur.hasSurface = "true".equals(mhs.group(1)); continue; }
            Matcher mv = P_VISIBLE.matcher(line);
            if (mv.find()) { cur.visible = "true".equals(mv.group(1)); continue; }
        }
        if (cur != null) list.add(cur);

        /*
         * 逐个窗口做一次综合可见性判定。
         * 主判据是 mViewVisibility（0 才是真正可见）；
         * 缺失时再看 mHasSurface；两者都没有才回落到 isVisible= 的解析结果。
         * 这样旧版本 dumpsys（无 mViewVisibility）仍能工作，新版本也不会因
         * 找不到 isVisible= 而把所有窗口都判成不可见 —— 后者正是之前全屏覆盖拦截
         * 完全失效的直接原因。
         */
        for (OverlayWindow w : list) {
            if (w.viewVisibility >= 0) {
                w.visible = (w.viewVisibility == 0);
            } else if (w.hasSurface) {
                w.visible = true;
            }
        }
        return list;
    }

    /** 是否排除（用户白名单、系统、本应用、非第三方应用） */
    private boolean isExcluded(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(context.getPackageName())) return true;
        // 用户白名单：不参与全屏覆盖拦截
        try {
            if (WhitelistPackages.isWhitelisted(context, pkg)) return true;
        } catch (Throwable ignored) {}
        for (String p : EXCLUDE_PREFIX) {
            if (pkg.equals(p) || pkg.startsWith(p + ".")) return true;
        }
        // 只针对第三方应用：系统应用一律排除（大幅降低误判）
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return true;
            if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true;
        } catch (Exception e) {
            return true; // 查不到信息的一律不处理
        }
        return false;
    }
}
