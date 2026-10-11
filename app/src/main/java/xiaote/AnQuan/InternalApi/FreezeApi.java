package xiaote.AnQuan.InternalApi;

import android.content.Context;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 冻结 / 解冻应用（pm disable / pm enable）。
 *
 * 可用通道：Shizuku → Root。
 *
 * ===== 为什么没有 Dhizuku / DeviceOwner =====
 *
 * DevicePolicyManager 里语义最接近的是 setApplicationHidden，但它和
 * pm disable 是两回事：
 *
 *   pm disable         —— 应用被真正停用，组件不可用、进程拉不起来；
 *   setApplicationHidden —— 只是从桌面与列表中隐藏，进程照样能跑。
 *
 * 拿 hidden 冒充冻结，会让调用方拿到「成功」却看到目标应用仍在后台活动，
 * 属于典型的假成功。因此本操作只做真正能停用的 shell 两级，不硬写 DPM 分支。
 *
 * ===== 判定方式 =====
 *
 * 不看命令输出，只看回读：执行后跑 `pm list packages -d`，目标包在
 * 禁用列表里才算成功。原因：部分 ROM 的 pm disable 输出为空或文案不同，
 * 靠文本匹配会误判。
 *
 * 全部为阻塞调用，必须在工作线程使用。
 */
public final class FreezeApi {

    private FreezeApi() {}

    /** 本操作支持的通道，按优先级排列 */
    public static Capability[] chain() {
        return new Capability[]{ Capability.SHIZUKU, Capability.ROOT };
    }

    /** 冻结（停用）应用 */
    public static ApiResult freeze(Context ctx, String pkg) {
        return switchState(ctx, pkg, true);
    }

    /** 解冻（启用）应用 */
    public static ApiResult unfreeze(Context ctx, String pkg) {
        return switchState(ctx, pkg, false);
    }

    private static ApiResult switchState(Context ctx, String pkg, boolean freeze) {
        if (pkg == null || pkg.length() == 0) return ApiResult.unsupported("包名为空");

        StringBuilder err = new StringBuilder();
        Capability last = null;
        for (Capability c : chain()) {
            last = c;
            if (!c.isReady(ctx)) {
                err.append(c.label).append(" 未就绪; ");
                continue;
            }
            // 先停进程，避免 disable 期间被系统拉起
            try { Exec.run(c, "am force-stop " + pkg); } catch (Throwable ignored) {}

            String[] tries = freeze
                    ? new String[]{ "pm disable-user --user 0 " + pkg,
                                    "pm disable --user 0 " + pkg }
                    : new String[]{ "pm enable --user 0 " + pkg,
                                    "pm enable " + pkg };
            for (String cmd : tries) {
                Exec.Out o = Exec.run(c, cmd);
                if (o == null) break;
                if (verify(c, pkg, freeze)) {
                    return ApiResult.success(c, o.text);
                }
                err.append(c.label).append(' ').append(cmd)
                        .append(" → ").append(o.toString()).append("; ");
            }
        }
        return ApiResult.failure(last, (freeze ? "冻结" : "解冻") + "失败：" + err);
    }

    /** 回读校验：pm list packages -d 里有没有目标包 */
    private static boolean verify(Capability c, String pkg, boolean expectDisabled) {
        Exec.Out o = Exec.run(c, "pm list packages -d");
        if (o == null || !o.ok || o.text == null) return false;
        boolean disabled = false;
        for (String line : o.text.split("\\r?\\n")) {
            String p = line.trim();
            if (p.startsWith("package:")) p = p.substring(8);
            if (pkg.equals(p)) { disabled = true; break; }
        }
        return disabled == expectDisabled;
    }

    /**
     * 读取系统内全部已被禁用（冻结）的包名。
     * 与冻结/解冻一样只走 shell 两级；两条都不可用时返回空集合。
     */
    public static Set<String> listDisabled(Context ctx) {
        Set<String> set = new HashSet<String>();
        for (Capability c : chain()) {
            if (!c.isReady(ctx)) continue;
            Exec.Out o = Exec.run(c, "pm list packages -d");
            if (o == null || !o.ok || o.text == null) continue;
            for (String line : o.text.split("\\r?\\n")) {
                String p = line.trim();
                if (p.startsWith("package:")) p = p.substring(8);
                if (p.length() > 0) set.add(p);
            }
            if (!set.isEmpty()) return set;
        }
        return set;
    }

    /** 目标包当前是否处于冻结状态 */
    public static boolean isFrozen(Context ctx, String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        for (Capability c : chain()) {
            if (!c.isReady(ctx)) continue;
            return verify(c, pkg, true);
        }
        return false;
    }
}
