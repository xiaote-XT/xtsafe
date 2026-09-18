package xiaote.AnQuan;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * 病毒应用管理器
 * 提取自 XTSafeMainService 的病毒扫描/禁止/卸载相关方法
 */
public class VirusManager {

    private final Context context;
    private final PackageManager pm;
    /** 扫描/拦截可能在工作线程执行，Toast 必须回到主线程显示 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VirusManager(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
    }

    /** 主线程 Toast（工作线程调用安全） */
    private void toast(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
            }
        });
    }

    /**
     * 强制停止所有已安装的病毒应用
     */
    public void forceStopVirusApps() {
        try {
            Set<String> virusPkgs = VirusPackages.getVirusPackages(context);
            if (virusPkgs.isEmpty()) return;
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo app : apps) {
                if (app.packageName.equals(context.getPackageName())) continue;
                if (virusPkgs.contains(app.packageName)) {
                    ShellExecutor.execShizuku(new String[]{"am", "force-stop", app.packageName});
                    ShellExecutor.execRoot("am force-stop " + app.packageName);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 扫描并禁止已安装的病毒应用
     */
    public void scanAndBlockVirusApps() {
        try {
            Set<String> virusPkgs = VirusPackages.getVirusPackages(context);
            if (virusPkgs.isEmpty()) return;
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo app : apps) {
                if (app.packageName.equals(context.getPackageName())) continue;
                if (virusPkgs.contains(app.packageName)) {
                    int enabled = pm.getApplicationEnabledSetting(app.packageName);
                    if (enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            || enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                            || enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                        continue;
                    }
                    blockVirusPackage(app.packageName);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 禁止病毒应用：方案1 Shizuku卸载；方案2 设备管理员隐藏；方案3 Root卸载
     */
    public void blockVirusPackage(final String packageName) {
        boolean blocked = false;

        // 优先：Shizuku 直接卸载（静默且彻底）
        if (Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            try {
                Shizuku.newProcess(
                        new String[]{"pm", "uninstall", "--user", "0", packageName},
                        null, null);
                blocked = true;
                toast(context.getString(R.string.virus_shizuku_uninstalled, packageName));
            } catch (Exception e) {
                // 失败则尝试下一种方案
            }
        }

        // 方案1：设备管理员隐藏 + 卸载阻止
        if (!blocked) {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager)
                        context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                ComponentName admin = new ComponentName(context, DeviceAdmin.class);
                if (dpm.isAdminActive(admin)) {
                    try { dpm.setApplicationHidden(admin, packageName, true); blocked = true; } catch (Exception e) {}
                    try { dpm.setUninstallBlocked(admin, packageName, true); } catch (Exception e) {}
                }
            } catch (Exception e) {}
        }

        // 方案2：Root 卸载（兜底）
        if (!blocked) {
            blocked = ShellExecutor.execRoot("pm uninstall --user 0 " + packageName);
        }

        if (blocked) {
            // 额外尝试强制停止，防止残留进程
            ShellExecutor.execShizuku(new String[]{"am", "force-stop", packageName});
            ShellExecutor.execRoot("am force-stop " + packageName);
        } else {
            toast(context.getString(R.string.virus_uninstall_fail, packageName));
        }
    }
}
