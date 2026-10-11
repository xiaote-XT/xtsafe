package xiaote.AnQuan.SmallTool.KillAd;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import xiaote.AnQuan.R;
import xiaote.AnQuan.SwipeBackHelper;
import xiaote.xtui.XtDialog;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * 广告拦截（DNS 层）设置页。
 *
 * 只负责 UI 与开关，真正的拦截逻辑在 KillAdVpnService。
 * 开启时弹出系统 VPN 授权弹窗（VpnService.prepare 必须用户手点）。
 *
 * 状态有三种：已停止 / 运行中 / 已暂停。
 * 暂停由通知栏按钮或本页按钮触发，服务不退出、通知保留，可随时恢复。
 *
 * ===== 布局约定 =====
 *
 * 本页刻意不用 xtui 卡片分区，也不加居中主题色小标题。
 * 因为这一页本质是一张纵向表单：几个配置项 + 各自的操作按钮，
 * 套卡片只会让每项被切碎、留白翻倍，反而更难扫读。
 * 现在改为常规表单：左对齐的加粗小标题直接标在对应控件上方，
 * 所有输入框与按钮统一由 root 的内边距对齐，宽度完全一致。
 *
 * 继承 Activity 而非 BaseActivity：开启 VPN 要跳系统授权页，
 * 中途弹密码锁会打断授权流程。
 */
public class KillAdActivity extends android.app.Activity {

    private static final int REQUEST_VPN = 2001;

    private SharedPreferences prefs;
    private XtUi.XtSwitch mainSwitch;
    private TextView statusView;
    private TextView statsView;
    private TextView rulesView;
    private TextView ruleInfoView;
    private TextView logInfoView;
    private TextView pauseBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(KillAdRules.PREFS, MODE_PRIVATE);

        // 界面模式分流：ui_mode=h5 时交给 H5 壳，原生界面不构建。默认 native。
        if ("h5".equals(getSharedPreferences("dot_config", MODE_PRIVATE)
                .getString("ui_mode", "native"))) {
            try { startActivity(new Intent(this, xiaote.AnQuan.H5KillAdActivity.class)); } catch (Throwable ignored) {}
            finish();
            return;
        }

