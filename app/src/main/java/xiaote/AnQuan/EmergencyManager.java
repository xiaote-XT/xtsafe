package xiaote.AnQuan;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Toast;

import java.util.List;

/**
 * 应急功能管理器。
 *
 * 应急操作菜单（卸载列表顶部入口）与安全操作（音量阈值 / 连按十下音量- /
 * 时间回拨）共用这套实现。
 *
 * ===== 为什么所有 Toast 都走 handler.post =====
 *
 * 本类的调用方有两条：
 *   · 应急操作菜单 —— 主线程点击回调；
 *   · SafeActionManager.run —— 后台线程串行执行（关闭锁屏密码那一项）。
 *
 * 后台线程直接 Toast.makeText().show() 会抛
 * "Can't create handler inside thread that has not called Looper.prepare()"，
 * 因此统一走 handler.post，两条路径都安全。
 */
public class EmergencyManager {

    private final Context context;
    private final PackageManager pm;
    private final Handler handler;

    public EmergencyManager(Context context, Handler handler) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.handler = handler;
    }

    /** 主线程 Toast：任何线程调用都安全 */
    private void toast(final CharSequence msg, final int duration) {
        if (msg == null) return;
        try {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(context, msg, duration).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void toastShort(CharSequence msg) {
        toast(msg, Toast.LENGTH_SHORT);
    }

    private void toastLong(CharSequence msg) {
        toast(msg, Toast.LENGTH_LONG);
    }

    /**
     * 强制停止所有第三方应用。
     */
    public void forceStopAll() {
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            int count = 0;
            for (ApplicationInfo app : apps) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                        && !app.packageName.equals(context.getPackageName())) {
                    if (ShellExecutor.execShizuku(new String[]{"am", "force-stop", app.packageName})
                        || ShellExecutor.execRoot("am force-stop " + app.packageName)) {
                        count++;
                    }
                }
            }
            toastShort(context.getString(R.string.force_stopped_count, count));
        } catch (Exception e) {
            toastLong(context.getString(R.string.op_failed_msg, e.getMessage()));
        }
    }

    /**
     * 关闭除自身外的所有无障碍服务。
     */
    public void disableAllAccessibility() {
        try {
            String currentList = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (currentList == null || currentList.isEmpty()) {
                toastShort(context.getString(R.string.no_acc_enabled));
                return;
            }
            String ourComponent = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
            String[] parts = currentList.split(":");
            StringBuilder newList = new StringBuilder();
            int removed = 0;
            for (String p : parts) {
                if (p.equals(ourComponent)) {
                    if (newList.length() > 0) newList.append(":");
                    newList.append(p);
                } else {
                    removed++;
                }
            }
            String result = newList.toString();
            boolean done = ShellExecutor.execShizuku(new String[]{"settings", "put", "secure",
                    "enabled_accessibility_services", result});
            if (!done) done = ShellExecutor.execRoot("settings put secure enabled_accessibility_services '" + result + "'");
            if (done) {
                toastShort(context.getString(R.string.disabled_acc_count, removed));
            } else {
                toastShort(context.getString(R.string.need_shizuku_root));
            }
        } catch (Exception e) {
            toastLong(context.getString(R.string.op_failed_msg, e.getMessage()));
        }
    }

    /**
     * 关闭所有其他设备管理员。
     */
    public void disableAllDeviceAdmin() {
        String ourPkg = context.getPackageName();
        String cmd = "dpm list-active-admins 2>/dev/null | while read line; do "
                + "comp=$(echo $line | tr -d '\\r'); "
                + "pkg=$(echo $comp | cut -d/ -f1); "
                + "if [ \"$pkg\" != \"" + ourPkg + "\" ] && [ -n \"$pkg\" ]; then "
                + "dpm remove-active-admin $comp; fi; done";
        boolean done = ShellExecutor.execShizuku(new String[]{"sh", "-c", cmd});
        if (!done) done = ShellExecutor.execRoot(cmd);
        if (done) {
            toastShort(context.getString(R.string.admin_removed_try));
        } else {
            toastShort(context.getString(R.string.need_shizuku_root));
        }
    }

    /**
     * 清除解锁密码。
     *
     * 两条路并用，任一条成功即算完成：
     *   · 本应用是设备管理员 → 直接用 DPM 把密码策略重置为空；
     *   · 否则 → shell 里依次试 locksettings clear / set-password 空串，
     *     顺带把 lockscreen.password_type 系列键清零。
     *
     * 现在被 SafeActionManager 的「关闭锁屏密码」复用，
     * 因此必须容忍在后台线程调用 —— Toast 已改走 handler.post。
     */
    public void clearPassword() {
        boolean done = false;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, DeviceAdmin.class);
            if (dpm.isAdminActive(admin)) {
                dpm.setPasswordQuality(admin, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);
                dpm.setPasswordMinimumLength(admin, 0);
                dpm.resetPassword("", 0);
                done = true;
            }
        } catch (Exception e) {}

        String setNoneCmd = "settings put secure lockscreen.password_type 0 && "
                + "settings put secure lockscreen.password_type_alternate 0 && "
                + "settings put secure lockscreen.pattern_type 0 && "
                + "settings put secure lockscreen.pattern_type_alternate 0";
        ShellExecutor.execShizuku(new String[]{"sh", "-c", setNoneCmd});
        ShellExecutor.execRoot(setNoneCmd);

        if (!done) {
            done = ShellExecutor.execShizuku(new String[]{"locksettings", "clear", "--old", ""});
            if (!done) done = ShellExecutor.execShizuku(new String[]{"locksettings", "clear"});
            if (!done) done = ShellExecutor.execShizuku(new String[]{"locksettings", "set-password", ""});
            if (!done) done = ShellExecutor.execRoot("locksettings clear --old ''");
        }
        if (done) {
            toastShort(context.getString(R.string.pwd_cleared_try));
        } else {
            toastShort(context.getString(R.string.need_shizuku_root_admin));
        }
    }

    /**
     * 重设锁屏密码为 1234。
     */
    public void resetPassword() {
        boolean done = false;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, DeviceAdmin.class);
            if (dpm.isAdminActive(admin)) {
                dpm.setPasswordQuality(admin, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC);
                dpm.setPasswordMinimumLength(admin, 4);
                dpm.resetPassword("1234", 0);
                done = true;
            }
        } catch (Exception e) {}

        if (!done) {
            String setNumeric = "settings put secure lockscreen.password_type 262144 && "
                    + "settings put secure lockscreen.password_type_alternate 262144";
            ShellExecutor.execShizuku(new String[]{"sh", "-c", setNumeric});
            ShellExecutor.execRoot(setNumeric);
            done = ShellExecutor.execShizuku(new String[]{"locksettings", "set-pin", "1234"});
            if (!done) done = ShellExecutor.execRoot("locksettings set-pin 1234");
        }
        if (done) {
            toastShort(context.getString(R.string.pwd_reset_done));
        } else {
            toastShort(context.getString(R.string.need_shizuku_root_admin));
        }
    }

    /**
     * 占满内存（让系统停止所有应用）。
     *
     * 纯 Java 内存分配，不涉及提权通道。分配一直持续到 OOM，
     * 捕获后停留 30 秒再让线程退出，给系统回收留出时间窗口。
     */
    public void fillMemory() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.util.ArrayList<byte[]> hog = new java.util.ArrayList<byte[]>();
                try {
                    int total = 0;
                    while (true) {
                        hog.add(new byte[1024 * 1024]);
                        total++;
                        if (total % 50 == 0) {
                            toastShort(context.getString(R.string.mem_used, total));
                        }
                    }
                } catch (OutOfMemoryError e) {
                    toastLong(context.getString(R.string.mem_full));
                    try { Thread.sleep(30000); } catch (Exception ex) {}
                }
            }
        }).start();
    }

    /**
     * 冻结所有拥有无障碍权限的应用。
     *
     * 复用 SafeActionManager 的完整链路：
     *   · collectAccessibilityPackages() 做快照（排除自身、系统应用、白名单）；
     *   · freezePackages() 逐个 force-stop + pm disable，逐级回退并回读校验。
     *
     * 注意：必须在「关闭所有应用无障碍」之前执行 ——
     * 目标来自当前启用的无障碍列表，顺序反了就会拿到空集合。
     * 这里只负责派发与提示：冻结涉及多次 shell 跨进程调用，
     * 直接跑在主线程会卡住界面。
     */
    public void freezeAllAccessibilityApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int count = 0;
                try {
                    count = SafeActionManager.freezeAccessibilityApps(context);
                } catch (Throwable ignored) {}
                toastShort("已冻结 " + count + " 个拥有无障碍权限的应用");
            }
        }, "emergency-freeze-acc").start();
    }
}
