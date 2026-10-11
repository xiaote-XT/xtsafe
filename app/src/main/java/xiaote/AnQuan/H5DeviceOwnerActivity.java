package xiaote.AnQuan;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

import rikka.shizuku.Shizuku;

/**
 * H5 界面壳（Device Owner 管理）。
 *
 * 与原生 DeviceOwnerActivity 平行，逻辑一一对应：
 *   · 授权   dpm set-device-owner（走 Shizuku 执行）
 *   · 取消授权  dpm.clearDeviceOwnerApp() + dpm.removeActiveAdmin()
 *   · 防卸载  dpm.setUninstallBlocked / isUninstallBlocked
 *
 * 状态查询是跨进程调用，不能在 JS 桥线程同步跑：
 *   getState() 立即返回上一次缓存；真正查询在后台线程做，
 *   完成后回主线程 refreshFrontend()，前端 XTRefresh() 再拉一次。
 *
 * 授权与取消授权涉及确认弹窗和耗时 shell，全部在原生的
 * confirmGrant / confirmRevoke 里用 AlertDialog + 后台线程完成，
 * 桥只负责触发。
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5DeviceOwnerActivity extends H5BaseActivity {

    

    private DevicePolicyManager dpm;
    private ComponentName admin;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile String cachedJson = "{}";
    private volatile boolean refreshing = false;

    @Override
    protected void onInit() {
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, DeviceAdmin.class);
    }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/deviceowner.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void toastOnUi(final String msg) {
        if (msg == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(H5DeviceOwnerActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== Shizuku ====================

    private boolean shizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 同步执行一条 shell 命令，返回 exit 码与输出；失败返回原因文本 */
    private String runShizuku(String cmd) {
        if (!shizukuReady()) return "Shizuku 未授权";
        try {
            Process p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader out = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = out.readLine()) != null) sb.append(line).append('\n');
            java.io.BufferedReader err = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream()));
            while ((line = err.readLine()) != null) sb.append(line).append('\n');
            int code = p.waitFor();
            String s = sb.toString().trim();
            return "exit=" + code + (s.isEmpty() ? "" : "\n" + s);
        } catch (Throwable t) {
            return "异常: " + t.getClass().getSimpleName() + " " + t.getMessage();
        }
    }

    // ==================== 状态查询 ====================

    private void refreshState() {
        if (refreshing) return;
        refreshing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean isAdmin = false;
                boolean isOwner = false;
                Boolean blocked = null;
                try { isAdmin = dpm.isAdminActive(admin); } catch (Throwable ignored) {}
                try { isOwner = dpm.isDeviceOwnerApp(getPackageName()); } catch (Throwable ignored) {}
                if (isOwner) {
                    try { blocked = dpm.isUninstallBlocked(admin, getPackageName()); }
                    catch (Throwable ignored) {}
                }
                JSONObject o = new JSONObject();
                try {
                    o.put("pkg", getPackageName());
                    o.put("admin", admin.flattenToShortString());
                    o.put("isAdmin", isAdmin);
                    o.put("isOwner", isOwner);
                    o.put("shizukuOk", shizukuReady());
                    o.put("blocked", blocked == null ? JSONObject.NULL : blocked);
                } catch (Throwable ignored) {}
                cachedJson = o.toString();
                refreshing = false;
                refreshFrontend();
            }
        }, "h5-device-owner-check").start();
    }

    // ==================== 授权 ====================

    private void confirmGrant() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("设为 Device Owner")
                .setMessage("将执行：\n"
                        + "dpm set-device-owner " + getPackageName() + "/.DeviceAdmin\n\n"
                        + "前提：\n"
                        + "1. 已授权 Shizuku\n"
                        + "2. 设备上当前没有其他 Device Owner\n"
                        + "3. 未添加任何账户（部分 ROM 要求）\n\n"
                        + "确定继续？")
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) { doGrant(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doGrant() {
        Toast.makeText(this, "正在授权…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String out = runShizuku(
                        "dpm set-device-owner " + getPackageName() + "/.DeviceAdmin");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        boolean ok = out != null && out.startsWith("exit=0");
                        new android.app.AlertDialog.Builder(H5DeviceOwnerActivity.this)
                                .setTitle(ok ? "授权成功" : "授权失败")
                                .setMessage(out)
                                .setPositiveButton("确定", null)
                                .show();
                        refreshState();
                    }
                });
            }
        }, "h5-device-owner-grant").start();
    }

    // ==================== 取消授权 ====================

    private void confirmRevoke() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("移除 Device Owner")
                .setMessage("将调用 DevicePolicyManager.clearDeviceOwnerApp()"
                        + "放弃设备所有者身份。\n\n"
                        + "注意：不能用 dpm remove-active-admin —— AOSP 只允许移除"
                        + "test-only 管理员，对正常 Device Owner 会抛 SecurityException。\n\n"
                        + "移除后星特安全不再具有 Device Owner 权限，禁止卸载开关会一并失效。\n\n"
                        + "确定继续？")
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) { doRevoke(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 与原生 doRevoke 完全同源：
     *   1. 先解除禁止卸载
     *   2. 若是 owner，调 clearDeviceOwnerApp 自清身份
     *   3. removeActiveAdmin 移除设备管理员
     *   4. 仍残留 owner 时才用 shell 兜底
     */
    private void doRevoke() {
        Toast.makeText(this, "正在移除…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                boolean cleared = false;
                boolean adminRemoved = false;

                try { dpm.setUninstallBlocked(admin, getPackageName(), false); }
                catch (Throwable ignored) {}

                boolean isOwnerNow = false;
                try { isOwnerNow = dpm.isDeviceOwnerApp(getPackageName()); }
                catch (Throwable ignored) {}
                if (isOwnerNow) {
                    try {
                        dpm.clearDeviceOwnerApp(getPackageName());
                        cleared = true;
                    } catch (Throwable t) {
                        err = "clearDeviceOwnerApp: " + t.getClass().getSimpleName()
                                + " " + t.getMessage();
                    }
                } else {
                    cleared = true;
                }

                try {
                    if (dpm.isAdminActive(admin)) {
                        dpm.removeActiveAdmin(admin);
                        adminRemoved = true;
                    } else {
                        adminRemoved = true;
                    }
                } catch (Throwable t) {
                    String s = "removeActiveAdmin: " + t.getClass().getSimpleName()
                            + " " + t.getMessage();
                    err = (err == null ? s : err + " | " + s);
                }

                boolean stillOwner = false;
                try { stillOwner = dpm.isDeviceOwnerApp(getPackageName()); }
                catch (Throwable ignored) {}
                String fallback = null;
                if (stillOwner && shizukuReady()) {
                    fallback = runShizuku(
                            "dpm remove-active-admin " + getPackageName() + "/.DeviceAdmin");
                }

                final boolean fCleared = cleared;
                final String fErr = err;
                final String fFallback = fallback;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        StringBuilder sb = new StringBuilder();
                        sb.append(fCleared
                                ? "clearDeviceOwnerApp() 已调用，Device Owner 身份已放弃"
                                : "clearDeviceOwnerApp() 失败");
                        if (fErr != null) sb.append("\n").append(fErr);
                        if (fFallback != null) sb.append("\n\nShell 兜底: \n").append(fFallback);
                        new android.app.AlertDialog.Builder(H5DeviceOwnerActivity.this)
                                .setTitle(fCleared ? "已移除" : "移除失败")
                                .setMessage(sb.toString())
                                .setPositiveButton("确定", null)
                                .show();
                        refreshState();
                    }
                });
            }
        }, "h5-device-owner-revoke").start();
    }

    // ==================== 禁止卸载 ====================

    private void applyUninstallBlock(final boolean blocked) {
        final String pkg = getPackageName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                boolean ok = false;
                try {
                    dpm.setUninstallBlocked(admin, pkg, blocked);
                    ok = true;
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                Boolean actual = null;
                if (ok) {
                    try { actual = dpm.isUninstallBlocked(admin, pkg); } catch (Throwable ignored) {}
                }
                if (!ok) {
                    toastOnUi("设置失败: " + err);
                } else if (actual != null && actual != blocked) {
                    toastOnUi("设置未生效，实际状态: " + (actual ? "已开启" : "未开启"));
                } else {
                    toastOnUi(blocked ? "已禁止卸载星特安全" : "已允许卸载星特安全");
                }
                refreshState();
            }
        }, "h5-device-owner-block").start();
    }

    // ==================== JS Bridge ====================

    public class Bridge {

        @JavascriptInterface
        public String getState() {
            return cachedJson;
        }

        @JavascriptInterface
        public void confirmGrant() {
            runOnUiThread(new Runnable() {
                @Override public void run() { H5DeviceOwnerActivity.this.confirmGrant(); }
            });
        }

        @JavascriptInterface
        public void confirmRevoke() {
            runOnUiThread(new Runnable() {
                @Override public void run() { H5DeviceOwnerActivity.this.confirmRevoke(); }
            });
        }

        @JavascriptInterface
        public void applyUninstallBlock(boolean blocked) {
            H5DeviceOwnerActivity.this.applyUninstallBlock(blocked);
        }

        @JavascriptInterface
        public void refreshStatus() {
            refreshState();
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
