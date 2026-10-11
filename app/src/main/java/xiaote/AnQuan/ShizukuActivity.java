package xiaote.AnQuan;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import xiaote.xtui.XtDialog;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * Shizuku 增强。
 *
 * 三个功能：
 *   1. 阻止卸载星特安全（自身）
 *   2. 阻止卸载：阻止所有应用被卸载，防止恶意应用卸载游戏
 *   3. 阻止阻止卸载：阻止一些应用拦截卸载（不含无障碍自动点击拦截）
 *
 * 「阻止卸载」与「阻止阻止卸载」效果相反，互斥，同时只会生效一个。
 * 检查频率以 100 毫秒为一个 tick，最低 0.1 秒执行一次，上限 120 分钟。
 * 自动执行结果写入 BlockUninstallManager 的日志文件，不弹 Toast。
 *
 * 继承 Activity 而非 BaseActivity：授权/执行期间不弹密码锁打断操作。
 */
public class ShizukuActivity extends Activity {

    private TextView statusView;
    private EditText tidInput;
    private XtUi.XtSwitch blockSwitch;
    private TextView blockHint;

    private XtUi.XtSwitch batchSwitch;
    private TextView batchFreqTip;
    private TextView batchHint;

    private XtUi.XtSwitch unblockSwitch;
    private TextView unblockFreqTip;
    private TextView unblockHint;

