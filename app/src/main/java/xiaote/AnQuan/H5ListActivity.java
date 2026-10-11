package xiaote.AnQuan;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * H5 界面壳（名单管理）。
 *
 * 与原生 ListManagerActivity 平行，四个 tab 一一对应：
 *   sensitive  敏感 App（默认列表 + 自定义，含「已移除的默认」分组）
 *   managed    管控列表
 *   whitelist  白名单
 *   frozen     冻结应用（读系统真实状态 pm list packages -d）
 *
 * 数据来源与写入全部复用原生存储类：
 *   ProtectedPackages / VirusPackages / WhitelistPackages / FrozenPackages /
 *   SafeActionManager / AppPickerActivity
 *
 * 桥方法（JS 调 XT.xxx）：
 *   getList(tab)          返回 [{pkg, label, path, flag, divider}, ...]
 *   pick(tab)             打开应用选择器，选中后按 tab 写回对应名单
 *   remove(tab, pkg)      移除条目（敏感走 removeProtected，其余走各自 remove）
 *   restore(pkg)          恢复被移除的默认敏感包名
 *   unfreeze(pkg)         解冻并移出冻结名单
 *   toast(msg) / close()
 *
 * 包名展示与原生一致：走 formatPkg 转成斜杠路径形式。
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5ListActivity extends H5BaseActivity {

    private static final int REQ_PICK_SENSITIVE = 4001;
    private static final int REQ_PICK_MANAGED = 4002;
    private static final int REQ_PICK_WHITELIST = 4003;
    private static final int REQ_PICK_FROZEN = 4004;

    private PackageManager pm;
    /** 最近一次 pick() 的 tab，用于 onActivityResult 分发 */
    private String pendingTab = "sensitive";

    @Override
    protected void onInit() {
        pm = getPackageManager();
    }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/list.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        String pkg = null;
        try {
            pkg = data.getStringExtra(
                    xiaote.AnQuan.SmallTool.AppPicker.AppPickerActivity.EXTRA_RESULT_PACKAGE);
        } catch (Throwable ignored) {}
        if (pkg == null || pkg.trim().isEmpty()) return;
        pkg = pkg.trim();

        if (requestCode == REQ_PICK_SENSITIVE) {
            ProtectedPackages.addProtected(this, pkg);
            toastOnUi("已新增敏感应用: " + formatPkg(pkg));
        } else if (requestCode == REQ_PICK_MANAGED) {
            VirusPackages.addManagedPackage(this, pkg);
            toastOnUi("已加入管控: " + formatPkg(pkg));
        } else if (requestCode == REQ_PICK_WHITELIST) {
            WhitelistPackages.add(this, pkg);
            toastOnUi("已加入白名单: " + formatPkg(pkg));
        } else if (requestCode == REQ_PICK_FROZEN) {
            freezePackage(pkg);
            return;
        }
        refreshFrontend();
    }

    // ==================== 列表构建 ====================

    private String buildListJson(String tab) {
        JSONArray arr = new JSONArray();
        try {
            if ("sensitive".equals(tab)) {
                Set<String> all = ProtectedPackages.getProtectedPackages(this);
                List<String> sorted = new ArrayList<String>(all);
                Collections.sort(sorted);
                for (String p : sorted) arr.put(buildItem(p, "builtin"));
                Set<String> removed = ProtectedPackages.getRemovedDefaults(this);
                if (!removed.isEmpty()) {
                    JSONObject d = new JSONObject();
                    d.put("divider", "已移除的默认");
                    arr.put(d);
                    List<String> rl = new ArrayList<String>(removed);
                    Collections.sort(rl);
                    for (String p : rl) arr.put(buildItem(p, "removed"));
                }
            } else if ("managed".equals(tab)) {
                List<String> list = new ArrayList<String>(VirusPackages.getManagedPackages(this));
                Collections.sort(list);
                for (String p : list) arr.put(buildItem(p, ""));
            } else if ("whitelist".equals(tab)) {
                List<String> list = new ArrayList<String>(WhitelistPackages.get(this));
                Collections.sort(list);
                for (String p : list) arr.put(buildItem(p, ""));
            } else if ("frozen".equals(tab)) {
                List<String> list = new ArrayList<String>(
                        SafeActionManager.listDisabledPackages(this));
                Collections.sort(list);
                for (String p : list) arr.put(buildItem(p, ""));
            }
        } catch (Throwable ignored) {}
        return arr.toString();
    }

    private JSONObject buildItem(String pkg, String flag) throws Exception {
        JSONObject o = new JSONObject();
        o.put("pkg", pkg);
        o.put("label", appLabel(pkg));
        o.put("path", formatPkg(pkg));
        o.put("flag", flag == null ? "" : flag);
        return o;
    }

    /** 与原生 ListManagerActivity.formatPkg 同源：包名 → 斜杠路径 + 启动 Activity 名 */
    private String formatPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String base = pkg.replace('.', '/');
        String cls = null;
        try {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null && launch.getComponent() != null) {
                cls = launch.getComponent().getClassName();
            }
        } catch (Throwable ignored) {}
        if (cls == null || cls.isEmpty()) {
            try {
                android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, 1 | 512);
                if (pi != null && pi.activities != null) {
                    for (android.content.pm.ActivityInfo ai : pi.activities) {
                        if (ai != null && ai.name != null && !ai.name.isEmpty()) {
                            cls = ai.name;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (cls != null && !cls.isEmpty()) {
            int dot = cls.lastIndexOf('.');
            String simple = dot >= 0 ? cls.substring(dot + 1) : cls;
            if (!simple.isEmpty()) return base + "/" + simple + ".java";
        }
        return base;
    }

    /** 与原生一致：冻结/停用应用需带 MATCH_DISABLED_COMPONENTS|MATCH_UNINSTALLED_PACKAGES */
    private String appLabel(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 512 | 8192);
            return pm.getApplicationLabel(ai).toString();
        } catch (Throwable e) {
            return pkg;
        }
    }

    // ==================== 操作 ====================

    private void freezePackage(final String pkg) {
        if (WhitelistPackages.isWhitelisted(this, pkg)) {
            toastOnUi("该应用在白名单中，跳过冻结");
            return;
        }
        FrozenPackages.add(this, pkg);
        toastOnUi("已加入冻结: " + formatPkg(pkg));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = SafeActionManager.freezeOnePublic(pkg);
                SafeActionManager.appendLog(H5ListActivity.this,
                        "手动冻结 " + pkg + " -> " + (ok ? "成功" : "失败"));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (!ok) toastOnUi("冻结失败，请检查 Shizuku 权限");
                        refreshFrontend();
                    }
                });
            }
        }, "h5-freeze-one").start();
    }

    private void unfreezePackage(final String pkg) {
        FrozenPackages.remove(this, pkg);
        toastOnUi("已解冻: " + formatPkg(pkg));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = SafeActionManager.unfreezeOnePublic(pkg);
                SafeActionManager.appendLog(H5ListActivity.this,
                        "手动解冻 " + pkg + " -> " + (ok ? "成功" : "失败"));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { refreshFrontend(); }
                });
            }
        }, "h5-unfreeze-one").start();
    }

    private void toastOnUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(H5ListActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== JS Bridge ====================

    public class Bridge {

        @JavascriptInterface
        public String getList(String tab) {
            return buildListJson(tab == null ? "sensitive" : tab);
        }

        @JavascriptInterface
        public void pick(final String tab) {
            final int req;
            final String title;
            if ("managed".equals(tab)) {
                req = REQ_PICK_MANAGED;
                title = "选择管控应用";
            } else if ("whitelist".equals(tab)) {
                req = REQ_PICK_WHITELIST;
                title = "选择白名单应用";
            } else if ("frozen".equals(tab)) {
                req = REQ_PICK_FROZEN;
                title = "选择要冻结的应用";
            } else {
                req = REQ_PICK_SENSITIVE;
                title = "选择敏感应用";
            }
            pendingTab = tab == null ? "sensitive" : tab;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(H5ListActivity.this,
                                xiaote.AnQuan.SmallTool.AppPicker.AppPickerActivity.class);
                        i.putExtra("title", title);
                        startActivityForResult(i, req);
                    } catch (Throwable t) {
                        toastOnUi("无法打开应用选择器");
                    }
                }
            });
        }

        @JavascriptInterface
        public void remove(String tab, String pkg) {
            if (pkg == null || pkg.isEmpty()) return;
            try {
                if ("managed".equals(tab)) {
                    VirusPackages.removeManagedPackage(H5ListActivity.this, pkg);
                } else if ("whitelist".equals(tab)) {
                    WhitelistPackages.remove(H5ListActivity.this, pkg);
                } else {
                    ProtectedPackages.removeProtected(H5ListActivity.this, pkg);
                }
            } catch (Throwable ignored) {}
            refreshFrontend();
        }

        @JavascriptInterface
        public void restore(String pkg) {
            if (pkg == null || pkg.isEmpty()) return;
            try { ProtectedPackages.restoreProtected(H5ListActivity.this, pkg); } catch (Throwable ignored) {}
            refreshFrontend();
        }

        @JavascriptInterface
        public void unfreeze(String pkg) {
            if (pkg == null || pkg.isEmpty()) return;
            unfreezePackage(pkg);
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
