package xiaote.AnQuan;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * H5 界面壳（无障碍管理）。
 *
 * 与原生 AccessibilityManagerActivity 平行：
 *   · 列表来源相同：只列申请了无障碍服务的应用，排除自身，按包名去重；
 *   · 模式存同一份 dot_config：acc_mode_<pkg> 与 acc_mode_<pkg>_start；
 *   · 模式常量值与原生完全一致（0 禁止 / 1 允许 / 2 十分钟 / 3 锁屏禁止 / 4 智能 / -1 默认）。
 *
 * 桥方法（JS 调 XT.xxx）：
 *   getList()              返回 [{pkg, label, mode}, ...] 的 JSON 数组字符串
 *   setMode(pkg, mode)     设置单个应用策略
 *   setBatch(pkgs, mode)   批量设置（pkgs 为逗号分隔包名）
 *   toast(msg) / close()
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5AccActivity extends H5BaseActivity {

    public static final int MODE_FORBIDDEN = 0;
    public static final int MODE_ALLOW = 1;
    public static final int MODE_ALLOW_10MIN = 2;
    public static final int MODE_LOCK_FORBID = 3;
    public static final int MODE_SMART = 4;
    public static final int MODE_DEFAULT = -1;

    private static final String PREFS = "dot_config";

    private SharedPreferences prefs;

    @Override
    protected void onInit() {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/acc.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    // ==================== 列表 ====================

    /**
     * 与原生 loadItems() 同源：遍历系统的无障碍服务列表，
     * 排除自身，按包名去重，逐个读 acc_mode_<pkg>。
     */
    private String buildListJson() {
        JSONArray arr = new JSONArray();
        try {
            PackageManager pm = getPackageManager();
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            getSystemService(ACCESSIBILITY_SERVICE);
            if (am == null) return arr.toString();
            java.util.List<android.accessibilityservice.AccessibilityServiceInfo> services =
                    am.getInstalledAccessibilityServiceList();
            Set<String> added = new HashSet<String>();
            if (services != null) {
                for (android.accessibilityservice.AccessibilityServiceInfo info : services) {
                    if (info == null || info.getResolveInfo() == null) continue;
                    String pkg = info.getResolveInfo().serviceInfo.packageName;
                    if (pkg == null || pkg.equals(getPackageName())) continue;
                    if (added.contains(pkg)) continue;
                    added.add(pkg);
                    String label;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        label = pm.getApplicationLabel(ai).toString();
                    } catch (Throwable t) {
                        label = pkg;
                    }
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("label", label);
                    o.put("mode", prefs.getInt("acc_mode_" + pkg, MODE_DEFAULT));
                    arr.put(o);
                }
            }
        } catch (Throwable ignored) {}
        return arr.toString();
    }

    // ==================== 写入 ====================

    /** 与原生 saveMode() 完全一致：默认值走 remove，十分钟额外记起始时间 */
    private void saveMode(String pkg, int mode) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            SharedPreferences.Editor ed = prefs.edit();
            if (mode == MODE_DEFAULT) {
                ed.remove("acc_mode_" + pkg);
                ed.remove("acc_mode_" + pkg + "_start");
            } else {
                ed.putInt("acc_mode_" + pkg, mode);
                if (mode == MODE_ALLOW_10MIN) {
                    ed.putLong("acc_mode_" + pkg + "_start", System.currentTimeMillis());
                }
            }
            ed.apply();
        } catch (Throwable ignored) {}
    }

    private void toastOnUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(H5AccActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== JS Bridge ====================

    public class Bridge {

        @JavascriptInterface
        public String getList() {
            return buildListJson();
        }

        @JavascriptInterface
        public void setMode(String pkg, int mode) {
            saveMode(pkg, mode);
        }

        @JavascriptInterface
        public void setBatch(String pkgs, int mode) {
            if (pkgs == null) return;
            int n = 0;
            for (String p : pkgs.split(",")) {
                String v = p.trim();
                if (v.isEmpty()) continue;
                saveMode(v, mode);
                n++;
            }
            final int count = n;
            toastOnUi("已设置 " + count + " 个应用");
        }

        @JavascriptInterface
        public void toast(final String msg) {
            if (msg == null) return;
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
