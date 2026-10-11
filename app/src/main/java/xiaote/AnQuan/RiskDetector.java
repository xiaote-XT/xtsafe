package xiaote.AnQuan;

import xiaote.xtui.XtToast;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 风险文字识别：模糊匹配"诱导开启权限"的木马话术，命中时顶部显示红色横幅。
 * 提取自 XTSafeMainService。
 */
public class RiskDetector {

    private final AccessibilityService service;
    private final Handler handler;
    private final SharedPreferences dotPrefs;
    private final OverlayManager overlayManager;
    private final XtToast xtToast;

    /** 诱导开启无障碍权限的单字关键词 */
    private static final String[] RISK_GUIDE_KEYWORDS = {
            "开启", "权限", "已安装的服务", "选择本应用", "下一步", "确定", "其他厂商"
    };

    /**
     * 分组诱导话术。
     *
     * 判定规则：组内每个词只要都出现即命中——不要求连续、不要求顺序、
     * 不要求出现在同一个节点里。恶意应用常把话术拆成多个 text 节点，
     * 或在同一 text 中插入换行、零宽字符绕过匹配；collectEventText 会把
     * 各节点文本用空格拼在一起，normalizeForMatch 再去掉所有空白与零宽字符，
     * 因此 "xxx" 与 "aaa" 无论各自出现在哪里都能被独立匹配到。
     *
     * 应用名（如"我的世界"）是变量，不写进规则；靠话术框架词识别。
     */
    private static final String[][] RISK_PHRASE_GROUPS = {
            // 温馨提醒式诱导开启权限
            {"温馨提醒", "大陆网络", "权限"},
            {"温馨提醒", "受限制", "开启"},
            {"温馨提醒", "使用步骤", "打开"},
            // 打开已下载服务 → 开始使用 → 等待加载 行为链
            {"使用步骤", "已下载服务", "开始使用"},
            {"使用步骤", "打开已下载", "开始使用"},
            {"已下载服务", "开始使用", "加载"},
            {"打开已下载", "开始使用", "加载"},
    };

    /** 命中后保持显示的最短时间：期间即使某次事件未命中也不收起，避免文字采集抖动导致反复弹灭 */
    private static final long HOLD_GRACE_MS = 2500L;

    /** 源节点浅遍历最大深度（快） */
    private static final int SOURCE_MAX_DEPTH = 3;
    /** 活动窗口全树遍历最大深度（仅窗口状态变化时执行，深） */
    private static final int ROOT_MAX_DEPTH = 6;
    /** 单节点最多遍历的子节点数 */
    private static final int MAX_CHILDREN = 20;

    /** 最近一次命中时间 */
    private long lastHitTime = 0L;
    /** 延迟收起任务 */
    private Runnable dismissTask;


    public RiskDetector(AccessibilityService service, Handler handler,
                        SharedPreferences dotPrefs, OverlayManager overlayManager,
                        XtToast xtToast) {
        this.service = service;
        this.handler = handler;
        this.dotPrefs = dotPrefs;
        this.overlayManager = overlayManager;
        this.xtToast = xtToast;
    }

	public void dismissRiskBanner() {
	}

    /** 当前是否正在显示风险横幅 */
    public boolean hasBanner() {
        return xtToast != null && xtToast.isBannerShowing();
    }

