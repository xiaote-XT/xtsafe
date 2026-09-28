package xiaote.AnQuan;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import xiaote.dhizukutool.Dhizuku;
import xiaote.dhizukutool.DhizukuDpm;
import xiaote.dhizukutool.DhizukuRequestPermissionListener;

/**
 * Dhizuku 增强。
 *
 * 页面职责：
 *   1. 显示 Dhizuku 连接与授权状态
 *   2. 未授权时拉起 Dhizuku 授权页
 *   3. 已授权时提供「禁止卸载星特安全」开关
 *
 * 底层走 xiaote.dhizukutool 手写的 AIDL 客户端，不依赖 Dhizuku-API AAR。
 *
 * 防卸载开关的实现：把系统 DevicePolicyManager 的 binder 包成
 * DhizukuBinderWrapper 后调用 setUninstallBlocked，从而以 Dhizuku 的
 * 设备所有者身份生效。admin 参数必须是 Dhizuku 自己的 owner 组件，
 * 传本应用自己的 DeviceAdmin 会被 system_server 拒绝。
 *
 * 开关状态以回读为准，不靠本地记忆。回读失败（blocked == null）时不置灰，
 * 只改提示文字——一次查询失败不该把功能锁死；真正决定可点性的是
 * 「已授权 + 系统支持该接口」。
 *
 * 继承 Activity 而非 BaseActivity：授权过程中会跳转到 Dhizuku 的
 * 授权页，本页 onStop 后 activityCount 归零，回来自动弹密码锁会打断
 * 授权流程，因此这里不带密码锁。
 *
 * 文案全部字面量，避免 AIDE 对新增字符串资源的索引延迟。
 */
public class DhizukuActivity extends Activity {

    private TextView statusView;
    private TextView detailView;
    private Button actionBtn;
    private Switch blockSwitch;
    private TextView blockHint;

