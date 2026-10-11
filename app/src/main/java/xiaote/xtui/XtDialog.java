package xiaote.xtui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * xtui 弹窗构造器（零 R 引用，可整体搬到其他项目）。
 *
 * 规范：
 *   深色 bg #1e1e1e / 标题白 / 内容 #d9d9d9；
 *   浅色 bg #ffffff / 标题黑 / 内容 #5e5e5e；提示 #888888；按钮统一主题色。
 *   tint() 给整窗铺一层圆角底板，避免按钮飞出圆角之外。
 *
 * 关于 android.R 索引：
 *   部分构建环境（AIDE）对 android.R.id 索引不全，直接写 android.R.id.alertTitle
 *   会报“未知成员”。这里统一用 getIdentifier 按名字查，绕开该问题。
 */
public final class XtDialog {

    public static int titleLight = 0xFF1E1E1E;
    public static int titleDark = 0xFFFFFFFF;
    public static int contentLight = 0xFF5E5E5E;
    public static int contentDark = 0xFFD9D9D9;
    public static int hintColor = 0xFF888888;

    public static float radiusDp = 16f;
    public static float padDp = 20f;
    public static float animFrom = 0.9f;
    public static long animMs = 180L;

    private XtDialog() {}

    /* ==================== 取色 ==================== */

    public static int colorTitle(Context c) {
        return XtUi.isDark(c) ? titleDark : titleLight;
    }

    public static int colorContent(Context c) {
        return XtUi.isDark(c) ? contentDark : contentLight;
    }

    /* ==================== Builder ==================== */

    /** 普通双按钮弹窗构造器 */
    public static AlertDialog.Builder builder(Context c, CharSequence title, CharSequence msg) {
        AlertDialog.Builder b = new AlertDialog.Builder(c);
        if (title != null && title.length() > 0) b.setTitle(title);
        if (msg != null && msg.length() > 0) b.setMessage(msg);
        return b;
    }

    /** 带输入框的弹窗构造器 */
    public static AlertDialog.Builder inputBuilder(Context c, CharSequence title,
                                                   CharSequence msg, View input) {
        AlertDialog.Builder b = new AlertDialog.Builder(c);
        if (title != null && title.length() > 0) b.setTitle(title);
        if (msg != null && msg.length() > 0) b.setMessage(msg);
        if (input != null) b.setView(input);
        return b;
    }

    /**
     * 单输入框：细描边 + 中圆角，配色走 xtui。
     *
     * 刻意不设任何外边距。
     *
     * 早期版本在这里塞过 20dp 左右 margin 想在弹窗里防贴边，副作用是：
     *   · 直接把输入框 addView 进容器的页面（广告拦截等），输入框比同页按钮窄一圈，
     *     看起来「长度和按钮不一样」；
     *   · 塞进横向 row 与按钮并排时（选择应用的包名行、Shizuku 的 TID 行），
     *     两侧 margin 与按钮挤在一起，表现为「输入框和按钮贴一起」。
     *
     * 防贴边改由弹窗侧解决：tint() 里的 applyCustomPadding 给 android.R.id.custom
     * 容器补内边距，弹窗外层本身也已有 24dp 左右的系统留白。
     * 输入框自身只保留 12dp 内边距，宽度完全交给父容器决定，
     * 这样放进任何布局都能自动对齐。
     */
    public static EditText input(Context c) {
        EditText et = new EditText(c);
        et.setTextSize(14f);
        et.setTextColor(XtUi.colorText(c));
        et.setHintTextColor(hintColor);
        et.setSingleLine(false);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(XtUi.dp(c, radiusDp));
        // 用页面底色而不是卡色：输入框多贴在卡色容器上，同色会看不出边界
        d.setColor(XtUi.colorBg(c));
        int w = XtUi.dp(c, XtUi.borderDp);
        if (w < 1) w = 1;
        d.setStroke(w, XtUi.colorBorder(c));
        et.setBackground(d);
        int p = XtUi.dp(c, 12);
        et.setPadding(p, p, p, p);
        // 给一个最小高度，保证与同排按钮高度接近、不会显得塌
        et.setMinHeight(XtUi.dp(c, 44));
        return et;
    }

    /** 内容容器：需要自定义内容时用 */
    public static LinearLayout content(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = XtUi.dp(c, padDp);
        box.setPadding(p, p, p, p);
        return box;
    }