    /** 立即拷贝事件文本（AccessibilityEvent 被系统复用，不能延迟读取）
     *
     * 性能约束：AccessibilityNodeInfo 的每次读取都是跨进程调用，
     * 全树遍历必须只在窗口状态变化时做一次；CONTENT_CHANGED 事件极其高频，
     * 若每次都全树遍历会导致主线程被拖死（ANR）。
     */
    public String collectEventText(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        try {
            if (event.getText() != null) {
                for (CharSequence c : event.getText()) {
                    if (c != null) sb.append(c).append(" ");
                }
            }
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                collectNodeText(source, sb, 0, SOURCE_MAX_DEPTH);
            }
            boolean isStateChange = event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            if (isStateChange) {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null) {
                    collectNodeText(root, sb, 0, ROOT_MAX_DEPTH);
                }
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /** 深度遍历节点收集 text / contentDescription（限制深度与子节点数防卡顿） */
    private void collectNodeText(AccessibilityNodeInfo node, StringBuilder sb, int depth, int maxDepth) {
        try {
            if (node == null || depth > maxDepth) return;
            CharSequence t = node.getText();
            if (t != null && t.length() > 0) sb.append(t).append(" ");
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.length() > 0) sb.append(desc).append(" ");
            int count = node.getChildCount();
            if (count > MAX_CHILDREN) count = MAX_CHILDREN;
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                collectNodeText(child, sb, depth + 1, maxDepth);
            }
        } catch (Exception ignored) {}
    }

    /** 模糊匹配是否为木马诱导话术 */
    public boolean isRiskGuideText(String text) {
        if (text == null) return false;
        String t = normalizeForMatch(text);
        if (t.isEmpty()) return false;
        // 1. 分组话术：组内每个词独立匹配，跨节点 / 跨换行 / 含零宽字符均可命中
        if (hitPhraseGroup(t)) return true;
        // 2. 诱导开启无障碍权限：核心词 + 至少两个关键词
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
     * 任一分组内的所有词都出现即命中。
     * 每个词独立判断，不要求连续，也不要求顺序——这正是为了对抗
     * 木马把 "xxx" 和 "aaa" 拆到不同 text、或中间插换行/零宽字符的绕过手法。
     */
    private static boolean hitPhraseGroup(String text) {
        for (String[] group : RISK_PHRASE_GROUPS) {
            boolean all = true;
            for (String kw : group) {
                if (!text.contains(kw)) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }

    /**
     * 归一化：去掉所有空白（含全角空格）与常见零宽 / 不可见字符。
     * 木马常在关键词之间插换行或零宽字符让字符串匹配失败，这里统一清掉。
     */
    private static String normalizeForMatch(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", "");
        t = t.replace("\u200B", "").replace("\u200C", "").replace("\u200D", "")
                .replace("\u200E", "").replace("\u200F", "").replace("\uFEFF", "")
                .replace("\u2060", "").replace("\u00A0", "").replace("\u3000", "");
        return t;
    }

    /**
     * 风险文字状态机：
     * 命中风险 → 未显示则显示横幅（已显示则保持，不重建防频闪）
     * 未命中风险 → 风险文字已消失，收起横幅
     */
    public void checkRiskGuideText(final String pkg, String text) {
        try {
            if (!dotPrefs.getBoolean("smart_risk_text_notify", true)) {
                hideBanner();
                return;
            }
            if (isRiskGuideText(text)) {
                // 命中：刷新时间戳、取消待收起任务，未显示时才创建（已显示则保持，不重播动画）
                lastHitTime = System.currentTimeMillis();
                cancelDismissTask();
                if (xtToast != null && !xtToast.isBannerShowing()) {
                    xtToast.showBanner(service.getString(R.string.risk_banner),
                            new XtToast.OnClick() {
                                @Override
                                public void onClick() {
                                    if (pkg != null && !pkg.isEmpty()) overlayManager.showUninstallList(pkg);
                                    else overlayManager.showUninstallList();
                                }
                            });
                }
            } else {
                // 未命中：不立即收起，等宽限期后确认期间无新命中再收起
                scheduleDismiss();
            }
        } catch (Exception ignored) {}
    }

    /** 安排延迟收起：宽限期内若再次命中会被取消，避免事件抖动导致横幅反复弹灭 */
    private void scheduleDismiss() {
        if (dismissTask != null) return; // 已排队，不重复
        dismissTask = new Runnable() {
            @Override
            public void run() {
                dismissTask = null;
                if (System.currentTimeMillis() - lastHitTime >= HOLD_GRACE_MS) {
                    if (xtToast != null) xtToast.hideBanner();
                } else {
                    scheduleDismiss(); // 期间又有命中，重新计时
                }
            }
        };
        handler.postDelayed(dismissTask, HOLD_GRACE_MS);
    }

    private void cancelDismissTask() {
        if (dismissTask != null && handler != null) {
            handler.removeCallbacks(dismissTask);
            dismissTask = null;
        }
    }

    /** 立即收起（开关关闭、服务销毁等场景）；取消待执行任务 */
    public void hideBanner() {
        cancelDismissTask();
        if (xtToast != null) xtToast.hideBanner();
    }
}
