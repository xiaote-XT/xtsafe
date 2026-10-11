package xiaote.AnQuan;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import xiaote.xtui.XtUi;

/**
 * 使用说明页面。
 *
 * 12 条说明，每条独立成一张 xtui 卡片（细描边 + 16dp 圆角 + 无阴影），
 * 条目标题走主题色、正文走 muted。文案取自 strings.xml 的 guide_section*。
 *
 * 骨架与返回钮都走 XtUi：root 由 XtUi.scroll 装进 ScrollView，
 * 不能再 addView；左上角圆形返回钮由 XtUi.attachBack 挂载。
 */
public class UsageGuideActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 界面模式分流：ui_mode=h5 时交给 H5 壳，原生界面不构建。默认 native。
        if ("h5".equals(getSharedPreferences("dot_config", MODE_PRIVATE)
                .getString("ui_mode", "native"))) {
            try {
                startActivity(new android.content.Intent(this, H5GuideActivity.class));
            } catch (Throwable ignored) {}
            finish();
            return;
        }

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, getString(R.string.guide_title)));
        XtUi.applyStatusBar(this);

        addSection(root, "1. " + getString(R.string.guide_section1_title), getString(R.string.guide_section1_body));
        addSection(root, "2. " + getString(R.string.guide_section2_title), getString(R.string.guide_section2_body));
        addSection(root, "3. " + getString(R.string.guide_section3_title), getString(R.string.guide_section3_body));
        addSection(root, "4. " + getString(R.string.guide_section4_title), getString(R.string.guide_section4_body));
        addSection(root, "5. " + getString(R.string.guide_section5_title), getString(R.string.guide_section5_body));
        addSection(root, "6. " + getString(R.string.guide_section6_title), getString(R.string.guide_section6_body));
        addSection(root, "7. " + getString(R.string.guide_section7_title), getString(R.string.guide_section7_body));
        addSection(root, "8. " + getString(R.string.guide_section8_title), getString(R.string.guide_section8_body));
        addSection(root, "9. " + getString(R.string.guide_section9_title), getString(R.string.guide_section9_body));
        addSection(root, "10. " + getString(R.string.guide_section10_title), getString(R.string.guide_section10_body));
        addSection(root, "11. " + getString(R.string.guide_section11_title), getString(R.string.guide_section11_body));
        addSection(root, "12. " + getString(R.string.guide_section12_title), getString(R.string.guide_section12_body));

        setContentView(scrollView);
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);
    }

    private void registerBackCallback() {
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                try {
                    getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                            new android.window.OnBackInvokedCallback() {
                                @Override
                                public void onBackInvoked() { finish(); }
                            });
                } catch (Exception ignored) {}
            }
        });
    }

    /** 每条说明独立成一张 xtui 卡片（细描边 + 中圆角 + 无阴影） */
    private void addSection(LinearLayout root, String title, String body) {
        LinearLayout card = XtUi.card(this);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16);
        tvTitle.setTextColor(XtUi.primary);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, XtUi.dp(this, 6));
        card.addView(tvTitle);

        TextView tvBody = new TextView(this);
        tvBody.setText(body);
        tvBody.setTextSize(13);
        tvBody.setTextColor(XtUi.colorMuted(this));
        tvBody.setLineSpacing(4, 1);
        card.addView(tvBody);

        root.addView(card);
    }
}
