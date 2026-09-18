package xiaote.AnQuan;

import xiaote.xtui.HintOverlayManager;

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

public class XTSafeMainService extends AccessibilityService implements BroadcastRegistrar.Host {

    private PackageManager pm;
    private Handler handler;
    private SharedPreferences dotPrefs;

    private OverlayManager overlayManager;
    private EmergencyManager emergencyManager;
    private AutoClickHelper autoClickHelper;
    private VirusManager virusManager;
    private ColorHelper colorHelper;

    private volatile boolean screenOn = true;
    private volatile String currentForegroundPkg = "";
    /** CONTENT_CHANGED 事件节流：高频内容变化事件最少间隔（毫秒），防止主线程被拖死 */
    private static final long CONTENT_EVENT_THROTTLE_MS = 800L;
    private long lastContentEventTime = 0L;
    private final Set<String> smartTempPkgs =
            java.util.Collections.synchronizedSet(new HashSet<String>());
    // 后台重活（无障碍模式计算/病毒扫描/Shell 调用）防堆积与节流
    private final java.util.concurrent.atomic.AtomicBoolean bgWorkRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long lastBgWorkTime = 0L;
    /** 后台重活最小间隔（毫秒），避免高频事件反复触发 shell */
    private static final long BG_WORK_MIN_INTERVAL_MS = 1000L;
    // 智能管理锁屏恢复（拆分为 SmartRecoverManager）
    private SmartRecoverManager smartRecoverManager;

    private Runnable forceTopTask;
    private boolean forceTopRunning = false;

    // 广播注册（拆分为 BroadcastRegistrar）
    private BroadcastRegistrar broadcastRegistrar;

    // 主动防护 - 音量键连按（拆分为 ProtectionVolumeKey）
    private ProtectionVolumeKey protectionVolumeKey;
    // 主动防护 - 音量阈值检测（拆分为 ProtectionVolumeThreshold）
    private ProtectionVolumeThreshold protectionVolumeThreshold;
    // 风险文字识别（拆分为 RiskDetector）
    private RiskDetector riskDetector;
    // 统一轻提示浮窗（风险横幅 / 计数提示）
    private HintOverlayManager hintOverlayManager;
    // 全屏覆盖检测（悬浮窗/无障碍覆盖型恶意应用）
    private OverlayDetector overlayDetector;
    private volatile boolean overlayScanRunning = false;
    private Runnable overlayScanTask;
    /** 全屏覆盖检测独立循环间隔（毫秒） */
    private static final long OVERLAY_SCAN_INTERVAL_MS = 1500L;
    private static final String TAG = "AntiLock";
    // 无障碍组件两种写法：标准（全限定）与短写法（系统/部分工具可能写入），比较时都认
    private static final String COMPONENT_STD = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
    private static final String COMPONENT_SHORT = "xiaote.AnQuan/.XTSafeMainService";
    // 移除→加回期间置锁，禁止 checkAndRecover 抢写列表，避免命令互相覆盖
    private volatile boolean accessibilityRecoverLock = false;
    private android.os.PowerManager powerManager; // 用于实时查询屏幕亮灭状态

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
        hintOverlayManager = new HintOverlayManager(this, handler);
        protectionVolumeKey = new ProtectionVolumeKey(handler, dotPrefs, overlayManager, emergencyManager, hintOverlayManager);
        protectionVolumeThreshold = new ProtectionVolumeThreshold(this, handler, dotPrefs, overlayManager, emergencyManager);
        riskDetector = new RiskDetector(this, handler, dotPrefs, overlayManager, hintOverlayManager);
        overlayDetector = new OverlayDetector(this);

        dotPrefs.registerOnSharedPreferenceChangeListener(dotPrefsListener);

