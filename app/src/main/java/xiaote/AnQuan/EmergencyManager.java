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
 * 应急功能管理器
 * 提取自 XTSafeMainService 的应急操作方法
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

    /**
     * 强制停止所有第三方应用
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
            Toast.makeText(context, context.getString(R.string.force_stopped_count, count), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, context.getString(R.string.op_failed_msg, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 关闭除自身外的所有无障碍服务
     */
    public void disableAllAccessibility() {
        try {
            String currentList = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (currentList == null || currentList.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.no_acc_enabled), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(context, context.getString(R.string.disabled_acc_count, removed), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, context.getString(R.string.need_shizuku_root), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, context.getString(R.string.op_failed_msg, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 关闭所有其他设备管理员
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
            Toast.makeText(context, context.getString(R.string.admin_removed_try), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, context.getString(R.string.need_shizuku_root), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 清除解锁密码
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
            Toast.makeText(context, context.getString(R.string.pwd_cleared_try), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, context.getString(R.string.need_shizuku_root_admin), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 重设锁屏密码为1234
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
            Toast.makeText(context, context.getString(R.string.pwd_reset_done), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, context.getString(R.string.need_shizuku_root_admin), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 占满内存(让系统停止所有应用)
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
                            final int t = total;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(context,
                                            context.getString(R.string.mem_used, t), Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }
                } catch (OutOfMemoryError e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context,
                                    context.getString(R.string.mem_full), Toast.LENGTH_LONG).show();
                        }
                    });
                    try { Thread.sleep(30000); } catch (Exception ex) {}
                }
            }
        }).start();
    }
}
