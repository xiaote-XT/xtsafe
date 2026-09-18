package xiaote.AnQuan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import rikka.shizuku.Shizuku;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // 自动提权开启无障碍
        boolean enabled = enableAccessibilityByShizuku(context);

        // 调度强制置顶兜底闹钟（防止锁屏停止后服务死亡无法恢复）
        ForceTopAlarmReceiver.schedule(context);

        // 发送通知
        try {
            NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("boot_antilock", context.getString(R.string.app_name),
                                                                 NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription(context.getString(R.string.boot_channel_title));
                nm.createNotificationChannel(ch);
            }

            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(context, 0, openIntent,
                                                         PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String contentText = enabled ? context.getString(R.string.boot_acc_on) : context.getString(R.string.boot_acc_off);
            Notification noti = new Notification.Builder(context, "boot_antilock")
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

            nm.notify(1002, noti);
        } catch (Exception ignored) {}
    }

    private boolean enableAccessibilityByShizuku(Context context) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                String component = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
                String cmd1 = "settings put secure enabled_accessibility_services '" + component + "'";
                String cmd2 = "settings put secure accessibility_enabled 1";
                Shizuku.newProcess(new String[]{"sh", "-c", cmd1 + " && " + cmd2}, null, null);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
