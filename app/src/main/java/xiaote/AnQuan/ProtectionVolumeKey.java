package xiaote.AnQuan;

import xiaote.xtui.HintOverlayManager;

import android.content.SharedPreferences;
import android.os.Handler;
import android.view.KeyEvent;

/**
 * 主动防护 - 音量键连按识别。
 * 连续按音量下键计数，达到阈值触发紧急操作（强制停止 + 唤起卸载列表）。
 * 提取自 XTSafeMainService.onKeyEvent。
 *
 * 依赖无障碍配置 android:canRequestFilterKeyEvents="true"，否则 onKeyEvent 不会被回调。
 */
public class ProtectionVolumeKey {

    /** 连按有效时间窗口 */
    private static final long TIME_WINDOW = 1000L;
    /** 触发阈值（连按次数） */
    private static final int PRESS_THRESHOLD = 10;

    private final Handler handler;
    private final SharedPreferences dotPrefs;
    private final OverlayManager overlayManager;
    private final EmergencyManager emergencyManager;
    private final HintOverlayManager hintOverlayManager;

    private int pressCount = 0;
    private long lastPressTime = 0;

    public ProtectionVolumeKey(Handler handler, SharedPreferences dotPrefs,
                          OverlayManager overlayManager, EmergencyManager emergencyManager,
                          HintOverlayManager hintOverlayManager) {
        this.handler = handler;
        this.dotPrefs = dotPrefs;
        this.overlayManager = overlayManager;
        this.emergencyManager = emergencyManager;
        this.hintOverlayManager = hintOverlayManager;
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
        if (hintOverlayManager != null) {
            hintOverlayManager.showCounter(R.string.volume_press_count, 1500L, current);
        }

        if (pressCount >= PRESS_THRESHOLD) {
            pressCount = 0;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    // 按十下音量-触发强制停止所有第三方应用（无障碍按键识别）
                    if (dotPrefs.getBoolean("volume_key_force_stop_all", true) && emergencyManager != null) {
                        emergencyManager.forceStopAll();
                    }
                    if (overlayManager != null) {
                        overlayManager.showUninstallList();
                    }
                }
            });
            return true;
        }
        return false;
    }
}
