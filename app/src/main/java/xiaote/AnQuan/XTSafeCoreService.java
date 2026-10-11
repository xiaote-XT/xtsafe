package xiaote.AnQuan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import xiaote.AnQuan.InternalApi.AccessibilityApi;
import xiaote.AnQuan.InternalApi.ApiResult;

/**
 * 星特核心常驻服务（普通 Service，不依赖无障碍）。
 *
 * 本服务只做「不需要无障碍」的那部分：
 *   1. 音量阈值检测 / 防篡改（ProtectionVolumeThreshold）
 *   2. 阻止卸载 / 阻止阻止卸载的周期下发（BlockUninstallManager）
 *   3. 自身无障碍是否掉线的兜底检查（写回 enabled_accessibility_services）
 *   4. 系统时间被回拨检测
 *   5. 反向唤醒保活助手（双向心跳）
 *
 * ===== 为什么不用 startForegroundService =====
 *
 * foregroundServiceType 的可用枚举随编译 SDK 变化：specialUse 要 API 34、
 * systemExempted 要 API 31，本项目的 aapt 都不认，写成字符串直接编译失败。
 * 而 targetSdk 34+ 若声明了前台服务却不带 type，startForeground 会抛
 * MissingForegroundServiceTypeException，被 try/catch 吞掉后就是「服务起了
 * 但 5 秒内没进前台」→ 系统 ANR 干掉进程。
 *
 * 所以这里彻底不走前台服务通道：用普通 startService + NotificationManager
 * 发一条 LOW 优先级常驻通知。
 *
 * ===== 拉起途径（多入口，任一存活即可）=====
 *   · App.onCreate
 *   · BootReceiver（开机广播）
 *   · ForceTopAlarmReceiver（60 秒兜底闹钟）
 *   · MainActivity.onCreate
 *   · HelperWakeReceiver（保活助手唤醒）
 *
 * 返回 START_STICKY：被系统杀死后尽量自动重建。
 */
public class XTSafeCoreService extends Service {

    private static final String TAG = "XTSafeCore";
    private static final String CHANNEL_ID = "xtsafe_core";
    private static final int NOTIFY_ID = 0x5A01;

    /** 阻止卸载检查循环间隔（与最低 0.1s 一次对齐） */
    private static final long BLOCK_CHECK_INTERVAL_MS = 100L;

    /** 一个 tick = 100 毫秒 */
    private static final long TICK_MS = 100L;
    /**
     * 无障碍保活关闭时的循环间隔。
     * 取 1 秒而不是 30 秒：开关是用户随时拨动的，循环每次都用 currentAccCheckInterval()
     * 重新取值，间隔太长会导致「刚打开开关要等半分钟才生效」。1 秒的空转成本可忽略。
     */
    private static final long ACC_CHECK_IDLE_MS = 1_000L;

    /** 无障碍保活开关（存 dot_config） */
    public static final String KEY_ACC_KEEP_ALIVE = "acc_keep_alive";
    /** 无障碍保活检查间隔，单位 tick，1 tick = 100ms */
    public static final String KEY_ACC_KEEP_ALIVE_TICKS = "acc_keep_alive_ticks";
    /**
     * 无障碍保活模式（存 dot_config）。
     *   auto  自动恢复：先检测无障碍是否掉线，掉线才写回服务列表；
     *   force 强制保活：不做检测，每轮都无条件写回，把无障碍强行拉起来。
     */
    public static final String KEY_ACC_KEEP_ALIVE_MODE = "acc_keep_alive_mode";
    /** 自动恢复（默认） */
    public static final String ACC_KEEP_ALIVE_MODE_AUTO = "auto";
    /** 强制保活 */
    public static final String ACC_KEEP_ALIVE_MODE_FORCE = "force";

    /** 默认 10 秒 */
    public static final int DEFAULT_ACC_KEEP_ALIVE_TICKS = 100;
    /** 最低 0.1 秒 */
    public static final int MIN_ACC_KEEP_ALIVE_TICKS = 1;
    /** 上限 120 分钟 */
    public static final int MAX_ACC_KEEP_ALIVE_TICKS = 72000;

