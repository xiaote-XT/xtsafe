package xiaote.AnQuan;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * H5 界面壳（规则管理）。
 *
 * 与原生 RuleManagerActivity 平行，三源独立存储完全一致：
 *   assets/scan_rules.json           内置规则（不可删除）
 *   filesDir/scan_rules_cloud.json   云端更新规则（可删除）
 *   filesDir/scan_rules_imported.json 导入规则（可删除）
 *
 * 优先级：导入 &gt; 云端 &gt; 内置，与原生 readCurrentRules 同序。
 *
 * 桥方法（JS 调 XT.xxx）：
 *   getState()            返回 {statusText, hasImport, hasCloud, ruleText,
 *                                 linkUrl, autoFreq}
 *   saveImport(text)      保存为导入规则（并刷新 App 内规则）
 *   importFile()          选本地 JSON 文件导入
 *   linkImport(url)       从链接拉取并保存为导入规则
 *   checkCloud()          拉取云端，与本地比对；有差异时回调前端询问
 *   applyCloud()          把已拉取的云端内容落盘
 *   deleteImport() / deleteCloud()
 *   setAutoFreq(f)        0 关 / 1 每天 / 2 每星期 / 3 每次打开
 *   toast(msg) / close()
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5RuleManagerActivity extends H5BaseActivity {

    /** 与原生 RuleManagerActivity 一致：导入/下载期间不弹密码锁 */
    @Override
    protected boolean lockEnabled() { return false; }

    private static final String CLOUD_FILE = "scan_rules_cloud.json";
    private static final String IMPORT_FILE = "scan_rules_imported.json";
    private static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/xiaote-XT/xtsafe/refs/heads/main/config.json";

    private static final int REQ_PICK_FILE = 1000;

    private SharedPreferences prefs;

    /** 最近一次从云端拉取到、尚未落盘的内容 */
    private volatile String pendingCloud = null;

    @Override
    protected void onInit() {
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);
    }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/rules.html";
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE || resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    if (is == null) {
                        toastOnUi(getString(R.string.import_failed, "无法读取"));
                        return;
                    }
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    String content = sb.toString();
                    // 校验 JSON，与原生一致
                    try { new JSONObject(content); }
                    catch (Throwable t) {
                        toastOnUi(getString(R.string.invalid_rule_format));
                        return;
                    }
                    saveToFile(IMPORT_FILE, content);
                    reloadRules();
                    toastOnUi(getString(R.string.import_success));
                    refreshFrontend();
                } catch (Throwable t) {
                    toastOnUi(getString(R.string.import_failed, String.valueOf(t.getMessage())));
                }
            }
        }, "h5-rules-import").start();
    }

    private void toastOnUi(final String msg) {
        if (msg == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(H5RuleManagerActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== 文件 ====================

    private boolean hasFile(String name) {
        try { return new File(getFilesDir(), name).exists(); } catch (Throwable t) { return false; }
    }

    private String readFile(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        reader.close();
        return sb.toString();
    }

    private void saveToFile(String fileName, String content) throws Exception {
        FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), fileName));
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }

    /** 与原生 readCurrentRules 同序：导入 &gt; 云端 &gt; 内置 */
    private String readCurrentRules() {
        try {
            File imp = new File(getFilesDir(), IMPORT_FILE);
            if (imp.exists()) return readFile(imp);
            File cloud = new File(getFilesDir(), CLOUD_FILE);
            if (cloud.exists()) return readFile(cloud);
            InputStream is = getAssets().open("scan_rules.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 规则落盘后重新加载进内存（病毒包名 / 敏感App / 白名单），与原生同源 */
    private void reloadRules() {
        try {
            SecurityScanActivity.ensureRulesLoaded(this);
        } catch (Throwable ignored) {}
        try {
            App app = App.getInstance();
            if (app != null) {
                // App.loadVirusPackages 是私有方法，这里不反射，靠下次冷启动加载即可
            }
        } catch (Throwable ignored) {}
    }

    /** 删除规则文件：与原生 confirmDelete 行为一致，删除动作由用户在弹层确认后触发 */
    private void deleteRule(final String label, final String fileName) {
        try {
            File f = new File(getFilesDir(), fileName);
            if (f.exists() && !f.delete()) {
                toastOnUi(getString(R.string.delete_failed, "删除被拒绝"));
                return;
            }
            toastOnUi(getString(R.string.rule_deleted, label));
            reloadRules();
        } catch (Throwable t) {
            toastOnUi(getString(R.string.delete_failed, String.valueOf(t.getMessage())));
        }
        refreshFrontend();
    }

    // ==================== 云端 ====================

    /** 与原生 checkUpdate 同源：拉取 → 校验 JSON → 与本地 cloud 比对 → 决定是否询问 */
    private void checkCloud() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String content = fetch(DEFAULT_URL);
                if (content == null) {
                    toastOnUi(getString(R.string.check_fail_short));
                    return;
                }
                try { new JSONObject(content); }
                catch (Throwable t) {
                    toastOnUi(getString(R.string.invalid_rule_format));
                    return;
                }
                // 与已有云端规则比对，完全一致就直接提示已是最新
                boolean same = false;
                try {
                    File cloudFile = new File(getFilesDir(), CLOUD_FILE);
                    if (cloudFile.exists()) {
                        String local = readFile(cloudFile).replace("\n", "");
                        if (local.equals(content.replace("\n", ""))) same = true;
                    }
                } catch (Throwable ignored) {}
                if (same) {
                    toastOnUi(getString(R.string.cloud_latest));
                    return;
                }
                // 有差异：缓存内容并让前端弹确认层
                pendingCloud = content;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (webView == null) return;
                        try {
                            webView.evaluateJavascript(
                                    "window.XTAskUpdate && window.XTAskUpdate();", null);
                        } catch (Throwable ignored) {}
                    }
                });
            }
        }, "h5-rules-cloud").start();
    }

    private void applyCloud() {
        final String content = pendingCloud;
        if (content == null) {
            toastOnUi(getString(R.string.check_fail_short));
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    saveToFile(CLOUD_FILE, content);
                    pendingCloud = null;
                    reloadRules();
                    toastOnUi(getString(R.string.cloud_updated));
                    refreshFrontend();
                } catch (Throwable t) {
                    toastOnUi(getString(R.string.update_failed, String.valueOf(t.getMessage())));
                }
            }
        }, "h5-rules-apply").start();
    }

    /** 从链接导入：拉取后保存为导入规则（与原生 fetchAndImport 同语义） */
    private void linkImport(final String url) {
        if (url == null || url.trim().isEmpty()) return;
        toastOnUi(getString(R.string.downloading_rules));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String content = fetch(url.trim());
                if (content == null) {
                    toastOnUi(getString(R.string.download_fail_short));
                    return;
                }
                try { new JSONObject(content); }
                catch (Throwable t) {
                    toastOnUi(getString(R.string.invalid_rule_format));
                    return;
                }
                try {
                    saveToFile(IMPORT_FILE, content);
                    reloadRules();
                    toastOnUi(getString(R.string.imported_as_import));
                    refreshFrontend();
                } catch (Throwable t) {
                    toastOnUi(getString(R.string.save_failed, String.valueOf(t.getMessage())));
                }
            }
        }, "h5-rules-link").start();
    }

    private String fetch(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();
                return sb.toString();
            }
            conn.disconnect();
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 状态 ====================

    private String buildStateJson() {
        JSONObject o = new JSONObject();
        try {
            boolean hasCloud = hasFile(CLOUD_FILE);
            boolean hasImport = hasFile(IMPORT_FILE);

            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.rule_source_builtin));
            if (hasImport) sb.append(getString(R.string.rule_source_import));
            if (hasCloud) sb.append(getString(R.string.rule_source_cloud));
            if (!hasCloud && !hasImport) sb.append(getString(R.string.rule_source_default));
            sb.append("\n").append(getString(R.string.rule_priority));

            o.put("statusText", sb.toString());
            o.put("hasImport", hasImport);
            o.put("hasCloud", hasCloud);
            o.put("ruleText", readCurrentRules());
            o.put("linkUrl", DEFAULT_URL);
            o.put("autoFreq", prefs.getInt("auto_update_frequency", 0));
        } catch (Throwable ignored) {}
        return o.toString();
    }

    // ==================== JS Bridge ====================

    public class Bridge {

        @JavascriptInterface
        public String getState() {
            return buildStateJson();
        }

        @JavascriptInterface
        public void saveImport(String text) {
            if (text == null) return;
            try {
                new JSONObject(text);
            } catch (Throwable t) {
                toastOnUi(getString(R.string.invalid_rule_format));
                return;
            }
            try {
                saveToFile(IMPORT_FILE, text);
                reloadRules();
                toastOnUi(getString(R.string.saved_as_import));
            } catch (Throwable t) {
                toastOnUi(getString(R.string.save_failed, String.valueOf(t.getMessage())));
            }
            refreshFrontend();
        }

        @JavascriptInterface
        public void importFile() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/json");
                        startActivityForResult(intent, REQ_PICK_FILE);
                    } catch (Throwable t) {
                        toastOnUi("无法打开文件选择器");
                    }
                }
            });
        }

        @JavascriptInterface
        public void linkImport(String url) {
            linkImport0(url);
        }

        @JavascriptInterface
        public void checkCloud() {
            H5RuleManagerActivity.this.checkCloud();
        }

        @JavascriptInterface
        public void applyCloud() {
            H5RuleManagerActivity.this.applyCloud();
        }

        @JavascriptInterface
        public void deleteImport() {
            deleteRule(getString(R.string.rule_import_label), IMPORT_FILE);
        }

        @JavascriptInterface
        public void deleteCloud() {
            deleteRule(getString(R.string.rule_cloud_label), CLOUD_FILE);
        }

        @JavascriptInterface
        public void setAutoFreq(int f) {
            try {
                prefs.edit().putInt("auto_update_frequency", f).apply();
            } catch (Throwable ignored) {}
            refreshFrontend();
        }

        @JavascriptInterface
        public void toast(String msg) {
            toastOnUi(msg);
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(new Runnable() {
                @Override public void run() { finish(); }
            });
        }
    }

    /** Bridge 内部转发，避免与外层同名方法混淆 */
    private void linkImport0(String url) {
        linkImport(url);
    }
}
