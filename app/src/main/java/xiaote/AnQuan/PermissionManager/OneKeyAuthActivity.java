package xiaote.AnQuan.PermissionManager;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

import xiaote.AnQuan.SwipeBackHelper;
import xiaote.dhizukutool.Dhizuku;
import xiaote.dhizukutool.DhizukuRequestPermissionListener;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * 一键授权。
 *
 * 流程：
 *   1. 检查无障碍：有 → 继续；无 → 跳转无障碍列表 + 居中提示，
 *      每秒轮询等待开启（最多 90 秒）。
 *   2. 无障碍就绪 → 启动持续自动点击（允许 / 始终允许 / 永远允许 /
 *      同意 / 始终同意 / 永远同意）。
 *   3. 依次申请：通知 → 省电优化 → Shizuku → Dhizuku。
 *   4. 结束：输出各步申请情况。
 *
 * 关键修正（之前"日志显示成功、实际没申请"的原因）：
 *   每步是否完成改为读「真实状态」，不再看前台窗口是不是星特安全。
 *   之前用 currentWindowPackage() 判定：无障碍读不到活动窗口时它返回空串，
 *   被当成"已回到星特安全"，于是弹窗刚弹出 2 秒就被判完成、直接跳到下一步，
 *   弹窗被顶掉——所以通知、Shizuku 根本没申请到，日志却一路 ✓。
 *   现在每 500ms 检查该步对应的系统真实状态（权限位 / 电池白名单 / Shizuku 授权 /
 *   Dhizuku 授权），达标才算成功，满 15 秒未达标记为失败，然后才回到本页走下一步。
 *
 * 每一步都会输出日志。点「取消自动授权」随时停止。
 *
 * 已按 xtui 规范重写：骨架 XtUi.column / XtUi.scroll，标题 XtUi.title，
 * 按钮 XtUi.primaryButton / XtUi.button，左上角返回钮 XtUi.attachBack。
 * 日志区保留固定高度可滚动，但底色与描边改走 xtui 取色。
 *
 * 继承 Activity 而非 BaseActivity：授权过程大量跳转外部页面，
 * 中途弹密码锁会打断流程。
 */
public class OneKeyAuthActivity extends Activity {

    private static final int SHIZUKU_REQUEST_CODE = 10086;
    /** 无障碍轮询间隔 */
    private static final long ACC_POLL_INTERVAL_MS = 1000L;
    /** 无障碍最多等待次数（约 90 秒） */
    private static final int ACC_POLL_MAX = 90;
    /** 状态轮询间隔 */
    private static final long STEP_POLL_INTERVAL_MS = 500L;
    /** 单步最多等待次数（约 15 秒） */
    private static final int STEP_POLL_MAX = 30;

    private static final String ACTION_AUTO_AUTHORIZE_START = "xiaote.AnQuan.AUTO_AUTHORIZE_START";
    private static final String ACTION_AUTO_AUTHORIZE_STOP = "xiaote.AnQuan.AUTO_AUTHORIZE_STOP";
    private static final String ACTION_AUTO_CLICK_LOG = "xiaote.AnQuan.AUTO_CLICK_LOG";

    // ===== 分步状态 =====
    private static final int STEP_ACCESSIBILITY = 0;
    private static final int STEP_NOTIFY = 1;
    private static final int STEP_BATTERY = 2;
    private static final int STEP_SHIZUKU = 3;
    private static final int STEP_DHIZUKU = 4;
    private static final int STEP_DONE = 5;

    /** 状态文字颜色：进行中 */
    private static final int C_RUNNING = 0xFFC8781E;
    /** 状态文字颜色：成功 */
    private static final int C_OK = 0xFF3CA050;

    private TextView statusView;
    private TextView detailView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private int step = STEP_ACCESSIBILITY;
    private volatile boolean autoClickRequested = false;

    /** 各步申请结果 */
    private boolean notifyOk = false;
    private boolean batteryOk = false;
    private boolean shizukuOk = false;
    private boolean dhizukuOk = false;

