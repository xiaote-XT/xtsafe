package xiaote.AnQuan.IncompatibleApp;

import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import xiaote.AnQuan.BaseActivity;
import xiaote.AnQuan.FrozenPackages;
import xiaote.AnQuan.InternalApi.ApiResult;
import xiaote.AnQuan.InternalApi.UninstallApi;
import xiaote.AnQuan.SafeActionManager;
import xiaote.AnQuan.SwipeBackHelper;
import xiaote.xtui.XtDialog;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * 不兼容应用详细扫描。
 *
 * 进入即自动开始扫描：无障碍应用清单比对 + 50MB 以下 APK 的
 * AndroidManifest.xml 特征匹配，结果按 XTUI 列表展示（统一圆角图标）。
 *
 * 点击结果项可处置：冻结（可解冻）或卸载（不可恢复），均需确认，
 * 都走 Shizuku → Root 通道，在工作线程执行。
 *
 * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟。
 */
public class ScanActivity extends BaseActivity {

    private TextView statusView;
    private TextView startBtn;
    private LinearLayout resultCard;
    private LinearLayout resultBox;
    private TextView resultSummary;

    private volatile boolean scanning = false;

    /** 扫描进度回调：工作线程调用，内部切主线程刷新 */
    private final Scanner.Progress progress = new Scanner.Progress() {
        @Override
        public void onProgress(final int index, final int total) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || !scanning || statusView == null) return;
                    statusView.setText("正在扫描 " + index + "/" + total + "…");
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, "不兼容应用扫描"));
        XtUi.applyStatusBar(this);

        // ============ 状态 ============
        LinearLayout cardStatus = XtUi.card(this);
        statusView = new TextView(this);
        statusView.setTextSize(14f);
        statusView.setTextColor(XtUi.colorMuted(this));
        statusView.setGravity(Gravity.CENTER);
        statusView.setText("准备扫描…");
        cardStatus.addView(statusView);
        root.addView(cardStatus);

        // ============ 操作 ============
        // 按钮与下方结果卡片之间留出与卡片间距一致的空白，避免贴在一起
        startBtn = XtUi.primaryButton(this, "重新扫描", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.bottomMargin = XtUi.dp(this, XtUi.cardGapDp);
        root.addView(startBtn, blp);

        // ============ 结果 ============
        resultCard = XtUi.cardWithSection(this, "不兼容应用");
        resultSummary = new TextView(this);
        resultSummary.setTextSize(13f);
        resultSummary.setTextColor(XtUi.colorMuted(this));
        resultSummary.setGravity(Gravity.CENTER);
        resultSummary.setPadding(0, 0, 0, XtUi.dp(this, 6));
        resultCard.addView(resultSummary);

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultCard.addView(resultBox);

        resultCard.setVisibility(View.GONE);
        root.addView(resultCard);

        // ============ 说明 ============
        LinearLayout cardNote = XtUi.cardWithSection(this, "说明");
        TextView note = XtUi.tip(this,
                "1. 点击结果中的应用可选择卸载或冻结，冻结可在名单管理里解冻，"
                        + "卸载不可恢复；\n"
                        + "2. 处置依赖 Shizuku 或 Root，未就绪时操作会失败；\n"
                        + "3. 检测结果仅供排查参考，不代表应用存在恶意行为。");
        note.setLineSpacing(0, 1.4f);
        cardNote.addView(note);
        root.addView(cardNote);

        setContentView(scrollView);

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);

        // 进入即自动扫描
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                startScan();
            }
        });
    }

    // ==================== 扫描 ====================

    private void startScan() {
        if (scanning) return;
        scanning = true;
        if (startBtn != null) startBtn.setEnabled(false);
        statusView.setText("正在扫描…");
        resultCard.setVisibility(View.GONE);
        resultBox.removeAllViews();

        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Scanner.Hit> hits = null;
                try {
                    hits = Scanner.fullScan(ScanActivity.this, progress);
                } catch (Throwable ignored) {}
                final List<Scanner.Hit> result = hits;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        if (startBtn != null) startBtn.setEnabled(true);
                        if (isFinishing()) return;
                        showHits(result);
                    }
                });
            }
        }, "incompatible-scan").start();
    }

    // ==================== 结果渲染 ====================

    /** 主线程：渲染结果 */
    private void showHits(List<Scanner.Hit> hits) {
        resultBox.removeAllViews();
        resultCard.setVisibility(View.VISIBLE);

        if (hits == null || hits.isEmpty()) {
            statusView.setText("检测完成");
            resultSummary.setText("未发现不兼容应用");
            TextView empty = XtUi.tip(this, "本次扫描没有命中不兼容应用。");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, XtUi.dp(this, 8), 0, 0);
            resultBox.addView(empty);
            return;
        }

        statusView.setText("检测完成，发现 " + hits.size() + " 个不兼容应用");
        resultSummary.setText("共 " + hits.size() + " 个");

        for (int i = 0; i < hits.size(); i++) {
            final Scanner.Hit h = hits.get(i);
            String sub = h.pkg + (h.reason == null ? "" : "\n" + h.reason);
            View row = XtUi.itemRow(this, h.icon, h.name, sub, null);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showActionDialog(h);
                }
            });
            XtUi.attachScale(row);
            resultBox.addView(row);
            if (i < hits.size() - 1) resultBox.addView(divider());
        }
    }

    // ==================== 处置 ====================

    /**
     * 点击结果项后的处置询问。
     *
     * 只给冻结 / 卸载两个动作，均需用户确认：
     *   冻结 → SafeActionManager.freezeOnePublic（Shizuku → Root，pm disable-user 分级），
     *          成功后记入 FrozenPackages，可在名单管理里解冻；
     *   卸载 → UninstallApi（Shizuku → Root，pm uninstall --user 0），不可恢复。
     * 两者都是阻塞调用，放到工作线程执行。
     */
    private void showActionDialog(final Scanner.Hit h) {
        android.app.AlertDialog dlg = XtDialog.builder(this,
                        "处置「" + h.name + "」",
                        h.pkg + "\n"
                                + (h.reason == null ? "" : h.reason + "\n")
                                + "\n该应用可能与星特安全不兼容，可卸载或冻结。\n"
                                + "冻结后会停用应用，可在名单管理里解冻；卸载不可恢复，请谨慎选择。")
                .setNeutralButton("冻结", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doFreeze(h);
                    }
                })
                .setNegativeButton("卸载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doUninstall(h);
                    }
                })
                .setPositiveButton("取消", null)
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    /** 冻结单个应用（后台线程） */
    private void doFreeze(final Scanner.Hit h) {
        XtToast.show(this, "正在冻结…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    success = SafeActionManager.freezeOnePublic(h.pkg);
                } catch (Throwable ignored) {}
                final boolean ok = success;
                if (ok) {
                    try { FrozenPackages.add(ScanActivity.this, h.pkg); } catch (Throwable ignored) {}
                    try {
                        SafeActionManager.appendLog(ScanActivity.this,
                                "不兼容检测：已冻结 " + h.pkg);
                    } catch (Throwable ignored) {}
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        XtToast.showLong(ScanActivity.this,
                                ok ? "已冻结 " + h.name : "冻结失败，请确认 Shizuku 或 Root 已就绪");
                        if (ok) startScan();
                    }
                });
            }
        }, "incompatible-freeze").start();
    }

    /** 卸载单个应用（后台线程） */
    private void doUninstall(final Scanner.Hit h) {
        XtToast.show(this, "正在卸载…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                ApiResult r = null;
                try {
                    r = UninstallApi.uninstall(ScanActivity.this, h.pkg);
                } catch (Throwable ignored) {}
                final boolean ok = r != null && r.ok;
                final String detail = (r == null || r.ok) ? null : r.detail;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (ok) {
                            XtToast.showLong(ScanActivity.this, "已卸载 " + h.name);
                            startScan();
                        } else {
                            XtToast.showLong(ScanActivity.this,
                                    "卸载失败" + (detail == null || detail.length() == 0 ? "" : "：" + detail));
                        }
                    }
                });
            }
        }, "incompatible-uninstall").start();
    }

    /** 列表分隔线：0.75dp 细线，按主题描边色 */
    private View divider() {
        View v = new View(this);
        int h = XtUi.dp(this, 0.75f);
        if (h < 1) h = 1;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h);
        lp.topMargin = XtUi.dp(this, 4);
        lp.bottomMargin = XtUi.dp(this, 4);
        v.setLayoutParams(lp);
        v.setBackgroundColor(XtUi.colorBorder(this));
        return v;
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
