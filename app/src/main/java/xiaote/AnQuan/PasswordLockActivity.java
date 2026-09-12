package xiaote.AnQuan;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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
 * 密码验证 Activity（独立页面，避免 Dialog 被取消）
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
                    // 验证通过，设置 5 秒解锁窗口
                    prefs.edit().putLong("unlock_until", System.currentTimeMillis() + 5000).apply();
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
                // 清除解锁窗口，确保下次启动需要验证密码
                prefs.edit().remove("unlock_until").apply();
                // 回到桌面（应用退到后台，不销毁 Activity 栈）
                moveTaskToBack(true);
            }
        });
        root.addView(btnExit);

        setContentView(root);
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