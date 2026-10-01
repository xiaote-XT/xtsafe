package xiaote.AnQuan.PermissionManager;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import xiaote.AnQuan.DhizukuActivity;
import xiaote.AnQuan.DeviceOwnerActivity;
import xiaote.AnQuan.ShizukuActivity;
import xiaote.AnQuan.SwipeBackHelper;

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

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 24, 30, 30);

        TextView title = new TextView(this);
        title.setText("权限增强");
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 10, 0, 4);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("各提权方案的增强设置，按需选择其中一种或多种");
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, 20);
        root.addView(tip);

        addButton(root, "Shizuku 增强", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(EnhanceActivity.this, ShizukuActivity.class));
            }
        });

        addButton(root, "Dhizuku 增强", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(EnhanceActivity.this, DhizukuActivity.class));
            }
        });

        addButton(root, "Device Owner 增强", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(EnhanceActivity.this, DeviceOwnerActivity.class));
            }
        });

        Button backBtn = new Button(this);
        backBtn.setText("完成");
        backBtn.setTextSize(14);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-1, -2);
        backLp.topMargin = 24;
        root.addView(backBtn, backLp);

        scrollView.addView(root);
        setContentView(scrollView);
        // 防止输入控件自动抢焦点弹键盘
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
    }

    private void addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setAllCaps(false);
        btn.setOnClickListener(listener);
        root.addView(btn);
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

    private int getTextColor() { return Color.argb(255, 30, 30, 30); }
    private int getSecondaryTextColor() { return Color.argb(150, 90, 90, 90); }
}
