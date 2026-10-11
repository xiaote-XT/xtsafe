package xiaote.AnQuan;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import xiaote.xtui.XtUi;

/**
 * 更多小工具。
 *
 * 主页功能区分流出来的独立工具入口聚合在这里，避免主页按钮越堆越长：
 *   1. 无障碍管理
 *   2. 广告拦截
 *   3. 进程管理
 *
 * 与主页同一套 xtui 规范：细描边卡片 + 中圆角 + 无阴影，深浅色自动跟随。
 */
public class MoreToolsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, "更多小工具"));
        XtUi.applyStatusBar(this);

        LinearLayout card = XtUi.card(this);

        XtUi.addButton(card, this, "无障碍管理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MoreToolsActivity.this, AccessibilityManagerActivity.class));
            }
        });

        XtUi.addButton(card, this, "广告拦截", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MoreToolsActivity.this,
                        xiaote.AnQuan.SmallTool.KillAd.KillAdActivity.class));
            }
        });

        XtUi.addButton(card, this, "进程管理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MoreToolsActivity.this,
                        xiaote.AnQuan.SmallTool.ProcessManager.ProcessManagerActivity.class));
            }
        });

        root.addView(card);

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
}
