package xiaote.AnQuan;

import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

import xiaote.dhizukutool.Dhizuku;
import xiaote.dhizukutool.DhizukuDpm;
import xiaote.dhizukutool.DhizukuRequestPermissionListener;

/**
 * H5 界面壳（Dhizuku 增强）。
 *
 * 与原生 DhizukuActivity 平行，逻辑一一对应：
 *   · 状态查询走同一套 Dhizuku.init / isPermissionGranted /
 *     DhizukuDpm.isUninstallBlockSupported / isUninstallBlocked；
 *   · 授权走 Dhizuku.requestPermission；
 *   · 防卸载走 DhizukuDpm.setUninstallBlocked，写完回读确认。
 *
 * 状态查询是跨进程调用，不能在 JS 桥线程同步跑，因此：
 *   getState() 立即返回上一次缓存的结果；
 *   真正的查询在后台线程做，完成后回主线程调 refreshFrontend()，
 *   前端 XTRefresh() 再拉一次 getState()。
 *
 * 密码锁：原生 DhizukuActivity 继承 Activity 刻意不带锁（授权会跳转到
 * Dhizuku 的页面，回来自动弹密码会打断流程）。本类继承 H5BaseActivity
 * （即 BaseActivity），因此在 onResume 里先 markSessionUnlocked()，
 * 效果与原生一致，同时复用基类的 WebView / 状态栏 / 深色注入。
 */
public class H5DhizukuActivity extends H5BaseActivity {

    /** 与原生 DhizukuActivity 一致：授权/跳转期间不弹密码锁 */
    @Override
    protected boolean lockEnabled() { return false; }

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 最近一次查询结果的 JSON，getState() 直接返回它 */
    private volatile String cachedJson = "{}";
    private volatile boolean refreshing = false;

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/dhizuku.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    @Override
    protected void onResume() {
        // 与原生一致：本页不带密码锁，避免授权跳转回来被密码页打断
        try { BaseActivity.markSessionUnlocked(); } catch (Throwable ignored) {}
        super.onResume();
        refreshState();
    }

    private void toastOnUi(final String msg) {
        if (msg == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(H5DhizukuActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== 状态查询 ====================

    /**
     * 后台线程查询 Dhizuku 状态，结果写入 cachedJson 并回推前端。
     * 防重入：上一次没跑完时直接丢弃本次请求。
     */
    private void refreshState() {
        if (refreshing) return;
        refreshing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject o = new JSONObject();
                boolean initOk = false;
                boolean granted = false;
                boolean supported = false;
                boolean exempt = false;
                String version = null;
                String owner = null;
                String err = null;
                String diag = null;
                Boolean blocked = null;

                try {
                    initOk = Dhizuku.init(H5DhizukuActivity.this);
                    if (!initOk) err = "Dhizuku 未安装，或未设为 Device Owner";
                } catch (Throwable t) {
                    err = "init 异常: " + t.getClass().getSimpleName() + " " + t.getMessage();
                }

                try { exempt = DhizukuDpm.isHiddenApiExempt(); } catch (Throwable ignored) {}

                if (initOk) {
                    try {
                        granted = Dhizuku.isPermissionGranted();
                    } catch (Throwable t) {
                        err = "查询权限异常: " + t.getClass().getSimpleName() + " " + t.getMessage();
                    }
                    try { owner = Dhizuku.getOwnerPackageName(); } catch (Throwable ignored) {}
                    if (granted) {
                        try { version = Dhizuku.getVersionName(); } catch (Throwable ignored) {}
                        try {
                            supported = DhizukuDpm.isUninstallBlockSupported(H5DhizukuActivity.this);
                        } catch (Throwable ignored) {}
                        if (!supported) {
                            try { diag = DhizukuDpm.getReflectDiagnostic(); } catch (Throwable ignored) {}
                        }
                        if (supported) {
                            try {
                                blocked = DhizukuDpm.isUninstallBlocked(
                                        H5DhizukuActivity.this,
                                        H5DhizukuActivity.this.getPackageName());
                            } catch (Throwable ignored) {}
                        }
                    }
                }

                try {
                    o.put("initOk", initOk);
                    o.put("granted", granted);
                    o.put("supported", supported);
                    o.put("exempt", exempt);
                    o.put("version", version == null ? JSONObject.NULL : version);
                    o.put("owner", owner == null ? JSONObject.NULL : owner);
                    o.put("err", err == null ? JSONObject.NULL : err);
                    o.put("diag", diag == null ? JSONObject.NULL : diag);
                    o.put("blocked", blocked == null ? JSONObject.NULL : blocked);
                } catch (Throwable ignored) {}

                cachedJson = o.toString();
                refreshing = false;
                refreshFrontend();
            }
        }, "h5-dhizuku-check").start();
    }

    // ==================== 防卸载 ====================

    private void applyUninstallBlock(final boolean blocked) {
        final String pkg = getPackageName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String err = null;
                Boolean actual = null;
                try {
                    ok = DhizukuDpm.setUninstallBlocked(H5DhizukuActivity.this, pkg, blocked);
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                if (ok) {
                    try {
                        actual = DhizukuDpm.isUninstallBlocked(H5DhizukuActivity.this, pkg);
                    } catch (Throwable ignored) {}
                }

                final boolean fOk = ok;
                final String fErr = err;
                final Boolean fActual = actual;
                if (!fOk) {
                    toastOnUi("设置失败" + (fErr == null || fErr.isEmpty() ? "" : ": " + fErr));
                } else if (fActual != null && fActual != blocked) {
                    toastOnUi("设置未生效，实际状态: " + (fActual ? "已开启" : "未开启"));
                } else {
                    toastOnUi(blocked ? "已禁止卸载星特安全" : "已允许卸载星特安全");
                }
                refreshState();
            }
        }, "h5-dhizuku-block").start();
    }

    // ==================== 授权 ====================

    private void requestPermission() {
        try {
            Dhizuku.requestPermission(new DhizukuRequestPermissionListener() {
                @Override
                public void onRequestPermission(final int requestCode) throws RemoteException {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            if (requestCode == 0) {
                                toastOnUi("Dhizuku 权限已授予");
                            } else {
                                toastOnUi("Dhizuku 授权未通过 (" + requestCode + ")");
                            }
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() { refreshState(); }
                            }, 600L);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            toastOnUi("无法拉起授权页: " + t.getMessage());
        }
    }

    // ==================== JS Bridge ====================

    public class Bridge {

        @JavascriptInterface
        public String getState() {
            return cachedJson;
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(new Runnable() {
                @Override public void run() { H5DhizukuActivity.this.requestPermission(); }
            });
        }

        @JavascriptInterface
        public void applyUninstallBlock(boolean blocked) {
            H5DhizukuActivity.this.applyUninstallBlock(blocked);
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