    /** 代码回写开关状态时置位，避免触发监听造成递归 */
    private boolean settingSwitch = false;
    private boolean tidInitialized = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 界面模式分流：ui_mode=h5 时交给 H5 壳，原生界面不构建。默认 native。
        if ("h5".equals(getSharedPreferences("dot_config", MODE_PRIVATE)
                .getString("ui_mode", "native"))) {
            try { startActivity(new android.content.Intent(this, H5ShizukuActivity.class)); } catch (Throwable ignored) {}
            finish();
            return;
        }

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, "Shizuku 增强"));
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this, "通过 Shizuku 调用系统卸载阻止接口，阻止/解除应用被卸载");
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, XtUi.dp(this, 12));
        root.addView(tip);

        // ============ 使用提示 ============
        LinearLayout cardNotice = XtUi.card(this);
        TextView notice = new TextView(this);
        notice.setText(
            "使用提示：\n"
            + "1. TID 默认不需要动。如果不小心改了，把设备的 Android 版本告诉 AI，让 AI 告诉你怎么填。\n"
            + "2. 设备必须有设备所有者（Device Owner），防卸载命令才会真正生效。"
            + "可以选择星特安全、Dhizuku 或其他支持该提权操作的应用激活。"
            + "不清楚是什么、为什么激活不了，问 AI，推荐 DeepSeek 而不是豆包。");
        notice.setTextColor(XtUi.colorMuted(this));
        notice.setTextSize(11);
        notice.setLineSpacing(0, 1.35f);
        cardNotice.addView(notice);
        root.addView(cardNotice);

        // ============ 状态卡片 ============
        LinearLayout cardStatus = XtUi.cardWithSection(this, "运行状态");
        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextColor(XtUi.colorMuted(this));
        statusView.setGravity(Gravity.CENTER);
        cardStatus.addView(statusView);
        root.addView(cardStatus);

        // ============ 事务号卡片 ============
        LinearLayout cardTid = XtUi.cardWithSection(this, "事务号 TID");
        cardTid.addView(XtUi.tip(this, "默认不动，误改后告诉 AI 你的 Android 版本"));

        LinearLayout tidRow = new LinearLayout(this);
        tidRow.setOrientation(LinearLayout.HORIZONTAL);
        tidRow.setGravity(Gravity.CENTER_VERTICAL);
        tidRow.setPadding(0, XtUi.dp(this, 8), 0, 0);

        tidInput = XtDialog.input(this);
        tidInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        tidInput.setHint("默认按 SDK 推导");
        tidInput.setSingleLine(true);
        tidRow.addView(tidInput, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView applyTidBtn = XtUi.button(this, "应用", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String s = tidInput.getText().toString().trim();
                if (!s.isEmpty()) {
                    try {
                        int t = Integer.parseInt(s);
                        if (t <= 0) throw new NumberFormatException();
                        BlockUninstallManager.setTidOverride(ShizukuActivity.this, t);
                    } catch (Exception e) {
                        XtToast.show(ShizukuActivity.this, "事务号必须为正整数");
                        return;
                    }
                } else {
                    BlockUninstallManager.setTidOverride(ShizukuActivity.this, 0);
                }
                refresh();
            }
        });
        LinearLayout.LayoutParams atLp = new LinearLayout.LayoutParams(-2, -2);
        atLp.leftMargin = XtUi.dp(this, 8);
        tidRow.addView(applyTidBtn, atLp);
        cardTid.addView(tidRow);
        root.addView(cardTid);

        // ============ 自身 ============
        LinearLayout cardSelf = XtUi.cardWithSection(this, "阻止卸载星特安全");

        blockSwitch = XtUi.switchView(this, false);
        blockSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(XtUi.XtSwitch buttonView, boolean isChecked) {
                if (settingSwitch) return;
                applyBlock(isChecked);
            }
        });
        cardSelf.addView(XtUi.row(this, "阻止卸载星特安全", blockSwitch));

        blockHint = new TextView(this);
        blockHint.setTextSize(11);
        blockHint.setTextColor(XtUi.colorMuted(this));
        cardSelf.addView(blockHint);
        root.addView(cardSelf);

        // ============ 阻止卸载 ============
        LinearLayout cardBatch = XtUi.cardWithSection(this, "阻止卸载");

        batchSwitch = XtUi.switchView(this, false);
        batchSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(XtUi.XtSwitch buttonView, boolean isChecked) {
                if (settingSwitch) return;
                BlockUninstallManager.setBatchEnabled(ShizukuActivity.this, isChecked);
                // 互斥：另一项可能被自动关闭，回读开关状态
                syncSwitches();
                if (isChecked) runBatchNow(true);
                refresh();
            }
        });
        cardBatch.addView(XtUi.row(this, "阻止卸载", batchSwitch));

        batchHint = new TextView(this);
        batchHint.setText("阻止所有应用被卸载，防止恶意应用卸载游戏。");
        batchHint.setTextColor(XtUi.colorMuted(this));
        batchHint.setTextSize(11);
        cardBatch.addView(batchHint);

        batchFreqTip = new TextView(this);
        batchFreqTip.setTextSize(11);
        batchFreqTip.setTextColor(XtUi.colorMuted(this));
        batchFreqTip.setPadding(0, XtUi.dp(this, 6), 0, 0);
        cardBatch.addView(batchFreqTip);

        SeekBar batchFreqSeek = XtUi.slider(this,
                BlockUninstallManager.MAX_INTERVAL_TICKS - BlockUninstallManager.MIN_INTERVAL_TICKS,
                BlockUninstallManager.getBatchIntervalTicks(this) - BlockUninstallManager.MIN_INTERVAL_TICKS);
        batchFreqSeek.setPadding(XtUi.dp(this, 12), 0, XtUi.dp(this, 12), XtUi.dp(this, 4));
        batchFreqSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = progress + BlockUninstallManager.MIN_INTERVAL_TICKS;
                BlockUninstallManager.setBatchIntervalTicks(ShizukuActivity.this, v);
                batchFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(v));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cardBatch.addView(batchFreqSeek);
        root.addView(cardBatch);

        // ============ 阻止阻止卸载 ============
        LinearLayout cardUnblock = XtUi.cardWithSection(this, "阻止阻止卸载");

        unblockSwitch = XtUi.switchView(this, false);
        unblockSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(XtUi.XtSwitch buttonView, boolean isChecked) {
                if (settingSwitch) return;
                BlockUninstallManager.setUnblockBatchEnabled(ShizukuActivity.this, isChecked);
                syncSwitches();
                if (isChecked) runBatchNow(false);
                refresh();
            }
        });
        cardUnblock.addView(XtUi.row(this, "阻止阻止卸载", unblockSwitch));

        unblockHint = new TextView(this);
        unblockHint.setText("阻止一些应用拦截卸载（不含无障碍自动点击拦截），避免恶意应用无法被卸载。与上方效果相反，不能同时开启。");
        unblockHint.setTextColor(XtUi.colorMuted(this));
        unblockHint.setTextSize(11);
        cardUnblock.addView(unblockHint);

        unblockFreqTip = new TextView(this);
        unblockFreqTip.setTextSize(11);
        unblockFreqTip.setTextColor(XtUi.colorMuted(this));
        unblockFreqTip.setPadding(0, XtUi.dp(this, 6), 0, 0);
        cardUnblock.addView(unblockFreqTip);

        SeekBar unblockFreqSeek = XtUi.slider(this,
                BlockUninstallManager.MAX_INTERVAL_TICKS - BlockUninstallManager.MIN_INTERVAL_TICKS,
                BlockUninstallManager.getUnblockBatchIntervalTicks(this) - BlockUninstallManager.MIN_INTERVAL_TICKS);
        unblockFreqSeek.setPadding(XtUi.dp(this, 12), 0, XtUi.dp(this, 12), XtUi.dp(this, 4));
        unblockFreqSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = progress + BlockUninstallManager.MIN_INTERVAL_TICKS;
                BlockUninstallManager.setUnblockBatchIntervalTicks(ShizukuActivity.this, v);
                unblockFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(v));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cardUnblock.addView(unblockFreqSeek);
        root.addView(cardUnblock);

        // ============ 操作 ============
        LinearLayout cardOps = XtUi.cardWithSection(this, "操作");
        XtUi.addButton(cardOps, this, "刷新状态", new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        });
        root.addView(cardOps);

        setContentView(scrollView);
        // 阻止 EditText 自动获取焦点弹出键盘（用户点一下才会唤起）
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
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
                                public void onBackInvoked() { finish(); }
                            });
                } catch (Exception ignored) {}
            }
        });
    }

    // ==================== 状态刷新 ====================

    private void refresh() {
        statusView.setText("正在检查...");
        statusView.setTextColor(XtUi.colorMuted(this));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ready = BlockUninstallManager.isShizukuReady();
                final int tid = BlockUninstallManager.getTid(ShizukuActivity.this);
                final Boolean real = BlockUninstallManager.readBlocked(
                        ShizukuActivity.this, ShizukuActivity.this.getPackageName());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        applyUi(ready, tid, real);
                    }
                });
            }
        }, "shizuku-check").start();
    }

    private void applyUi(boolean ready, int tid, Boolean real) {
        if (!tidInitialized) {
            tidInitialized = true;
            tidInput.setText(tid > 0 ? String.valueOf(tid) : "");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Shizuku 就绪: ").append(ready ? "是" : "否");
        sb.append("  事务号: ").append(tid > 0 ? String.valueOf(tid) : "无效");
        sb.append("\n自身状态: ").append(real == null ? "无法回读"
                : (real ? "已阻止卸载" : "未阻止"));
        statusView.setText(sb.toString());

        boolean canUse = ready && tid > 0;
        blockSwitch.setEnabled(canUse);
        batchSwitch.setEnabled(canUse);
        unblockSwitch.setEnabled(canUse);

        settingSwitch = true;
        try {
            if (real != null) blockSwitch.setChecked(real);
            else blockSwitch.setChecked(BlockUninstallManager.isSelfBlocked(this));
            batchSwitch.setChecked(BlockUninstallManager.isBatchEnabled(this));
            unblockSwitch.setChecked(BlockUninstallManager.isUnblockBatchEnabled(this));
        } finally {
            settingSwitch = false;
        }

        if (!ready) {
            blockHint.setText("需先授予 Shizuku 权限");
            batchHint.setText("需先授予 Shizuku 权限");
            unblockHint.setText("需先授予 Shizuku 权限");
        } else if (tid <= 0) {
            blockHint.setText("请先在输入框填写正确的事务号");
            batchHint.setText("事务号无效");
            unblockHint.setText("事务号无效");
        } else {
            if (real == null) blockHint.setText("无法回读真实状态，显示为上次设置值");
            else if (real) blockHint.setText("已阻止，普通方式无法卸载星特安全；需先在本页关闭");
            else blockHint.setText("开启后普通方式无法卸载星特安全");

            batchHint.setText("阻止所有应用被卸载，防止恶意应用卸载游戏。");
            unblockHint.setText("阻止一些应用拦截卸载（不含无障碍自动点击拦截），避免恶意应用无法被卸载。与上方效果相反，不能同时开启。");
        }

        batchFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(BlockUninstallManager.getBatchIntervalTicks(this)));
        unblockFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(BlockUninstallManager.getUnblockBatchIntervalTicks(this)));
    }

    /** 互斥后回读两个开关的真实勾选状态（不触发监听） */
    private void syncSwitches() {
        settingSwitch = true;
        try {
            batchSwitch.setChecked(BlockUninstallManager.isBatchEnabled(this));
            unblockSwitch.setChecked(BlockUninstallManager.isUnblockBatchEnabled(this));
        } finally {
            settingSwitch = false;
        }
    }

    private void revertSwitch(boolean target) {
        settingSwitch = true;
        try { blockSwitch.setChecked(!target); } finally { settingSwitch = false; }
    }

    // ==================== 自身阻止 ====================

    private void applyBlock(final boolean blocked) {
        if (BlockUninstallManager.getTid(this) <= 0) {
            XtToast.show(this, "事务号无效");
            revertSwitch(blocked);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = BlockUninstallManager.setBlocked(
                        ShizukuActivity.this, ShizukuActivity.this.getPackageName(), blocked);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (ok) {
                            BlockUninstallManager.setSelfBlocked(ShizukuActivity.this, blocked);
                            XtToast.show(ShizukuActivity.this,
                                    blocked ? "已阻止卸载星特安全" : "已允许卸载星特安全");
                        } else {
                            XtToast.showLong(ShizukuActivity.this,
                                    "执行未成功（检查事务号/权限/是否已激活设备所有者）");
                            revertSwitch(blocked);
                        }
                        refresh();
                    }
                });
            }
        }, "shizuku-block").start();
    }

    // ==================== 批量 ====================

    private void runBatchNow(final boolean blocked) {
        if (!BlockUninstallManager.isShizukuReady()) {
            XtToast.show(this, "Shizuku 未就绪");
            return;
        }
        if (BlockUninstallManager.getTid(this) <= 0) {
            XtToast.show(this, "事务号无效");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int[] r = BlockUninstallManager.runBatch(ShizukuActivity.this, blocked);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (r == null) {
                            XtToast.showLong(ShizukuActivity.this, "执行失败");
                        } else {
                            XtToast.showLong(ShizukuActivity.this,
                                    (blocked ? "已阻止 " : "已解除 ") + r[0] + " 个，失败 " + r[1]);
                        }
                        refresh();
                    }
                });
            }
        }, "shizuku-batch").start();
    }
}