    /* ==================== 显示 ==================== */

    /** 简易弹窗：标题 + 内容 + 单个确定按钮 */
    public static AlertDialog show(Context c, CharSequence title, CharSequence msg) {
        return show(c, title, msg, null, null);
    }

    /** 完整弹窗：可指定确定按钮与监听 */
    public static AlertDialog show(Context c, CharSequence title, CharSequence msg,
                                   CharSequence positive, DialogInterface.OnClickListener onOk) {
        return show(c, title, msg, positive, onOk, null, null);
    }

    /** 双按钮弹窗 */
    public static AlertDialog show(Context c, CharSequence title, CharSequence msg,
                                   CharSequence positive, DialogInterface.OnClickListener onOk,
                                   CharSequence negative, DialogInterface.OnClickListener onCancel) {
        if (c == null) return null;
        AlertDialog d = builder(c, title, msg)
                .setPositiveButton(positive == null ? "确定" : positive, onOk)
                .setNegativeButton(negative, onCancel)
                .create();
        d.show();
        tint(d);
        return d;
    }

    /** 可指定 cancelable 的简易弹窗 */
    public static AlertDialog show(Context c, CharSequence title, CharSequence msg, boolean cancelable) {
        AlertDialog d = builder(c, title, msg)
                .setPositiveButton("确定", null)
                .create();
        d.setCancelable(cancelable);
        d.show();
        tint(d);
        return d;
    }

    /* ==================== 整窗染色 ==================== */

