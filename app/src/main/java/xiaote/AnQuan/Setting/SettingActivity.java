package xiaote.AnQuan.Setting;

import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import xiaote.AnQuan.BaseActivity;
import xiaote.AnQuan.SwipeBackHelper;
import xiaote.xtui.XtDialog;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * 设置。
 *
 * 目前只做密码设置，密码支持两种模式：
 *   · 数字密码（默认）—— 存 SHA-256 哈希，验证走 PasswordLockActivity；
 *   · 系统验证     —— 复用设备锁屏凭据（PIN / 图案 / 密码 / 指纹），
 *                     走 KeyguardManager.createConfirmDeviceCredentialIntent。
 *
 * 两种模式的读写全部收在 SystemAuthHelper，本页只负责界面与交互。
 * 切到系统验证时会先检查设备是否设过锁屏，没设直接拦下并提示。
 *
 * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟。
 */
public class SettingActivity extends BaseActivity {

    private TextView modeTip;
    private TextView stateTip;
    private XtUi.XtSwitch enabledSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, "设置"));
        XtUi.applyStatusBar(this);

        // ============ 密码锁 ============
        LinearLayout cardPwd = XtUi.cardWithSection(this, "密码锁");

        enabledSwitch = XtUi.switchView(this, SystemAuthHelper.isEnabled(this));
        enabledSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(XtUi.XtSwitch v, boolean checked) {
                SystemAuthHelper.setEnabled(SettingActivity.this, checked);
                refreshState();
                XtToast.show(SettingActivity.this, checked ? "已启用密码锁" : "已关闭密码锁");
            }
        });
        cardPwd.addView(XtUi.row(this, "启用密码锁", enabledSwitch));
        cardPwd.addView(XtUi.tip(this, "关闭后打开应用不再验证；已设置的密码会保留"));

        modeTip = new TextView(this);
        modeTip.setTextSize(13);
        modeTip.setTextColor(XtUi.primary);
        modeTip.setPadding(0, XtUi.dp(this, 12), 0, XtUi.dp(this, 4));
        cardPwd.addView(modeTip);

        XtUi.addButton(cardPwd, this, "选择验证方式", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showModeDialog(); }
        });

        XtUi.addButton(cardPwd, this, "设置数字密码", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showSetNumericDialog(); }
        });

        XtUi.addButton(cardPwd, this, "清除数字密码", new View.OnClickListener() {
            @Override
            public void onClick(View v) { confirmClearNumeric(); }
        });

        stateTip = new TextView(this);
        stateTip.setTextSize(12);
        stateTip.setTextColor(XtUi.colorMuted(this));
        stateTip.setLineSpacing(0, 1.3f);
        stateTip.setPadding(0, XtUi.dp(this, 10), 0, 0);
        cardPwd.addView(stateTip);

        root.addView(cardPwd);

        // ============ 说明 ============
        LinearLayout cardNote = XtUi.cardWithSection(this, "说明");
        TextView note = XtUi.tip(this,
                "数字密码：本应用自己存 SHA-256 哈希，与系统锁屏无关。\n"
                        + "系统验证：直接复用设备锁屏凭据（PIN / 图案 / 密码 / 指纹），"
                        + "不用另记一套密码；前提是设备已设置锁屏。\n\n"
                        + "安全提示：数字密码以哈希形式存在本地 dot_config，"
                        + "不上传任何服务器；遗忘后只能清除应用数据重来。");
        note.setLineSpacing(0, 1.4f);
        cardNote.addView(note);
        root.addView(cardNote);

        setContentView(scrollView);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);

        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    // ==================== 状态刷新 ====================

    private void refreshState() {
        boolean system = SystemAuthHelper.isSystemMode(this);
        if (modeTip != null) {
            modeTip.setText(system ? "当前验证方式：系统验证" : "当前验证方式：数字密码");
        }
        if (stateTip != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("密码锁：").append(SystemAuthHelper.isEnabled(this) ? "已启用" : "已关闭");
            sb.append("\n数字密码：").append(SystemAuthHelper.hasNumericPassword(this) ? "已设置" : "未设置");
            sb.append("\n系统验证可用：").append(SystemAuthHelper.isSystemAvailable(this) ? "是" : "否（设备未设锁屏）");
            stateTip.setText(sb.toString());
        }
        if (enabledSwitch != null) {
            enabledSwitch.setChecked(SystemAuthHelper.isEnabled(this));
        }
    }

    // ==================== 验证方式 ====================

    private void showModeDialog() {
        final String[] items = {
                "数字密码（本应用自己存密码）",
                "系统验证（复用设备锁屏凭据）"
        };
        final String[] values = {
                SystemAuthHelper.TYPE_NUMERIC,
                SystemAuthHelper.TYPE_SYSTEM
        };
        int checked = SystemAuthHelper.isSystemMode(this) ? 1 : 0;
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("选择验证方式")
                .setSingleChoiceItems(items, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (SystemAuthHelper.TYPE_SYSTEM.equals(values[which])) {
                            applySystemMode();
                        } else {
                            SystemAuthHelper.setType(SettingActivity.this, SystemAuthHelper.TYPE_NUMERIC);
                            SystemAuthHelper.setEnabled(SettingActivity.this, true);
                            refreshState();
                            XtToast.show(SettingActivity.this, "已切换为数字密码");
                            if (!SystemAuthHelper.hasNumericPassword(SettingActivity.this)) {
                                showSetNumericDialog();
                            }
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    private void applySystemMode() {
        if (!SystemAuthHelper.isSystemAvailable(this)) {
            XtDialog.show(this, "无法使用系统验证",
                    "当前设备没有设置锁屏密码、PIN、图案或指纹。\n\n"
                            + "请先到系统设置里设置锁屏，再回来选择系统验证。");
            return;
        }
        SystemAuthHelper.setType(this, SystemAuthHelper.TYPE_SYSTEM);
        refreshState();
        XtToast.showLong(this, "已切换为系统验证");
    }

    // ==================== 数字密码 ====================

    private void showSetNumericDialog() {
        final EditText input = XtDialog.input(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("请输入新密码（至少 4 位）");

        android.app.AlertDialog dlg = XtDialog.inputBuilder(this,
                        "设置数字密码",
                        "以后打开星特安全都需要输入此密码",
                        input)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String pwd = input.getText().toString().trim();
                        if (pwd.length() < 4) {
                            XtToast.show(SettingActivity.this, "密码至少 4 位");
                            return;
                        }
                        SystemAuthHelper.setNumericPasswordHash(SettingActivity.this,
                                SystemAuthHelper.hash(pwd));
                        // 设完密码顺带把模式切回数字密码，避免用户设了却仍在系统验证模式
                        SystemAuthHelper.setType(SettingActivity.this, SystemAuthHelper.TYPE_NUMERIC);
                        SystemAuthHelper.setEnabled(SettingActivity.this, true);
                        refreshState();
                        XtToast.show(SettingActivity.this, "密码已设置");
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    private void confirmClearNumeric() {
        if (!SystemAuthHelper.hasNumericPassword(this)) {
            XtToast.show(this, "当前未设置数字密码");
            return;
        }
        XtDialog.show(this, "清除数字密码",
                "清除后若仍处于数字密码模式，打开应用将不再需要验证。\n\n确定继续？",
                "清除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        SystemAuthHelper.clearNumericPassword(SettingActivity.this);
                        refreshState();
                        XtToast.show(SettingActivity.this, "数字密码已清除");
                    }
                },
                "取消", null);
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
