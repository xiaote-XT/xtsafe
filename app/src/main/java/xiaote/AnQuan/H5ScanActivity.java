package xiaote.AnQuan;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * H5 界面壳（安全扫描）。
 *
 * 扫描引擎完全复用原生 SecurityScanActivity 的静态方法：
 *   ensureRulesLoaded / scanInstalledAppResult / scanApk / summarize /
 *   ScanResult.toDetail / getLabelFromArchive / getSignatureMd5
 * 本类只做「列表 + 触发 + 结果回传」，不复制任何检测规则。
 *
 * 列表来源与原生一致的口径：已安装的第三方应用（排除自身），按应用名排序。
 *
 * 扫描是耗时操作（读 dex + 解析），全部在后台线程串行跑，
 * 用 evaluateJavascript 回推进度（window.XTSetProgress）与完成（window.XTScanDone）。
 *
 * 桥方法（JS 调 XT.xxx）：
 *   getList()            返回 [{appName, pkg, scanned, risk, riskText, summary, detail}]
 *   isScanning()         是否正在扫描
 *   scanAll()            扫描全部已安装应用
 *   scanOne(pkg)         扫描单个已安装应用
 *   pickApk()            选 APK 文件扫描（ACTION_OPEN_DOCUMENT）
 *   openRules()          打开规则管理页（原生）
 *   uninstall(pkg)       发起系统卸载
 *   toast(msg) / close()
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5ScanActivity extends H5BaseActivity {

    /** 与原生 SecurityScanActivity 一致：扫描/文件选择期间不弹密码锁 */
    @Override
    protected boolean lockEnabled() { return false; }

    private static final int REQUEST_SELECT_APK = 1001;

    private PackageManager pm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 已扫描结果缓存：pkg -> JSON（scanned/risk/riskText/summary/detail） */
    private final java.util.Map<String, JSONObject> results =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, JSONObject>());

    private volatile boolean scanning = false;

    @Override
    protected void onInit() {
        pm = getPackageManager();
        try { SecurityScanActivity.ensureRulesLoaded(this); } catch (Throwable ignored) {}
        // 从分享/打开进入的 APK：转交给原生扫描任务处理不了（无实例），
        // 这里用 scanApk 自己跑一遍，结果只写缓存并提示。
        try { handleIncoming(getIntent()); } catch (Throwable ignored) {}
    }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/scan.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SELECT_APK || resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        toastOnUi(getString(R.string.selected_toast, uri.getLastPathSegment()));
        new Thread(new Runnable() {
            @Override
            public void run() {
                File cache = copyUriToCache(uri);
                if (cache == null) {
                    toastOnUi(getString(R.string.copy_apk_fail));
                    return;
                }
                try {
                    SecurityScanActivity.ScanResult r =
                            SecurityScanActivity.scanApk(pm, cache.getAbsolutePath(), null);
                    showFileResult(cache.getName(), r);
                } catch (Throwable t) {
                    toastOnUi("扫描失败: " + t.getClass().getSimpleName());
                }
            }
        }, "h5-scan-apk").start();
    }

    private void toastOnUi(final String msg) {
        if (msg == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(H5ScanActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== 结果模型 ====================

    /** 与原生 riskText 同一套文案，等级取自 result.maxRiskLevel */
    private String riskText(int risk) {
        switch (risk) {
            case 4: return getString(R.string.risk_extreme);
            case 3: return getString(R.string.risk_high);
            case 2: return getString(R.string.risk_sensitive);
            case 1: return getString(R.string.risk_low);
            default: return getString(R.string.risk_safe);
        }
    }

    /** 把 ScanResult 转成前端需要的字段（摘要与详细都走原生实现） */
    private JSONObject toJson(SecurityScanActivity.ScanResult r) {
        JSONObject o = new JSONObject();
        try {
            int risk = (r == null) ? 0 : r.maxRiskLevel;
            o.put("scanned", true);
            o.put("risk", risk);
            o.put("riskText", riskText(risk));
            o.put("summary", r == null ? "" : SecurityScanActivity.summarize(r));
            o.put("detail", r == null ? "" : r.toDetail());
        } catch (Throwable ignored) {}
        return o;
    }

    // ==================== 列表 ====================

    /**
     * 已安装第三方应用列表（排除自身），按应用名排序，
     * 已扫描过的带上缓存结果。
     */
    private String buildListJson() {
        JSONArray arr = new JSONArray();
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            List<ApplicationInfo> kept = new ArrayList<ApplicationInfo>();
            String self = getPackageName();
            if (apps != null) {
                for (ApplicationInfo ai : apps) {
                    if (ai == null) continue;
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue;
                    if (self.equals(ai.packageName)) continue;
                    kept.add(ai);
                }
            }
            Collections.sort(kept, new Comparator<ApplicationInfo>() {
                @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                    return labelOf(a).compareToIgnoreCase(labelOf(b));
                }
            });
            for (ApplicationInfo ai : kept) {
                JSONObject o = new JSONObject();
                o.put("appName", labelOf(ai));
                o.put("pkg", ai.packageName);
                JSONObject cached = results.get(ai.packageName);
                if (cached != null) {
                    o.put("scanned", true);
                    o.put("risk", cached.optInt("risk", 0));
                    o.put("riskText", cached.optString("riskText", ""));
                    o.put("summary", cached.optString("summary", ""));
                    o.put("detail", cached.optString("detail", ""));
                } else {
                    o.put("scanned", false);
                }
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        return arr.toString();
    }

    private String labelOf(ApplicationInfo ai) {
        try {
            CharSequence c = pm.getApplicationLabel(ai);
            if (c != null && c.length() > 0) return c.toString();
        } catch (Throwable ignored) {}
        return ai.packageName;
    }

    // ==================== 扫描 ====================

    /** 扫描全部已安装第三方应用，逐个回推进度 */
    private void scanAll() {
        if (scanning) return;
        scanning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<ApplicationInfo> apps = new ArrayList<ApplicationInfo>();
                try {
                    String self = getPackageName();
                    List<ApplicationInfo> all = pm.getInstalledApplications(0);
                    if (all != null) {
                        for (ApplicationInfo ai : all) {
                            if (ai == null) continue;
                            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                            if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue;
                            if (self.equals(ai.packageName)) continue;
                            apps.add(ai);
                        }
                    }
                } catch (Throwable ignored) {}

                int total = apps.size();
                int cur = 0;
                for (ApplicationInfo ai : apps) {
                    cur++;
                    final int fc = cur, ft = total;
                    final String fl = labelOf(ai);
                    postProgress(fc, ft, fl);
                    try {
                        SecurityScanActivity.ScanResult r =
                                SecurityScanActivity.scanInstalledAppResult(pm, ai.packageName);
                        results.put(ai.packageName, toJson(r));
                    } catch (Throwable ignored) {}
                }
                scanning = false;
                postDone();
            }
        }, "h5-scan-all").start();
    }

    /** 扫描单个已安装应用 */
    private void scanOne(final String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SecurityScanActivity.ScanResult r =
                            SecurityScanActivity.scanInstalledAppResult(pm, pkg);
                    results.put(pkg, toJson(r));
                } catch (Throwable t) {
                    toastOnUi("扫描失败: " + t.getClass().getSimpleName());
                }
                refreshFrontend();
            }
        }, "h5-scan-one").start();
    }

    /** 文件扫描结果：不进列表，直接弹原生对话框展示摘要 */
    private void showFileResult(final String fileName, final SecurityScanActivity.ScanResult r) {
        final String summary = (r == null) ? "" : SecurityScanActivity.summarize(r);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                new android.app.AlertDialog.Builder(H5ScanActivity.this)
                        .setTitle("扫描结果 - " + fileName)
                        .setMessage(summary)
                        .setPositiveButton("详细", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                String detail = (r == null) ? "无" : r.toDetail();
                                new android.app.AlertDialog.Builder(H5ScanActivity.this)
                                        .setTitle("详细 - " + fileName)
                                        .setMessage(detail)
                                        .setPositiveButton("关闭", null)
                                        .show();
                            }
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            }
        });
    }

    private void postProgress(final int cur, final int total, final String label) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                try {
                    webView.evaluateJavascript(
                            "window.XTSetProgress && window.XTSetProgress(" + cur + "," + total
                                    + "," + jsString(label) + ");", null);
                } catch (Throwable ignored) {}
            }
        });
    }

    private void postDone() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                try {
                    webView.evaluateJavascript(
                            "window.XTScanDone && window.XTScanDone();", null);
                } catch (Throwable ignored) {}
            }
        });
    }

    // ==================== 文件 ====================

    /** 从 Uri 拷到缓存目录（与原生 copyUriToCache 同源） */
    private File copyUriToCache(Uri uri) {
        InputStream input = null;
        OutputStream output = null;
        try {
            ContentResolver resolver = getContentResolver();
            input = resolver.openInputStream(uri);
            if (input == null) return null;
            File cacheDir = getExternalCacheDir();
            if (cacheDir == null) cacheDir = getCacheDir();
            if (cacheDir == null) return null;
            File outFile = new File(cacheDir, "temp_scan_" + System.currentTimeMillis() + ".apk");
            output = new FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = input.read(buffer)) != -1) output.write(buffer, 0, len);
            output.close();
            output = null;
            input.close();
            input = null;
            return outFile;
        } catch (Throwable e) {
            return null;
        } finally {
            try { if (output != null) output.close(); } catch (Throwable ignored) {}
            try { if (input != null) input.close(); } catch (Throwable ignored) {}
        }
    }

    /** 处理分享/打开进来的 APK（与原生 handleIncomingFile 同判据） */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        }
        if (uri == null) return;
        String path = uri.getPath();
        if (path == null) return;
        String lower = path.toLowerCase();
        if (!lower.endsWith(".apk") && !lower.endsWith(".1") && !lower.endsWith(".xtapp")) {
            toastOnUi(getString(R.string.unsupported_format));
            return;
        }
        toastOnUi(getString(R.string.received_file, uri.getLastPathSegment()));
        final Uri furi = uri;
        new Thread(new Runnable() {
            @Override
            public void run() {
                File cache = copyUriToCache(furi);
                if (cache == null) {
                    toastOnUi(getString(R.string.copy_file_fail));
                    return;
                }
                try {
                    SecurityScanActivity.ScanResult r =
                            SecurityScanActivity.scanApk(pm, cache.getAbsolutePath(), null);
                    showFileResult(cache.getName(), r);
                } catch (Throwable t) {
                    toastOnUi("扫描失败: " + t.getClass().getSimpleName());
                }
            }
        }, "h5-scan-incoming").start();
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
        public String getList() {
            return buildListJson();
        }

        @JavascriptInterface
        public boolean isScanning() {
            return scanning;
        }

        @JavascriptInterface
        public void scanAll() {
            runOnUiThread(new Runnable() {
                @Override public void run() { H5ScanActivity.this.scanAll(); }
            });
        }

        @JavascriptInterface
        public void scanOne(String pkg) {
            if (pkg == null || pkg.isEmpty()) return;
            scanOne0(pkg);
        }

        @JavascriptInterface
        public void pickApk() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/vnd.android.package-archive");
                        startActivityForResult(intent, REQUEST_SELECT_APK);
                    } catch (Throwable t) {
                        toastOnUi("无法打开文件选择器");
                    }
                }
            });
        }

        @JavascriptInterface
        public void openRules() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        startActivity(new Intent(H5ScanActivity.this, RuleManagerActivity.class));
                    } catch (Throwable ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void uninstall(final String pkg) {
            if (pkg == null || pkg.isEmpty()) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent intent = new Intent(Intent.ACTION_DELETE);
                        intent.setData(Uri.parse("package:" + pkg));
                        startActivity(intent);
                    } catch (Throwable t) {
                        toastOnUi("无法启动卸载");
                    }
                }
            });
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

    /** Bridge 内部转发，避免与同名方法混淆 */
    private void scanOne0(final String pkg) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SecurityScanActivity.ScanResult r =
                            SecurityScanActivity.scanInstalledAppResult(pm, pkg);
                    results.put(pkg, toJson(r));
                } catch (Throwable t) {
                    toastOnUi("扫描失败: " + t.getClass().getSimpleName());
                }
                refreshFrontend();
            }
        }, "h5-scan-one").start();
    }
}
