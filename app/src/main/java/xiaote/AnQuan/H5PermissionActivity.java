package xiaote.AnQuan;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

import rikka.shizuku.Shizuku;

/**
 * H5 界面壳（权限管理）。
 *
 * 与原生 PermissionManager.Activity 平行，功能一一对应：
 *   一键授权 / Shizuku 授权 / 无障碍设置 / 一键开启无障碍 /
 *   通知权限 / 设备管理员 / 省电优化
 *
 * 桥方法（JS 调 XT.xxx）：
 *   getStatus()                返回 {shizuku, root} 的 JSON
 *   openPage('onekey')         打开一键授权页
 *   requestShizuku()           申请 Shizuku 权限
 *   openAccessibilitySettings() 进系统无障碍设置（1 分钟免拦截）
 *   enableAccessibility()      用 Shizuku / Root 写 settings 开启无障碍
 *   requestNotification()      申请通知权限（13+）
 *   setupAdmin()               广播触发自动设置设备管理员
 *   requestBattery()           进省电优化白名单设置
 *   toast(msg) / close()
 *
 * 继承 H5BaseActivity，主题 / 状态栏 / 深色注入全部复用基类。
 */
public class H5PermissionActivity extends H5BaseActivity {

    private static final int SHIZUKU_REQUEST_CODE = 10086;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == SHIZUKU_REQUEST_CODE) {
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            toast("Shizuku 已授权");
                        }
                        refreshFrontend();
                    }
                }
            };

    @Override
    protected void onInit() {
        try { Shizuku.addRequestPermissionResultListener(permissionListener); } catch (Throwable ignored) {}
    }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/perm.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFrontend();
    }

    @Override
    protected void onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(permissionListener); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(H5PermissionActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== JS Bridge ====================

    public class Bridge {

        @JavascriptInterface
        public String getStatus() {
            JSONObject o = new JSONObject();
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
            try {
                o.put("shizuku", shizuku);
                o.put("root", isRootAvailable() ? "已授权" : "未授权");
            } catch (Throwable ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public void openPage(final String key) {
            if (key == null) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if ("onekey".equals(key)) {
                        try {
                            startActivity(new Intent(H5PermissionActivity.this,
                                    xiaote.AnQuan.PermissionManager.OneKeyAuthActivity.class));
                        } catch (Throwable ignored) {}
                    }
                }
            });
        }

        @JavascriptInterface
        public void requestShizuku() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        if (Shizuku.pingBinder()) {
                            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                            } else {
                                toast("Shizuku 已授权");
                            }
                        } else {
                            toast("Shizuku 未运行");
                        }
                    } catch (Throwable t) {
                        toast("Shizuku 调用异常");
                    }
                }
            });
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    // 手动进无障碍设置，1 分钟内不拦截
                    try {
                        getSharedPreferences("dot_config", MODE_PRIVATE).edit()
                                .putLong("admin_unlock_time", System.currentTimeMillis() + 60000)
                                .apply();
                    } catch (Throwable ignored) {}
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Throwable t) {
                        toast("无法打开设置");
                    }
                }
            });
        }

        @JavascriptInterface
        public void enableAccessibility() {
            runOnUiThread(new Runnable() {
                @Override public void run() { enableAccessibilityByShell(); }
            });
        }

        @JavascriptInterface
        public void requestNotification() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= 33) {
                        try {
                            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2000);
                        } catch (Throwable ignored) {}
                    } else {
                        toast("当前系统无需单独授权");
                    }
                }
            });
        }

        @JavascriptInterface
        public void setupAdmin() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent intent = new Intent("xiaote.AnQuan.AUTO_SETUP_ADMIN");
                        intent.setPackage(getPackageName());
                        sendBroadcast(intent);
                        toast("正在打开设备管理员设置");
                    } catch (Throwable ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void requestBattery() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Throwable t) {
                        toast("无法打开设置");
                    }
                }
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            if (msg == null) return;
            H5PermissionActivity.this.toast(msg);
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(new Runnable() {
                @Override public void run() { finish(); }
            });
        }
    }

    // ==================== 一键开启无障碍：Shizuku → Root ====================

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
        toast(done ? "已尝试开启无障碍，请到系统设置确认" : "需要 Shizuku 或 Root 权限");
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
        } catch (Throwable e) {
            return false;
        }
    }
}
