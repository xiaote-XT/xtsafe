package xiaote.AnQuan;

import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * H5 界面壳（主动防护）。
 *
 * 与原生 ProtectionSettingsActivity 平行：读写同一份 dot_config，调用同一批业务类
 * （ProtectionVolumeThreshold / BlockUninstallManager / SafeActionManager / VirusPackages）。
 *
 * 桥方法（JS 调 XT.xxx）：
 *   getState()                          返回全部开关/滑块/掩码/病毒包名的 JSON
 *   setBool(key, value)                 写布尔型偏好
 *   setInt(key, value)                  写整型偏好（自动识别 tick 键）
 *   setSafeActionMask(mask)             写安全操作位掩码
 *   applyBlockUninstall(isBlock, en)    阻止卸载 / 阻止阻止卸载（互斥，写后执行一次批量）
 *   formatInterval(ticks)               与原生 formatInterval 等价的格式化
 *   getVirusPackages() / saveVirusPackages(text)
 *   toast(msg) / close()
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理、refreshFrontend 全部由基类负责。
 */
public class H5ProtectionActivity extends H5BaseActivity {

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/protect.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    // ==================== JS Bridge ====================

    public class Bridge {

        @JavascriptInterface
        public String getState() {
            JSONObject o = new JSONObject();
            try {
                o.put("block_virus", prefs().getBoolean("block_virus", true));
                o.put("notify_install", prefs().getBoolean("notify_install", true));
                o.put("force_stop_virus", prefs().getBoolean("force_stop_virus", true));
                o.put("manage_install", prefs().getBoolean("manage_install", true));
                o.put("auto_scan_install", prefs().getBoolean("auto_scan_install", false));
                o.put("smart_risk_text_notify", prefs().getBoolean("smart_risk_text_notify", true));
                o.put("volume_protect", prefs().getBoolean("volume_protect", false));
                o.put("volume_threshold_action", prefs().getBoolean("volume_threshold_action", false));
                o.put("volume_key_action", prefs().getBoolean("volume_key_action", true));
                o.put("block_overlay", prefs().getBoolean("block_overlay", true));
                o.put("acc_keep_alive", prefs().getBoolean(XTSafeCoreService.KEY_ACC_KEEP_ALIVE, false));
                o.put("acc_keep_alive_ticks", getAccKeepAliveTicks());
                o.put("acc_keep_alive_mode", prefs().getString(
                        XTSafeCoreService.KEY_ACC_KEEP_ALIVE_MODE,
                        XTSafeCoreService.ACC_KEEP_ALIVE_MODE_AUTO));
                o.put("time_rollback_action", prefs().getBoolean(
                        XTSafeCoreService.KEY_TIME_ROLLBACK_ACTION, false));
                o.put("time_rollback_minutes", getTimeRollbackMinutes());
                o.put("block_other_uninstall", BlockUninstallManager.isBatchEnabled(H5ProtectionActivity.this));
                o.put("unblock_other_blocked", BlockUninstallManager.isUnblockBatchEnabled(H5ProtectionActivity.this));
                o.put("volume_threshold", prefs().getInt("volume_threshold", 80));
                o.put("volume_check_interval", prefs().getInt("volume_check_interval", 1));
                o.put("block_other_uninstall_ticks",
                        BlockUninstallManager.getBatchIntervalTicks(H5ProtectionActivity.this));
                o.put("unblock_other_blocked_ticks",
                        BlockUninstallManager.getUnblockBatchIntervalTicks(H5ProtectionActivity.this));
                o.put("safe_actions", SafeActionManager.getMask(H5ProtectionActivity.this));
            } catch (Throwable ignored) {}
            return o.toString();
        }

        /**
         * 写布尔型偏好。
         *
         * 「防音量恶意修改」与「音量超阈值执行安全操作」是绑定关系，
         * 在这里统一兜底：开后者自动带上前者，关前者连带关掉后者。
         * 前端 onSwitch 也做了同样处理，两处都做是为了防止旧页面缓存漏刷。
         * 发生联动时回推前端，保证开关显示与存储一致。
         */
        @JavascriptInterface
        public void setBool(String key, boolean value) {
            if (key == null) return;
            boolean linked = false;
            try {
                prefs().edit().putBoolean(key, value).apply();
                if ("volume_threshold_action".equals(key) && value
                        && !prefs().getBoolean("volume_protect", false)) {
                    prefs().edit().putBoolean("volume_protect", true).apply();
                    linked = true;
                } else if ("volume_protect".equals(key) && !value
                        && prefs().getBoolean("volume_threshold_action", false)) {
                    prefs().edit().putBoolean("volume_threshold_action", false).apply();
                    linked = true;
                }
            } catch (Throwable ignored) {}
            if (linked) refreshFrontend();
        }

