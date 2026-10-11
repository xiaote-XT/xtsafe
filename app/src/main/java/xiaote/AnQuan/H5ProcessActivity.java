package xiaote.AnQuan;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import xiaote.AnQuan.SmallTool.ProcessManager.ProcessItem;
import xiaote.AnQuan.SmallTool.ProcessManager.ProcessScanner;

/**
 * H5 界面壳（后台进程管理）。
 *
 * 与原生 ProcessManagerActivity 平行，安全约束完全一致：
 *   · 扫描走 ProcessScanner.scan()（Shizuku ps 管道，按内存降序）；
 *   · 屏蔽本应用自身进程（xiaote.AnQuan / xiaote.AnQuan:xxx）；
 *   · com.android.systemui 仅列出，不提供任何操作；
 *   · 系统应用与未知进程（system_server、surfaceflinger 等）操作前必须二次确认，
 *     该确认由前端弹层负责，桥侧只执行已确认的调用；
 *   · 查不到 ApplicationInfo 时 isSystem=true + isUnknown=true（与原生同一保守策略）。
 *
 * 扫描是耗时跨进程操作，不能在 JS 桥线程同步跑：
 *   getList(filter) 立即返回上一次缓存；
 *   refresh() 触发后台扫描，完成后回主线程 refreshFrontend()，
 *   前端 XTRefresh() 再拉一次 getList。
 *
 * 桥方法（JS 调 XT.xxx）：
 *   getList(filter)          filter: 0=全部 1=用户 2=系统
 *   refresh()                触发一次后台扫描
 *   openApp(pkg)             monkey 拉起 LAUNCHER，失败回退 am start
 *   stopApp(pkg)             am force-stop（Shizuku → Root → shell）
 *   toast(msg) / close()
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5ProcessActivity extends H5BaseActivity {

    /** 与原生 ProcessManagerActivity 一致：扫描/操作期间不弹密码锁 */
    @Override
    protected boolean lockEnabled() { return false; }

    /** 仅列出、禁止任何操作的系统界面包名 */
    private static final String SYSTEM_UI_PKG = "com.android.systemui";

    private PackageManager pm;

    /** 最近一次扫描结果的 JSON 数组字符串 */
    private volatile String cachedJson = "[]";
    private volatile boolean scanning = false;
    private volatile boolean everScanned = false;

    @Override
    protected void onInit() {
        pm = getPackageManager();
    }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/process.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void toastOnUi(final String msg) {
        if (msg == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(H5ProcessActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== 扫描 ====================

    /**
     * 后台扫描进程，结果整批写入 cachedJson 并回推前端。
     * 防重入：上一次没跑完时直接丢弃本次请求。
     */
    private void refreshList() {
        if (scanning) return;
        if (!ProcessScanner.isAvailable()) {
            cachedJson = "[]";
            refreshFrontend();
            return;
        }
        scanning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONArray arr = new JSONArray();
                try {
                    List<ProcessItem> list = ProcessScanner.scan();
                    String selfPkg = getPackageName();
                    if (list != null) {
                        for (ProcessItem it : list) {
                            if (it == null || it.name == null) continue;
                            // 屏蔽自身进程，避免误操作自己
                            if (it.name.equals(selfPkg) || it.name.startsWith(selfPkg + ":")) continue;
                            fillAppInfo(it);
                            JSONObject o = new JSONObject();
                            o.put("name", it.name);
                            o.put("pid", it.pid);
                            o.put("rssKb", it.rssKb);
                            o.put("memText", formatMem(it.rssKb));
                            o.put("appName", it.appName == null ? "" : it.appName);
                            o.put("pkg", it.packageName == null ? "" : it.packageName);
                            o.put("isSystem", it.isSystem);
                            o.put("isSystemUi", it.isSystemUi);
                            o.put("isUnknown", it.isUnknown);
                            arr.put(o);
                        }
                    }
                } catch (Throwable ignored) {}
                cachedJson = arr.toString();
                everScanned = true;
                scanning = false;
                refreshFrontend();
            }
        }, "h5-pm-scan").start();
    }

    /**
     * 取应用名并判定系统应用 / 系统界面 / 未知进程。
     * 与原生 fillAppInfo 完全同源：查不到 ApplicationInfo 一律按
     * isSystem=true + isUnknown=true 保守处理，禁止直接操作。
     */
    private void fillAppInfo(ProcessItem it) {
        if (it == null) return;
        String pkg = it.packageName;
        if (pkg == null || pkg.isEmpty()) return;
        it.isSystemUi = SYSTEM_UI_PKG.equals(pkg);
        it.isUnknown = false;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            it.appName = pm.getApplicationLabel(ai).toString();
            it.isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Throwable e) {
            it.appName = "";
            it.isSystem = true;
            it.isUnknown = true;
        }
        if (it.isSystemUi) it.isSystem = true;
    }

    /** 格式化常驻内存（ps 的 RSS 单位是 KB），与原生 formatMem 等价 */
    static String formatMem(int rssKb) {
        if (rssKb <= 0) return "-";
        long bytes = (long) rssKb * 1024L;
        if (bytes < 1024L * 1024L) {
            double kb = rssKb / 1024.0;
            return kb < 1 ? rssKb + "KB" : String.format(java.util.Locale.US, "%.1fMB", kb);
        }
        return String.format(java.util.Locale.US, "%.0fMB", bytes / 1048576.0);
    }

    // ==================== 操作 ====================

    /** 打开应用：优先 monkey 拉起 LAUNCHER，失败回退 am start（与原生同源） */
    private void openApp(final String pkg) {
        Intent launch = null;
        try {
            launch = pm.getLaunchIntentForPackage(pkg);
        } catch (Throwable ignored) {}
        if (launch == null) {
            toastOnUi(getString(R.string.pm_open_fail));
            return;
        }
        final Intent fallback = launch;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = ShellExecutor.execShizukuSync(new String[]{"sh", "-c",
                        "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1"});
                if (ok) {
                    toastOnUi(getString(R.string.pm_opened, pkg));
                    return;
                }
                try {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(fallback);
                } catch (Throwable t) {
                    toastOnUi(getString(R.string.pm_open_fail));
                }
            }
        }, "h5-pm-open").start();
    }

    /** 强制停止：Shizuku → Root → 普通 shell，完成后重扫 */
    private void stopApp(final String pkg) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = ShellExecutor.forceStopApp(pkg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (ok) {
                            toastOnUi(getString(R.string.pm_stopped, pkg));
                            refreshList();
                        } else {
                            toastOnUi(getString(R.string.pm_stop_fail));
                        }
                    }
                });
            }
        }, "h5-pm-stop").start();
    }

    // ==================== JS Bridge ====================

    public class Bridge {

        @JavascriptInterface
        public String getList(int filter) {
            // filter: 0=全部 1=用户 2=系统
            // 系统侧直接按过滤条件裁剪，前端拿到的就是最终列表。
            if (filter == 0) return cachedJson;
            JSONArray out = new JSONArray();
            try {
                JSONArray all = new JSONArray(cachedJson);
                for (int i = 0; i < all.length(); i++) {
                    JSONObject o = all.optJSONObject(i);
                    if (o == null) continue;
                    boolean isSystem = o.optBoolean("isSystem", false);
                    if (filter == 1 && isSystem) continue;
                    if (filter == 2 && !isSystem) continue;
                    out.put(o);
                }
            } catch (Throwable ignored) {}
            return out.toString();
        }

        @JavascriptInterface
        public boolean isAvailable() {
            try { return ProcessScanner.isAvailable(); } catch (Throwable t) { return false; }
        }

        @JavascriptInterface
        public boolean isScanning() {
            return scanning;
        }

        @JavascriptInterface
        public boolean hasScanned() {
            return everScanned;
        }

        @JavascriptInterface
        public void refresh() {
            refreshList();
        }

        @JavascriptInterface
        public void openApp(String pkg) {
            if (pkg == null || pkg.isEmpty()) return;
            H5ProcessActivity.this.openApp(pkg);
        }

        @JavascriptInterface
        public void stopApp(String pkg) {
            if (pkg == null || pkg.isEmpty()) return;
            H5ProcessActivity.this.stopApp(pkg);
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
}
