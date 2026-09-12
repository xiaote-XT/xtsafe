package xiaote.AnQuan;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * 所有 Activity 的基类，实现后台返回密码锁
 */
public class BaseActivity extends Activity {

    private static final int REQUEST_PASSWORD = 1000;
    private static int activityCount = 0; // 前台 Activity 数量
    public static void resetActivityCount() { activityCount = 0; }
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从后台回到前台（activityCount 从 0 变为 1）时才需要密码验证
        if (activityCount == 1 && !isPasswordChecking) {
            if (prefs.contains("app_password_hash")) {
                long unlockUntil = prefs.getLong("unlock_until", 0);
                if (System.currentTimeMillis() < unlockUntil) return;
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