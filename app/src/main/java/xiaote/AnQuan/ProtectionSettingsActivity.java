package xiaote.AnQuan;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import xiaote.xtui.XtDialog;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * 主动防护。
 *
 * 病毒拦截、安装管控与各种自动触发方式全部集中在这一页，按 xtui 规范分卡片：
 *   1. 基础防护：病毒拦截 / 安装提醒 / 强制停止 / 管控提示 / 自动检测 /
 *      风险文字识别 / 全屏覆盖拦截 / 无障碍保活（含 auto·force 双模式）
 *   2. 音量防护：防篡改 + 阈值 + 检查频率 + 三种触发方式
 *   3. 选择操作：多选安全操作（强制停止 / 关无障碍 / 冻结 / 唤起列表 / 关锁屏密码）
 *   4. 阻止卸载：阻止卸载 / 阻止阻止卸载（互斥）
 *   5. 自定义病毒包名
 *
 * 三种触发方式（音量超阈值 / 连按十下音量- / 时间被回拨）共用同一套
 * SafeActionManager 掩码。
 */
public class ProtectionSettingsActivity extends BaseActivity {

    private SharedPreferences prefs;
    /** 代码回写开关状态时置位，避免触发监听造成递归 */
    private boolean settingToggles = false;

    private XtUi.XtSwitch blockOtherSwitch;
    private XtUi.XtSwitch unblockOtherSwitch;
    private TextView blockOtherFreqTip;
    private TextView unblockOtherFreqTip;
    private TextView safeActionSummary;
    private TextView accKeepAliveFreqTip;
    private TextView accKeepAliveModeBtn;
    private TextView timeRollbackTip;
    /** 全屏覆盖拦截力度按钮（标准 / 敏感），onResume 回本页时刷新 */
    private TextView overlayModeBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        // 界面模式分流：ui_mode=h5 时交给 H5 壳，原生界面不构建。默认 native。
        if ("h5".equals(prefs.getString("ui_mode", "native"))) {
            try { startActivity(new android.content.Intent(this, H5ProtectionActivity.class)); } catch (Throwable ignored) {}
            finish();
            return;
        }

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, getString(R.string.protection_title)));
        XtUi.applyStatusBar(this);

        // ==================== 基础防护 ====================
        LinearLayout cardBase = XtUi.card(this);

        addSwitch(cardBase, getString(R.string.switch_block_virus), "block_virus", true);
        cardBase.addView(XtUi.tip(this, getString(R.string.block_virus_tip)));

        addSwitch(cardBase, getString(R.string.switch_notify_install), "notify_install", true);
        addSwitch(cardBase, getString(R.string.switch_force_stop_virus), "force_stop_virus", true);
        addSwitch(cardBase, getString(R.string.switch_manage_install), "manage_install", true);

        addSwitch(cardBase, getString(R.string.auto_scan_install), "auto_scan_install", false);
        cardBase.addView(XtUi.tip(this, getString(R.string.auto_scan_tip)));

        addSwitch(cardBase, getString(R.string.smart_risk_text), "smart_risk_text_notify", true);
        cardBase.addView(XtUi.tip(this, getString(R.string.smart_risk_tip)));

        addSwitch(cardBase, getString(R.string.switch_block_overlay), "block_overlay", true);
        cardBase.addView(XtUi.tip(this, getString(R.string.tip_block_overlay)));

        // 拦截力度：标准（连续命中才拦，抗误判）/ 敏感（出现即拦，每秒检查一次）
        overlayModeBtn = XtUi.button(this, "", new View.OnClickListener() {
            @Override public void onClick(View v) { showOverlayModeDialog(); }
        });
        refreshOverlayModeBtn();
        LinearLayout.LayoutParams omLp = new LinearLayout.LayoutParams(-1, -2);
        omLp.topMargin = XtUi.dp(this, 6);
        cardBase.addView(overlayModeBtn, omLp);

        // 无障碍保活：auto 检测到掉线才拉活；force 每轮无条件写回
        addSwitch(cardBase, "无障碍保活", XTSafeCoreService.KEY_ACC_KEEP_ALIVE, false);

        accKeepAliveModeBtn = XtUi.button(this, "", new View.OnClickListener() {
            @Override public void onClick(View v) { showAccKeepAliveModeDialog(); }
        });
        refreshAccKeepAliveModeBtn();
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(-1, -2);
        modeLp.topMargin = XtUi.dp(this, 6);
        cardBase.addView(accKeepAliveModeBtn, modeLp);

        accKeepAliveFreqTip = new TextView(this);
        accKeepAliveFreqTip.setTextSize(12);
        accKeepAliveFreqTip.setTextColor(XtUi.colorMuted(this));
        accKeepAliveFreqTip.setPadding(0, XtUi.dp(this, 8), 0, XtUi.dp(this, 4));
        accKeepAliveFreqTip.setText("保活检查频率：" + BlockUninstallManager.formatInterval(getAccKeepAliveTicks()));
        cardBase.addView(accKeepAliveFreqTip);

        SeekBar accKeepAliveSeek = XtUi.slider(this,
                XTSafeCoreService.MAX_ACC_KEEP_ALIVE_TICKS - XTSafeCoreService.MIN_ACC_KEEP_ALIVE_TICKS,
                getAccKeepAliveTicks() - XTSafeCoreService.MIN_ACC_KEEP_ALIVE_TICKS);
        // 左右必须保留 12dp：XtUi.slider 预留的空间不能被覆盖，否则进度 0%/100% 时圆点会被裁掉
        accKeepAliveSeek.setPadding(XtUi.dp(this, 12), 0, XtUi.dp(this, 12), XtUi.dp(this, 6));
        accKeepAliveSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = progress + XTSafeCoreService.MIN_ACC_KEEP_ALIVE_TICKS;
                prefs.edit().putInt(XTSafeCoreService.KEY_ACC_KEEP_ALIVE_TICKS, v).apply();
                if (accKeepAliveFreqTip != null) {
                    accKeepAliveFreqTip.setText("保活检查频率：" + BlockUninstallManager.formatInterval(v));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cardBase.addView(accKeepAliveSeek);

        cardBase.addView(XtUi.tip(this,
                "自动恢复：按上面频率检测，仅在无障碍掉线时写回服务列表把它拉活；"
                        + "强制保活：不做检测，每轮都无条件写回开启。最低 0.1 秒一次。"));

        root.addView(cardBase);

        // ==================== 音量防护 ====================
        LinearLayout cardVolume = XtUi.card(this);

        addSwitch(cardVolume, getString(R.string.volume_protect), "volume_protect", false);
        final TextView volumeTip = XtUi.tip(this,
                getString(R.string.volume_protect_tip, prefs.getInt("volume_threshold", 80)));
        cardVolume.addView(volumeTip);

        SeekBar volumeSeek = XtUi.slider(this, 100, prefs.getInt("volume_threshold", 80));
        volumeSeek.setPadding(XtUi.dp(this, 12), 0, XtUi.dp(this, 12), XtUi.dp(this, 6));
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
        cardVolume.addView(volumeSeek);

        final TextView volumeFreqTip = new TextView(this);
        int freqRaw = prefs.getInt("volume_check_interval", 1);
        float freqSec = freqRaw / 10.0f;
        volumeFreqTip.setTextSize(12);
        volumeFreqTip.setTextColor(XtUi.colorMuted(this));
        volumeFreqTip.setPadding(0, XtUi.dp(this, 8), 0, XtUi.dp(this, 4));
        volumeFreqTip.setText(getString(R.string.volume_freq_tip,
                (freqSec == (int) freqSec ? String.valueOf((int) freqSec) : String.valueOf(freqSec))));
        cardVolume.addView(volumeFreqTip);

        SeekBar volumeFreqSeek = XtUi.slider(this, 599, freqRaw - 1);
        volumeFreqSeek.setPadding(XtUi.dp(this, 12), 0, XtUi.dp(this, 12), XtUi.dp(this, 6));
        volumeFreqSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int val = progress + 1;
                    prefs.edit().putInt("volume_check_interval", val).apply();
                    float sec = val / 10.0f;
                    volumeFreqTip.setText(getString(R.string.volume_freq_tip,
                            (sec == (int) sec ? String.valueOf((int) sec) : String.valueOf(sec))));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cardVolume.addView(volumeFreqSeek);

        addSwitch(cardVolume, "音量超阈值执行安全操作", "volume_threshold_action", false);
        addSwitch(cardVolume, "按十下音量-触发安全操作", "volume_key_action", true);
        cardVolume.addView(XtUi.tip(this,
                "1 秒内连按十下音量减键即可触发；与音量超阈值、时间被回拨共用同一套「选择操作」。"));

        // 时间被回拨时触发安全操作
        addSwitch(cardVolume, "时间被回拨时触发安全操作", XTSafeCoreService.KEY_TIME_ROLLBACK_ACTION, false);
        timeRollbackTip = new TextView(this);
        timeRollbackTip.setTextSize(12);
        timeRollbackTip.setTextColor(XtUi.colorMuted(this));
        timeRollbackTip.setPadding(0, XtUi.dp(this, 8), 0, XtUi.dp(this, 4));
        timeRollbackTip.setText(timeRollbackTipText(getTimeRollbackMinutes()));
        cardVolume.addView(timeRollbackTip);

        SeekBar timeRollbackSeek = XtUi.slider(this,
                XTSafeCoreService.MAX_TIME_ROLLBACK_MINUTES - XTSafeCoreService.MIN_TIME_ROLLBACK_MINUTES,
                getTimeRollbackMinutes() - XTSafeCoreService.MIN_TIME_ROLLBACK_MINUTES);
        timeRollbackSeek.setPadding(XtUi.dp(this, 12), 0, XtUi.dp(this, 12), XtUi.dp(this, 6));
        timeRollbackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = progress + XTSafeCoreService.MIN_TIME_ROLLBACK_MINUTES;
                prefs.edit().putInt(XTSafeCoreService.KEY_TIME_ROLLBACK_MINUTES, v).apply();
                if (timeRollbackTip != null) timeRollbackTip.setText(timeRollbackTipText(v));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cardVolume.addView(timeRollbackSeek);

        cardVolume.addView(XtUi.tip(this,
                "系统时间被往前调（回拨）超过上面阈值时，执行「选择操作」里勾选的安全操作。"
                        + "例如阈值设为 3 天，时间被调回 3 天前就会触发；只往回退触发，正常走时不触发。"));

        root.addView(cardVolume);

        // ==================== 选择操作 ====================
        LinearLayout cardAction = XtUi.card(this);

        cardAction.addView(XtUi.tip(this, "上面三种触发方式共用这一套多选操作。"));

        safeActionSummary = new TextView(this);
        safeActionSummary.setTextSize(13);
        safeActionSummary.setTextColor(XtUi.primary);
        safeActionSummary.setPadding(0, XtUi.dp(this, 8), 0, XtUi.dp(this, 4));
        safeActionSummary.setText("已选择：" + SafeActionManager.describe(this));
        cardAction.addView(safeActionSummary);

        XtUi.addButton(cardAction, this, "选择操作", new View.OnClickListener() {
            @Override public void onClick(View v) { showSafeActionDialog(); }
        });

        root.addView(cardAction);

        // ==================== 阻止卸载 ====================
        LinearLayout cardBlock = XtUi.card(this);

        blockOtherSwitch = XtUi.switchView(this, BlockUninstallManager.isBatchEnabled(this));
        blockOtherSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(XtUi.XtSwitch v, boolean isChecked) {
                if (settingToggles) return;
                BlockUninstallManager.setBatchEnabled(ProtectionSettingsActivity.this, isChecked);
                syncToggles();
                if (isChecked) {
                    new Thread(new Runnable() {
                        @Override public void run() {
                            final int[] r = BlockUninstallManager.runBatch(ProtectionSettingsActivity.this, true);
                            BlockUninstallManager.appendLog(ProtectionSettingsActivity.this,
                                    "手动阻止卸载：" + (r == null ? "执行失败" : ("成功 " + r[0] + " 个，失败 " + r[1] + " 个")));
                        }
                    }, "block-other-batch").start();
                }
            }
        });
        cardBlock.addView(XtUi.row(this, "阻止卸载", blockOtherSwitch));
        cardBlock.addView(XtUi.tip(this, "阻止所有应用被卸载，防止恶意应用卸载游戏。与下方互斥。"));

        blockOtherFreqTip = new TextView(this);
        blockOtherFreqTip.setTextSize(12);
        blockOtherFreqTip.setTextColor(XtUi.colorMuted(this));
        blockOtherFreqTip.setPadding(0, XtUi.dp(this, 8), 0, XtUi.dp(this, 4));
        blockOtherFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(BlockUninstallManager.getBatchIntervalTicks(this)));
        cardBlock.addView(blockOtherFreqTip);

        SeekBar blockOtherFreqSeek = XtUi.slider(this,
                BlockUninstallManager.MAX_INTERVAL_TICKS - BlockUninstallManager.MIN_INTERVAL_TICKS,
                BlockUninstallManager.getBatchIntervalTicks(this) - BlockUninstallManager.MIN_INTERVAL_TICKS);
        blockOtherFreqSeek.setPadding(XtUi.dp(this, 12), 0, XtUi.dp(this, 12), XtUi.dp(this, 8));
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
        cardBlock.addView(blockOtherFreqSeek);

        unblockOtherSwitch = XtUi.switchView(this, BlockUninstallManager.isUnblockBatchEnabled(this));
        unblockOtherSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(XtUi.XtSwitch v, boolean isChecked) {
                if (settingToggles) return;
                BlockUninstallManager.setUnblockBatchEnabled(ProtectionSettingsActivity.this, isChecked);
                syncToggles();
                if (isChecked) {
                    new Thread(new Runnable() {
                        @Override public void run() {
                            final int[] r = BlockUninstallManager.runBatch(ProtectionSettingsActivity.this, false);
                            BlockUninstallManager.appendLog(ProtectionSettingsActivity.this,
                                    "手动阻止阻止卸载：" + (r == null ? "执行失败" : ("成功 " + r[0] + " 个，失败 " + r[1] + " 个")));
                        }
                    }, "unblock-other-batch").start();
                }
            }
        });
        cardBlock.addView(XtUi.row(this, "阻止阻止卸载", unblockOtherSwitch));
        cardBlock.addView(XtUi.tip(this,
                "阻止一些应用拦截卸载（不含无障碍自动点击拦截），避免恶意应用无法被卸载。与上方效果相反，不能同时开启。"));

        unblockOtherFreqTip = new TextView(this);
        unblockOtherFreqTip.setTextSize(12);
        unblockOtherFreqTip.setTextColor(XtUi.colorMuted(this));
        unblockOtherFreqTip.setPadding(0, XtUi.dp(this, 8), 0, XtUi.dp(this, 4));
        unblockOtherFreqTip.setText("检查频率：" + BlockUninstallManager.formatInterval(BlockUninstallManager.getUnblockBatchIntervalTicks(this)));
        cardBlock.addView(unblockOtherFreqTip);

        SeekBar unblockOtherFreqSeek = XtUi.slider(this,
                BlockUninstallManager.MAX_INTERVAL_TICKS - BlockUninstallManager.MIN_INTERVAL_TICKS,
                BlockUninstallManager.getUnblockBatchIntervalTicks(this) - BlockUninstallManager.MIN_INTERVAL_TICKS);
        unblockOtherFreqSeek.setPadding(XtUi.dp(this, 12), 0, XtUi.dp(this, 12), XtUi.dp(this, 6));
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
        cardBlock.addView(unblockOtherFreqSeek);

        root.addView(cardBlock);

        // ==================== 自定义病毒包名 ====================
        LinearLayout cardVirus = XtUi.card(this);

        final android.widget.EditText virusInput = XtDialog.input(this);
        virusInput.setText(VirusPackages.getCustomVirusPackages(this));
        virusInput.setSingleLine(false);
        virusInput.setMinLines(2);
        virusInput.setMaxLines(4);
        virusInput.setTextSize(13);
        cardVirus.addView(virusInput);

        XtUi.addButton(cardVirus, this, getString(R.string.save_virus), new View.OnClickListener() {
            @Override public void onClick(View v) {
                VirusPackages.saveCustomVirusPackages(ProtectionSettingsActivity.this,
                        virusInput.getText().toString());
                XtToast.show(ProtectionSettingsActivity.this, getString(R.string.virus_saved));
            }
        });

        root.addView(cardVirus);

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
        syncToggles();
        if (accKeepAliveFreqTip != null) {
            accKeepAliveFreqTip.setText("保活检查频率：" + BlockUninstallManager.formatInterval(getAccKeepAliveTicks()));
        }
        refreshAccKeepAliveModeBtn();
        refreshOverlayModeBtn();
        if (timeRollbackTip != null) {
            timeRollbackTip.setText(timeRollbackTipText(getTimeRollbackMinutes()));
        }
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

    // ==================== 无障碍保活模式 ====================

    private String accKeepAliveMode() {
        try {
            return prefs.getString(XTSafeCoreService.KEY_ACC_KEEP_ALIVE_MODE,
                    XTSafeCoreService.ACC_KEEP_ALIVE_MODE_AUTO);
        } catch (Throwable t) {
            return XTSafeCoreService.ACC_KEEP_ALIVE_MODE_AUTO;
        }
    }

    private void refreshAccKeepAliveModeBtn() {
        if (accKeepAliveModeBtn == null) return;
        boolean force = XTSafeCoreService.ACC_KEEP_ALIVE_MODE_FORCE.equals(accKeepAliveMode());
        accKeepAliveModeBtn.setText(force ? "保活模式：强制保活" : "保活模式：自动恢复");
    }

    private void showAccKeepAliveModeDialog() {
        final String[] items = {
                "自动恢复（检测到掉线才重新开启）",
                "强制保活（每轮无条件写回开启）"
        };
        final String[] values = {
                XTSafeCoreService.ACC_KEEP_ALIVE_MODE_AUTO,
                XTSafeCoreService.ACC_KEEP_ALIVE_MODE_FORCE
        };
        int checked = XTSafeCoreService.ACC_KEEP_ALIVE_MODE_FORCE.equals(accKeepAliveMode()) ? 1 : 0;
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("无障碍保活模式")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putString(XTSafeCoreService.KEY_ACC_KEEP_ALIVE_MODE, values[which]).apply();
                        refreshAccKeepAliveModeBtn();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    // ==================== 全屏覆盖拦截力度 ====================

    /** 当前拦截力度：standard 标准 / sensitive 敏感 */
    private String overlayMode() {
        try {
            return prefs.getString(XTSafeMainService.KEY_OVERLAY_BLOCK_MODE,
                    XTSafeMainService.OVERLAY_MODE_STANDARD);
        } catch (Throwable t) {
            return XTSafeMainService.OVERLAY_MODE_STANDARD;
        }
    }

    private void refreshOverlayModeBtn() {
        if (overlayModeBtn == null) return;
        boolean sensitive = XTSafeMainService.OVERLAY_MODE_SENSITIVE.equals(overlayMode());
        overlayModeBtn.setText(sensitive ? "拦截力度：敏感" : "拦截力度：标准");
    }

    /** 拦截力度单选弹窗 */
    private void showOverlayModeDialog() {
        final String[] items = {
                "标准（连续命中才拦截，抗误判）",
                "敏感（出现即拦截，每秒检查一次）"
        };
        final String[] values = {
                XTSafeMainService.OVERLAY_MODE_STANDARD,
                XTSafeMainService.OVERLAY_MODE_SENSITIVE
        };
        int checked = XTSafeMainService.OVERLAY_MODE_SENSITIVE.equals(overlayMode()) ? 1 : 0;
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("全屏覆盖拦截力度")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putString(XTSafeMainService.KEY_OVERLAY_BLOCK_MODE,
                                values[which]).apply();
                        refreshOverlayModeBtn();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    // ==================== 时间回拨 ====================

    private int getTimeRollbackMinutes() {
        int v = prefs.getInt(XTSafeCoreService.KEY_TIME_ROLLBACK_MINUTES,
                XTSafeCoreService.DEFAULT_TIME_ROLLBACK_MINUTES);
        if (v < XTSafeCoreService.MIN_TIME_ROLLBACK_MINUTES) v = XTSafeCoreService.MIN_TIME_ROLLBACK_MINUTES;
        if (v > XTSafeCoreService.MAX_TIME_ROLLBACK_MINUTES) v = XTSafeCoreService.MAX_TIME_ROLLBACK_MINUTES;
        return v;
    }

    private String timeRollbackTipText(int minutes) {
        return "回拨阈值：" + XTSafeCoreService.formatRollbackSpan((long) minutes * 60000L);
    }

    // ==================== 无障碍保活频率 ====================

    private int getAccKeepAliveTicks() {
        int v = prefs.getInt(XTSafeCoreService.KEY_ACC_KEEP_ALIVE_TICKS,
                XTSafeCoreService.DEFAULT_ACC_KEEP_ALIVE_TICKS);
        if (v < XTSafeCoreService.MIN_ACC_KEEP_ALIVE_TICKS) v = XTSafeCoreService.MIN_ACC_KEEP_ALIVE_TICKS;
        if (v > XTSafeCoreService.MAX_ACC_KEEP_ALIVE_TICKS) v = XTSafeCoreService.MAX_ACC_KEEP_ALIVE_TICKS;
        return v;
    }

    // ==================== 选择操作 ====================

    private void showSafeActionDialog() {
        final String[] names = SafeActionManager.ACTION_NAMES;
        final int[] values = SafeActionManager.ACTION_VALUES;
        int mask = SafeActionManager.getMask(this);
        final boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++) checked[i] = (mask & values[i]) != 0;

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("选择安全操作（可多选）")
                .setMultiChoiceItems(names, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int m = 0;
                        for (int i = 0; i < names.length; i++) if (checked[i]) m |= values[i];
                        SafeActionManager.setMask(ProtectionSettingsActivity.this, m);
                        if (safeActionSummary != null) {
                            safeActionSummary.setText("已选择：" + SafeActionManager.describe(ProtectionSettingsActivity.this));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    // ==================== 开关 ====================

    /** 开关行：左标签 + 右 XtSwitch，写 dot_config */
    private void addSwitch(LinearLayout root, String text, final String key, boolean def) {
        final XtUi.XtSwitch sw = XtUi.switchView(this, prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(XtUi.XtSwitch v, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
            }
        });
        root.addView(XtUi.row(this, text, sw));
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
