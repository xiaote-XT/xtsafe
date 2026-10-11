package xiaote.AnQuan;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.animation.ValueAnimator;

/**
 * 界面水印。
 *
 * 覆盖范围：由 App 在 Application 层注册 ActivityLifecycleCallbacks，
 * 所有 Activity 创建后自动叠加一层水印 View。放在 Application 层而不是
 * BaseActivity，是为了覆盖那些刻意继承 Activity（不走 BaseActivity）的页面，
 * 例如 ShizukuActivity / SecurityScanActivity / KillAdActivity 等。
 *
 * 水印行为：
 *   · 居中绘制两行文字：「星特安全」与项目地址；
 *   · 整体绕屏幕中心缓慢旋转，30 秒一圈、线性；
 *   · 浅色半透明：浅色主题用低透明度黑，深色主题用低透明度白；
 *   · 不可点击、不可聚焦，onTouchEvent 返回 false，触摸事件穿透到下层，
 *     不改变任何原有交互。
 *
 * 旋转角度由全局时间线推算，不挂在各个 View 实例上：
 * 切页面、转屏幕、Activity 重建，水印都接着当前角度继续转，不会跳回 0 度。
 *
 * 该水印同时是项目根目录 NOTICE 中 AGPL-3.0 第 7(b) 条附加条款
 * 所列的来源标识之一：移除、隐藏或遮挡水印将违反附加条款。
 */
public final class WatermarkHelper {

    /** 水印 View 的标记，防止同一 Activity 重复叠加 */
    private static final String TAG = "xt_safe_watermark";

    /** 标题行 */
    private static final String TEXT_TITLE = "星特安全";
    /** 项目地址行 */
    private static final String TEXT_URL = "github.com/xiaote-XT/xtsafe";
    /** 旋转一圈用时：30 秒，足够缓慢，不干扰阅读 */
    private static final long ROTATE_DURATION_MS = 30000L;

    /**
     * 旋转基准时刻，全应用共用。
     *
     * 用 SystemClock.elapsedRealtime() 而不是 currentTimeMillis()：
     * 前者是开机以来的毫秒数，不受用户改系统时间影响，水印不会因为
     * 时间被调而突然跳一大截。
     *
     * 每个页面的水印 View 都是独立实例，但角度不再各自从 0 起算，
     * 而是统一由「当前时刻 - 基准时刻」推算，因此切换页面、旋转屏幕、
     * 甚至 Activity 重建，水印角度都能无缝接上，不会跳回 0 度。
     * 静态字段在进程存活期间持续有效；进程被杀后重启会重新计时，
     * 这是可接受的（页面全部重建，本来就没有连续性可言）。
     */
    private static volatile long sRotateBase = 0L;

    /** 当前应显示的角度（0~360），所有页面共用同一条时间线 */
    private static float currentAngle() {
        if (sRotateBase == 0L) {
            sRotateBase = android.os.SystemClock.elapsedRealtime();
        }
        long elapsed = android.os.SystemClock.elapsedRealtime() - sRotateBase;
        long pos = elapsed % ROTATE_DURATION_MS;
        return pos * 360f / ROTATE_DURATION_MS;
    }

    private WatermarkHelper() {}

    /** 给指定 Activity 叠加水印；重复调用不会重复添加 */
    public static void attach(Activity activity) {
        if (activity == null) return;
        try {
            ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
            if (content == null) return;
            if (content.findViewWithTag(TAG) != null) return;
            WatermarkView view = new WatermarkView(activity);
            view.setTag(TAG);
            /*
             * 用 ViewGroup.LayoutParams 而不是 FrameLayout.LayoutParams。
             * android.R.id.content 通常是 FrameLayout，但部分 ROM 或自定义
             * 主题会换成别的 ViewGroup；此时把 FrameLayout.LayoutParams
             * 交给它 addView 会抛 ClassCastException，被外层 catch 吞掉后
             * 表现为「水印没加上」且没有任何日志。基类 LayoutParams 在所有
             * ViewGroup 上都可用，MATCH_PARENT 语义一致。
             */
            content.addView(view, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignored) {}
    }

    /** 当前是否深色主题 */
    private static boolean isDark(Context ctx) {
        try {
            int mode = ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 水印本体：自绘文字 + 持续旋转 */
    private static class WatermarkView extends View {

        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint urlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator animator;

        WatermarkView(Context ctx) {
            super(ctx);
            // 不参与交互：点击与焦点一律让给下层
            setClickable(false);
            setFocusable(false);
            setWillNotDraw(false);

            /*
             * 浅色半透明。
             * 之前用 0x1F（约 12%）和 0x1A（约 10%），在实机屏幕上太淡，
             * 加上文字本身还在缓慢旋转，很容易被当成"没显示"。
             * 现在提到 0x33（约 20%）和 0x2E（约 18%）：仍然明显是水印、
             * 不遮挡正文阅读，但在浅色与深色背景下都看得清。
             */
            int color = isDark(ctx) ? 0x2EFFFFFF : 0x33000000;

            titlePaint.setColor(color);
            titlePaint.setTextAlign(Paint.Align.CENTER);
            titlePaint.setTextSize(sp(ctx, 30));
            titlePaint.setTypeface(Typeface.DEFAULT_BOLD);

            urlPaint.setColor(color);
            urlPaint.setTextAlign(Paint.Align.CENTER);
            urlPaint.setTextSize(sp(ctx, 15));
        }

        private static float sp(Context ctx, float value) {
            return value * ctx.getResources().getDisplayMetrics().scaledDensity;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f;
            float cy = h / 2f;
            // 两行文字以屏幕中心为基准上下排布，整体再绕中心旋转
            float gap = titlePaint.getTextSize() * 1.8f;
            canvas.save();
            // 角度由全局时间线推算，新页面一挂上就接在当前位置，不会跳回 0
            canvas.rotate(currentAngle(), cx, cy);
            canvas.drawText(TEXT_TITLE, cx, cy - gap / 2f, titlePaint);
            canvas.drawText(TEXT_URL, cx, cy + gap / 2f, urlPaint);
            canvas.restore();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            startRotate();
        }

        @Override
        protected void onDetachedFromWindow() {
            stopRotate();
            super.onDetachedFromWindow();
        }

        private void startRotate() {
            stopRotate();
            try {
                animator = ValueAnimator.ofFloat(0f, 360f);
                animator.setDuration(ROTATE_DURATION_MS);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setInterpolator(new LinearInterpolator());
                /*
                 * 动画只当「每帧刷新」的触发器用：角度本身由 currentAngle()
                 * 按时间推算，不再从 ValueAnimator 取值。
                 *
                 * 这样同一份进度在多个页面之间天然一致 —— 之前每页各自跑一个
                 * 0→360 的动画，切页就重新从 0 开始，视觉上就是「旋转被打断」。
                 */
                animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        invalidate();
                    }
                });
                animator.start();
            } catch (Throwable ignored) {}
        }

        private void stopRotate() {
            try {
                if (animator != null) {
                    animator.cancel();
                    animator = null;
                }
            } catch (Throwable ignored) {}
        }
    }
}