        overlayManager.createStatusBarDot();
        if (dotPrefs.getBoolean("background_keep_alive", true)) {
            startForegroundService();
        }
        broadcastRegistrar = new BroadcastRegistrar(this, handler, dotPrefs, overlayManager,
                virusManager, autoClickHelper, this);
        broadcastRegistrar.registerAll();
        smartRecoverManager = new SmartRecoverManager(this, handler, dotPrefs, new Runnable() {
            @Override
            public void run() {
                runBackgroundWork(false);
            }
        });
        smartRecoverManager.registerReceiver();
        // 首次无障碍模式计算含跨进程读取与 Shell 写入，放到工作线程，避免拖住服务连接
        runBackgroundWork(false);
        startForceTopTask();
        protectionVolumeThreshold.start();
        startOverlayScanTask();
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
                            if (broadcastRegistrar != null) broadcastRegistrar.reregisterPackageReceiver();
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
                if (pkgChanged && (ProtectedPackages.isProtected(this, pkg) || !smartTempPkgs.isEmpty())) {
                    runBackgroundWork(false);
                }
            }
        } catch (Exception ignored) {}

        try {
            final int type = event.getEventType();
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                return;
            }
            final boolean isStateChange = (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            // 节流：CONTENT_CHANGED 高频，限制处理频率，避免节点遍历堆积拖死主线程
            if (!isStateChange) {
                long now = System.currentTimeMillis();
                if (now - lastContentEventTime < CONTENT_EVENT_THROTTLE_MS) return;
                lastContentEventTime = now;
            }
            // 以下检测（节点遍历 + 弹窗查找 + 风险话术匹配）全部只在窗口状态变化时执行。
            // CONTENT_CHANGED 是高频事件（输入法/动画/滚动），任何节点读取都会造成主线程跨进程堆积。
            if (!isStateChange) return;
            final String evtCls = event.getClassName() != null ? event.getClassName().toString() : "";
            final String evtPkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
            // 立即拷贝事件文本（AccessibilityEvent 会被系统复用，不能延迟读取）
            final String evtText = riskDetector.collectEventText(event);
            handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkAndHandleAdminDialog();
                        checkAndHandleShizukuDialog();
                        checkAndHandleSubSettings(evtCls, evtPkg);
                        riskDetector.checkRiskGuideText(evtPkg, evtText);
                    }
                }, 300);
        } catch (Exception ignored) {}
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // 音量键识别逻辑已拆分到 ProtectionVolumeKey
        if (protectionVolumeKey != null && protectionVolumeKey.onKeyEvent(event)) {
            return true;
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
            List<AccessibilityNodeInfo> exact = root.findAccessibilityNodeInfosByText("星特安全无障碍服务，用于监测音量键、显示置顶反锁机悬浮按钮、风险内容识别等，如果出现异常，关闭无障碍再重新开启即可。");
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
                        Intent intent = new Intent(XTSafeMainService.this, MainActivity.class);
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
                        boolean smartHold = smartRecoverManager != null
                                && (smartRecoverManager.isPending(pkg) || smartRecoverManager.isDeferred(pkg));
                        if (ProtectedPackages.isProtected(this, currentForegroundPkg) || !screenOn || smartHold) {
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
                    if (smartRecoverManager != null
                        && (smartRecoverManager.isPending(spkg) || smartRecoverManager.isDeferred(spkg))) continue;
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

    /**
     * 把重活（病毒扫描 / 无障碍模式计算 / Shell 写入）放到工作线程执行，避免主线程 ANR。
     * 节流 + 防重入：短时间内多次触发只执行一次，执行中再次触发直接丢弃。
     *
     * @param doVirusScan 是否顺带执行病毒扫描
     */
    private void runBackgroundWork(final boolean doVirusScan) {
        long now = System.currentTimeMillis();
        if (now - lastBgWorkTime < BG_WORK_MIN_INTERVAL_MS) return;
        if (!bgWorkRunning.compareAndSet(false, true)) return;
        lastBgWorkTime = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (doVirusScan && dotPrefs.getBoolean("block_virus", true)) {
                        virusManager.scanAndBlockVirusApps();
                    }
                    applyAccessibilityModes();
                    checkAndRecoverAccessibility();
                } catch (Exception ignored) {
                } finally {
                    bgWorkRunning.set(false);
                }
            }
        }).start();
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
                    // 病毒扫描 / 无障碍模式计算 / Shell 写入均为阻塞操作，全部移到工作线程
                    runBackgroundWork(true);
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
        if (riskDetector != null && riskDetector.hasBanner()) {
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
        if (riskDetector != null && riskDetector.hasBanner()) return;
        // 悬浮按钮关闭时强制置顶一并关闭：不做任何置顶动作，避免干扰触摸
        if (!dotPrefs.getBoolean("dot_enabled", true)) return;
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

    /** 启动全屏覆盖检测独立循环（每 3 秒一次） */
    private void startOverlayScanTask() {
        stopOverlayScanTask();
        overlayScanTask = new Runnable() {
            @Override
            public void run() {
                try {
                    checkOverlayBlock();
                } catch (Exception ignored) {
                } finally {
                    if (overlayScanTask != null && handler != null) {
                        handler.postDelayed(this, OVERLAY_SCAN_INTERVAL_MS);
                    }
                }
            }
        };
        handler.postDelayed(overlayScanTask, OVERLAY_SCAN_INTERVAL_MS);
    }

    private void stopOverlayScanTask() {
        if (overlayScanTask != null && handler != null) {
            handler.removeCallbacks(overlayScanTask);
            overlayScanTask = null;
        }
    }

    /** 检测全屏覆盖型恶意应用（需 Shizuku；检测在子线程执行，避免阻塞） */
    private void checkOverlayBlock() {
        try {
            if (!dotPrefs.getBoolean("block_overlay", true)) return;
            if (overlayDetector == null || !overlayDetector.isAvailable()) return;
            if (overlayScanRunning) return;
            overlayScanRunning = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.util.List<String> targets = overlayDetector.scanConfirmed();
                        if (targets == null || targets.isEmpty()) return;
                        for (String pkg : targets) {
                            // 复检：窗口仍存在才处置，避免瞬时动画/切换误判
                            if (!overlayDetector.stillCovering(pkg)) {
                                overlayDetector.clear(pkg);
                                continue;
                            }
                            overlayDetector.clear(pkg);
                            blockOverlayApp(pkg);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        overlayScanRunning = false;
                    }
                }
            }).start();
        } catch (Exception ignored) {}
    }

    /** 处置全屏覆盖应用：强制停止 + 冻结，并写入拦截日志 */
    private void blockOverlayApp(final String pkg) {
        String appName = pkg;
        try {
            appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {}
        boolean stopped = ShellExecutor.forceStopApp(pkg);
        boolean frozen = ShellExecutor.freezeApp(pkg);
        final String toastName = appName;
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(XTSafeMainService.this,
                        getString(R.string.overlay_blocked, toastName), Toast.LENGTH_LONG).show();
            }
        });
        OverlayBlockLogger.log(this, "BLOCK", "包名=" + pkg
                + " 应用名=" + appName
                + " 强制停止=" + stopped
                + " 冻结=" + frozen);
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

    @Override
    public void onInterrupt() { cleanup(); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanup();
    }

    // ==================== BroadcastRegistrar.Host ====================

    @Override
    public void onScreenOff() {
        screenOn = false;
        // 记录锁屏前开启的智能管理应用，供亮屏询问恢复
        if (smartRecoverManager != null) smartRecoverManager.handleScreenOffSmart();
        // 无障碍模式重算含跨进程与 Shell 调用，放到工作线程，避免阻塞主线程
        runBackgroundWork(false);
    }

    @Override
    public void onScreenOn() {
        // 亮屏/解锁：通知询问是否恢复，5秒无操作默认恢复
        screenOn = true;
        if (smartRecoverManager != null) smartRecoverManager.handleScreenOnSmart();
    }

    private void cleanup() {
        Log.d(TAG, "cleanup: 服务清理/销毁");
        if (riskDetector != null) riskDetector.dismissRiskBanner();
        if (dotPrefs != null) {
            dotPrefs.unregisterOnSharedPreferenceChangeListener(dotPrefsListener);
        }
        stopForceTopTask();
        stopOverlayScanTask();
        if (protectionVolumeThreshold != null) protectionVolumeThreshold.stop();
        if (broadcastRegistrar != null) broadcastRegistrar.unregisterAll();
        if (smartRecoverManager != null) smartRecoverManager.cleanup();
        if (hintOverlayManager != null) hintOverlayManager.cleanup();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (overlayManager != null) overlayManager.cleanup();
        try { stopForeground(true); } catch (Exception e) {}
    }
}
