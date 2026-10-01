package xiaote.AnQuan;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主动防护：禁止安装病毒、安装通知提醒、强制停止病毒、安装管控提示、
 * 音量安全操作、阻止卸载 / 阻止阻止卸载、自定义病毒包名。
 *
 * 「阻止卸载」与「阻止阻止卸载」效果相反，二者互斥，同时只会生效一个。
 * 检查频率以 100 毫秒为一个 tick，最低 0.1 秒执行一次。
 *
 * 「音量超阈值执行安全操作」「按十下音量-触发安全操作」共享同一套多选操作，
 * 具体操作由「选择操作」按钮弹出的多选框配置。
 */
public class ProtectionSettingsActivity extends BaseActivity {

    private SharedPreferences prefs;
    /** 代码回写开关状态时置位，避免触发监听造成递归 */
    private boolean settingToggles = false;

    private android.widget.Switch blockOtherSwitch;
    private android.widget.Switch unblockOtherSwitch;
    private TextView blockOtherFreqTip;
    private TextView unblockOtherFreqTip;
    private TextView safeActionSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 24, 30, 30);

        TextView title = new TextView(this);
        title.setText(getString(R.string.protection_title));
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 10, 0, 4);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText(R.string.protection_tip);
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, 20);
        root.addView(tip);

        // 1. 禁止安装病毒
        addSwitch(root, getString(R.string.switch_block_virus), "block_virus", true);
        TextView tip1 = new TextView(this);
        tip1.setText(R.string.block_virus_tip);
        tip1.setTextColor(getHintTextColor());
        tip1.setTextSize(11);
        tip1.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(tip1);

        // 2. 安装病毒通知提醒
        addSwitch(root, getString(R.string.switch_notify_install), "notify_install", true);

        // 3. 唤起列表时强制停止病毒
        addSwitch(root, getString(R.string.switch_force_stop_virus), "force_stop_virus", true);

        // 4. 新安装应用管控提示
        addSwitch(root, getString(R.string.switch_manage_install), "manage_install", true);

        // 4.5 自动检测新安装应用
        addSwitch(root, getString(R.string.auto_scan_install), "auto_scan_install", false);
        TextView tipScan = new TextView(this);
        tipScan.setText(R.string.auto_scan_tip);
        tipScan.setTextColor(getHintTextColor());
        tipScan.setTextSize(11);
        tipScan.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(tipScan);

        // 5. 智能识别风险文字并提示
        addSwitch(root, getString(R.string.smart_risk_text), "smart_risk_text_notify", true);
        TextView tipRisk = new TextView(this);
        tipRisk.setText(R.string.smart_risk_tip);
        tipRisk.setTextColor(getHintTextColor());
        tipRisk.setTextSize(11);
        tipRisk.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(tipRisk);

        // 防音量恶意修改
        addSwitch(root, getString(R.string.volume_protect), "volume_protect", false);
        final TextView volumeTip = new TextView(this);
        volumeTip.setText(getString(R.string.volume_protect_tip, prefs.getInt("volume_threshold", 80)));
        volumeTip.setTextColor(getHintTextColor());
        volumeTip.setTextSize(11);
        volumeTip.setPadding(dpToPx(12), 4, dpToPx(12), 4);
        root.addView(volumeTip);

        SeekBar volumeSeek = new SeekBar(this);
        volumeSeek.setMax(100);
        volumeSeek.setProgress(prefs.getInt("volume_threshold", 80));
        volumeSeek.setPadding(dpToPx(12), 0, dpToPx(12), 10);
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    prefs.edit().putInt("volume_threshold", progress).apply();
                    volumeTip.setText(getString(R.string.volume_protect_tip, progress));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(volumeSeek);

        // 音量检查频率
        final TextView volumeFreqTip = new TextView(this);
        int freqRaw = prefs.getInt("volume_check_interval", 1); // 默认0.1秒
        float freqSec = freqRaw / 10.0f;
        volumeFreqTip.setText(getString(R.string.volume_freq_tip, (freqSec == (int) freqSec ? String.valueOf((int) freqSec) : String.valueOf(freqSec))));
        volumeFreqTip.setTextColor(getHintTextColor());
        volumeFreqTip.setTextSize(11);
        volumeFreqTip.setPadding(dpToPx(12), 8, dpToPx(12), 4);
        root.addView(volumeFreqTip);

        SeekBar volumeFreqSeek = new SeekBar(this);
        volumeFreqSeek.setMax(599); // 0.1 ~ 60.0 秒，步进0.1
        volumeFreqSeek.setProgress(freqRaw - 1);
        volumeFreqSeek.setPadding(dpToPx(12), 0, dpToPx(12), 10);
        volumeFreqSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int val = progress + 1;
                    prefs.edit().putInt("volume_check_interval", val).apply();
                    float sec = val / 10.0f;
                    volumeFreqTip.setText(getString(R.string.volume_freq_tip, (sec == (int) sec ? String.valueOf((int) sec) : String.valueOf(sec))));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(volumeFreqSeek);

        // 音量超阈值执行安全操作
        addSwitch(root, "音量超阈值执行安全操作", "volume_threshold_action", false);
        TextView tipThresholdAction = new TextView(this);
        tipThresholdAction.setText("音量达到上方阈值时，执行下面「选择操作」里勾选的安全操作。");
        tipThresholdAction.setTextColor(getHintTextColor());
        tipThresholdAction.setTextSize(11);
        tipThresholdAction.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(tipThresholdAction);

        // 按十下音量-触发安全操作
        addSwitch(root, "按十下音量-触发安全操作", "volume_key_action", true);
        TextView tipKeyAction = new TextView(this);
        tipKeyAction.setText("1 秒内连按十下音量减键，执行下面「选择操作」里勾选的安全操作。");
        tipKeyAction.setTextColor(getHintTextColor());
        tipKeyAction.setTextSize(11);
        tipKeyAction.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(tipKeyAction);

        // 选择操作
        TextView safeActionLabel = new TextView(this);
        safeActionLabel.setText("选择操作（音量触发时执行，可多选）");
        safeActionLabel.setTextColor(getTextColor());
        safeActionLabel.setTextSize(13);
        safeActionLabel.setPadding(0, 8, 0, 4);
        root.addView(safeActionLabel);

        safeActionSummary = new TextView(this);
        safeActionSummary.setTextColor(getHintTextColor());
        safeActionSummary.setTextSize(11);
        safeActionSummary.setPadding(dpToPx(12), 0, dpToPx(12), 4);
        safeActionSummary.setText("已选择：" + SafeActionManager.describe(this));
        root.addView(safeActionSummary);

        Button safeActionBtn = new Button(this);
        safeActionBtn.setText("选择操作");
        safeActionBtn.setTextSize(14);
        safeActionBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSafeActionDialog(); }
        });
        root.addView(safeActionBtn);

        // 自动拦截全屏覆盖应用
        addSwitch(root, getString(R.string.switch_block_overlay), "block_overlay", true);
        TextView tipBlockOverlay = new TextView(this);
        tipBlockOverlay.setText(R.string.tip_block_overlay);
        tipBlockOverlay.setTextColor(getHintTextColor());
        tipBlockOverlay.setTextSize(11);
        tipBlockOverlay.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(tipBlockOverlay);

        // ==================== 阻止卸载 ====================
        blockOtherSwitch = new android.widget.Switch(this);
        unblockOtherSwitch = new android.widget.Switch(this);

        blockOtherSwitch.setText("阻止卸载");
        blockOtherSwitch.setTextSize(13);
        blockOtherSwitch.setTextColor(getTextColor());
        blockOtherSwitch.setChecked(BlockUninstallManager.isBatchEnabled(this));
        blockOtherSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (settingToggles) return;
                BlockUninstallManager.setBatchEnabled(ProtectionSettingsActivity.this, isChecked);
                syncToggles();
                if (isChecked) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            final int[] r = BlockUninstallManager.runBatch(ProtectionSettingsActivity.this, true);
                            BlockUninstallManager.appendLog(ProtectionSettingsActivity.this,
                                    "手动阻止卸载：" + (r == null ? "执行失败" : ("成功 " + r[0] + " 个，失败 " + r[1] + " 个")));
                        }
                    }, "block-other-batch").start();
                }
            }
        });
        root.addView(blockOtherSwitch);

        TextView tipBlockOther = new TextView(this);
        tipBlockOther.setText("阻止所有应用被卸载，防止恶意应用卸载游戏。");
        tipBlockOther.setTextColor(getHintTextColor());
        tipBlockOther.setTextSize(11);
        tipBlockOther.setPadding(dpToPx(12), 4, dpToPx(12), 4);
        root.addView(tipBlockOther);

        blockOtherFreqTip = new TextView(this);
        blockOtherFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(BlockUninstallManager.getBatchIntervalTicks(this)));
        blockOtherFreqTip.setTextColor(getHintTextColor());
        blockOtherFreqTip.setTextSize(11);
        blockOtherFreqTip.setPadding(dpToPx(12), 8, dpToPx(12), 4);
        root.addView(blockOtherFreqTip);

        SeekBar blockOtherFreqSeek = new SeekBar(this);
        blockOtherFreqSeek.setMax(BlockUninstallManager.MAX_INTERVAL_TICKS - BlockUninstallManager.MIN_INTERVAL_TICKS);
        blockOtherFreqSeek.setProgress(BlockUninstallManager.getBatchIntervalTicks(this) - BlockUninstallManager.MIN_INTERVAL_TICKS);
        blockOtherFreqSeek.setPadding(dpToPx(12), 0, dpToPx(12), 10);
        blockOtherFreqSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = progress + BlockUninstallManager.MIN_INTERVAL_TICKS;
                BlockUninstallManager.setBatchIntervalTicks(ProtectionSettingsActivity.this, v);
                blockOtherFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(v));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(blockOtherFreqSeek);

        // ==================== 阻止阻止卸载 ====================
        unblockOtherSwitch.setText("阻止阻止卸载");
        unblockOtherSwitch.setTextSize(13);
        unblockOtherSwitch.setTextColor(getTextColor());
        unblockOtherSwitch.setChecked(BlockUninstallManager.isUnblockBatchEnabled(this));
        unblockOtherSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (settingToggles) return;
                BlockUninstallManager.setUnblockBatchEnabled(ProtectionSettingsActivity.this, isChecked);
                syncToggles();
                if (isChecked) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            final int[] r = BlockUninstallManager.runBatch(ProtectionSettingsActivity.this, false);
                            BlockUninstallManager.appendLog(ProtectionSettingsActivity.this,
                                    "手动阻止阻止卸载：" + (r == null ? "执行失败" : ("成功 " + r[0] + " 个，失败 " + r[1] + " 个")));
                        }
                    }, "unblock-other-batch").start();
                }
            }
        });
        root.addView(unblockOtherSwitch);

        TextView tipUnblockOther = new TextView(this);
        tipUnblockOther.setText("阻止一些应用拦截卸载（不含无障碍自动点击拦截），避免恶意应用无法被卸载。与上方效果相反，不能同时开启。");
        tipUnblockOther.setTextColor(getHintTextColor());
        tipUnblockOther.setTextSize(11);
        tipUnblockOther.setPadding(dpToPx(12), 4, dpToPx(12), 4);
        root.addView(tipUnblockOther);

        unblockOtherFreqTip = new TextView(this);
        unblockOtherFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(BlockUninstallManager.getUnblockBatchIntervalTicks(this)));
        unblockOtherFreqTip.setTextColor(getHintTextColor());
        unblockOtherFreqTip.setTextSize(11);
        unblockOtherFreqTip.setPadding(dpToPx(12), 8, dpToPx(12), 4);
        root.addView(unblockOtherFreqTip);

        SeekBar unblockOtherFreqSeek = new SeekBar(this);
        unblockOtherFreqSeek.setMax(BlockUninstallManager.MAX_INTERVAL_TICKS - BlockUninstallManager.MIN_INTERVAL_TICKS);
        unblockOtherFreqSeek.setProgress(BlockUninstallManager.getUnblockBatchIntervalTicks(this) - BlockUninstallManager.MIN_INTERVAL_TICKS);
        unblockOtherFreqSeek.setPadding(dpToPx(12), 0, dpToPx(12), 10);
        unblockOtherFreqSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = progress + BlockUninstallManager.MIN_INTERVAL_TICKS;
                BlockUninstallManager.setUnblockBatchIntervalTicks(ProtectionSettingsActivity.this, v);
                unblockOtherFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(v));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(unblockOtherFreqSeek);

        // 5. 自定义病毒包名
        TextView virusLabel = new TextView(this);
        virusLabel.setText(R.string.custom_virus_label);
        virusLabel.setTextColor(getTextColor());
        virusLabel.setTextSize(13);
        virusLabel.setPadding(0, 16, 0, 8);
        root.addView(virusLabel);

        final android.widget.EditText virusInput = new android.widget.EditText(this);
        virusInput.setText(VirusPackages.getCustomVirusPackages(this));
        virusInput.setSingleLine(false);
        virusInput.setMinLines(2);
        virusInput.setMaxLines(4);
        virusInput.setTextColor(getTextColor());
        virusInput.setTextSize(13);
        root.addView(virusInput);

        Button virusSaveBtn = new Button(this);
        virusSaveBtn.setText(R.string.save_virus);
        virusSaveBtn.setTextSize(14);
        virusSaveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VirusPackages.saveCustomVirusPackages(ProtectionSettingsActivity.this,
                        virusInput.getText().toString());
                Toast.makeText(ProtectionSettingsActivity.this, R.string.virus_saved, Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(virusSaveBtn);

        Button backBtn = new Button(this);
        backBtn.setText(R.string.guide_back);
        backBtn.setTextSize(14);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        root.addView(backBtn);

        scrollView.addView(root);
        setContentView(scrollView);
        // 阻止 EditText 自动获取焦点弹出键盘（用户点一下才会唤起）
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
    }

    /** 弹出多选安全操作对话框 */
    private void showSafeActionDialog() {
        final String[] names = SafeActionManager.ACTION_NAMES;
        final int[] values = SafeActionManager.ACTION_VALUES;
        int mask = SafeActionManager.getMask(this);
        final boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++) checked[i] = (mask & values[i]) != 0;

        new android.app.AlertDialog.Builder(this)
                .setTitle("选择安全操作（可多选）")
                .setMultiChoiceItems(names, checked,
                        new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which, boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        int m = 0;
                        for (int i = 0; i < names.length; i++) if (checked[i]) m |= values[i];
                        SafeActionManager.setMask(ProtectionSettingsActivity.this, m);
                        if (safeActionSummary != null) {
                            safeActionSummary.setText("已选择：" + SafeActionManager.describe(ProtectionSettingsActivity.this));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncToggles();
        if (blockOtherFreqTip != null) {
            blockOtherFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(BlockUninstallManager.getBatchIntervalTicks(this)));
        }
        if (unblockOtherFreqTip != null) {
            unblockOtherFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(BlockUninstallManager.getUnblockBatchIntervalTicks(this)));
        }
        if (safeActionSummary != null) {
            safeActionSummary.setText("已选择：" + SafeActionManager.describe(this));
        }
    }

    /** 两项互斥：把开关勾选状态回读成存储中的真实值（不触发监听） */
    private void syncToggles() {
        settingToggles = true;
        try {
            if (blockOtherSwitch != null) {
                blockOtherSwitch.setChecked(BlockUninstallManager.isBatchEnabled(this));
            }
            if (unblockOtherSwitch != null) {
                unblockOtherSwitch.setChecked(BlockUninstallManager.isUnblockBatchEnabled(this));
            }
        } finally {
            settingToggles = false;
        }
    }

    private void registerBackCallback() {
        getWindow().getDecorView().post(new java.lang.Runnable() {
            @Override
            public void run() {
                try {
                    getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                            new android.window.OnBackInvokedCallback() {
                                @Override
                                public void onBackInvoked() { finish(); }
                            });
                } catch (Exception e) {}
            }
        });
    }

    private void addSwitch(LinearLayout root, String text, final String key, boolean def) {
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setTextOn(getString(R.string.force_on));
        sw.setTextOff(getString(R.string.force_off));
        sw.setText(text);
        sw.setTextSize(13);
        sw.setTextColor(getTextColor());
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
            }
        });
        root.addView(sw);
    }

    private int getTextColor() {
        return Color.argb(255, 30, 30, 30);
    }

    private int getSecondaryTextColor() {
        return Color.argb(150, 90, 90, 90);
    }

    private int getHintTextColor() {
        return Color.argb(120, 90, 90, 90);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
