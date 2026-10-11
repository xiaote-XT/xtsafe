package xiaote.AnQuan.InternalApi;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 强制停止应用。
 *
 * 可用通道：Shizuku → Root。
 *
 * ===== 为什么没有 Dhizuku / DeviceOwner =====
 *
 * DevicePolicyManager 里没有任何「停止某应用进程」的接口。最接近的是
 * setPackagesSuspended / setApplicationHidden，但它们语义完全不同：
 *   · setPackagesSuspended —— 挂起应用，图标变灰、不能启动，但已存在的进程
 *     不会立即被杀；
 *   · setApplicationHidden —— 从桌面与列表中隐藏，进程照样能跑。
 * 用它们冒充「强制停止」会让调用方拿到「成功」却看不到进程消失。
 *
 * 真正能杀进程的只有 am force-stop，而它是 shell 命令，
 * 因此本操作只做 Shizuku / Root 两级。
 *
 * 全部为阻塞调用，必须在工作线程使用。
 */
public final class ForceStopApi {

    private ForceStopApi() {}

    /** 本操作支持的通道，按优先级排列 */
    public static Capability[] chain() {
        return new Capability[]{ Capability.SHIZUKU, Capability.ROOT };
    }

    /** 强制停止单个应用 */
    public static ApiResult stop(Context ctx, String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return ApiResult.unsupported("包名为空");
        }
        String cmd = "am force-stop " + pkg;
        return runOnChain(ctx, cmd, "停止 " + pkg);
    }

    /**
     * 强制停止全部第三方应用（排除自身与系统应用）。
     *
     * 逐个调用 stop()，每个包都会走一遍通道探测；包数量大时开销明显，
     * 但换来的是「某个包失败不影响其余包」，且失败原因可以逐包记录。
     */
    public static ApiResult stopAllThirdParty(Context ctx) {
        if (ctx == null) return ApiResult.unsupported("ctx 为空");
        List<String> targets = listThirdParty(ctx);
        if (targets.isEmpty()) return ApiResult.successCount(null, 0, "没有第三方应用");

        int ok = 0;
        StringBuilder err = new StringBuilder();
        Capability used = null;
        for (String p : targets) {
            ApiResult r = stop(ctx, p);
            if (r.ok) {
                ok++;
                if (used == null) used = r.via;
            } else if (err.length() < 200) {
                err.append(p).append(' ').append(r.detail).append("; ");
            }
        }
        String detail = "共 " + targets.size() + " 个，成功 " + ok + " 个"
                + (err.length() > 0 ? ("；失败示例：" + err) : "");
        if (ok == 0) return ApiResult.failure(used, detail);
        return ApiResult.successCount(used, ok, detail);
    }

    /** 本机第三方应用包名（排除系统应用与自身） */
    public static List<String> listThirdParty(Context ctx) {
        List<String> list = new ArrayList<String>();
        if (ctx == null) return list;
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            String self = ctx.getPackageName();
            for (ApplicationInfo ai : apps) {
                if (ai == null) continue;
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue;
                if (self.equals(ai.packageName)) continue;
                list.add(ai.packageName);
            }
        } catch (Throwable ignored) {}
        return list;
    }

    /** 按 chain 顺序执行命令，第一条成功即返回 */
    private static ApiResult runOnChain(Context ctx, String cmd, String label) {
        StringBuilder err = new StringBuilder();
        Capability last = null;
        for (Capability c : chain()) {
            last = c;
            if (!c.isReady(ctx)) {
                err.append(c.label).append(" 未就绪; ");
                continue;
            }
            Exec.Out o = Exec.run(c, cmd);
            if (o != null && o.ok) return ApiResult.success(c, o.text);
            err.append(c.label).append(' ').append(o == null ? "不支持" : o.toString()).append("; ");
        }
        return ApiResult.failure(last, label + " 失败：" + err);
    }
}
