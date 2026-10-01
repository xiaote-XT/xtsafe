package xiaote.AnQuan;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/**
 * 主动防护 - 音量阈值检测。
 *
 * 轮询 + ContentObserver 双通道读音量，达到阈值时：
 *   1. 防篡改：音量压回 0（开关 volume_protect）
 *   2. 音量超阈值执行安全操作（开关 volume_threshold_action）
 *
 * 本类已从「必须由无障碍服务持有」改为只依赖 Context，
 * 因此可以在普通 Service（XTSafeCoreService）里运行——
 * 无障碍被关闭后，音量防护依旧工作。
 */
public class ProtectionVolumeThreshold {

    /** 触发冷却（毫秒） */
    private static final long TRIGGER_COOLDOWN_MS = 5000L;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences dotPrefs;

    private Runnable volumeCheckTask;
    private android.database.ContentObserver volumeObserver;
    private final Object volumeTriggerLock = new Object();
    private boolean pendingVolumeTrigger = false; // "曾达到阈值需触发"标志，与防篡改压低音量解耦

    public ProtectionVolumeThreshold(Context context, Handler handler,
                                     SharedPreferences dotPrefs) {
        this.context = context.getApplicationContext();
        this.handler = (handler != null) ? handler : new Handler(Looper.getMainLooper());
        this.dotPrefs = dotPrefs;
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

    /** 核心：读音量 → 防篡改 / 置触发标志 / 冷却后执行安全操作 */
    private void checkVolumeAndReduce() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int threshold = dotPrefs.getInt("volume_threshold", 80);
            boolean reachedThreshold = max > 0 && current * 100 / max >= threshold;

            // 防音量恶意修改：达到阈值即压回0
            if (dotPrefs.getBoolean("volume_protect", false) && reachedThreshold) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            }

            // 用标志位记录"曾达到阈值"，与防篡改压低音量解耦。
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
                        // 音量超阈值：执行「选择操作」里勾选的安全操作
                        if (dotPrefs.getBoolean("volume_threshold_action", false)) {
                            SafeActionManager.run(context);
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}
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
            context.getContentResolver().registerContentObserver(
                    android.provider.Settings.System.CONTENT_URI, true, volumeObserver);
        } catch (Throwable ignored) {}
    }

    private void unregisterObserver() {
        try {
            if (volumeObserver != null) {
                context.getContentResolver().unregisterContentObserver(volumeObserver);
                volumeObserver = null;
            }
        } catch (Throwable ignored) {}
    }
}
