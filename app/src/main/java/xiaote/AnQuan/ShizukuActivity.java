package xiaote.AnQuan;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shizuku 增强。
 *
 * 三个功能：
 *   1. 阻止卸载星特安全（自身）
 *   2. 阻止卸载：阻止所有应用被卸载，防止恶意应用卸载游戏
 *   3. 阻止阻止卸载：阻止一些应用拦截卸载（不含无障碍自动点击拦截），避免恶意应用无法被卸载
 *
 * 「阻止卸载」与「阻止阻止卸载」效果相反，互斥，同时只会生效一个。
 * 检查频率以 100 毫秒为一个 tick，最低 0.1 秒执行一次，上限 120 分钟。
 * 自动执行结果写入 BlockUninstallManager 的日志文件（超过 2MB 自动清空），
 * 不弹 Toast。
 *
 * 继承 Activity 而非 BaseActivity：授权/执行期间不弹密码锁打断操作。
 */
public class ShizukuActivity extends Activity {

    private TextView statusView;
    private EditText tidInput;
    private Switch blockSwitch;
    private TextView blockHint;

    private Switch batchSwitch;
    private TextView batchFreqTip;
    private TextView batchHint;

    private Switch unblockSwitch;
    private TextView unblockFreqTip;
    private TextView unblockHint;

