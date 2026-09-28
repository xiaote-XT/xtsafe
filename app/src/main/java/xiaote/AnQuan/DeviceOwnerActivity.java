package xiaote.AnQuan;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/**
 * Device Owner 管理页。
 *
 * 三块功能：
 *   1. 授权：用 Shizuku 执行 dpm set-device-owner，把星特安全设为设备所有者
 *   2. 取消授权：dpm remove-active-admin 移除设备所有者身份
 *   3. 禁止卸载星特安全：Device Owner 身份下调用
 *      DevicePolicyManager.setUninstallBlocked(admin, pkg, true)
 *
 * 为什么需要 Device Owner：
 *   setUninstallBlocked 只允许 Device Owner 调用。星特安全自己成为
 *   Device Owner 后，就能用公开 API 直接把自己设为不可卸载，
 *   不依赖任何第三方提权框架。
 *
 * 前提：设备上当前不能已有其他 Device Owner（同一时刻只能有一个）。
 *       若已被其他应用占用，需先在系统设置里解除，或恢复出厂。
 *
 * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟。
 */
public class DeviceOwnerActivity extends BaseActivity {

    private DevicePolicyManager dpm;
    private ComponentName admin;

    private TextView statusView;
    private TextView detailView;
    private Button grantBtn;
    private Button revokeBtn;
    private Switch blockSwitch;
    private TextView blockHint;

