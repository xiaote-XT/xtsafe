//AI诚实测试，xiaote手动写入
package xiaote.AnQuan;

import android.app.Application;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 动态注册安装检测广播（Android 8.0+ 静态注册隐式广播受限）
 * 
 * 规则文件三源独立存储：
 *   assets/scan_rules.json        - 内置规则（不可删除）
 *   filesDir/scan_rules_cloud.json    - 云端更新规则（可删除）
 *   filesDir/scan_rules_imported.json - 导入规则（可删除）
 */
public class App extends Application {
    private static Context sContext;

    public static Context getContext() {
        return sContext;
    }
    private static App instance;
    private InstallScanReceiver installScanReceiver;

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        sContext = this;
        loadVirusPackages();
        registerInstallScanReceiver();
        loadSensitivePackages();
        checkAutoUpdate();

        // 调度强制置顶兜底闹钟（独立于服务进程，防锁屏停止后服务死亡无法恢复）
        ForceTopAlarmReceiver.schedule(this);
        
    }

    /** 应用启动时从规则文件加载病毒包名、敏感App、白名单，确保各服务拿到最新列表 */
    private void loadVirusPackages() {
        try {
            // 优先级：导入 > 云端 > 内置
            String json = null;
            File imported = new File(getFilesDir(), "scan_rules_imported.json");
            if (imported.exists()) {
                json = readFile(imported);
            }
            if (json == null) {
                File cloud = new File(getFilesDir(), "scan_rules_cloud.json");
                if (cloud.exists()) {
                    json = readFile(cloud);
                }
            }
            if (json == null) {
                InputStream is = getAssets().open("scan_rules.json");
                BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
                r.close();
                json = sb.toString();
            }
            if (json != null) {
                org.json.JSONObject root = new org.json.JSONObject(json);

                // 病毒包名
                org.json.JSONArray virus = root.optJSONArray("virusPackages");
                if (virus != null && virus.length() > 0) {
                    String[] list = new String[virus.length()];
                    for (int i = 0; i < virus.length(); i++) list[i] = virus.getString(i);
                    VirusPackages.setBuiltin(list);
                }

                // 敏感App
                org.json.JSONArray prot = root.optJSONArray("protectedPackages");
                if (prot != null && prot.length() > 0) {
                    String[] list = new String[prot.length()];
                    for (int i = 0; i < prot.length(); i++) list[i] = prot.getString(i);
                    ProtectedPackages.setBuiltin(list);
                }

                // 白名单
                org.json.JSONArray wl = root.optJSONArray("whitelist");
                if (wl != null && wl.length() > 0) {
                    java.util.List<String[]> wlList = new java.util.ArrayList<>();
                    for (int i = 0; i < wl.length(); i++) {
                        org.json.JSONObject obj = wl.optJSONObject(i);
                        if (obj == null) continue;
                        String pkg = obj.optString("packageName", "");
                        String md5 = obj.optString("signatureMd5", "");
                        if (!pkg.isEmpty() && !md5.isEmpty()) {
                            wlList.add(new String[]{pkg, md5});
                        }
                    }
                    if (!wlList.isEmpty()) {
                        SecurityScanActivity.setWhitelist(wlList.toArray(new String[0][0]));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private String readFile(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        BufferedReader r = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        return sb.toString();
    }

    private void loadSensitivePackages() {
    }

    private void registerInstallScanReceiver() {
        try {
            installScanReceiver = new InstallScanReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(installScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(installScanReceiver, filter);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 云端自动更新规则
     * 保存到独立的 cloud 文件，与内置规则和导入规则互不干扰
     */
    private void checkAutoUpdate() {
        try {
            final SharedPreferences prefs = getSharedPreferences("dot_config", MODE_PRIVATE);
            final int freq = prefs.getInt("auto_update_frequency", 0);
            if (freq == 0) return; // 关闭

            final long lastUpdate = prefs.getLong("auto_update_last_time", 0);
            final long now = System.currentTimeMillis();
            if (freq == 1) { // 每天
                if (now - lastUpdate < 86400000L) return;
            } else if (freq == 2) { // 每星期
                if (now - lastUpdate < 604800000L) return;
            }
            // freq == 3 每次打开，每次都检查

            final String urlStr = "https://raw.githubusercontent.com/xiaote-XT/xtsafe/refs/heads/main/config.json";
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        URL url = new URL(urlStr);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);
                        int code = conn.getResponseCode();
                        if (code != 200) { conn.disconnect(); return; }
                        InputStream is = conn.getInputStream();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();
                        conn.disconnect();
                        final String remoteContent = sb.toString();

                        // 对比云端规则文件（独立存储，不与内置规则混淆）
                        File cloudFile = new File(getFilesDir(), "scan_rules_cloud.json");
                        String localContent = "";
                        if (cloudFile.exists()) {
                            FileInputStream fis = new FileInputStream(cloudFile);
                            BufferedReader lr = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
                            StringBuilder lsb = new StringBuilder();
                            String l;
                            while ((l = lr.readLine()) != null) lsb.append(l);
                            lr.close();
                            fis.close();
                            localContent = lsb.toString();
                        }

                        // 内容相同，不更新
                        if (remoteContent.equals(localContent)) {
                            prefs.edit().putLong("auto_update_last_time", now).apply();
                            return;
                        }

                        // 内容不同，保存到云端规则文件
                        FileOutputStream fos = new FileOutputStream(cloudFile);
                        fos.write(remoteContent.getBytes("UTF-8"));
                        fos.close();
                        prefs.edit().putLong("auto_update_last_time", now).apply();
                        // 重新加载病毒包名列表
                        loadVirusPackages();

                        // 通知用户
                        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        String channelId = "auto_update";
                        if (Build.VERSION.SDK_INT >= 26) {
                            android.app.NotificationChannel ch = new android.app.NotificationChannel(channelId, 
                                getString(R.string.rules_update_channel), android.app.NotificationManager.IMPORTANCE_DEFAULT);
                            nm.createNotificationChannel(ch);
                        }
                        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                            ? new android.app.Notification.Builder(App.this, channelId)
                            : new android.app.Notification.Builder(App.this);
                        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle(getString(R.string.rules_updated))
                            .setContentText(getString(R.string.rules_updated_msg))
                            .setAutoCancel(true);
                        nm.notify(10086, builder.build());
                    } catch (Exception ignored) {}
                }
            }).start();
        } catch (Exception ignored) {}
    }

    @Override
    public void onTerminate() {
        try {
            if (installScanReceiver != null) {
                unregisterReceiver(installScanReceiver);
                installScanReceiver = null;
            }
        } catch (Exception e) {
            // ignore
        }
        super.onTerminate();
    }
}
