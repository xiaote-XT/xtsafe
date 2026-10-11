package xiaote.AnQuan;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * H5 界面壳通用基类。
 *
 * 负责 WebView 初始化、状态栏与 H5 背景同色、深色状态注入、返回键处理、销毁清理。
 * 子类通过 onInit() 读取参数、pageUrl() 指定页面、createBridge() 挂载业务桥。
 *
 * 除子类自己的业务桥外，基类固定额外注册一个公共桥 XTB，供 lib/xtui/xtui.js 使用：
 *   getUiMode() / setUiMode(mode)   界面风格偏好（custom / mdui），存 dot_config.h5_ui_mode
 *   getPref(key, def) / setPref(k,v)
 *   isDark()                        当前是否深色
 *   toast(msg) / close()
 * 这样各页不必各写一遍风格读取逻辑，xtui.js 一进来就知道该渲染哪套 DOM。
 *
 * 状态栏约定：颜色取 H5 背景同色（浅 #ffffff / 深 #1e1e1e），浅色时打
 * LIGHT_STATUS_BAR 让图标变深，深色则去掉，保证前景背景一致。
 *
 * 深色一致性：WebView 的 prefers-color-scheme 跟随策略不一定和原生判定一致，
 * 因此 onPageFinished 时由原生注入 html.dark 类，HTML 侧两套规则并存。
 *
 * 刻意不接 SwipeBackHelper：侧滑返回会与 WebView 横向滚动抢事件。
 */
public abstract class H5BaseActivity extends BaseActivity {

    /** 界面风格偏好：星特自有样式 / mdui */
    public static final String KEY_H5_UI_MODE = "h5_ui_mode";
    public static final String UI_MODE_CUSTOM = "custom";
    public static final String UI_MODE_MDUI = "mdui";

    protected WebView webView;
    /** 已注册的业务桥对象名，销毁时按它移除 */
    private String bridgeName = "XT";

    /** 子类在此读取 Intent 参数等初始状态 */
    protected void onInit() {}

    /** 页面 HTML 资源地址，如 file:///android_asset/h5/perm.html */
    protected abstract String pageUrl();

