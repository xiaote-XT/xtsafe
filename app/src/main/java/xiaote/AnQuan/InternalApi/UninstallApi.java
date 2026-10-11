package xiaote.AnQuan.InternalApi;

import android.content.Context;

/**
 * 应用卸载。
 *
 * 可用通道：Shizuku → Root。
 *
 * ===== 为什么没有 Dhizuku / DeviceOwner =====
 *
 * DevicePolicyManager 只有一个 uninstallPackage(ComponentName, String)，
 * 它是 @SystemApi —— 普通应用直接调会被权限检查拒掉，只有以设备所有者
 * 身份、通过 binder 代理转发才可能生效（即 Dhizuku 那条路）。
 *
 * 但这条路的可靠性很差：
 *   · 不同 ROM 对 uninstallPackage 的 AIDL 参数个数不一致（2 参 / 3 参），
 *     反射匹配容易落空；
 *   · 部分 ROM 直接把它从 AIDL 里裁掉；
 *   · 失败时抛的是 SecurityException，与「应用本来就不让卸」的失败无法区分。
 *
 * 而 pm uninstall 在 Shizuku / Root 下稳定可用，且这两个通道优先级本来
 * 就在前面。所以卸载只做 shell 两级，不硬写 DPM 分支 —— 写了大概率是
 * 一段永远走不到、走到也可能报错的死代码。
 *
 * 全部为阻塞调用，必须在工作线程使用。
 */
public final class UninstallApi {

    private UninstallApi() {}

    /** 本操作支持的通道，按优先级排列 */
    public static Capability[] chain() {
        return new Capability[]{ Capability.SHIZUKU, Capability.ROOT };
    }

    /**
     * 静默卸载。
     *
     * 命令用 pm uninstall --user 0 而不是 pm uninstall：
     * 多用户设备上只移除当前用户的应用，不动其他用户的安装。
     *
     * 注意：卸载系统应用、设备管理员应用、设备所有者应用会被系统拒绝，
     * 这类失败属于预期，不重试。
     */
    public static ApiResult uninstall(Context ctx, String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return ApiResult.unsupported("包名为空");
        }
        String cmd = "pm uninstall --user 0 " + pkg;

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
                 * pm uninstall 成功时会打印 "Success"，失败时通常返回码非 0
                 * 并打印 Failure / DELETE_FAILED_xxx。这里已经按退出码判定，
                 * 不再额外检查文本 —— 部分 ROM 成功时不打印任何内容。
                 */
                return ApiResult.success(c, o.text);
            }
            err.append(c.label).append(' ').append(o == null ? "不支持" : o.toString()).append("; ");
        }
        return ApiResult.failure(last, err.toString());
    }
}
