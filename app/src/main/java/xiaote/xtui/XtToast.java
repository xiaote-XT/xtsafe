package xiaote.xtui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * xtui 轻提示组件（两种形态 + 普通 Toast）。
 *
 * 1) 系统 Toast（静态方法）
 *      show(ctx, "文字") / showLong(...)
 *      圆角 + 无阴影，深色底 #161616 配白字，浅色底 #fbfbfb 配 #161616。
 *      注意：Android 11+ 会忽略自定义 View，这类机型上退化为纯文本提示。
 *
 * 2) 悬浮胶囊 pill（实例方法）
 *      showPill(text, durationMs) / showPill(resId, durationMs, args...)
 *      用于无障碍服务等后台场景（系统 Toast 在后台常被拦截），走 WindowManager。
 *      入场 180ms 淡入下滑，出场 150ms 淡出上移，动画结束才真正移除。
 *
 * 3) 悬浮横幅 banner（实例方法）
 *      showBanner(text, onClick) / hideBanner() / isBannerShowing()
 *
 * 自包含实现，不引用任何 R 资源。
 * 使用前提：pill / banner 的 Context 必须是 AccessibilityService。
 */
public class XtToast {

    /* ==================== 可配置色值 ==================== */

    public static int bgLight = 0xFFFBFBFB;
    public static int bgDark = 0xFF161616;
    public static int textLight = 0xFF161616;
    public static int textDark = 0xFFFFFFFF;

    public static int bannerBg = 0xFFE74C3C;
    public static int bannerTextColor = 0xFFFFFFFF;

    /* ==================== 可配置尺寸 ==================== */

    public static float radiusDp = 16f;
    public static float pillPadHDp = 20f;
    public static float pillPadVDp = 12f;
    public static float bannerPadHDp = 16f;
    public static float bannerPadVDp = 12f;
    public static float textSizeSp = 15f;
    public static float maxWidthDp = 320f;
    public static float topOffsetDp = 90f;
    public static float sideMarginDp = 16f;

    public static long defaultPillMs = 1500L;

    /** pill 入场时长 / 位移 */
    public static long pillInMs = 180L;
    public static float pillInOffsetDp = 16f;
    /** pill 出场时长 / 位移 */
    public static long pillOutMs = 150L;
    public static float pillOutOffsetDp = 12f;

    /* ==================== 点击回调 ==================== */

    public interface OnClick {
        void onClick();
    }

    /* ==================== 实例状态 ==================== */

    private final Context context;
    private final Handler handler;
    private WindowManager windowManager;

    private View pillView;
    private TextView pillText;
    private Runnable pillDismissTask;
    /** 正在做淡出动画：期间不重复触发移除 */
    private boolean pillHiding = false;

    private View bannerView;
    private TextView bannerText;
    private boolean bannerShowing = false;

    public XtToast(Context context, Handler handler) {
        this.context = context;
        this.handler = (handler != null) ? handler : new Handler();
        try {
            this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        } catch (Throwable ignored) {
            this.windowManager = null;
        }
    }

    public XtToast(Context context) {
        this(context, null);
    }

    /* ==================== 工具 ==================== */

    public static int dp(Context c, float v) {
        return XtUi.dp(c, v);
    }

    private static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    private static boolean dark(Context c) {
        try {
            return XtUi.isDark(c);
        } catch (Throwable t) {
            return false;
        }
    }

