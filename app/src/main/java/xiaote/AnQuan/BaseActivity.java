package xiaote.AnQuan;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import xiaote.AnQuan.Setting.SystemAuthHelper;

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
    /** 系统验证（锁屏凭据）请求码 */
    private static final int REQUEST_SYSTEM_AUTH = 1001;
    private static int activityCount = 0; // 前台 Activity 数量
    /** 会话解锁标志：本次前台停留期间已验证过密码 */
    private static boolean sessionUnlocked = false;
    /**
     * 系统验证通过后的临时解锁保护截止时刻（毫秒）。
     *
     * 系统验证页属于另一个进程的 Activity，跳过去再回来会经历一次
     * onStop → onStart，activityCount 瞬间归零，sessionUnlocked 会被重置，
     * 结果刚验证完立刻又被拦一次。
     *
     * 这里给 1 秒保护窗口：验证通过的瞬间写入截止时间，
     * onStop 时若仍在窗口内就不清会话标志；1 秒后窗口自然失效，
     * 之后再退到后台照样要重新验证。
     */
    private static volatile long tempUnlockUntil = 0L;

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
            // 系统验证返回瞬间的 onStop 不清标志，避免刚验证完又被拦一次
            if (System.currentTimeMillis() >= tempUnlockUntil) {
                sessionUnlocked = false;
            }
        }
    }

    /**
     * 本页是否参与密码锁。默认 true。
     *
     * 原生里授权 / 系统跳转类页面（Shizuku、Dhizuku、Device Owner、安全扫描、
     * 规则管理、广告拦截、进程管理、权限增强、更新日志）刻意继承 Activity
     * 而不继承 BaseActivity，避免授权过程中弹密码页打断流程。
     * H5 壳统一继承 H5BaseActivity（即本类），由 H5BaseActivity 覆写本方法
     * 返回 false，从而在不改变继承关系的前提下跳过密码校验。
     */
    protected boolean isLockEnabled() {
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 不参与密码锁的页面直接跳过
        if (!isLockEnabled()) return;
        // 从后台回到前台（activityCount 从 0 变为 1）时才需要密码验证
        if (activityCount == 1 && !isPasswordChecking) {
            if (sessionUnlocked) return;
            // 刚通过系统验证的 1 秒保护期内直接放行，不再重复弹
            if (System.currentTimeMillis() < tempUnlockUntil) return;
            isPasswordChecking = true;
            if (SystemAuthHelper.isSystemMode(this)) {
                /*
                 * 系统验证模式：直接调系统锁屏凭据（PIN / 图案 / 密码 / 指纹）。
                 * 设备若在设置页选完系统验证后又把锁屏删了，buildIntent 会返回 null，
                 * 这时不能把用户永远挡在外面 —— 退回数字密码逻辑；
                 * 数字密码也没设过就当作未启用密码锁，直接放行。
                 */
                Intent sys = SystemAuthHelper.buildIntent(this);
                if (sys != null) {
                    startActivityForResult(sys, REQUEST_SYSTEM_AUTH);
                    return;
                }
                if (!SystemAuthHelper.hasNumericPassword(this)) {
                    isPasswordChecking = false;
                    return;
                }
                startActivityForResult(new Intent(this, PasswordLockActivity.class), REQUEST_PASSWORD);
                return;
            }
            // 数字密码模式：没设过哈希就当未启用
            if (!SystemAuthHelper.hasNumericPassword(this)) {
                isPasswordChecking = false;
                return;
            }
            // 不再使用 unlock_until 宽限窗口：只要应用整体退到后台再回来
            // （activityCount 归 0 已重置 sessionUnlocked），就必须重新验证密码。
            startActivityForResult(new Intent(this, PasswordLockActivity.class), REQUEST_PASSWORD);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SYSTEM_AUTH) {
            isPasswordChecking = false;
            if (resultCode == RESULT_OK) {
                sessionUnlocked = true;
                // 1 秒临时状态：覆盖系统验证页返回时的 onStop 抖动，之后再退后台照常验证
                tempUnlockUntil = System.currentTimeMillis() + 1000L;
            } else {
                // 用户取消或验证失败：退出应用，避免绕过密码锁
                finishAffinity();
            }
            return;
        }
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
