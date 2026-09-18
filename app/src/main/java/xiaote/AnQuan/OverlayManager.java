package xiaote.AnQuan;

import xiaote.AnQuan.R;

import android.accessibilityservice.AccessibilityService;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * 浮窗 UI 管理器
 * 提取自 XTSafeMainService 的所有浮窗 UI 代码
 */
public class OverlayManager {

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Handler handler;
    private final ColorHelper colorHelper;
    private final PackageManager pm;
    private final EmergencyManager emergencyManager;
    private final SharedPreferences dotPrefs;

    private View statusBarView;
    private WindowManager.LayoutParams statusBarParams;
    private LinearLayout listOverlayView;
    private WindowManager.LayoutParams listParams;
    private View confirmView;
    private WindowManager.LayoutParams confirmParams;
    private View moreMenuView;
    private WindowManager.LayoutParams moreMenuParams;
    private View emergencyMenuView;
    private WindowManager.LayoutParams emergencyMenuParams;
    private boolean isListShowing = false;

    public boolean isListShowing() { return isListShowing; }

    public OverlayManager(AccessibilityService service, Handler handler,
                          ColorHelper colorHelper, EmergencyManager emergencyManager) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.handler = handler;
        this.colorHelper = colorHelper;
        this.pm = service.getPackageManager();
        this.emergencyManager = emergencyManager;
        this.dotPrefs = service.getSharedPreferences("dot_config", Context.MODE_PRIVATE);
    }

    // ==================== 状态栏悬浮按钮 ====================

    public void createStatusBarDot() {
        // 悬浮按钮总开关：关闭时不创建，并移除已有悬浮窗（强制置顶逻辑不受影响）
        if (!dotPrefs.getBoolean("dot_enabled", true)) {
            if (statusBarView != null) {
                try { wm.removeView(statusBarView); } catch (Exception e) {}
                statusBarView = null;
            }
            return;
        }
        if (statusBarView != null) {
            try { wm.removeView(statusBarView); } catch (Exception e) {}
            statusBarView = null;
        }

        int cfgWidth = dotPrefs.getInt("dot_width", 40);
        int cfgHeight = dotPrefs.getInt("dot_height", 0);
        int cfgColor = dotPrefs.getInt("dot_color", Color.argb(140, 255, 200, 100));
        int cfgPosition = dotPrefs.getInt("dot_position", 1);
        int cfgAlpha = dotPrefs.getInt("dot_alpha", 140);
        cfgColor = (cfgColor & 0x00FFFFFF) | (Math.min(255, Math.max(0, cfgAlpha)) << 24);

        int statusBarHeight = colorHelper.getStatusBarHeight();
        int realHeight = cfgHeight > 0 ? colorHelper.dpToPx(cfgHeight) : statusBarHeight + 4;

        int gravity = Gravity.TOP;
        switch (cfgPosition) {
            case 0: gravity |= Gravity.START; break;
            case 2: gravity |= Gravity.END; break;
            default: gravity |= Gravity.CENTER_HORIZONTAL; break;
        }

        View dot = new View(service);
        dot.setBackgroundColor(cfgColor);
        dot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reTopStatusBar();
                showUninstallList();
            }
        });

        int windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        if (Build.VERSION.SDK_INT < 26) {
            flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        }

        statusBarParams = new WindowManager.LayoutParams(
                colorHelper.dpToPx(cfgWidth), realHeight, windowType, flags, PixelFormat.TRANSLUCENT);
        statusBarParams.gravity = gravity;
        statusBarParams.x = 0;
        statusBarParams.y = 0;

        try {
            wm.addView(dot, statusBarParams);
            statusBarView = dot;
        } catch (Exception e) {
            Toast.makeText(service, R.string.dot_create_fail, Toast.LENGTH_SHORT).show();
        }
    }

    public void reTopStatusBar() {
        if (statusBarView != null && wm != null) {
            try {
                wm.removeView(statusBarView);
                wm.addView(statusBarView, statusBarParams);
            } catch (Exception e) {
                statusBarView = null;
                createStatusBarDot();
            }
        }
    }

    /**
     * 最高优先级模式：重新创建悬浮窗，确保始终位于窗口栈顶。
     * 移除旧窗口后新建并 addView，后添加的窗口位于栈顶。
     * 旧窗口移除失败不影响新建（容错），不强制依赖旧窗口销毁。
     */
    public void reCreateStatusBarDot() {
        createStatusBarDot();
    }

    // ==================== 卸载列表 ====================

    private String highlightPkg = null;

    /** 唤起卸载列表并定位到指定风险应用包名 */
    public void showUninstallList(String highlightPkg) {
        this.highlightPkg = highlightPkg;
        showUninstallList();
    }

    public void showUninstallList() {
        isListShowing = true;
        reTopStatusBar();

        if (dotPrefs.getBoolean("force_stop_virus", true)) {
            new VirusManager(service).forceStopVirusApps();
        }

        if (listOverlayView != null) {
            try { wm.removeView(listOverlayView); } catch (Exception e) {}
            listOverlayView = null;
        }

        int wType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        int baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

        final WindowManager.LayoutParams loadParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                wType, baseFlags, PixelFormat.TRANSLUCENT);
        loadParams.gravity = Gravity.CENTER;

        final LinearLayout loadingRoot = new LinearLayout(service);
        loadingRoot.setOrientation(LinearLayout.VERTICAL);
        loadingRoot.setGravity(Gravity.CENTER);
        loadingRoot.setBackgroundColor(colorHelper.getBgColor());
        ProgressBar progressBar = new ProgressBar(service);
        progressBar.setIndeterminate(true);
        if (Build.VERSION.SDK_INT >= 21) {
            progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(colorHelper.getThemeColor()));
        }
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(colorHelper.dpToPx(48), colorHelper.dpToPx(48)));
        loadingRoot.addView(progressBar);
        TextView loadingText = new TextView(service);
        loadingText.setText(R.string.loading);
        loadingText.setTextColor(colorHelper.getSecondaryTextColor());
        loadingText.setTextSize(14);
        loadingText.setPadding(0, 16, 0, 0);
        loadingRoot.addView(loadingText);

        try {
            wm.addView(loadingRoot, loadParams);
            listOverlayView = loadingRoot;
            listParams = loadParams;
        } catch (Exception e) {
            return;
        }

        handler.post(new Runnable() {
            @Override
            public void run() {
                final List<AppInfo> appList = getThirdPartyApps();
                // 风险唤起：把目标（当前诱导）应用置顶，便于直接处置
                if (highlightPkg != null) {
                    for (int i = 0; i < appList.size(); i++) {
                        if (highlightPkg.equals(appList.get(i).packageName)) {
                            AppInfo t = appList.remove(i);
                            appList.add(0, t);
                            break;
                        }
                    }
                }
                final Set<String> accPkgs = getEnabledAccessibilityPackages();
                LinearLayout listRoot = buildListUI(appList, accPkgs);
                try {
                    wm.removeView(loadingRoot);
                    wm.addView(listRoot, listParams);
                    listOverlayView = listRoot;
                    listRoot.setAlpha(0f);
                    listRoot.animate().alpha(1f).setDuration(350).start();
                } catch (Exception e) {
                    try { wm.addView(listRoot, listParams); } catch (Exception e2) {}
                    listOverlayView = listRoot;
                }
            }
        });
    }

    private LinearLayout buildListUI(final List<AppInfo> appList, final Set<String> accPkgs) {
        LinearLayout root = new LinearLayout(service);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colorHelper.getBgColor());

        int themeColor = colorHelper.getThemeColor();
        int titleBarColor = (themeColor & 0x00FFFFFF) | (0xFF << 24);
        if (!colorHelper.isDarkMode()) {
            int r = Color.red(titleBarColor), g = Color.green(titleBarColor), b = Color.blue(titleBarColor);
            double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
            if (lum > 0.6) {
                titleBarColor = Color.argb(255, Math.max(0, r - 60), Math.max(0, g - 60), Math.max(0, b - 60));
            }
        }

        LinearLayout titleBar = new LinearLayout(service);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setBackgroundColor(titleBarColor);
        titleBar.setPadding(16, 14, 16, 14);

        TextView titleText = new TextView(service);
        titleText.setText(service.getString(R.string.app_name) + " - " + service.getString(R.string.uninstall_title));
        titleText.setTextColor(colorHelper.getContrastTextColor(titleBarColor));
        titleText.setTextSize(17);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        titleBar.addView(titleText);

        Button emergencyBtn = new Button(service);
        emergencyBtn.setText(R.string.emergency_ops);
        emergencyBtn.setTextSize(12);
        emergencyBtn.setTextColor(colorHelper.getContrastTextColor(titleBarColor));
        GradientDrawable emerBg = new GradientDrawable();
        emerBg.setCornerRadius(colorHelper.dpToPx(16));
        emerBg.setColor(Color.argb(80, 255, 80, 80));
        emergencyBtn.setBackground(emerBg);
        emergencyBtn.setPadding(12, 6, 12, 6);
        emergencyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dismissEmergencyMenu(); showEmergencyMenu(); }
        });
        titleBar.addView(emergencyBtn);

        Button closeBtn = new Button(service);
        closeBtn.setText(R.string.hide);
        closeBtn.setTextSize(14);
        closeBtn.setTextColor(colorHelper.getContrastTextColor(titleBarColor));
        GradientDrawable hideBg = new GradientDrawable();
        hideBg.setCornerRadius(colorHelper.dpToPx(16));
        hideBg.setColor(Color.argb(60, 0, 0, 0));
        closeBtn.setBackground(hideBg);
        closeBtn.setPadding(16, 6, 16, 6);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dismissList(); }
        });
        titleBar.addView(closeBtn);
        root.addView(titleBar);

        EditText searchBox = new EditText(service);
        searchBox.setHint(R.string.search_hint);
        searchBox.setHintTextColor(colorHelper.getHintTextColor());
        searchBox.setTextColor(colorHelper.getTextColor());
        searchBox.setTextSize(14);
        searchBox.setBackgroundColor(colorHelper.getSearchBgColor());
        searchBox.setPadding(16, 10, 16, 10);
        searchBox.setSingleLine(true);
        root.addView(searchBox);

        boolean shizukuReady = ShellExecutor.isShizukuReady();
        String uninstallMode;
        if (shizukuReady) {
            uninstallMode = service.getString(R.string.shizuku_silent);
        } else if (isRootAvailable()) {
            uninstallMode = service.getString(R.string.root_silent);
        } else {
            uninstallMode = service.getString(R.string.system_uninstall);
        }
        String statusText = String.format(service.getString(R.string.count_format), appList.size(), uninstallMode);
        TextView hint = new TextView(service);
        hint.setText(statusText);
        hint.setTextColor(colorHelper.getSecondaryTextColor());
        hint.setTextSize(11);
        hint.setPadding(20, 8, 20, 8);
        hint.setBackgroundColor(colorHelper.getHintBgColor());
        root.addView(hint);

        ListView listView = new ListView(service);
        listView.setBackgroundColor(colorHelper.getHintBgColor());
        listView.setDividerHeight(1);
        listView.setChoiceMode(ListView.CHOICE_MODE_NONE);

        final Set<String> virusPkgs = VirusPackages.getVirusPackages(service);
        final AppListAdapter adapter = new AppListAdapter(service, colorHelper, virusPkgs, appList);
        adapter.highlightPkg = highlightPkg;
        listView.setAdapter(adapter);

        LinearLayout filterRow = new LinearLayout(service);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER);
        filterRow.setPadding(12, 8, 12, 8);

        Button btnAll = new Button(service);
        btnAll.setText(R.string.filter_all);
        btnAll.setTextSize(13);
        btnAll.setAllCaps(false);
        btnAll.setPadding(16, 6, 16, 6);
        btnAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { adapter.setShowManagedOnly(false); }
        });
        filterRow.addView(btnAll);

        TextView filterSpacer = new TextView(service);
        filterSpacer.setWidth(colorHelper.dpToPx(12));
        filterRow.addView(filterSpacer);

        Button btnMgr = new Button(service);
        btnMgr.setText(R.string.filter_managed);
        btnMgr.setTextSize(13);
        btnMgr.setAllCaps(false);
        btnMgr.setPadding(16, 6, 16, 6);
        btnMgr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { adapter.setShowManagedOnly(true); }
        });
        filterRow.addView(btnMgr);
        root.addView(filterRow);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AppInfo ai = (AppInfo) parent.getItemAtPosition(position);
                if (ai == null) return;
                reTopStatusBar();
                showConfirmDialog(ai.name, ai.packageName);
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                AppInfo ai = (AppInfo) parent.getItemAtPosition(position);
                if (ai == null) return false;
                showMoreMenu(ai);
                return true;
            }
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.getFilter().filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        root.addView(listView);

        LinearLayout bottomBar = new LinearLayout(service);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackgroundColor(colorHelper.getSurfaceColor());
        bottomBar.setPadding(20, 12, 20, 12);

        Button refreshBtn = new Button(service);
        refreshBtn.setText(R.string.refresh_list);
        refreshBtn.setTextSize(14);
        refreshBtn.setTextColor(Color.WHITE);
        refreshBtn.setBackgroundColor((colorHelper.getThemeColor() & 0x00FFFFFF) | (0x99 << 24));
        refreshBtn.setPadding(20, 8, 20, 8);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showUninstallList(); }
        });
        bottomBar.addView(refreshBtn);

        TextView spacer = new TextView(service);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        bottomBar.addView(spacer);

        Button homeBtn = new Button(service);
        homeBtn.setText(R.string.back_home);
        homeBtn.setTextSize(14);
        homeBtn.setTextColor(colorHelper.getTextColor());
        homeBtn.setBackgroundColor(colorHelper.getTransparentBgColor());
        homeBtn.setPadding(20, 8, 20, 8);
        homeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }
        });
        bottomBar.addView(homeBtn);
        root.addView(bottomBar);

        return root;
    }

    public void dismissList() {
        isListShowing = false;
        confirmView = null;
        if (listOverlayView != null) {
            try { wm.removeView(listOverlayView); } catch (Exception e) {}
            listOverlayView = null;
        }
    }

    // ==================== 确认卸载对话框 ====================

    public void showConfirmDialog(final String appName, final String packageName) {
        if (confirmView != null) {
            try { wm.removeView(confirmView); } catch (Exception e) {}
            confirmView = null;
        }

        LinearLayout layout = new LinearLayout(service);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        GradientDrawable layoutBg = new GradientDrawable();
        layoutBg.setCornerRadius(colorHelper.dpToPx(16));
        int bgColor = 0xFFFF6A00;
        if (Build.VERSION.SDK_INT >= 31) {
            try { bgColor = service.getResources().getColor(android.R.color.system_accent2_100, service.getTheme()); } catch (Exception ignored) {}
        }
        bgColor = (bgColor & 0x00FFFFFF) | (0x99 << 24);
        layoutBg.setColor(bgColor);
        layout.setBackground(layoutBg);

        TextView title = new TextView(service);
        title.setText(R.string.confirm_uninstall);
        title.setTextColor(colorHelper.getContrastTextColor(bgColor));
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(30, 25, 30, 10);
        layout.addView(title);

        TextView info = new TextView(service);
        info.setText(appName + "\n" + packageName);
        info.setTextColor(colorHelper.getContrastTextColor(bgColor));
        info.setTextSize(14);
        info.setGravity(Gravity.CENTER);
        info.setPadding(30, 10, 30, 20);
        layout.addView(info);

        LinearLayout btnRow = new LinearLayout(service);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(20, 10, 20, 20);

        Button cancelBtn = new Button(service);
        cancelBtn.setText(R.string.cancel);
        cancelBtn.setTextSize(15);
        cancelBtn.setTextColor(colorHelper.getContrastTextColor(bgColor));
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setCornerRadius(colorHelper.dpToPx(16));
        cancelBg.setColor(colorHelper.isDarkMode() ? Color.argb(80, 255, 255, 255) : Color.argb(80, 0, 0, 0));
        cancelBtn.setBackground(cancelBg);
        cancelBtn.setPadding(30, 10, 30, 10);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dismissConfirm(); }
        });
        btnRow.addView(cancelBtn);

        TextView spacer = new TextView(service);
        spacer.setWidth(colorHelper.dpToPx(20));
        btnRow.addView(spacer);

        Button okBtn = new Button(service);
        okBtn.setText(R.string.ok_uninstall);
        okBtn.setTextSize(15);
        okBtn.setTextColor(colorHelper.getContrastTextColor(colorHelper.getThemeColor()));
        GradientDrawable okBg = new GradientDrawable();
        okBg.setCornerRadius(colorHelper.dpToPx(16));
        okBg.setColor(colorHelper.getThemeColor());
        okBtn.setBackground(okBg);
        okBtn.setPadding(30, 10, 30, 10);
        okBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dismissConfirm(); uninstallApp(packageName); }
        });
        btnRow.addView(okBtn);
        layout.addView(btnRow);

        int windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

        confirmParams = new WindowManager.LayoutParams(
                colorHelper.dpToPx(300), WindowManager.LayoutParams.WRAP_CONTENT,
                windowType, flags, PixelFormat.TRANSLUCENT);
        confirmParams.gravity = Gravity.CENTER;

        try {
            wm.addView(layout, confirmParams);
            confirmView = layout;
        } catch (Exception e) {
            Toast.makeText(service, R.string.confirm_show_fail, Toast.LENGTH_SHORT).show();
        }
    }

    public void dismissConfirm() {
        if (confirmView != null) {
            try { wm.removeView(confirmView); } catch (Exception e) {}
            confirmView = null;
        }
    }

    // ==================== 更多菜单 ====================

    public void showMoreMenu(final AppInfo ai) {
        if (moreMenuView != null) {
            try { wm.removeView(moreMenuView); } catch (Exception e) {}
            moreMenuView = null;
        }

        LinearLayout layout = new LinearLayout(service);
        layout.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable layoutBg = new GradientDrawable();
        layoutBg.setCornerRadius(colorHelper.dpToPx(16));
        layoutBg.setColor(colorHelper.getBgColor());
        layout.setBackground(layoutBg);

        int themeColor = colorHelper.getThemeColor();
        int titleBarColor = (themeColor & 0x00FFFFFF) | (0xFF << 24);
        if (!colorHelper.isDarkMode()) {
            int r = Color.red(titleBarColor), g = Color.green(titleBarColor), b = Color.blue(titleBarColor);
            double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
            if (lum > 0.6) titleBarColor = Color.argb(255, Math.max(0, r - 60), Math.max(0, g - 60), Math.max(0, b - 60));
        }

        LinearLayout titleBar = new LinearLayout(service);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable titleBarBg = new GradientDrawable();
        titleBarBg.setCornerRadii(new float[]{colorHelper.dpToPx(16), colorHelper.dpToPx(16), colorHelper.dpToPx(16), colorHelper.dpToPx(16), 0, 0, 0, 0});
        titleBarBg.setColor(titleBarColor);
        titleBar.setBackground(titleBarBg);
        titleBar.setPadding(20, 14, 20, 14);

        TextView titleText = new TextView(service);
        titleText.setText(ai.name);
        titleText.setTextColor(colorHelper.getContrastTextColor(titleBarColor));
        titleText.setTextSize(16);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        titleBar.addView(titleText);

        TextView pkgLabel = new TextView(service);
        pkgLabel.setText(ai.packageName);
        pkgLabel.setTextColor(colorHelper.getContrastTextColor(titleBarColor));
        pkgLabel.setTextSize(10);
        pkgLabel.setAlpha(0.8f);
        titleBar.addView(pkgLabel);
        layout.addView(titleBar);

        GradientDrawable itemBg = new GradientDrawable();
        itemBg.setCornerRadius(0);
        itemBg.setColor(Color.TRANSPARENT);

        String[] labels = {service.getString(R.string.action_all_in_one), service.getString(R.string.action_force_stop), service.getString(R.string.action_freeze), service.getString(R.string.action_disable_acc)};
        View.OnClickListener[] listeners = new View.OnClickListener[]{
            new View.OnClickListener() { @Override public void onClick(View v) { dismissMoreMenu(); executeAllActions(ai.packageName); } },
            new View.OnClickListener() { @Override public void onClick(View v) { dismissMoreMenu(); forceStopApp(ai.packageName); } },
            new View.OnClickListener() { @Override public void onClick(View v) { dismissMoreMenu(); freezeApp(ai.packageName); } },
            new View.OnClickListener() { @Override public void onClick(View v) { dismissMoreMenu(); disableAppAccessibility(ai.packageName); } }
        };

        for (int i = 0; i < labels.length; i++) {
            Button btn = new Button(service);
            btn.setText(labels[i]);
            btn.setTextSize(15);
            btn.setTextColor(i == 0 ? colorHelper.getThemeColor() : colorHelper.getTextColor());
            btn.setBackground(itemBg);
            btn.setPadding(30, 18, 30, 18);
            btn.setOnClickListener(listeners[i]);
            layout.addView(btn);

            View divider = new View(service);
            divider.setBackgroundColor(colorHelper.getHintTextColor());
            divider.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            layout.addView(divider);
        }

        if (ai.isManaged) {
            Button btnRemoveMgr = new Button(service);
            btnRemoveMgr.setText(R.string.remove_from_managed);
            btnRemoveMgr.setTextSize(15);
            btnRemoveMgr.setTextColor(Color.argb(255, 100, 200, 255));
            btnRemoveMgr.setBackground(itemBg);
            btnRemoveMgr.setPadding(30, 18, 30, 18);
            btnRemoveMgr.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismissMoreMenu();
                    VirusPackages.removeManagedPackage(service, ai.packageName);
                    Toast.makeText(service, service.getString(R.string.removed_managed_toast, ai.packageName), Toast.LENGTH_SHORT).show();
                }
            });
            layout.addView(btnRemoveMgr);
            View divider = new View(service);
            divider.setBackgroundColor(colorHelper.getHintTextColor());
            divider.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            layout.addView(divider);
        }

        Button btnCancel = new Button(service);
        btnCancel.setText(R.string.cancel);
        btnCancel.setTextSize(15);
        btnCancel.setTextColor(colorHelper.getSecondaryTextColor());
        btnCancel.setBackground(itemBg);
        btnCancel.setPadding(30, 18, 30, 18);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dismissMoreMenu(); }
        });
        layout.addView(btnCancel);

        int windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

        moreMenuParams = new WindowManager.LayoutParams(
                colorHelper.dpToPx(280), WindowManager.LayoutParams.WRAP_CONTENT,
                windowType, flags, PixelFormat.TRANSLUCENT);
        moreMenuParams.gravity = Gravity.CENTER;

        try {
            wm.addView(layout, moreMenuParams);
            moreMenuView = layout;
        } catch (Exception e) {
            Toast.makeText(service, R.string.menu_show_fail, Toast.LENGTH_SHORT).show();
        }
    }

    public void dismissMoreMenu() {
        if (moreMenuView != null) {
            try { wm.removeView(moreMenuView); } catch (Exception e) {}
            moreMenuView = null;
        }
    }

    // ==================== 应急操作菜单 ====================

    public void showEmergencyMenu() {
        if (emergencyMenuView != null) {
            try { wm.removeView(emergencyMenuView); } catch (Exception e) {}
            emergencyMenuView = null;
        }

        LinearLayout layout = new LinearLayout(service);
        layout.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable layoutBg = new GradientDrawable();
        layoutBg.setCornerRadius(colorHelper.dpToPx(16));
        layoutBg.setColor(colorHelper.getBgColor());
        layout.setBackground(layoutBg);

        int warnColor = Color.argb(255, 200, 50, 50);
        LinearLayout titleBar = new LinearLayout(service);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable titleBarBg = new GradientDrawable();
        titleBarBg.setCornerRadii(new float[]{colorHelper.dpToPx(16), colorHelper.dpToPx(16), 0, 0, 0, 0, 0, 0});
        titleBarBg.setColor(warnColor);
        titleBar.setBackground(titleBarBg);
        titleBar.setPadding(20, 14, 20, 14);
        TextView titleText = new TextView(service);
        titleText.setText(R.string.emergency_ops);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(16);
        titleText.setTypeface(null, Typeface.BOLD);
        titleBar.addView(titleText);
        layout.addView(titleBar);

        GradientDrawable itemBg = new GradientDrawable();
        itemBg.setCornerRadius(0);
        itemBg.setColor(Color.TRANSPARENT);

        final String appName = service.getString(R.string.app_name);
        final String[] items = {
            service.getString(R.string.emergency_stop_all),
            service.getString(R.string.emergency_disable_all_acc, appName),
            service.getString(R.string.emergency_disable_all_admin),
            service.getString(R.string.emergency_clear_pwd),
            service.getString(R.string.emergency_fill_mem)
        };
        final Runnable[] actions = new Runnable[]{
            new Runnable() { @Override public void run() { emergencyManager.forceStopAll(); } },
            new Runnable() { @Override public void run() { emergencyManager.disableAllAccessibility(); } },
            new Runnable() { @Override public void run() { emergencyManager.disableAllDeviceAdmin(); } },
            new Runnable() { @Override public void run() { emergencyManager.clearPassword(); } },
            new Runnable() { @Override public void run() { emergencyManager.fillMemory(); } }
        };

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            View divider = new View(service);
            divider.setBackgroundColor(colorHelper.getHintTextColor());
            divider.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            layout.addView(divider);

            Button btn = new Button(service);
            btn.setText(items[i]);
            btn.setTextSize(14);
            btn.setTextColor(colorHelper.getTextColor());
            btn.setBackground(itemBg);
            btn.setPadding(30, 16, 30, 16);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { dismissEmergencyMenu(); showDoubleConfirm(items[idx], actions[idx]); }
            });
            layout.addView(btn);
        }

        View dividerEnd = new View(service);
        dividerEnd.setBackgroundColor(colorHelper.getHintTextColor());
        dividerEnd.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        layout.addView(dividerEnd);

        Button btnCancel = new Button(service);
        btnCancel.setText(R.string.cancel);
        btnCancel.setTextSize(14);
        btnCancel.setTextColor(colorHelper.getSecondaryTextColor());
        btnCancel.setBackground(itemBg);
        btnCancel.setPadding(30, 16, 30, 16);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dismissEmergencyMenu(); }
        });
        layout.addView(btnCancel);

        int windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

        emergencyMenuParams = new WindowManager.LayoutParams(
                colorHelper.dpToPx(280), WindowManager.LayoutParams.WRAP_CONTENT,
                windowType, flags, PixelFormat.TRANSLUCENT);
        emergencyMenuParams.gravity = Gravity.CENTER;

        try {
            wm.addView(layout, emergencyMenuParams);
            emergencyMenuView = layout;
        } catch (Exception e) {
            Toast.makeText(service, R.string.emergency_menu_fail, Toast.LENGTH_SHORT).show();
        }
    }

    public void dismissEmergencyMenu() {
        if (emergencyMenuView != null) {
            try { wm.removeView(emergencyMenuView); } catch (Exception e) {}
            emergencyMenuView = null;
        }
    }

    private void showDoubleConfirm(final String title, final Runnable action) {
        LinearLayout layout = new LinearLayout(service);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(colorHelper.dpToPx(16));
        int bgColor = (0xFF4444 & 0x00FFFFFF) | (0xCC << 24);
        bg.setColor(bgColor);
        layout.setBackground(bg);
        layout.setPadding(30, 25, 30, 20);

        TextView titleView = new TextView(service);
        titleView.setText(R.string.double_confirm_title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, 10);
        layout.addView(titleView);

        TextView msgView = new TextView(service);
        msgView.setText(service.getString(R.string.double_confirm_msg, title));
        msgView.setTextColor(Color.WHITE);
        msgView.setTextSize(14);
        msgView.setGravity(Gravity.CENTER);
        msgView.setPadding(0, 0, 0, 20);
        layout.addView(msgView);

        LinearLayout btnRow = new LinearLayout(service);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button cancelBtn = new Button(service);
        cancelBtn.setText(R.string.cancel);
        cancelBtn.setTextSize(14);
        cancelBtn.setTextColor(Color.WHITE);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setCornerRadius(colorHelper.dpToPx(16));
        cancelBg.setColor(Color.argb(80, 0, 0, 0));
        cancelBtn.setBackground(cancelBg);
        cancelBtn.setPadding(24, 8, 24, 8);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dismissEmergencyMenu(); }
        });
        btnRow.addView(cancelBtn);

        TextView spacer2 = new TextView(service);
        spacer2.setWidth(colorHelper.dpToPx(16));
        btnRow.addView(spacer2);

        Button okBtn = new Button(service);
        okBtn.setText(R.string.confirm_ok);
        okBtn.setTextSize(14);
        okBtn.setTextColor(Color.WHITE);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setCornerRadius(colorHelper.dpToPx(16));
        okBg.setColor(0xCCFF4444);
        okBtn.setBackground(okBg);
        okBtn.setPadding(24, 8, 24, 8);
        okBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dismissEmergencyMenu(); showSecondConfirm(title, action); }
        });
        btnRow.addView(okBtn);
        layout.addView(btnRow);

        addOverlayView(layout, colorHelper.dpToPx(300));
        emergencyMenuView = layout;
    }

    private void showSecondConfirm(final String title, final Runnable action) {
        LinearLayout layout = new LinearLayout(service);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(colorHelper.dpToPx(16));
        int bgColor = (0xFF0000 & 0x00FFFFFF) | (0xDD << 24);
        bg.setColor(bgColor);
        layout.setBackground(bg);
        layout.setPadding(30, 25, 30, 20);

        TextView titleView = new TextView(service);
        titleView.setText(R.string.second_confirm_title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, 10);
        layout.addView(titleView);

        TextView msgView = new TextView(service);
        msgView.setText(service.getString(R.string.second_confirm_msg, title));
        msgView.setTextColor(Color.WHITE);
        msgView.setTextSize(14);
        msgView.setGravity(Gravity.CENTER);
        msgView.setPadding(0, 0, 0, 20);
        layout.addView(msgView);

        LinearLayout btnRow = new LinearLayout(service);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button cancelBtn = new Button(service);
        cancelBtn.setText(R.string.cancel);
        cancelBtn.setTextSize(14);
        cancelBtn.setTextColor(Color.WHITE);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setCornerRadius(colorHelper.dpToPx(16));
        cancelBg.setColor(Color.argb(80, 0, 0, 0));
        cancelBtn.setBackground(cancelBg);
        cancelBtn.setPadding(24, 8, 24, 8);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dismissEmergencyMenu(); }
        });
        btnRow.addView(cancelBtn);

        TextView spacer2 = new TextView(service);
        spacer2.setWidth(colorHelper.dpToPx(16));
        btnRow.addView(spacer2);

        Button okBtn = new Button(service);
        okBtn.setText(R.string.second_confirm_ok);
        okBtn.setTextSize(14);
        okBtn.setTextColor(Color.WHITE);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setCornerRadius(colorHelper.dpToPx(16));
        okBg.setColor(0xDDFF0000);
        okBtn.setBackground(okBg);
        okBtn.setPadding(24, 8, 24, 8);
        okBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dismissEmergencyMenu(); action.run(); }
        });
        btnRow.addView(okBtn);
        layout.addView(btnRow);

        addOverlayView(layout, colorHelper.dpToPx(300));
        emergencyMenuView = layout;
    }

    // ==================== 应用操作 ====================

    public void forceStopApp(String packageName) {
        if (ShellExecutor.forceStopApp(packageName)) {
            Toast.makeText(service, service.getString(R.string.force_stopped_toast, packageName), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(service, R.string.need_shizuku_root, Toast.LENGTH_SHORT).show();
        }
    }

    public void freezeApp(String packageName) {
        if (ShellExecutor.freezeApp(packageName)) {
            Toast.makeText(service, service.getString(R.string.frozen_toast, packageName), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(service, R.string.need_shizuku_root, Toast.LENGTH_SHORT).show();
        }
    }

    public void executeAllActions(final String packageName) {
        forceStopApp(packageName);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                freezeApp(packageName);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() { disableAppAccessibility(packageName); }
                }, 1000);
            }
        }, 1000);
    }

    public void disableAppAccessibility(String packageName) {
        try {
            String currentList = Settings.Secure.getString(
                    service.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (currentList == null || currentList.isEmpty()) {
                Toast.makeText(service, R.string.no_accessibility, Toast.LENGTH_SHORT).show();
                return;
            }
            String[] parts = currentList.split(":");
            StringBuilder newList = new StringBuilder();
            boolean found = false;
            for (String p : parts) {
                if (p.startsWith(packageName + "/")) { found = true; }
                else { if (newList.length() > 0) newList.append(":"); newList.append(p); }
            }
            if (!found) { Toast.makeText(service, R.string.no_accessibility, Toast.LENGTH_SHORT).show(); return; }
            String result = newList.toString();
            boolean done = ShellExecutor.execShizuku(new String[]{"settings", "put", "secure", "enabled_accessibility_services", result});
            if (!done) done = ShellExecutor.execRoot("settings put secure enabled_accessibility_services '" + result + "'");
            if (done) {
                Toast.makeText(service, service.getString(R.string.disabled_accessibility) + ": " + packageName, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(service, R.string.shizuku_no_perm, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(service, R.string.op_failed, Toast.LENGTH_SHORT).show();
        }
    }

    public void uninstallApp(String packageName) {
        // 1. 设备管理员静默卸载
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) service.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(service, DeviceAdmin.class);
            if (dpm.isAdminActive(admin)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        java.lang.reflect.Method method = DevicePolicyManager.class.getMethod("uninstallPackage", ComponentName.class, String.class);
                        method.invoke(dpm, admin, packageName);
                        Toast.makeText(service, service.getString(R.string.admin_uninstall_ok, packageName), Toast.LENGTH_SHORT).show();
                        return;
                    } catch (Exception e) {
                        Toast.makeText(service, R.string.admin_uninstall_fail, Toast.LENGTH_LONG).show();
                    }
                }
            }
        } catch (Exception e) {}

        // 2. Shizuku 静默卸载
        if (ShellExecutor.uninstallApp(packageName)) {
            Toast.makeText(service, service.getString(R.string.shizuku_uninstall_ok, packageName), Toast.LENGTH_SHORT).show();
            return;
        }

        // 3. Root 静默卸载
        if (ShellExecutor.execRoot("pm uninstall --user 0 " + packageName)) {
            Toast.makeText(service, service.getString(R.string.root_uninstall_ok, packageName), Toast.LENGTH_SHORT).show();
            return;
        }

        // 4. 兜底：系统卸载页
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE);
            intent.setData(Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(service, R.string.open_uninstall_fail, Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 辅助方法 ====================

    private void addOverlayView(View view, int width) {
        int wType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT, wType, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        try { wm.addView(view, params); } catch (Exception e) {
            Toast.makeText(service, R.string.popup_show_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private Set<String> getEnabledAccessibilityPackages() {
        Set<String> pkgs = new HashSet<String>();
        try {
            String enabledStr = Settings.Secure.getString(
                    service.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledStr != null && !enabledStr.isEmpty()) {
                String[] services = enabledStr.split(":");
                for (String s : services) {
                    int idx = s.indexOf('/');
                    if (idx > 0) pkgs.add(s.substring(0, idx));
                }
            }
        } catch (Exception ignored) {}
        return pkgs;
    }

    private List<AppInfo> getThirdPartyApps() {
        Set<String> accPkgs = getEnabledAccessibilityPackages();
        Set<String> virusPkgs = VirusPackages.getVirusPackages(service);
        Set<String> managedPkgs = VirusPackages.getManagedPackages(service);
        List<AppInfo> virusList = new ArrayList<AppInfo>();
        List<AppInfo> list = new ArrayList<AppInfo>();
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo app : apps) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                        && !app.packageName.equals(service.getPackageName())) {
                    AppInfo ai = new AppInfo();
                    ai.name = pm.getApplicationLabel(app).toString();
                    ai.packageName = app.packageName;
                    ai.hasAccessibility = accPkgs.contains(app.packageName);
                    ai.isManaged = managedPkgs.contains(app.packageName);
                    if (virusPkgs.contains(app.packageName)) virusList.add(ai);
                    else list.add(ai);
                }
            }
            Collections.sort(list, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo a, AppInfo b) {
                    if (a.hasAccessibility != b.hasAccessibility)
                        return a.hasAccessibility ? -1 : 1;
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
            List<AppInfo> merged = new ArrayList<AppInfo>();
            merged.addAll(virusList);
            merged.addAll(list);
            return merged;
        } catch (Exception e) {
            Toast.makeText(service, R.string.list_error, Toast.LENGTH_SHORT).show();
        }
        return list;
    }

    private boolean isRootAvailable() {
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            br.close();
            p.destroy();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    public void cleanup() {
        dismissList();
        dismissConfirm();
        dismissMoreMenu();
        dismissEmergencyMenu();
        if (statusBarView != null) {
            try { wm.removeView(statusBarView); } catch (Exception e) {}
            statusBarView = null;
        }
    }
}
