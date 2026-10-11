package xiaote.AnQuan;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
 * 悬浮反锁机设置。
 *
 * 全部按 xtui 规范重写：
 *   · 骨架 XtUi.column / XtUi.scroll，标题 XtUi.title，状态栏同色；
 *   · 按功能分卡片，小标题在卡片内（20sp 主题色居中）；
 *   · 开关统一 XtUi.XtSwitch + XtUi.row；
 *   · 滑块统一 XtUi.slider（左右已留 12dp，圆点不再被裁）；
 *   · 弹窗走 XtDialog，轻提示走 XtToast。
 *
 * 业务逻辑（悬浮按钮开关、尺寸/透明度/颜色/位置、强制置顶与其模式、
 * 锁屏自动停止、后台保活、间隔、置顶模式风险提示）全部保留。
 */
public class FloatingButtonSettingsActivity extends BaseActivity {

    private SharedPreferences prefs;
    /** 悬浮按钮开关：关闭时要联动把强制置顶也关掉 */
    private XtUi.XtSwitch forceSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        // 界面模式分流：ui_mode=h5 时交给 H5 壳，原生界面不构建。默认 native。
        if ("h5".equals(prefs.getString("ui_mode", "native"))) {
            try { startActivity(new android.content.Intent(this, H5FloatActivity.class)); } catch (Throwable ignored) {}
            finish();
            return;
        }

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, getString(R.string.float_settings_title)));
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this, getString(R.string.float_settings_tip));
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, XtUi.dp(this, 16));
        root.addView(tip);

        // ============ 悬浮按钮 ============
        LinearLayout cardDot = XtUi.card(this);

        final XtUi.XtSwitch dotSwitch = XtUi.switchView(this, prefs.getBoolean("dot_enabled", false));
        dotSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(XtUi.XtSwitch buttonView, boolean isChecked) {
                prefs.edit().putBoolean("dot_enabled", isChecked).apply();
                // 关掉悬浮按钮时一并关闭强制置顶：置顶依赖悬浮窗与无障碍，关掉后继续跑会干扰触摸
                if (!isChecked) {
                    prefs.edit().putBoolean("force_top", false).apply();
                    if (forceSwitch != null) forceSwitch.setChecked(false);
                    XtToast.show(FloatingButtonSettingsActivity.this, R.string.dot_off_force_top_off);
                }
            }
        });
        cardDot.addView(XtUi.row(this, getString(R.string.switch_show_dot), dotSwitch));
        cardDot.addView(XtUi.tip(this, getString(R.string.tip_show_dot)));

        root.addView(cardDot);

        // ============ 外观 ============
        LinearLayout cardLook = XtUi.card(this);

        addSeekRow(cardLook, getString(R.string.label_width), "width", 10, 120, prefs.getInt("dot_width", 40), "dp", null);
        addSeekRow(cardLook, getString(R.string.label_height), "height", 0, 60, prefs.getInt("dot_height", 0), "dp", getString(R.string.tip_height));
        addSeekRow(cardLook, getString(R.string.label_alpha), "alpha", 0, 255, prefs.getInt("dot_alpha", 140), "", null);

        // 颜色
        TextView colorLabel = new TextView(this);
        colorLabel.setText(R.string.label_color_full);
        colorLabel.setTextColor(XtUi.colorText(this));
        colorLabel.setTextSize(14);
        colorLabel.setPadding(0, XtUi.dp(this, 12), 0, XtUi.dp(this, 8));
        cardLook.addView(colorLabel);

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
        final String[] colorNames = {
            getString(R.string.color_orange), getString(R.string.color_white),
            getString(R.string.color_purple), getString(R.string.color_blue),
            getString(R.string.color_green), getString(R.string.color_red)
        };
        for (int i = 0; i < colors.length; i++) {
            final int colorVal = colors[i];
            TextView cb = new TextView(this);
            cb.setText(colorNames[i]);
            cb.setTextSize(11);
            cb.setTextColor(Color.WHITE);
            cb.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(XtUi.dp(this, 12));
            bg.setColor(colorVal);
            cb.setBackground(bg);
            cb.setPadding(XtUi.dp(this, 10), XtUi.dp(this, 8), XtUi.dp(this, 10), XtUi.dp(this, 8));
            cb.setClickable(true);
            cb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.edit().putInt("dot_color", colorVal).apply();
                    XtToast.show(FloatingButtonSettingsActivity.this, R.string.toast_color_set);
                }
            });
            XtUi.attachScale(cb);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
            clp.leftMargin = XtUi.dp(this, 3);
            clp.rightMargin = XtUi.dp(this, 3);
            colorRow.addView(cb, clp);
        }
        cardLook.addView(colorRow);

        // 位置
        TextView posLabel = new TextView(this);
        posLabel.setText(R.string.label_position_full);
        posLabel.setTextColor(XtUi.colorText(this));
        posLabel.setTextSize(14);
        posLabel.setPadding(0, XtUi.dp(this, 16), 0, XtUi.dp(this, 8));
        cardLook.addView(posLabel);

        LinearLayout posRow = new LinearLayout(this);
        posRow.setOrientation(LinearLayout.HORIZONTAL);
        posRow.setGravity(Gravity.CENTER);
        final String[] posNames = {
            getString(R.string.pos_left), getString(R.string.pos_center), getString(R.string.pos_right)
        };
        final int[] posValues = {0, 1, 2};
        for (int i = 0; i < posNames.length; i++) {
            final int posVal = posValues[i];
            TextView pb = XtUi.button(this, posNames[i], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.edit().putInt("dot_position", posVal).apply();
                    XtToast.show(FloatingButtonSettingsActivity.this, R.string.position_set);
                }
            });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, -2, 1f);
            plp.leftMargin = XtUi.dp(this, 4);
            plp.rightMargin = XtUi.dp(this, 4);
            posRow.addView(pb, plp);
        }
        cardLook.addView(posRow);

        root.addView(cardLook);

        // ============ 强制置顶 ============
        LinearLayout cardTop = XtUi.card(this);

        forceSwitch = XtUi.switchView(this, prefs.getBoolean("force_top", true));
        forceSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(XtUi.XtSwitch buttonView, boolean isChecked) {
                prefs.edit().putBoolean("force_top", isChecked).apply();
            }
        });
        cardTop.addView(XtUi.row(this, getString(R.string.label_force_top), forceSwitch));

        // 置顶模式
        TextView modeLabel = new TextView(this);
        modeLabel.setText(R.string.force_top_mode_label);
        modeLabel.setTextColor(XtUi.colorText(this));
        modeLabel.setTextSize(14);
        modeLabel.setPadding(0, XtUi.dp(this, 16), 0, XtUi.dp(this, 8));
        cardTop.addView(modeLabel);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER);
        final String[] modeNames = {
            getString(R.string.mode_restart), getString(R.string.mode_priority), getString(R.string.mode_mixed)
        };
        final int[] modeValues = {0, 1, 2};
        for (int i = 0; i < modeNames.length; i++) {
            final int modeVal = modeValues[i];
            TextView mb = XtUi.button(this, modeNames[i], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.edit().putInt("force_top_mode", modeVal).apply();
                    XtToast.show(FloatingButtonSettingsActivity.this,
                            getString(R.string.mode_selected, modeNames[modeVal]));
                }
            });
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1f);
            mlp.leftMargin = XtUi.dp(this, 4);
            mlp.rightMargin = XtUi.dp(this, 4);
            modeRow.addView(mb, mlp);
        }
        cardTop.addView(modeRow);

        cardTop.addView(XtUi.tip(this, getString(R.string.mode_tip)));

        // 锁屏自动停止强制置顶（实验性）
        final XtUi.XtSwitch stopOnOffSwitch = XtUi.switchView(this, prefs.getBoolean("force_stop_on_screen_off", true));
        stopOnOffSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(XtUi.XtSwitch buttonView, boolean isChecked) {
                prefs.edit().putBoolean("force_stop_on_screen_off", isChecked).apply();
            }
        });
        LinearLayout stopRow = XtUi.row(this, getString(R.string.stop_on_screen_off), stopOnOffSwitch);
        stopRow.setPadding(0, XtUi.dp(this, 10), 0, XtUi.dp(this, 6));
        cardTop.addView(stopRow);
        cardTop.addView(XtUi.tip(this, getString(R.string.stop_on_screen_off_tip)));

        // 后台保活优化（默认关闭）
        final XtUi.XtSwitch keepSwitch = XtUi.switchView(this, prefs.getBoolean("background_keep_alive", false));
        keepSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(XtUi.XtSwitch buttonView, boolean isChecked) {
                prefs.edit().putBoolean("background_keep_alive", isChecked).apply();
            }
        });
        LinearLayout keepRow = XtUi.row(this, getString(R.string.background_keep_alive), keepSwitch);
        keepRow.setPadding(0, XtUi.dp(this, 10), 0, XtUi.dp(this, 6));
        cardTop.addView(keepRow);
        cardTop.addView(XtUi.tip(this, getString(R.string.background_keep_alive_tip)));

        // 间隔
        addSeekRow(cardTop, getString(R.string.label_interval), "interval", 5, 60, prefs.getInt("force_interval", 5), "秒", getString(R.string.tip_interval));

        root.addView(cardTop);

        // 底部提示
        TextView footer = new TextView(this);
        footer.setText(R.string.footer_settings);
        footer.setTextColor(XtUi.colorMuted(this));
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, XtUi.dp(this, 16), 0, XtUi.dp(this, 10));
        root.addView(footer);

        setContentView(scrollView);
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);

        maybeShowModeWarning();
    }

    /**
     * 进入本页时，若未勾选不再提示，弹窗说明置顶模式可能导致滑动断触，
     * 并提供「切换最高优先级 / 关闭弹窗 / 不再提示」。
     */
    private void maybeShowModeWarning() {
        try {
            if (prefs.getBoolean("force_top_mode_warn_off", false)) return;
            android.app.AlertDialog dlg = XtDialog.builder(this,
                            getString(R.string.mode_warn_title), getString(R.string.mode_warn_msg))
                    .setPositiveButton(R.string.switch_restart_mode, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            prefs.edit().putInt("force_top_mode", 1).apply();
                            XtToast.show(FloatingButtonSettingsActivity.this,
                                    getString(R.string.mode_selected, getString(R.string.mode_priority)));
                        }
                    })
                    .setNegativeButton(R.string.close_dialog, null)
                    .setNeutralButton(R.string.no_more_prompt, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            prefs.edit().putBoolean("force_top_mode_warn_off", true).apply();
                        }
                    })
                    .create();
            dlg.show();
            XtDialog.tint(dlg);
        } catch (Exception ignored) {}
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

    /**
     * 一行滑块设置：左标签 + 中间数值 + 右滑块（占满剩余宽度）。
     *
     * 数值实时回显；拖动时写 "dot_" + key。tip 非空时在下方补一行说明。
     * 滑块左右各留 12dp，确保进度 0%/100% 时圆点完整显示。
     */
    private void addSeekRow(LinearLayout root, String label, final String key,
                            int min, int max, int currentValue, String unit, String tip) {
        final int fMin = min;
        final String fKey = key;
        final String fUnit = unit;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, XtUi.dp(this, 8), 0, XtUi.dp(this, 4));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(XtUi.colorText(this));
        lbl.setTextSize(14);
        lbl.setWidth(XtUi.dp(this, 80));
        row.addView(lbl);

        final TextView valDisplay = new TextView(this);
        String displayText = (currentValue == 0 && "height".equals(key))
                ? getString(R.string.auto_val) : currentValue + unit;
        valDisplay.setText(displayText);
        valDisplay.setTextColor(XtUi.primary);
        valDisplay.setTextSize(14);
        valDisplay.setGravity(Gravity.CENTER);
        valDisplay.setWidth(XtUi.dp(this, 56));
        row.addView(valDisplay);

        SeekBar seek = XtUi.slider(this, max - min, currentValue - min);
        seek.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = progress + fMin;
                String display = (val == 0 && "height".equals(fKey))
                        ? getString(R.string.auto_val) : val + fUnit;
                valDisplay.setText(display);
                if (fromUser) {
                    prefs.edit().putInt("dot_" + fKey, val).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        row.addView(seek);

        root.addView(row);

        if (tip != null && tip.length() > 0) {
            TextView tv = XtUi.tip(this, tip);
            tv.setPadding(XtUi.dp(this, 80), 0, 0, XtUi.dp(this, 6));
            root.addView(tv);
        }
    }
}