    /** 接收自动点击进度广播，实时显示到界面下方 */
    private BroadcastReceiver logReceiver;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == SHIZUKU_REQUEST_CODE) {
                        shizukuOk = grantResult == PackageManager.PERMISSION_GRANTED;
                        appendDetail(shizukuOk ? "✓ Shizuku 已授权" : "✗ Shizuku 授权被拒绝");
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        registerLogReceiver();

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, "一键授权"));
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this,
                "先确认无障碍已开启，再依次申请通知、省电优化、Shizuku、Dhizuku；"
                        + "每一步最多等 15 秒，成功或超时都会回到星特安全");
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, XtUi.dp(this, 14));
        root.addView(tip);

        // ============ 状态 ============
        LinearLayout cardStatus = XtUi.card(this);

        statusView = new TextView(this);
        statusView.setTextSize(15f);
        statusView.setTextColor(XtUi.colorMuted(this));
        statusView.setGravity(Gravity.CENTER);
        statusView.setText("未开始");
        cardStatus.addView(statusView);

        TextView statusTip = XtUi.tip(this, "点下方「一键授权」开始；过程中可随时取消");
        statusTip.setGravity(Gravity.CENTER);
        statusTip.setPadding(0, XtUi.dp(this, 8), 0, 0);
        cardStatus.addView(statusTip);

        root.addView(cardStatus);

        // ============ 操作 ============
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-1, -2);
        root.addView(XtUi.primaryButton(this, "一键授权", new View.OnClickListener() {
            @Override public void onClick(View v) { startAuth(); }
        }), startLp);

        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(-1, -2);
        cancelLp.topMargin = XtUi.dp(this, 10);
        root.addView(XtUi.button(this, "取消自动授权", new View.OnClickListener() {
            @Override public void onClick(View v) { cancelAuth(); }
        }), cancelLp);

        // ============ 执行进度 ============
        // 小标题左对齐加粗，不居中、不套卡片标题（本页是表单 + 日志，不是分区页）
        TextView logLabel = new TextView(this);
        logLabel.setText("执行进度");
        logLabel.setTextSize(14f);
        logLabel.setTypeface(null, Typeface.BOLD);
        logLabel.setTextColor(XtUi.colorText(this));
        logLabel.setPadding(0, XtUi.dp(this, 20), 0, XtUi.dp(this, 8));
        root.addView(logLabel);

        ScrollView logScroll = new ScrollView(this);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setCornerRadius(XtUi.dp(this, XtUi.radiusDp));
        logBg.setColor(XtUi.colorCard(this));
        int logBw = XtUi.dp(this, XtUi.borderDp);
        if (logBw < 1) logBw = 1;
        logBg.setStroke(logBw, XtUi.colorBorder(this));
        logScroll.setBackground(logBg);
        logScroll.setPadding(XtUi.dp(this, 12), XtUi.dp(this, 10),
                XtUi.dp(this, 12), XtUi.dp(this, 10));
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, XtUi.dp(this, 280)));

        detailView = new TextView(this);
        detailView.setTextSize(11f);
        detailView.setTextColor(XtUi.colorMuted(this));
        detailView.setTextIsSelectable(true);
        detailView.setText("等待开始…");
        logScroll.addView(detailView);

        setContentView(scrollView);
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);
    }

    /** 通知权限结果回调：只记日志，成功与否由 waitStepDone 读真实权限位判定 */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 2000) {
            boolean ok = grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            appendDetail(ok ? "✓ 通知权限已授予" : "✗ 通知权限被拒绝");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { Shizuku.removeRequestPermissionResultListener(permissionListener); } catch (Throwable ignored) {}
        unregisterLogReceiver();
        sendControl(ACTION_AUTO_AUTHORIZE_STOP);
    }

    // ==================== 自动点击进度广播 ====================

    private void registerLogReceiver() {
        try {
            if (logReceiver != null) return;
            logReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String msg = intent.getStringExtra("msg");
                    if (msg == null || msg.isEmpty()) return;
                    appendDetail("· " + msg);
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_AUTO_CLICK_LOG);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(logReceiver, filter);
            }
        } catch (Throwable ignored) {}
    }

    private void unregisterLogReceiver() {
        try {
            if (logReceiver != null) {
                unregisterReceiver(logReceiver);
                logReceiver = null;
            }
        } catch (Throwable ignored) {}
    }

    // ==================== 流程控制 ====================

    private void startAuth() {
        if (running) {
            XtToast.show(this, "已在授权中");
            return;
        }
        running = true;
        step = STEP_ACCESSIBILITY;
        autoClickRequested = false;
        notifyOk = false;
        batteryOk = false;
        shizukuOk = false;
        dhizukuOk = false;
        setStatus("正在授权…", C_RUNNING);
        detailView.setText("");
        appendDetail("开始一键授权");

        // 无障碍就绪前不自动点击，先停掉可能残留的循环
        sendControl(ACTION_AUTO_AUTHORIZE_STOP);
        runStep();
    }

    private void cancelAuth() {
        running = false;
        step = STEP_DONE;
        autoClickRequested = false;
        sendControl(ACTION_AUTO_AUTHORIZE_STOP);
        setStatus("已取消自动授权", XtUi.colorMuted(this));
        appendDetail("已取消自动授权");
    }

    /** 结束：停自动点击，输出每步申请情况 */
    private void finishFlow() {
        running = false;
        step = STEP_DONE;
        autoClickRequested = false;
        sendControl(ACTION_AUTO_AUTHORIZE_STOP);
        setStatus("授权流程完成", C_OK);
        appendDetail("===== 申请情况 =====");
        appendDetail("通知权限：" + (notifyOk ? "✓ 已授予" : "✗ 未授予"));
        appendDetail("省电优化：" + (batteryOk ? "✓ 已加入白名单" : "✗ 未生效"));
        appendDetail("Shizuku：" + (shizukuOk ? "✓ 已授权" : "✗ 未授权"));
        appendDetail("Dhizuku：" + (dhizukuOk ? "✓ 已授权" : "✗ 未授权"));
        appendDetail("一键授权流程结束");
    }

    /** 统一改状态文字：主线程安全，空引用安全 */
    private void setStatus(final String text, final int color) {
        if (statusView == null) return;
        handler.post(new Runnable() {
            @Override public void run() {
                if (isFinishing() || statusView == null) return;
                statusView.setText(text);
                statusView.setTextColor(color);
            }
        });
    }

    private void runStep() {
        if (!running) return;
        switch (step) {
            case STEP_ACCESSIBILITY: doAccessibility(); break;
            case STEP_NOTIFY: doNotify(); break;
            case STEP_BATTERY: doBattery(); break;
            case STEP_SHIZUKU: doShizuku(); break;
            case STEP_DHIZUKU: doDhizuku(); break;
            default: break;
        }
    }

    // ==================== 各步真实状态判定 ====================

    /**
     * 该步是否已达成（读系统真实状态，不看前台窗口）。
     * 这是修正"日志说成功、实际没申请"的核心：只有状态真的变了才算完成。
     */
    private boolean isStepDone(int s) {
        try {
            switch (s) {
                case STEP_NOTIFY:
                    if (Build.VERSION.SDK_INT < 33) return true;
                    return checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            == PackageManager.PERMISSION_GRANTED;
                case STEP_BATTERY: {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                }
                case STEP_SHIZUKU:
                    return Shizuku.pingBinder()
                            && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                case STEP_DHIZUKU:
                    return Dhizuku.init(this) && Dhizuku.isPermissionGranted();
                default:
                    return true;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 轮询等待当前步骤达成。
     *
     * 每 500ms 读一次真实状态：
     *   · 状态达标 → 成功，进入下一步；
     *   · 满 15 秒仍未达标 → 失败，回到星特安全后进入下一步。
     * 全程不因为"前台窗口是本应用"就提前判定完成，那是弹窗被顶掉、
     * 权限根本没申请到的根因。
     */
    private void waitStepDone(final int target, final int attempt) {
        if (!running || step != target) return;
        if (isStepDone(target)) {
            appendDetail("✓ 状态已达成");
            afterStep(target, true);
            return;
        }
        if (attempt >= STEP_POLL_MAX) {
            appendDetail("✗ 等待超时（15 秒），未达成");
            bringSelfToFront();
            handler.postDelayed(new Runnable() {
                @Override public void run() { afterStep(target, false); }
            }, 600L);
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { waitStepDone(target, attempt + 1); }
        }, STEP_POLL_INTERVAL_MS);
    }

    /** 步骤结束：记录真实结果并推进到下一步 */
    private void afterStep(final int doneStep, final boolean ok) {
        if (!running || step != doneStep) return;
        if (doneStep == STEP_NOTIFY) {
            notifyOk = ok;
            appendDetail(ok ? "✓ 通知权限申请完成" : "✗ 通知权限未获得");
        } else if (doneStep == STEP_BATTERY) {
            batteryOk = ok;
            appendDetail(ok ? "✓ 省电优化申请完成" : "✗ 省电优化未生效");
        } else if (doneStep == STEP_SHIZUKU) {
            shizukuOk = ok;
            appendDetail(ok ? "✓ Shizuku 申请完成" : "✗ Shizuku 未授权");
        } else if (doneStep == STEP_DHIZUKU) {
            dhizukuOk = ok;
            appendDetail(ok ? "✓ Dhizuku 申请完成" : "✗ Dhizuku 未授权");
        }
        nextStep();
    }

    /** 推进到下一步：先回到星特安全，稳定 600ms 后执行下一步 */
    private void nextStep() {
        if (!running) return;
        step++;
        if (step > STEP_DHIZUKU) {
            finishFlow();
            return;
        }
        final int target = step;
        bringSelfToFront();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (running && step == target) runStep();
            }
        }, 600L);
    }

    // ==================== 步骤 0：无障碍 ====================

    private void doAccessibility() {
        appendDetail("步骤 1/5：检查无障碍");
        if (isAccessibilityOn()) {
            appendDetail("✓ 无障碍已开启，继续");
            onAccessibilityReady();
            return;
        }
        appendDetail("→ 无障碍未开启，跳转无障碍列表");
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            appendDetail("✗ 无法打开无障碍设置页");
        }
        toastCenter("请在无障碍列表中开启「星特安全」的无障碍服务");
        pollAccessibility(0);
    }

    /** 轮询等待无障碍开启；开启后启动自动点击并进入下一步 */
    private void pollAccessibility(final int attempt) {
        if (!running) return;
        if (isAccessibilityOn()) {
            appendDetail("✓ 无障碍已开启，继续");
            onAccessibilityReady();
            return;
        }
        if (attempt >= ACC_POLL_MAX) {
            appendDetail("✗ 等待无障碍超时，流程结束");
            finishFlow();
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { pollAccessibility(attempt + 1); }
        }, ACC_POLL_INTERVAL_MS);
    }

    /** 无障碍就绪：启动自动点击循环，进入通知步骤 */
    private void onAccessibilityReady() {
        requestAutoClickStart();
        nextStep();
    }

    // ==================== 步骤 1：通知权限 ====================

    private void doNotify() {
        appendDetail("步骤 2/5：申请通知权限");
        if (isStepDone(STEP_NOTIFY)) {
            appendDetail(Build.VERSION.SDK_INT < 33
                    ? "✓ 当前系统无需单独授权" : "✓ 通知权限已具备");
            afterStep(STEP_NOTIFY, true);
            return;
        }
        try {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2000);
            appendDetail("→ 弹窗已弹出，自动点击「允许 / 同意」");
        } catch (Throwable t) {
            appendDetail("✗ 通知权限申请异常");
        }
        waitStepDone(STEP_NOTIFY, 0);
    }

    // ==================== 步骤 2：省电优化 ====================

    private void doBattery() {
        appendDetail("步骤 3/5：申请取消省电优化");
        if (isStepDone(STEP_BATTERY)) {
            appendDetail("✓ 已在电池优化白名单");
            afterStep(STEP_BATTERY, true);
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            appendDetail("→ 已跳转省电优化，自动点击「允许 / 始终允许 / 永远允许」");
        } catch (Throwable t) {
            appendDetail("✗ 无法打开省电优化设置");
        }
        waitStepDone(STEP_BATTERY, 0);
    }

    // ==================== 步骤 3：Shizuku ====================

    private void doShizuku() {
        appendDetail("步骤 4/5：申请 Shizuku 权限");
        if (isStepDone(STEP_SHIZUKU)) {
            appendDetail("✓ Shizuku 已授权");
            afterStep(STEP_SHIZUKU, true);
            return;
        }
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                appendDetail("→ 弹窗已弹出，自动点击「允许 / 同意」");
            } else {
                appendDetail("✗ Shizuku 未运行，无法申请");
                afterStep(STEP_SHIZUKU, false);
                return;
            }
        } catch (Throwable t) {
            appendDetail("✗ Shizuku 调用异常");
        }
        waitStepDone(STEP_SHIZUKU, 0);
    }

    // ==================== 步骤 4：Dhizuku ====================

    private void doDhizuku() {
        appendDetail("步骤 5/5：申请 Dhizuku 权限");
        if (isStepDone(STEP_DHIZUKU)) {
            appendDetail("✓ Dhizuku 已授权");
            afterStep(STEP_DHIZUKU, true);
            return;
        }
        try {
            if (Dhizuku.init(this)) {
                Dhizuku.requestPermission(new DhizukuRequestPermissionListener() {
                    @Override
                    public void onRequestPermission(int requestCode) {
                        appendDetail(requestCode == 0 ? "✓ Dhizuku 已授权" : "✗ Dhizuku 授权未通过: " + requestCode);
                    }
                });
                appendDetail("→ 已跳转 Dhizuku，自动点击「允许 / 同意」");
            } else {
                appendDetail("✗ Dhizuku 未安装或未设为设备所有者");
                afterStep(STEP_DHIZUKU, false);
                return;
            }
        } catch (Throwable t) {
            appendDetail("✗ Dhizuku 调用异常");
        }
        waitStepDone(STEP_DHIZUKU, 0);
    }

    /** 只在需要时发一次自动点击启动广播 */
    private void requestAutoClickStart() {
        if (autoClickRequested) return;
        autoClickRequested = true;
        sendControl(ACTION_AUTO_AUTHORIZE_START);
        appendDetail("→ 已启动持续自动点击（允许/始终允许/永远允许/同意/始终同意/永远同意）");
    }

    // ==================== 拉回前台 ====================

    /**
     * 把本页拉回前台。
     *
     * Android 10+ 限制后台应用启动 Activity，本页自己 startActivity 可能被拦截，
     * 优先广播给无障碍服务（前台服务，具备后台启动 Activity 特权）代发，再兜底。
     */
    private void bringSelfToFront() {
        try {
            Intent b = new Intent("xiaote.AnQuan.BRING_TO_FRONT");
            b.setPackage(getPackageName());
            sendBroadcast(b);
        } catch (Throwable ignored) {}
        try {
            Intent intent = new Intent(this, OneKeyAuthActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Throwable ignored) {}
    }

    // ==================== 辅助 ====================

    private void sendControl(String action) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Throwable ignored) {}
    }

    private boolean isAccessibilityOn() {
        try {
            String list = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return false;
            return list.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 居中提示。
     *
     * 这里刻意保留原生 Toast 而不是换 XtToast：XtToast 的 pill 需要
     * AccessibilityService 才能拿到 TYPE_ACCESSIBILITY_OVERLAY，
     * 本页是普通 Activity 用不了；而这一步的提示必须居中、盖在系统设置页上方，
     * 位置比样式重要。
     */
    private void toastCenter(String msg) {
        try {
            Toast t = Toast.makeText(this, msg, Toast.LENGTH_LONG);
            t.setGravity(Gravity.CENTER, 0, 0);
            t.show();
        } catch (Throwable ignored) {}
    }

    private void appendDetail(final String msg) {
        handler.post(new Runnable() {
            @Override public void run() {
                if (isFinishing() || detailView == null) return;
                String old = detailView.getText() == null ? "" : detailView.getText().toString();
                if (old.equals("等待开始…")) old = "";
                if (old.length() > 8000) old = old.substring(old.length() - 6000);
                detailView.setText(old + (old.isEmpty() ? "" : "\n") + msg);
            }
        });
    }

    private void registerBackCallback() {
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                try {
                    getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                            new android.window.OnBackInvokedCallback() {
                                @Override
                                public void onBackInvoked() {
                                    cancelAuth();
                                    finish();
                                }
                            });
                } catch (Exception ignored) {}
            }
        });
    }
}
