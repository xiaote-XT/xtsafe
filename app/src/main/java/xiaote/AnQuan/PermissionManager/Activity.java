package xiaote.AnQuan.PermissionManager;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

import xiaote.AnQuan.BaseActivity;
import xiaote.AnQuan.SwipeBackHelper;

/**
 * 权限管理。
 *
 * 集中原主界面「授权」区的全部入口：
 *   1. Shizuku 授权
 *   2. 无障碍设置（手动进入系统页）
 *   3. 一键开启无障碍（Shizuku / Root 写 settings）
 *   4. 通知权限
 *   5. 设备管理员（广播触发自动设置）
 *   6. 省电优化
 *
 * 各提权方案的增强设置（Shizuku 增强 / Dhizuku 增强 / Device Owner 增强）
 * 单独放在「权限增强」页（EnhanceActivity）。
 */
public class Activity extends BaseActivity {

    private static final int SHIZUKU_REQUEST_CODE = 10086;

    private SharedPreferences prefs;
    private TextView statusView;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == SHIZUKU_REQUEST_CODE) {
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(Activity.this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
                        }
                        refreshStatus();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 24, 30, 30);

        TextView title = new TextView(this);
        title.setText("权限管理");
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 10, 0, 4);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("集中管理运行所需的系统权限与保活设置");
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, 16);
        root.addView(tip);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(getSecondaryTextColor());
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(16, 4, 16, 16);
        root.addView(statusView);

        addButton(root, "一键授权", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Activity.this, OneKeyAuthActivity.class));
            }
        });

        addButton(root, "Shizuku 授权", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    if (Shizuku.pingBinder()) {
                        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                        } else {
                            Toast.makeText(Activity.this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(Activity.this, "Shizuku 未运行", Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable t) {
                    Toast.makeText(Activity.this, "Shizuku 调用异常", Toast.LENGTH_SHORT).show();
                }
            }
        });

        addButton(root, "无障碍设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 手动进入无障碍设置，1 分钟内不拦截
                prefs.edit().putLong("admin_unlock_time", System.currentTimeMillis() + 60000).apply();
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(Activity.this, "无法打开设置", Toast.LENGTH_SHORT).show();
                }
            }
        });

        addButton(root, "一键开启无障碍", new View.OnClickListener() {
            @Override public void onClick(View v) { enableAccessibilityByShell(); }
        });

        addButton(root, "通知权限", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2000);
                } else {
                    Toast.makeText(Activity.this, "当前系统无需单独授权", Toast.LENGTH_SHORT).show();
                }
            }
        });

        addButton(root, "设备管理员", new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 广播给 XTSafeMainService 自动设置设备管理员
                Intent intent = new Intent("xiaote.AnQuan.AUTO_SETUP_ADMIN");
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
                Toast.makeText(Activity.this, "正在打开设备管理员设置", Toast.LENGTH_SHORT).show();
            }
        });

        addButton(root, "省电优化", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(Activity.this, "无法打开设置", Toast.LENGTH_SHORT).show();
                }
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

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { Shizuku.removeRequestPermissionResultListener(permissionListener); } catch (Throwable ignored) {}
    }

    private void refreshStatus() {
        if (statusView == null) return;
        String shizuku;
        try {
            if (Shizuku.pingBinder()) {
                shizuku = (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                        ? "已授权" : "未授权";
            } else {
                shizuku = "未运行";
            }
        } catch (Throwable t) {
            shizuku = "未知";
        }
        statusView.setText("Shizuku: " + shizuku + "    Root: "
                + (isRootAvailable() ? "已授权" : "未授权"));
    }

    private void addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setAllCaps(false);
        btn.setOnClickListener(listener);
        root.addView(btn);
    }

    /** 一键开启无障碍：Shizuku → Root 依次尝试 */
    private void enableAccessibilityByShell() {
        String component = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
        String cmd1 = "settings put secure enabled_accessibility_services '" + component + "'";
        String cmd2 = "settings put secure accessibility_enabled 1";
        boolean done = false;
        try {
            if (Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shizuku.newProcess(new String[]{"sh", "-c", cmd1 + " && " + cmd2}, null, null);
                done = true;
            }
        } catch (Throwable ignored) {}
        if (!done && isRootAvailable()) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd1 + " && " + cmd2});
                p.waitFor();
                done = true;
            } catch (Throwable ignored) {}
        }
        if (done) {
            Toast.makeText(this, "已尝试开启无障碍，请到系统设置确认", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "需要 Shizuku 或 Root 权限", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isRootAvailable() {
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            br.close();
            p.destroy();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
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