    /** 系统时间被回拨时触发安全操作的开关（存 dot_config） */
    public static final String KEY_TIME_ROLLBACK_ACTION = "time_rollback_action";
    /** 判定为「被回拨」的最小回拨量，单位分钟 */
    public static final String KEY_TIME_ROLLBACK_MINUTES = "time_rollback_minutes";
    /** 上次记录的时间戳（毫秒），用于比对是否被回拨 */
    public static final String KEY_TIME_ROLLBACK_LAST = "time_rollback_last_ms";
    /** 默认回拨阈值：3 天 */
    public static final int DEFAULT_TIME_ROLLBACK_MINUTES = 3 * 24 * 60;
    /** 阈值下限：1 分钟 */
    public static final int MIN_TIME_ROLLBACK_MINUTES = 1;
    /** 阈值上限：30 天 */
    public static final int MAX_TIME_ROLLBACK_MINUTES = 30 * 24 * 60;
    /** 时间回拨检查循环间隔（毫秒） */
    private static final long TIME_ROLLBACK_CHECK_MS = 1000L;
    /** 高水位落盘节流：每 30 秒最多写一次 SharedPreferences */
    private static final long TIME_ROLLBACK_PERSIST_MS = 30000L;

    /**
     * 反向心跳的兜底间隔。
     *
     * 实际间隔每轮都从 HelperWakeSender.readInterval() 读，与助手侧共用同一份配置
     * （默认 1 秒，可在「保活增强」页调整）。这个常量只在读配置失败时兜底。
     */
    private static final long HELPER_WAKE_FALLBACK_MS = 1000L;
    /** 反向唤醒首轮延迟 */
    private static final long HELPER_WAKE_FIRST_MS = 1000L;

    private static final String COMPONENT_STD = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
    private static final String COMPONENT_SHORT = "xiaote.AnQuan/.XTSafeMainService";

    private Handler handler;
    private SharedPreferences dotPrefs;

    private ProtectionVolumeThreshold volumeThreshold;

    private Runnable blockCheckTask;
    private Runnable accCheckTask;
    private Runnable timeRollbackTask;
    private Runnable helperWakeTask;
    /** 时间回拨高水位（毫秒），内存缓存避免每秒写盘 */
    private long timeRollbackHighWater = 0L;
    /** 上次把高水位写入 SharedPreferences 的时间 */
    private long timeRollbackPersistAt = 0L;
    /** 上次真正执行无障碍保活检查的时间戳（毫秒） */
    private long lastAccCheckAt = 0L;

