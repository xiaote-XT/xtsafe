package xiaote.AnQuan;

import xiaote.AnQuan.R;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * 自动点击授权助手。
 *
 * 实现参照「安全杀手」里验证可用的做法：
 *   1. 遍历 getWindows() 的所有窗口，逐个取 root（权限弹窗、Shizuku/Dhizuku
 *      授权页、省电优化对话框都在独立窗口，只看活动窗口会一个按钮都找不到）；
 *   2. 递归收集窗口内全部节点，无深度限制；
 *   3. 归一化文本（去空白 / 全角空格 / 零宽字符）后做 **equals 精准比较**，
 *      而不是 contains —— 这是关键：contains 下「允许」会命中同一弹窗里的
 *      「不允许」「暂不允许」，节点顺序不保证，点第一个就把弹窗点成拒绝。
 *   4. 命中后向上最多 8 层找可点父节点，执行 ACTION_CLICK；
 *      失败再退回节点自身 ACTION_CLICK，最后才用 dispatchGesture 坐标触摸。
 *
 * 驱动：250ms 定时循环 + 无障碍事件 tickFromEvent()，双通道保证不漏点。
 */
public class AutoClickHelper {

    private final AccessibilityService service;
    private final Handler handler;

    /** 自动点击循环开关（静态，供外部广播取消） */
    public static volatile boolean sAutoRunning = false;
    /** 连续检测不到无障碍的次数 */
    public static volatile int sAccDeadCount = 0;

    /** 最近创建的实例，供一键授权页查询前台窗口包名 */
    private static volatile AutoClickHelper sInstance;

    private Runnable autoClickLoopTask;

    private static final String LOG_FILE = "auto_click.log";
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;

    /** 单根节点最多收集的节点数（防极端布局卡顿） */
    private static final int MAX_NODES = 2000;

    // ==================== 点击目标（精准 equals 匹配） ====================

    /**
     * 授权按钮完整文案。
     *
     * 用 equals 精准比较，所以这里必须写按钮上的完整文字，
     * 不能只写「允许」指望包含匹配——那会同时命中「不允许」。
     * 长文本在前没有意义（equals 不会互相截断），但保持可读顺序。
     */
    private static final String[] ALLOW_TEXTS = {
            // 权限弹窗
            "始终允许", "总是允许", "总数允许", "永远允许", "一直允许", "一律允许", "永久允许",
            "始终同意", "永远同意", "总是同意",
            "仅在使用中允许", "仅在使用该应用时允许", "仅在使用时允许", "仅使用中允许",
            "使用应用时允许", "使用期间允许", "使用该应用时允许",
            "本次允许", "仅本次允许", "仅此一次", "允许一次", "允许此次",
            "允许", "同意",
            // 后台 / 电池
            "始终在后台运行", "永远运行", "允许后台运行", "允许后台活动",
            "允许自启动", "允许自动启动", "允许唤醒", "允许关联启动",
            "忽略电池优化", "不限制", "无限制", "不优化", "允许通知", "开启通知",
            // 通用确认
            "同意并继续", "同意并允许", "接受并继续", "我同意", "接受",
            "确定", "确认", "好的", "好", "知道了", "继续", "下一步",
            "开启", "打开", "启用", "激活",
            // 设备管理员
            "启用此设备管理应用", "激活此设备管理应用", "启用设备管理",
            // 安装
            "安装", "允许安装", "仍要安装", "继续安装",
            // 英文
            "Always allow", "Allow all the time", "Allow", "Agree", "Accept",
            "Continue", "Turn on", "Enable", "OK", "Ok",
    };

    /** 否定文案：equals 匹配下这些不会被肯定词命中，但保留做二次兜底 */
    private static final String[] NEGATIVE_TEXTS = {
            "不允许", "暂不允许", "不再允许", "不同意", "不接受",
            "不开启", "不启用", "不安装", "不优化", "拒绝", "禁止",
            "取消", "暂不", "暂时不", "稍后", "以后再说", "下次再说",
            "跳过", "不再询问", "关闭", "退出", "停止", "返回",
            "Deny", "Cancel", "Not now", "Later",
    };

    public AutoClickHelper(AccessibilityService service, Handler handler) {
        this.service = service;
        this.handler = handler;
        sInstance = this;
    }

