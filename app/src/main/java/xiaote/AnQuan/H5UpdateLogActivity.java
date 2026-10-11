package xiaote.AnQuan;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * H5 界面壳（更新日志）。
 *
 * 与原生 UpdateLogActivity 平行，数据同源：同一份 Update-Log.txt 远端地址，
 * 同一套失败文案，复制行为也一致。
 *
 * 桥方法（JS 调 XT.xxx）：
 *   getLogText()   取当前已加载的日志全文（未加载完返回空串）
 *   copyLog()      复制日志到剪贴板
 *   close()        关闭本页
 *
 * 拉取在后台线程做，完成后用 evaluateJavascript 调 window.XTSetLog(text) 回填，
 * 避免阻塞 UI 也避免 JS 侧轮询。
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5UpdateLogActivity extends H5BaseActivity {

    /** 与原生 UpdateLogActivity 一致：日志浏览页，不弹密码锁 */
    @Override
    protected boolean lockEnabled() { return false; }

    private static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/xiaote-XT/xtsafe/refs/heads/main/Update-Log.txt";

    private volatile String logText = "";

    @Override
    protected void onInit() {
        // 与原生一致：未加载完先显示加载中
        logText = "";
    }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/update_log.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ==================== 拉取 ====================

    private void load() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = fetch(DEFAULT_URL);
                final String value = (text == null || text.trim().isEmpty())
                        ? getString(R.string.fetch_log_fail) : text;
                logText = value;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || webView == null) return;
                        try {
                            webView.evaluateJavascript(
                                    "window.XTSetLog && window.XTSetLog(" + jsString(value) + ");", null);
                        } catch (Throwable ignored) {}
                    }
                });
            }
        }, "h5-update-log").start();
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
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                conn.disconnect();
                return sb.toString();
            }
            conn.disconnect();
        } catch (Throwable ignored) {}
        return null;
    }

    /** 把任意文本安全地包成 JS 字符串字面量 */
    private static String jsString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ==================== JS Bridge ====================

    public class Bridge {

        @JavascriptInterface
        public String getLogText() {
            return logText == null ? "" : logText;
        }

        @JavascriptInterface
        public void copyLog() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        String text = logText == null ? "" : logText;
                        cm.setPrimaryClip(ClipData.newPlainText(
                                getString(R.string.update_log_label), text));
                        Toast.makeText(H5UpdateLogActivity.this,
                                R.string.copied, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(new Runnable() {
                @Override public void run() { finish(); }
            });
        }
    }
}
