package xiaote.AnQuan;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

import rikka.shizuku.Shizuku;

/**
 * H5 界面壳（主页）。
 *
 * 与原生 MainActivity 平行：两者读写同一份 dot_config，调用同一批业务类，
 * 仅外观不同。切换由 dot_config.ui_mode 决定（native / h5，默认 native）。
 *
 * 顶部状态卡片由 XT.getStatus() 提供数据，覆盖：
 *   Shizuku / Root / 无障碍 / 通知权限 / 无障碍保活 / 专版 Shizuku（Stellar）
 *
 * 状态采集是阻塞操作（su、ContentResolver.call 到 Stellar 最多等 1.2 秒），
 * 因此不在 JS 桥线程里同步跑：getStatus() 只返回上一次缓存，
 * 真正的采集在后台线程完成，回主线程 refreshFrontend() 让前端重拉。
 *
 * 路由约定（JS 调 XT.openPage(key)）：
 *   guide / perm / enhance / float / protect / acc / list / scan /
 *   killad / process / update_log / blog / github
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5MainActivity extends H5BaseActivity {

    /** 无障碍组件两种写法，与 XTSafeCoreService / XTSafeMainService 保持一致 */
    private static final String COMPONENT_STD = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
    private static final String COMPONENT_SHORT = "xiaote.AnQuan/.XTSafeMainService";
    /** 专版 Shizuku（Stellar）状态查询 authority */
    private static final String STELLAR_AUTHORITY = "content://roro.stellar.manager.status";

    private SharedPreferences prefs;

    /** 最近一次采集结果，getStatus() 直接返回它 */
    private volatile String cachedJson = "{}";
    private volatile boolean refreshing = false;

    @Override
    protected void onInit() {
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);
        try { XTSafeCoreService.ensureStarted(this); } catch (Throwable ignored) {}
    }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/main.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ==================== 状态采集 ====================

    /**
     * 后台线程采集全部状态，写入 cachedJson 后回推前端。
     * 防重入：上一次没跑完时丢弃本次请求。
     */
    private void refreshStatus() {
        if (refreshing) return;
        refreshing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject o = new JSONObject();
                try {
                    o.put("shizuku", shizukuText());
                    o.put("root", isRootAvailable() ? "已授权" : "未授权");
                    o.put("accessibility", isAccessibilityOn() ? "已开启" : "未开启");
                    o.put("notification", notificationText());
                    o.put("keepAlive", isKeepAliveOn());
                    o.put("stellar", stellarText());
                } catch (Throwable ignored) {}
                cachedJson = o.toString();
                refreshing = false;
                refreshFrontend();
            }
        }, "h5-main-status").start();
    }

    /** Shizuku 状态文案 */
    private String shizukuText() {
        try {
            if (!Shizuku.pingBinder()) return "未运行";
            return (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                    ? "已授权" : "未授权";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** 通知权限文案（Android 13 以下无需单独申请） */
    private String notificationText() {
        try {
            if (Build.VERSION.SDK_INT < 33) return "无需授权";
            return (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED) ? "已授权" : "未授权";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** 本应用无障碍服务是否在系统启用列表中 */
    private boolean isAccessibilityOn() {
        try {
            String list = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return false;
            for (String item : list.split(":")) {
                if (item.equals(COMPONENT_STD) || item.equals(COMPONENT_SHORT)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isKeepAliveOn() {
        try {
            return prefs.getBoolean(XTSafeCoreService.KEY_ACC_KEEP_ALIVE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 专版 Shizuku（Stellar）是否在运行。
     *
     * 通过 ContentResolver.call 查询其公开状态 provider，无需权限、
     * 无需在 Manifest 里声明 <queries>。未安装或未运行时 provider 不可达，
     * call 会抛异常，统一按「未安装/未运行」处理。
     *
     * @return "运行中（ADB 2.1.5）" 这类可读文案，或 "未运行"/"未安装"
     */
    private String stellarText() {
        try {
            android.os.Bundle reply = getContentResolver().call(
                    Uri.parse(STELLAR_AUTHORITY), "isRunning", null, null);
            if (reply == null) return "未安装";
            if (!reply.getBoolean("running", false)) return "未运行";

            int uid = reply.getInt("uid", -1);
            String kind;
            if (uid == 0) kind = "Root";
            else if (uid == 2000) kind = "ADB";
            else if (uid > 0) kind = "uid " + uid;
            else kind = "未知身份";

            String vn = null;
            try { vn = reply.getString("versionName"); } catch (Throwable ignored) {}
            return "运行中（" + kind + (vn != null && !vn.isEmpty() ? " " + vn : "") + "）";
        } catch (Throwable t) {
            // 未安装、provider 未发布、或管理器刚被拉起尚未就绪
            return "未运行";
        }
    }

    /** Root 检测：执行 su -c id 判 uid=0。阻塞调用，只允许在后台线程执行 */
    private boolean isRootAvailable() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su -c id");
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            br.close();
            return line != null && line.contains("uid=0");
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    // ==================== JS Bridge ====================

    public class Bridge {

        /** 返回上一次采集到的状态 JSON；调用方应在 onResume 或下拉后重拉 */
        @JavascriptInterface
        public String getStatus() {
            return cachedJson;
        }

        /** 主动触发一次重新采集 */
        @JavascriptInterface
        public void refreshStatus() {
            H5MainActivity.this.refreshStatus();
        }

        @JavascriptInterface
        public void openPage(final String key) {
            runOnUiThread(new Runnable() {
                @Override public void run() { openPageInternal(key); }
            });
        }

        @JavascriptInterface
        public void switchToNative() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    prefs.edit().putString("ui_mode", "native").apply();
                    try {
                        startActivity(new Intent(H5MainActivity.this, MainActivity.class));
                    } catch (Throwable ignored) {}
                    finish();
                }
            });
        }

        @JavascriptInterface
        public void showWarning() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    new AlertDialog.Builder(H5MainActivity.this)
                            .setTitle(R.string.warning_title)
                            .setMessage(R.string.warning_body)
                            .setPositiveButton(R.string.got_it, null)
                            .setCancelable(false)
                            .show();
                }
            });
        }

        @JavascriptInterface
        public boolean isDark() {
            return isDarkMode();
        }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(H5MainActivity.this, R.string.checking_update, Toast.LENGTH_SHORT).show();
                }
            });
            new Thread(new Runnable() {
                @Override public void run() { doCheckUpdate(); }
            }, "h5-check-update").start();
        }

        @JavascriptInterface
        public String getPref(String key, String def) {
            if (key == null) return def;
            try {
                if (prefs.contains(key)) return prefs.getString(key, def);
            } catch (Throwable ignored) {}
            return def;
        }

        @JavascriptInterface
        public void setPref(String key, String value) {
            if (key == null) return;
            try { prefs.edit().putString(key, value).apply(); } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void toast(final String msg) {
            if (msg == null) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(H5MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    // ==================== 路由 ====================

    private void openPageInternal(String key) {
        if (key == null) return;
        try {
            if ("guide".equals(key)) startActivity(new Intent(this, H5GuideActivity.class));
            else if ("perm".equals(key)) startActivity(new Intent(this, H5PermissionActivity.class));
            else if ("enhance".equals(key)) startActivity(new Intent(this, H5EnhanceActivity.class));
            else if ("float".equals(key)) startActivity(new Intent(this, H5FloatActivity.class));
            else if ("protect".equals(key)) startActivity(new Intent(this, H5ProtectionActivity.class));
            else if ("acc".equals(key)) startActivity(new Intent(this, H5AccActivity.class));
            else if ("list".equals(key)) startActivity(new Intent(this, H5ListActivity.class));
            else if ("scan".equals(key)) startActivity(new Intent(this, H5ScanActivity.class));
            else if ("killad".equals(key)) startActivity(new Intent(this, H5KillAdActivity.class));
            else if ("process".equals(key)) startActivity(new Intent(this, H5ProcessActivity.class));
            else if ("update_log".equals(key)) startActivity(new Intent(this, H5UpdateLogActivity.class));
            else if ("blog".equals(key)) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://xiaote.data.blog/xtsafe")));
            else if ("github".equals(key)) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xiaote-xt/xtsafe")));
        } catch (Throwable ignored) {}
    }

    // ==================== 更新检查（与原生同源逻辑） ====================

    private void doCheckUpdate() {
        String remoteVersion = null;
        try {
            java.net.URL url = new java.net.URL(
                    "https://raw.githubusercontent.com/xiaote-XT/xtsafe/refs/heads/main/Update-Log.txt");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (t.matches("[vV]?\\d+(\\.\\d+)+")) {
                        String ver = t.replaceFirst("^[vV]", "").trim();
                        if (remoteVersion == null || compareVersions(ver, remoteVersion) > 0) {
                            remoteVersion = ver;
                        }
                    }
                }
                r.close();
            }
            conn.disconnect();
        } catch (Throwable ignored) {
            remoteVersion = null;
        }

        final String latest = remoteVersion;
        String currentVersion = "";
        try {
            currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {}
        final String cur = currentVersion == null ? "" : currentVersion;

        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                if (latest == null) {
                    Toast.makeText(H5MainActivity.this, R.string.check_update_fail, Toast.LENGTH_LONG).show();
                    return;
                }
                if (compareVersions(latest, cur.replaceFirst("^[vV]", "").trim()) > 0) {
                    new AlertDialog.Builder(H5MainActivity.this)
                            .setTitle(R.string.new_version_found)
                            .setMessage(getString(R.string.latest_version, latest, cur))
                            .setPositiveButton(R.string.view_log, new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    UpdateLogActivity.start(H5MainActivity.this);
                                }
                            })
                            .setNegativeButton(R.string.ignore, null)
                            .show();
                } else {
                    Toast.makeText(H5MainActivity.this,
                            getString(R.string.already_latest, cur), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private int compareVersions(String a, String b) {
        try {
            String[] pa = a.trim().replaceFirst("^[vV]", "").split("\\.");
            String[] pb = b.trim().replaceFirst("^[vV]", "").split("\\.");
            int len = Math.max(pa.length, pb.length);
            for (int i = 0; i < len; i++) {
                int na = i < pa.length ? Integer.parseInt(pa[i].trim()) : 0;
                int nb = i < pb.length ? Integer.parseInt(pb[i].trim()) : 0;
                if (na != nb) return na - nb;
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
