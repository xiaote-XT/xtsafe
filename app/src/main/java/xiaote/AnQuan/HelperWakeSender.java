package xiaote.AnQuan;

import android.content.Context;
import android.content.Intent;

/**
 * 与「星特安全保活助手」的通信出口。
 *
 * 四件事：
 *   1. wakeHelperNow()  发唤醒广播，把助手进程拉起来；
 *   2. sendInterval()   下发唤醒间隔配置；
 *   3. isHelperInstalled() / openHelper()  查询与打开助手；
 *   4. isEnabled() / setEnabled()  保活总开关。
 *
 * 所有 Intent 都带 FLAG_INCLUDE_STOPPED_PACKAGES：
 * 助手被强行停止后进入 stopped state，普通广播投不进去，
 * 带这个 flag 才能穿透，把助手进程拉起来。
 *
 * 开关关闭时的行为：只停止唤起助手，其余一律不做 ——
 * 不去停助手的服务、不去卸载助手、也不改已经下发的间隔配置。
 */
public final class HelperWakeSender {

    /** 保活助手包名 */
    public static final String HELPER_PACKAGE = "xiaote.AnQuan.helper.keepalive";
    /** 唤醒助手的 action，与助手侧 WakeReceiver 对应 */
    public static final String ACTION_WAKE_HELPER = "xiaote.AnQuan.action.WAKE_HELPER";
    /** 下发配置的 action，与助手侧 ConfigReceiver 对应 */
    public static final String ACTION_CONFIG = "xiaote.AnQuan.action.HELPER_CONFIG";
    /** 配置项：唤醒间隔（毫秒） */
    public static final String EXTRA_INTERVAL_MS = "interval_ms";

    /** 默认唤醒间隔 1 秒（与助手侧 KeepAliveService.DEFAULT_INTERVAL_MS 一致） */
    public static final long DEFAULT_INTERVAL_MS = 1000L;
    /** 下限 100ms（0.1 秒） */
    public static final long MIN_INTERVAL_MS = 100L;
    /** 上限 60 秒 */
    public static final long MAX_INTERVAL_MS = 60000L;

    /** 间隔在星特安全侧的镜像键 */
    public static final String KEY_INTERVAL_MS = "helper_interval_ms";
    /** 保活总开关：关闭后不再唤起助手，也不自动安装 */
    public static final String KEY_ENABLED = "helper_keepalive_enabled";
    /** 保活助手的签名 MD5 */
    public static final String HELPER_SIGN_MD5 = "259f172bcb9692517ee05ca7f589d635";
    /** 星特系列应用的可信签名 MD5（与助手同源） */
    public static final String TRUSTED_SIGN_MD5 = "259f172bcb9692517ee05ca7f589d635";

    /**
     * 指定包名是否由可信签名签署（只比签名，不比包名）。
     * 用于安全检测、安装管控这类需要「放过自家应用」的场景。
     */
    public static boolean isTrustedSignature(Context c, String pkg) {
        if (c == null || pkg == null || pkg.isEmpty()) return false;
        /*
         * 包名快速通道：保活助手是自家固定组件，包名匹配就直接放过。
         * PACKAGE_ADDED 送达瞬间 PackageManager 有时还没登记完，
         * 纯签名查询会失败导致漏放，这里用包名兜住。
         */
        if (HELPER_PACKAGE.equals(pkg)) return true;
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
            String path = (pi != null && pi.applicationInfo != null)
                    ? pi.applicationInfo.sourceDir : null;
            if (path == null || path.isEmpty()) return false;
            String md5 = SecurityScanActivity.getSignatureMd5(c.getPackageManager(), path);
            return TRUSTED_SIGN_MD5.equalsIgnoreCase(md5);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 指定包名是否是正版保活助手（包名 + 签名双重匹配） */
    public static boolean isGenuineHelper(Context c, String pkg) {
        if (c == null || pkg == null || !HELPER_PACKAGE.equals(pkg)) return false;
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
            String path = (pi != null && pi.applicationInfo != null)
                    ? pi.applicationInfo.sourceDir : null;
            if (path == null || path.isEmpty()) return false;
            String md5 = SecurityScanActivity.getSignatureMd5(c.getPackageManager(), path);
            return HELPER_SIGN_MD5.equalsIgnoreCase(md5);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isGenuineHelperInstalled(Context c) {
        return isGenuineHelper(c, HELPER_PACKAGE);
    }

    private HelperWakeSender() {}

    /** 保活开关状态，默认开启 */
    public static boolean isEnabled(Context c) {
        if (c == null) return false;
        try {
            return c.getSharedPreferences("dot_config", Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 写入保活开关。只写键，不做副作用。 */
    public static void setEnabled(Context c, boolean on) {
        if (c == null) return;
        try {
            c.getSharedPreferences("dot_config", Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, on).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 立即向助手发一条唤醒广播；开关关闭时静默跳过 */
    public static void wakeHelperNow(Context c) {
        if (c == null) return;
        if (!isEnabled(c)) return;
        try {
            Intent i = new Intent(ACTION_WAKE_HELPER);
            i.setPackage(HELPER_PACKAGE);
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            c.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 向助手下发唤醒间隔配置。
     * 间隔下发不受保活开关限制：关掉开关只是停止唤起，
     * 用户调好的间隔值仍要落盘，下次开启时直接沿用。
     */
    public static void sendInterval(Context c, long ms) {
        if (c == null) return;
        if (ms < MIN_INTERVAL_MS) ms = MIN_INTERVAL_MS;
        if (ms > MAX_INTERVAL_MS) ms = MAX_INTERVAL_MS;
        try {
            c.getSharedPreferences("dot_config", Context.MODE_PRIVATE)
                    .edit().putLong(KEY_INTERVAL_MS, ms).apply();
        } catch (Throwable ignored) {
        }
        if (!isEnabled(c)) return;
        try {
            Intent i = new Intent(ACTION_CONFIG);
            i.setPackage(HELPER_PACKAGE);
            i.putExtra(EXTRA_INTERVAL_MS, ms);
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            c.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /** 读取本地镜像的间隔值，带范围钳制 */
    public static long readInterval(Context c) {
        long v = DEFAULT_INTERVAL_MS;
        try {
            v = c.getSharedPreferences("dot_config", Context.MODE_PRIVATE)
                    .getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        } catch (Throwable ignored) {
        }
        if (v < MIN_INTERVAL_MS) v = MIN_INTERVAL_MS;
        if (v > MAX_INTERVAL_MS) v = MAX_INTERVAL_MS;
        return v;
    }

    /** 助手是否已安装 */
    public static boolean isHelperInstalled(Context c) {
        if (c == null) return false;
        try {
            c.getPackageManager().getPackageInfo(HELPER_PACKAGE, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 拉起助手主界面（助手已去掉桌面图标，只能从这里进） */
    public static void openHelper(Context c) {
        if (c == null) return;
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(HELPER_PACKAGE);
            if (i == null) {
                i = new Intent();
                i.setClassName(HELPER_PACKAGE, HELPER_PACKAGE + ".MainActivity");
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) {
        }
    }
}
