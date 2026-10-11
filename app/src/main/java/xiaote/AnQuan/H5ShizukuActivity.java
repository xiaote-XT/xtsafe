package xiaote.AnQuan;

import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * H5 界面壳（Shizuku 增强）。
 *
 * 与原生 ShizukuActivity 平行，功能一一对应：
 *   事务号 TID（用户覆盖 / 清除）
 *   自身阻止卸载
 *   阻止卸载 / 阻止阻止卸载（互斥，各自带检查频率）
 *
 * 所有写入都走 BlockUninstallManager，与原生共用同一份存储与同一套批量命令。
 *
 * 桥方法（JS 调 XT.xxx）：
 *   getState()                     返回 ready/tid/self_blocked/batch_on/unblock_on/
 *                                  各自 ticks 的 JSON
 *   setTid(tid)                    tid<=0 表示清除覆盖，回到 SDK 推导
 *   applySelfBlock(enabled)        阻止卸载星特安全自身
 *   applyBatch(isBlock, enabled)   阻止卸载 / 阻止阻止卸载；互斥，写后立刻回推前端
 *   setBatchTicks(isBlock, ticks)  写检查频率
 *   formatInterval(ticks)          与原生 formatInterval 等价
 *   refreshStatus()                让前端重新拉一次状态
 *   toast(msg) / close()
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5ShizukuActivity extends H5BaseActivity {

    /** 与原生 ShizukuActivity 一致：授权/执行期间不弹密码锁 */
    @Override
    protected boolean lockEnabled() { return false; }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/shizuku.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    private void toastOnUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(H5ShizukuActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ==================== JS Bridge ====================

    public class Bridge {

        @JavascriptInterface
        public String getState() {
            JSONObject o = new JSONObject();
            try {
                o.put("ready", BlockUninstallManager.isShizukuReady());
                o.put("tid", BlockUninstallManager.getTid(H5ShizukuActivity.this));

                Boolean real = BlockUninstallManager.readBlocked(
                        H5ShizukuActivity.this, H5ShizukuActivity.this.getPackageName());
                if (real != null) {
                    o.put("self_blocked", real.booleanValue());
                } else {
                    o.put("self_blocked", BlockUninstallManager.isSelfBlocked(H5ShizukuActivity.this));
                }

                o.put("batch_on", BlockUninstallManager.isBatchEnabled(H5ShizukuActivity.this));
                o.put("unblock_on", BlockUninstallManager.isUnblockBatchEnabled(H5ShizukuActivity.this));
                o.put("batch_ticks",
                        BlockUninstallManager.getBatchIntervalTicks(H5ShizukuActivity.this));
                o.put("unblock_ticks",
                        BlockUninstallManager.getUnblockBatchIntervalTicks(H5ShizukuActivity.this));
            } catch (Throwable ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public void setTid(int tid) {
            try {
                BlockUninstallManager.setTidOverride(H5ShizukuActivity.this, tid > 0 ? tid : 0);
            } catch (Throwable ignored) {}
            refreshFrontend();
        }

        @JavascriptInterface
        public void applySelfBlock(final boolean enabled) {
            if (BlockUninstallManager.getTid(H5ShizukuActivity.this) <= 0) {
                toastOnUi("事务号无效");
                refreshFrontend();
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final boolean ok = BlockUninstallManager.setBlocked(
                            H5ShizukuActivity.this,
                            H5ShizukuActivity.this.getPackageName(), enabled);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            if (ok) {
                                BlockUninstallManager.setSelfBlocked(H5ShizukuActivity.this, enabled);
                                toastOnUi(enabled ? "已阻止卸载星特安全" : "已允许卸载星特安全");
                            } else {
                                toastOnUi("执行未成功（检查事务号/权限/是否已激活设备所有者）");
                            }
                            refreshFrontend();
                        }
                    });
                }
            }, "h5-shizuku-self").start();
        }

        /**
         * 阻止卸载 / 阻止阻止卸载切换。两项互斥，写入后立刻回推前端，
         * 保证开关显示与存储真实值一致；开启时后台跑一次批量并写日志。
         */
        @JavascriptInterface
        public void applyBatch(final boolean isBlock, final boolean enabled) {
            try {
                if (isBlock) BlockUninstallManager.setBatchEnabled(H5ShizukuActivity.this, enabled);
                else BlockUninstallManager.setUnblockBatchEnabled(H5ShizukuActivity.this, enabled);
            } catch (Throwable ignored) {}
            refreshFrontend();
            if (!enabled) return;

            if (!BlockUninstallManager.isShizukuReady()) {
                toastOnUi("Shizuku 未就绪");
                return;
            }
            if (BlockUninstallManager.getTid(H5ShizukuActivity.this) <= 0) {
                toastOnUi("事务号无效");
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int[] r = null;
                    String err = null;
                    try {
                        r = BlockUninstallManager.runBatch(H5ShizukuActivity.this, isBlock);
                    } catch (Throwable t) {
                        err = t.getClass().getSimpleName();
                    }
                    String prefix = isBlock ? "手动阻止卸载：" : "手动阻止阻止卸载：";
                    if (err != null) {
                        BlockUninstallManager.appendLog(H5ShizukuActivity.this, prefix + "异常 " + err);
                    } else if (r == null) {
                        BlockUninstallManager.appendLog(H5ShizukuActivity.this, prefix + "执行失败");
                    } else {
                        BlockUninstallManager.appendLog(H5ShizukuActivity.this,
                                prefix + "成功 " + r[0] + " 个，失败 " + r[1] + " 个");
                    }
                    final int[] result = r;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            if (result == null) {
                                toastOnUi("执行失败");
                            } else {
                                toastOnUi((isBlock ? "已阻止 " : "已解除 ")
                                        + result[0] + " 个，失败 " + result[1]);
                            }
                            refreshFrontend();
                        }
                    });
                }
            }, "h5-shizuku-batch").start();
        }

        @JavascriptInterface
        public void setBatchTicks(boolean isBlock, int ticks) {
            try {
                if (isBlock) BlockUninstallManager.setBatchIntervalTicks(H5ShizukuActivity.this, ticks);
                else BlockUninstallManager.setUnblockBatchIntervalTicks(H5ShizukuActivity.this, ticks);
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public String formatInterval(int ticks) {
            try { return BlockUninstallManager.formatInterval(ticks); } catch (Throwable t) { return ""; }
        }

        @JavascriptInterface
        public void refreshStatus() {
            refreshFrontend();
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