        @JavascriptInterface
        public void setInt(String key, int value) {
            if (key == null) return;
            try {
                if ("block_other_uninstall_ticks".equals(key)) {
                    BlockUninstallManager.setBatchIntervalTicks(H5ProtectionActivity.this, value);
                } else if ("unblock_other_blocked_ticks".equals(key)) {
                    BlockUninstallManager.setUnblockBatchIntervalTicks(H5ProtectionActivity.this, value);
                } else {
                    prefs().edit().putInt(key, value).apply();
                }
            } catch (Throwable ignored) {}
        }

        /**
         * 无障碍保活模式：auto 自动恢复（掉线才拉活）/ force 强制保活（每轮无条件写回）。
         * 只接受这两个值，其他输入直接忽略。
         */
        @JavascriptInterface
        public void setAccKeepAliveMode(String mode) {
            if (mode == null) return;
            try {
                if (!XTSafeCoreService.ACC_KEEP_ALIVE_MODE_FORCE.equals(mode)
                        && !XTSafeCoreService.ACC_KEEP_ALIVE_MODE_AUTO.equals(mode)) return;
                prefs().edit().putString(XTSafeCoreService.KEY_ACC_KEEP_ALIVE_MODE, mode).apply();
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void setSafeActionMask(int mask) {
            try { SafeActionManager.setMask(H5ProtectionActivity.this, mask); } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public String formatInterval(int ticks) {
            try { return BlockUninstallManager.formatInterval(ticks); } catch (Throwable t) { return ""; }
        }

        @JavascriptInterface
        public String getVirusPackages() {
            try { return VirusPackages.getCustomVirusPackages(H5ProtectionActivity.this); } catch (Throwable t) { return ""; }
        }

        @JavascriptInterface
        public void saveVirusPackages(String text) {
            try { VirusPackages.saveCustomVirusPackages(H5ProtectionActivity.this, text == null ? "" : text); } catch (Throwable ignored) {}
        }

        /**
         * 阻止卸载 / 阻止阻止卸载切换。两项互斥：setXxxEnabled 内部会关掉另一项，
         * 因此写入后必须回推前端刷新，避免前端开关显示与真实状态不一致。
         */
        @JavascriptInterface
        public void applyBlockUninstall(final boolean isBlock, final boolean enabled) {
            try {
                if (isBlock) BlockUninstallManager.setBatchEnabled(H5ProtectionActivity.this, enabled);
                else BlockUninstallManager.setUnblockBatchEnabled(H5ProtectionActivity.this, enabled);
            } catch (Throwable ignored) {}
            refreshFrontend();
            if (!enabled) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int[] r = null;
                    String err = null;
                    try {
                        r = BlockUninstallManager.runBatch(H5ProtectionActivity.this, isBlock);
                    } catch (Throwable t) {
                        err = t.getClass().getSimpleName();
                    }
                    String prefix = isBlock ? "手动阻止卸载：" : "手动阻止阻止卸载：";
                    if (err != null) {
                        BlockUninstallManager.appendLog(H5ProtectionActivity.this, prefix + "异常 " + err);
                    } else if (r == null) {
                        BlockUninstallManager.appendLog(H5ProtectionActivity.this, prefix + "执行失败");
                    } else {
                        BlockUninstallManager.appendLog(H5ProtectionActivity.this,
                                prefix + "成功 " + r[0] + " 个，失败 " + r[1] + " 个");
                    }
                    refreshFrontend();
                }
            }, "h5-block-batch").start();
        }

        @JavascriptInterface
        public void toast(final String msg) {
            if (msg == null) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(H5ProtectionActivity.this, msg, Toast.LENGTH_SHORT).show();
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

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("dot_config", MODE_PRIVATE);
    }

    /** 时间回拨阈值（分钟），带范围钳制 */
    private int getTimeRollbackMinutes() {
        int v = prefs().getInt(XTSafeCoreService.KEY_TIME_ROLLBACK_MINUTES,
                XTSafeCoreService.DEFAULT_TIME_ROLLBACK_MINUTES);
        if (v < XTSafeCoreService.MIN_TIME_ROLLBACK_MINUTES) v = XTSafeCoreService.MIN_TIME_ROLLBACK_MINUTES;
        if (v > XTSafeCoreService.MAX_TIME_ROLLBACK_MINUTES) v = XTSafeCoreService.MAX_TIME_ROLLBACK_MINUTES;
        return v;
    }

    /** 无障碍保活检查频率（tick，1 tick = 100ms），带范围钳制 */
    private int getAccKeepAliveTicks() {
        int v = prefs().getInt(XTSafeCoreService.KEY_ACC_KEEP_ALIVE_TICKS,
                XTSafeCoreService.DEFAULT_ACC_KEEP_ALIVE_TICKS);
        if (v < XTSafeCoreService.MIN_ACC_KEEP_ALIVE_TICKS) v = XTSafeCoreService.MIN_ACC_KEEP_ALIVE_TICKS;
        if (v > XTSafeCoreService.MAX_ACC_KEEP_ALIVE_TICKS) v = XTSafeCoreService.MAX_ACC_KEEP_ALIVE_TICKS;
        return v;
    }
}
