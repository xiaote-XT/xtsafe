package xiaote.AnQuan.InternalApi;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;
import xiaote.dhizukutool.Dhizuku;

/**
 * 提权通道枚举与可用性探测。
 *
 * 通道优先级（高到低）：
 *   SHIZUKU  —— shell(uid 2000)，能力最广：pm / am / settings / dumpsys 全都能跑
 *   DHIZUKU  —— 设备所有者代理，只能走 DPM 那套接口，跑不了 shell 命令
 *   ROOT     —— uid 0，能力等同 shell 且不需要额外授权
 *   DEVICE_OWNER —— 应用自身即设备所有者，直接用 DPM 公开 API
 *
 * 注意：不是每个操作都支持全部四级。例如「强制停止应用」只有 shell 能做到
 * （am force-stop），Dhizuku 与 DeviceOwner 都没有对应接口；「读取全屏覆盖」
 * 只能靠 dumpsys window，同样只有 shell 两级。各操作 API 会按真实能力挑通道，
 * 不硬写。
 *
 * Root 探测要起 su 进程（未授权时会弹窗等待），开销远大于另外三级，
 * 因此带 5 秒缓存；调用方在授权状态可能变化后调 invalidateRootCache() 手动失效。
 */
public enum Capability {

    SHIZUKU("Shizuku"),
    DHIZUKU("Dhizuku"),
    ROOT("Root"),
    DEVICE_OWNER("Device Owner");

    /** 用于日志与界面提示的可读名称 */
    public final String label;

    Capability(String label) {
        this.label = label;
    }

    // ==================== 探测 ====================

    /** 本通道当前是否可用 */
    public boolean isReady(Context ctx) {
        switch (this) {
            case SHIZUKU: return isShizukuReady();
            case DHIZUKU: return isDhizukuReady(ctx);
            case ROOT: return isRootReady();
            case DEVICE_OWNER: return isDeviceOwner(ctx);
            default: return false;
        }
    }

    /** Shizuku 已运行且本应用已获授权 */
    public static boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Dhizuku 已连接且本应用已获 API 权限 */
    public static boolean isDhizukuReady(Context ctx) {
        if (ctx == null) return false;
        try {
            if (!Dhizuku.init(ctx)) return false;
            return Dhizuku.isPermissionGranted();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 本应用自身是否为设备所有者 */
    public static boolean isDeviceOwner(Context ctx) {
        if (ctx == null) return false;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            return dpm.isDeviceOwnerApp(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== Root 探测（带缓存） ====================

    private static final long ROOT_CACHE_MS = 5000L;
    private static final long ROOT_PROBE_TIMEOUT_MS = 1500L;

    private static volatile long sRootCheckedAt = 0L;
    private static volatile boolean sRootOk = false;

    /**
     * Root 是否可用。
     *
     * 结果缓存 5 秒：su 探测要起进程，而调用方常在同一轮里连续问多次；
     * 不缓存会明显拖慢整条链路。授权状态变化后调 invalidateRootCache() 失效。
     */
    public static boolean isRootReady() {
        long now = System.currentTimeMillis();
        if (now - sRootCheckedAt < ROOT_CACHE_MS) return sRootOk;
        sRootCheckedAt = now;
        sRootOk = probeRoot();
        return sRootOk;
    }

    /** 手动让 Root 缓存失效（例如用户在授权页刚授过权） */
    public static void invalidateRootCache() {
        sRootCheckedAt = 0L;
        sRootOk = false;
    }

    /**
     * 实际探测：su -c id，读一行输出判断是否含 uid=0。
     *
     * 必须限时：su 未授权时会弹窗等待用户点允许，无限等会挂住调用线程。
     * 读取放到独立线程，主线程只等 ROOT_PROBE_TIMEOUT_MS。
     */
    private static boolean probeRoot() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            final Process fp = p;
            final java.util.concurrent.atomic.AtomicReference<String> line =
                    new java.util.concurrent.atomic.AtomicReference<String>(null);
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(fp.getInputStream()));
                        line.set(br.readLine());
                        br.close();
                    } catch (Throwable ignored) {}
                }
            });
            reader.setDaemon(true);
            reader.start();
            reader.join(ROOT_PROBE_TIMEOUT_MS);
            String s = line.get();
            return s != null && s.contains("uid=0");
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }
}
