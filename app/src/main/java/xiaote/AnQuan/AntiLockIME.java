package xiaote.AnQuan;

import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 应急键盘（锁机时的紧急方案）
 * 原生样式控件 + 深色背景；两个紧急操作（关闭所有无障碍 / 强制停止所有应用），
 * 二次确认在键盘内完成（切换输入视图，不依赖系统弹窗）
 */
public class AntiLockIME extends InputMethodService {

    private EmergencyManager emergencyManager;
    private View mainView;
    private static final int BG_COLOR = Color.argb(240, 20, 20, 30);

    @Override
    public View onCreateInputView() {
        emergencyManager = new EmergencyManager(this, new Handler(Looper.getMainLooper()));
        mainView = buildMainView();
        return mainView;
    }

    /** 主视图：标题 + 提示 + 两个紧急操作按钮（原生控件样式） */
    private LinearLayout buildMainView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(40, 30, 40, 30);
        layout.setBackgroundColor(BG_COLOR);

        TextView title = new TextView(this);
        String appName;
        try {
            appName = getString(R.string.app_name);
        } catch (Exception e) {
            appName = "XTsafe";
        }
        title.setText(appName + getString(R.string.ime_name_suffix));
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView hint = new TextView(this);
        hint.setText(R.string.ime_emergency_tip);
        hint.setTextSize(14);
        hint.setTextColor(Color.argb(180, 200, 200, 200));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 12, 0, 24);
        layout.addView(hint);

        Button btnDisableAcc = new Button(this);
        btnDisableAcc.setText(R.string.ime_action_disable_acc);
        btnDisableAcc.setTextSize(16);
        btnDisableAcc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfirmView(getString(R.string.ime_action_disable_acc),
                        new Runnable() {
                            @Override
                            public void run() {
                                emergencyManager.disableAllAccessibility();
                            }
                        });
            }
        });
        layout.addView(btnDisableAcc);

        Button btnStopAll = new Button(this);
        btnStopAll.setText(R.string.ime_action_stop_all);
        btnStopAll.setTextSize(16);
        btnStopAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfirmView(getString(R.string.ime_action_stop_all),
                        new Runnable() {
                            @Override
                            public void run() {
                                emergencyManager.forceStopAll();
                            }
                        });
            }
        });
        layout.addView(btnStopAll);

        return layout;
    }

    /** 确认视图：在键盘内完成二次确认（不依赖系统弹窗） */
    private void showConfirmView(final String actionName, final Runnable action) {
        LinearLayout cv = new LinearLayout(this);
        cv.setOrientation(LinearLayout.VERTICAL);
        cv.setGravity(Gravity.CENTER);
        cv.setPadding(40, 30, 40, 30);
        cv.setBackgroundColor(BG_COLOR);

        TextView title = new TextView(this);
        title.setText(R.string.double_confirm_title);
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 12);
        cv.addView(title);

        TextView msg = new TextView(this);
        msg.setText(getString(R.string.double_confirm_msg, actionName));
        msg.setTextSize(14);
        msg.setTextColor(Color.argb(220, 220, 220, 220));
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, 0, 0, 24);
        cv.addView(msg);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button cancel = new Button(this);
        cancel.setText(R.string.cancel);
        cancel.setTextSize(14);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMainView();
            }
        });
        btnRow.addView(cancel);

        TextView spacer = new TextView(this);
        spacer.setWidth(dpToPx(20));
        btnRow.addView(spacer);

        Button ok = new Button(this);
        ok.setText(R.string.confirm_ok);
        ok.setTextSize(14);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
                showMainView();
            }
        });
        btnRow.addView(ok);
        cv.addView(btnRow);

        setInputView(cv);
    }

    /** 返回主视图 */
    private void showMainView() {
        if (mainView != null) {
            setInputView(mainView);
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
