package xiaote.AnQuan.InternalApi;

import android.content.Context;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Set;

/**
 * 无障碍服务列表的读写。
 *
 * 可用通道：Shizuku → Root。
 *
 * ===== 为什么没有 Dhizuku / DeviceOwner =====
 *
 * enabled_accessibility_services 存在 Settings.Secure 里。
 *
 *   DevicePolicyManager  → 没有任何接口能改这个键（setSecureSetting 是
 *                          @SystemApi，普通应用即使拿了 DO 身份也调不到）；
 *   Dhizuku              → 它只是设备所有者代理，DPM 里没有的能力它也没有。
 *
 * 所以这里只做 shell 两级。
 *
 * ===== 组件名两种写法 =====
 *
 * 系统里存的是 pkg/cls 或 pkg/.Cls 两种形式，读取与去重时必须都认，
 * 否则会出现「明明已启用却被判成未启用」或反过来写入重复条目。
 *
 * 全部为阻塞调用，必须在工作线程使用。
 */
public final class AccessibilityApi {

    /** 本应用的无障碍组件，与 XTSafeMainService / XTSafeCoreService 保持一致 */
    public static final String SELF_STD   = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
    public static final String SELF_SHORT = "xiaote.AnQuan/.XTSafeMainService";

    private AccessibilityApi() {}

    /** 本操作支持的通道，按优先级排列 */
    public static Capability[] chain() {
        return new Capability[]{ Capability.SHIZUKU, Capability.ROOT };
    }

    // ==================== 读 ====================