    /** 返回 [对象名, 桥对象]；不需要桥时返回 null */
    protected Object[] createBridge() { return null; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        onInit();

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                applyThemeColors();
                syncHtmlTheme();
            }
        });
        Object[] bridge = createBridge();
        if (bridge != null && bridge.length == 2 && bridge[0] != null && bridge[1] != null) {
            bridgeName = (String) bridge[0];
            webView.addJavascriptInterface(bridge[1], bridgeName);
        }
        // 公共桥：xtui.js 依赖它读界面风格与偏好
        webView.addJavascriptInterface(new XtuiBridge(), "XTB");
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        applyThemeColors();
        webView.loadUrl(pageUrl());
    }

    /**
     * 本页是否参与密码锁。默认 true，与原生 BaseActivity 行为一致。
     *
     * 原生里授权 / 系统跳转类页面（Shizuku、Dhizuku、Device Owner、安全扫描、
     * 规则管理、广告拦截、进程管理、权限增强、更新日志）刻意继承 Activity
     * 而不继承 BaseActivity，避免授权过程中弹密码页打断流程。
     * 对应 H5 壳覆写本方法返回 false，由基类代为跳过密码校验。
     */
    protected boolean lockEnabled() { return true; }

    /**
     * 把本类的 lockEnabled() 转发到 BaseActivity 的 isLockEnabled() 钩子。
     * 不直接让子类覆写 isLockEnabled()，是为了让 H5 壳统一用 lockEnabled()
     * 这一个名字表达「本页不参与密码锁」，避免和 BaseActivity 的命名混淆。
     */
    @Override
    protected boolean isLockEnabled() { return lockEnabled(); }

    /**
     * 让前端重新拉一次状态（子类在数据变更后调用）。
     *
     * 内部切到主线程再执行：WebView 的 evaluateJavascript 只能在 UI 线程调用，
     * 而状态采集（Root 探测、专版 Shizuku 查询等）都跑在工作线程，
     * 采完直接回调本方法。若不切线程，轻则调用被静默丢弃、前端永远停在
     * 「检查中」，重则在部分 ROM 上抛异常。切线程后所有调用点都不必自己
     * 再包一层 runOnUiThread。
     */
    protected void refreshFrontend() {
        if (webView == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                try {
                    webView.evaluateJavascript("window.XTRefresh && window.XTRefresh();", null);
                } catch (Throwable ignored) {}
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyThemeColors();
        syncHtmlTheme();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try {
            if (webView != null) {
                webView.removeJavascriptInterface(bridgeName);
                webView.removeJavascriptInterface("XTB");
                webView.destroy();
                webView = null;
            }
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    // ==================== 公共桥（xtui 依赖） ====================

    protected SharedPreferences dotPrefs() {
        return getSharedPreferences("dot_config", MODE_PRIVATE);
    }

    /** 当前界面风格：custom（星特自有）/ mdui，默认 custom */
    public String getUiMode() {
        try {
            String v = dotPrefs().getString(KEY_H5_UI_MODE, UI_MODE_CUSTOM);
            return UI_MODE_MDUI.equals(v) ? UI_MODE_MDUI : UI_MODE_CUSTOM;
        } catch (Throwable t) {
            return UI_MODE_CUSTOM;
        }
    }

    public void setUiMode(String mode) {
        String v = UI_MODE_MDUI.equals(mode) ? UI_MODE_MDUI : UI_MODE_CUSTOM;
        try { dotPrefs().edit().putString(KEY_H5_UI_MODE, v).apply(); } catch (Throwable ignored) {}
    }

    /**
     * 供 xtui.js 调用的公共桥。
     * 命名与 xtui.js 中的 XTB.xxx 一一对应，改动需两边同步。
     */
    public class XtuiBridge {

        @JavascriptInterface
        public String getUiMode() {
            return H5BaseActivity.this.getUiMode();
        }

        @JavascriptInterface
        public void setUiMode(String mode) {
            H5BaseActivity.this.setUiMode(mode);
        }

        /**
         * 读任意偏好并转成字符串。
         * 不用 getString，避免该键存的是 boolean/int 时抛 ClassCastException。
         */
        @JavascriptInterface
        public String getPref(String key, String def) {
            if (key == null) return def;
            try {
                Object v = dotPrefs().getAll().get(key);
                return v == null ? def : String.valueOf(v);
            } catch (Throwable t) {
                return def;
            }
        }

        @JavascriptInterface
        public void setPref(String key, String value) {
            if (key == null) return;
            try { dotPrefs().edit().putString(key, value).apply(); } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public boolean isDark() {
            return isDarkMode();
        }

        @JavascriptInterface
        public void toast(final String msg) {
            if (msg == null) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(H5BaseActivity.this, msg, Toast.LENGTH_SHORT).show();
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

    // ==================== 主题 / 状态栏 ====================

    protected boolean isDarkMode() {
        try {
            int mode = getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    protected void applyThemeColors() {
        boolean dark = isDarkMode();
        int bg = dark ? Color.parseColor("#1e1e1e") : Color.parseColor("#ffffff");
        try {
            if (webView != null) webView.setBackgroundColor(bg);
        } catch (Throwable ignored) {}
        try {
            getWindow().setStatusBarColor(bg);
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {}
    }

    /**
     * 注入深色状态 + mdui 配色。
     *
     * 主题色固定为星特橙 #ff6a00，由 mdui.setColorScheme 按 Material You 规则
     * 生成一整套 primary / surface / outline 等 color token，mdui 组件会自动跟随；
     * custom 模式下 typeof mdui === 'undefined'，整段直接跳过，无副作用。
     *
     * setColorScheme 每次调用会先移除旧的 custom scheme 类再生成新的，
     * 因此重复执行不会堆积样式。
     */
    protected void syncHtmlTheme() {
        if (webView == null) return;
        final boolean dark = isDarkMode();
        String js = "document.documentElement.classList.toggle('dark'," + dark + ");"
                + "(function(){if(typeof mdui==='undefined')return;"
                + "try{mdui.setColorScheme('#ff6a00',{target:document.documentElement});}catch(e){}"
                + "try{mdui.setTheme('" + (dark ? "dark" : "light") + "',document.documentElement);}catch(e){}"
                + "})();";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable ignored) {}
    }
}
