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
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 悬浮按钮设置
 */
public class FloatingButtonSettingsActivity extends BaseActivity {

    private SharedPreferences prefs;
    /** 强制置顶开关：需要在悬浮按钮开关的回调里同步 UI 状态 */
    private android.widget.Switch forceSwitch;

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
        title.setText(R.string.float_settings_title);
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 10, 0, 4);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText(R.string.float_settings_tip);
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, 20);
        root.addView(tip);

        // 悬浮按钮总开关（默认开）
        final android.widget.Switch dotSwitch = new android.widget.Switch(this);
        dotSwitch.setChecked(prefs.getBoolean("dot_enabled", true));
        dotSwitch.setTextOn(getString(R.string.force_on));
        dotSwitch.setTextOff(getString(R.string.force_off));
        dotSwitch.setText(R.string.switch_show_dot);
        dotSwitch.setTextSize(14);
        dotSwitch.setTextColor(getTextColor());
        dotSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("dot_enabled", isChecked).apply();
                // 关掉悬浮按钮时一并关闭强制置顶：置顶依赖悬浮窗与无障碍，关掉后继续跑会干扰触摸
                if (!isChecked) {
                    prefs.edit().putBoolean("force_top", false).apply();
                    if (forceSwitch != null) forceSwitch.setChecked(false);
                    Toast.makeText(FloatingButtonSettingsActivity.this,
                            R.string.dot_off_force_top_off, Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(dotSwitch);

        TextView dotTip = new TextView(this);
        dotTip.setText(R.string.tip_show_dot);
        dotTip.setTextColor(getHintTextColor());
        dotTip.setTextSize(11);
        dotTip.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(dotTip);

        // 宽度
        addSeekBarSetting(root, getString(R.string.label_width), "width", 10, 120, prefs.getInt("dot_width", 40), "dp");
        // 高度
        addSeekBarSetting(root, getString(R.string.label_height), "height", 0, 60, prefs.getInt("dot_height", 0), "dp");
        TextView heightTip = new TextView(this);
        heightTip.setText(R.string.tip_height);
        heightTip.setTextColor(getHintTextColor());
        heightTip.setTextSize(11);
        heightTip.setPadding(dpToPx(90), 0, 0, 10);
        root.addView(heightTip);
        // 透明度
        addSeekBarSetting(root, getString(R.string.label_alpha), "alpha", 0, 255, prefs.getInt("dot_alpha", 140), "");

        // 颜色
        TextView colorLabel = new TextView(this);
        colorLabel.setText(R.string.label_color_full);
        colorLabel.setTextColor(getTextColor());
        colorLabel.setTextSize(14);
        colorLabel.setPadding(0, 10, 0, 8);
        root.addView(colorLabel);

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER);
        final int[] colors = {
            Color.argb(140, 255, 200, 100),
            Color.argb(140, 255, 255, 255),
            Color.argb(140, 160, 100, 230),
            Color.argb(140, 100, 180, 255),
            Color.argb(140, 100, 230, 150),
            Color.argb(140, 255, 100, 100),
        };
        final String[] colorNames = {getString(R.string.color_orange), getString(R.string.color_white), getString(R.string.color_purple), getString(R.string.color_blue), getString(R.string.color_green), getString(R.string.color_red)};
        for (int i = 0; i < colors.length; i++) {
            final int colorVal = colors[i];
            Button cb = new Button(this);
            cb.setText(colorNames[i]);
            cb.setTextSize(11);
            cb.setTextColor(Color.WHITE);
            cb.setBackgroundColor(colorVal);
            cb.setPadding(12, 8, 12, 8);
            cb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.edit().putInt("dot_color", colorVal).apply();
                    Toast.makeText(FloatingButtonSettingsActivity.this, R.string.toast_color_set, Toast.LENGTH_SHORT).show();
                }
            });
            colorRow.addView(cb);
        }
        root.addView(colorRow);

        // 位置
        TextView posLabel = new TextView(this);
        posLabel.setText(R.string.label_position_full);
        posLabel.setTextColor(getTextColor());
        posLabel.setTextSize(14);
        posLabel.setPadding(0, 15, 0, 8);
        root.addView(posLabel);

        LinearLayout posRow = new LinearLayout(this);
        posRow.setOrientation(LinearLayout.HORIZONTAL);
        posRow.setGravity(Gravity.CENTER);
        final String[] posNames = {getString(R.string.pos_left), getString(R.string.pos_center), getString(R.string.pos_right)};
        final int[] posValues = {0, 1, 2};
        for (int i = 0; i < posNames.length; i++) {
            final int posVal = posValues[i];
            Button pb = new Button(this);
            pb.setText(posNames[i]);
            pb.setTextSize(14);
            pb.setAllCaps(false);
            pb.setPadding(14, 6, 14, 6);
            pb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.edit().putInt("dot_position", posVal).apply();
                    Toast.makeText(FloatingButtonSettingsActivity.this, R.string.position_set, Toast.LENGTH_SHORT).show();
                }
            });
            posRow.addView(pb);
        }
        root.addView(posRow);

        // 强制置顶开关
        TextView forceLabel = new TextView(this);
        forceLabel.setText(R.string.label_force_top);
        forceLabel.setTextColor(getTextColor());
        forceLabel.setTextSize(13);
        forceLabel.setPadding(0, 20, 0, 8);
        root.addView(forceLabel);

        forceSwitch = new android.widget.Switch(this);
        forceSwitch.setChecked(prefs.getBoolean("force_top", true));
        forceSwitch.setTextOn(getString(R.string.force_on));
        forceSwitch.setTextOff(getString(R.string.force_off));
        forceSwitch.setTextSize(14);
        forceSwitch.setTextColor(getTextColor());
        forceSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("force_top", isChecked).apply();
            }
        });
        root.addView(forceSwitch);

        // 置顶模式选择
        TextView modeLabel = new TextView(this);
        modeLabel.setText(R.string.force_top_mode_label);
        modeLabel.setTextColor(getTextColor());
        modeLabel.setTextSize(14);
        modeLabel.setPadding(0, 15, 0, 8);
        root.addView(modeLabel);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER);
        final String[] modeNames = {getString(R.string.mode_restart), getString(R.string.mode_priority), getString(R.string.mode_mixed)};
        final int[] modeValues = {0, 1, 2};
        for (int i = 0; i < modeNames.length; i++) {
            final int modeVal = modeValues[i];
            Button mb = new Button(this);
            mb.setText(modeNames[i]);
            mb.setTextSize(13);
            mb.setAllCaps(false);
            mb.setPadding(10, 6, 10, 6);
            mb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.edit().putInt("force_top_mode", modeVal).apply();
                    Toast.makeText(FloatingButtonSettingsActivity.this, getString(R.string.mode_selected, modeNames[modeVal]), Toast.LENGTH_SHORT).show();
                }
            });
            modeRow.addView(mb);
        }
        root.addView(modeRow);

        TextView modeTip = new TextView(this);
        modeTip.setText(R.string.mode_tip);
        modeTip.setTextColor(getHintTextColor());
        modeTip.setTextSize(11);
        modeTip.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(modeTip);

        // 锁屏自动停止强制置顶（实验性）
        final android.widget.Switch stopOnOffSwitch = new android.widget.Switch(this);
        stopOnOffSwitch.setChecked(prefs.getBoolean("force_stop_on_screen_off", true));
        stopOnOffSwitch.setTextOn(getString(R.string.force_on));
        stopOnOffSwitch.setTextOff(getString(R.string.force_off));
        stopOnOffSwitch.setText(R.string.stop_on_screen_off);
        stopOnOffSwitch.setTextSize(13);
        stopOnOffSwitch.setTextColor(getTextColor());
        stopOnOffSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("force_stop_on_screen_off", isChecked).apply();
            }
        });
        root.addView(stopOnOffSwitch);
        TextView stopOnOffTip = new TextView(this);
        stopOnOffTip.setText(R.string.stop_on_screen_off_tip);
        stopOnOffTip.setTextColor(getHintTextColor());
        stopOnOffTip.setTextSize(11);
        stopOnOffTip.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(stopOnOffTip);

        // 后台保活优化
        final android.widget.Switch keepSwitch = new android.widget.Switch(this);
        keepSwitch.setChecked(prefs.getBoolean("background_keep_alive", true));
        keepSwitch.setTextOn(getString(R.string.force_on));
        keepSwitch.setTextOff(getString(R.string.force_off));
        keepSwitch.setText(R.string.background_keep_alive);
        keepSwitch.setTextSize(14);
        keepSwitch.setTextColor(getTextColor());
        keepSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("background_keep_alive", isChecked).apply();
            }
        });
        root.addView(keepSwitch);

        TextView keepTip = new TextView(this);
        keepTip.setText(R.string.background_keep_alive_tip);
        keepTip.setTextColor(getHintTextColor());
        keepTip.setTextSize(11);
        keepTip.setPadding(dpToPx(12), 4, dpToPx(12), 10);
        root.addView(keepTip);

        // 间隔
        addSeekBarSetting(root, getString(R.string.label_interval), "interval", 5, 60, prefs.getInt("force_interval", 5), "秒");
        TextView intervalTip = new TextView(this);
        intervalTip.setText(R.string.tip_interval);
        intervalTip.setTextColor(getHintTextColor());
        intervalTip.setTextSize(11);
        intervalTip.setPadding(dpToPx(90), 0, 0, 10);
        root.addView(intervalTip);

        TextView footer = new TextView(this);
        footer.setText(R.string.footer_settings);
        footer.setTextColor(getSecondaryTextColor());
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, 30, 0, 10);
        root.addView(footer);

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

        maybeShowModeWarning();
    }

    /**
     * 进入本页时，若当前为「最高优先级 / 混合」置顶模式且未勾选不再提示，
     * 弹窗说明该模式可能导致滑动断触，并提供「切换重启模式 / 关闭弹窗 / 不再提示」。
     */
    private void maybeShowModeWarning() {
        try {
            if (prefs.getBoolean("force_top_mode_warn_off", false)) return;
            // 每次进入本页都提示（除非已勾选不再提示）：断触来自重启服务模式，建议切到最高优先级
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.mode_warn_title)
                    .setMessage(R.string.mode_warn_msg)
                    .setPositiveButton(R.string.switch_restart_mode, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            prefs.edit().putInt("force_top_mode", 1).apply();
                            Toast.makeText(FloatingButtonSettingsActivity.this,
                                    getString(R.string.mode_selected, getString(R.string.mode_priority)),
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(R.string.close_dialog, null)
                    .setNeutralButton(R.string.no_more_prompt, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            prefs.edit().putBoolean("force_top_mode_warn_off", true).apply();
                        }
                    })
                    .show();
        } catch (Exception ignored) {}
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

    private void addSeekBarSetting(LinearLayout root, String label, final String key,
                                   int min, int max, int currentValue, String unit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 5, 0, 5);

        TextView lbl = new TextView(this);
        lbl.setText(label + ":");
        lbl.setTextColor(getTextColor());
        lbl.setTextSize(14);
        lbl.setWidth(dpToPx(50));
        row.addView(lbl);

        final TextView valDisplay = new TextView(this);
        String displayText = (currentValue == 0 && key.equals("height")) ? getString(R.string.auto_val) : currentValue + unit;
        valDisplay.setText(displayText);
        valDisplay.setTextColor(getThemeColor());
        valDisplay.setTextSize(14);
        valDisplay.setWidth(dpToPx(50));
        valDisplay.setGravity(Gravity.CENTER);
        row.addView(valDisplay);

        final int fMin = min;
        final int fMax = max;
        final String fKey = key;
        final String fUnit = unit;
        final SharedPreferences fPrefs = prefs;

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(currentValue - min);
        seek.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        if (Build.VERSION.SDK_INT >= 21) {
            seek.setProgressTintList(android.content.res.ColorStateList.valueOf(getThemeColor()));
            seek.setThumbTintList(android.content.res.ColorStateList.valueOf(getThemeColor()));
        }
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = progress + fMin;
                String display = (val == 0 && fKey.equals("height")) ? getString(R.string.auto_val) : val + fUnit;
                valDisplay.setText(display);
                if (fromUser) {
                    fPrefs.edit().putInt("dot_" + fKey, val).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        row.addView(seek);
        root.addView(row);
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

    private int getThemeColor() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return getResources().getColor(android.R.color.system_accent1_500, getTheme());
            } catch (Exception e) {}
        }
        return 0xFFFF6A00;
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}