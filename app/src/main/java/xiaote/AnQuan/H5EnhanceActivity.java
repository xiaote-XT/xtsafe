package xiaote.AnQuan;

import android.content.Intent;
import android.webkit.JavascriptInterface;

/**
 * H5 界面壳（权限增强）。
 *
 * 与原生 PermissionManager.EnhanceActivity 平行，三个入口一一对应：
 *   openPage('shizuku')      Shizuku 增强
 *   openPage('dhizuku')      Dhizuku 增强
 *   openPage('deviceowner')  Device Owner 增强
 *
 * 纯跳转页，无状态回读，无偏好读写。
 * 继承 H5BaseActivity，主题 / 状态栏 / 深色注入全部复用基类。
 * 不接 SwipeBackHelper：侧滑返回会与 WebView 横滑抢事件。
 */
public class H5EnhanceActivity extends H5BaseActivity {

    /** 与原生 EnhanceActivity 一致：纯跳转页，不弹密码锁 */
    @Override
    protected boolean lockEnabled() { return false; }

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/enhance.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    public class Bridge {

        @JavascriptInterface
        public void openPage(final String key) {
            if (key == null) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        if ("shizuku".equals(key)) {
                            startActivity(new Intent(H5EnhanceActivity.this, H5ShizukuActivity.class));
                        } else if ("dhizuku".equals(key)) {
                            startActivity(new Intent(H5EnhanceActivity.this, H5DhizukuActivity.class));
                        } else if ("deviceowner".equals(key)) {
                            startActivity(new Intent(H5EnhanceActivity.this, H5DeviceOwnerActivity.class));
                        }
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
