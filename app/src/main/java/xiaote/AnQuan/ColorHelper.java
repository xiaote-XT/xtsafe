package xiaote.AnQuan;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

/**
 * 颜色/深浅色模式辅助工具
 * 提取自 XTSafeMainService 的颜色相关方法
 */
public class ColorHelper {

    private final Context context;

    public ColorHelper(Context context) {
        this.context = context;
    }

    public int getThemeColor() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return context.getResources().getColor(android.R.color.system_accent1_500, context.getTheme());
            } catch (Exception e) {
                return 0xFFFF6A00;
            }
        }
        return 0xFFFF6A00;
    }

    public int getContrastTextColor(int bgColor) {
        int r = Color.red(bgColor), g = Color.green(bgColor), b = Color.blue(bgColor);
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
        return luminance > 0.5 ? Color.argb(255, 30, 30, 30) : Color.WHITE;
    }

    public boolean isDarkMode() {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public int getMonetNeutral(String lightName, String darkName, int defaultColor) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                String name = isDarkMode() ? darkName : lightName;
                int resId = context.getResources().getIdentifier(name, "color", "android");
                if (resId != 0) {
                    return context.getResources().getColor(resId, context.getTheme());
                }
            } catch (Exception e) {}
        }
        return defaultColor;
    }

    public int getBgColor() {
        return getMonetNeutral(
            "system_neutral1_50",
            "system_neutral1_950",
            isDarkMode() ? Color.argb(255, 20, 20, 30) : Color.argb(255, 245, 245, 250));
    }

    public int getSurfaceColor() {
        int monet = getMonetNeutral(
            "system_neutral1_100",
            "system_neutral1_900",
            isDarkMode() ? Color.argb(200, 40, 40, 55) : Color.argb(200, 230, 230, 240));
        if (Build.VERSION.SDK_INT >= 31 && (monet & 0xFF000000) == 0xFF000000) {
            monet = (monet & 0x00FFFFFF) | (0xC8 << 24);
        }
        return monet;
    }

    public int getTextColor() {
        return isDarkMode() ? Color.WHITE : Color.argb(255, 30, 30, 30);
    }

    public int getSecondaryTextColor() {
        return isDarkMode() ? Color.argb(180, 200, 200, 200) : Color.argb(180, 90, 90, 90);
    }

    public int getHintTextColor() {
        return isDarkMode() ? Color.argb(120, 255, 255, 255) : Color.argb(120, 80, 80, 80);
    }

    public int getHintBgColor() {
        int monet = getMonetNeutral(
            "system_neutral1_1000",
            "system_neutral1_0",
            isDarkMode() ? Color.argb(40, 255, 255, 255) : Color.argb(40, 0, 0, 0));
        return (monet & 0x00FFFFFF) | (0x28 << 24);
    }

    public int getListItemBgColor() {
        int monet = getMonetNeutral(
            "system_neutral1_1000",
            "system_neutral1_0",
            isDarkMode() ? Color.argb(15, 255, 255, 255) : Color.argb(15, 0, 0, 0));
        return (monet & 0x00FFFFFF) | (0x0F << 24);
    }

    public int getSearchBgColor() {
        int monet = getMonetNeutral(
            "system_neutral1_1000",
            "system_neutral1_0",
            isDarkMode() ? Color.argb(60, 255, 255, 255) : Color.argb(60, 0, 0, 0));
        return (monet & 0x00FFFFFF) | (0x3C << 24);
    }

    public int getTransparentBgColor() {
        int monet = getMonetNeutral(
            "system_neutral1_1000",
            "system_neutral1_0",
            isDarkMode() ? Color.argb(80, 255, 255, 255) : Color.argb(80, 0, 0, 0));
        return (monet & 0x00FFFFFF) | (0x50 << 24);
    }

    public int dpToPx(int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    public int getStatusBarHeight() {
        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) return context.getResources().getDimensionPixelSize(resId);
        return (int) (24 * context.getResources().getDisplayMetrics().density);
    }
}