    /**
     * 给弹窗整窗铺一层圆角底板。
     *
     * 系统 AlertDialog 的圆角只在窗口背景上，按钮区域会溢出到方角；
     * 这里把 window 背景置透明，再给根布局铺圆角卡色底 + 细描边，
     * 视觉上就是一个完整的 xtui 卡片。
     */
    public static void tint(final AlertDialog d) {
        if (d == null) return;
        try {
            Context c = d.getContext();
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0x00000000));
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.9f);
                w.setAttributes(lp);
            }

            // 弹窗底板：卡色 + 中圆角，按需求不加细边框
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(XtUi.dp(c, radiusDp));
            bg.setColor(XtUi.colorCard(c));

            View decor = w != null ? w.getDecorView() : null;
            if (decor instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) decor;
                if (g.getChildCount() > 0) {
                    View child = g.getChildAt(0);
                    child.setBackground(bg);
                }
            }
            // setView 传入的内容挂在 android.R.id.custom 下，部分 ROM 该容器左右 padding 为 0，
            // 会让输入框紧贴弹窗边缘；这里补一个最小 20dp 的左右内边距。
            applyCustomPadding(decor, c);
            /*
             * 标题/内容/按钮的染色放到 post 里再跑一次。
             *
             * show() 返回时 decorView 的内容有时还没完全 attach（部分 ROM 上
             * findViewById 会拿到 null），只染一次会漏掉标题，表现就是
             * 「弹窗标题在深色模式下还是黑的」。post 到下一帧再染一遍，两次都染不到才真算没有。
             */
            tintSystemText(d);
            tintDialogList(d);
            View decorView = w != null ? w.getDecorView() : null;
            if (decorView != null) {
                decorView.post(new Runnable() {
                    @Override
                    public void run() {
                        tintSystemText(d);
                        tintDialogList(d);
                    }
                });
            }
            animateScale(d);
        } catch (Throwable ignored) {
        }
    }

    /** 系统标题/内容/按钮染色 */
    public static void tintSystemText(AlertDialog d) {
        if (d == null) return;
        try {
            Context c = d.getContext();
            TextView t = (TextView) d.findViewById(androidId("alertTitle"));
            if (t != null) {
                t.setTextColor(colorTitle(c));
                t.setTextSize(17f);
            }
            TextView m = (TextView) d.findViewById(androidId("message"));
            if (m != null) {
                m.setTextColor(colorContent(c));
                m.setTextSize(14f);
                m.setLineSpacing(0, 1.25f);
            }
            int okId = androidId("button1");
            int noId = androidId("button2");
            int midId = androidId("button3");
            TextView ok = (TextView) d.findViewById(okId);
            if (ok != null) ok.setTextColor(XtUi.primary);
            TextView no = (TextView) d.findViewById(noId);
            if (no != null) no.setTextColor(XtUi.primary);
            TextView mid = (TextView) d.findViewById(midId);
            if (mid != null) mid.setTextColor(XtUi.primary);

            /*
             * 兜底遍历。
             *
             * 部分 ROM 的 AlertDialog 布局里标题/内容的 id 名字与 AOSP 不一致，
             * 上面按 alertTitle / message 精确匹配会全部落空，表现就是
             * 「标题在深色模式下还是黑的」。
             *
             * 这里再扫一遍 decorView：
             *   · 跳过按钮面板（按钮色已单独设成主题色，不能被覆盖）；
             *   · 字号 >= 16sp 的 TextView 当标题，其余当内容。
             * 规则朴素，但对系统弹窗的实际字号分布足够稳。
             */
            View decor = d.getWindow() != null ? d.getWindow().getDecorView() : null;
            if (decor instanceof ViewGroup) {
                fallbackTintTexts((ViewGroup) decor, c);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 给 setView 的内容容器补左右内边距，避免输入框紧贴弹窗边缘 */
    private static void applyCustomPadding(View decor, Context c) {
        if (decor == null) return;
        try {
            int id = androidId("custom");
            if (id == 0) return;
            View custom = decor.findViewById(id);
            if (custom == null) return;
            int min = XtUi.dp(c, 20);
            int pl = custom.getPaddingLeft();
            int pr = custom.getPaddingRight();
            if (pl < min || pr < min) {
                custom.setPadding(Math.max(pl, min), custom.getPaddingTop(),
                        Math.max(pr, min), custom.getPaddingBottom());
            }
        } catch (Throwable ignored) {}
    }

    /** 兜底染色：跳过按钮面板与按钮，其余 TextView 按字号区分标题与内容 */
    private static void fallbackTintTexts(ViewGroup g, Context c) {
        if (g == null) return;
        int panelId = androidId("buttonPanel");
        int b1 = androidId("button1");
        int b2 = androidId("button2");
        int b3 = androidId("button3");
        try {
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = g.getChildAt(i);
                int vid = v.getId();
                if (panelId != 0 && vid == panelId) continue;
                /*
                 * 按钮必须跳过。
                 *
                 * tintSystemText 已把 button1/2/3 单独染成主题色 #ff6a00；
                 * 而系统弹窗的按钮在部分 ROM 上是普通 TextView 而非 Button，
                 * 只判断 Button 类型会漏，按钮随后被当成正文覆盖成内容色 ——
                 * 表现就是「按钮不是主题色」。这里按 id 再挡一道。
                 */
                if (v instanceof android.widget.Button) continue;
                if (b1 != 0 && vid == b1) continue;
                if (b2 != 0 && vid == b2) continue;
                if (b3 != 0 && vid == b3) continue;
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    float sp = tv.getTextSize() / c.getResources().getDisplayMetrics().scaledDensity;
                    tv.setTextColor(sp >= 16f ? colorTitle(c) : colorContent(c));
                }
                if (v instanceof ViewGroup) fallbackTintTexts((ViewGroup) v, c);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 弹窗出现时的缩放动画：0.9 → 1.0，180ms 减速 */
    public static void animateScale(AlertDialog d) {
        if (d == null) return;
        try {
            Window w = d.getWindow();
            View decor = w != null ? w.getDecorView() : null;
            if (decor == null) return;
            decor.setScaleX(animFrom);
            decor.setScaleY(animFrom);
            decor.animate().scaleX(1f).scaleY(1f)
                    .setDuration(animMs)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } catch (Throwable ignored) {
        }
    }

    /** 按名字查 android.R.id，绕开 AIDE 对 android.R 索引不全的问题 */
    private static int androidId(String name) {
        try {
            return android.R.id.class.getField(name).getInt(null);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 单选 / 多选列表染色。
     *
     * setSingleChoiceItems / setMultiChoiceItems 生成的 ListView 用系统默认配色：
     * 深色模式下条目文字仍是深色、分隔线是浅色，在卡色底上几乎看不清。
     * 这里把列表背景置透明、分隔线换成 xtui 边框色，并把当前已显示的子项逐个染色。
     *
     * 滚动后新回收复用的子项也要染，所以额外挂 OnScrollListener；
     * 再用 post 补一次，覆盖首帧还没测量完的情况。
     * tintListItem 内部会分别处理 CheckedTextView / CheckBox / TextView，
     * 单选多选这两种系统布局都能覆盖。
     */
    private static void tintDialogList(final AlertDialog d) {
        if (d == null) return;
        try {
            final Context c = d.getContext();
            final ListView lv = d.getListView();
            if (lv == null) return;
            lv.setBackgroundColor(0x00000000);
            lv.setCacheColorHint(0x00000000);
            lv.setDivider(new ColorDrawable(XtUi.colorBorder(c)));
            lv.setDividerHeight(Math.max(1, XtUi.dp(c, XtUi.borderDp)));
            tintListChildren(lv);
            lv.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(android.widget.AbsListView v, int state) {}
                @Override public void onScroll(android.widget.AbsListView v, int first, int visible,
                                               int total) {
                    tintListChildren(lv);
                }
            });
            lv.post(new Runnable() {
                @Override public void run() { tintListChildren(lv); }
            });
        } catch (Throwable ignored) {}
    }

    /** 递归给列表的可见子项染色（系统条目可能是单 TextView，也可能是包了一层的容器） */
    private static void tintListChildren(ListView lv) {
        if (lv == null) return;
        try {
            for (int i = 0; i < lv.getChildCount(); i++) {
                tintListItemRecursive(lv.getChildAt(i));
            }
        } catch (Throwable ignored) {}
    }

    private static void tintListItemRecursive(View v) {
        if (v == null) return;
        tintListItem(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                tintListItemRecursive(g.getChildAt(i));
            }
        }
    }

    /* ==================== 控件染色 ==================== */

    /** CheckBox 染主题色（含三重保险） */
    public static void tintCheckBoxes(final View root) {
        if (root == null) return;
        try {
            applyCheckBoxTint(root);
            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                g.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                    @Override public void onChildViewAdded(View parent, View child) {
                        applyCheckBoxTint(child);
                    }
                    @Override public void onChildViewRemoved(View parent, View child) {}
                });
            }
            root.post(new Runnable() {
                @Override
                public void run() {
                    // 弹窗内容 inflate 完成后可能还有新子 View，再补一遍
                    tintCheckBoxesRecursive(root);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void tintCheckBoxesRecursive(View v) {
        if (v == null) return;
        applyCheckBoxTint(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                tintCheckBoxesRecursive(g.getChildAt(i));
            }
        }
    }

    private static void applyCheckBoxTint(View v) {
        if (!(v instanceof CheckBox)) return;
        CheckBox cb = (CheckBox) v;
        try {
            android.content.res.ColorStateList cs =
                    android.content.res.ColorStateList.valueOf(XtUi.primary);
            if (Build.VERSION.SDK_INT >= 21) cb.setButtonTintList(cs);
        } catch (Throwable ignored) {}
        cb.setTextColor(XtUi.colorText(cb.getContext()));
    }

    /**
     * 单选/多选列表染色：
     *   CheckedTextView 染文字 + checkMarkTintList，CheckBox 染主题色，
     *   普通 TextView 用正常文字色，列表底透明、分隔线走 xtui 边框色。
     */
    public static void tintListItems(ListView lv) {
        if (lv == null) return;
        try {
            Context c = lv.getContext();
            lv.setBackgroundColor(0x00000000);
            lv.setDivider(new ColorDrawable(XtUi.colorBorder(c)));
            lv.setDividerHeight(Math.max(1, XtUi.dp(c, XtUi.borderDp)));
        } catch (Throwable ignored) {}
    }

    /** 对单个列表项视图染色（在 Adapter.getView 里调） */
    public static void tintListItem(View v) {
        if (v == null) return;
        Context c = v.getContext();
        try {
            if (v instanceof CheckedTextView) {
                CheckedTextView ctv = (CheckedTextView) v;
                ctv.setTextColor(XtUi.colorText(c));
                if (Build.VERSION.SDK_INT >= 21) {
                    ctv.setCheckMarkTintList(
                            android.content.res.ColorStateList.valueOf(XtUi.primary));
                }
            } else if (v instanceof CheckBox) {
                applyCheckBoxTint(v);
            } else if (v instanceof TextView) {
                ((TextView) v).setTextColor(XtUi.colorText(c));
            }
        } catch (Throwable ignored) {}
    }
}