    /** 当前前台窗口包名（读不到返回空串） */
    public static String currentWindowPackage() {
        AutoClickHelper inst = sInstance;
        if (inst == null || inst.service == null) return "";
        AccessibilityNodeInfo root = null;
        try {
            root = inst.service.getRootInActiveWindow();
            if (root == null) return "";
            CharSequence p = root.getPackageName();
            return p == null ? "" : p.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    // ==================== 日志 ====================

    /** 追加一条日志；超过 2MB 清空重写，同时广播给一键授权页显示 */
    public void appendLog(String msg) {
        if (msg == null) return;
        try {
            Intent it = new Intent("xiaote.AnQuan.AUTO_CLICK_LOG");
            it.setPackage(service.getPackageName());
            it.putExtra("msg", msg);
            service.sendBroadcast(it);
        } catch (Throwable ignored) {}
        java.io.FileOutputStream fos = null;
        try {
            java.io.File f = new java.io.File(service.getFilesDir(), LOG_FILE);
            if (f.exists() && f.length() > MAX_LOG_BYTES) {
                fos = new java.io.FileOutputStream(f, false);
                fos.write(new byte[0]);
                fos.close();
                fos = null;
            }
            String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new java.util.Date());
            fos = new java.io.FileOutputStream(f, true);
            fos.write((time + " " + msg + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    // ==================== 循环控制 ====================

    private boolean isAccessibilityAlive() {
        try {
            String list = Settings.Secure.getString(service.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || list.isEmpty()) return false;
            return list.contains(service.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 启动持续自动点击（定时循环 + 事件驱动） */
    public void startAutoClickLoop() {
        sAutoRunning = true;
        sAccDeadCount = 0;
        appendLog("启动自动点击循环");
        if (autoClickLoopTask != null) {
            handler.removeCallbacks(autoClickLoopTask);
            autoClickLoopTask = null;
        }
        autoClickLoopTask = new Runnable() {
            @Override
            public void run() {
                if (!sAutoRunning) return;
                if (isAccessibilityAlive()) {
                    sAccDeadCount = 0;
                } else {
                    sAccDeadCount++;
                    if (sAccDeadCount >= 5) {
                        appendLog("无障碍已关闭，自动停止自动点击循环");
                        stopAutoClickLoop();
                        return;
                    }
                }
                try { clickRound(); } catch (Throwable ignored) {}
                if (sAutoRunning && handler != null) {
                    handler.postDelayed(this, 250L);
                }
            }
        };
        handler.postDelayed(autoClickLoopTask, 150L);
    }

    /** 停止自动点击 */
    public void stopAutoClickLoop() {
        if (sAutoRunning) appendLog("停止自动点击循环");
        sAutoRunning = false;
        if (autoClickLoopTask != null && handler != null) {
            handler.removeCallbacks(autoClickLoopTask);
            autoClickLoopTask = null;
        }
    }

    /** 由无障碍事件驱动：弹窗一出现立刻点一次 */
    public void tickFromEvent() {
        if (!sAutoRunning) return;
        try { clickRound(); } catch (Throwable ignored) {}
    }

    // ==================== 一轮点击 ====================

    /** 一轮：遍历所有窗口，命中精准文案即点 */
    private void clickRound() {
        List<AccessibilityWindowInfo> windows = null;
        try { windows = service.getWindows(); } catch (Throwable ignored) {}

        if (windows != null) {
            for (int i = 0; i < windows.size(); i++) {
                AccessibilityWindowInfo w = windows.get(i);
                if (w == null) continue;
                AccessibilityNodeInfo root = null;
                try { root = w.getRoot(); } catch (Throwable ignored) {}
                if (root == null) continue;
                boolean hit = false;
                try { hit = scanAndClick(root); } catch (Throwable ignored) {}
                try { root.recycle(); } catch (Throwable ignored) {}
                if (hit) return;
            }
        }

        AccessibilityNodeInfo active = null;
        try { active = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
        if (active != null) {
            try { scanAndClick(active); } catch (Throwable ignored) {}
            try { active.recycle(); } catch (Throwable ignored) {}
        }
    }

    /** 在指定根节点下找授权按钮并点击（命中即返回 true） */
    private boolean scanAndClick(AccessibilityNodeInfo root) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
        collectAllNodes(root, nodes);

        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo node = nodes.get(i);
            if (node == null) continue;
            String txt = normalize(node.getText());
            String desc = normalize(node.getContentDescription());
            if (isNegative(txt) || isNegative(desc)) continue;

            String hit = matchAllow(txt);
            if (hit == null) hit = matchAllow(desc);
            if (hit == null) continue;

            String how = clickUpwards(node);
            if (how != null) {
                appendLog("点击授权按钮: " + hit + " [" + how + "]");
                return true;
            }
        }
        return false;
    }

    /** 文本是否等于某个授权文案（精准比较） */
    private String matchAllow(String s) {
        if (s == null || s.isEmpty()) return null;
        for (int i = 0; i < ALLOW_TEXTS.length; i++) {
            String k = ALLOW_TEXTS[i];
            if (k.equals(s)) return k;
        }
        // 英文大小写不敏感兜底
        try {
            String lower = s.toLowerCase(Locale.US);
            for (int i = 0; i < ALLOW_TEXTS.length; i++) {
                String k = ALLOW_TEXTS[i];
                boolean ascii = true;
                for (int j = 0; j < k.length(); j++) {
                    if (k.charAt(j) > 127) { ascii = false; break; }
                }
                if (ascii && k.toLowerCase(Locale.US).equals(lower)) return k;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 文本是否是否定文案 */
    private boolean isNegative(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < NEGATIVE_TEXTS.length; i++) {
            if (NEGATIVE_TEXTS[i].equals(s)) return true;
        }
        return false;
    }

    /** 递归收集全部节点 */
    private void collectAllNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (out.size() >= MAX_NODES) return;
        out.add(node);
        try {
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                if (out.size() >= MAX_NODES) return;
                AccessibilityNodeInfo child = null;
                try { child = node.getChild(i); } catch (Throwable ignored) {}
                if (child != null) collectAllNodes(child, out);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 按文本查找并点击（跳过否定节点）。
     *
     * 供 XTSafeMainService 的反拦截流程使用（点「取消」「拒绝」），
     * 这些文案不在 ALLOW_TEXTS 里，因此这里直接按文本查找 + clickUpwards，
     * 不走精准匹配。先 findAccessibilityNodeInfosByText，再全树遍历兜底。
     */
    public boolean autoClickButton(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null || text.isEmpty()) return false;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes != null) {
                for (int i = 0; i < nodes.size(); i++) {
                    AccessibilityNodeInfo n = nodes.get(i);
                    if (n == null) continue;
                    if (clickUpwards(n) != null) return true;
                }
            }
        } catch (Throwable ignored) {}
        // 兜底：findAccessibilityNodeInfosByText 对拆节点/带前后缀的按钮会漏，这里全树遍历
        try {
            List<AccessibilityNodeInfo> all = new ArrayList<AccessibilityNodeInfo>();
            collectAllNodes(root, all);
            for (int i = 0; i < all.size(); i++) {
                AccessibilityNodeInfo n = all.get(i);
                if (n == null) continue;
                String t = normalize(n.getText());
                String d = normalize(n.getContentDescription());
                if (t.contains(text) || d.contains(text)) {
                    if (clickUpwards(n) != null) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ==================== 点击执行 ====================

    /**
     * 从命中节点向上最多 8 层找可点节点并点击。
     * 返回使用的方式名；全部失败返回 null。
     *
     * 顺序（不能反）：
     *   1. 向上找可点父节点 ACTION_CLICK  —— 安全杀手验证可用的主路径
     *   2. 节点自身 ACTION_CLICK
     *   3. dispatchGesture 坐标触摸兜底（返回 true 只代表手势被派发，不代表点到）
     */
    private String clickUpwards(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo p = node;
        int depth = 0;
        while (p != null && depth < 8) {
            if (p.isClickable()) {
                try {
                    if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return depth == 0 ? "self" : "parent" + depth;
                    }
                } catch (Throwable ignored) {}
            }
            try { p = p.getParent(); } catch (Throwable ignored) { p = null; }
            depth++;
        }
        // 兜底一：节点自身可点但上面循环里没成功
        try {
            if (node.isClickable()
                    && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "self-retry";
            }
        } catch (Throwable ignored) {}
        // 兜底二：坐标手势
        if (gestureClick(node)) return "gesture";
        return null;
    }

    /** 用 dispatchGesture 点击节点屏幕中心 */
    private boolean gestureClick(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) return false;
            Path path = new Path();
            path.moveTo(r.exactCenterX(), r.exactCenterY());
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 40);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke).build();
            return service.dispatchGesture(gesture, null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 归一化：去除空白、全角空格、零宽字符 */
    private String normalize(CharSequence cs) {
        if (cs == null) return "";
        String s = cs.toString();
        if (s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == 0x3000) continue;
            if (c == 0x200B || c == 0x200C || c == 0x200D || c == 0x200E
                    || c == 0x200F || c == 0xFEFF || c == 0x2060 || c == 0x00A0) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    // ==================== 设备管理员 ====================

    /** 自动设置设备管理员：打开设置页 → 点应用名 → 点启用 */
    public void autoSetupAdmin() {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) service.getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName admin = new android.content.ComponentName(service, DeviceAdmin.class);
            if (dpm.isAdminActive(admin)) {
                Toast.makeText(service, "设备管理员已激活", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception ignored) {
        }

        try {
            Intent intent = new Intent();
            intent.setClassName("com.android.settings", "com.android.settings.Settings$DeviceAdminSettingsActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
        } catch (Exception e) {
        }

        final String appName = service.getString(R.string.app_name);
        handler.postDelayed(new Runnable() {
            @Override public void run() { tryAutoClickAdminApp(0, appName); }
        }, 1500);
    }

    private void tryAutoClickAdminApp(final int attempt, final String appName) {
        if (attempt > 15) return;
        AccessibilityNodeInfo root = null;
        try { root = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
        if (root == null) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { tryAutoClickAdminApp(attempt + 1, appName); }
            }, 300);
            return;
        }
        try {
            List<AccessibilityNodeInfo> title = root.findAccessibilityNodeInfosByText("设备管理");
            if (title == null || title.isEmpty()) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() { tryAutoClickAdminApp(attempt + 1, appName); }
                }, 300);
                return;
            }
            List<AccessibilityNodeInfo> appNodes = root.findAccessibilityNodeInfosByText(appName);
            boolean clicked = false;
            if (appNodes != null) {
                for (int i = 0; i < appNodes.size(); i++) {
                    if (appNodes.get(i) != null && clickUpwards(appNodes.get(i)) != null) {
                        clicked = true;
                        break;
                    }
                }
            }
            if (clicked) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() { tryAutoClickEnableAdmin(0); }
                }, 800);
            }
        } catch (Throwable ignored) {
        } finally {
            try { root.recycle(); } catch (Throwable ignored) {}
        }
    }

    public void tryAutoClickEnableAdmin(final int attempt) {
        if (attempt > 20) return;
        AccessibilityNodeInfo root = null;
        try { root = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
        if (root == null) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { tryAutoClickEnableAdmin(attempt + 1); }
            }, 500);
            return;
        }
        boolean clicked = false;
        try {
            clicked = scanAndClick(root);
        } catch (Throwable ignored) {
        } finally {
            try { root.recycle(); } catch (Throwable ignored) {}
        }
        if (clicked) {
            Toast.makeText(service, "设备管理员已启用", Toast.LENGTH_SHORT).show();
        } else {
            handler.postDelayed(new Runnable() {
                @Override public void run() { tryAutoClickEnableAdmin(attempt + 1); }
            }, 500);
        }
    }

    // ==================== 旧入口保留 ====================

    /** 自动授权（用户主动触发） */
    public void autoAuthorize() {
        Toast.makeText(service, "自动授权开始...", Toast.LENGTH_SHORT).show();
        ShellExecutor.execShizuku(new String[] {"appops", "set", service.getPackageName(), "POST_NOTIFICATIONS", "allow"});
        ShellExecutor.execRoot("appops set " + service.getPackageName() + " POST_NOTIFICATIONS allow");
        enableAccessibilityByShell();
        startAutoClickLoop();
    }

    public void enableAccessibilityByShell() {
        String component = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
        String cmd1 = "settings put secure enabled_accessibility_services '" + component + "'";
        String cmd2 = "settings put secure accessibility_enabled 1";
        ShellExecutor.execShizuku(new String[] {"sh", "-c", cmd1 + " && " + cmd2});
        ShellExecutor.execRoot(cmd1 + " && " + cmd2);
    }
}
