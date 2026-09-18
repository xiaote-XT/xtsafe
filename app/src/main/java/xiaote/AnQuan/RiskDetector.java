package xiaote.AnQuan;

import xiaote.xtui.HintOverlayManager;

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
    private final HintOverlayManager hintOverlayManager;

    private static final String[] RISK_GUIDE_KEYWORDS = {
            "开启", "权限", "已安装的服务", "选择本应用", "下一步", "确定", "其他厂商"
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
                        HintOverlayManager hintOverlayManager) {
        this.service = service;
        this.handler = handler;
        this.dotPrefs = dotPrefs;
        this.overlayManager = overlayManager;
        this.hintOverlayManager = hintOverlayManager;
    }

    /** 当前是否正在显示风险横幅 */
    public boolean hasBanner() {
        return hintOverlayManager != null && hintOverlayManager.isRiskBannerShowing();
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

    /** 模糊匹配是否为"诱导开启权限"的风险话术 */
    public boolean isRiskGuideText(String text) {
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
    public void checkRiskGuideText(final String pkg, String text) {
        try {
            if (!dotPrefs.getBoolean("smart_risk_text_notify", true)) {
                dismissRiskBanner();
                return;
            }
            if (isRiskGuideText(text)) {
                // 命中：刷新时间戳、取消待收起任务，未显示时才创建（已显示则保持，不重播动画）
                lastHitTime = System.currentTimeMillis();
                cancelDismissTask();
                if (hintOverlayManager != null && !hintOverlayManager.isRiskBannerShowing()) {
                    hintOverlayManager.showRiskBanner(service.getString(R.string.risk_banner),
                            new HintOverlayManager.OnRiskBannerClick() {
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
                    if (hintOverlayManager != null) hintOverlayManager.hideRiskBanner();
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
    public void dismissRiskBanner() {
        cancelDismissTask();
        if (hintOverlayManager != null) hintOverlayManager.hideRiskBanner();
    }
}
