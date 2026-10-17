package xiaote.AnQuan.IncompatibleApp;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import xiaote.AnQuan.BaseActivity;
import xiaote.AnQuan.SwipeBackHelper;
import xiaote.xtui.XtDialog;
import xiaote.xtui.XtUi;

/**
 * 不兼容应用检测入口。
 *
 * 轻页面，本身不做深度扫描：打开时后台跑一次快速扫描
 * （声明了无障碍服务的应用 ∩ 内置不兼容包名清单），
 * 命中就在本页提醒是否进入详细扫描；
 * 详细扫描、结果列表与处置都在 ScanActivity。
 *
 * 不兼容不等于恶意：这类应用多为同类型防护软件，可能互相拦截安全操作，
 * 导致防护失效甚至系统卡死。
 *
 * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟。
 */
public class Activity extends BaseActivity {

    private TextView statusView;
    private volatile boolean checking = false;
    private volatile boolean prompted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, "不兼容应用检测"));
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this, "检测本机中可能与星特安全不兼容的应用。");
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, XtUi.dp(this, 16));
        root.addView(tip);

        // ============ 状态 ============
        LinearLayout cardStatus = XtUi.card(this);
        statusView = new TextView(this);
        statusView.setTextSize(14f);
        statusView.setTextColor(XtUi.colorMuted(this));
        statusView.setGravity(Gravity.CENTER);
        statusView.setText("正在检查…");
        cardStatus.addView(statusView);
        root.addView(cardStatus);

        // ============ 操作 ============
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.bottomMargin = XtUi.dp(this, XtUi.cardGapDp);
        root.addView(XtUi.primaryButton(this, "详细扫描", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Activity.this, ScanActivity.class));
            }
        }), blp);

        // ============ 说明 ============
        LinearLayout cardNote = XtUi.cardWithSection(this, "说明");
        TextView note = XtUi.tip(this,
                "1. 不兼容不等于恶意：这类应用多为同类型防护软件，"
                        + "与星特安全目标相近、实现相斥；\n"
                        + "2. 同时运行会互相拦截对方的安全操作，"
                        + "导致卸载、冻结、关闭无障碍等动作被对方挡下而失效；\n"
                        + "3. 争抢无障碍服务与悬浮窗时，双方可能反复掉线、"
                        + "被对方反复关闭，防护形同虚设；\n"
                        + "4. 极端情况下相互锁死，表现为系统卡死、"
                        + "桌面无响应，甚至互相卸载；\n"
                        + "5. 建议同类防护软件只保留一个。");
        note.setLineSpacing(0, 1.4f);
        cardNote.addView(note);
        root.addView(cardNote);

        setContentView(scrollView);

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        runQuickCheck();
    }

    // ==================== 后台快速检查 ====================

    /**
     * 打开本页就后台跑一次快速扫描，只做无障碍应用与不兼容包名清单的比对，
     * 一次 PackageManager 调用，成本很低；命中才提醒，未命中不打扰。
     */
    private void runQuickCheck() {
        if (checking) return;
        checking = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                try { n = Scanner.quickScan(Activity.this).size(); } catch (Throwable ignored) {}
                final int count = n;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        if (isFinishing() || statusView == null) return;
                        if (count > 0) {
                            statusView.setText("检测到 " + count + " 个可能不兼容的应用");
                            if (!prompted) {
                                prompted = true;
                                showScanPromptDialog();
                            }
                        } else {
                            statusView.setText("未检测到可能不兼容的应用");
                        }
                    }
                });
            }
        }, "incompatible-quick").start();
    }

    /**
     * 提醒弹窗：不列具体应用、不解释细节，只问是否详细扫描。
     * 详细结果在 ScanActivity 里呈现。
     */
    private void showScanPromptDialog() {
        android.app.AlertDialog dlg = XtDialog.builder(Activity.this, "不兼容应用提醒",
                        "检测到本机存在可能与星特安全不兼容的应用。\n\n"
                                + "是否进行详细扫描？")
                .setNegativeButton("暂不", null)
                .setPositiveButton("详细扫描", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(Activity.this, ScanActivity.class));
                    }
                })
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    // ==================== 返回回调 ====================

    private void registerBackCallback() {
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                try {
                    getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                            new android.window.OnBackInvokedCallback() {
                                @Override
                                public void onBackInvoked() { finish(); }
                            });
                } catch (Exception ignored) {}
            }
        });
    }
}
