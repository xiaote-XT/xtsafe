package xiaote.AnQuan;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 密码验证 Activity（独立页面，避免 Dialog 被取消）。
 *
 * 修整点：
 *   1. 「退出应用」补 setResult(RESULT_CANCELED)，避免父页面 isPasswordChecking
 *      一直停在 true；
 *   2. Android 13+ 显式注册 OnBackInvokedCallback，不再隐式依赖 onBackPressed；
 *   3. 验证通过只调用 BaseActivity.markSessionUnlocked()，不再写 unlock_until。
 *      之前写了 30 秒宽限窗口，BaseActivity 又优先看它，结果“第一次验证完，
 *      之后 30 秒内回到前台都不再弹密码”，表现为密码只出现一次。
 *      现在改为：退到后台（activityCount 归 0）即清会话标志，回来必定重新验证。
 *   4. 全屏主题 + setFinishOnTouchOutside(false)，点空白不会误关本页。
 */
public class PasswordLockActivity extends Activity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(40, 40, 40, 40);
        root.setBackgroundColor(Color.argb(255, 245, 245, 250));

        TextView title = new TextView(this);
        title.setText(R.string.password_prompt);
        title.setTextSize(22);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 30);
        root.addView(title);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.password_hint);
        input.setHintTextColor(Color.argb(150, 120, 120, 120));
        input.setTextColor(getTextColor());
        root.addView(input);

        Button btnOk = new Button(this);
        btnOk.setText(R.string.confirm_ok);
        btnOk.setTextSize(16);
        btnOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pwd = input.getText().toString().trim();
                String saved = prefs.getString("app_password_hash", "");
                if (pwd.isEmpty()) {
                    Toast.makeText(PasswordLockActivity.this, getString(R.string.password_prompt), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (hashPassword(pwd).equals(saved)) {
                    // 只标记会话已解锁：退到后台时 BaseActivity 会重置该标志，
                    // 下次回到前台必定重新弹密码页。
                    try { BaseActivity.markSessionUnlocked(); } catch (Throwable ignored) {}
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Toast.makeText(PasswordLockActivity.this, R.string.password_wrong_short, Toast.LENGTH_SHORT).show();
                    input.setText("");
                }
            }
        });
        root.addView(btnOk);

        Button btnExit = new Button(this);
        btnExit.setText(R.string.exit_app);
        btnExit.setTextSize(14);
        btnExit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 补 CANCELED：父页面 onActivityResult 才能正确复位 isPasswordChecking
                setResult(RESULT_CANCELED);
                // 回到桌面（应用退到后台，不销毁 Activity 栈）
                moveTaskToBack(true);
            }
        });
        root.addView(btnExit);

        setContentView(root);
        // 已改为全屏主题（无 Dialog 外部区域），点空白不会再触发 ACTION_OUTSIDE，
        // 这里再保险调一次 setFinishOnTouchOutside(false)，对非 Dialog 窗口无副作用。
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

    private int getTextColor() {
        return Color.argb(255, 30, 30, 30);
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
