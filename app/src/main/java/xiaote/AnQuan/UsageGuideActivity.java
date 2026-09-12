package xiaote.AnQuan;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 使用说明页面
 */
public class UsageGuideActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 30);

        TextView title = new TextView(this);
        title.setText(getString(R.string.guide_title));
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 20);
        root.addView(title);

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

        Button backBtn = new Button(this);
        backBtn.setText(getString(R.string.guide_back));
        backBtn.setTextSize(14);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(backBtn);

        scrollView.addView(root);
        setContentView(scrollView);
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
    }

    private void registerBackCallback() {
        getWindow().getDecorView().post(new java.lang.Runnable() {
            @Override
            public void run() {
                try {
                    getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                            new android.window.OnBackInvokedCallback() {
                                @Override
                                public void onBackInvoked() { finish(); }
                            });
                } catch (Exception e) {}
            }
        });
    }

    private void addSection(LinearLayout root, String title, String body) {
        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16);
        tvTitle.setTextColor(getTextColor());
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 16, 0, 4);
        root.addView(tvTitle);

        TextView tvBody = new TextView(this);
        tvBody.setText(body);
        tvBody.setTextSize(13);
        tvBody.setTextColor(getBodyTextColor());
        tvBody.setLineSpacing(4, 1);
        tvBody.setPadding(8, 0, 0, 8);
        root.addView(tvBody);
    }

    private int getTextColor() {
        return Color.argb(255, 30, 30, 30);
    }

    private int getBodyTextColor() {
        return Color.argb(220, 80, 80, 80);
    }
}