    private boolean settingSwitch = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, DeviceAdmin.class);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 24, 30, 30);

        TextView title = new TextView(this);
        title.setText("Device Owner 管理");
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 10, 0, 4);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("把星特安全设为设备所有者后，可直接把自己设为不可卸载。\n"
                + "需先授权 Shizuku，且设备当前无其他 Device Owner。");
        tip.setTextColor(getSecondaryTextColor());
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 0, 0, 20);
        root.addView(tip);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(getSecondaryTextColor());
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(16, 8, 16, 8);
        root.addView(statusView);

        detailView = new TextView(this);
        detailView.setTextSize(11);
        detailView.setTextColor(getSecondaryTextColor());
        detailView.setPadding(16, 4, 16, 16);
        detailView.setTextIsSelectable(true);
        root.addView(detailView);

        grantBtn = new Button(this);
        grantBtn.setText("授权：设为 Device Owner");
        grantBtn.setTextSize(14);
        grantBtn.setAllCaps(false);
        grantBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { confirmGrant(); }
        });
        root.addView(grantBtn);

        revokeBtn = new Button(this);
        revokeBtn.setText("取消授权：移除 Device Owner");
        revokeBtn.setTextSize(14);
        revokeBtn.setAllCaps(false);
        revokeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { confirmRevoke(); }
        });
        root.addView(revokeBtn);

        blockSwitch = new Switch(this);
        blockSwitch.setText("禁止卸载星特安全");
        blockSwitch.setTextSize(14);
        blockSwitch.setTextColor(getTextColor());
        blockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (settingSwitch) return;
                applyUninstallBlock(isChecked);
            }
        });
        root.addView(blockSwitch);

        blockHint = new TextView(this);
        blockHint.setTextSize(11);
        blockHint.setTextColor(getSecondaryTextColor());
        blockHint.setPadding(dpToPx(12), 4, dpToPx(12), 12);
        blockHint.setText("需先成为 Device Owner 才能开启");
        root.addView(blockHint);

        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新状态");
        refreshBtn.setTextSize(14);
        refreshBtn.setAllCaps(false);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { refresh(); }
        });
        root.addView(refreshBtn);

        Button backBtn = new Button(this);
        backBtn.setText("完成");
        backBtn.setTextSize(14);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-1, -2);
        backLp.topMargin = 24;
        root.addView(backBtn, backLp);

        scrollView.addView(root);
        setContentView(scrollView);

        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        SwipeBackHelper.attach(this);
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
            color = Color.argb(255, 60, 160, 80);
        } else if (isAdmin) {
            status = "仅设备管理员，尚未提权为 Device Owner";
            color = Color.argb(255, 200, 120, 30);
        } else {
            status = "未授权（设备管理员未激活）";
            color = getSecondaryTextColor();
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
        new android.app.AlertDialog.Builder(this)
                .setTitle("设为 Device Owner")
                .setMessage("将执行：\n"
                        + "dpm set-device-owner " + getPackageName() + "/.DeviceAdmin\n\n"
                        + "前提：\n"
                        + "1. 已授权 Shizuku\n"
                        + "2. 设备上当前没有其他 Device Owner\n"
                        + "3. 未添加任何账户（部分 ROM 要求）\n\n"
                        + "确定继续？")
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) { doGrant(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doGrant() {
        Toast.makeText(this, "正在授权…", Toast.LENGTH_SHORT).show();
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
                        new android.app.AlertDialog.Builder(DeviceOwnerActivity.this)
                                .setTitle(ok ? "授权成功" : "授权失败")
                                .setMessage(out)
                                .setPositiveButton("确定", null)
                                .show();
                        refresh();
                    }
                });
            }
        }, "device-owner-grant").start();
    }

    // ==================== 取消授权 ====================

    private void confirmRevoke() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("移除 Device Owner")
                .setMessage("将调用 DevicePolicyManager.clearDeviceOwnerApp()"
                        + "放弃设备所有者身份。\n\n"
                        + "注意：不能用 dpm remove-active-admin —— AOSP 只允许移除"
                        + "test-only 管理员，对正常 Device Owner 会抛 SecurityException。\n\n"
                        + "移除后星特安全不再具有 Device Owner 权限，禁止卸载开关会一并失效。\n\n"
                        + "确定继续？")
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) { doRevoke(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 放弃 Device Owner 身份。
     *
     * 不能用 dpm remove-active-admin：AOSP 的 forceRemoveActiveAdmin 只接受
     * test-only 管理员（APK 声明 android:testOnly="true"），对正常 Device Owner
     * 必然抛 SecurityException("Attempt to remove non-test admin")。
     *
     * 正确路径是应用自清：Device Owner 自己调 clearDeviceOwnerApp()，
     * 这是系统唯一认可的 owner 主动放弃身份的方式，不需要 Shizuku。
     */
    private void doRevoke() {
        Toast.makeText(this, "正在移除…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                boolean cleared = false;
                boolean adminRemoved = false;
                // 先解除禁止卸载，避免残留状态影响后续卸载
                try { dpm.setUninstallBlocked(admin, getPackageName(), false); }
                catch (Throwable ignored) {}

                // 步骤一：若是 Device Owner，先自清 owner 身份。
                // clearDeviceOwnerApp 是 owner 主动放弃身份的唯一系统认可路径，
                // 不需要 Shizuku；dpm remove-active-admin 那条路对正常 owner
                // 会被 AOSP 拒绝（只接受 test-only admin）。
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
                    cleared = true;  // 本来就不是 owner，无需自清
                }

                // 步骤二：移除设备管理员身份。
                // removeActiveAdmin 对自身的 admin 不需要任何特权，
                // 任何应用都能移除自己注册的 DeviceAdminReceiver。
                try {
                    if (dpm.isAdminActive(admin)) {
                        dpm.removeActiveAdmin(admin);
                        adminRemoved = true;
                    } else {
                        adminRemoved = true;  // 本就不是 admin
                    }
                } catch (Throwable t) {
                    String s = "removeActiveAdmin: " + t.getClass().getSimpleName()
                            + " " + t.getMessage();
                    err = (err == null ? s : err + " | " + s);
                }

                // 步骤三：仅当仍残留 owner 身份时才用 shell 兜底
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
                        new android.app.AlertDialog.Builder(DeviceOwnerActivity.this)
                                .setTitle(fCleared ? "已移除" : "移除失败")
                                .setMessage(sb.toString())
                                .setPositiveButton("确定", null)
                                .show();
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
                            Toast.makeText(DeviceOwnerActivity.this,
                                    "设置失败: " + fErr, Toast.LENGTH_LONG).show();
                            revertSwitch(blocked);
                        } else if (fActual != null && fActual != blocked) {
                            Toast.makeText(DeviceOwnerActivity.this,
                                    "设置未生效，实际状态: " + (fActual ? "已开启" : "未开启"),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(DeviceOwnerActivity.this,
                                    blocked ? "已禁止卸载星特安全" : "已允许卸载星特安全",
                                    Toast.LENGTH_SHORT).show();
                        }
                        refresh();
                    }
                });
            }
        }, "device-owner-block").start();
    }

    // ==================== 工具 ====================

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
