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

    /** 连续命中次数阈值（标准力度） */
    public static final int CONFIRM_TIMES = 2;
    /** 面积占比阈值（0.90） */
    private static final float AREA_RATIO = 0.90f;

    /**
     * 拦截力度。
     *
     *   false 标准（默认）—— 连续 CONFIRM_TIMES 次命中才确认，抗瞬时动画误判；
     *   true  敏感         —— 一次命中即确认，不累计、不等待，
     *                        配合 XTSafeMainService 的 1 秒扫描间隔使用。
     *
     * 敏感模式不改变候选筛选口径（类型 / 面积 / 可见性 / 排除规则全部照旧），
     * 只是把「需要连续命中几次」这一道门降到 1 次。
     */
    public static boolean sensitiveMode = false;

    /** 系统窗口类型区间（WindowManager.LayoutParams.FIRST/LAST_SYSTEM_WINDOW） */
    private static final int TYPE_SYS_MIN = 2000;
    private static final int TYPE_SYS_MAX = 2999;

    /**
     * 安全类型名单：这些系统装饰窗口不参与全屏覆盖判定。
     *
     * 用「名字」而不是「数值」维护。dumpsys 的 ty= 有的版本输出数字、
     * 有的版本输出常量名，数值还可能随 ROM 变化；常量名是 AOSP 的稳定标识。
     * 名字与数值的对应关系由下面的反射表在运行时自动建立，不用手写。
     */
    private static final Set<String> SAFE_TYPE_NAMES = new HashSet<String>();
    static {
        SAFE_TYPE_NAMES.add("STATUS_BAR");
        SAFE_TYPE_NAMES.add("SEARCH_BAR");
        SAFE_TYPE_NAMES.add("KEYGUARD");
        SAFE_TYPE_NAMES.add("TOAST");
        SAFE_TYPE_NAMES.add("PRIORITY_PHONE");
        SAFE_TYPE_NAMES.add("SYSTEM_DIALOG");
        SAFE_TYPE_NAMES.add("KEYGUARD_DIALOG");
        SAFE_TYPE_NAMES.add("INPUT_METHOD");
        SAFE_TYPE_NAMES.add("INPUT_METHOD_DIALOG");
        SAFE_TYPE_NAMES.add("WALLPAPER");
        SAFE_TYPE_NAMES.add("STATUS_BAR_PANEL");
        SAFE_TYPE_NAMES.add("STATUS_BAR_SUB_PANEL");
        SAFE_TYPE_NAMES.add("SECURE_SYSTEM_OVERLAY");
        SAFE_TYPE_NAMES.add("DRAG");
        SAFE_TYPE_NAMES.add("POINTER");
        SAFE_TYPE_NAMES.add("NAVIGATION_BAR");
        SAFE_TYPE_NAMES.add("NAVIGATION_BAR_PANEL");
        SAFE_TYPE_NAMES.add("VOLUME_OVERLAY");
        SAFE_TYPE_NAMES.add("BOOT_PROGRESS");
        SAFE_TYPE_NAMES.add("MAGNIFICATION_OVERLAY");
        SAFE_TYPE_NAMES.add("ACCESSIBILITY_MAGNIFICATION_OVERLAY");
        SAFE_TYPE_NAMES.add("DISPLAY_OVERLAY");
        SAFE_TYPE_NAMES.add("LOGICAL_DISPLAY_DECORATION");
        SAFE_TYPE_NAMES.add("VOICE_INTERACTION");
        SAFE_TYPE_NAMES.add("PRIVATE_PRESENTATION");
    }

    /* ==================== 窗口类型名 / 数值自动映射 ==================== */

    private static Map<String, Integer> sNameToVal;
    private static Map<Integer, String> sValToName;

    /**
     * 反射 WindowManager.LayoutParams 的 TYPE_* 常量，自动建立「名字 ↔ 数值」双向表。
     *
     * 这是通配的关键：不再手写映射表。新 ROM 新增的类型只要常量名存在就能被识别，
     * 数值与名字的对应也不会写错。
     *
     * 反射失败（极端裁剪 ROM 或黑名单拦截）时退化为空表，
     * typeFromToken 仍有前缀与区间兜底，不会崩、也不会因此漏判。
     */
    private static synchronized void ensureTypeTable() {
        if (sNameToVal != null) return;
        Map<String, Integer> n2v = new HashMap<String, Integer>();
        Map<Integer, String> v2n = new HashMap<Integer, String>();
        try {
            java.lang.reflect.Field[] fs =
                    android.view.WindowManager.LayoutParams.class.getDeclaredFields();
            for (java.lang.reflect.Field f : fs) {
                if (f == null) continue;
                int mod = f.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(mod)) continue;
                if (f.getType() != int.class) continue;
                String n = f.getName();
                if (n == null || !n.startsWith("TYPE_")) continue;
                try {
                    f.setAccessible(true);
                    int v = f.getInt(null);
                    String key = n.substring(5).toUpperCase(java.util.Locale.US);
                    n2v.put(key, v);
                    if (!v2n.containsKey(v)) v2n.put(v, key);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        sNameToVal = n2v;
        sValToName = v2n;
    }

    /** 数值 → 常量名（去 TYPE_ 前缀、大写）；查不到返回 null */
    private static String typeName(int type) {
        ensureTypeTable();
        return sValToName.get(type);
    }

    /**
     * 是否属于需要关注的覆盖窗口类型。
     *
     * 旧实现用固定白名单（2002/2003/2006/2010/2032/2038），
     * 实际漏掉大量 ROM 自行分配的覆盖类型，也容易把类型写错
     * （2002 其实是 TYPE_PHONE，不是注释里写的 TYPE_SYSTEM_OVERLAY；
     * 靠白名单恰好命中，换成别的 ROM 分配的类型就直接漏判）。
     *
     * 现在改为「系统窗口区间内、且不在安全名单里」：
     * TYPE_PHONE(2002)、TYPE_SYSTEM_ALERT(2003)、TYPE_SYSTEM_OVERLAY(2006)、
     * TYPE_SYSTEM_ERROR(2010)、TYPE_ACCESSIBILITY_OVERLAY(2032)、
     * TYPE_APPLICATION_OVERLAY(2038) 以及各 ROM 的 2xxx 覆盖都能命中。
     */
    public static boolean isOverlayType(int type) {
        if (type < TYPE_SYS_MIN || type > TYPE_SYS_MAX) return false;
        String name = typeName(type);
        // 名字查得到且在安全名单里 → 排除；查不到名字的按候选处理
        if (name != null && SAFE_TYPE_NAMES.contains(name)) return false;
        return true;
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
        String t = token.toUpperCase(java.util.Locale.US);
        if (t.startsWith("TYPE_")) t = t.substring(5);
        /*
         * 先查反射表。
         *
         * 表里是 LayoutParams 的全部 TYPE_* 常量，因此
         * APPLICATION_OVERLAY、SYSTEM_ALERT、ACCESSIBILITY_OVERLAY、
         * NAVIGATION_BAR、BASE_APPLICATION(1) 等等全都自动命中，
         * 不需要为任何新类型补代码 —— 应用类窗口的常量值本身就是 1~4 这类
         * 小数字，天然落在系统窗口区间之外。
         */
        ensureTypeTable();
        Integer v = sNameToVal.get(t);
        if (v != null) return v;
        /*
         * 表里没有（反射被禁，或 ROM 自造的极新常量）时按前缀兜底：
         * 应用类窗口统一降为非系统值，其余按候选覆盖处理。
         */
        if ("SUB_PANEL".equals(t)) return 1000;
        if (t.startsWith("BASE_APPLICATION")) return 1;
        if (t.startsWith("DRAWN_APPLICATION")) return 1;
        if (t.startsWith("APPLICATION_")) return 1000;
        if ("APPLICATION".equals(t)) return 1;
        return 2500;
    }
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
    /** 屏幕实际像素尺寸。横竖屏切换后会变，不能只在构造时读一次 */
    private volatile int screenW;
    private volatile int screenH;

    /** 连续命中计数：包名 -> 次数 */
    private final Map<String, Integer> hitCounter = new HashMap<String, Integer>();

    public OverlayDetector(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
        refreshScreenSize();
    }

    /**
     * 重新读取屏幕实际尺寸。
     *
     * 只在构造时读一次并存成常量不行：服务启动时若设备是竖屏，
     * 之后旋转到横屏，窗口 mFrame 变成横屏尺寸，而缓存的宽高仍是竖屏值，
     * 面积判定会出现「一边达标、另一边不达标」，整屏覆盖窗被直接滤掉。
     * 横屏下最容易复现「明明盖满屏幕却抓不到」。
     */
    private void refreshScreenSize() {
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
        if (w > 0 && h > 0) {
            screenW = w;
            screenH = h;
        }
    }

    public int getScreenWidth() { return screenW; }
    public int getScreenHeight() { return screenH; }

    /**
     * 窗口是否达到「全屏覆盖」标准。
     *
     * 方向无关：短边比短边、长边比长边。之前直接用 screenW/screenH 比较，
     * 一旦窗口方向与缓存的屏幕方向不一致（典型就是横竖屏切换），
     * 就会出现宽度达标、高度不达标，整屏窗口被漏掉。
     */
    private boolean isFullCoverage(int w, int h) {
        if (w <= 0 || h <= 0) return false;
        int sw = Math.min(screenW, screenH);
        int sh = Math.max(screenW, screenH);
        if (sw <= 0 || sh <= 0) return false;
        int ww = Math.min(w, h);
        int wh = Math.max(w, h);
        return ww >= sw * AREA_RATIO && wh >= sh * AREA_RATIO;
    }

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
        refreshScreenSize();
        String out = dumpWindows();
        if (out == null || out.isEmpty()) {
            // 取不到数据时不清零，避免抖动导致永远无法累计
            return confirmed;
        }
        List<OverlayWindow> all = parse(out);
        Set<String> currentHit = new HashSet<String>();
        for (OverlayWindow w : all) {
            if (!w.visible) continue;
            if (!isOverlayType(w.type)) continue;
            if (w.width() <= 0 || w.height() <= 0) continue;
            // 面积占比 ≥ 90%%（方向无关：短边比短边、长边比长边）
            if (!isFullCoverage(w.width(), w.height())) continue;
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

        // 敏感力度只需 1 次命中即确认；标准力度保持 CONFIRM_TIMES 次
        int need = sensitiveMode ? 1 : CONFIRM_TIMES;
        for (Map.Entry<String, Integer> e : hitCounter.entrySet()) {
            if (e.getValue() >= need) confirmed.add(e.getKey());
        }
        return confirmed;
    }

    /** 命中后复检：该包名的全屏覆盖窗口是否仍然存在 */
    public boolean stillCovering(String pkg) {
        refreshScreenSize();
        String out = dumpWindows();
        if (out == null) return true; // 取不到数据时按仍存在处理，避免漏拦截
        List<OverlayWindow> all = parse(out);
        for (OverlayWindow w : all) {
            if (!pkg.equals(w.packageName)) continue;
            if (!w.visible) continue;
            if (!isOverlayType(w.type)) continue;
            if (isFullCoverage(w.width(), w.height())) return true;
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
            /*
             * 同一行可能同时带 package= 与 ty=（部分 ROM 的 mAttrs 行就是这样），
             * 旧实现遇到 package= 就 continue，后面的 ty= 永远解析不到，
             * 窗口类型恒为 0，isOverlayType 全部判否，整屏覆盖窗口被静默漏掉。
             * 这里改为每个字段独立判断，互不抢占。
             */
            Matcher mp = P_PACKAGE.matcher(line);
            if (mp.find()) cur.packageName = mp.group(1);
            Matcher mt = P_TYPE.matcher(line);
            if (mt.find() && cur.type == 0) {
                // 新版 dumpsys 输出常量名（ty=APPLICATION_OVERLAY），旧版输出数字
                cur.type = typeFromToken(mt.group(1));
            }
            /*
             * 窗口矩形两种写法：
             *   旧版 mFrame=[l,t][r,b] last=[l,t][r,b]；
             *   新版只在 Frames: 行里给 frame=[l,t][r,b]。
             *
             * 注意 Matcher.find() 有状态：同一个 matcher 连续调两次 find()，
             * 第二次是从第一次匹配末尾继续搜，通常立刻返回 false。
             * 旧写法 if(!mf.find()) ...; if(mf.find() ...) 会把已经匹配成功的
             * mFrame 行白白丢掉，宽高恒为 0，面积判定全失败 —— 这正是
             * 「窗口明明全屏覆盖、isOnScreen=true，却抓不到」的直接原因。
             * 这里只 find() 一次并保存结果。
             */
            Matcher mf = P_FRAME.matcher(line);
            boolean frameFound = mf.find();
            if (!frameFound) {
                mf = P_FRAME_LOOSE.matcher(line);
                frameFound = mf.find();
            }
            if (frameFound && cur.right == 0 && cur.bottom == 0) {
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
            /*
             * 查不到 ApplicationInfo 时不再直接放过。
             *
             * 旧实现这里 return true（当成系统应用排除），本意是降低误判，
             * 实际后果是：只要 PackageManager 查询出现一次瞬时失败
             * （包可见性、安装态变化、查询超时），这个包就被静默跳过，
             * 全屏覆盖拦截直接失效且没有任何痕迹 —— 正是「明明盖着屏幕却抓不到」。
             *
             * 现在返回 false（不排除），继续交给类型/面积/可见性三道判定过滤；
             * 真正的系统装饰窗口已被 SAFE_TYPES 与 EXCLUDE_PREFIX 挡在外面，
             * 不会因此误杀。
             */
            return false;
        }
        return false;
    }
}
