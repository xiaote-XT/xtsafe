package xiaote.AnQuan.InternalApi;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.Build;

/**
 * 运行时权限的授予 / 撤销。
 *
 * 可用通道（按优先级）：Shizuku → Root → Dhizuku → DeviceOwner。
 *
 * 这是少数四条通道都能做的操作，但两组的语义并不完全相同：
 *
 *   Shizuku / Root  → pm revoke / pm grant
 *                     走 PackageManager，等价于 adb 命令，改的是
 *                     runtime permission 的 grant 状态，最贴近系统真实行为。
 *
 *   Dhizuku / DeviceOwner → DevicePolicyManager.setPermissionGrantState
 *                     以设备所有者身份设定权限状态，额外支持
 *                     PERMISSION_GRANT_STATE_DEFAULT（交还用户决定）；
 *                     但部分 ROM 对非 DO 管理的应用会拒绝，且不覆盖
 *                     所有权限类型（如部分特殊权限）。
 *
 * 因此优先级把 shell 两级放前面：能走 shell 就走 shell，走不通再退到 DPM。
 * 调用方拿到 ApiResult.via 即可知道实际是哪一级生效的。
 *
 * 全部为阻塞调用，必须在工作线程使用。
 */
public final class PermissionApi {

    /** 与 DevicePolicyManager 常量同名，避免调用方还要 import DPM */
    public static final int STATE_DEFAULT = 0;
    public static final int STATE_GRANTED = 1;
    public static final int STATE_DENIED = 2;

    private PermissionApi() {}

    /** 本操作支持的通道，按优先级排列 */
    public static Capability[] chain() {
        return new Capability[]{
                Capability.SHIZUKU,
                Capability.DHIZUKU,
                Capability.ROOT,
                Capability.DEVICE_OWNER
        };
    }

    // ==================== 对外 ====================

    /** 撤销某个运行时权限 */
    public static ApiResult revoke(Context ctx, String pkg, String permission) {
        return setState(ctx, pkg, permission, STATE_DENIED);
    }

    /** 授予某个运行时权限 */
    public static ApiResult grant(Context ctx, String pkg, String permission) {
        return setState(ctx, pkg, permission, STATE_GRANTED);
    }

    /**
     * 设置权限状态。
     *
     * STATE_DEFAULT 只有 DPM 通道支持（shell 的 pm 没有「交还用户」这个概念，
     * 只能 grant 或 revoke），因此传 DEFAULT 时会跳过 shell 两级。
     */
    public static ApiResult setState(Context ctx, String pkg, String permission, int state) {
        if (ctx == null || pkg == null || pkg.length() == 0
                || permission == null || permission.length() == 0) {
            return ApiResult.unsupported("参数为空");
        }

        StringBuilder err = new StringBuilder();
        Capability last = null;
        for (Capability c : chain()) {
            last = c;
            if (!c.isReady(ctx)) {
                err.append(c.label).append(" 未就绪; ");
                continue;
            }
            try {
                if (c == Capability.SHIZUKU || c == Capability.ROOT) {
                    if (state == STATE_DEFAULT) {
                        err.append(c.label).append(" 不支持 DEFAULT; ");
                        continue;
                    }
                    String verb = (state == STATE_GRANTED) ? "grant" : "revoke";
                    Exec.Out o = Exec.run(c, "pm " + verb + " " + pkg + " " + permission);
                    if (o != null && o.ok) return ApiResult.success(c, o.text);
                    err.append(c.label).append(' ').append(o == null ? "不支持" : o.toString()).append("; ");
                    continue;
                }
                if (c == Capability.DHIZUKU || c == Capability.DEVICE_OWNER) {
                    if (Build.VERSION.SDK_INT < 23) {
                        err.append(c.label).append(" 需要 API 23+; ");
                        continue;
                    }
                    int dpmState = dpmState(state);
                    DpmChannel.setPermissionGrantState(ctx, c, pkg, permission, dpmState);
                    return ApiResult.success(c, null);
                }
            } catch (Throwable t) {
                err.append(c.label).append(' ').append(DpmChannel.describe(t)).append("; ");
            }
        }
        return ApiResult.failure(last, err.toString());
    }

    private static int dpmState(int state) {
        switch (state) {
            case STATE_GRANTED: return DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;
            case STATE_DENIED:  return DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED;
            default:            return DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
        }
    }
}
