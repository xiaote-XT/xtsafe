package xiaote.AnQuan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Set;

/**
 * 智能管理的锁屏关闭 / 亮屏询问恢复机制。
 * 提取自 XTSafeMainService：
 *   锁屏时记录当前开启的智能管理应用并关闭；
 *   亮屏时通知询问是否恢复，5 秒无操作默认恢复；
 *   用户选"暂不"则保持关闭，下次锁屏重新询问。
 */
public class SmartRecoverManager {

    private static final String ACTION_SMART_RECOVER = "xiaote.AnQuan.SMART_RECOVER";
    private static final String ACTION_SMART_DEFER = "xiaote.AnQuan.SMART_DEFER";
    private static final long RECOVER_DELAY_MS = 5000L;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences dotPrefs;
    /** 恢复/清理后回调主类重算无障碍列表（applyAccessibilityModes） */
    private final Runnable onRecoverNeeded;

    // 跨线程访问（后台工作线程会查 isPending/isDeferred），使用同步集合
    private final Set<String> screenOffSmartPkgs = java.util.Collections.synchronizedSet(new HashSet<String>());  // 锁屏前开启的智能管理应用
    private final Set<String> smartRecoverPending = java.util.Collections.synchronizedSet(new HashSet<String>());  // 延迟恢复期，保持关闭
    private final Set<String> smartUserDeferred = java.util.Collections.synchronizedSet(new HashSet<String>());    // 用户选"暂不"，保持关闭
    private boolean smartRecoverNotified = false;  // 防止亮屏重复通知（SCREEN_ON+USER_PRESENT）
    private Runnable smartRecoverTimeout;
    private BroadcastReceiver smartRecoverReceiver;

    public SmartRecoverManager(Context context, Handler handler,
                               SharedPreferences dotPrefs, Runnable onRecoverNeeded) {
        this.context = context;
        this.handler = handler;
        this.dotPrefs = dotPrefs;
        this.onRecoverNeeded = onRecoverNeeded;
    }

    /** 是否处于延迟恢复期（应保持关闭） */
    public boolean isPending(String pkg) {
        return smartRecoverPending.contains(pkg);
    }

    /** 用户选"暂不"，应保持关闭 */
    public boolean isDeferred(String pkg) {
        return smartUserDeferred.contains(pkg);
    }

    /** 锁屏时记录当前开启的智能管理应用，供亮屏询问恢复 */
    public void handleScreenOffSmart() {
        screenOffSmartPkgs.clear();
        smartRecoverPending.clear();
        smartRecoverNotified = false;
        if (smartRecoverTimeout != null && handler != null) {
            handler.removeCallbacks(smartRecoverTimeout);
            smartRecoverTimeout = null;
        }
        try {
            String currentList = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (currentList == null) currentList = "";
            for (String p : currentList.split(":")) {
                if (p.isEmpty()) continue;
                int idx = p.indexOf('/');
                String pkg = idx > 0 ? p.substring(0, idx) : p;
                if (pkg.equals(context.getPackageName())) continue;
                int mode = dotPrefs.getInt("acc_mode_" + pkg, -1);
                if (mode == AccessibilityManagerActivity.MODE_SMART) {
                    screenOffSmartPkgs.add(pkg);
                }
            }
        } catch (Exception ignored) {}
    }

    /** 亮屏/解锁：对锁屏前开启的智能管理应用通知询问是否恢复，5秒无操作默认恢复 */
    public void handleScreenOnSmart() {
        if (smartRecoverNotified) return;  // 防重复（SCREEN_ON + USER_PRESENT 都触发）
        if (screenOffSmartPkgs.isEmpty()) return;
        smartRecoverNotified = true;
        // 进入延迟恢复期：保持关闭，等待用户选择或5秒自动恢复
        smartRecoverPending.addAll(screenOffSmartPkgs);
        // 上次选"暂不"的，本次重新纳入询问
        for (String pkg : screenOffSmartPkgs) {
            smartUserDeferred.remove(pkg);
        }
        sendSmartRecoverNotify();
        if (smartRecoverTimeout == null) {
            smartRecoverTimeout = new Runnable() {
                @Override
                public void run() {
                    smartRecoverPending.clear();
                    cancelSmartRecoverNotify();
                    if (onRecoverNeeded != null) onRecoverNeeded.run();  // 5秒无操作，默认恢复
                }
            };
        }
        handler.postDelayed(smartRecoverTimeout, RECOVER_DELAY_MS);
    }

    private void sendSmartRecoverNotify() {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId = "smart_recover";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(channelId, context.getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent recover = new Intent(ACTION_SMART_RECOVER).setPackage(context.getPackageName());
            PendingIntent recoverPi = PendingIntent.getBroadcast(context, 9101, recover,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent defer = new Intent(ACTION_SMART_DEFER).setPackage(context.getPackageName());
            PendingIntent deferPi = PendingIntent.getBroadcast(context, 9102, defer,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, channelId)
                    : new Notification.Builder(context);
            b.setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.smart_recover_notify))
                    .addAction(0, context.getString(R.string.smart_recover_yes), recoverPi)
                    .addAction(0, context.getString(R.string.smart_recover_no), deferPi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_DEFAULT);
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(6000L);
            nm.notify(9100, b.build());
        } catch (Exception ignored) {}
    }

    private void cancelSmartRecoverNotify() {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(9100);
        } catch (Exception ignored) {}
    }

    public void registerReceiver() {
        try {
            if (smartRecoverReceiver != null) return;
            smartRecoverReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_SMART_RECOVER.equals(action)) {
                        // 用户选恢复：立即恢复
                        if (smartRecoverTimeout != null && handler != null) {
                            handler.removeCallbacks(smartRecoverTimeout);
                        }
                        smartRecoverPending.clear();
                        cancelSmartRecoverNotify();
                        if (onRecoverNeeded != null) onRecoverNeeded.run();
                    } else if (ACTION_SMART_DEFER.equals(action)) {
                        // 用户选暂不：移入 deferred 保持关闭，下次锁屏重新询问
                        if (smartRecoverTimeout != null && handler != null) {
                            handler.removeCallbacks(smartRecoverTimeout);
                        }
                        smartUserDeferred.addAll(smartRecoverPending);
                        smartRecoverPending.clear();
                        cancelSmartRecoverNotify();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_SMART_RECOVER);
            filter.addAction(ACTION_SMART_DEFER);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(smartRecoverReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(smartRecoverReceiver, filter);
        } catch (Exception ignored) {}
    }

    public void unregisterReceiver() {
        try {
            if (smartRecoverReceiver != null) {
                context.unregisterReceiver(smartRecoverReceiver);
                smartRecoverReceiver = null;
            }
        } catch (Exception ignored) {}
    }

    /** 服务销毁时清理定时任务 */
    public void cleanup() {
        if (smartRecoverTimeout != null && handler != null) {
            handler.removeCallbacks(smartRecoverTimeout);
            smartRecoverTimeout = null;
        }
        unregisterReceiver();
    }
}
