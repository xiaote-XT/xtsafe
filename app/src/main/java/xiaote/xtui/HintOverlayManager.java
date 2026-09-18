package xiaote.xtui;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 统一提示浮窗管理器：集中管理无障碍服务里的所有「轻提示」UI，方便统一风格。
 *
 * 目前包含两类：
 *   1. 顶部风险横幅（risk banner）—— 命中木马话术时显示，点击唤起卸载，风险文字消失时收起
 *   2. 顶部计数提示（counter hint）—— 如音量键连按计数，N 毫秒无更新自动消失
 *
 * 设计：每个提示是独立 View 与独立 WindowManager.LayoutParams，
 * 但样式由本类统一构建（圆角、背景、文字大小、边距、层级类型），
 * 调用方只关心「显示什么文字 / 何时收起」。
 */
public class HintOverlayManager {

    /** 提示默认停留时长（毫秒） */
    private static final long DEFAULT_HINT_DURATION = 1500L;

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Handler handler;

    // ---- 风险横幅 ----
    private View riskBanner;

    // ---- 计数提示 ----
    private View counterView;
    private final Runnable counterDismiss = new Runnable() {
        @Override
        public void run() {
            hideCounter();
        }
    };

    /** 点击风险横幅的回调（由调用方决定后续动作） */
    public interface OnRiskBannerClick {
        void onClick();
    }

    public HintOverlayManager(AccessibilityService service, Handler handler) {
        this.service = service;
        this.handler = handler;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    private float density() {
        return service.getResources().getDisplayMetrics().density;
    }

    private int dp(int v) {
        return (int) (v * density() + 0.5f);
    }

    // ==================== 风险横幅 ====================

    public boolean isRiskBannerShowing() {
        return riskBanner != null;
    }

    /**
     * 显示顶部风险横幅。已在显示时保持原样（防频闪）。
     *
     * @param text    横幅文案
     * @param listener 点击回调，可为 null
     */
    public void showRiskBanner(String text, final OnRiskBannerClick listener) {
        try {
            if (riskBanner != null) return;
            if (wm == null) return;

            LinearLayout banner = new LinearLayout(service);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setPadding(dp(12), dp(6), dp(12), dp(6));
            banner.setBackgroundColor(0xCCD32F2F);
            banner.setClickable(true);

            TextView tv = new TextView(service);
            tv.setText(text == null ? "" : text);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(14);
            tv.setGravity(Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            banner.addView(tv);
            banner.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideRiskBanner();
                    if (listener != null) listener.onClick();
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            lp.windowAnimations = android.R.style.Animation_Dialog;

            preparePopIn(banner);
            wm.addView(banner, lp);
            riskBanner = banner;
            playPopIn(banner);
        } catch (Exception ignored) {}
    }

    public void hideRiskBanner() {
        if (riskBanner == null) return;
        try {
            if (wm != null) wm.removeView(riskBanner);
        } catch (Exception ignored) {}
        riskBanner = null;
    }

    // ==================== 计数提示（顶部居中，自动消失） ====================

    public boolean isCounterShowing() {
        return counterView != null;
    }

    /**
     * 显示/更新顶部计数提示。复用一个浮窗，重复调用只更新文字并重置计时。
     *
     * @param text     显示文字
     * @param durationMs 无更新自动消失时长（毫秒），<=0 用默认值
     */
    public void showCounter(String text, long durationMs) {
        long dur = durationMs > 0 ? durationMs : DEFAULT_HINT_DURATION;
        try {
            if (wm == null) return;
            if (counterView != null) {
                Object tag = counterView.getTag();
                if (tag instanceof TextView) {
                    ((TextView) tag).setText(text == null ? "" : text);
                }
                handler.removeCallbacks(counterDismiss);
                handler.postDelayed(counterDismiss, dur);
                return;
            }

            LinearLayout box = new LinearLayout(service);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(0xCC000000);
            box.setBackground(bg);
            box.setPadding(dp(18), dp(10), dp(18), dp(10));

            TextView tv = new TextView(service);
            tv.setText(text == null ? "" : text);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            box.addView(tv);
            box.setTag(tv);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(80);

            preparePopIn(box);
            wm.addView(box, lp);
            counterView = box;
            playPopIn(box);
            handler.removeCallbacks(counterDismiss);
            handler.postDelayed(counterDismiss, dur);
        } catch (Exception ignored) {}
    }

    /**
     * 按资源 id 显示计数提示，自动做参数格式化。
     *
     * @param resId      字符串资源 id
     * @param durationMs 无更新自动消失时长（毫秒），<=0 用默认值
     * @param args       格式化参数
     */
    public void showCounter(int resId, long durationMs, Object... args) {
        String text = null;
        try {
            text = service.getString(resId, args);
        } catch (Exception ignored) {}
        showCounter(text, durationMs);
    }

    public void hideCounter() {
        handler.removeCallbacks(counterDismiss);
        if (counterView != null) {
            try {
                if (wm != null) wm.removeView(counterView);
            } catch (Exception ignored) {}
            counterView = null;
        }
    }

    // ==================== 缩放弹出动画 ====================

    /** 动画前初始状态：略小 + 透明（在 addView 之前调用，避免闪一帧原大小） */
    private void preparePopIn(View v) {
        if (v == null) return;
        v.setScaleX(0.85f);
        v.setScaleY(0.85f);
        v.setAlpha(0f);
    }

    /** 播放缩放弹出动画：0.85 → 1.0，透明度 0 → 1，200ms */
    private void playPopIn(View v) {
        if (v == null) return;
        v.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(200L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    // ==================== 统一清理 ====================

    /** 服务销毁时清理全部提示与回调 */
    public void cleanup() {
        handler.removeCallbacks(counterDismiss);
        hideCounter();
        hideRiskBanner();
    }
}
