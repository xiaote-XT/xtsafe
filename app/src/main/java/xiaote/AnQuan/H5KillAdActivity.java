package xiaote.AnQuan;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import xiaote.AnQuan.SmallTool.KillAd.KillAdLogger;
import xiaote.AnQuan.SmallTool.KillAd.KillAdRuleUpdater;
import xiaote.AnQuan.SmallTool.KillAd.KillAdRules;
import xiaote.AnQuan.SmallTool.KillAd.KillAdVpnService;

/**
 * H5 界面壳（广告拦截）。
 *
 * 与原生 KillAdActivity 平行，状态与操作完全同源：
 *   · 运行状态、暂停状态、拦截计数读 KillAdVpnService 的静态方法与 prefs
 *   · 规则条数读 KillAdRules.getRules
 *   · 在线规则源、上游 DNS、自定义域名读写 KillAdRules / KillAdRuleUpdater
 *   · 日志路径与大小读 KillAdLogger
 *
 * 开关行为与原生一致：
 *   开启 → 先 VpnService.prepare 请求系统授权 → onActivityResult 里启动前台服务
 *   关闭 → 发 ACTION_STOP 并 stopService，清掉三个状态标记
 *   暂停 / 恢复 → 发 ACTION_PAUSE / ACTION_RESUME
 *
 * 首次进入的提示弹窗与原生同文案，「不再提示」写 killad_notice_dont_show。
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5KillAdActivity extends H5BaseActivity {

    /** 与原生 KillAdActivity 一致：VPN 授权/系统跳转期间不弹密码锁 */
    @Override
    protected boolean lockEnabled() { return false; }

    private static final int REQUEST_VPN = 2001;

    private android.content.SharedPreferences prefs;

    @Override
    protected void onInit() {
        prefs = getSharedPreferences(KillAdRules.PREFS, MODE_PRIVATE);
    }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/killad.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFrontend();
        showNoticeIfNeeded();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_VPN) return;
        if (resultCode == RESULT_OK) {
            startVpnService();
        } else {
            toastOnUi(getString(R.string.killad_vpn_denied));
            refreshFrontend();
        }
    }

    private void toastOnUi(final String msg) {
        if (msg == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(H5KillAdActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 首次进入提示。与原生同文案，不再提示写入 dot_config 之外由原生用的 key。
     * 用原生 prefs（KillAdRules.PREFS）里的 killad_notice_dont_show。
     */
    private void showNoticeIfNeeded() {
        try {
            if (prefs.getBoolean("killad_notice_dont_show", false)) return;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("星特安全内置的广告拦截功能为简单轻量实现，局限性较大，如果你希望获得完整的广告拦截，建议下载GPL-3.0开源的BlockAds，注意，部分恶意修改者会提供不开源的版本，并将应用改成自己的名字，这个可能有较高风险")
                    .setPositiveButton("确定", null)
                    .setNegativeButton("不再提示", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            prefs.edit().putBoolean("killad_notice_dont_show", true).apply();
                        }
                    })
                    .setNeutralButton("下载BlockAds", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            try {
                                Intent i = new Intent(Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://github.com/pass-with-high-score/blockads-android/releases"));
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i);
                            } catch (Exception e) {
                                toastOnUi("无法打开链接");
                            }
                        }
                    })
                    .setCancelable(true)
                    .show();
        } catch (Throwable ignored) {}
    }

    // ==================== 开关 ====================

    /** 与原生 requestStart 一致：先 VpnService.prepare，有授权页就跳，否则直接启动 */
    private void requestStart() {
        try {
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) {
                startActivityForResult(prepare, REQUEST_VPN);
                return;
            }
        } catch (Throwable ignored) {}
        startVpnService();
    }

    private void startVpnService() {
        try {
            Intent i = new Intent(this, KillAdVpnService.class);
            i.setAction(KillAdVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            prefs.edit().putBoolean(KillAdRules.KEY_ENABLED, true).apply();
            toastOnUi(getString(R.string.killad_starting));
        } catch (Throwable e) {
            toastOnUi(getString(R.string.killad_start_fail));
        }
        refreshFrontend();
        webView.postDelayed(new Runnable() {
            @Override public void run() { refreshFrontend(); }
        }, 800L);
    }

    /** 与原生 stopVpnService 一致：发 ACTION_STOP + stopService，清三个标记 */
    private void stopVpnService() {
        try {
            Intent i = new Intent(this, KillAdVpnService.class);
            i.setAction(KillAdVpnService.ACTION_STOP);
            startService(i);
        } catch (Throwable ignored) {}
        try { stopService(new Intent(this, KillAdVpnService.class)); } catch (Throwable ignored) {}
        prefs.edit().putBoolean(KillAdRules.KEY_ENABLED, false)
                .putBoolean("killad_running", false)
                .putBoolean(KillAdVpnService.KEY_PAUSED, false).apply();
        toastOnUi(getString(R.string.killad_stopped));
        refreshFrontend();
        webView.postDelayed(new Runnable() {
            @Override public void run() { refreshFrontend(); }
        }, 500L);
    }

    private void togglePause() {
        if (!KillAdVpnService.isRunning(this)) {
            toastOnUi(getString(R.string.killad_status_stopped));
            return;
        }
        boolean paused = KillAdVpnService.isPaused(this);
        Intent i = new Intent(this, KillAdVpnService.class);
        i.setAction(paused ? KillAdVpnService.ACTION_RESUME : KillAdVpnService.ACTION_PAUSE);
        try { startService(i); } catch (Throwable ignored) {}
        webView.postDelayed(new Runnable() {
            @Override public void run() { refreshFrontend(); }
        }, 400L);
    }

    // ==================== 状态 ====================

    private String buildStateJson() {
        JSONObject o = new JSONObject();
        try {
            boolean running = KillAdVpnService.isRunning(this);
            boolean paused = running && KillAdVpnService.isPaused(this);
            o.put("running", running);
            o.put("paused", paused);
            o.put("statusText", getString(!running
                    ? R.string.killad_status_stopped
                    : (paused ? R.string.killad_status_paused : R.string.killad_status_running)));

            int blocked = prefs.getInt(KillAdVpnService.KEY_BLOCKED, 0);
            int total = prefs.getInt(KillAdVpnService.KEY_TOTAL, 0);
            o.put("statsText", getString(R.string.killad_stats,
                    KillAdVpnService.formatCount(blocked),
                    KillAdVpnService.formatCount(total)));

            int count = 0;
            try { count = KillAdRules.getRules(this).size(); } catch (Throwable ignored) {}
            o.put("rulesCountText", getString(R.string.killad_rules_count, count));

            long last = KillAdRuleUpdater.getLastUpdateTime(this);
            boolean fileExists = new java.io.File(getFilesDir(), KillAdRuleUpdater.RULES_FILE).exists();
            String ruleInfo;
            if (fileExists && !KillAdRuleUpdater.hasLocalRules(this)) {
                ruleInfo = getString(R.string.killad_rule_reupdate);
            } else if (last <= 0L) {
                ruleInfo = getString(R.string.killad_rule_never);
            } else {
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(last));
                ruleInfo = getString(R.string.killad_rule_last, time, KillAdRuleUpdater.getLastCount(this));
            }
            o.put("ruleInfoText", ruleInfo);

            String path = KillAdLogger.logPath(this);
            long size = KillAdLogger.size(this);
            o.put("logInfoText", getString(R.string.killad_log_info,
                    path.isEmpty() ? "-" : path,
                    KillAdVpnService.formatBytes(size)));

            o.put("ruleUrl", KillAdRuleUpdater.getRuleUrl(this));
            o.put("upstream", KillAdRules.getUpstream(this));
            o.put("customDomains", KillAdRules.getCustomDomains(this));
            o.put("showNotice", !prefs.getBoolean("killad_notice_dont_show", false));
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
        public void toggle(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (on) requestStart();
                    else stopVpnService();
                }
            });
        }

        @JavascriptInterface
        public void togglePause() {
            runOnUiThread(new Runnable() {
                @Override public void run() { H5KillAdActivity.this.togglePause(); }
            });
        }

        @JavascriptInterface
        public void saveRuleUrl(String url) {
            try {
                KillAdRuleUpdater.setRuleUrl(H5KillAdActivity.this, url == null ? "" : url);
                toastOnUi(getString(R.string.killad_rule_source_saved));
            } catch (Throwable ignored) {}
            refreshFrontend();
        }

        @JavascriptInterface
        public void updateRules() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    toastOnUi(getString(R.string.killad_rule_updating));
                    KillAdRuleUpdater.updateAsync(H5KillAdActivity.this, new KillAdRuleUpdater.Callback() {
                        @Override
                        public void onDone(boolean ok, int count, String message) {
                            if (ok) {
                                toastOnUi(getString(R.string.killad_rule_update_ok, count));
                            } else {
                                toastOnUi(getString(R.string.killad_rule_update_fail, message));
                            }
                            refreshFrontend();
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public void saveUpstream(String host) {
            try {
                String v = (host == null) ? "" : host.trim();
                if (v.isEmpty()) v = KillAdRules.DEFAULT_UPSTREAM;
                KillAdRules.setUpstream(H5KillAdActivity.this, v);
                toastOnUi(getString(R.string.killad_upstream_saved));
            } catch (Throwable ignored) {}
            refreshFrontend();
        }

        @JavascriptInterface
        public void saveCustomDomains(String text) {
            try {
                KillAdRules.setCustomDomains(H5KillAdActivity.this, text == null ? "" : text);
                toastOnUi(getString(R.string.killad_custom_saved));
            } catch (Throwable ignored) {}
            refreshFrontend();
        }

        @JavascriptInterface
        public void clearLog() {
            try {
                KillAdLogger.clear(H5KillAdActivity.this);
                toastOnUi(getString(R.string.killad_log_cleared));
            } catch (Throwable ignored) {}
            refreshFrontend();
        }

        @JavascriptInterface
        public void showNotice() {
            runOnUiThread(new Runnable() {
                @Override public void run() { showNoticeIfNeeded(); }
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
}