    /**
     * 幂等启动入口。任何页面/广播都可以调，重复调用不会叠加循环。
     *
     * 用 startService（非 startForegroundService）：不需要
     * foregroundServiceType，也不会因未在 5 秒内调用 startForeground 而 ANR。
     * 后台启动限制（Android 8+）下可能抛异常，直接忽略——其余入口会补上。
     */
    public static void ensureStarted(Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(context, XTSafeCoreService.class);
            context.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "startService 失败（后台限制），等待其他入口拉起", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        dotPrefs = getSharedPreferences("dot_config", MODE_PRIVATE);
        postPersistentNotification();

        // 1. 音量阈值检测：只依赖 Context，与无障碍无关
        volumeThreshold = new ProtectionVolumeThreshold(this, handler, dotPrefs);
        volumeThreshold.start();

        // 2. 阻止卸载周期任务
        startBlockCheckTask();

        // 3. 无障碍掉线兜底
        startAccCheckTask();

        // 4. 系统时间被回拨检测
        startTimeRollbackTask();

        // 5. 反向唤醒保活助手（双向心跳，任一方活着都能把对方拽起来）
        startHelperWakeTask();

        Log.d(TAG, "XTSafeCoreService onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 再次确保通知存在（部分 ROM 重建后会丢）
        postPersistentNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "XTSafeCoreService onDestroy");
        stopBlockCheckTask();
        stopAccCheckTask();
        stopTimeRollbackTask();
        stopHelperWakeTask();
        if (volumeThreshold != null) {
            volumeThreshold.stop();
            volumeThreshold = null;
        }
        // 被系统杀掉后尝试自拉活（START_STICKY 之外再补一次）
        try {
            Intent i = new Intent(this, XTSafeCoreService.class);
            startService(i);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 常驻通知（非前台服务） ====================

    private void postPersistentNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
                ch.enableVibration(false);
                ch.setSound(null, null);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0x5A01, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_menu_share)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("核心防护运行中（音量防护 / 阻止卸载）")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(Notification.PRIORITY_LOW)
                    .setWhen(0)
                    .setShowWhen(false);
            nm.notify(NOTIFY_ID, b.build());
        } catch (Throwable ignored) {}
    }

    // ==================== 阻止卸载循环 ====================

    private void startBlockCheckTask() {
        stopBlockCheckTask();
        blockCheckTask = new Runnable() {
            @Override
            public void run() {
                try {
                    BlockUninstallManager.maybeRunPeriodic(getApplicationContext());
                } catch (Throwable ignored) {
                } finally {
                    if (blockCheckTask != null && handler != null) {
                        handler.postDelayed(this, BLOCK_CHECK_INTERVAL_MS);
                    }
                }
            }
        };
        handler.postDelayed(blockCheckTask, BLOCK_CHECK_INTERVAL_MS);
    }

    private void stopBlockCheckTask() {
        if (blockCheckTask != null && handler != null) {
            handler.removeCallbacks(blockCheckTask);
            blockCheckTask = null;
        }
    }

    // ==================== 无障碍掉线兜底 ====================

    /**
     * 无障碍保活心跳的最大间隔。
     *
     * 实际心跳 = min(这个值, currentAccCheckInterval())：
     *   · 间隔配置较大（如 10 分钟）时，心跳固定 500ms 空转，只做一次时间比较；
     *   · 间隔配置较小（如 0.1 秒）时，心跳跟着降到 100ms，保证设定频率能真正达到。
     */
    private static final long ACC_HEARTBEAT_MAX_MS = 500L;
    /** 心跳下限：间隔设得再小也不低于这个值，避免把主线程 Handler 压满 */
    private static final long ACC_HEARTBEAT_MIN_MS = 50L;

    private void startAccCheckTask() {
        stopAccCheckTask();
        lastAccCheckAt = 0L;
        accCheckTask = new Runnable() {
            @Override
            public void run() {
                try {
                    long now = System.currentTimeMillis();
                    if (now - lastAccCheckAt >= currentAccCheckInterval()) {
                        lastAccCheckAt = now;
                        runAccCheckAsync();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (accCheckTask != null && handler != null) {
                        handler.postDelayed(this, nextAccHeartbeat());
                    }
                }
            }
        };
        handler.postDelayed(accCheckTask, nextAccHeartbeat());
    }

    private long nextAccHeartbeat() {
        long interval = currentAccCheckInterval();
        long hb = Math.min(ACC_HEARTBEAT_MAX_MS, interval);
        if (hb < ACC_HEARTBEAT_MIN_MS) hb = ACC_HEARTBEAT_MIN_MS;
        return hb;
    }

    private long currentAccCheckInterval() {
        try {
            if (!dotPrefs.getBoolean(KEY_ACC_KEEP_ALIVE, false)) return ACC_CHECK_IDLE_MS;
            int ticks = dotPrefs.getInt(KEY_ACC_KEEP_ALIVE_TICKS, DEFAULT_ACC_KEEP_ALIVE_TICKS);
            if (ticks < MIN_ACC_KEEP_ALIVE_TICKS) ticks = MIN_ACC_KEEP_ALIVE_TICKS;
            if (ticks > MAX_ACC_KEEP_ALIVE_TICKS) ticks = MAX_ACC_KEEP_ALIVE_TICKS;
            return (long) ticks * TICK_MS;
        } catch (Throwable t) {
            return ACC_CHECK_IDLE_MS;
        }
    }

    private void stopAccCheckTask() {
        if (accCheckTask != null && handler != null) {
            handler.removeCallbacks(accCheckTask);
            accCheckTask = null;
        }
    }

    /**
     * 把保活检查丢到工作线程执行。
     *
     * checkAccessibilityAlive() 内部要读 Settings.Secure、并在判定掉线时
     * 走 Shizuku / Root 写回，都是阻塞式跨进程调用；直接挂在主线程的
     * Handler 循环上会拖住 UI 与后续心跳。
     */
    private void runAccCheckAsync() {
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    checkAccessibilityAlive();
                }
            }, "xtsafe-acc-keepalive").start();
        } catch (Throwable ignored) {
            checkAccessibilityAlive();
        }
    }

    /**
     * 无障碍掉线就写回 enabled_accessibility_services 触发系统重建。
     *
     * 保活模式：auto 掉线才拉活；force 每轮无条件写回。
     * 组件读取与写回统一走 AccessibilityApi，本服务不再自己拼 settings 命令。
     *
     * 注意：本方法含阻塞调用，只应在工作线程执行，入口是 runAccCheckAsync()。
     */
    private void checkAccessibilityAlive() {
        try {
            if (!dotPrefs.getBoolean(KEY_ACC_KEEP_ALIVE, false)) return;
        } catch (Throwable t) {
            return;
        }

        // 保活模式：auto 掉线才拉活；force 每轮无条件写回。
        boolean force = false;
        try {
            force = ACC_KEEP_ALIVE_MODE_FORCE.equals(
                    dotPrefs.getString(KEY_ACC_KEEP_ALIVE_MODE, ACC_KEEP_ALIVE_MODE_AUTO));
        } catch (Throwable ignored) {}

        try {
            boolean inList = false;
            for (String item : AccessibilityApi.listEnabled(this)) {
                if (item.equals(COMPONENT_STD) || item.equals(COMPONENT_SHORT)) { inList = true; break; }
            }

            boolean running = false;
            try {
                android.view.accessibility.AccessibilityManager am =
                        (android.view.accessibility.AccessibilityManager)
                                getSystemService(Context.ACCESSIBILITY_SERVICE);
                if (am != null) {
                    java.util.List<android.accessibilityservice.AccessibilityServiceInfo> enabled =
                            am.getEnabledAccessibilityServiceList(
                                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                    if (enabled != null) {
                        for (android.accessibilityservice.AccessibilityServiceInfo info : enabled) {
                            String id = info.getId();
                            if (COMPONENT_STD.equals(id) || COMPONENT_SHORT.equals(id)) { running = true; break; }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 自动恢复：既在启用列表、服务也确实在跑，才认为没掉线，跳过写回。
            // 强制保活：不看状态，直接往下走写回。
            if (!force && inList && running) return;

            ApiResult r = AccessibilityApi.writeSelfBack(this);
            Log.d(TAG, "无障碍保活写回 force=" + force + " inList=" + inList
                    + " running=" + running + " result=" + r);
        } catch (Throwable ignored) {}
    }

    // ==================== 系统时间回拨检测 ====================

    private void startTimeRollbackTask() {
        stopTimeRollbackTask();
        timeRollbackTask = new Runnable() {
            @Override
            public void run() {
                try {
                    checkTimeRollback();
                } catch (Throwable ignored) {
                } finally {
                    if (timeRollbackTask != null && handler != null) {
                        handler.postDelayed(this, TIME_ROLLBACK_CHECK_MS);
                    }
                }
            }
        };
        handler.postDelayed(timeRollbackTask, TIME_ROLLBACK_CHECK_MS);
    }

    private void stopTimeRollbackTask() {
        if (timeRollbackTask != null && handler != null) {
            handler.removeCallbacks(timeRollbackTask);
            timeRollbackTask = null;
        }
        timeRollbackHighWater = 0L;
    }

    /**
     * 系统时间被回拨检测。
     *
     * 每秒把当前时间与「高水位」（历次检查中见过的最大时间）比对：
     *   · 当前时间更大 → 时间正常前进，推进高水位；
     *   · 当前时间更小且差值达到阈值（默认 3 天）→ 判定为被回拨，
     *     按「选择操作」里勾选的安全操作执行一次，并把高水位压到当前时间，
     *     避免同一轮回拨被反复触发。
     *
     * 用高水位而不是「上一次检查时间」：检查间隔只有 1 秒，用上一次检查时间
     * 只能捕捉到「1 秒内发生的回拨」；高水位记录的是「曾经到过的最大时间」，
     * 只要时间倒退过阈值就一定能检出。
     *
     * 高频写盘已做节流：高水位每 30 秒最多落盘一次，进程被杀最多丢 30 秒。
     */
    private void checkTimeRollback() {
        try {
            if (!dotPrefs.getBoolean(KEY_TIME_ROLLBACK_ACTION, false)) {
                // 开关关闭：清空内存高水位与落盘值，下次开启从当前时间重新起算
                if (timeRollbackHighWater != 0L) {
                    timeRollbackHighWater = 0L;
                    timeRollbackPersistAt = 0L;
                    dotPrefs.edit().putLong(KEY_TIME_ROLLBACK_LAST, 0L).apply();
                }
                return;
            }

            long now = System.currentTimeMillis();
            if (timeRollbackHighWater <= 0L) {
                timeRollbackHighWater = dotPrefs.getLong(KEY_TIME_ROLLBACK_LAST, 0L);
            }

            int minutes = dotPrefs.getInt(KEY_TIME_ROLLBACK_MINUTES, DEFAULT_TIME_ROLLBACK_MINUTES);
            if (minutes < MIN_TIME_ROLLBACK_MINUTES) minutes = MIN_TIME_ROLLBACK_MINUTES;
            if (minutes > MAX_TIME_ROLLBACK_MINUTES) minutes = MAX_TIME_ROLLBACK_MINUTES;
            long thresholdMs = (long) minutes * 60000L;

            if (timeRollbackHighWater > 0L && now < timeRollbackHighWater
                    && (timeRollbackHighWater - now) >= thresholdMs) {
                long span = timeRollbackHighWater - now;
                timeRollbackHighWater = now;
                timeRollbackPersistAt = now;
                dotPrefs.edit().putLong(KEY_TIME_ROLLBACK_LAST, now).apply();

                String msg = "检测到系统时间被回拨 " + formatRollbackSpan(span)
                        + "（阈值 " + minutes + " 分钟），执行安全操作";
                Log.d(TAG, msg);
                SafeActionManager.appendLog(getApplicationContext(), msg);
                try {
                    SafeActionManager.run(getApplicationContext(), null);
                } catch (Throwable ignored) {
                }
                return;
            }

            if (now > timeRollbackHighWater) {
                timeRollbackHighWater = now;
                if (now - timeRollbackPersistAt >= TIME_ROLLBACK_PERSIST_MS) {
                    timeRollbackPersistAt = now;
                    dotPrefs.edit().putLong(KEY_TIME_ROLLBACK_LAST, now).apply();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把回拨量（毫秒）格式化成「X 天 Y 小时 Z 分钟」，供日志与界面复用 */
    public static String formatRollbackSpan(long ms) {
        long totalMin = ms / 60000L;
        long days = totalMin / (24L * 60L);
        long hours = (totalMin % (24L * 60L)) / 60L;
        long mins = totalMin % 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append(" 天 ");
        if (hours > 0) sb.append(hours).append(" 小时 ");
        if (mins > 0 || sb.length() == 0) sb.append(mins).append(" 分钟");
        return sb.toString().trim();
    }

    // ==================== 反向唤醒保活助手 ====================

    /**
     * 定期向保活助手发唤醒广播。
     *
     * 双向心跳的意义：助手被强行停止后，若只靠它自己发广播，它永远醒不过来；
     * 而星特安全这边一旦还活着，就能用带 FLAG_INCLUDE_STOPPED_PACKAGES 的
     * 广播把助手拉起来，助手再反过来定期拉星特安全。两边任一方活着，
     * 整条链路就能恢复，不会出现「双方都被停、谁也救不了谁」的死局。
     */
    private void startHelperWakeTask() {
        stopHelperWakeTask();
        helperWakeTask = new Runnable() {
            @Override
            public void run() {
                try {
                    HelperWakeSender.wakeHelperNow(getApplicationContext());
                } catch (Throwable ignored) {
                } finally {
                    if (helperWakeTask != null && handler != null) {
                        // 每轮重读间隔：与助手侧同源配置，滑块改完下一轮即生效
                        long iv = HelperWakeSender.readInterval(getApplicationContext());
                        if (iv <= 0L) iv = HELPER_WAKE_FALLBACK_MS;
                        handler.postDelayed(this, iv);
                    }
                }
            }
        };
        handler.postDelayed(helperWakeTask, HELPER_WAKE_FIRST_MS);
    }

    private void stopHelperWakeTask() {
        if (helperWakeTask != null && handler != null) {
            handler.removeCallbacks(helperWakeTask);
            helperWakeTask = null;
        }
    }
}
