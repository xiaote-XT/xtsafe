package xiaote.AnQuan;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;

/**
 * 主动防护 - 音量阈值检测。
 *
 * 轮询 + ContentObserver 双通道读音量，达到阈值时：
 *   1. 防篡改：音量压回 0（开关 volume_protect）
 *   2. 唤起卸载列表（开关 volume_max_show_uninstall）
 *   3. 强制停止所有第三方应用（开关 volume_force_stop_all）
 *
 * 触发用「曾达到阈值」标志位驱动，与防篡改压低音量解耦，
 * 冷却时间 5 秒防止连续触发。提取自 XTSafeMainService。
 */
public class ProtectionVolumeThreshold {

    /** 触发冷却（毫秒） */
    private static final long TRIGGER_COOLDOWN_MS = 5000L;

    private final AccessibilityService service;
    private final Handler handler;
    private final SharedPreferences dotPrefs;
    private final OverlayManager overlayManager;
    private final EmergencyManager emergencyManager;

    private Runnable volumeCheckTask;
    private android.database.ContentObserver volumeObserver;
    private final Object volumeTriggerLock = new Object();
    private boolean pendingVolumeTrigger = false; // "曾达到阈值需唤起"标志，与防篡改压低音量解耦

    public ProtectionVolumeThreshold(AccessibilityService service, Handler handler,
                                     SharedPreferences dotPrefs, OverlayManager overlayManager,
                                     EmergencyManager emergencyManager) {
        this.service = service;
        this.handler = handler;
        this.dotPrefs = dotPrefs;
        this.overlayManager = overlayManager;
        this.emergencyManager = emergencyManager;
    }

    /** 启动轮询任务与音量变化监听 */
    public void start() {
        startCheckTask();
        registerObserver();
    }

    /** 停止轮询任务与音量变化监听 */
    public void stop() {
        stopCheckTask();
        unregisterObserver();
    }

    // ==================== 轮询 ====================

    private void startCheckTask() {
        stopCheckTask();
        volumeCheckTask = new Runnable() {
            @Override
            public void run() {
                if (handler == null) return;
                checkVolumeAndReduce();
                int volInterval = dotPrefs.getInt("volume_check_interval", 1);
                handler.postDelayed(volumeCheckTask, Math.max(100, volInterval * 100L));
            }
        };
        handler.postDelayed(volumeCheckTask, Math.max(100, dotPrefs.getInt("volume_check_interval", 1) * 100L));
    }

    private void stopCheckTask() {
        if (volumeCheckTask != null && handler != null) {
            handler.removeCallbacks(volumeCheckTask);
            volumeCheckTask = null;
        }
    }

    /** 核心：读音量 → 防篡改 / 置触发标志 / 冷却后执行动作 */
    private void checkVolumeAndReduce() {
        try {
            AudioManager am = (AudioManager) service.getSystemService(AccessibilityService.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int threshold = dotPrefs.getInt("volume_threshold", 80);
            boolean reachedThreshold = max > 0 && current * 100 / max >= threshold;

            // 防音量恶意修改：达到阈值即压回0
            if (dotPrefs.getBoolean("volume_protect", false) && reachedThreshold) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            }

            // 唤起卸载列表：用标志位记录"曾达到阈值"，与防篡改压低音量解耦。
            // 防篡改已把音量压到0，若仍依赖 current 判断则永远不会再触发，故改为标志位驱动。
            if (reachedThreshold) {
                synchronized (volumeTriggerLock) {
                    pendingVolumeTrigger = true;
                }
            }
            boolean shouldTrigger = false;
            synchronized (volumeTriggerLock) {
                if (pendingVolumeTrigger) {
                    long now = System.currentTimeMillis();
                    long last = dotPrefs.getLong("last_volume_max_uninstall_time", 0);
                    if (now - last > TRIGGER_COOLDOWN_MS) {
                        dotPrefs.edit().putLong("last_volume_max_uninstall_time", now).apply();
                        pendingVolumeTrigger = false;
                        shouldTrigger = true;
                    } else {
                        pendingVolumeTrigger = false; // 冷却期内丢弃，避免标志堆积
                    }
                }
            }
            if (shouldTrigger) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dotPrefs.getBoolean("volume_max_show_uninstall", false)) {
                            triggerUninstallList();
                        }
                        if (dotPrefs.getBoolean("volume_force_stop_all", false) && emergencyManager != null) {
                            emergencyManager.forceStopAll();
                        }
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    /** 唤起卸载列表：优先悬浮窗，失败兜底高优先级通知，保证退到后台也有路径进入应用 */
    private void triggerUninstallList() {
        if (overlayManager != null) {
            try {
                overlayManager.showUninstallList();
                return;
            } catch (Exception e) {}
        }
        // 悬浮窗失败兜底：高优先级通知（点击进入主界面）
        try {
            NotificationManager nm = (NotificationManager) service.getSystemService(AccessibilityService.NOTIFICATION_SERVICE);
            String channelId = "volume_trigger";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(channelId,
                        service.getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            Intent main = new Intent(service, MainActivity.class);
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(service, 9001, main,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(service, channelId)
                    : new Notification.Builder(service);
            b.setSmallIcon(android.R.drawable.ic_menu_delete)
                    .setContentTitle(service.getString(R.string.app_name))
                    .setContentText(service.getString(R.string.volume_trigger_notify))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH);
            nm.notify(9001, b.build());
        } catch (Exception ignored) {}
    }

    // ==================== 音量变化事件监听 ====================

    /** 注册音量变化 ContentObserver（事件驱动，弥补纯轮询窗口期） */
    private void registerObserver() {
        if (volumeObserver != null) return;
        try {
            volumeObserver = new android.database.ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    checkVolumeAndReduce();
                }
            };
            service.getContentResolver().registerContentObserver(
                    android.provider.Settings.System.CONTENT_URI, true, volumeObserver);
        } catch (Exception ignored) {}
    }

    private void unregisterObserver() {
        try {
            if (volumeObserver != null) {
                service.getContentResolver().unregisterContentObserver(volumeObserver);
                volumeObserver = null;
            }
        } catch (Exception ignored) {}
    }
}
