package xiaote.AnQuan;

import android.content.Context;
import android.util.Log;

import xiaote.AnQuan.InternalApi.Capability;
import xiaote.AnQuan.InternalApi.Exec;

/**
 * 省电优化放开（Doze 白名单 + 后台运行 op）。
 *
 * 对应系统设置里的「电池 → 不受限制」。国产 ROM 自家的省电策略管不到，
 * 这里是 AOSP 层的兜底，能做多少做多少。
 *
 * 三条命令各自作用：
 *   1. cmd deviceidle whitelist +<pkg>
 *      —— 把应用加入 Doze 白名单，等价于设置里的「不受限制」；
 *   2. cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow
 *      —— 放开后台运行 op，部分 ROM 单独管这个开关；
 *   3. cmd appops set <pkg> RUN_IN_BACKGROUND allow
 *      —— 个别版本只认这个 op 名，两条都写不冲突。
 *
 * 用 ';' 串联而不是 '&&'：一条失败不影响其余，尽量多放开一点。
 * 通道：Shizuku → Root，两者都不可用时静默失败，不改任何状态。
 */
public final class PowerSaveHelper {

    private static final String TAG = "XTPowerSave";

    /** 星特安全自身包名 */
    public static final String SELF_PACKAGE = "xiaote.AnQuan";
    /** 保活助手包名 */
    public static final String HELPER_PACKAGE = HelperWakeSender.HELPER_PACKAGE;

    private PowerSaveHelper() {}

    /**
     * 对指定包放开省电限制。
     *
     * @return true 表示至少有一条通道执行成功
     */
    public static boolean applyFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String cmd = "cmd deviceidle whitelist +" + pkg
                + "; cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow"
                + "; cmd appops set " + pkg + " RUN_IN_BACKGROUND allow";
        try {
            Exec.Out o = Exec.run(Capability.SHIZUKU, cmd);
            if (o != null && o.ok) {
                Log.d(TAG, "Shizuku 放开省电限制成功: " + pkg);
                return true;
            }
            Log.w(TAG, "Shizuku 放开省电限制失败: " + pkg + " "
                    + (o == null ? "该通道不支持" : o.toString()));
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku 通道异常", t);
        }
        try {
            Exec.Out o = Exec.run(Capability.ROOT, cmd);
            if (o != null && o.ok) {
                Log.d(TAG, "Root 放开省电限制成功: " + pkg);
                return true;
            }
            Log.w(TAG, "Root 放开省电限制失败: " + pkg + " "
                    + (o == null ? "该通道不支持" : o.toString()));
        } catch (Throwable t) {
            Log.w(TAG, "Root 通道异常", t);
        }
        return false;
    }

    /**
     * 星特安全自身 + 保活助手一起放开。
     *
     * 自身也要放开：XTSafeCoreService 承担反向唤醒助手的心跳，
     * 若自己被系统按停，助手那侧就没人拉了。
     *
     * 助手没装时跳过它那一条，不对不存在的包发命令。
     * 内部会起 shell 进程（含可能的 su 探测），必须在工作线程调用。
     */
    public static void applyBoth(Context c) {
        if (c == null) return;
        applyFor(SELF_PACKAGE);
        try {
            if (HelperWakeSender.isHelperInstalled(c)) {
                applyFor(HELPER_PACKAGE);
            }
        } catch (Throwable ignored) {
        }
    }
}