        // 骨架：column 自带 16dp 内边距，本页所有输入框与按钮都以它为基准对齐
        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, getString(R.string.killad_title)));
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this, getString(R.string.killad_tip));
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, XtUi.dp(this, 12));
        root.addView(tip);

        // ============ 开关 ============
        mainSwitch = XtUi.switchView(this, KillAdVpnService.isRunning(this));
        mainSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(XtUi.XtSwitch v, boolean isChecked) {
                if (isChecked) requestStart();
                else stopVpnService();
            }
        });
        root.addView(XtUi.row(this, getString(R.string.killad_switch), mainSwitch));

        // ============ 状态 ============
        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextColor(XtUi.colorMuted(this));
        statusView.setPadding(0, XtUi.dp(this, 6), 0, XtUi.dp(this, 2));
        root.addView(statusView);

        statsView = new TextView(this);
        statsView.setTextSize(12);
        statsView.setTextColor(XtUi.colorMuted(this));
        statsView.setPadding(0, 0, 0, XtUi.dp(this, 8));
        root.addView(statsView);

        pauseBtn = XtUi.button(this, getString(R.string.killad_notify_pause), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!KillAdVpnService.isRunning(KillAdActivity.this)) {
                    XtToast.show(KillAdActivity.this, R.string.killad_status_stopped);
                    return;
                }
                boolean paused = KillAdVpnService.isPaused(KillAdActivity.this);
                Intent i = new Intent(KillAdActivity.this, KillAdVpnService.class);
                i.setAction(paused ? KillAdVpnService.ACTION_RESUME : KillAdVpnService.ACTION_PAUSE);
                try { startService(i); } catch (Exception ignored) {}
                statusView.postDelayed(new Runnable() {
                    @Override
                    public void run() { refresh(); }
                }, 400L);
            }
        });
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, -2);
        pLp.topMargin = XtUi.dp(this, 4);
        root.addView(pauseBtn, pLp);

        TextView pauseTip = XtUi.tip(this, getString(R.string.killad_pause_tip));
        pauseTip.setPadding(0, XtUi.dp(this, 6), 0, 0);
        root.addView(pauseTip);

        rulesView = new TextView(this);
        rulesView.setTextSize(12);
        rulesView.setTextColor(XtUi.colorMuted(this));
        rulesView.setPadding(0, XtUi.dp(this, 10), 0, XtUi.dp(this, 4));
        root.addView(rulesView);

        // ============ 在线规则源 ============
        root.addView(sectionLabel(getString(R.string.killad_rule_source_label)));

        final EditText ruleUrlInput = XtDialog.input(this);
        ruleUrlInput.setSingleLine(true);
        ruleUrlInput.setTextSize(13);
        ruleUrlInput.setText(KillAdRuleUpdater.getRuleUrl(this));
        root.addView(ruleUrlInput);

        LinearLayout ruleBtnRow = new LinearLayout(this);
        ruleBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        ruleBtnRow.setGravity(Gravity.CENTER);

        TextView ruleSave = XtUi.button(this, getString(R.string.killad_rule_source_save),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        KillAdRuleUpdater.setRuleUrl(KillAdActivity.this, ruleUrlInput.getText().toString());
                        ruleUrlInput.setText(KillAdRuleUpdater.getRuleUrl(KillAdActivity.this));
                        XtToast.show(KillAdActivity.this, R.string.killad_rule_source_saved);
                    }
                });
        LinearLayout.LayoutParams rsLp = new LinearLayout.LayoutParams(0, -2, 1f);
        rsLp.rightMargin = XtUi.dp(this, 4);
        rsLp.topMargin = XtUi.dp(this, 8);
        ruleBtnRow.addView(ruleSave, rsLp);

        TextView ruleUpdate = XtUi.button(this, getString(R.string.killad_rule_update),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        XtToast.show(KillAdActivity.this, R.string.killad_rule_updating);
                        if (ruleInfoView != null) ruleInfoView.setText(R.string.killad_rule_updating);
                        KillAdRuleUpdater.updateAsync(KillAdActivity.this, new KillAdRuleUpdater.Callback() {
                            @Override
                            public void onDone(boolean ok, int count, String message) {
                                if (ok) {
                                    XtToast.showLong(KillAdActivity.this,
                                            getString(R.string.killad_rule_update_ok, count));
                                } else {
                                    XtToast.showLong(KillAdActivity.this,
                                            getString(R.string.killad_rule_update_fail, message));
                                }
                                refresh();
                            }
                        });
                    }
                });
        LinearLayout.LayoutParams ruLp = new LinearLayout.LayoutParams(0, -2, 1f);
        ruLp.leftMargin = XtUi.dp(this, 4);
        ruLp.topMargin = XtUi.dp(this, 8);
        ruleBtnRow.addView(ruleUpdate, ruLp);
        root.addView(ruleBtnRow);

        ruleInfoView = new TextView(this);
        ruleInfoView.setTextSize(11);
        ruleInfoView.setTextColor(XtUi.colorMuted(this));
        ruleInfoView.setPadding(0, XtUi.dp(this, 6), 0, 0);
        root.addView(ruleInfoView);

        // ============ 上游 DNS ============
        root.addView(sectionLabel(getString(R.string.killad_upstream_label)));

        final EditText upInput = XtDialog.input(this);
        upInput.setSingleLine(true);
        upInput.setTextSize(13);
        upInput.setText(KillAdRules.getUpstream(this));
        root.addView(upInput);

        XtUi.addButton(root, this, getString(R.string.killad_upstream_save), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String host = upInput.getText().toString().trim();
                if (host.isEmpty()) host = KillAdRules.DEFAULT_UPSTREAM;
                KillAdRules.setUpstream(KillAdActivity.this, host);
                XtToast.show(KillAdActivity.this, R.string.killad_upstream_saved);
            }
        });

        // ============ 自定义拦截域名 ============
        root.addView(sectionLabel(getString(R.string.killad_custom_label)));

        final EditText customInput = XtDialog.input(this);
        customInput.setSingleLine(false);
        customInput.setMinLines(3);
        customInput.setMaxLines(8);
        customInput.setTextSize(13);
        customInput.setText(KillAdRules.getCustomDomains(this));
        root.addView(customInput);

        XtUi.addButton(root, this, getString(R.string.killad_custom_save), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                KillAdRules.setCustomDomains(KillAdActivity.this, customInput.getText().toString());
                XtToast.show(KillAdActivity.this, R.string.killad_custom_saved);
                refresh();
            }
        });

        // ============ 运行日志 ============
        root.addView(sectionLabel(getString(R.string.killad_log_label)));

        logInfoView = new TextView(this);
        logInfoView.setTextSize(11);
        logInfoView.setTextColor(XtUi.colorMuted(this));
        logInfoView.setTextIsSelectable(true);
        root.addView(logInfoView);

        XtUi.addButton(root, this, getString(R.string.killad_log_clear), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                KillAdLogger.clear(KillAdActivity.this);
                XtToast.show(KillAdActivity.this, R.string.killad_log_cleared);
                refresh();
            }
        });

        setContentView(scrollView);

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);

        showNoticeDialogIfNeeded();
    }

    /**
     * 常规表单小标题：左对齐、正常文字色加粗、14sp，直接标在对应控件上方。
     *
     * 不居中、不用主题色、不套卡片 —— 这一页是表单，扫读靠的是分组节奏，
     * 不是视觉块。
     */
    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(XtUi.colorText(this));
        tv.setPadding(0, XtUi.dp(this, 20), 0, XtUi.dp(this, 8));
        return tv;
    }

    /**
     * 首次进入时的提示弹窗。
     *
     * 说明内置广告拦截的局限，并引导有更高需求的用户去下载开源的 BlockAds。
     * 「不再提示」会把标记写入偏好，之后不再弹出。
     */
    private void showNoticeDialogIfNeeded() {
        try {
            if (prefs.getBoolean("killad_notice_dont_show", false)) return;
            android.app.AlertDialog dlg = XtDialog.builder(this, "提示",
                            "星特安全内置的广告拦截功能为简单轻量实现，局限性较大，如果你希望获得完整的广告拦截，建议下载GPL-3.0开源的BlockAds，注意，部分恶意修改者会提供不开源的版本，并将应用改成自己的名字，这个可能有较高风险")
                    .setPositiveButton("确定", null)
                    .setNegativeButton("不再提示", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            prefs.edit().putBoolean("killad_notice_dont_show", true).apply();
                        }
                    })
                    .setNeutralButton("下载BlockAds", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                Intent i = new Intent(Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://github.com/pass-with-high-score/blockads-android/releases"));
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i);
                            } catch (Exception e) {
                                XtToast.show(KillAdActivity.this, "无法打开链接");
                            }
                        }
                    })
                    .create();
            dlg.show();
            XtDialog.tint(dlg);
        } catch (Throwable ignored) {}
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
                } catch (Exception e) {}
            }
        });
    }

    private void refresh() {
        boolean running = KillAdVpnService.isRunning(this);
        boolean paused = running && KillAdVpnService.isPaused(this);

        if (mainSwitch != null && mainSwitch.isChecked() != running) {
            mainSwitch.setChecked(running);
        }

        if (statusView != null) {
            if (!running) {
                statusView.setText(R.string.killad_status_stopped);
                statusView.setTextColor(XtUi.colorMuted(this));
            } else if (paused) {
                statusView.setText(R.string.killad_status_paused);
                statusView.setTextColor(0xFFC8821E);
            } else {
                statusView.setText(R.string.killad_status_running);
                statusView.setTextColor(0xFF3CA050);
            }
        }

        if (pauseBtn != null) {
            pauseBtn.setText(paused ? R.string.killad_notify_resume : R.string.killad_notify_pause);
            pauseBtn.setEnabled(running);
            pauseBtn.setAlpha(running ? 1f : 0.4f);
        }

        if (statsView != null) {
            int blocked = prefs.getInt(KillAdVpnService.KEY_BLOCKED, 0);
            int total = prefs.getInt(KillAdVpnService.KEY_TOTAL, 0);
            statsView.setText(getString(R.string.killad_stats,
                    KillAdVpnService.formatCount(blocked),
                    KillAdVpnService.formatCount(total)));
        }

        if (rulesView != null) {
            int count = 0;
            try { count = KillAdRules.getRules(this).size(); } catch (Exception ignored) {}
            rulesView.setText(getString(R.string.killad_rules_count, count));
        }

        if (ruleInfoView != null) {
            long last = KillAdRuleUpdater.getLastUpdateTime(this);
            // 本地规则文件是旧解析格式生成的，必须重新更新，否则不生效
            boolean fileExists = new java.io.File(getFilesDir(), KillAdRuleUpdater.RULES_FILE).exists();
            if (fileExists && !KillAdRuleUpdater.hasLocalRules(this)) {
                ruleInfoView.setText(R.string.killad_rule_reupdate);
            } else if (last <= 0L) {
                ruleInfoView.setText(R.string.killad_rule_never);
            } else {
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(last));
                ruleInfoView.setText(getString(R.string.killad_rule_last, time, KillAdRuleUpdater.getLastCount(this)));
            }
        }

        if (logInfoView != null) {
            String path = KillAdLogger.logPath(this);
            long size = KillAdLogger.size(this);
            logInfoView.setText(getString(R.string.killad_log_info,
                    path.isEmpty() ? "-" : path,
                    KillAdVpnService.formatBytes(size)));
        }
    }

    /** 开启：先请求系统 VPN 授权，再启动前台服务 */
    private void requestStart() {
        try {
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) {
                startActivityForResult(prepare, REQUEST_VPN);
                return;
            }
        } catch (Exception e) {
            // 部分 ROM 无 VPN 组件，直接尝试启动
        }
        startVpnService();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_VPN) return;
        if (resultCode == RESULT_OK) {
            startVpnService();
        } else {
            XtToast.show(this, R.string.killad_vpn_denied);
            refresh();
        }
    }

    private void startVpnService() {
        try {
            Intent i = new Intent(this, KillAdVpnService.class);
            i.setAction(KillAdVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            prefs.edit().putBoolean(KillAdRules.KEY_ENABLED, true).apply();
            XtToast.show(this, R.string.killad_starting);
        } catch (Exception e) {
            XtToast.show(this, R.string.killad_start_fail);
        }
        statusView.postDelayed(new Runnable() {
            @Override
            public void run() { refresh(); }
        }, 800L);
    }

    /** 完全关闭：停止服务并撤下通知 */
    private void stopVpnService() {
        try {
            Intent i = new Intent(this, KillAdVpnService.class);
            i.setAction(KillAdVpnService.ACTION_STOP);
            startService(i);
        } catch (Exception ignored) {}
        try { stopService(new Intent(this, KillAdVpnService.class)); } catch (Exception ignored) {}
        prefs.edit().putBoolean(KillAdRules.KEY_ENABLED, false)
                .putBoolean("killad_running", false)
                .putBoolean(KillAdVpnService.KEY_PAUSED, false).apply();
        XtToast.show(this, R.string.killad_stopped);
        statusView.postDelayed(new Runnable() {
            @Override
            public void run() { refresh(); }
        }, 500L);
    }
}
