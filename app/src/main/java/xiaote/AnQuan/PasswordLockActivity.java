package xiaote.AnQuan;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import xiaote.xtui.XtDialog;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * 密码验证 Activity（独立页面，避免 Dialog 被取消）。
 *
 * 已按 xtui 规范重写：骨架走 XtUi.column，标题 XtUi.title，
 * 输入框 XtDialog.input（细描边 + 中圆角 + 左右不贴边），
 * 主按钮 XtUi.primaryButton，次按钮 XtUi.button，提示走 XtToast。
 *
 * 行为约定：
 *   1. 「退出应用」补 setResult(RESULT_CANCELED)，避免父页面 isPasswordChecking
 *      一直停在 true；
 *   2. Android 13+ 显式注册 OnBackInvokedCallback；
 *   3. 验证通过只调 BaseActivity.markSessionUnlocked()，不写 unlock_until，
 *      退到后台（activityCount 归 0）即清会话标志，回来必定重新验证；
 *   4. 全屏主题 + setFinishOnTouchOutside(false)，点空白不会误关本页。
 */
public class PasswordLockActivity extends Activity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        LinearLayout root = XtUi.column(this);
        root.setGravity(Gravity.CENTER);
        root.setPadding(XtUi.dp(this, 28), XtUi.dp(this, 28),
                XtUi.dp(this, 28), XtUi.dp(this, 28));
        XtUi.applyStatusBar(this);

        TextView title = XtUi.title(this, getString(R.string.password_prompt));
        title.setPadding(0, 0, 0, XtUi.dp(this, 24));
        root.addView(title);

        final EditText input = XtDialog.input(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.password_hint);
        input.setSingleLine(true);
        root.addView(input);

        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(-1, -2);
        okLp.topMargin = XtUi.dp(this, 20);
        root.addView(XtUi.primaryButton(this, getString(R.string.confirm_ok),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String pwd = input.getText().toString().trim();
                        String saved = prefs.getString("app_password_hash", "");
                        if (pwd.isEmpty()) {
                            XtToast.show(PasswordLockActivity.this, getString(R.string.password_prompt));
                            return;
                        }
                        if (hashPassword(pwd).equals(saved)) {
                            // 只标记会话已解锁：退到后台时 BaseActivity 会重置该标志，
                            // 下次回到前台必定重新弹密码页。
                            try { BaseActivity.markSessionUnlocked(); } catch (Throwable ignored) {}
                            setResult(RESULT_OK);
                            finish();
                        } else {
                            XtToast.show(PasswordLockActivity.this, R.string.password_wrong_short);
                            input.setText("");
                        }
                    }
                }), okLp);

        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(-1, -2);
        exitLp.topMargin = XtUi.dp(this, 10);
        root.addView(XtUi.button(this, getString(R.string.exit_app), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 补 CANCELED：父页面 onActivityResult 才能正确复位 isPasswordChecking
                setResult(RESULT_CANCELED);
                // 回到桌面（应用退到后台，不销毁 Activity 栈）
                moveTaskToBack(true);
            }
        }), exitLp);

        setContentView(root);
        try { setFinishOnTouchOutside(false); } catch (Throwable ignored) {}
        // 阻止输入框自动获取焦点弹出键盘（用户点一下才唤起）
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
    }

    /** Android 13+ 显式注册返回回调，等价于 onBackPressed 的退出语义 */
    private void registerBackCallback() {
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                try {
                    getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                            new android.window.OnBackInvokedCallback() {
                                @Override
                                public void onBackInvoked() {
                                    setResult(RESULT_CANCELED);
                                    finishAffinity();
                                }
                            });
                } catch (Exception ignored) {}
            }
        });
    }

    private String hashPassword(String password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onBackPressed() {
        // 按返回键视为退出应用
        setResult(RESULT_CANCELED);
        finishAffinity();
    }
}
