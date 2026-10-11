package xiaote.AnQuan;

import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * H5 界面壳（悬浮按钮设置）。
 *
 * 与原生 FloatingButtonSettingsActivity 平行：读写同一份 dot_config。
 *
 * 键名对齐说明：
 *   · 尺寸类键在原生里以 "dot_" 前缀存储（dot_width / dot_height / dot_alpha），
 *     置顶相关键没有前缀（force_top / force_stop_on_screen_off / force_interval）。
 *   · 颜色在原生里是 int（Color.argb），H5 侧用 #AARRGGBB 字符串表示，
 *     桥负责双向转换，存储仍是 int，保证与原生一致。
 *
 * 桥方法（JS 调 XT.xxx）：
 *   getState()            返回全部开关/滑块/颜色/位置/模式的 JSON
 *   setBool(key, value)   写布尔型偏好
 *   setInt(key, value)    写整型偏好
 *   setDotColor(hex)      写悬浮按钮颜色（#AARRGGBB）
 *   toast(msg) / close()
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5FloatActivity extends H5BaseActivity {

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/float.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("dot_config", MODE_PRIVATE);
    }

    /** int 颜色 → #AARRGGBB 字符串 */
    private static String intToHex(int color) {
        return String.format("#%08X", color);
    }

    /** #AARRGGBB 字符串 → int；解析失败返回 0 */
    private static int hexToInt(String hex) {
        if (hex == null) return 0;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            return (int) Long.parseLong(s, 16);
        } catch (Throwable t) {
            return 0;
        }
    }

    public class Bridge {

        @JavascriptInterface
        public String getState() {
            JSONObject o = new JSONObject();
            try {
                // 悬浮按钮默认关闭：与原生 OverlayManager / XTSafeMainService 保持一致
                o.put("dot_enabled", prefs().getBoolean("dot_enabled", false));
                o.put("force_top", prefs().getBoolean("force_top", true));
                o.put("force_stop_on_screen_off", prefs().getBoolean("force_stop_on_screen_off", true));
                o.put("background_keep_alive", prefs().getBoolean("background_keep_alive", false));

                o.put("dot_width", prefs().getInt("dot_width", 40));
                o.put("dot_height", prefs().getInt("dot_height", 0));
                o.put("dot_alpha", prefs().getInt("dot_alpha", 140));
                o.put("dot_position", prefs().getInt("dot_position", 0));
                o.put("force_top_mode", prefs().getInt("force_top_mode", 0));
                o.put("force_interval", prefs().getInt("force_interval", 5));

                int color = prefs().getInt("dot_color", 0x8CFFC864);
                o.put("dot_color", intToHex(color));
            } catch (Throwable ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public void setBool(String key, boolean value) {
            if (key == null) return;
            try { prefs().edit().putBoolean(key, value).apply(); } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void setInt(String key, int value) {
            if (key == null) return;
            try { prefs().edit().putInt(key, value).apply(); } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void setDotColor(String hex) {
            try { prefs().edit().putInt("dot_color", hexToInt(hex)).apply(); } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void toast(final String msg) {
            if (msg == null) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(H5FloatActivity.this, msg, Toast.LENGTH_SHORT).show();
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
