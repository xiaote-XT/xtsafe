package xiaote.AnQuan.PermissionManager;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import xiaote.AnQuan.DhizukuActivity;
import xiaote.AnQuan.DeviceOwnerActivity;
import xiaote.AnQuan.ShizukuActivity;
import xiaote.AnQuan.SwipeBackHelper;
import xiaote.xtui.XtUi;

/**
 * 权限增强。
 *
 * 集中各提权方案的增强设置入口：
 *   1. Shizuku 增强（阻止卸载 / 阻止阻止卸载）
 *   2. Dhizuku 增强（设备所有者代理）
 *   3. Device Owner 增强（自带设备所有者 / 禁止卸载）
 *
 * 与「权限管理」页配套：权限管理放基础系统权限，本页放提权增强。
 *
 * 继承 Activity 而非 BaseActivity：授权过程会跳转外部页面，
 * 中途弹密码锁会打断授权流程。
 */
public class EnhanceActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, "权限增强"));
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this, "各提权方案的增强设置，按需选择其中一种或多种");
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, XtUi.dp(this, 16));
        root.addView(tip);

        LinearLayout card = XtUi.card(this);

        XtUi.addButton(card, this, "Shizuku 增强", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(EnhanceActivity.this, ShizukuActivity.class));
            }
        });

        XtUi.addButton(card, this, "Dhizuku 增强", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(EnhanceActivity.this, DhizukuActivity.class));
            }
        });

        XtUi.addButton(card, this, "Device Owner 增强", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(EnhanceActivity.this, DeviceOwnerActivity.class));
            }
        });

        root.addView(card);

        setContentView(scrollView);
        // 防止输入控件自动抢焦点弹键盘
        root.setFocusableInTouchMode(true);
        root.requestFocus();

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
