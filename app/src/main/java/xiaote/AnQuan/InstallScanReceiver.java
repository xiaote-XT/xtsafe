package xiaote.AnQuan;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import rikka.shizuku.Shizuku;

/**
 * 监听新安装应用，自动检测：
 *   进度通知(不可移除静音) ->
 *   完成(安全/可能安全：静音"检测完毕"可查看结果，10秒后自动隐藏；有风险：声音提醒)
 */
public class InstallScanReceiver extends BroadcastReceiver {

    private static final String CHANNEL_PROGRESS = "install_scan_progress";
    private static final String CHANNEL_RESULT = "install_scan_result";
    private static final int NOTIFY_PROGRESS = 0x5100;
    private static final String EXTRA_NOTIFY_ID = "notify_id";

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_PACKAGE_ADDED.equals(action)) return;

        final String packageName = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
        if (packageName == null || packageName.equals(context.getPackageName())) return;

        final SharedPreferences prefs = context.getSharedPreferences("dot_config", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_scan_install", false)) return;

        final int notifyId = (int) (System.currentTimeMillis() & 0x7fffffff);

        // 显示不可移除的静音进度通知
        sendProgressNotification(context, packageName, notifyId);

        // 后台线程扫描，避免阻塞
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 强制加载病毒包名列表（确保BUILTIN不为空）
                    VirusPackages.getVirusPackages(context);
                    SecurityScanActivity.ScanResult scanResult = SecurityScanActivity.scanInstalledAppResult(
                            context.getPackageManager(), packageName);
                    boolean risk = isRisk(scanResult);
                    if (risk) {
                        // 检查是否允许禁止安装病毒应用
                        boolean blockVirus = prefs.getBoolean("block_virus", true);
                        if (blockVirus && needsAutoUninstall(scanResult)) {
                            // 静默卸载：仅通知书别的风险（普通权限组合）或仅签名命中不自动卸载
                            silentUninstall(context, packageName);
                        }
                        sendResultNotification(context, packageName, scanResult, notifyId);
                    } else {
                        // 安全/可能安全：不发送任何通知，直接取消进度通知
                        cancelNotification(context, notifyId);
                    }
                } catch (Exception e) {
                    cancelNotification(context, notifyId);
                }
            }
        }).start();
    }

    /**
     * 判断是否真正有风险（中高风险以上才通知）
     * maxRiskLevel: 0=安全, 1=可能安全, 2=有风险, 3=高风险, 4=极高风险
     * 只有 >= 2 才发送通知
     */
    private boolean isRisk(SecurityScanActivity.ScanResult result) {
        if (result == null) return false;
        // 匹配病毒包名直接视为风险
        if (result.malwareConfirmed) return true;
        return result.maxRiskLevel >= 2;
    }

    /**
     * 判断是否"仅"命中风险签名（无病毒包名、权限组合、风险类名、文件特征等其他风险）。
     * 仅签名命中时保留通知提醒，但不再自动卸载。
     */
    private boolean isOnlySignatureMatch(SecurityScanActivity.ScanResult result) {
        if (result == null) return false;
        // 命中病毒包名（malwareConfirmed）不属于"仅签名"，仍按原逻辑自动卸载
        if (result.malwareConfirmed) return false;
        if (result.riskSignatures == null || result.riskSignatures.isEmpty()) return false;
        boolean noOtherRisk = (result.combinations == null || result.combinations.isEmpty())
                && (result.riskClasses == null || result.riskClasses.isEmpty())
                && (result.fileWarnings == null || result.fileWarnings.isEmpty());
        return noOtherRisk;
    }

    /**
     * 是否需要自动卸载：只有命中病毒包名才自动卸载。
     * 其余风险（风险签名、权限组合、风险类名、文件特征、高/极高风险）一律只提示不卸载。
     */
    private boolean needsAutoUninstall(SecurityScanActivity.ScanResult result) {
        if (result == null) return false;
        return result.malwareConfirmed;
    }

    /**
     * 静默卸载病毒应用
     * 优先 Shizuku → Root → 回退系统卸载界面
     */
    private void silentUninstall(Context context, String packageName) {
        // 方案1：Shizuku 静默卸载
        try {
            if (Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shizuku.newProcess(
                        new String[]{"pm", "uninstall", "--user", "0", packageName},
                        null, null);
                // 强制停止残留进程
                try {
                    Shizuku.newProcess(new String[]{"am", "force-stop", packageName}, null, null);
                } catch (Exception ignored) {}
                return;
            }
        } catch (Exception ignored) {}

        // 方案2：Root 静默卸载
        try {
            Process rootProc = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "pm uninstall --user 0 " + packageName});
            rootProc.waitFor();
            return;
        } catch (Exception ignored) {}

        // 方案3：回退到系统卸载界面（需用户确认）
        try {
            Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
            uninstallIntent.setData(Uri.parse("package:" + packageName));
            uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(uninstallIntent);
        } catch (Exception ignored) {}
    }

    // 不可移除的静音进度通知（最小化，不打扰）
    private void sendProgressNotification(Context context, String packageName, int notifyId) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_PROGRESS,
                        context.getString(R.string.install_scan_channel_progress), NotificationManager.IMPORTANCE_LOW);
                channel.enableVibration(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
            android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(context, CHANNEL_PROGRESS)
                    : new android.app.Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(context.getString(R.string.scanning_new_app))
                    .setContentText(packageName)
                    .setPriority(Notification.PRIORITY_LOW)
                    .setOngoing(true)               // 不可移除
                    .setOnlyAlertOnce(true)
                    .setWhen(0)
                    .setShowWhen(false);
            nm.notify(notifyId, builder.build());
        } catch (Exception e) {
            // ignore
        }
    }

    // 有风险：结果提醒通知（点击查看详细）
    private void sendResultNotification(Context context, String packageName,
                                        SecurityScanActivity.ScanResult result, int notifyId) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_RESULT,
                        context.getString(R.string.install_scan_channel_result), NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(channel);
            }

            String summary = SecurityScanActivity.summarize(result);
            String detail = result.toDetail();

            Intent dialogIntent = new Intent(context, ScanResultDialogActivity.class);
            dialogIntent.putExtra("package_name", packageName);
            dialogIntent.putExtra("summary", summary);
            dialogIntent.putExtra("detail", detail);
            dialogIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(context, (int) notifyId,
                    dialogIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(context, CHANNEL_RESULT)
                    : new android.app.Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(context.getString(R.string.risk_detected, packageName))
                    .setContentText(context.getString(R.string.tap_to_view))
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(summary))
                    .setContentIntent(pi)
                    .setAutoCancel(true);

            if (Build.VERSION.SDK_INT >= 33) {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    cancelNotification(context, notifyId);
                    return;
                }
            }
            nm.notify(notifyId, builder.build());
        } catch (Exception e) {
            cancelNotification(context, notifyId);
        }
    }

    // 安全/可能安全：静音"检测完毕"通知（点击查看结果，10秒后自动隐藏）
    private void sendDoneNotification(Context context, String packageName,
                                      SecurityScanActivity.ScanResult result, int notifyId) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                // 复用静音通道（不响铃、不震动）
                NotificationChannel channel = new NotificationChannel(CHANNEL_PROGRESS,
                        context.getString(R.string.install_scan_channel_progress), NotificationManager.IMPORTANCE_LOW);
                channel.enableVibration(false);
                channel.setSound(null, null);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }

            String summary = SecurityScanActivity.summarize(result);
            String detail = result.toDetail();

            // 点击打开结果弹窗
            Intent dialogIntent = new Intent(context, ScanResultDialogActivity.class);
            dialogIntent.putExtra("package_name", packageName);
            dialogIntent.putExtra("summary", summary);
            dialogIntent.putExtra("detail", detail);
            dialogIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(context, notifyId + 1,
                    dialogIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(context, CHANNEL_PROGRESS)
                    : new android.app.Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(context.getString(R.string.install_scan_done, packageName))
                    .setContentText(context.getString(R.string.no_risk_found))
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(summary))
                    .setContentIntent(pi)
                    .setPriority(Notification.PRIORITY_LOW)
                    .setOnlyAlertOnce(true)
                    .setWhen(0)
                    .setShowWhen(false)
                    .setAutoCancel(false); // 不点击不消失，由定时器隐藏

            if (Build.VERSION.SDK_INT >= 33) {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    cancelNotification(context, notifyId);
                    return;
                }
            }
            nm.notify(notifyId, builder.build());

            // 10秒后自动隐藏结果通知
            final Context fContext = context.getApplicationContext();
            final int fNotifyId = notifyId;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    cancelNotification(fContext, fNotifyId);
                }
            }, 10000L);
        } catch (Exception e) {
            cancelNotification(context, notifyId);
        }
    }

    private void cancelNotification(Context context, int notifyId) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notifyId);
        } catch (Exception e) {
            // ignore
        }
    }
}
