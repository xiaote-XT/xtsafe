package xiaote.AnQuan.PermissionManager;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

import xiaote.AnQuan.BaseActivity;
import xiaote.AnQuan.SwipeBackHelper;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * 权限管理。
 *
 * 集中原主界面「授权」区的全部入口：
 *   1. 一键授权
 *   2. Shizuku 授权
 *   3. 无障碍设置（手动进入系统页）
 *   4. 一键开启无障碍（Shizuku / Root 写 settings）
 *   5. 通知权限
 *   6. 设备管理员（广播触发自动设置）
 *   7. 省电优化
 *
 * 各提权方案的增强设置单独放在「权限增强」页。
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
                            XtToast.show(Activity.this, "Shizuku 已授权");
                        }
                        refreshStatus();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 界面模式分流：ui_mode=h5 时交给 H5 壳，原生界面不构建。默认 native。
        SharedPreferences uiPrefs = getSharedPreferences("dot_config", MODE_PRIVATE);
        if ("h5".equals(uiPrefs.getString("ui_mode", "native"))) {
            try { startActivity(new Intent(Activity.this, xiaote.AnQuan.H5PermissionActivity.class)); } catch (Throwable ignored) {}
            finish();
            return;
        }

        Shizuku.addRequestPermissionResultListener(permissionListener);
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, "权限管理"));
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this, "集中管理运行所需的系统权限与保活设置");
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, XtUi.dp(this, 16));
        root.addView(tip);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(XtUi.colorMuted(this));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 0, 0, XtUi.dp(this, 16));
        root.addView(statusView);

        LinearLayout card = XtUi.card(this);

        XtUi.addButton(card, this, "一键授权", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Activity.this, OneKeyAuthActivity.class));
            }
        });

        XtUi.addButton(card, this, "Shizuku 授权", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    if (Shizuku.pingBinder()) {
                        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                        } else {
                            XtToast.show(Activity.this, "Shizuku 已授权");
                        }
                    } else {
                        XtToast.show(Activity.this, "Shizuku 未运行");
                    }
                } catch (Throwable t) {
                    XtToast.show(Activity.this, "Shizuku 调用异常");
                }
            }
        });

        XtUi.addButton(card, this, "无障碍设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 手动进入无障碍设置，1 分钟内不拦截
                prefs.edit().putLong("admin_unlock_time", System.currentTimeMillis() + 60000).apply();
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    XtToast.show(Activity.this, "无法打开设置");
                }
            }
        });

        XtUi.addButton(card, this, "一键开启无障碍", new View.OnClickListener() {
            @Override public void onClick(View v) { enableAccessibilityByShell(); }
        });

        XtUi.addButton(card, this, "通知权限", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2000);
                } else {
                    XtToast.show(Activity.this, "当前系统无需单独授权");
                }
            }
        });

        XtUi.addButton(card, this, "设备管理员", new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 广播给 XTSafeMainService 自动设置设备管理员
                Intent intent = new Intent("xiaote.AnQuan.AUTO_SETUP_ADMIN");
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
                XtToast.show(Activity.this, "正在打开设备管理员设置");
            }
        });

        XtUi.addButton(card, this, "省电优化", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    XtToast.show(Activity.this, "无法打开设置");
                }
            }
        });

        root.addView(card);

        setContentView(scrollView);

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);
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
            XtToast.showLong(this, "已尝试开启无障碍，请到系统设置确认");
        } else {
            XtToast.show(this, "需要 Shizuku 或 Root 权限");
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
}
