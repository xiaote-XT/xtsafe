package xiaote.AnQuan;

import xiaote.xtui.XtToast;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.KeyEvent;

/**
 * 主动防护 - 音量键连按识别。
 * 连续按音量下键计数，达到阈值触发「选择操作」里勾选的安全操作。
 *
 * 依赖无障碍配置 android:canRequestFilterKeyEvents="true"，否则 onKeyEvent 不会被回调。
 */
public class ProtectionVolumeKey {

    /** 连按有效时间窗口 */
    private static final long TIME_WINDOW = 1000L;
    /** 触发阈值（连按次数） */
    private static final int PRESS_THRESHOLD = 10;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences dotPrefs;
    private final XtToast xtToast;

    /*
     * 计数状态刻意用 static，不用实例字段。
     *
     * 无障碍服务一旦被禁用再启用（恶意应用摘挂、系统杀进程、保活写回拉起），
     * 系统会销毁旧实例并重建，onServiceConnected 里 new 一个新的
     * ProtectionVolumeKey；实例字段随之清零 —— 表现就是「连按到第 7 下时无障碍
     * 断了一下，计数从头开始，永远凑不满十下」。
     *
     * 计数本身属于「用户这一串按键」而不是「某个服务实例」，因此放静态，
     * 服务重建后继续累加。TIME_WINDOW 内没有任何按键时会自然重置为 1，
     * 不存在跨天残留的问题。
     */
    private static volatile int pressCount = 0;
    private static volatile long lastPressTime = 0L;

    public ProtectionVolumeKey(Context context, Handler handler, SharedPreferences dotPrefs, XtToast xtToast) {
        this.context = context;
        this.handler = handler;
        this.dotPrefs = dotPrefs;
        this.xtToast = xtToast;
    }

    public static int getThreshold() {
        return PRESS_THRESHOLD;
    }

    /** 当前连按次数 */
    public int getPressCount() {
        return pressCount;
    }

    /**
     * 处理按键事件。
     *
     * @return true 表示事件已被消费，不再向下传递
     */
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN
                || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastPressTime > TIME_WINDOW) {
            pressCount = 1;
        } else {
            pressCount++;
        }
        lastPressTime = now;

        // 显示当前连按次数：用无障碍悬浮窗而非 Toast（Toast 在本服务/后台易被系统拦截）
        final int current = pressCount;
        if (xtToast != null) {
            xtToast.showPill(R.string.volume_press_count, 1500L, current);
        }

        if (pressCount >= PRESS_THRESHOLD) {
            pressCount = 0;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    // 连按十下音量-：执行「选择操作」里勾选的安全操作，完成后提示执行结果
                    if (dotPrefs.getBoolean("volume_key_action", true)) {
                        SafeActionManager.run(context, new SafeActionManager.Callback() {
                            @Override
                            public void onDone(String summary) {
                                if (xtToast != null) {
                                    xtToast.showPill("安全操作已执行：" + summary, 2500L);
                                }
                            }
                        });
                    }
                }
            });
            return true;
        }
        return false;
    }
}