    private int windowType() {
        if (Build.VERSION.SDK_INT >= 22) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    /* ==================== 1) 系统 Toast ==================== */

    public static void show(Context c, CharSequence text) {
        make(c, text, Toast.LENGTH_SHORT).show();
    }

    public static void show(Context c, int resId) {
        show(c, c.getString(resId));
    }

    public static void showLong(Context c, CharSequence text) {
        make(c, text, Toast.LENGTH_LONG).show();
    }

    public static void showLong(Context c, int resId) {
        showLong(c, c.getString(resId));
    }

    /** 自行组装但未弹出的 Toast */
    public static Toast make(Context c, CharSequence text, int duration) {
        boolean isDark = dark(c);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setElevation(0f);
        box.setBackground(round(c, isDark ? bgDark : bgLight, radiusDp));
        box.setPadding(dp(c, pillPadHDp), dp(c, pillPadVDp),
                dp(c, pillPadHDp), dp(c, pillPadVDp));

        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(textSizeSp);
        tv.setTextColor(isDark ? textDark : textLight);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxWidth(dp(c, maxWidthDp));
        box.addView(tv);

        Toast t = Toast.makeText(c, text, duration);
        try {
            t.setView(box);
        } catch (Throwable ignored) {
        }
        return t;
    }

    /* ==================== 2) 悬浮胶囊 pill ==================== */

    public void showPill(CharSequence text, long durationMs) {
        if (windowManager == null) {
            try { show(context, text); } catch (Throwable ignored) {}
            return;
        }
        try {
            if (pillView == null) {
                pillView = buildPill();
            }
            pillText.setText(text);

            if (pillView.getParent() == null) {
                // 入场：透明 + 轻微上移，180ms 淡入下滑
                pillHiding = false;
                pillView.setAlpha(0f);
                pillView.setTranslationY(-dp(context, pillInOffsetDp));
                windowManager.addView(pillView, pillParams());
                pillView.animate().alpha(1f).translationY(0f)
                        .setDuration(pillInMs)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else {
                // 已在屏上：取消可能进行中的淡出，恢复完全不透明，只刷新文字
                pillHiding = false;
                pillView.animate().cancel();
                pillView.setAlpha(1f);
                pillView.setTranslationY(0f);
                pillText.setText(text);
            }
            resetPillTimer(durationMs > 0 ? durationMs : defaultPillMs);
        } catch (Throwable t) {
            try { show(context, text); } catch (Throwable ignored) {}
        }
    }

    public void showPill(int resId, long durationMs, Object... args) {
        String text;
        try {
            text = (args == null || args.length == 0)
                    ? context.getString(resId)
                    : context.getString(resId, args);
        } catch (Throwable t) {
            text = String.valueOf(resId);
        }
        showPill(text, durationMs);
    }

    private View buildPill() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setElevation(0f);
        box.setBackground(round(context, dark(context) ? bgDark : bgLight, radiusDp));
        box.setPadding(dp(context, pillPadHDp), dp(context, pillPadVDp),
                dp(context, pillPadHDp), dp(context, pillPadVDp));

        pillText = new TextView(context);
        pillText.setTextSize(textSizeSp);
        pillText.setTextColor(dark(context) ? textDark : textLight);
        pillText.setGravity(Gravity.CENTER);
        pillText.setMaxWidth(dp(context, maxWidthDp));
        box.addView(pillText);
        return box;
    }

    private WindowManager.LayoutParams pillParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = windowType();
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(context, topOffsetDp);
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        return lp;
    }

    private void resetPillTimer(long durationMs) {
        if (pillDismissTask != null) {
            handler.removeCallbacks(pillDismissTask);
        }
        pillDismissTask = new Runnable() {
            @Override
            public void run() {
                pillDismissTask = null;
                removePill();
            }
        };
        handler.postDelayed(pillDismissTask, durationMs);
    }

    public void hidePill() {
        if (pillDismissTask != null) {
            handler.removeCallbacks(pillDismissTask);
            pillDismissTask = null;
        }
        removePill();
    }

    /**
     * 收起 pill：先播 150ms 淡出上移，动画结束再真正 removeView。
     * 淡出期间若又来了新提示，showPill 会取消动画并复位，不会叠影。
     */
    private void removePill() {
        if (pillView == null || pillView.getParent() == null || windowManager == null) return;
        if (pillHiding) return;
        pillHiding = true;
        try {
            pillView.animate()
                    .alpha(0f)
                    .translationY(-dp(context, pillOutOffsetDp))
                    .setDuration(pillOutMs)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            pillHiding = false;
                            removePillImmediate();
                        }
                    })
                    .start();
        } catch (Throwable t) {
            pillHiding = false;
            removePillImmediate();
        }
    }

    /** 不做动画，直接从窗口移除；cleanup 与动画兜底用 */
    private void removePillImmediate() {
        try {
            if (pillView != null && pillView.getParent() != null && windowManager != null) {
                windowManager.removeView(pillView);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 3) 悬浮横幅 banner ==================== */

    public boolean isBannerShowing() {
        return bannerShowing;
    }

    public void showBanner(CharSequence text, final OnClick onClick) {
        if (windowManager == null) return;
        try {
            if (bannerView == null) {
                bannerView = buildBanner();
            }
            bannerText.setText(text);
            bannerView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (onClick != null) {
                        try { onClick.onClick(); } catch (Throwable ignored) {}
                    }
                }
            });
            if (bannerView.getParent() == null) {
                windowManager.addView(bannerView, bannerParams());
            }
            bannerShowing = true;
        } catch (Throwable ignored) {
        }
    }

    private View buildBanner() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setElevation(0f);
        box.setBackground(round(context, bannerBg, radiusDp));
        box.setPadding(dp(context, bannerPadHDp), dp(context, bannerPadVDp),
                dp(context, bannerPadHDp), dp(context, bannerPadVDp));

        bannerText = new TextView(context);
        bannerText.setTextSize(textSizeSp);
        bannerText.setTextColor(bannerTextColor);
        bannerText.setGravity(Gravity.CENTER);
        box.addView(bannerText);
        return box;
    }

    private WindowManager.LayoutParams bannerParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = windowType();
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.TOP;
        lp.y = dp(context, topOffsetDp);
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        return lp;
    }

    public void hideBanner() {
        bannerShowing = false;
        try {
            if (bannerView != null && bannerView.getParent() != null && windowManager != null) {
                windowManager.removeView(bannerView);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 统一清理 ==================== */

    public void cleanup() {
        hidePill();
        hideBanner();
        // 服务/页面销毁时不播淡出，直接摘掉窗口
        removePillImmediate();
        pillView = null;
        pillText = null;
        bannerView = null;
        bannerText = null;
    }
}
