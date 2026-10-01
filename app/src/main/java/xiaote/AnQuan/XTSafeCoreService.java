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

/**
 * 星特核心常驻服务（普通 Service，不依赖无障碍）。
 *
 * 背景：几乎所有功能原先都挂在无障碍服务 XTSafeMainService 上，
 * 无障碍一旦被关闭，进程直接消失，连音量阈值检测这种根本不需要
 * 无障碍的功能也跟着停摆。
 *
 * 本服务只做「不需要无障碍」的那部分：
 *   1. 音量阈值检测 / 防篡改（ProtectionVolumeThreshold）
 *   2. 阻止卸载 / 阻止阻止卸载的周期下发（BlockUninstallManager）
 *   3. 自身无障碍是否掉线的兜底检查（写回 enabled_accessibility_services）
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
 * 发一条 LOW 优先级常驻通知。既不需要 foregroundServiceType，也不会 ANR，
 * 用户仍能在通知栏看到「核心防护运行中」。
 *
 * ===== 拉起途径（多入口，任一存活即可）=====
 *   · App.onCreate
 *   · BootReceiver（开机广播）
 *   · ForceTopAlarmReceiver（60 秒兜底闹钟，始终续订）
 *   · MainActivity.onCreate
 *
 * 返回 START_STICKY：被系统杀死后尽量自动重建。
 */
public class XTSafeCoreService extends Service {

    private static final String TAG = "XTSafeCore";
    private static final String CHANNEL_ID = "xtsafe_core";
    private static final int NOTIFY_ID = 0x5A01;

    /** 阻止卸载检查循环间隔（与最低 0.1s 一次对齐） */
    private static final long BLOCK_CHECK_INTERVAL_MS = 100L;
    /** 无障碍掉线检查间隔 */
    private static final long ACC_CHECK_INTERVAL_MS = 30_000L;

    private static final String COMPONENT_STD = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
    private static final String COMPONENT_SHORT = "xiaote.AnQuan/.XTSafeMainService";

    private Handler handler;
    private SharedPreferences dotPrefs;

    private ProtectionVolumeThreshold volumeThreshold;

    private Runnable blockCheckTask;
    private Runnable accCheckTask;

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

    /**
     * 发一条 LOW 优先级、无声无震动的常驻通知。
     * 不走 startForeground，因此不需要 foregroundServiceType。
     */
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

    private void startAccCheckTask() {
        stopAccCheckTask();
        accCheckTask = new Runnable() {
            @Override
            public void run() {
                try {
                    checkAccessibilityAlive();
                } catch (Throwable ignored) {
                } finally {
                    if (accCheckTask != null && handler != null) {
                        handler.postDelayed(this, ACC_CHECK_INTERVAL_MS);
                    }
                }
            }
        };
        handler.postDelayed(accCheckTask, ACC_CHECK_INTERVAL_MS);
    }

    private void stopAccCheckTask() {
        if (accCheckTask != null && handler != null) {
            handler.removeCallbacks(accCheckTask);
            accCheckTask = null;
        }
    }

    /**
     * 无障碍掉线就写回 enabled_accessibility_services 触发系统重建。
     * 只做「写回」这一件事，不涉及浮窗/事件处理，因此本服务独立于无障碍存活。
     */
    private void checkAccessibilityAlive() {
        try {
            String list = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) list = "";
            boolean inList = false;
            for (String item : list.split(":")) {
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

            if (inList && running) return;

            String clean = removeSelf(list);
            String target = clean.isEmpty() ? COMPONENT_STD : clean + ":" + COMPONENT_STD;
            boolean sOk = ShellExecutor.execShizuku(new String[]{"settings", "put", "secure",
                    "enabled_accessibility_services", target});
            boolean rOk = ShellExecutor.execRoot(
                    "settings put secure enabled_accessibility_services '" + target + "'");
            Log.d(TAG, "无障碍掉线，写回拉活 inList=" + inList + " shizuku=" + sOk + " root=" + rOk);
        } catch (Throwable ignored) {}
    }

    private static String removeSelf(String list) {
        if (list == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : list.split(":")) {
            if (p.equals(COMPONENT_STD) || p.equals(COMPONENT_SHORT)) continue;
            if (sb.length() > 0) sb.append(":");
            sb.append(p);
        }
        return sb.toString();
    }
}
