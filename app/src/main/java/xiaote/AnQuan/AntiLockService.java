package xiaote.AnQuan;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AntiLockService extends AccessibilityService {

    private PackageManager pm;
    private Handler handler;
    private SharedPreferences dotPrefs;

    private OverlayManager overlayManager;
    private EmergencyManager emergencyManager;
    private AutoClickHelper autoClickHelper;
    private VirusManager virusManager;
    private ColorHelper colorHelper;

    private boolean screenOn = true;
    private String currentForegroundPkg = "";
    private final Set<String> smartTempPkgs = new HashSet<String>();
    // 智能管理锁屏关闭后的延迟恢复机制
    private final Set<String> screenOffSmartPkgs = new HashSet<String>();  // 锁屏前开启的智能管理应用
    private final Set<String> smartRecoverPending = new HashSet<String>();  // 延迟恢复期，保持关闭
    private final Set<String> smartUserDeferred = new HashSet<String>();    // 用户选"暂不"，保持关闭
    private boolean smartRecoverNotified = false;  // 防止亮屏重复通知（SCREEN_ON+USER_PRESENT）
    private Runnable smartRecoverTimeout;
    private BroadcastReceiver smartRecoverReceiver;

    private Runnable forceTopTask;
    private boolean forceTopRunning = false;
    private Runnable volumeCheckTask;

    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver imeReceiver;
    private BroadcastReceiver packageReceiver;
    private BroadcastReceiver notificationActionReceiver;
    private BroadcastReceiver autoAuthorizeReceiver;
    private BroadcastReceiver autoSetupAdminReceiver;

    private int volumePressCount = 0;
    private long lastVolumePressTime = 0;
    private static final long VOLUME_TIME_WINDOW = 3000;
    private static final int VOLUME_PRESS_THRESHOLD = 10;
    // 音量变化事件监听（弥补纯轮询窗口期，退到后台也能即时响应）
    private android.database.ContentObserver volumeObserver;
    private final Object volumeTriggerLock = new Object();
    private boolean pendingVolumeTrigger = false; // "曾达到阈值需唤起"标志，与防篡改压低音量解耦

    private static final String ACTION_SHOW_UNINSTALL = "xiaote.AnQuan.SHOW_UNINSTALL";
    // 智能识别风险文字：诱导开启权限的木马话术关键短语（模糊匹配，命中核心短语且总数>=2判风险）
    private static final String[] RISK_GUIDE_KEYWORDS = {
            "开启", "权限", "已安装的服务", "选择本应用", "下一步", "确定", "其他厂商"
    };
    private View riskBanner = null; // 顶部风险提示横幅
    private static final String TAG = "AntiLock";
    // 无障碍组件两种写法：标准（全限定）与短写法（系统/部分工具可能写入），比较时都认
    private static final String COMPONENT_STD = "xiaote.AnQuan/xiaote.AnQuan.AntiLockService";
    private static final String COMPONENT_SHORT = "xiaote.AnQuan/.AntiLockService";
    // 移除→加回期间置锁，禁止 checkAndRecover 抢写列表，避免命令互相覆盖
    private volatile boolean accessibilityRecoverLock = false;
    private android.os.PowerManager powerManager; // 用于实时查询屏幕亮灭状态
    private static final String ACTION_NOTIFY_UNINSTALL = "xiaote.AnQuan.NOTIFY_UNINSTALL";
    private static final String ACTION_NOTIFY_MANAGE = "xiaote.AnQuan.NOTIFY_MANAGE";
    private static final String ACTION_NOTIFY_IGNORE = "xiaote.AnQuan.NOTIFY_IGNORE";
    private static final String ACTION_AUTO_AUTHORIZE = "xiaote.AnQuan.AUTO_AUTHORIZE";
    private static final String ACTION_AUTO_SETUP_ADMIN = "xiaote.AnQuan.AUTO_SETUP_ADMIN";
    private static final String ACTION_SMART_RECOVER = "xiaote.AnQuan.SMART_RECOVER";
    private static final String ACTION_SMART_DEFER = "xiaote.AnQuan.SMART_DEFER";

    @Override
    public void onServiceConnected() {
        Log.d(TAG, "onServiceConnected: 无障碍服务已连接");
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        pm = getPackageManager();
        handler = new Handler();
        dotPrefs = getSharedPreferences("dot_config", MODE_PRIVATE);
        powerManager = (android.os.PowerManager) getSystemService(POWER_SERVICE);

        colorHelper = new ColorHelper(this);
        emergencyManager = new EmergencyManager(this, handler);
        overlayManager = new OverlayManager(this, handler, colorHelper, emergencyManager);
        autoClickHelper = new AutoClickHelper(this, handler);
        virusManager = new VirusManager(this);

        dotPrefs.registerOnSharedPreferenceChangeListener(dotPrefsListener);

        overlayManager.createStatusBarDot();
        if (dotPrefs.getBoolean("background_keep_alive", true)) {
            startForegroundService();
        }
        registerScreenReceiver();
        registerIMEReceiver();
        registerPackageReceiver();
        registerNotificationActionReceiver();
        registerAutoAuthorizeReceiver();
        registerAutoSetupAdminReceiver();
        registerSmartRecoverReceiver();
        applyAccessibilityModes();
        startForceTopTask();
        startVolumeCheckTask();
        registerVolumeObserver();
        }

    private final SharedPreferences.OnSharedPreferenceChangeListener dotPrefsListener =
    new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key == null) return;
            if (key.startsWith("dot_")) {
                handler.post(new Runnable() {
                        @Override
                        public void run() {
                            overlayManager.createStatusBarDot();
                        }
                    });
            } else if (key.equals("force_top")) {
                handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (dotPrefs.getBoolean("force_top", false)) startForceTopTask();
                            else stopForceTopTask();
                        }
                    });
            } else if (key.equals("background_keep_alive")) {
                handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (dotPrefs.getBoolean("background_keep_alive", true)) startForegroundService();
                            else try { stopForeground(true); } catch (Exception e) {}
                        }
                    });
            } else if (key.equals("block_virus") || key.equals("notify_install") || key.equals("manage_install")) {
                handler.post(new Runnable() {
                        @Override
                        public void run() {
                            unregisterPackageReceiver();
                            registerPackageReceiver();
                        }
                    });
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event.getPackageName() != null) {
                String pkg = event.getPackageName().toString();
                boolean pkgChanged = !pkg.equals(currentForegroundPkg);
                if (pkgChanged) currentForegroundPkg = pkg;
                if (ProtectedPackages.isProtected(this, pkg) || !smartTempPkgs.isEmpty()) {
                    handler.post(new Runnable() {
                            @Override
                            public void run() {
                                applyAccessibilityModes();
                            }
                        });
                }
            }
        } catch (Exception ignored) {}

        try {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                final String evtCls = event.getClassName() != null ? event.getClassName().toString() : "";
                final String evtPkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
                // 立即拷贝事件文本（AccessibilityEvent 会被系统复用，不能延迟读取）
                final String evtText = collectEventText(event);
                handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            checkAndHandleAdminDialog();
                            checkAndHandleShizukuDialog();
                            checkAndHandleSubSettings(evtCls, evtPkg);
                            checkRiskGuideText(evtPkg, evtText);
                        }
                    }, 300);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
            && event.getAction() == KeyEvent.ACTION_DOWN) {
            long now = System.currentTimeMillis();
            if (now - lastVolumePressTime > VOLUME_TIME_WINDOW) {
                volumePressCount = 1;
            } else {
                volumePressCount++;
            }
            lastVolumePressTime = now;
            if (volumePressCount >= VOLUME_PRESS_THRESHOLD) {
                volumePressCount = 0;
                handler.post(new Runnable() {
                        @Override
                        public void run() {
                            overlayManager.showUninstallList();
                        }
                    });
                return true;
            }
        }
        return super.onKeyEvent(event);
    }

    private void checkAndHandleAdminDialog() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            String appName = getString(R.string.app_name);
            List<AccessibilityNodeInfo> stopNodes = root.findAccessibilityNodeInfosByText("停用此设备管理应用");
            if (stopNodes == null || stopNodes.isEmpty()) {
                stopNodes = root.findAccessibilityNodeInfosByText("停用设备管理应用");
            }
            boolean hasStop = stopNodes != null && !stopNodes.isEmpty();
            boolean hasName = false;
            if (hasStop) {
                List<AccessibilityNodeInfo> nameNodes = root.findAccessibilityNodeInfosByText(appName);
                if (nameNodes == null || nameNodes.isEmpty()) {
                    nameNodes = root.findAccessibilityNodeInfosByText("星特安全");
                }
                hasName = nameNodes != null && !nameNodes.isEmpty();
            }

            if (hasStop && hasName) {
                long unlockTime = dotPrefs.getLong("admin_unlock_time", 0);
                if (System.currentTimeMillis() < unlockTime) {
                    root.recycle();
                    return;
                }
                boolean clicked = autoClickHelper.autoClickButton(root, "取消");
                if (!clicked) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    clicked = true;
                }
                if (clicked) {
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.putExtra("show_math_verify", true);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                }
            }
            root.recycle();
        } catch (Exception ignored) {}
    }

    private void checkAndHandleSubSettings(String evtCls, String evtPkg) {
        try {
            // 事件类名才是 Activity 类名；包名是 com.android.settings
            boolean isSub = evtCls.contains("SubSettings") || evtCls.contains("AccessibilitySettings");
            boolean isSettingsPkg = evtPkg.contains("com.android.settings");
            if (!isSub && !isSettingsPkg) return;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            boolean hasDesc = false;
            // 1) 精确匹配整段（平台可能完整显示）
            List<AccessibilityNodeInfo> exact = root.findAccessibilityNodeInfosByText("星特安全无障碍服务，用于在锁机状态下弹出卸载列表，帮助卸载恶意应用。");
            if (exact != null && !exact.isEmpty()) hasDesc = true;
            // 2) 模糊匹配关键子串（防截断/换行）
            if (!hasDesc) hasDesc = hasTextContains(root, "星特安全无障碍服务") || hasTextContains(root, "帮助卸载恶意应用");

            if (hasDesc) {
                long unlockTime = dotPrefs.getLong("admin_unlock_time", 0);
                if (System.currentTimeMillis() < unlockTime) {
                    root.recycle();
                    return;
                }
                // 模拟返回一次，避免被连续触发
                performGlobalAction(GLOBAL_ACTION_BACK);
                // 延迟后再跳转主界面唤起数学验证，避免与返回手势冲突
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Intent intent = new Intent(AntiLockService.this, MainActivity.class);
                        intent.putExtra("show_math_verify", true);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(intent);
                    }
                }, 400);
            }
            root.recycle();
        } catch (Exception ignored) {}
    }

    /** 遍历节点树，模糊匹配是否包含目标文字 */
    private boolean hasTextContains(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;
        try {
            CharSequence text = node.getText();
            if (text != null && text.toString().contains(target)) return true;
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().contains(target)) return true;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (hasTextContains(child, target)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void checkAndHandleShizukuDialog() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            List<AccessibilityNodeInfo> allowNodes = root.findAccessibilityNodeInfosByText("要允许");
            boolean hasAllow = allowNodes != null && !allowNodes.isEmpty();
            boolean hasShizuku = false;
            if (hasAllow) {
                List<AccessibilityNodeInfo> shizukuNodes = root.findAccessibilityNodeInfosByText("Shizuku");
                if (shizukuNodes == null || shizukuNodes.isEmpty()) {
                    shizukuNodes = root.findAccessibilityNodeInfosByText("shizuku");
                }
                hasShizuku = shizukuNodes != null && !shizukuNodes.isEmpty();
            }

            if (hasAllow && hasShizuku) {
                String selfName = getString(R.string.app_name);
                boolean isSelf = false;
                for (AccessibilityNodeInfo node : allowNodes) {
                    if (node == null) continue;
                    CharSequence nodeText = node.getText();
                    if (nodeText == null) nodeText = node.getContentDescription();
                    if (nodeText != null) {
                        String txt = nodeText.toString();
                        if (txt.contains(selfName) || txt.contains("星特安全") || txt.contains("XT Safe")) {
                            isSelf = true;
                            break;
                        }
                    }
                }
                if (isSelf) {
                    root.recycle();
                    return;
                }
                long shizukuUnlock = dotPrefs.getLong("shizuku_unlock_until", 0);
                if (System.currentTimeMillis() < shizukuUnlock) {
                    root.recycle();
                    return;
                }
                if (!dotPrefs.contains("app_password_hash")) {
                    root.recycle();
                    return;
                }
                boolean clicked = autoClickHelper.autoClickButton(root, "拒绝");
                if (!clicked) clicked = autoClickHelper.autoClickButton(root, "取消");
                if (clicked) {
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.putExtra("show_password_verify", true);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                }
            }
            root.recycle();
        } catch (Exception ignored) {}
    }

    private void applyAccessibilityModes() {
        try {
            // 移除→加回期间跳过，避免与 forceTopNow 异步写列表竞态
            if (accessibilityRecoverLock) return;
            String currentList = Settings.Secure.getString(getContentResolver(),
                                                           Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (currentList == null) currentList = "";

            Set<String> virusPkgs = VirusPackages.getVirusPackages(this);
            virusPkgs.addAll(VirusPackages.getManagedPackages(this));
            long now = System.currentTimeMillis();

            String[] parts = currentList.split(":");
            Set<String> keep = new HashSet<String>();
            Set<String> disable = new HashSet<String>();

            for (String p : parts) {
                if (p.isEmpty()) continue;
                int idx = p.indexOf('/');
                String pkg = idx > 0 ? p.substring(0, idx) : p;
                if (pkg.equals(getPackageName())) { keep.add(p); continue; }
                if (virusPkgs.contains(pkg)) { disable.add(pkg); continue; }

                int mode = dotPrefs.getInt("acc_mode_" + pkg, -1);
                boolean shouldDisable = false;
                switch (mode) {
                    case AccessibilityManagerActivity.MODE_FORBIDDEN: shouldDisable = true; break;
                    case AccessibilityManagerActivity.MODE_ALLOW: break;
                    case AccessibilityManagerActivity.MODE_ALLOW_10MIN:
                        long start = dotPrefs.getLong("acc_mode_" + pkg + "_start", 0);
                        if (now - start > 10 * 60 * 1000L) shouldDisable = true;
                        break;
                    case AccessibilityManagerActivity.MODE_LOCK_FORBID:
                        if (!screenOn) shouldDisable = true;
                        break;
                    case AccessibilityManagerActivity.MODE_SMART:
                        if (ProtectedPackages.isProtected(this, currentForegroundPkg) || !screenOn
                            || smartRecoverPending.contains(pkg) || smartUserDeferred.contains(pkg)) {
                            smartTempPkgs.add(p);
                            shouldDisable = true;
                        }
                        break;
                    default: break;
                }
                if (shouldDisable) disable.add(pkg);
                else keep.add(p);
            }

            boolean inSensitiveNow = ProtectedPackages.isProtected(this, currentForegroundPkg);
            if (!inSensitiveNow && screenOn && !smartTempPkgs.isEmpty()) {
                Set<String> keepSet = new HashSet<String>(keep);
                for (String savedComp : smartTempPkgs) {
                    int idx = savedComp.indexOf('/');
                    String spkg = idx > 0 ? savedComp.substring(0, idx) : savedComp;
                    if (spkg.equals(getPackageName())) continue;
                    if (virusPkgs.contains(spkg)) continue;
                    // 延迟恢复期或用户选"暂不"的，不在此自动恢复
                    if (smartRecoverPending.contains(spkg) || smartUserDeferred.contains(spkg)) continue;
                    int mode = dotPrefs.getInt("acc_mode_" + spkg, -1);
                    if (mode == AccessibilityManagerActivity.MODE_SMART
                        && !keepSet.contains(savedComp) && !disable.contains(spkg)) {
                        keep.add(savedComp);
                    }
                }
                smartTempPkgs.clear();
            }

            try {
                android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
                List<android.accessibilityservice.AccessibilityServiceInfo> services = am.getInstalledAccessibilityServiceList();
                if (services != null) {
                    for (android.accessibilityservice.AccessibilityServiceInfo info : services) {
                        if (info == null || info.getResolveInfo() == null) continue;
                        String pkg = info.getResolveInfo().serviceInfo.packageName;
                        String comp = pkg + "/" + info.getResolveInfo().serviceInfo.name;
                        if (pkg.equals(getPackageName())) continue;
                        if (virusPkgs.contains(pkg)) continue;
                        int mode = dotPrefs.getInt("acc_mode_" + pkg, -1);
                        if (mode == AccessibilityManagerActivity.MODE_ALLOW
                            && !currentList.contains(comp) && !disable.contains(pkg)) {
                            keep.add(comp);
                        }
                    }
                }
            } catch (Exception ignored) {}

            StringBuilder sb = new StringBuilder();
            for (String p : keep) {
                int idx = p.indexOf('/');
                String pkg = idx > 0 ? p.substring(0, idx) : p;
                if (disable.contains(pkg)) continue;
                if (sb.length() > 0) sb.append(":");
                sb.append(p);
            }
            String newList = sb.toString();
            if (!newList.equals(currentList)) {
                ShellExecutor.execShizuku(new String[]{"settings", "put", "secure", "enabled_accessibility_services", newList});
                ShellExecutor.execRoot("settings put secure enabled_accessibility_services '" + newList + "'");
            }
        } catch (Exception ignored) {}
    }

    private void startForceTopTask() {
        stopForceTopTask();
        if (!dotPrefs.getBoolean("force_top", true)) return;
        forceTopRunning = true;
        final int interval = Math.max(3, dotPrefs.getInt("force_interval", 5));
        Log.d(TAG, "startForceTopTask: 置顶循环启动 interval=" + interval + "s");
        forceTopTask = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!forceTopRunning || handler == null) return;
                    // 熄屏跳过动作（不影响锁屏/避免亮屏），循环继续跑，亮屏后自动恢复
                    if (dotPrefs.getBoolean("force_stop_on_screen_off", true)) {
                        boolean screenInteractive = powerManager != null && powerManager.isInteractive();
                        if (!screenInteractive) return;
                    }
                    doForceTopByMode();
                    if (dotPrefs.getBoolean("block_virus", true)) {
                        virusManager.scanAndBlockVirusApps();
                    }
                    applyAccessibilityModes();
                    checkAndRecoverAccessibility();
                } catch (Exception e) {
                    // 防止任何异常导致循环终止
                } finally {
                    // 使用 this 而非 forceTopTask，避免 stopForceTopTask 置空后调度失败
                    if (forceTopRunning && handler != null) {
                        handler.postDelayed(this, interval * 1000);
                    }
                }
            }
        };
        handler.postDelayed(forceTopTask, interval * 1000);
    }

    private void stopForceTopTask() {
        forceTopRunning = false;
        if (forceTopTask != null && handler != null) {
            handler.removeCallbacks(forceTopTask);
            forceTopTask = null;
        }
        Log.d(TAG, "stopForceTopTask: 置顶循环停止");
    }

    /** 从无障碍列表字符串中移除本服务的两种写法（标准/短），返回剩余列表 */
    private static String removeSelf(String list) {
        if (list == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : list.split(":")) {
            if (p.equals(COMPONENT_STD) || p.equals(COMPONENT_SHORT)) continue;
            if (sb.length() > 0) sb.append(":");
            sb.append(p);
        }
        return sb.toString();
    }

    private void forceTopNow() {
        // 如果卸载列表正在显示，跳过强制置顶，避免列表被关闭
        if (overlayManager != null && overlayManager.isListShowing()) {
            return;
        }
        // 顶部风险横幅显示期间跳过移除加回，避免服务重启把横幅清掉/造成频闪
        if (riskBanner != null) {
            return;
        }
        // 移到子线程执行，避免 Thread.sleep 阻塞主线程，且外层 try-catch 确保异常不会中断循环
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String currentList = Settings.Secure.getString(getContentResolver(),
                                                                   Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    if (currentList == null) currentList = "";
                    boolean found = false;
                    for (String item : currentList.split(":")) {
                        if (item.equals(COMPONENT_STD) || item.equals(COMPONENT_SHORT)) { found = true; break; }
                    }
                    if (!found) {
                        // 不在列表：清理自己两种写法残留后，用标准写法写回加入
                        String clean = removeSelf(currentList);
                        String target = clean.isEmpty() ? COMPONENT_STD : clean + ":" + COMPONENT_STD;
                        boolean sOk = ShellExecutor.execShizuku(new String[]{"settings", "put", "secure", "enabled_accessibility_services", target});
                        boolean rOk = ShellExecutor.execRoot("settings put secure enabled_accessibility_services '" + target + "'");
                        Log.d(TAG, "forceTopNow: 不在列表，写回加入 list=" + target + " shizuku=" + sOk + " root=" + rOk);
                        return;
                    }
                    // 在列表：移除→加回确保置顶。上锁期间禁止 checkAndRecover 抢写
                    accessibilityRecoverLock = true;
                    try {
                        Log.d(TAG, "forceTopNow: 在列表，执行移除→加回");
                        // 移除：重新读取最新列表，避免用方法开头的旧快照覆盖 applyAccessibilityModes 的禁用
                        String latestRemove = Settings.Secure.getString(getContentResolver(),
                                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                        if (latestRemove == null) latestRemove = "";
                        String without = removeSelf(latestRemove);
                        boolean s1 = ShellExecutor.execShizuku(new String[]{"settings", "put", "secure", "enabled_accessibility_services", without});
                        boolean r1 = ShellExecutor.execRoot("settings put secure enabled_accessibility_services '" + without + "'");
                        try { Thread.sleep(500); } catch (Exception ignored) {}
                        // 加回：再次读取最新列表（移除后可能被 applyAccessibilityModes 修改），
                        // 移除自身两种写法残留后加回，确保不覆盖期间的禁用操作
                        String latestBack = Settings.Secure.getString(getContentResolver(),
                                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                        if (latestBack == null) latestBack = "";
                        String cleanBack = removeSelf(latestBack);
                        String withBack = cleanBack.isEmpty() ? COMPONENT_STD : cleanBack + ":" + COMPONENT_STD;
                        boolean s2 = ShellExecutor.execShizuku(new String[]{"settings", "put", "secure", "enabled_accessibility_services", withBack});
                        boolean r2 = ShellExecutor.execRoot("settings put secure enabled_accessibility_services '" + withBack + "'");
                        Log.d(TAG, "forceTopNow: 移除(shizuku=" + s1 + ",root=" + r1 + ") 加回(shizuku=" + s2 + ",root=" + r2 + ") withBack=" + withBack);
                    } finally {
                        accessibilityRecoverLock = false;
                    }
                } catch (Exception e) {
                    // 子线程异常不影响主循环
                }
            }
        }).start();
    }
        

    /** 根据置顶模式执行强制置顶：0=重启服务 1=最高优先级 2=混合 */
    private void doForceTopByMode() {
        // 卸载列表或风险横幅显示期间跳过，避免重建冲突
        if (overlayManager != null && overlayManager.isListShowing()) return;
        if (riskBanner != null) return;
        int mode = dotPrefs.getInt("force_top_mode", 0);
        if (mode == 0 || mode == 2) {
            forceTopNow();  // 重启服务：移除→加回无障碍，触发服务重新绑定
        }
        if (mode == 1 || mode == 2) {
            if (overlayManager != null) {
                overlayManager.reCreateStatusBarDot();  // 最高优先级：重新创建悬浮窗确保最顶层
            }
        }
    }

    private void checkAndRecoverAccessibility() {
        try {
            // 移除→加回期间跳过，避免抢写列表
            if (accessibilityRecoverLock) return;
            String currentList = Settings.Secure.getString(getContentResolver(),
                                                           Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (currentList == null) currentList = "";
            boolean found = false;
            for (String item : currentList.split(":")) {
                if (item.equals(COMPONENT_STD) || item.equals(COMPONENT_SHORT)) { found = true; break; }
            }
            if (!found) {
                String clean = removeSelf(currentList);
                String target = clean.isEmpty() ? COMPONENT_STD : clean + ":" + COMPONENT_STD;
                boolean sOk = ShellExecutor.execShizuku(new String[]{"settings", "put", "secure", "enabled_accessibility_services", target});
                boolean rOk = ShellExecutor.execRoot("settings put secure enabled_accessibility_services '" + target + "'");
                Log.d(TAG, "checkAndRecover: 不在列表，写回加入 shizuku=" + sOk + " root=" + rOk);
            }
        } catch (Exception ignored) {}
    }

    /** 立即拷贝事件文本（AccessibilityEvent 被系统复用，不能延迟读取） */
    private String collectEventText(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        try {
            // 1) 事件自带文本
            if (event.getText() != null) {
                for (CharSequence c : event.getText()) {
                    if (c != null) sb.append(c).append(" ");
                }
            }
            // 2) 源节点深度遍历（弹窗文字多在节点上，event.getText 常常为空）
            android.view.accessibility.AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                collectNodeText(source, sb, 0);
            }
            // 3) 弹窗出现（窗口状态变化）或文字不足时，直接遍历整个活动窗口取全文本 → 及时识别
            boolean isStateChange = event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            if (isStateChange || sb.length() < 10) {
                android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    collectNodeText(root, sb, 0);
                }
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /** 深度遍历节点收集 text / contentDescription（限制深度与子节点数防卡顿） */
    private void collectNodeText(android.view.accessibility.AccessibilityNodeInfo node,
                                 StringBuilder sb, int depth) {
        try {
            if (node == null || depth > 12) return;
            CharSequence t = node.getText();
            if (t != null && t.length() > 0) sb.append(t).append(" ");
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.length() > 0) sb.append(desc).append(" ");
            int count = node.getChildCount();
            if (count > 100) count = 100;
            for (int i = 0; i < count; i++) {
                android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                collectNodeText(child, sb, depth + 1);
            }
        } catch (Exception ignored) {}
    }

    /**
     * 智能识别风险文字：窗口文案模糊匹配"诱导开启权限"的木马话术
     * （如"请开启 xxx 权限：1.点击本页《下一步》2.找到《已安装的服务》3.选择本应用..."）。
     * 命中核心短语（已安装的服务/选择本应用）且总命中数>=2 判为风险，通知提醒可唤起卸载。
     */
    /** 模糊匹配是否为"诱导开启权限"的风险话术 */
    private boolean isRiskGuideText(String text) {
        if (text == null) return false;
        String t = text.replaceAll("\\s+", "");
        if (t.isEmpty()) return false;
        int hit = 0;
        boolean core = false;
        for (String kw : RISK_GUIDE_KEYWORDS) {
            if (t.contains(kw)) {
                hit++;
                if ("已安装的服务".equals(kw) || "选择本应用".equals(kw)) core = true;
            }
        }
        return core && hit >= 2;
    }

    /**
     * 风险文字状态机：
     * 命中风险 → 未显示则显示横幅（已显示则保持，不重建防频闪）
     * 未命中风险 → 风险文字已消失，收起横幅
     */
    private void checkRiskGuideText(String pkg, String text) {
        try {
            if (!dotPrefs.getBoolean("smart_risk_text_notify", true)) {
                dismissRiskBanner();
                return;
            }
            if (isRiskGuideText(text)) {
                if (riskBanner == null) showRiskTopBanner(pkg);
            } else {
                dismissRiskBanner();
            }
        } catch (Exception ignored) {}
    }

    /** 顶部风险提示横幅：瞬时显示，3秒自动消失，点击直接唤起卸载（无系统通知，不轰炸） */
    private void showRiskTopBanner(final String pkg) {
        try {
            if (riskBanner != null) return; // 已在显示则不再重复创建，防频闪
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;
            final float density = getResources().getDisplayMetrics().density;

            LinearLayout banner = new LinearLayout(this);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setPadding((int) (12 * density), (int) (6 * density), (int) (12 * density), (int) (6 * density));
            banner.setBackgroundColor(0xCCD32F2F); // 半透明红
            banner.setClickable(true);

            TextView tv = new TextView(this);
            tv.setText(getString(R.string.risk_banner));
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(14);
            tv.setGravity(Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            banner.addView(tv);
            banner.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismissRiskBanner();
                    if (pkg != null && !pkg.isEmpty()) overlayManager.showUninstallList(pkg);
                    else overlayManager.showUninstallList();
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            lp.windowAnimations = android.R.style.Animation_Dialog;

            wm.addView(banner, lp);
            riskBanner = banner;
            // 不设自动消失：由 checkRiskGuideText 检测到风险文字消失时才收起
        } catch (Exception ignored) {}
    }

    private void dismissRiskBanner() {
        if (riskBanner == null) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) wm.removeView(riskBanner);
        } catch (Exception ignored) {}
        riskBanner = null;
    }

    private void startVolumeCheckTask() {
        stopVolumeCheckTask();
        volumeCheckTask = new Runnable() {
            @Override
            public void run() {
                if (handler == null) return;
                checkVolumeAndReduce();
                int volInterval = dotPrefs.getInt("volume_check_interval", 1);
                handler.postDelayed(volumeCheckTask, Math.max(100, volInterval * 100L));
            }
        };
        handler.postDelayed(volumeCheckTask, Math.max(100, dotPrefs.getInt("volume_check_interval", 1) * 100L));
    }

    private void stopVolumeCheckTask() {
        if (volumeCheckTask != null && handler != null) {
            handler.removeCallbacks(volumeCheckTask);
            volumeCheckTask = null;
        }
    }

    private void checkVolumeAndReduce() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int threshold = dotPrefs.getInt("volume_threshold", 80);
            boolean reachedThreshold = max > 0 && current * 100 / max >= threshold;

            // 防音量恶意修改：达到阈值即压回0
            if (dotPrefs.getBoolean("volume_protect", false) && reachedThreshold) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            }

            // 唤起卸载列表：用标志位记录"曾达到阈值"，与防篡改压低音量解耦。
            // 防篡改已把音量压到0，若仍依赖 current 判断则永远不会再触发，故改为标志位驱动。
            if (reachedThreshold) {
                synchronized (volumeTriggerLock) {
                    pendingVolumeTrigger = true;
                }
            }
            boolean shouldTrigger = false;
            synchronized (volumeTriggerLock) {
                if (pendingVolumeTrigger) {
                    long now = System.currentTimeMillis();
                    long last = dotPrefs.getLong("last_volume_max_uninstall_time", 0);
                    if (now - last > 5000) {
                        dotPrefs.edit().putLong("last_volume_max_uninstall_time", now).apply();
                        pendingVolumeTrigger = false;
                        shouldTrigger = true;
                    } else {
                        pendingVolumeTrigger = false; // 冷却期内丢弃，避免标志堆积
                    }
                }
            }
            if (shouldTrigger) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dotPrefs.getBoolean("volume_max_show_uninstall", false)) {
                            triggerUninstallList();
                        }
                        if (dotPrefs.getBoolean("volume_force_stop_all", false) && emergencyManager != null) {
                            emergencyManager.forceStopAll();
                        }
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    /** 唤起卸载列表：优先悬浮窗，失败兜底高优先级通知，保证退到后台也有路径进入应用 */
    private void triggerUninstallList() {
        if (overlayManager != null) {
            try {
                overlayManager.showUninstallList();
                return;
            } catch (Exception e) {}
        }
        // 悬浮窗失败兜底：高优先级通知（点击进入主界面）
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String channelId = "volume_trigger";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(channelId, getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            Intent main = new Intent(this, MainActivity.class);
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 9001, main,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, channelId)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_menu_delete)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.volume_trigger_notify))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH);
            nm.notify(9001, b.build());
        } catch (Exception ignored) {}
    }

    /** 注册音量变化 ContentObserver（事件驱动，弥补纯轮询窗口期） */
    private void registerVolumeObserver() {
        if (volumeObserver != null) return;
        try {
            volumeObserver = new android.database.ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    checkVolumeAndReduce();
                }
            };
            getContentResolver().registerContentObserver(
                    android.provider.Settings.System.CONTENT_URI, true, volumeObserver);
        } catch (Exception ignored) {}
    }

    private void unregisterVolumeObserver() {
        try {
            if (volumeObserver != null) {
                getContentResolver().unregisterContentObserver(volumeObserver);
                volumeObserver = null;
            }
        } catch (Exception ignored) {}
    }

    private void startForegroundService() {
        try {
            String channelId = "anti_lock_service";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(channelId,
                                                                      getString(R.string.anti_lock_channel), NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(getString(R.string.anti_lock_channel_desc));
                channel.enableVibration(false);
                channel.setSound(null, null);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(channel);
            }
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);
            builder.setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.anti_lock_running))
                .setPriority(Notification.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(0)
                .setShowWhen(false);
            startForeground(1, builder.build());
        } catch (Exception e) {}
    }

    private void registerScreenReceiver() {
        try {
            if (screenReceiver != null) return;
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        screenOn = false;
                        // 记录锁屏前开启的智能管理应用，供亮屏询问恢复
                        handleScreenOffSmart();
                        applyAccessibilityModes();
                    } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                        // 亮屏/解锁：通知询问是否恢复，5秒无操作默认恢复
                        screenOn = true;
                        handleScreenOnSmart();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED);
            else registerReceiver(screenReceiver, filter);
        } catch (Exception ignored) {}
    }

    private void unregisterScreenReceiver() {
        try {
            if (screenReceiver != null) { unregisterReceiver(screenReceiver); screenReceiver = null; }
        } catch (Exception ignored) {}
    }

    private void registerIMEReceiver() {
        try {
            if (imeReceiver != null) return;
            imeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_SHOW_UNINSTALL.equals(intent.getAction())) {
                        final String pkg = intent != null ? intent.getStringExtra("pkg") : null;
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (pkg != null && !pkg.isEmpty()) overlayManager.showUninstallList(pkg);
                                    else overlayManager.showUninstallList();
                                }
                            });
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(imeReceiver, new IntentFilter(ACTION_SHOW_UNINSTALL), Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(imeReceiver, new IntentFilter(ACTION_SHOW_UNINSTALL));
        } catch (Exception ignored) {}
    }

    private void unregisterIMEReceiver() {
        try {
            if (imeReceiver != null) { unregisterReceiver(imeReceiver); imeReceiver = null; }
        } catch (Exception ignored) {}
    }

    private void registerPackageReceiver() {
        try {
            if (packageReceiver != null) return;
            if (!dotPrefs.getBoolean("block_virus", true) && !dotPrefs.getBoolean("notify_install", true) && !dotPrefs.getBoolean("manage_install", true)) return;
            packageReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                        final String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
                        if (pkg == null || pkg.equals(getPackageName())) return;
                        boolean isVirus = VirusPackages.getVirusPackages(AntiLockService.this).contains(pkg);
                        if (dotPrefs.getBoolean("notify_install", true) && isVirus) sendInstallNotify(pkg, true);
                        if (dotPrefs.getBoolean("manage_install", true) && !isVirus) sendInstallNotify(pkg, false);
                        if (dotPrefs.getBoolean("block_virus", true) && isVirus) {
                            handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        virusManager.blockVirusPackage(pkg);
                                    }
                                });
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED);
            else registerReceiver(packageReceiver, filter);
        } catch (Exception ignored) {}
    }

    private void unregisterPackageReceiver() {
        try {
            if (packageReceiver != null) { unregisterReceiver(packageReceiver); packageReceiver = null; }
        } catch (Exception ignored) {}
    }

    private void sendInstallNotify(String pkg, boolean isVirus) {
        try {
            final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String channelId = "install_notify";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(channelId, getString(R.string.install_channel), NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription(getString(R.string.install_channel));
                nm.createNotificationChannel(ch);
            }
            String appName = pkg;
            try { appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) {}

            Intent uninstallIntent = new Intent(ACTION_NOTIFY_UNINSTALL);
            uninstallIntent.putExtra("pkg", pkg);
            uninstallIntent.setPackage(getPackageName());
            PendingIntent uninstallPi = PendingIntent.getBroadcast(this, pkg.hashCode(), uninstallIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent manageIntent = new Intent(ACTION_NOTIFY_MANAGE);
            manageIntent.putExtra("pkg", pkg);
            manageIntent.setPackage(getPackageName());
            PendingIntent managePi = PendingIntent.getBroadcast(this, pkg.hashCode() + 1, manageIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent ignoreIntent = new Intent(ACTION_NOTIFY_IGNORE);
            ignoreIntent.putExtra("pkg", pkg);
            ignoreIntent.setPackage(getPackageName());
            PendingIntent ignorePi = PendingIntent.getBroadcast(this, pkg.hashCode() + 2, ignoreIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder = new Notification.Builder(this, channelId)
                .setContentTitle((isVirus ? getString(R.string.virus_detected, appName) : getString(R.string.new_app_installed, appName)))
                .setContentText(isVirus ? getString(R.string.ask_uninstall) : getString(R.string.ask_manage))
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setAutoCancel(true);
            if (isVirus) { builder.addAction(0, getString(R.string.uninstall), uninstallPi); builder.addAction(0, getString(R.string.allow), ignorePi); }
            else { builder.addAction(0, getString(R.string.join_manage), managePi); builder.addAction(0, getString(R.string.allow), ignorePi); }
            if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(30000L);
            final int notifyId = 2000 + pkg.hashCode() % 1000;
            nm.notify(notifyId, builder.build());
            if (Build.VERSION.SDK_INT < 26) {
                handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try { nm.cancel(notifyId); } catch (Exception ignored) {}
                        }
                    }, 30000L);
            }
        } catch (Exception ignored) {}
    }

    private void registerNotificationActionReceiver() {
        try {
            if (notificationActionReceiver != null) return;
            notificationActionReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    final String pkg = intent != null ? intent.getStringExtra("pkg") : null;
                    if (pkg == null) return;
                    try {
                        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        nm.cancel(2000 + pkg.hashCode() % 1000);
                    } catch (Exception ignored) {}
                    if (ACTION_NOTIFY_UNINSTALL.equals(action)) {
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    overlayManager.uninstallApp(pkg);
                                }
                            });
                    } else if (ACTION_NOTIFY_MANAGE.equals(action)) {
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    VirusPackages.addManagedPackage(AntiLockService.this, pkg);
                                    Toast.makeText(AntiLockService.this, getString(R.string.added_managed, pkg), Toast.LENGTH_SHORT).show();
                                }
                            });
                    } else if (ACTION_NOTIFY_IGNORE.equals(action)) {
                        Toast.makeText(AntiLockService.this, getString(R.string.released, pkg), Toast.LENGTH_SHORT).show();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_NOTIFY_UNINSTALL);
            filter.addAction(ACTION_NOTIFY_MANAGE);
            filter.addAction(ACTION_NOTIFY_IGNORE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(notificationActionReceiver, filter);
        } catch (Exception ignored) {}
    }

    private void unregisterNotificationActionReceiver() {
        try {
            if (notificationActionReceiver != null) { unregisterReceiver(notificationActionReceiver); notificationActionReceiver = null; }
        } catch (Exception ignored) {}
    }

    private void registerAutoAuthorizeReceiver() {
        try {
            if (autoAuthorizeReceiver != null) return;
            autoAuthorizeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_AUTO_AUTHORIZE.equals(intent.getAction())) {
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    autoClickHelper.autoAuthorize();
                                }
                            });
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(autoAuthorizeReceiver, new IntentFilter(ACTION_AUTO_AUTHORIZE), Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(autoAuthorizeReceiver, new IntentFilter(ACTION_AUTO_AUTHORIZE));
        } catch (Exception ignored) {}
    }

    private void unregisterAutoAuthorizeReceiver() {
        try {
            if (autoAuthorizeReceiver != null) { unregisterReceiver(autoAuthorizeReceiver); autoAuthorizeReceiver = null; }
        } catch (Exception ignored) {}
    }

    private void registerAutoSetupAdminReceiver() {
        try {
            if (autoSetupAdminReceiver != null) return;
            autoSetupAdminReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_AUTO_SETUP_ADMIN.equals(intent.getAction())) {
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    autoClickHelper.autoSetupAdmin();
                                }
                            });
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(autoSetupAdminReceiver, new IntentFilter(ACTION_AUTO_SETUP_ADMIN), Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(autoSetupAdminReceiver, new IntentFilter(ACTION_AUTO_SETUP_ADMIN));
        } catch (Exception ignored) {}
    }

    private void unregisterAutoSetupAdminReceiver() {
        try {
            if (autoSetupAdminReceiver != null) { unregisterReceiver(autoSetupAdminReceiver); autoSetupAdminReceiver = null; }
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() { cleanup(); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanup();
    }

    // ==================== 智能管理锁屏恢复 ====================

    /** 锁屏时记录当前开启的智能管理应用，供亮屏询问恢复 */
    private void handleScreenOffSmart() {
        screenOffSmartPkgs.clear();
        smartRecoverPending.clear();
        smartRecoverNotified = false;
        if (smartRecoverTimeout != null && handler != null) {
            handler.removeCallbacks(smartRecoverTimeout);
            smartRecoverTimeout = null;
        }
        try {
            String currentList = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (currentList == null) currentList = "";
            for (String p : currentList.split(":")) {
                if (p.isEmpty()) continue;
                int idx = p.indexOf('/');
                String pkg = idx > 0 ? p.substring(0, idx) : p;
                if (pkg.equals(getPackageName())) continue;
                int mode = dotPrefs.getInt("acc_mode_" + pkg, -1);
                if (mode == AccessibilityManagerActivity.MODE_SMART) {
                    screenOffSmartPkgs.add(pkg);
                }
            }
        } catch (Exception ignored) {}
    }

    /** 亮屏/解锁：对锁屏前开启的智能管理应用通知询问是否恢复，5秒无操作默认恢复 */
    private void handleScreenOnSmart() {
        if (smartRecoverNotified) return;  // 防重复（SCREEN_ON + USER_PRESENT 都触发）
        if (screenOffSmartPkgs.isEmpty()) return;
        smartRecoverNotified = true;
        // 进入延迟恢复期：保持关闭，等待用户选择或5秒自动恢复
        smartRecoverPending.addAll(screenOffSmartPkgs);
        // 上次选"暂不"的，本次重新纳入询问
        for (String pkg : screenOffSmartPkgs) {
            smartUserDeferred.remove(pkg);
        }
        sendSmartRecoverNotify();
        if (smartRecoverTimeout == null) {
            smartRecoverTimeout = new Runnable() {
                @Override
                public void run() {
                    smartRecoverPending.clear();
                    cancelSmartRecoverNotify();
                    applyAccessibilityModes();  // 5秒无操作，默认恢复
                }
            };
        }
        handler.postDelayed(smartRecoverTimeout, 5000);
    }

    private void sendSmartRecoverNotify() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String channelId = "smart_recover";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(channelId, getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent recover = new Intent(ACTION_SMART_RECOVER).setPackage(getPackageName());
            PendingIntent recoverPi = PendingIntent.getBroadcast(this, 9101, recover,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent defer = new Intent(ACTION_SMART_DEFER).setPackage(getPackageName());
            PendingIntent deferPi = PendingIntent.getBroadcast(this, 9102, defer,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, channelId)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.smart_recover_notify))
                    .addAction(0, getString(R.string.smart_recover_yes), recoverPi)
                    .addAction(0, getString(R.string.smart_recover_no), deferPi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_DEFAULT);
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(6000L);
            nm.notify(9100, b.build());
        } catch (Exception ignored) {}
    }

    private void cancelSmartRecoverNotify() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.cancel(9100);
        } catch (Exception ignored) {}
    }

    private void registerSmartRecoverReceiver() {
        try {
            if (smartRecoverReceiver != null) return;
            smartRecoverReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_SMART_RECOVER.equals(action)) {
                        // 用户选恢复：立即恢复
                        if (smartRecoverTimeout != null && handler != null) {
                            handler.removeCallbacks(smartRecoverTimeout);
                        }
                        smartRecoverPending.clear();
                        cancelSmartRecoverNotify();
                        applyAccessibilityModes();
                    } else if (ACTION_SMART_DEFER.equals(action)) {
                        // 用户选暂不：移入 deferred 保持关闭，下次锁屏重新询问
                        if (smartRecoverTimeout != null && handler != null) {
                            handler.removeCallbacks(smartRecoverTimeout);
                        }
                        smartUserDeferred.addAll(smartRecoverPending);
                        smartRecoverPending.clear();
                        cancelSmartRecoverNotify();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_SMART_RECOVER);
            filter.addAction(ACTION_SMART_DEFER);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(smartRecoverReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(smartRecoverReceiver, filter);
        } catch (Exception ignored) {}
    }

    private void unregisterSmartRecoverReceiver() {
        try {
            if (smartRecoverReceiver != null) {
                unregisterReceiver(smartRecoverReceiver);
                smartRecoverReceiver = null;
            }
        } catch (Exception ignored) {}
    }

    private void cleanup() {
        Log.d(TAG, "cleanup: 服务清理/销毁");
        dismissRiskBanner();
        if (dotPrefs != null) {
            dotPrefs.unregisterOnSharedPreferenceChangeListener(dotPrefsListener);
        }
        stopForceTopTask();
        stopVolumeCheckTask();
        unregisterVolumeObserver();
        unregisterScreenReceiver();
        unregisterIMEReceiver();
        unregisterPackageReceiver();
        unregisterNotificationActionReceiver();
        unregisterAutoAuthorizeReceiver();
        unregisterAutoSetupAdminReceiver();
        unregisterSmartRecoverReceiver();
        if (smartRecoverTimeout != null && handler != null) handler.removeCallbacks(smartRecoverTimeout);
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (overlayManager != null) overlayManager.cleanup();
        try { stopForeground(true); } catch (Exception e) {}
    }
}
