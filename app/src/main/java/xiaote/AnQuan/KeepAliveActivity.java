package xiaote.AnQuan;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import xiaote.xtui.XtUi;
import xiaote.xtui.XtToast;

/**
 * 保活增强。
 *
 * 集中查看与配置「星特安全 ↔ 保活助手」这条互相拉起链路：
 *   1. 保活总开关：开启后每次启动自动释放内置 APK 并安装助手（需 Shizuku），
 *      同时恢复双向心跳；关闭后只停止唤起助手，不卸载、不停对方服务。
 *   2. 运行状态：核心服务、助手是否安装、当前间隔。
 *   3. 唤醒间隔：0.1 ~ 60 秒，默认 1 秒，拖动即下发。
 *   4. 操作：立即安装助手 / 立即唤醒助手 / 打开助手界面 / 忽略省电优化。
 *
 * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟。
 */
public class KeepAliveActivity extends BaseActivity {

    private SharedPreferences prefs;

    private TextView selfStatusTip;
    private TextView helperStatusTip;
    private TextView intervalTip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, "保活增强"));
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this,
                "星特安全与「星特安全保活助手」互相定期发唤醒广播。"
                        + "被强行停止后，只要其中一方还活着，就能把对方重新拉起。");
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, XtUi.dp(this, 16));
        root.addView(tip);

        // ============ 总开关卡片 ============
        LinearLayout cardSwitch = XtUi.cardWithSection(this, "保活开关");

        XtUi.XtSwitch sw = XtUi.switchView(this, HelperWakeSender.isEnabled(this));
        sw.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(XtUi.XtSwitch v, boolean checked) {
                HelperWakeSender.setEnabled(KeepAliveActivity.this, checked);
                if (checked) {
                    // 开启：先尝试自动安装（助手不存在时），再立刻下发间隔
                    HelperInstaller.installIfNeededAsync(KeepAliveActivity.this);
                    HelperWakeSender.sendInterval(KeepAliveActivity.this,
                            HelperWakeSender.readInterval(KeepAliveActivity.this));
                    XtToast.show(KeepAliveActivity.this, "已开启保活");
                } else {
                    // 关闭：只停止唤起，不卸载、不停对方服务
                    XtToast.show(KeepAliveActivity.this, "已关闭保活，不再唤起助手");
                }
                refreshStatus();
            }
        });
        cardSwitch.addView(XtUi.row(this, "启用保活（互相拉起）", sw));

        TextView switchTip = XtUi.tip(this,
                "开启后：每次启动检查助手是否存在，缺失则自动装好并恢复双向心跳。\n"
                        + "关闭后：仅停止唤起助手，不卸载助手、不停止它自己的服务。");
        cardSwitch.addView(switchTip);
        root.addView(cardSwitch);

        // ============ 状态卡片 ============
        LinearLayout cardStatus = XtUi.cardWithSection(this, "运行状态");

        selfStatusTip = XtUi.body(this, "");
        selfStatusTip.setTextSize(13);
        cardStatus.addView(selfStatusTip);

        helperStatusTip = XtUi.body(this, "");
        helperStatusTip.setTextSize(13);
        helperStatusTip.setPadding(0, XtUi.dp(this, 6), 0, 0);
        cardStatus.addView(helperStatusTip);

        root.addView(cardStatus);

        // ============ 唤醒间隔卡片 ============
        LinearLayout cardInterval = XtUi.cardWithSection(this, "唤醒间隔");

        TextView intervalDesc = XtUi.tip(this,
                "助手每隔多久向星特安全发一次唤醒广播。间隔越短越不容易被系统"
                        + "彻底按停，但耗电略高；默认 1 秒。");
        cardInterval.addView(intervalDesc);

        intervalTip = new TextView(this);
        intervalTip.setTextSize(13);
        intervalTip.setTextColor(XtUi.primary);
        intervalTip.setPadding(0, XtUi.dp(this, 8), 0, 0);
        cardInterval.addView(intervalTip);

        // 滑块步长 100ms：范围 0.1 ~ 60 秒
        SeekBar seek = XtUi.slider(this,
                (int) ((HelperWakeSender.MAX_INTERVAL_MS - HelperWakeSender.MIN_INTERVAL_MS) / 100L),
                (int) ((HelperWakeSender.readInterval(this) - HelperWakeSender.MIN_INTERVAL_MS) / 100L));
        seek.setPadding(XtUi.dp(this, 12), XtUi.dp(this, 8), XtUi.dp(this, 12), XtUi.dp(this, 4));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long ms = HelperWakeSender.MIN_INTERVAL_MS + (long) progress * 100L;
                HelperWakeSender.sendInterval(KeepAliveActivity.this, ms);
                refreshIntervalTip();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        cardInterval.addView(seek);

        root.addView(cardInterval);

        // ============ 操作卡片 ============
        LinearLayout cardOps = XtUi.cardWithSection(this, "操作");

        XtUi.addButton(cardOps, this, "立即安装助手", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (HelperWakeSender.isHelperInstalled(KeepAliveActivity.this)) {
                    XtToast.show(KeepAliveActivity.this, "助手已安装");
                    return;
                }
                new Thread(new Runnable() {
                    @Override public void run() {
                        final boolean ok = HelperInstaller.installIfNeeded(KeepAliveActivity.this);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                XtToast.showLong(KeepAliveActivity.this,
                                        ok ? "助手安装完成" : "安装失败，请确认已授权 Shizuku 或 Root");
                                refreshStatus();
                            }
                        });
                    }
                }, "xt-helper-install-manual").start();
            }
        });

        XtUi.addButton(cardOps, this, "立即唤醒助手", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!HelperWakeSender.isHelperInstalled(KeepAliveActivity.this)) {
                    XtToast.showLong(KeepAliveActivity.this, "未安装保活助手");
                    return;
                }
                HelperWakeSender.wakeHelperNow(KeepAliveActivity.this);
                XtToast.show(KeepAliveActivity.this, "已发送唤醒广播");
                selfStatusTip.postDelayed(new Runnable() {
                    @Override public void run() { refreshStatus(); }
                }, 800L);
            }
        });

        XtUi.addButton(cardOps, this, "打开助手界面", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!HelperWakeSender.isHelperInstalled(KeepAliveActivity.this)) {
                    XtToast.showLong(KeepAliveActivity.this, "未安装保活助手");
                    return;
                }
                HelperWakeSender.openHelper(KeepAliveActivity.this);
            }
        });

        XtUi.addButton(cardOps, this, "忽略省电优化", new View.OnClickListener() {
            @Override public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        // 星特安全自身与助手一起放开：两者互为心跳，缺一不可
                        PowerSaveHelper.applyBoth(KeepAliveActivity.this);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                XtToast.showLong(KeepAliveActivity.this,
                                        "已尝试放开省电限制（需 Shizuku 或 Root）");
                            }
                        });
                    }
                }, "xt-powersave").start();
            }
        });

        root.addView(cardOps);

        // ============ 说明卡片 ============
        LinearLayout cardNote = XtUi.cardWithSection(this, "说明");
        TextView note = XtUi.tip(this,
                "1. 首次开启时会自动把助手准备好，装不上不影响其他功能；\n"
                        + "2. 助手需要在后台常驻，建议加入电池白名单；\n"
                        + "3. 助手已去掉桌面图标，只能从本页打开；\n"
                        + "4. 两边都被强行停止时无法自动恢复，需要手动打开任意一个，"
                        + "之后双向心跳会自动接管；\n"
                        + "5. 「忽略省电优化」会把星特安全与助手一起加入电池白名单，"
                        + "需 Shizuku 或 Root，国产 ROM 自家的省电策略仍需手动放行。");
        note.setLineSpacing(0, 1.4f);
        cardNote.addView(note);
        root.addView(cardNote);

        setContentView(scrollView);
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshIntervalTip();
    }

    private void refreshStatus() {
        if (selfStatusTip != null) {
            boolean enabled = HelperWakeSender.isEnabled(this);
            boolean core = isCoreServiceRunning();
            selfStatusTip.setText("保活开关：" + (enabled ? "已开启" : "已关闭")
                    + "\n星特安全核心服务：" + (core ? "运行中" : "未运行"));
            selfStatusTip.setTextColor(enabled ? 0xFF3CA050 : XtUi.colorMuted(this));
        }
        if (helperStatusTip != null) {
            boolean installed = HelperWakeSender.isHelperInstalled(this);
            if (!installed) {
                helperStatusTip.setText("保活助手：未安装（开启开关后会自动尝试安装）");
                helperStatusTip.setTextColor(XtUi.colorMuted(this));
            } else {
                helperStatusTip.setText("保活助手：已安装（间隔 "
                        + formatInterval(HelperWakeSender.readInterval(this)) + "）");
                helperStatusTip.setTextColor(XtUi.colorText(this));
            }
        }
    }

    private void refreshIntervalTip() {
        if (intervalTip == null) return;
        intervalTip.setText("当前间隔：" + formatInterval(HelperWakeSender.readInterval(this)));
    }

    /** 毫秒格式化成「X 秒」/「X.X 秒」/「X 分 Y 秒」 */
    private static String formatInterval(long ms) {
        if (ms < 60000L) {
            float s = ms / 1000f;
            if (s == (int) s) return ((int) s) + " 秒";
            return String.format(java.util.Locale.US, "%.1f 秒", s);
        }
        long min = ms / 60000L;
        long rest = (ms % 60000L) / 1000L;
        return rest == 0 ? (min + " 分钟") : (min + " 分 " + rest + " 秒");
    }

    /**
     * 核心服务是否在跑。
     *
     * 没有直接查询 Service 状态的公开 API，这里用「拉起一次」间接判断：
     * ensureStarted 是幂等的，服务已在跑时不会叠加循环，成本可忽略。
     */
    private boolean isCoreServiceRunning() {
        try {
            XTSafeCoreService.ensureStarted(this);
            return true;
        } catch (Throwable t) {
            return false;
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