    /** 正在用代码改写开关状态，避免触发监听回调 */
    private boolean settingSwitch = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 24, 30, 30);

        TextView title = new TextView(this);
        title.setText("Dhizuku 增强");
        title.setTextSize(20);
        title.setTextColor(getTextColor());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 10, 0, 4);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("Dhizuku 是设备所有者（Device Owner）提权方案，授权后星特安全可以设备所有者身份调用系统特权接口");
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

        actionBtn = new Button(this);
        actionBtn.setTextSize(14);
        actionBtn.setAllCaps(false);
        actionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermission();
            }
        });
        root.addView(actionBtn);

        // ============ 禁止卸载开关 ============
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
        blockHint.setText("需先完成 Dhizuku 授权");
        root.addView(blockHint);

        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新状态");
        refreshBtn.setTextSize(14);
        refreshBtn.setAllCaps(false);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        root.addView(refreshBtn);

        Button backBtn = new Button(this);
        backBtn.setText("完成");
        backBtn.setTextSize(14);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
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
                                public void onBackInvoked() {
                                    finish();
                                }
                            });
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ==================== 状态刷新 ====================

    /**
     * 后台线程查询（ContentResolver + Binder 跨进程），结果回主线程刷 UI。
     *
     * 防卸载状态只在「已授权 + 系统支持该 transact 码」时才查询，
     * 避免在未授权时做无意义的跨进程调用。
     */
    private void refresh() {
        statusView.setText("正在检查...");
        statusView.setTextColor(getSecondaryTextColor());
        actionBtn.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean initOk = false;
                boolean granted = false;
                boolean supported = false;
                boolean exempt = false;
                String version = null;
                String owner = null;
                String err = null;
                String diag = null;
                Boolean blocked = null;

                try {
                    initOk = Dhizuku.init(DhizukuActivity.this);
                    if (!initOk) {
                        err = "Dhizuku 未安装，或未设为 Device Owner";
                    }
                } catch (Throwable t) {
                    err = "init 异常: " + t.getClass().getSimpleName()
                            + " " + t.getMessage();
                }

                try {
                    exempt = DhizukuDpm.isHiddenApiExempt();
                } catch (Throwable ignored) {
                }

                if (initOk) {
                    try {
                        granted = Dhizuku.isPermissionGranted();
                    } catch (Throwable t) {
                        err = "查询权限异常: " + t.getClass().getSimpleName()
                                + " " + t.getMessage();
                    }
                    try {
                        owner = Dhizuku.getOwnerPackageName();
                    } catch (Throwable ignored) {
                    }
                    if (granted) {
                        try {
                            version = Dhizuku.getVersionName();
                        } catch (Throwable ignored) {
                        }
                        try {
                            supported = DhizukuDpm.isUninstallBlockSupported(DhizukuActivity.this);
                        } catch (Throwable ignored) {
                        }
                        // 不支持时把反射失败的具体原因带出来：
                        // 是「类拿不到」「字段名不同」还是「一个 TRANSACTION 字段都没枚举到」
                        if (!supported) {
                            try {
                                diag = DhizukuDpm.getReflectDiagnostic();
                            } catch (Throwable ignored) {
                            }
                        }
                        if (supported) {
                            try {
                                blocked = DhizukuDpm.isUninstallBlocked(
                                        DhizukuActivity.this,
                                        DhizukuActivity.this.getPackageName());
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }

                final boolean fInit = initOk;
                final boolean fGranted = granted;
                final boolean fSupported = supported;
                final boolean fExempt = exempt;
                final String fVersion = version;
                final String fOwner = owner;
                final String fErr = err;
                final String fDiag = diag;
                final Boolean fBlocked = blocked;

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        applyUi(fInit, fGranted, fSupported, fExempt, fVersion,
                                fOwner, fErr, fDiag, fBlocked);
                    }
                });
            }
        }, "dhizuku-check").start();
    }

    private void applyUi(boolean initOk, boolean granted, boolean supported,
                         boolean exempt, String version, String owner,
                         String err, String diag, Boolean blocked) {
        String status;
        int color;
        if (!initOk) {
            status = "Dhizuku 不可用";
            color = Color.argb(255, 200, 120, 30);
        } else if (!granted) {
            status = "Dhizuku 已就绪，权限未授权";
            color = Color.argb(255, 200, 120, 30);
        } else {
            status = "Dhizuku 权限已授权";
            color = Color.argb(255, 60, 160, 80);
        }
        statusView.setText(status);
        statusView.setTextColor(color);

        StringBuilder sb = new StringBuilder();
        sb.append("初始化: ").append(initOk);
        if (owner != null && !owner.isEmpty()) {
            sb.append("\nOwner 包名: ").append(owner);
        }
        if (version != null && !version.isEmpty()) {
            sb.append("\nDhizuku 版本: ").append(version);
        }
        sb.append("\nAPI 权限: ").append(granted ? "已授权" : "未授权");
        sb.append("\nHiddenAPI 豁免: ").append(exempt ? "成功" : "失败或未执行");
        if (granted) {
            sb.append("\n防卸载支持: ").append(supported ? "是" : "否");
            if (supported) {
                sb.append("\n当前防卸载: ")
                        .append(blocked == null ? "未知" : (blocked ? "已开启" : "未开启"));
            } else if (diag != null && !diag.isEmpty()) {
                sb.append("\n不支持原因: ").append(diag);
            }
        }
        if (err != null) {
            sb.append("\n说明: ").append(err);
        }
        detailView.setText(sb.toString());

        if (!initOk) {
            actionBtn.setText("Dhizuku 不可用");
            actionBtn.setEnabled(false);
        } else if (!granted) {
            actionBtn.setText("申请 Dhizuku 权限");
            actionBtn.setEnabled(true);
        } else {
            actionBtn.setText("已授权，无需操作");
            actionBtn.setEnabled(false);
        }

        // 开关：只要系统支持就允许拨动。未授权 / 不支持时置灰并说明原因，
        // 回读状态未知（blocked == null）也不置灰，避免一次查询失败就把功能锁死。
        settingSwitch = true;
        try {
            if (blocked != null) {
                blockSwitch.setChecked(blocked);
            }
            boolean canUse = granted && supported;
            blockSwitch.setEnabled(canUse);
            if (!initOk) {
                blockHint.setText("需先安装 Dhizuku 并设为 Device Owner");
            } else if (!granted) {
                blockHint.setText("需先完成 Dhizuku 授权");
            } else if (!supported) {
                blockHint.setText("未读到 setUninstallBlocked 的 transact 码"
                        + (diag == null || diag.isEmpty() ? "" : "，详见上方说明"));
            } else if (blocked == null) {
                blockHint.setText("回读状态失败，拨动开关可尝试设置");
            } else if (blocked) {
                blockHint.setText("已禁止卸载，普通方式无法卸载星特安全；需先在本页关闭");
            } else {
                blockHint.setText("开启后普通方式无法卸载星特安全");
            }
        } finally {
            settingSwitch = false;
        }
    }

    /** 回滚开关勾选（不触发监听） */
    private void revertSwitch(boolean current) {
        settingSwitch = true;
        try { blockSwitch.setChecked(!current); } finally { settingSwitch = false; }
    }

    // ==================== 禁止卸载 ====================

    /**
     * 应用禁止卸载设置。
     *
     * 走 DhizukuDpm.setUninstallBlocked，内部把 DPM 的 binder
     * 包成 Dhizuku 代理后以设备所有者身份调用。
     * 成功后回读确认，避免界面显示与实际不符。
     */
    private void applyUninstallBlock(final boolean blocked) {
        final String pkg = getPackageName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String err = null;
                Boolean actual = null;
                try {
                    ok = DhizukuDpm.setUninstallBlocked(
                            DhizukuActivity.this, pkg, blocked);
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                if (ok) {
                    try {
                        actual = DhizukuDpm.isUninstallBlocked(
                                DhizukuActivity.this, pkg);
                    } catch (Throwable ignored) {
                    }
                }

                final boolean fOk = ok;
                final String fErr = err;
                final Boolean fActual = actual;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (!fOk) {
                            String msg = "设置失败";
                            if (fErr != null && !fErr.isEmpty()) msg += ": " + fErr;
                            Toast.makeText(DhizukuActivity.this, msg,
                                    Toast.LENGTH_LONG).show();
                            revertSwitch(blocked);
                        } else if (fActual != null && fActual != blocked) {
                            Toast.makeText(DhizukuActivity.this,
                                    "设置未生效，实际状态: " + (fActual ? "已开启" : "未开启"),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(DhizukuActivity.this,
                                    blocked ? "已禁止卸载星特安全" : "已允许卸载星特安全",
                                    Toast.LENGTH_SHORT).show();
                        }
                        refresh();
                    }
                });
            }
        }, "dhizuku-block").start();
    }

    // ==================== 授权 ====================

    /** 拉起 Dhizuku 授权页，结果通过 listener 回调 */
    private void requestPermission() {
        try {
            Dhizuku.requestPermission(new DhizukuRequestPermissionListener() {
                @Override
                public void onRequestPermission(final int requestCode) throws RemoteException {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            if (requestCode == 0) {
                                Toast.makeText(DhizukuActivity.this,
                                        "Dhizuku 权限已授予", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(DhizukuActivity.this,
                                        "Dhizuku 授权未通过 (" + requestCode + ")",
                                        Toast.LENGTH_SHORT).show();
                            }
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    refresh();
                                }
                            }, 600L);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Toast.makeText(this, "无法拉起授权页: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
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
