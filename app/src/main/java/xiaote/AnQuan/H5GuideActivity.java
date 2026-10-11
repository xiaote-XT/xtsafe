package xiaote.AnQuan;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * H5 界面壳（使用指南）。
 *
 * 与原生 UsageGuideActivity 平行，内容同源：11 段标题与正文全部从 R.string
 * 读取，不在 HTML 里硬编码，保证两种界面文案始终一致。
 *
 * 桥方法（JS 调 XT.xxx）：
 *   getSections()  返回 [{title, body}, ...] 的 JSON 字符串
 *   close()        关闭本页
 *
 * 继承 H5BaseActivity：WebView 初始化、状态栏同色、深色注入、返回键、
 * 销毁清理全部由基类负责。
 */
public class H5GuideActivity extends H5BaseActivity {

    @Override
    protected String pageUrl() {
        return "file:///android_asset/h5/guide.html";
    }

    @Override
    protected Object[] createBridge() {
        return new Object[]{"XT", new Bridge()};
    }

    public class Bridge {

        @JavascriptInterface
        public String getSections() {
            JSONArray arr = new JSONArray();
            int[] titleIds = {
                    R.string.guide_section1_title, R.string.guide_section2_title,
                    R.string.guide_section3_title, R.string.guide_section4_title,
                    R.string.guide_section5_title, R.string.guide_section6_title,
                    R.string.guide_section7_title, R.string.guide_section8_title,
                    R.string.guide_section9_title, R.string.guide_section10_title,
                    R.string.guide_section11_title
            };
            int[] bodyIds = {
                    R.string.guide_section1_body, R.string.guide_section2_body,
                    R.string.guide_section3_body, R.string.guide_section4_body,
                    R.string.guide_section5_body, R.string.guide_section6_body,
                    R.string.guide_section7_body, R.string.guide_section8_body,
                    R.string.guide_section9_body, R.string.guide_section10_body,
                    R.string.guide_section11_body
            };
            for (int i = 0; i < titleIds.length && i < bodyIds.length; i++) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("title", getString(titleIds[i]));
                    o.put("body", getString(bodyIds[i]));
                    arr.put(o);
                } catch (Throwable ignored) {}
            }
            return arr.toString();
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(new Runnable() {
                @Override public void run() { finish(); }
            });
        }
    }
}
