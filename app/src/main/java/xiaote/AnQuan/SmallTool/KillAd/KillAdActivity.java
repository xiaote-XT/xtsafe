package xiaote.AnQuan.SmallTool.KillAd;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import xiaote.AnQuan.R;
import xiaote.AnQuan.SwipeBackHelper;

/**
 * 广告拦截（DNS 层）设置页。
 *
 * 只负责 UI 与开关，真正的拦截逻辑在 KillAdVpnService。
 * 首页入口 -> 本页 -> 开启时弹出系统 VPN 授权弹窗（VpnService.prepare 必须用户手点）。
 *
 * 状态有三种：已停止 / 运行中 / 已暂停。
 * 暂停由通知栏按钮或本页按钮触发，服务不退出、通知保留，可随时恢复。
 */
public class KillAdActivity extends Activity {

    private static final int REQUEST_VPN = 2001;

    private SharedPreferences prefs;
    private Switch mainSwitch;
    private TextView statusView;
    private TextView statsView;
    private TextView rulesView;
    private TextView ruleInfoView;
    private TextView logInfoView;
    private Button pauseBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(KillAdRules.PREFS, MODE_PRIVATE);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 24, 30, 30);

        TextView title = new TextView(this);
        title.setText(R.string.killad_title);
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 10, 0, 4);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText(R.string.killad_tip);
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, 20);
        root.addView(tip);

        // ============ 开关 ============
        mainSwitch = new Switch(this);
        mainSwitch.setText(R.string.killad_switch);
        mainSwitch.setTextSize(14);
        mainSwitch.setTextColor(getTextColor());
        mainSwitch.setChecked(KillAdVpnService.isRunning(this));
        bindSwitch();
        root.addView(mainSwitch);

        // ============ 状态 ============
        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(getSecondaryTextColor());
        statusView.setPadding(dpToPx(12), 6, dpToPx(12), 2);
        root.addView(statusView);

        statsView = new TextView(this);
        statsView.setTextSize(12);
        statsView.setTextColor(getSecondaryTextColor());
        statsView.setPadding(dpToPx(12), 2, dpToPx(12), 8);
        root.addView(statsView);

        // ============ 暂停 / 恢复 ============
        pauseBtn = new Button(this);
        pauseBtn.setText(R.string.killad_notify_pause);
        pauseBtn.setTextSize(14);
        pauseBtn.setAllCaps(false);
        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!KillAdVpnService.isRunning(KillAdActivity.this)) {
                    Toast.makeText(KillAdActivity.this, R.string.killad_status_stopped, Toast.LENGTH_SHORT).show();
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
        root.addView(pauseBtn);

        TextView pauseTip = new TextView(this);
        pauseTip.setText(R.string.killad_pause_tip);
        pauseTip.setTextColor(getSecondaryTextColor());
        pauseTip.setTextSize(11);
        pauseTip.setPadding(dpToPx(12), 4, dpToPx(12), 14);
        root.addView(pauseTip);

        // ============ 规则条数 ============
        rulesView = new TextView(this);
        rulesView.setTextSize(12);
        rulesView.setTextColor(getSecondaryTextColor());
        rulesView.setPadding(dpToPx(12), 0, dpToPx(12), 16);
        root.addView(rulesView);

        // ============ 在线规则源 ============
        TextView ruleLabel = new TextView(this);
        ruleLabel.setText(R.string.killad_rule_source_label);
        ruleLabel.setTextSize(13);
        ruleLabel.setTextColor(getTextColor());
        ruleLabel.setPadding(0, 8, 0, 6);
        root.addView(ruleLabel);

        final EditText ruleUrlInput = new EditText(this);
        ruleUrlInput.setSingleLine(true);
        ruleUrlInput.setTextSize(12);
        ruleUrlInput.setTextColor(getTextColor());
        ruleUrlInput.setText(KillAdRuleUpdater.getRuleUrl(this));
        root.addView(ruleUrlInput);

        LinearLayout ruleBtnRow = new LinearLayout(this);
        ruleBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        ruleBtnRow.setGravity(Gravity.CENTER);

        Button ruleSave = new Button(this);
        ruleSave.setText(R.string.killad_rule_source_save);
        ruleSave.setTextSize(13);
        ruleSave.setAllCaps(false);
        ruleSave.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        ruleSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                KillAdRuleUpdater.setRuleUrl(KillAdActivity.this, ruleUrlInput.getText().toString());
                ruleUrlInput.setText(KillAdRuleUpdater.getRuleUrl(KillAdActivity.this));
                Toast.makeText(KillAdActivity.this, R.string.killad_rule_source_saved, Toast.LENGTH_SHORT).show();
            }
        });
        ruleBtnRow.addView(ruleSave);

        Button ruleUpdate = new Button(this);
        ruleUpdate.setText(R.string.killad_rule_update);
        ruleUpdate.setTextSize(13);
        ruleUpdate.setAllCaps(false);
        ruleUpdate.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        ruleUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(KillAdActivity.this, R.string.killad_rule_updating, Toast.LENGTH_SHORT).show();
                if (ruleInfoView != null) ruleInfoView.setText(R.string.killad_rule_updating);
                KillAdRuleUpdater.updateAsync(KillAdActivity.this, new KillAdRuleUpdater.Callback() {
                    @Override
                    public void onDone(boolean ok, int count, String message) {
                        if (ok) {
                            Toast.makeText(KillAdActivity.this,
                                    getString(R.string.killad_rule_update_ok, count), Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(KillAdActivity.this,
                                    getString(R.string.killad_rule_update_fail, message), Toast.LENGTH_LONG).show();
                        }
                        refresh();
                    }
                });
            }
        });
        ruleBtnRow.addView(ruleUpdate);
        root.addView(ruleBtnRow);

        ruleInfoView = new TextView(this);
        ruleInfoView.setTextSize(11);
        ruleInfoView.setTextColor(getSecondaryTextColor());
        ruleInfoView.setPadding(dpToPx(12), 6, dpToPx(12), 10);
        root.addView(ruleInfoView);

        // ============ 上游 DNS ============
        TextView upLabel = new TextView(this);
        upLabel.setText(R.string.killad_upstream_label);
        upLabel.setTextSize(13);
        upLabel.setTextColor(getTextColor());
        upLabel.setPadding(0, 12, 0, 6);
        root.addView(upLabel);

        final EditText upInput = new EditText(this);
        upInput.setSingleLine(true);
        upInput.setTextSize(13);
        upInput.setTextColor(getTextColor());
        upInput.setText(KillAdRules.getUpstream(this));
        root.addView(upInput);

        Button upSave = new Button(this);
        upSave.setText(R.string.killad_upstream_save);
        upSave.setTextSize(14);
        upSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String host = upInput.getText().toString().trim();
                if (host.isEmpty()) host = KillAdRules.DEFAULT_UPSTREAM;
                KillAdRules.setUpstream(KillAdActivity.this, host);
                Toast.makeText(KillAdActivity.this, R.string.killad_upstream_saved, Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(upSave);

        // ============ 自定义拦截域名 ============
        TextView customLabel = new TextView(this);
        customLabel.setText(R.string.killad_custom_label);
        customLabel.setTextSize(13);
        customLabel.setTextColor(getTextColor());
        customLabel.setPadding(0, 20, 0, 6);
        root.addView(customLabel);

        final EditText customInput = new EditText(this);
        customInput.setSingleLine(false);
        customInput.setMinLines(3);
        customInput.setMaxLines(8);
        customInput.setTextSize(13);
        customInput.setTextColor(getTextColor());
        customInput.setText(KillAdRules.getCustomDomains(this));
        root.addView(customInput);

        Button customSave = new Button(this);
        customSave.setText(R.string.killad_custom_save);
        customSave.setTextSize(14);
        customSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                KillAdRules.setCustomDomains(KillAdActivity.this, customInput.getText().toString());
                Toast.makeText(KillAdActivity.this, R.string.killad_custom_saved, Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        root.addView(customSave);

        // ============ 运行日志 ============
        TextView logLabel = new TextView(this);
        logLabel.setText(R.string.killad_log_label);
        logLabel.setTextSize(13);
        logLabel.setTextColor(getTextColor());
        logLabel.setPadding(0, 20, 0, 6);
        root.addView(logLabel);

        logInfoView = new TextView(this);
        logInfoView.setTextSize(11);
        logInfoView.setTextColor(getSecondaryTextColor());
        logInfoView.setTextIsSelectable(true);
        logInfoView.setPadding(dpToPx(12), 0, dpToPx(12), 8);
        root.addView(logInfoView);

        Button logClear = new Button(this);
        logClear.setText(R.string.killad_log_clear);
        logClear.setTextSize(14);
        logClear.setAllCaps(false);
        logClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                KillAdLogger.clear(KillAdActivity.this);
                Toast.makeText(KillAdActivity.this, R.string.killad_log_cleared, Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        root.addView(logClear);

        // ============ 返回 ============
        Button backBtn = new Button(this);
        backBtn.setText(R.string.guide_back);
        backBtn.setTextSize(14);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-1, -2);
        backLp.topMargin = dpToPx(24);
        root.addView(backBtn, backLp);

        scrollView.addView(root);
        setContentView(scrollView);

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);

        showNoticeDialogIfNeeded();
    }

    /**
     * 首次进入时的提示弹窗。
     *
     * 说明内置广告拦截的局限，并引导有更高需求的用户去下载开源的 BlockAds。
     * 「不再提示」会把标记写入 dot_config，之后不再弹出。
     *
     * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟导致编译报错。
     */
    private void showNoticeDialogIfNeeded() {
        try {
            if (prefs.getBoolean("killad_notice_dont_show", false)) return;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("星特安全内置的广告拦截功能为简单轻量实现，局限性较大，如果你希望获得完整的广告拦截，建议下载GPL-3.0开源的BlockAds，注意，部分恶意修改者会提供不开源的版本，并将应用改成自己的名字，这个可能有较高风险")
                    .setPositiveButton("确定", null)
                    .setNegativeButton("不再提示", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            prefs.edit().putBoolean("killad_notice_dont_show", true).apply();
                        }
                    })
                    .setNeutralButton("下载BlockAds", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            try {
                                Intent i = new Intent(Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://github.com/pass-with-high-score/blockads-android/releases"));
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i);
                            } catch (Exception e) {
                                Toast.makeText(KillAdActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setCancelable(true)
                    .show();
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void bindSwitch() {
        mainSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (isChecked) requestStart();
                else stopVpnService();
            }
        });
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

    private void refresh() {
        boolean running = KillAdVpnService.isRunning(this);
        boolean paused = running && KillAdVpnService.isPaused(this);

        if (mainSwitch != null && mainSwitch.isChecked() != running) {
            mainSwitch.setOnCheckedChangeListener(null);
            mainSwitch.setChecked(running);
            bindSwitch();
        }

        if (statusView != null) {
            if (!running) {
                statusView.setText(R.string.killad_status_stopped);
                statusView.setTextColor(getSecondaryTextColor());
            } else if (paused) {
                statusView.setText(R.string.killad_status_paused);
                statusView.setTextColor(Color.argb(255, 210, 150, 40));
            } else {
                statusView.setText(R.string.killad_status_running);
                statusView.setTextColor(Color.argb(255, 60, 160, 80));
            }
        }

        if (pauseBtn != null) {
            pauseBtn.setText(paused ? R.string.killad_notify_resume : R.string.killad_notify_pause);
            pauseBtn.setEnabled(running);
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
            Toast.makeText(this, R.string.killad_vpn_denied, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, R.string.killad_starting, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.killad_start_fail, Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, R.string.killad_stopped, Toast.LENGTH_SHORT).show();
        statusView.postDelayed(new Runnable() {
            @Override
            public void run() { refresh(); }
        }, 500L);
    }

    private int getTextColor() {
        return Color.argb(255, 30, 30, 30);
    }

    private int getSecondaryTextColor() {
        return Color.argb(150, 90, 90, 90);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
