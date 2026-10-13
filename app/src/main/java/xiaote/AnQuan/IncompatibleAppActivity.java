package xiaote.AnQuan;

import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * 不兼容应用检测。
 *
 * 目前只是入口骨架：页面结构、返回钮、说明文案就位，
 * 具体检测逻辑与结果展示待后续补充。
 *
 * 用途预期：找出与本设备/本应用存在冲突的应用
 * （例如与本应用争抢无障碍、注入系统进程、拦截卸载、
 * 与核心防护功能互斥的同类安全软件等），给出可操作的处置建议。
 *
 * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟。
 */
public class IncompatibleAppActivity extends BaseActivity {

    /** 首次使用弹窗「不再提示」偏好键 */
    public static final String KEY_NOTICE_OFF = "incompatible_notice_off";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, "不兼容应用检测"));
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this,
                "扫描本机应用，找出与星特安全存在冲突的应用，"
                        + "例如争抢无障碍、注入系统进程、拦截卸载，"
                        + "或与核心防护功能互斥的同类安全软件。");
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, XtUi.dp(this, 16));
        root.addView(tip);

        // ============ 状态 ============
        LinearLayout cardStatus = XtUi.card(this);

        TextView statusView = new TextView(this);
        statusView.setTextSize(15f);
        statusView.setTextColor(XtUi.colorMuted(this));
        statusView.setGravity(Gravity.CENTER);
        statusView.setText("尚未开始检测");
        cardStatus.addView(statusView);

        TextView statusTip = XtUi.tip(this, "检测逻辑待补充，当前仅为入口");
        statusTip.setGravity(Gravity.CENTER);
        statusTip.setPadding(0, XtUi.dp(this, 8), 0, 0);
        cardStatus.addView(statusTip);

        root.addView(cardStatus);

        // ============ 操作 ============
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-1, -2);
        root.addView(XtUi.primaryButton(this, "开始检测", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TODO 检测逻辑待补
                XtToast.showLong(IncompatibleAppActivity.this, "检测逻辑尚未实现");
            }
        }), startLp);

        // ============ 说明 ============
        LinearLayout cardNote = XtUi.cardWithSection(this, "说明");
        TextView note = XtUi.tip(this,
                "1. 检测结果仅供排查参考，不代表应用存在恶意行为；\n"
                        + "2. 处置前请确认目标应用确属冲突来源，误操作由使用者自行承担；\n"
                        + "3. 功能细节待补充。");
        note.setLineSpacing(0, 1.4f);
        cardNote.addView(note);
        root.addView(cardNote);

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
