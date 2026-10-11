package xiaote.AnQuan;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import rikka.shizuku.Shizuku;
import xiaote.xtui.XtDialog;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * Device Owner 管理页。
 *
 * 三块功能：
 *   1. 授权：用 Shizuku 执行 dpm set-device-owner，把星特安全设为设备所有者
 *   2. 取消授权：clearDeviceOwnerApp 移除设备所有者身份
 *   3. 禁止卸载星特安全：Device Owner 身份下调用
 *      DevicePolicyManager.setUninstallBlocked(admin, pkg, true)
 *
 * 为什么需要 Device Owner：
 *   setUninstallBlocked 只允许 Device Owner 调用。星特安全自己成为
 *   Device Owner 后，就能用公开 API 直接把自己设为不可卸载，
 *   不依赖任何第三方提权框架。
 *
 * 前提：设备上当前不能已有其他 Device Owner。
 *
 * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟。
 */
public class DeviceOwnerActivity extends BaseActivity {

    private DevicePolicyManager dpm;
    private ComponentName admin;

    private TextView statusView;
    private TextView detailView;
    private TextView grantBtn;
    private TextView revokeBtn;
    private XtUi.XtSwitch blockSwitch;
    private TextView blockHint;

    private boolean settingSwitch = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 界面模式分流：ui_mode=h5 时交给 H5 壳，原生界面不构建。默认 native。
        if ("h5".equals(getSharedPreferences("dot_config", MODE_PRIVATE)
                .getString("ui_mode", "native"))) {
            try { startActivity(new android.content.Intent(this, H5DeviceOwnerActivity.class)); } catch (Throwable ignored) {}
            finish();
            return;
        }

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, DeviceAdmin.class);

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, "Device Owner 管理"));
        XtUi.applyStatusBar(this);

        TextView tip = XtUi.tip(this,
                "把星特安全设为设备所有者后，可直接把自己设为不可卸载。\n"
                        + "需先授权 Shizuku，且设备当前无其他 Device Owner。");
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, XtUi.dp(this, 16));
        root.addView(tip);

        // ============ 状态卡片 ============
        LinearLayout cardStatus = XtUi.cardWithSection(this, "运行状态");

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(XtUi.colorMuted(this));
        statusView.setGravity(Gravity.CENTER);
        cardStatus.addView(statusView);

        detailView = new TextView(this);
        detailView.setTextSize(11);
        detailView.setTextColor(XtUi.colorMuted(this));
        detailView.setTextIsSelectable(true);
        detailView.setPadding(0, XtUi.dp(this, 8), 0, 0);
        cardStatus.addView(detailView);

        root.addView(cardStatus);

        // ============ 授权卡片 ============
        LinearLayout cardAuth = XtUi.cardWithSection(this, "授权");

        grantBtn = XtUi.button(this, "授权：设为 Device Owner", new View.OnClickListener() {
            @Override
            public void onClick(View v) { confirmGrant(); }
        });
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(-1, -2);
        gLp.topMargin = XtUi.dp(this, 4);
        cardAuth.addView(grantBtn, gLp);

        revokeBtn = XtUi.button(this, "取消授权：移除 Device Owner", new View.OnClickListener() {
            @Override
            public void onClick(View v) { confirmRevoke(); }
        });
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
        rLp.topMargin = XtUi.dp(this, 8);
        cardAuth.addView(revokeBtn, rLp);

        root.addView(cardAuth);

        // ============ 禁止卸载卡片 ============
        LinearLayout cardBlock = XtUi.cardWithSection(this, "禁止卸载");

        blockSwitch = XtUi.switchView(this, false);
        blockSwitch.setOnCheckedChangeListener(new XtUi.XtSwitch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(XtUi.XtSwitch buttonView, boolean isChecked) {
                if (settingSwitch) return;
                applyUninstallBlock(isChecked);
            }
        });
        cardBlock.addView(XtUi.row(this, "禁止卸载星特安全", blockSwitch));

        blockHint = new TextView(this);
        blockHint.setTextSize(11);
        blockHint.setTextColor(XtUi.colorMuted(this));
        blockHint.setPadding(0, XtUi.dp(this, 4), 0, 0);
        blockHint.setText("需先成为 Device Owner 才能开启");
        cardBlock.addView(blockHint);

        root.addView(cardBlock);

        // ============ 操作卡片 ============
        LinearLayout cardOps = XtUi.cardWithSection(this, "操作");
        XtUi.addButton(cardOps, this, "刷新状态", new View.OnClickListener() {
            @Override
            public void onClick(View v) { refresh(); }
        });
        root.addView(cardOps);

        setContentView(scrollView);

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
        XtUi.attachBack(this);
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

    // ==================== Shizuku 执行 ====================

    private boolean shizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 同步执行一条 shell 命令，返回 exit 码与输出；失败返回原因文本 */
    private String runShizuku(String cmd) {
        if (!shizukuReady()) return "Shizuku 未授权";
        try {
            Process p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader out = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = out.readLine()) != null) sb.append(line).append('\n');
            java.io.BufferedReader err = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream()));
            while ((line = err.readLine()) != null) sb.append(line).append('\n');
            int code = p.waitFor();
            String s = sb.toString().trim();
            return "exit=" + code + (s.isEmpty() ? "" : "\n" + s);
        } catch (Throwable t) {
            return "异常: " + t.getClass().getSimpleName() + " " + t.getMessage();
        }
    }

    // ==================== 状态刷新 ====================

    private void refresh() {
        statusView.setText("正在检查...");
        statusView.setTextColor(XtUi.colorMuted(this));
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean isAdmin = false;
                boolean isOwner = false;
                Boolean blocked = null;
                try { isAdmin = dpm.isAdminActive(admin); } catch (Throwable ignored) {}
                try { isOwner = dpm.isDeviceOwnerApp(getPackageName()); } catch (Throwable ignored) {}
                if (isOwner) {
                    try { blocked = dpm.isUninstallBlocked(admin, getPackageName()); }
                    catch (Throwable ignored) {}
                }
                final boolean fAdmin = isAdmin;
                final boolean fOwner = isOwner;
                final Boolean fBlocked = blocked;
                final boolean fShizuku = shizukuReady();

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        applyUi(fAdmin, fOwner, fBlocked, fShizuku);
                    }
                });
            }
        }, "device-owner-check").start();
    }

    private void applyUi(boolean isAdmin, boolean isOwner, Boolean blocked, boolean shizukuOk) {
        String status;
        int color;
        if (isOwner) {
            status = "已是 Device Owner";
            color = 0xFF3CA050;
        } else if (isAdmin) {
            status = "仅设备管理员，尚未提权为 Device Owner";
            color = 0xFFC8781E;
        } else {
            status = "未授权（设备管理员未激活）";
            color = XtUi.colorMuted(this);
        }
        statusView.setText(status);
        statusView.setTextColor(color);

        StringBuilder sb = new StringBuilder();
        sb.append("包名: ").append(getPackageName());
        sb.append("\n管理员组件: ").append(admin.flattenToShortString());
        sb.append("\n管理员已激活: ").append(isAdmin);
        sb.append("\nDevice Owner: ").append(isOwner);
        sb.append("\nShizuku: ").append(shizukuOk ? "已授权" : "未授权");
        if (isOwner) {
            sb.append("\n禁止卸载: ").append(blocked == null ? "未知" : (blocked ? "已开启" : "未开启"));
        }
        detailView.setText(sb.toString());

        grantBtn.setEnabled(shizukuOk && !isOwner);
        grantBtn.setText(isOwner ? "已是 Device Owner" : "授权：设为 Device Owner");
        revokeBtn.setEnabled(shizukuOk && (isOwner || isAdmin));

        settingSwitch = true;
        try {
            blockSwitch.setChecked(isOwner && blocked != null && blocked);
            blockSwitch.setEnabled(isOwner);
            if (!isOwner) {
                blockHint.setText("需先成为 Device Owner 才能开启");
            } else if (blocked != null && blocked) {
                blockHint.setText("已禁止卸载，普通方式无法卸载星特安全；需先在本页关闭");
            } else {
                blockHint.setText("开启后普通方式无法卸载星特安全");
            }
        } finally {
            settingSwitch = false;
        }
    }

    private void revertSwitch(boolean current) {
        settingSwitch = true;
        try { blockSwitch.setChecked(!current); } finally { settingSwitch = false; }
    }

    // ==================== 授权 ====================

    private void confirmGrant() {
        XtDialog.show(this, "设为 Device Owner",
                "将执行：\n"
                        + "dpm set-device-owner " + getPackageName() + "/.DeviceAdmin\n\n"
                        + "前提：\n"
                        + "1. 已授权 Shizuku\n"
                        + "2. 设备上当前没有其他 Device Owner\n"
                        + "3. 未添加任何账户（部分 ROM 要求）\n\n"
                        + "确定继续？",
                "确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) { doGrant(); }
                },
                "取消", null);
    }

    private void doGrant() {
        XtToast.show(this, "正在授权…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String out = runShizuku(
                        "dpm set-device-owner " + getPackageName() + "/.DeviceAdmin");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        boolean ok = out != null && out.startsWith("exit=0");
                        XtDialog.show(DeviceOwnerActivity.this,
                                ok ? "授权成功" : "授权失败", out);
                        refresh();
                    }
                });
            }
        }, "device-owner-grant").start();
    }

    // ==================== 取消授权 ====================

    private void confirmRevoke() {
        XtDialog.show(this, "移除 Device Owner",
                "将调用 DevicePolicyManager.clearDeviceOwnerApp()"
                        + "放弃设备所有者身份。\n\n"
                        + "注意：不能用 dpm remove-active-admin —— AOSP 只允许移除"
                        + "test-only 管理员，对正常 Device Owner 会抛 SecurityException。\n\n"
                        + "移除后星特安全不再具有 Device Owner 权限，禁止卸载开关会一并失效。\n\n"
                        + "确定继续？",
                "确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) { doRevoke(); }
                },
                "取消", null);
    }

    /**
     * 放弃 Device Owner 身份。
     *
     * 不能用 dpm remove-active-admin：AOSP 的 forceRemoveActiveAdmin 只接受
     * test-only 管理员，对正常 Device Owner 必然抛 SecurityException。
     *
     * 正确路径是应用自清：Device Owner 自己调 clearDeviceOwnerApp()，
     * 这是系统唯一认可的 owner 主动放弃身份的方式，不需要 Shizuku。
     */
    private void doRevoke() {
        XtToast.show(this, "正在移除…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                boolean cleared = false;
                boolean adminRemoved = false;
                // 先解除禁止卸载，避免残留状态影响后续卸载
                try { dpm.setUninstallBlocked(admin, getPackageName(), false); }
                catch (Throwable ignored) {}

                boolean isOwnerNow = false;
                try { isOwnerNow = dpm.isDeviceOwnerApp(getPackageName()); }
                catch (Throwable ignored) {}
                if (isOwnerNow) {
                    try {
                        dpm.clearDeviceOwnerApp(getPackageName());
                        cleared = true;
                    } catch (Throwable t) {
                        err = "clearDeviceOwnerApp: " + t.getClass().getSimpleName()
                                + " " + t.getMessage();
                    }
                } else {
                    cleared = true;
                }

                try {
                    if (dpm.isAdminActive(admin)) {
                        dpm.removeActiveAdmin(admin);
                        adminRemoved = true;
                    } else {
                        adminRemoved = true;
                    }
                } catch (Throwable t) {
                    String s = "removeActiveAdmin: " + t.getClass().getSimpleName()
                            + " " + t.getMessage();
                    err = (err == null ? s : err + " | " + s);
                }

                boolean stillOwner = false;
                try { stillOwner = dpm.isDeviceOwnerApp(getPackageName()); }
                catch (Throwable ignored) {}
                String fallback = null;
                if (stillOwner && shizukuReady()) {
                    fallback = runShizuku(
                            "dpm remove-active-admin " + getPackageName() + "/.DeviceAdmin");
                }

                final boolean fCleared = cleared;
                final boolean fAdminRemoved = adminRemoved;
                final String fErr = err;
                final String fFallback = fallback;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        StringBuilder sb = new StringBuilder();
                        sb.append(fCleared
                                ? "clearDeviceOwnerApp() 已调用，Device Owner 身份已放弃"
                                : "clearDeviceOwnerApp() 失败");
                        if (fErr != null) sb.append("\n").append(fErr);
                        if (fFallback != null) {
                            sb.append("\n\nShell 兜底: \n").append(fFallback);
                        }
                        XtDialog.show(DeviceOwnerActivity.this,
                                fCleared ? "已移除" : "移除失败", sb.toString());
                        refresh();
                    }
                });
            }
        }, "device-owner-revoke").start();
    }

    // ==================== 禁止卸载 ====================

    private void applyUninstallBlock(final boolean blocked) {
        final String pkg = getPackageName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                boolean ok = false;
                try {
                    dpm.setUninstallBlocked(admin, pkg, blocked);
                    ok = true;
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                Boolean actual = null;
                if (ok) {
                    try { actual = dpm.isUninstallBlocked(admin, pkg); } catch (Throwable ignored) {}
                }
                final boolean fOk = ok;
                final String fErr = err;
                final Boolean fActual = actual;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (!fOk) {
                            XtToast.showLong(DeviceOwnerActivity.this, "设置失败: " + fErr);
                            revertSwitch(blocked);
                        } else if (fActual != null && fActual != blocked) {
                            XtToast.showLong(DeviceOwnerActivity.this,
                                    "设置未生效，实际状态: " + (fActual ? "已开启" : "未开启"));
                        } else {
                            XtToast.show(DeviceOwnerActivity.this,
                                    blocked ? "已禁止卸载星特安全" : "已允许卸载星特安全");
                        }
                        refresh();
                    }
                });
            }
        }, "device-owner-block").start();
    }
}