    /** 代码回写开关状态时置位，避免触发监听造成递归 */
    private boolean settingSwitch = false;
    private boolean tidInitialized = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 24, 30, 30);

        TextView title = new TextView(this);
        title.setText("Shizuku 增强");
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 10, 0, 4);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("通过 Shizuku 调用系统卸载阻止接口，阻止/解除应用被卸载");
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, 12);
        root.addView(tip);

        TextView notice = new TextView(this);
        notice.setText(
            "使用提示：\n"
            + "1. TID 默认不需要动。如果不小心改了，把设备的 Android 版本告诉 AI，让 AI 告诉你怎么填。\n"
            + "2. 设备必须有设备所有者（Device Owner），防卸载命令才会真正生效。"
            + "可以选择星特安全、Dhizuku 或其他支持该提权操作的应用激活。"
            + "不清楚是什么、为什么激活不了，问 AI，推荐 DeepSeek 而不是豆包。");
        notice.setTextColor(getSecondaryTextColor());
        notice.setTextSize(11);
        notice.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(14));
        root.addView(notice);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextColor(getSecondaryTextColor());
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(16, 4, 16, 12);
        root.addView(statusView);

        TextView tidLabel = new TextView(this);
        tidLabel.setText("事务号 TID（默认不动，误改后告诉 AI 你的 Android 版本）");
        tidLabel.setTextSize(12);
        tidLabel.setTextColor(getSecondaryTextColor());
        tidLabel.setPadding(dpToPx(4), 0, dpToPx(4), dpToPx(4));
        root.addView(tidLabel);

        LinearLayout tidRow = new LinearLayout(this);
        tidRow.setOrientation(LinearLayout.HORIZONTAL);
        tidRow.setGravity(Gravity.CENTER_VERTICAL);

        tidInput = new EditText(this);
        tidInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        tidInput.setTextSize(14);
        tidInput.setTextColor(getTextColor());
        tidInput.setHint("默认按 SDK 推导");
        tidRow.addView(tidInput, new LinearLayout.LayoutParams(0, -2, 1));

        Button applyTidBtn = new Button(this);
        applyTidBtn.setText("应用");
        applyTidBtn.setTextSize(13);
        applyTidBtn.setAllCaps(false);
        applyTidBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String s = tidInput.getText().toString().trim();
                if (!s.isEmpty()) {
                    try {
                        int t = Integer.parseInt(s);
                        if (t <= 0) throw new NumberFormatException();
                        BlockUninstallManager.setTidOverride(ShizukuActivity.this, t);
                    } catch (Exception e) {
                        Toast.makeText(ShizukuActivity.this, "事务号必须为正整数", Toast.LENGTH_SHORT).show();
                        return;
                    }
                } else {
                    BlockUninstallManager.setTidOverride(ShizukuActivity.this, 0);
                }
                refresh();
            }
        });
        tidRow.addView(applyTidBtn);
        root.addView(tidRow);

        // ---- 自身 ----
        blockSwitch = new Switch(this);
        blockSwitch.setText("阻止卸载星特安全");
        blockSwitch.setTextSize(14);
        blockSwitch.setTextColor(getTextColor());
        blockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (settingSwitch) return;
                applyBlock(isChecked);
            }
        });
        root.addView(blockSwitch);

        blockHint = new TextView(this);
        blockHint.setTextSize(11);
        blockHint.setTextColor(getSecondaryTextColor());
        blockHint.setPadding(dpToPx(12), 4, dpToPx(12), 12);
        root.addView(blockHint);

        // ---- 阻止卸载 ----
        batchSwitch = new Switch(this);
        batchSwitch.setText("阻止卸载");
        batchSwitch.setTextSize(14);
        batchSwitch.setTextColor(getTextColor());
        batchSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (settingSwitch) return;
                BlockUninstallManager.setBatchEnabled(ShizukuActivity.this, isChecked);
                // 互斥：另一项可能被自动关闭，回读开关状态
                syncSwitches();
                if (isChecked) runBatchNow(true);
                refresh();
            }
        });
        root.addView(batchSwitch);

        batchHint = new TextView(this);
        batchHint.setText("阻止所有应用被卸载，防止恶意应用卸载游戏。");
        batchHint.setTextColor(getHintTextColor());
        batchHint.setTextSize(11);
        batchHint.setPadding(dpToPx(12), 4, dpToPx(12), 4);
        root.addView(batchHint);

        batchFreqTip = new TextView(this);
        batchFreqTip.setTextSize(11);
        batchFreqTip.setTextColor(getHintTextColor());
        batchFreqTip.setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(2));
        root.addView(batchFreqTip);

        SeekBar batchFreqSeek = new SeekBar(this);
        batchFreqSeek.setMax(BlockUninstallManager.MAX_INTERVAL_TICKS - BlockUninstallManager.MIN_INTERVAL_TICKS);
        batchFreqSeek.setProgress(BlockUninstallManager.getBatchIntervalTicks(this) - BlockUninstallManager.MIN_INTERVAL_TICKS);
        batchFreqSeek.setPadding(dpToPx(12), 0, dpToPx(12), dpToPx(6));
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
        root.addView(batchFreqSeek);

        // ---- 阻止阻止卸载 ----
        unblockSwitch = new Switch(this);
        unblockSwitch.setText("阻止阻止卸载");
        unblockSwitch.setTextSize(14);
        unblockSwitch.setTextColor(getTextColor());
        unblockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (settingSwitch) return;
                BlockUninstallManager.setUnblockBatchEnabled(ShizukuActivity.this, isChecked);
                syncSwitches();
                if (isChecked) runBatchNow(false);
                refresh();
            }
        });
        root.addView(unblockSwitch);

        unblockHint = new TextView(this);
        unblockHint.setText("阻止一些应用拦截卸载（不含无障碍自动点击拦截），避免恶意应用无法被卸载。与上方效果相反，不能同时开启。");
        unblockHint.setTextColor(getHintTextColor());
        unblockHint.setTextSize(11);
        unblockHint.setPadding(dpToPx(12), 4, dpToPx(12), 4);
        root.addView(unblockHint);

        unblockFreqTip = new TextView(this);
        unblockFreqTip.setTextSize(11);
        unblockFreqTip.setTextColor(getHintTextColor());
        unblockFreqTip.setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(2));
        root.addView(unblockFreqTip);

        SeekBar unblockFreqSeek = new SeekBar(this);
        unblockFreqSeek.setMax(BlockUninstallManager.MAX_INTERVAL_TICKS - BlockUninstallManager.MIN_INTERVAL_TICKS);
        unblockFreqSeek.setProgress(BlockUninstallManager.getUnblockBatchIntervalTicks(this) - BlockUninstallManager.MIN_INTERVAL_TICKS);
        unblockFreqSeek.setPadding(dpToPx(12), 0, dpToPx(12), dpToPx(6));
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
        root.addView(unblockFreqSeek);

        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新状态");
        refreshBtn.setTextSize(14);
        refreshBtn.setAllCaps(false);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { refresh(); }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = 16;
        root.addView(refreshBtn, rlp);

        Button backBtn = new Button(this);
        backBtn.setText("完成");
        backBtn.setTextSize(14);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-1, -2);
        backLp.topMargin = 12;
        root.addView(backBtn, backLp);

        scrollView.addView(root);
        setContentView(scrollView);
        // 阻止 EditText 自动获取焦点弹出键盘（用户点一下才会唤起）
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
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
        statusView.setTextColor(getSecondaryTextColor());
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
            Toast.makeText(this, "事务号无效", Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(ShizukuActivity.this,
                                    blocked ? "已阻止卸载星特安全" : "已允许卸载星特安全",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(ShizukuActivity.this,
                                    "执行未成功（检查事务号/权限/是否已激活设备所有者）",
                                    Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        if (BlockUninstallManager.getTid(this) <= 0) {
            Toast.makeText(this, "事务号无效", Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(ShizukuActivity.this, "执行失败", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(ShizukuActivity.this,
                                    (blocked ? "已阻止 " : "已解除 ") + r[0] + " 个，失败 " + r[1],
                                    Toast.LENGTH_LONG).show();
                        }
                        refresh();
                    }
                });
            }
        }, "shizuku-batch").start();
    }

    private int getTextColor() { return Color.argb(255, 30, 30, 30); }
    private int getSecondaryTextColor() { return Color.argb(150, 90, 90, 90); }
    private int getHintTextColor() { return Color.argb(120, 90, 90, 90); }
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
