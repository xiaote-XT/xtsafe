package xiaote.xtui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.animation.ValueAnimator;

/**
 * xtui 通用 UI 构造器（零 R 引用，可整体搬到其他项目）。
 *
 * 规范：
 *   主题色 #ff6a00，按下态 #e55a00；浅色底 #ffffff / 深色底 #1e1e1e，
 *   卡片靠 0.75dp 细描边分层，无阴影；主圆角 16dp。
 *   按钮按下 scale(0.97)，松手 100ms 后回弹。
 *
 * 所有颜色都按 isDark() 动态取，深浅主题自动跟随。
 */
public final class XtUi {

    /* ==================== 主题色 ==================== */

    public static int primary = 0xFFFF6A00;
    public static int primaryPressed = 0xFFE55A00;

    public static int bgLight = 0xFFFFFFFF;
    public static int bgDark = 0xFF1E1E1E;
    public static int cardLight = 0xFFFFFFFF;
    public static int cardDark = 0xFF1E1E1E;
    public static int textLight = 0xFF1E1E1E;
    public static int textDark = 0xFFFFFFFF;
    public static int mutedLight = 0xFF5E5E5E;
    public static int mutedDark = 0xFFB0B0B0;
    public static int borderLight = 0xFFDDDDDD;
    public static int borderDark = 0xFF444444;

    /* ==================== 尺寸 ==================== */

    public static float radiusDp = 16f;
    public static float radiusSheetDp = 20f;
    public static float radiusMenuDp = 12f;
    public static float borderDp = 0.75f;
    public static float cardPadDp = 16f;
    public static float cardGapDp = 14f;
    public static float sectionGapDp = 10f;
    public static float btnPadVDp = 13f;
    public static float btnPadHDp = 16f;
    public static float btnTextSp = 16f;
    public static float sectionTextSp = 20f;
    public static float pressAnimMs = 120f;
    public static float releaseDelayMs = 100f;
    public static float pressScale = 0.97f;
    public static float switchWidthDp = 50f;
    public static float switchHeightDp = 24f;
    /** 列表图标尺寸与圆角（应用列表统一用） */
    public static float iconSizeDp = 44f;
    public static float iconRadiusDp = 12f;

    /** 强制深浅模式；null 表示跟随系统 */
    public static Boolean forceDark = null;

    private XtUi() {}

    /* ==================== 深浅与取色 ==================== */

    public static boolean isDark(Context c) {
        if (forceDark != null) return forceDark;
        try {
            int mode = c.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int dp(Context c, float v) {
        if (c == null) return (int) v;
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static int colorBg(Context c) { return isDark(c) ? bgDark : bgLight; }
    public static int colorCard(Context c) { return isDark(c) ? cardDark : cardLight; }
    public static int colorText(Context c) { return isDark(c) ? textDark : textLight; }
    public static int colorMuted(Context c) { return isDark(c) ? mutedDark : mutedLight; }
    public static int colorBorder(Context c) { return isDark(c) ? borderDark : borderLight; }

    private static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    private static GradientDrawable cardBg(Context c) {
        GradientDrawable d = round(c, colorCard(c), radiusDp);
        int w = dp(c, borderDp);
        if (w < 1) w = 1;
        d.setStroke(w, colorBorder(c));
        return d;
    }

    /* ==================== 骨架 ==================== */

    /** 纵向根容器：自带背景色与整体内边距 */
    public static LinearLayout column(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colorBg(c));
        int p = dp(c, 16);
        root.setPadding(p, p, p, p);
        return root;
    }

    /** 把 root 装进 ScrollView 并返回 ScrollView（root 不能再 addView） */
    public static ScrollView scroll(Context c, View root) {
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setBackgroundColor(colorBg(c));
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    /** 卡片：卡色底 + 细描边 + 中圆角 + 无阴影 */
    public static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(cardBg(c));
        box.setElevation(0f);
        int p = dp(c, cardPadDp);
        box.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, cardGapDp);
        box.setLayoutParams(lp);
        return box;
    }

    /** 带小标题的卡片：小标题也在卡片内，主题色 20sp 居中 */
    public static LinearLayout cardWithSection(Context c, CharSequence sectionText) {
        LinearLayout card = card(c);
        if (sectionText != null && sectionText.length() > 0) {
            TextView tv = new TextView(c);
            tv.setText(sectionText);
            tv.setTextSize(sectionTextSp);
            tv.setTextColor(primary);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, 0, 0, dp(c, sectionGapDp));
            card.addView(tv);
        }
        return card;
    }

