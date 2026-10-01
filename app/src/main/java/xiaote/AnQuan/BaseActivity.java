package xiaote.AnQuan;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * 所有 Activity 的基类，实现后台返回密码锁。
 *
 * 解锁判定改为「会话标志 + 宽限窗口」双保险：
 *   · sessionUnlocked：本次前台停留期间已验证过密码，Activity 之间切换、
 *     从密码页返回都不会再次弹锁；
 *   · 全部页面退到后台（activityCount 归 0）时重置 sessionUnlocked，
 *     下次回到前台重新验证。
 *   · unlock_until 仍保留，作为后台短暂切走再回来的宽限窗口。
 */
public class BaseActivity extends Activity {

    private static final int REQUEST_PASSWORD = 1000;
    private static int activityCount = 0; // 前台 Activity 数量
    /** 会话解锁标志：本次前台停留期间已验证过密码 */
    private static boolean sessionUnlocked = false;

    public static void resetActivityCount() {
        activityCount = 0;
        sessionUnlocked = false;
    }

    /** 密码验证通过后调用，标记本次前台会话已解锁 */
    public static void markSessionUnlocked() {
        sessionUnlocked = true;
    }

    private SharedPreferences prefs;
    private boolean isPasswordChecking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityCount++;
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityCount--;
        // 全部页面退到后台：重置会话解锁，下次回到前台重新验证
        if (activityCount <= 0) {
            activityCount = 0;
            sessionUnlocked = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从后台回到前台（activityCount 从 0 变为 1）时才需要密码验证
        if (activityCount == 1 && !isPasswordChecking) {
            if (prefs.contains("app_password_hash")) {
                if (sessionUnlocked) return;
                // 不再使用 unlock_until 宽限窗口：只要应用整体退到后台再回来
                // （activityCount 归 0 已重置 sessionUnlocked），就必须重新验证密码。
                isPasswordChecking = true;
                Intent intent = new Intent(this, PasswordLockActivity.class);
                startActivityForResult(intent, REQUEST_PASSWORD);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PASSWORD) {
            isPasswordChecking = false;
            if (resultCode == RESULT_OK) {
                sessionUnlocked = true;
                Intent callingIntent = getIntent();
                if (callingIntent != null
                        && callingIntent.getBooleanExtra("show_password_verify", false)) {
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(R.string.shizuku_request_title)
                            .setMessage(R.string.shizuku_request_msg)
                            .setPositiveButton(getString(R.string.allow_btn), new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    SharedPreferences.Editor ed = prefs.edit();
                                    ed.putLong("shizuku_unlock_until",
                                            System.currentTimeMillis() + 60000);
                                    ed.apply();
                                    android.widget.Toast.makeText(BaseActivity.this,
                                            getString(R.string.shizuku_allowed),
                                            android.widget.Toast.LENGTH_LONG).show();
                                }
                            })
                            .setNegativeButton(getString(R.string.deny_btn), null)
                            .setCancelable(false)
                            .show();
                }
            } else {
                finishAffinity();
            }
        }
    }
}