    /**
     * 读取当前启用的无障碍组件列表。
     *
     * 优先直接读 Settings.Secure（本应用读这个键不需要任何权限），
     * 读不到再退到 shell 通道。
     */
    public static Set<String> listEnabled(Context ctx) {
        Set<String> set = new HashSet<String>();
        if (ctx != null) {
            try {
                String raw = Settings.Secure.getString(ctx.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (raw != null && raw.length() > 0) {
                    for (String p : raw.split(":")) {
                        String v = p.trim();
                        if (v.length() > 0) set.add(v);
                    }
                    return set;
                }
            } catch (Throwable ignored) {}
        }
        for (Capability c : chain()) {
            if (!c.isReady(ctx)) continue;
            Exec.Out o = Exec.run(c, "settings get secure enabled_accessibility_services");
            if (o == null || !o.ok || o.text == null) continue;
            String t = o.text.trim();
            if (t.isEmpty() || "null".equals(t)) continue;
            for (String p : t.split(":")) {
                String v = p.trim();
                if (v.length() > 0) set.add(v);
            }
            if (!set.isEmpty()) return set;
        }
        return set;
    }

    /** 某个包的无障碍服务当前是否在启用列表里（认两种写法） */
    public static boolean isPackageEnabled(Context ctx, String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        String prefix = pkg + "/";
        for (String c : listEnabled(ctx)) {
            if (c.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * 收集当前启用了无障碍的第三方应用包名。
     *
     * 排除：自身、系统应用、系统预装更新应用、白名单应用。
     * 与 SafeActionManager 的旧实现口径一致，方便对照。
     */
    public static Set<String> listThirdPartyPackages(Context ctx) {
        Set<String> pkgs = new HashSet<String>();
        if (ctx == null) return pkgs;
        String self = ctx.getPackageName();
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        for (String comp : listEnabled(ctx)) {
            int idx = comp.indexOf('/');
            String pkg = idx > 0 ? comp.substring(0, idx) : comp;
            if (pkg.length() == 0 || pkg.equals(self)) continue;
            try {
                if (xiaote.AnQuan.WhitelistPackages.isWhitelisted(ctx, pkg)) continue;
            } catch (Throwable ignored) {}
            try {
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue;
            } catch (Throwable t) {
                continue;
            }
            pkgs.add(pkg);
        }
        return pkgs;
    }

    // ==================== 写 ====================

    /**
     * 关闭除本应用以外的全部无障碍服务。
     *
     * 保留自身是刻意的：星特的防卸载、浮窗、音量键等功能全挂在无障碍上，
     * 一并关掉等于把自己也停了。
     *
     * @return 成功返回被移除的组件数
     */
    public static ApiResult disableAllExceptSelf(Context ctx) {
        if (ctx == null) return ApiResult.unsupported("ctx 为空");
        Set<String> current = listEnabled(ctx);
        if (current.isEmpty()) return ApiResult.successCount(null, 0, "当前无已启用的无障碍");

        StringBuilder keep = new StringBuilder();
        int removed = 0;
        for (String comp : current) {
            int idx = comp.indexOf('/');
            String pkg = idx > 0 ? comp.substring(0, idx) : comp;
            if (pkg.equals(ctx.getPackageName())) {
                if (keep.length() > 0) keep.append(':');
                keep.append(comp);
            } else {
                removed++;
            }
        }
        if (removed == 0) return ApiResult.successCount(null, 0, "无需移除");

        ApiResult r = writeList(ctx, keep.toString());
        if (r.ok) return ApiResult.successCount(r.via, removed, "已关闭 " + removed + " 个无障碍服务");
        return ApiResult.failure(r.via, "关闭失败：" + r.detail);
    }

    /**
     * 把指定包的无障碍服务从启用列表里移除（不区分是哪一个 Service）。
     */
    public static ApiResult disablePackage(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.length() == 0) {
            return ApiResult.unsupported("ctx / pkg 为空");
        }
        Set<String> current = listEnabled(ctx);
        StringBuilder keep = new StringBuilder();
        int removed = 0;
        String prefix = pkg + "/";
        for (String comp : current) {
            if (comp.startsWith(prefix)) {
                removed++;
            } else {
                if (keep.length() > 0) keep.append(':');
                keep.append(comp);
            }
        }
        if (removed == 0) return ApiResult.successCount(null, 0, "该应用未启用无障碍");
        ApiResult r = writeList(ctx, keep.toString());
        if (r.ok) return ApiResult.successCount(r.via, removed, "已关闭 " + pkg + " 的无障碍");
        return ApiResult.failure(r.via, "关闭失败：" + r.detail);
    }

    /**
     * 把本应用的无障碍组件加入启用列表（不存在则追加）。
     * 只在列表里没有自身时写入，避免制造重复条目。
     */
    public static ApiResult enableSelf(Context ctx) {
        if (ctx == null) return ApiResult.unsupported("ctx 为空");
        Set<String> current = listEnabled(ctx);
        for (String comp : current) {
            if (SELF_STD.equals(comp) || SELF_SHORT.equals(comp)) {
                return ApiResult.success(null, "已在启用列表");
            }
        }
        String target = current.isEmpty() ? SELF_STD : (join(current) + ":" + SELF_STD);
        return writeList(ctx, target);
    }

    /**
     * 把本应用的无障碍组件写回启用列表（先清掉两种写法的残留，再用标准写法追加一次）。
     *
     * 用途：恶意应用把星特安全从无障碍列表摘掉后，重新把它加回去并触发系统重建。
     * 与 enableSelf 的区别：enableSelf 只在缺失时追加，本方法无论是否存在都重写一次，
     * 因此可用于「服务在列表里但系统没真正绑定」的掉线场景。
     */
    public static ApiResult writeSelfBack(Context ctx) {
        if (ctx == null) return ApiResult.unsupported("ctx 为空");
        Set<String> current = listEnabled(ctx);
        StringBuilder keep = new StringBuilder();
        for (String comp : current) {
            if (SELF_STD.equals(comp) || SELF_SHORT.equals(comp)) continue;
            if (comp == null || comp.length() == 0) continue;
            if (keep.length() > 0) keep.append(':');
            keep.append(comp);
        }
        if (keep.length() > 0) keep.append(':');
        keep.append(SELF_STD);
        return writeList(ctx, keep.toString());
    }

    /** 把组件列表字符串写入 Settings.Secure，并顺带把 accessibility_enabled 置 1 */
    private static ApiResult writeList(Context ctx, String list) {
        String v = (list == null) ? "" : list;
        String cmd = "settings put secure enabled_accessibility_services '" + v + "'";
        StringBuilder err = new StringBuilder();
        Capability last = null;
        for (Capability c : chain()) {
            last = c;
            if (!c.isReady(ctx)) {
                err.append(c.label).append(" 未就绪; ");
                continue;
            }
            Exec.Out o = Exec.run(c, cmd);
            if (o != null && o.ok) {
                /*
                 * 附带把总开关打开。
                 * 只写 enabled_accessibility_services 而不开 accessibility_enabled，
                 * 系统仍会把无障碍视为整体关闭，表现就是「列表里有但服务没起」。
                 * 这一步失败不回滚：服务列表已经写对了，总开关由系统设置页兜底。
                 */
                try { Exec.run(c, "settings put secure accessibility_enabled 1"); } catch (Throwable ignored) {}
                return ApiResult.success(c, o.text);
            }
            err.append(c.label).append(' ').append(o == null ? "不支持" : o.toString()).append("; ");
        }
        return ApiResult.failure(last, err.toString());
    }

    /** 把组件集合拼成冒号分隔字符串 */
    private static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (s == null || s.length() == 0) continue;
            if (sb.length() > 0) sb.append(':');
            sb.append(s);
        }
        return sb.toString();
    }
}