    /** 页面大标题 */
    public static TextView title(Context c, CharSequence text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(sectionTextSp);
        tv.setTextColor(primary);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(c, 24), 0, dp(c, 16));
        return tv;
    }

    /** 提示文字（muted） */
    public static TextView tip(Context c, CharSequence text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(colorMuted(c));
        tv.setLineSpacing(0, 1.3f);
        return tv;
    }

    /** 正文（正常文字色） */
    public static TextView body(Context c, CharSequence text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(colorText(c));
        return tv;
    }

    /* ==================== 按钮 ==================== */

    /** 普通按钮：卡色底 + 细描边 + 中圆角 */
    public static TextView button(Context c, CharSequence text, View.OnClickListener l) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(btnTextSp);
        tv.setTextColor(colorText(c));
        tv.setGravity(Gravity.CENTER);
        GradientDrawable d = round(c, colorCard(c), radiusDp);
        int w = dp(c, borderDp);
        if (w < 1) w = 1;
        d.setStroke(w, colorBorder(c));
        tv.setBackground(d);
        tv.setPadding(dp(c, btnPadHDp), dp(c, btnPadVDp), dp(c, btnPadHDp), dp(c, btnPadVDp));
        tv.setClickable(true);
        if (l != null) tv.setOnClickListener(l);
        attachScale(tv);
        return tv;
    }

    /** 主按钮：主题色底 + 白字 */
    public static TextView primaryButton(Context c, CharSequence text, View.OnClickListener l) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(btnTextSp);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(round(c, primary, radiusDp));
        tv.setPadding(dp(c, btnPadHDp), dp(c, btnPadVDp), dp(c, btnPadHDp), dp(c, btnPadVDp));
        tv.setClickable(true);
        if (l != null) tv.setOnClickListener(l);
        attachScale(tv);
        return tv;
    }

    /** 往容器里加一个普通按钮（自动带下间距） */
    public static TextView addButton(LinearLayout root, Context c, CharSequence text, View.OnClickListener l) {
        TextView b = button(c, text, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        lp.bottomMargin = dp(c, 6);
        root.addView(b, lp);
        return b;
    }

    /** 一行：左标签 + 右控件（开关等） */
    public static LinearLayout row(Context c, CharSequence label, View right) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(c, 6), 0, dp(c, 6));
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextSize(15f);
        tv.setTextColor(colorText(c));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(tv);
        if (right != null) r.addView(right);
        return r;
    }

    /**
     * 统一尺寸、统一圆角的应用图标控件。
     *
     * 尺寸固定 iconSizeDp × iconSizeDp —— 各应用自带图标大小不一，
     * 不写死会让列表行高参差不齐。
     *
     * 图标铺满整个方形区域（CENTER_CROP，不留白），再由 clipToOutline
     * 把图标本身连同底板一起裁成圆角，这样看到的是圆角图标，而不是
     * 「里面一个小图标 + 外面一个圆角框」。透明边缘处露出的是卡色底板，
     * 圆角轮廓依然成立。
     */
    public static ImageView iconView(Context c, android.graphics.drawable.Drawable icon) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(round(c, colorCard(c), iconRadiusDp));
        if (icon != null) iv.setImageDrawable(icon);
        final float r = dp(c, iconRadiusDp);
        try {
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            iv.setClipToOutline(true);
        } catch (Throwable ignored) {}
        return iv;
    }

    /**
     * 一行：左图标 + 中间标题/副标题 + 右侧控件。
     *
     * 用于应用列表（统一 44dp 圆角图标 + 应用名 + 包名）。subtitle / right 可为 null。
     * 不主动给卡片底色，由调用方决定放在卡片里还是裸放。
     */
    public static LinearLayout itemRow(Context c, android.graphics.drawable.Drawable icon,
            CharSequence title, CharSequence subtitle, View right) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int py = dp(c, 8);
        row.setPadding(0, py, 0, py);

        ImageView iv = iconView(c, icon);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                dp(c, iconSizeDp), dp(c, iconSizeDp));
        ilp.rightMargin = dp(c, 12);
        iv.setLayoutParams(ilp);
        row.addView(iv);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(c);
        tvTitle.setText(title == null ? "" : title);
        tvTitle.setTextSize(15f);
        tvTitle.setTextColor(colorText(c));
        col.addView(tvTitle);

        if (subtitle != null && subtitle.length() > 0) {
            TextView tvSub = new TextView(c);
            tvSub.setText(subtitle);
            tvSub.setTextSize(12f);
            tvSub.setTextColor(colorMuted(c));
            tvSub.setPadding(0, dp(c, 2), 0, 0);
            col.addView(tvSub);
        }
        row.addView(col);
        if (right != null) row.addView(right);
        return row;
    }

    /* ==================== 自绘胶囊开关 ==================== */

    /** 开关：50×24dp 胶囊，选中主题色，150ms 动画 */
    public static XtSwitch switchView(Context c, boolean checked) {
        XtSwitch s = new XtSwitch(c);
        s.setChecked(checked);
        return s;
    }

    /** 滑块：全圆轨道，主题色进度 */
    public static SeekBar slider(Context c, int max, int progress) {
        SeekBar sb = new SeekBar(c);
        sb.setMax(max < 1 ? 1 : max);
        sb.setProgress(progress);
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                sb.setProgressTintList(android.content.res.ColorStateList.valueOf(primary));
                sb.setThumbTintList(android.content.res.ColorStateList.valueOf(primary));
                sb.setProgressBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(colorBorder(c)));
            }
        } catch (Throwable ignored) {}
        /*
         * 左右各留出 12dp，让圆形滑块在进度 0% / 100% 时整体仍在控件范围内。
         * 不加内边距时，thumb 圆心正好落在轨道两端，外侧一半会被父容器裁掉 ——
         * 表现就是「拖到最高或最低时圆点缺一半」。
         * setThumbOffset(0) 让 thumb 中心与轨道端点对齐到内边距边界，
         * 偏移量与 padding 配合，各家 ROM 上都能完整显示。
         */
        int ph = dp(c, 12);
        sb.setPadding(ph, sb.getPaddingTop(), ph, sb.getPaddingBottom());
        try { sb.setThumbOffset(0); } catch (Throwable ignored) {}
        return sb;
    }

    /* ==================== 状态栏 ==================== */

    /** 状态栏与页面同色 */
    public static void applyStatusBar(Activity a) {
        if (a == null) return;
        try {
            android.view.Window w = a.getWindow();
            w.setStatusBarColor(colorBg(a));
            View dv = w.getDecorView();
            int flags = dv.getSystemUiVisibility();
            if (isDark(a)) {
                if (Build.VERSION.SDK_INT >= 23) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                if (Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            dv.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {}
    }

    /* ==================== 按下反馈 ==================== */

    /** 按下缩到 0.97，松手延迟 100ms 回弹 */
    public static void attachScale(final View v) {
        if (v == null) return;
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(final View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(pressScale).scaleY(pressScale)
                                .setDuration((long) pressAnimMs).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                view.animate().scaleX(1f).scaleY(1f)
                                        .setDuration((long) pressAnimMs).start();
                            }
                        }, (long) releaseDelayMs);
                        break;
                }
                return false;
            }
        });
    }

    /* ==================== 左上角圆形返回按钮 ==================== */

    public static float backSizeDp = 40f;
    public static float backIconDp = 24f;
    public static float backMarginDp = 12f;
    public static int backIconRes = 0;

    private static int sBackIconRes = 0;
    private static boolean sBackIconResolved = false;

    public static int resolveBackIcon(Context c) {
        if (backIconRes != 0) return backIconRes;
        if (sBackIconResolved) return sBackIconRes;
        sBackIconResolved = true;
        try {
            int id = c.getResources().getIdentifier(
                    "xt_ic_arrow_left", "drawable", c.getPackageName());
            if (id != 0) sBackIconRes = id;
        } catch (Throwable ignored) {}
        return sBackIconRes;
    }

    /**
     * 在页面左上角挂一个圆形返回按钮。
     *
     * 做法：把 android.R.id.content 里的页面视图取出，套进 FrameLayout，
     * 再把圆钮浮在左上角，不动各页原有布局。
     * 各页只需在 onCreate 末尾加一行 XtUi.attachBack(this)。
     */
    public static View attachBack(final Activity a) {
        if (a == null) return null;
        try {
            int contentId = a.getResources().getIdentifier("content", "id", "android");
            if (contentId == 0) return null;
            View cv = a.findViewById(contentId);
            if (!(cv instanceof ViewGroup)) return null;
            ViewGroup root = (ViewGroup) cv;
            if (root.getChildCount() == 0) return null;

            View page = root.getChildAt(0);
            root.removeView(page);

            FrameLayout wrap = new FrameLayout(a);
            wrap.addView(page, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            View btn = buildBackButton(a);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    dp(a, backSizeDp), dp(a, backSizeDp));
            lp.leftMargin = dp(a, backMarginDp);
            lp.topMargin = dp(a, backMarginDp);
            wrap.addView(btn, lp);

            root.addView(wrap, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return btn;
        } catch (Throwable t) {
            return null;
        }
    }

    private static View buildBackButton(final Activity a) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(colorCard(a));
        int w = dp(a, borderDp);
        if (w < 1) w = 1;
        bg.setStroke(w, colorBorder(a));

        final View btn;
        int icon = resolveBackIcon(a);
        if (icon != 0) {
            ImageView iv = new ImageView(a);
            iv.setImageResource(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try {
                iv.setColorFilter(colorText(a), PorterDuff.Mode.SRC_IN);
            } catch (Throwable ignored) {}
            int pad = dp(a, (backSizeDp - backIconDp) / 2f);
            iv.setPadding(pad, pad, pad, pad);
            btn = iv;
        } else {
            TextView tv = new TextView(a);
            tv.setText("\u2190");
            tv.setTextSize(20f);
            tv.setTextColor(colorText(a));
            tv.setGravity(Gravity.CENTER);
            btn = tv;
        }
        btn.setBackground(bg);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.finish(); }
        });
        attachScale(btn);
        return btn;
    }

    /* ==================== 自绘胶囊开关实现 ==================== */

    /**
     * 50×24dp 胶囊开关。
     *
     * setChecked() 刻意不触发监听，供代码回写状态用（避免递归），
     * 与系统 Switch 的行为差异需要注意。
     */
    public static class XtSwitch extends View {

        public interface OnCheckedChangeListener {
            void onCheckedChanged(XtSwitch buttonView, boolean isChecked);
        }

        private boolean checked = false;
        private float progress = 0f;
        private ValueAnimator anim;
        private OnCheckedChangeListener listener;

        private final android.graphics.Paint trackPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint thumbPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();

        public XtSwitch(Context c) {
            super(c);
            trackPaint.setStyle(android.graphics.Paint.Style.FILL);
            thumbPaint.setStyle(android.graphics.Paint.Style.FILL);
            thumbPaint.setColor(0xFFFFFFFF);
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) { toggle(); }
            });
        }

        public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
            this.listener = l;
        }

        public boolean isChecked() { return checked; }

        /** 代码回写状态，不触发监听 */
        public void setChecked(boolean v) {
            if (this.checked == v) return;
            this.checked = v;
            animateTo(v ? 1f : 0f);
        }

        /** 用户点击切换，会触发监听 */
        public void toggle() {
            checked = !checked;
            animateTo(checked ? 1f : 0f);
            if (listener != null) listener.onCheckedChanged(this, checked);
        }

        private void animateTo(final float to) {
            if (anim != null) { anim.cancel(); anim = null; }
            try {
                anim = ValueAnimator.ofFloat(progress, to);
                anim.setDuration(150L);
                anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        Object v = a.getAnimatedValue();
                        if (v instanceof Float) { progress = (Float) v; invalidate(); }
                    }
                });
                anim.start();
            } catch (Throwable t) {
                progress = to;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = dp(getContext(), switchWidthDp);
            int h = dp(getContext(), switchHeightDp);
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h / 2f;

            int offColor = isDark(getContext()) ? 0xFF555555 : 0xFFCCCCCC;
            int onColor = primary;
            int cr = (int) (android.graphics.Color.red(offColor)
                    + (android.graphics.Color.red(onColor) - android.graphics.Color.red(offColor)) * progress);
            int cg = (int) (android.graphics.Color.green(offColor)
                    + (android.graphics.Color.green(onColor) - android.graphics.Color.green(offColor)) * progress);
            int cb = (int) (android.graphics.Color.blue(offColor)
                    + (android.graphics.Color.blue(onColor) - android.graphics.Color.blue(offColor)) * progress);
            trackPaint.setColor(android.graphics.Color.argb(255, cr, cg, cb));

            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, trackPaint);

            float thumbR = r - dp(getContext(), 2f);
            float cx = r + progress * (w - h);
            canvas.drawCircle(cx, r, thumbR, thumbPaint);
        }
    }
}
