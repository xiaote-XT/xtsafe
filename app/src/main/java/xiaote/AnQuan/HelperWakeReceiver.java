package xiaote.AnQuan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import xiaote.AnQuan.InternalApi.AccessibilityApi;

/**
 * 保活助手唤醒接收器。
 *
 * 星特安全被用户「强行停止」或恶意应用 force-stop 后会进入 stopped state：
 * 自定义广播、开机广播、AlarmManager 闹钟、JobScheduler 任务全部被系统清空，
 * 应用自身无法自愈。唯一可行的应用层出路是「外部实体发带
 * FLAG_INCLUDE_STOPPED_PACKAGES 的显式广播」——该 flag 能穿透 stopped 状态。
 *
 * 配套的「星特安全保活助手」每 1 秒发一次 xiaote.AnQuan.action.WAKE_TARGET，
 * 本接收器收到后立刻：
 *   1. 拉起核心常驻服务 XTSafeCoreService；
 *   2. 把自身无障碍写回系统启用列表（带节流，见下）；
 *   3. 回发一条唤醒广播给助手，维持双向心跳。
 *
 * 本接收器必须 exported=true，否则外部应用的广播投不进来。
 */
public class HelperWakeReceiver extends BroadcastReceiver {

    private static final String TAG = "XTWakeReceiver";

    /** 与保活助手侧 KeepAliveService.ACTION_WAKE_TARGET 对应 */
    public static final String ACTION_WAKE_TARGET = "xiaote.AnQuan.action.WAKE_TARGET";

    /**
     * 无障碍写回节流时间戳。
     *
     * 助手默认 1 秒发一次唤醒广播，若每次都在 onReceive 里同步做无障碍写回，
     * 就是每秒一次跨进程 shell 调用 —— 耗电明显，还会把主线程拖到 ANR 边缘。
     * 两道保护：
     *   1. 节流：距上次写回不足 10 秒直接跳过（XTSafeCoreService 的保活循环
     *      本身也会按用户配置的频率继续处理）；
     *   2. 丢到后台线程：writeSelfBack 是阻塞调用，绝不能挂在广播主线程上。
     */
    private static volatile long sLastWriteBackAt = 0L;
    private static final long WRITE_BACK_THROTTLE_MS = 10000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!ACTION_WAKE_TARGET.equals(intent.getAction())) return;

        Log.d(TAG, "收到保活助手唤醒广播");

        // 1. 拉起核心常驻服务（音量阈值防护 / 阻止卸载 / 无障碍保活都在这里）
        try { XTSafeCoreService.ensureStarted(context); } catch (Throwable ignored) {}

        // 2. 尝试把自身无障碍写回系统启用列表，节流 + 后台线程
        try {
            long now = System.currentTimeMillis();
            if (now - sLastWriteBackAt >= WRITE_BACK_THROTTLE_MS) {
                sLastWriteBackAt = now;
                final Context appCtx = context.getApplicationContext();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try { AccessibilityApi.writeSelfBack(appCtx); } catch (Throwable ignored) {}
                    }
                }, "xt-wake-writeback").start();
            }
        } catch (Throwable ignored) {}

        // 3. 回发一条唤醒给助手，双向心跳，任一方活着都能把对方拽起来
        try { HelperWakeSender.wakeHelperNow(context); } catch (Throwable ignored) {}
    }
}
