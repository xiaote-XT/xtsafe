package xiaote.AnQuan;

import android.app.Activity;
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
 * 主动防护：禁止安装病毒、安装通知提醒、强制停止病毒、安装管控提示、自定义病毒包名
 */
public class ProtectionSettingsActivity extends BaseActivity {

    private SharedPreferences prefs;

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
                    int val = progress + 1; // 1 ~ 600, 对应0.1~60.0秒
                    prefs.edit().putInt("volume_check_interval", val).apply();
                    float sec = val / 10.0f;
                    volumeFreqTip.setText(getString(R.string.volume_freq_tip, (sec == (int) sec ? String.valueOf((int) sec) : String.valueOf(sec))));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(volumeFreqSeek);

        // 音量最高唤起卸载列表
        addSwitch(root, getString(R.string.volume_max_show), "volume_max_show_uninstall", false);
        TextView tipMax = new TextView(this);
        tipMax.setText(R.string.volume_max_tip);
        tipMax.setTextColor(getHintTextColor());
        tipMax.setTextSize(11);
        tipMax.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(tipMax);

        // 音量超阈值强制停止所有应用
        addSwitch(root, getString(R.string.volume_force_stop), "volume_force_stop_all", false);
        TextView tipForceStop = new TextView(this);
        tipForceStop.setText(R.string.volume_force_stop_tip);
        tipForceStop.setTextColor(getHintTextColor());
        tipForceStop.setTextSize(11);
        tipForceStop.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(tipForceStop);

        // 按十下音量-触发强制停止所有应用（无障碍按键识别）
        addSwitch(root, getString(R.string.volume_key_force_stop), "volume_key_force_stop_all", true);
        TextView tipKeyForceStop = new TextView(this);
        tipKeyForceStop.setText(R.string.volume_key_force_stop_tip);
        tipKeyForceStop.setTextColor(getHintTextColor());
        tipKeyForceStop.setTextSize(11);
        tipKeyForceStop.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(tipKeyForceStop);

        // 自动拦截全屏覆盖应用
        addSwitch(root, getString(R.string.switch_block_overlay), "block_overlay", true);
        TextView tipBlockOverlay = new TextView(this);
        tipBlockOverlay.setText(R.string.tip_block_overlay);
        tipBlockOverlay.setTextColor(getHintTextColor());
        tipBlockOverlay.setTextSize(11);
        tipBlockOverlay.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(tipBlockOverlay);

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
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
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