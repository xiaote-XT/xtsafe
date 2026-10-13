package xiaote.AnQuan;

import xiaote.xtui.XtToast;

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

import xiaote.AnQuan.InternalApi.AccessibilityApi;

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
    /** 按键过滤 flag 周期复检任务 */
    private Runnable keyFilterWatchTask;
    /** 无障碍保护方案·强力模式的每秒检查任务 */
    private Runnable accProtectTask;
    /** 强力模式防重入 */
    private volatile boolean accProtectRunning = false;

    // 广播注册（拆分为 BroadcastRegistrar）
    private BroadcastRegistrar broadcastRegistrar;

    // 主动防护 - 音量键连按（拆分为 ProtectionVolumeKey）
    private ProtectionVolumeKey protectionVolumeKey;
    // 主动防护 - 音量阈值检测（拆分为 ProtectionVolumeThreshold）
    private ProtectionVolumeThreshold protectionVolumeThreshold;
    // 风险文字识别（拆分为 RiskDetector）
    private RiskDetector riskDetector;
    // 统一轻提示浮窗（风险横幅 / 计数提示）
    private XtToast xtToast;
    // 全屏覆盖检测（悬浮窗/无障碍覆盖型恶意应用）
    private OverlayDetector overlayDetector;
    private volatile boolean overlayScanRunning = false;
    private Runnable overlayScanTask;
    /**
     * 敏感力度下已处置过的包名。
     *
     * 敏感模式每秒扫一次、命中即拦，若不做去重，同一个覆盖窗口会在被彻底
     * 关掉之前被反复强制停止 + 冻结。这里记一份「已经处置过」的包名，
     * 本轮覆盖消失后移除，下次它再出现才会被重新拦截。
     */
    private final Set<String> overlayBlockedPkgs =
            java.util.Collections.synchronizedSet(new HashSet<String>());
    /** 全屏覆盖检测独立循环间隔（毫秒） */
    /** 全屏覆盖扫描间隔：标准力度（毫秒） */
    private static final long OVERLAY_SCAN_INTERVAL_STD_MS = 1500L;
    /** 全屏覆盖扫描间隔：敏感力度（毫秒），每秒一次 */
    private static final long OVERLAY_SCAN_INTERVAL_SENSITIVE_MS = 1000L;
    /** 全屏覆盖拦截力度偏好键：standard / sensitive */
    public static final String KEY_OVERLAY_BLOCK_MODE = "overlay_block_mode";
    /** 标准力度（默认） */
    public static final String OVERLAY_MODE_STANDARD = "standard";
    /** 敏感力度 */
    public static final String OVERLAY_MODE_SENSITIVE = "sensitive";

    /** 无障碍保护方案偏好键：standard / strong */
    public static final String KEY_ACC_PROTECT_MODE = "acc_protect_mode";
    /** 标准方案（默认）：沿用原有事件驱动 + 后台重活时恢复 */
    public static final String ACC_PROTECT_MODE_STANDARD = "standard";
    /** 强力方案：每秒检查一次，被关闭立即恢复 */
    public static final String ACC_PROTECT_MODE_STRONG = "strong";
    /** 强力方案检查间隔（毫秒） */
    private static final long ACC_PROTECT_STRONG_INTERVAL_MS = 1000L;
    /** 阻止卸载检查独立循环（毫秒）：与最低 0.1 秒一次对齐 */
    private Runnable blockCheckTask;
    private static final long BLOCK_CHECK_INTERVAL_MS = 100L;
    private static final String TAG = "AntiLock";
    // 无障碍组件两种写法：标准（全限定）与短写法（系统/部分工具可能写入），比较时都认
    private static final String COMPONENT_STD = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
    private static final String COMPONENT_SHORT = "xiaote.AnQuan/.XTSafeMainService";
    private static final String LOVE = "DeepSeek";
    // 移除→加回期间置锁，禁止 checkAndRecover 抢写列表，避免命令互相覆盖
    private volatile boolean accessibilityRecoverLock = false;
    private android.os.PowerManager powerManager; // 用于实时查询屏幕亮灭状态

    @Override
    public void onServiceConnected() {
        Log.d(TAG, "onServiceConnected: 无障碍服务已连接");
        // 参照「安全杀手」的可行做法：这里完全不调用 setServiceInfo()。
        //
        // 原因：new 一个 AccessibilityServiceInfo 再 setServiceInfo 会整体覆盖
        // XML 解析出来的配置，把 canRetrieveWindowContent / canPerformGestures
        // 一起重置为 false，结果就是读不到任何弹窗节点、dispatchGesture 直接失败——
        // 这正是「一个都点不动、根本没点到允许」的根因。
        //
        // 事件类型、flags（含 flagRetrieveInteractiveWindows / flagIncludeNotImportantViews）、
        // canRetrieveWindowContent 全部由 res/xml/accessibility_service_config.xml 声明，
        // 系统在绑定服务时自动应用，不再用代码覆盖。

        pm = getPackageManager();
        handler = new Handler();
        dotPrefs = getSharedPreferences("dot_config", MODE_PRIVATE);
        powerManager = (android.os.PowerManager) getSystemService(POWER_SERVICE);

        // 修复：恶意应用把本服务从无障碍列表摘掉再恢复后，系统有时不会重新下发
        // flagRequestFilterKeyEvents，结果 onKeyEvent 不再回调 —— 表现为「无障碍明明
        // 已开启，音量键连按却彻底没反应」。这里取系统当前配置、补回该 flag 再写回。
        ensureKeyEventFilterFlag();

        colorHelper = new ColorHelper(this);
        emergencyManager = new EmergencyManager(this, handler);
        overlayManager = new OverlayManager(this, handler, colorHelper, emergencyManager);
        autoClickHelper = new AutoClickHelper(this, handler);
        virusManager = new VirusManager(this);
        xtToast = new XtToast(this, handler);
        protectionVolumeKey = new ProtectionVolumeKey(this, handler, dotPrefs, xtToast);
        protectionVolumeThreshold = new ProtectionVolumeThreshold(this, handler, dotPrefs);
        riskDetector = new RiskDetector(this, handler, dotPrefs, overlayManager, xtToast);
        overlayDetector = new OverlayDetector(this);

        dotPrefs.registerOnSharedPreferenceChangeListener(dotPrefsListener);

        overlayManager.createStatusBarDot();
        // 前台保活默认关闭：未设置过时按关闭处理
        if (dotPrefs.getBoolean("background_keep_alive", false)) {
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
        startOverlayScanTask();
        startAccProtectTask();
        // 音量阈值检测 / 阻止卸载周期任务已统一由 XTSafeCoreService 负责，
        // 此处不再重复启动，避免双份循环。

        // 关键：置顶循环会周期性「移除→加回无障碍」，导致本服务被系统重建，
        // 而 AutoClickHelper.sAutoRunning 是静态的、启动广播只在点「一键授权」时发过一次。
        // 服务重建后新实例没有循环在跑，sAutoRunning 却仍是 true ——
        // 表现就是「点了一键授权，自动点击启动了一下就再也没反应」。
        // 这里在每次服务连接时按静态开关把循环恢复起来。
        if (AutoClickHelper.sAutoRunning && autoClickHelper != null) {
            autoClickHelper.startAutoClickLoop();
        }
        }

    /**
     * 补回按键过滤 flag，并启动周期复检。
     *
     * 背景：音量键连按靠 onKeyEvent 回调，它依赖 XML 里声明的
     * android:canRequestFilterKeyEvents="true"（对应 FLAG_REQUEST_FILTER_KEY_EVENTS）。
     * 恶意应用把星特安全从无障碍列表摘掉、用户又把它加回来之后，部分 ROM
     * 不会重新下发这个 flag，结果就是「无障碍显示已开启，音量键连按彻底没反应」。
     *
     * 为什么不直接 new 一个 AccessibilityServiceInfo 再 setServiceInfo：
     * 那会整体覆盖 XML 解析出的配置，把 canRetrieveWindowContent / 事件类型
     * 一起重置（这正是早期「一个都点不动」的根因）。
     * 这里用 getServiceInfo() 取系统当前实际配置，只补缺失的那一个 flag 再写回。
     */
    private void ensureKeyEventFilterFlag() {
        applyKeyEventFilterFlag("connect");
        // 部分 ROM 在 onServiceConnected 之后才把 XML 配置最终落到服务上，
        // 紧接着再补一次，确保 flag 是「最后一次写入」的那一方。
        try {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyKeyEventFilterFlag("delayed");
                }
            }, 1200L);
        } catch (Throwable ignored) {
        }
        startKeyFilterWatch();
    }

    /**
     * 按键过滤 flag 周期复检。
     *
     * 光在 onServiceConnected 里补一次不够：
     *   · 部分 ROM 在连接回调之后才最终写入 XML 配置，那一刻读到的 flags 还没有
     *     按键过滤位，等它落下去反而把我们补的覆盖掉了；
     *   · 恶意应用可能反复摘挂无障碍，每次重建都要重新补。
     * 因此每 5 秒复检一次，发现缺失就补。getServiceInfo 是本地调用，
     * 5 秒一次的频率对耗电与性能的影响可以忽略。
     */
    private void startKeyFilterWatch() {
        stopKeyFilterWatch();
        keyFilterWatchTask = new Runnable() {
            @Override
            public void run() {
                try {
                    applyKeyEventFilterFlag("watchdog");
                } catch (Throwable ignored) {
                } finally {
                    if (keyFilterWatchTask != null && handler != null) {
                        handler.postDelayed(this, 5000L);
                    }
                }
            }
        };
        handler.postDelayed(keyFilterWatchTask, 5000L);
    }

    private void stopKeyFilterWatch() {
        if (keyFilterWatchTask != null && handler != null) {
            handler.removeCallbacks(keyFilterWatchTask);
            keyFilterWatchTask = null;
        }
    }

    /**
     * 真正补 flag 的动作。
     *
     * 除了「缺失才补」，这里还会在补完后回读一次并把前后 flags 都打进日志，
     * 因为音量键不回调有两种可能：
     *   · flag 确实没下发 —— 回读后能看到补成功了；
     *   · flag 本来就带着 —— 那说明是别的无障碍服务在 key 过滤链上把音量键吃掉，
     *     日志里会显示 before 已含该位，可直接排除本机制。
     */
    private void applyKeyEventFilterFlag(String from) {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                Log.w(TAG, "getServiceInfo 为 null（" + from + "），无法补按键过滤 flag");
                return;
            }
            int before = info.flags;
            boolean has = (before & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
            if (has) {
                if (!"watchdog".equals(from)) {
                    Log.d(TAG, "按键过滤 flag 已存在（" + from + "）, flags=" + before);
                }
                return;
            }
            info.flags = before | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
            int after = before;
            try {
                AccessibilityServiceInfo back = getServiceInfo();
                if (back != null) after = back.flags;
            } catch (Throwable ignored) {}
            Log.d(TAG, "补回按键过滤 flag（" + from + "）: " + before + " -> " + after
                    + "，回读" + (((after & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0)
                    ? "已生效" : "仍未生效"));
        } catch (Throwable t) {
            Log.w(TAG, "补回按键过滤 flag 失败（" + from + "）", t);
        }
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
                            if (dotPrefs.getBoolean("background_keep_alive", false)) startForegroundService();
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
        // 一键授权自动点击：每个无障碍事件都驱动一次点击。
        // 弹窗一出现立刻点，不必等 250ms 定时器，也避免定时器被系统节流时漏点。
        // 之前 tickFromEvent() 写了却没接进来，是「根本没点到允许」的直接原因之一。
        if (AutoClickHelper.sAutoRunning && autoClickHelper != null) {
            try { autoClickHelper.tickFromEvent(); } catch (Throwable ignored) {}
        }
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
                    // 周期性重新下发“阻止其他应用被卸载”（频率由设置页决定，内部自带节流）
                    BlockUninstallManager.maybeRunPeriodic(getApplicationContext());
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

    /** 反射读取 AccessibilityServiceInfo 的布尔字段（AIDE 的 android.jar 无此字段声明） */
    private static boolean getBoolField(Object obj, String name) {
        if (obj == null || name == null) return false;
        try {
            java.lang.reflect.Field f = obj.getClass().getField(name);
            return f.getBoolean(obj);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 反射设置 AccessibilityServiceInfo 的布尔字段 */
    private static void setBoolField(Object obj, String name, boolean value) {
        if (obj == null || name == null) return;
        try {
            java.lang.reflect.Field f = obj.getClass().getField(name);
            f.setBoolean(obj, value);
        } catch (Throwable ignored) {}
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
        // 悬浮按钮默认关闭：未设置过时按关闭处理，与 OverlayManager 保持一致
        if (!dotPrefs.getBoolean("dot_enabled", false)) return;
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

    /** 当前全屏覆盖扫描间隔：敏感力度 1 秒，标准力度 1.5 秒 */
    private long currentOverlayInterval() {
        try {
            return OVERLAY_MODE_SENSITIVE.equals(
                    dotPrefs.getString(KEY_OVERLAY_BLOCK_MODE, OVERLAY_MODE_STANDARD))
                    ? OVERLAY_SCAN_INTERVAL_SENSITIVE_MS
                    : OVERLAY_SCAN_INTERVAL_STD_MS;
        } catch (Throwable t) {
            return OVERLAY_SCAN_INTERVAL_STD_MS;
        }
    }

    /**
     * 启动全屏覆盖检测独立循环。
     *
     * 每轮都重读间隔：用户在「安全防护」里切换力度后不必重启服务，
     * 下一轮就按新间隔走。
     */
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
                        handler.postDelayed(this, currentOverlayInterval());
                    }
                }
            }
        };
        handler.postDelayed(overlayScanTask, currentOverlayInterval());
    }

    private void stopOverlayScanTask() {
        if (overlayScanTask != null && handler != null) {
            handler.removeCallbacks(overlayScanTask);
            overlayScanTask = null;
        }
    }

    // ==================== 无障碍保护方案 ====================

    /** 当前保护方案：standard 标准 / strong 强力 */
    private String accProtectMode() {
        try {
            return dotPrefs.getString(KEY_ACC_PROTECT_MODE, ACC_PROTECT_MODE_STANDARD);
        } catch (Throwable t) {
            return ACC_PROTECT_MODE_STANDARD;
        }
    }

    /**
     * 强力模式检查循环。
     *
     * 每 1 秒读一次系统启用列表，找出「用户设为『允许（自动保活）』、
     * 但当前不在列表里」的第三方无障碍服务，逐个加回。
     *
     * 只处理 MODE_ALLOW，不动 MODE_FORBIDDEN / MODE_LOCK_FORBID / MODE_SMART ——
     * 那三种的「关闭」是用户主动要求的，不能被保护逻辑顶回去。
     *
     * 标准方案下本循环空转（只做一次字符串比较），不产生额外跨进程调用。
     */
    private void startAccProtectTask() {
        stopAccProtectTask();
        accProtectTask = new Runnable() {
            @Override
            public void run() {
                try {
                    if (ACC_PROTECT_MODE_STRONG.equals(accProtectMode())) {
                        checkAccProtectStrong();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (accProtectTask != null && handler != null) {
                        handler.postDelayed(this, ACC_PROTECT_STRONG_INTERVAL_MS);
                    }
                }
            }
        };
        handler.postDelayed(accProtectTask, ACC_PROTECT_STRONG_INTERVAL_MS);
    }

    private void stopAccProtectTask() {
        if (accProtectTask != null && handler != null) {
            handler.removeCallbacks(accProtectTask);
            accProtectTask = null;
        }
    }

    /**
     * 强力模式：找出被关掉的「允许保活」应用并立即恢复。
     *
     * 组件列表读取与写回都是跨进程调用，整体丢到工作线程，
     * 每秒最多一次，且用 accProtectRunning 防重入，避免上一轮没跑完就叠加。
     */
    private void checkAccProtectStrong() {
        if (accProtectRunning) return;
        if (accessibilityRecoverLock) return;   // 置顶循环正在移除→加回，避免抢写
        accProtectRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Set<String> enabled = AccessibilityApi.listEnabled(getApplicationContext());
                    Set<String> enabledPkgs = new HashSet<String>();
                    for (String c : enabled) {
                        int idx = c.indexOf('/');
                        if (idx > 0) enabledPkgs.add(c.substring(0, idx));
                    }
                    android.view.accessibility.AccessibilityManager am =
                            (android.view.accessibility.AccessibilityManager)
                                    getSystemService(ACCESSIBILITY_SERVICE);
                    if (am == null) return;
                    List<android.accessibilityservice.AccessibilityServiceInfo> services =
                            am.getInstalledAccessibilityServiceList();
                    if (services == null) return;
                    Set<String> virusPkgs = VirusPackages.getVirusPackages(getApplicationContext());
                    virusPkgs.addAll(VirusPackages.getManagedPackages(getApplicationContext()));
                    for (android.accessibilityservice.AccessibilityServiceInfo info : services) {
                        if (info == null || info.getResolveInfo() == null) continue;
                        String pkg = info.getResolveInfo().serviceInfo.packageName;
                        if (pkg == null || pkg.equals(getPackageName())) continue;
                        if (virusPkgs.contains(pkg)) continue;
                        if (enabledPkgs.contains(pkg)) continue;
                        int mode = dotPrefs.getInt("acc_mode_" + pkg, -1);
                        if (mode != AccessibilityManagerActivity.MODE_ALLOW) continue;
                        String comp = pkg + "/" + info.getResolveInfo().serviceInfo.name;
                        AccessibilityApi.enableComponent(getApplicationContext(), comp);
                        Log.d(TAG, "强力保活：恢复 " + comp);
                    }
                } catch (Throwable ignored) {
                } finally {
                    accProtectRunning = false;
                }
            }
        }, "acc-protect-strong").start();
    }

    /** 启动“阻止卸载/阻止阻止卸载”独立检查循环（每 100ms 一次，内部按用户设定频率节流） */
    private void startBlockCheckTask() {
        stopBlockCheckTask();
        blockCheckTask = new Runnable() {
            @Override
            public void run() {
                try {
                    BlockUninstallManager.maybeRunPeriodic(getApplicationContext());
                } catch (Exception ignored) {
                } finally {
                    if (blockCheckTask != null && handler != null) {
                        handler.postDelayed(this, BLOCK_CHECK_INTERVAL_MS);
                    }
                }
            }
        };
        handler.postDelayed(blockCheckTask, BLOCK_CHECK_INTERVAL_MS);
    }

    private void stopBlockCheckTask() {
        if (blockCheckTask != null && handler != null) {
            handler.removeCallbacks(blockCheckTask);
            blockCheckTask = null;
        }
    }

    /** 检测全屏覆盖型恶意应用（需 Shizuku；检测在子线程执行，避免阻塞） */
    private void checkOverlayBlock() {
        try {
            if (!dotPrefs.getBoolean("block_overlay", true)) return;
            if (overlayDetector == null || !overlayDetector.isAvailable()) return;
            if (overlayScanRunning) return;

            final boolean sensitive = OVERLAY_MODE_SENSITIVE.equals(
                    dotPrefs.getString(KEY_OVERLAY_BLOCK_MODE, OVERLAY_MODE_STANDARD));
            // 同步给检测器：敏感模式一次命中即确认，不走连续累计
            OverlayDetector.sensitiveMode = sensitive;

            overlayScanRunning = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.util.List<String> targets = overlayDetector.scanConfirmed();

                        if (sensitive) {
                            // 覆盖已消失的包名从去重表移除，它再出现时可重新拦截
                            java.util.Set<String> stillHere = new java.util.HashSet<String>();
                            if (targets != null) stillHere.addAll(targets);
                            java.util.List<String> gone = new java.util.ArrayList<String>();
                            synchronized (overlayBlockedPkgs) {
                                for (String p : overlayBlockedPkgs) {
                                    if (!stillHere.contains(p)) gone.add(p);
                                }
                            }
                            overlayBlockedPkgs.removeAll(gone);
                        }

                        if (targets == null || targets.isEmpty()) return;
                        for (String pkg : targets) {
                            /*
                             * 敏感力度：出现即拦，跳过复检、不等第二轮；
                             * 同一包名在覆盖消失前只处置一次，避免每秒重复冻结。
                             */
                            if (sensitive) {
                                if (overlayBlockedPkgs.contains(pkg)) continue;
                                overlayBlockedPkgs.add(pkg);
                                overlayDetector.clear(pkg);
                                blockOverlayApp(pkg);
                                continue;
                            }
                            // 标准力度：保持原有「复检仍存在才处置」的抗误判逻辑
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

    /**
     * 处置全屏覆盖应用：强制停止 + 冻结，结果以通知告知并写入拦截日志。
     *
     * 为什么改通知而不是 Toast：Toast 只在当前前台界面短暂显示，
     * 而覆盖窗口出现时用户多半正在被遮挡的界面上，Toast 极易被盖住或错过；
     * 通知留在通知栏可回看，也能被 TalkBack 等辅助功能读到。
     */
    private void blockOverlayApp(final String pkg) {
        String appName = pkg;
        try {
            appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {}
        /*
         * 走 SafeActionManager 的完整冻结链路，不再用 ShellExecutor.freezeApp。
         *
         * ShellExecutor.freezeApp 是早期简化版，只发一条
         * `pm disable --user 0 <pkg>`，然后看退出码：
         *   · 没有 disable-user 这一级 —— 多数 ROM 上第三方应用要靠
         *     `pm disable-user --user 0` 才真正生效，`pm disable` 经常
         *     返回 0 但包仍是 enabled；
         *   · 没有 force-stop 的同步等待；
         *   · 没有 `pm list packages -d` 回读校验。
         * 覆盖窗口所属应用此刻正前台运行，上面三点叠加，判定基本必然是「未成功」。
         *
         * 安全操作（音量阈值 / 连按音量键 / 时间回拨）走的是
         * SafeActionManager.freezePackages → freezeOne：先 force-stop，
         * 再依次试 disable-user → disable → Root，每一级都用输出 + 回读双重判定。
         * 同一个包在那边能成功、这里不行，差别就在这里。
         *
         * 现在改为复用同一条链路，两处口径一致。
         */
        boolean stopped = ShellExecutor.forceStopApp(pkg);
        boolean frozen = SafeActionManager.freezeOnePublic(pkg);
        sendOverlayBlockNotify(appName, pkg, stopped, frozen);
        OverlayBlockLogger.log(this, "BLOCK", "包名=" + pkg
                + " 应用名=" + appName
                + " 强制停止=" + stopped
                + " 冻结=" + frozen);
    }

    /** 全屏覆盖拦截通知渠道 */
    private static final String OVERLAY_CHANNEL_ID = "overlay_block";
    /** 全屏覆盖拦截通知 id：固定值，后一次拦截覆盖前一次，不堆通知栏 */
    private static final int OVERLAY_NOTIFY_ID = 0x5A10;

    /**
     * 拦截结果通知。
     *
     * 标题固定「星特安全拦截一次操作」，正文给全应用名、包名、处置动作与时间。
     * 用 BigTextStyle，折叠态只显示一行摘要，展开可见完整信息。
     *
     * 通知 id 固定：连续拦截多个应用时后一条覆盖前一条，避免刷屏；
     * 完整的拦截流水仍由 OverlayBlockLogger 落到日志文件里。
     */
    private void sendOverlayBlockNotify(String appName, String pkg,
                                        boolean stopped, boolean frozen) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(OVERLAY_CHANNEL_ID,
                        "全屏覆盖拦截", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("检测到全屏覆盖应用并处置时提醒");
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
            String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.US).format(new java.util.Date());
            String action = (stopped ? "已强制停止" : "强制停止未成功")
                    + "，" + (frozen ? "已冻结" : "冻结未成功");
            String detail = "应用：" + appName
                    + "\n包名：" + pkg
                    + "\n处置：" + action
                    + "\n时间：" + time;

            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, OVERLAY_NOTIFY_ID, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, OVERLAY_CHANNEL_ID)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("星特安全拦截一次操作")
                    .setContentText("全屏覆盖：" + appName + "（" + action + "）")
                    .setStyle(new Notification.BigTextStyle().bigText(detail))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(false)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setWhen(System.currentTimeMillis());
            nm.notify(OVERLAY_NOTIFY_ID, b.build());
        } catch (Throwable ignored) {}
    }

    private void checkAndRecoverAccessibility() {
        try {
            /*
             * 无障碍保活开关：与 XTSafeCoreService 共用同一份偏好。
             *
             * 这里是历史遗留的第二条写回路径，跑在 runBackgroundWork 里
             * （由强制置顶循环与无障碍事件触发），原先不看任何开关，
             * 导致用户在设置页关掉「无障碍保活」后它仍在往
             * ENABLED_ACCESSIBILITY_SERVICES 写回 —— 表现为「关掉了还在跑」。
             * 加上判定后，两条路径行为一致，开关关闭即彻底停手。
             */
            if (!dotPrefs.getBoolean(XTSafeCoreService.KEY_ACC_KEEP_ALIVE, false)) return;
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
        stopBlockCheckTask();
        stopAccProtectTask();
        stopKeyFilterWatch();
        if (protectionVolumeThreshold != null) protectionVolumeThreshold.stop();
        if (broadcastRegistrar != null) broadcastRegistrar.unregisterAll();
        if (smartRecoverManager != null) smartRecoverManager.cleanup();
        if (xtToast != null) xtToast.cleanup();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (overlayManager != null) overlayManager.cleanup();
        try { stopForeground(true); } catch (Exception e) {}
    }
}
