package xiaote.AnQuan;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import rikka.shizuku.Shizuku;
import xiaote.xtui.XtDialog;
import xiaote.xtui.XtToast;
import xiaote.xtui.XtUi;

/**
 * 主页。
 *
 * 按 xtui 规范重写：
 *   · 骨架走 XtUi.column / XtUi.scroll，标题 XtUi.title，状态栏同色；
 *   · 每个功能分区一张细描边卡片，小标题在卡片内（20sp 主题色居中）；
 *   · 所有入口按钮走 XtUi.addButton；
 *   · 所有弹窗走 XtDialog（含整窗圆角底板与 0.9→1.0 缩放），
 *     需要 setCancelable(false) 的用 builder + tint 手动收尾；
 *   · 所有轻提示走 XtToast。
 *
 * 业务逻辑（Shizuku 授权、状态采集、密码锁、算数题验证、更新检测、
 * 免责声明、H5 分流、后台服务拉起）全部保留，只换 UI 层。
 */
public class MainActivity extends BaseActivity {

    private static final int SHIZUKU_REQUEST_CODE = 10086;
    private SharedPreferences prefs;
    private boolean mathVerifyShowing = false;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
        new Shizuku.OnRequestPermissionResultListener() {
            @Override
            public void onRequestPermissionResult(int requestCode, int grantResult) {
                if (requestCode == SHIZUKU_REQUEST_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        XtToast.show(MainActivity.this,
                                "Shizuku " + getString(R.string.toast_shizuku_ok));
                    }
                }
            }
        };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Shizuku.addRequestPermissionResultListener(permissionListener);
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        // 界面模式分流：ui_mode=h5 时交给 H5 壳，原生界面不构建。默认 native。
        if ("h5".equals(prefs.getString("ui_mode", "native"))) {
            try { Shizuku.removeRequestPermissionResultListener(permissionListener); } catch (Throwable ignored) {}
            try { startActivity(new Intent(this, H5MainActivity.class)); } catch (Throwable ignored) {}
            finish();
            return;
        }

        // 拉起核心常驻服务（不依赖无障碍：音量阈值防护 / 阻止卸载周期任务 /
        // 无障碍掉线兜底 / 时间回拨检测 / 反向唤醒助手）。
        // 多入口之一，App / BootReceiver / 闹钟 / 助手唤醒广播也会拉起。
        try { XTSafeCoreService.ensureStarted(this); } catch (Throwable ignored) {}

        LinearLayout root = XtUi.column(this);
        ScrollView scrollView = XtUi.scroll(this, root);

        root.addView(XtUi.title(this, getString(R.string.main_title)));
        XtUi.applyStatusBar(this);

        // ============ 运行状态 ============
        // 采集项：Shizuku / Root / 无障碍 / 通知权限 / 无障碍保活 / 专版 Shizuku。
        // 其中 Root 探测要走 su、专版 Shizuku 查询要走 ContentResolver.call
        // （未运行时内部最多等 1.2 秒），都是阻塞调用，放在 onCreate 主线程会卡开屏。
        // 这里先挂占位文本，交给后台线程采集，完成后回主线程一次性刷新。
        LinearLayout cardStatus = XtUi.card(this);
        final TextView statusCard = XtUi.tip(this, "运行状态检查中…");
        statusCard.setTextSize(11);
        statusCard.setLineSpacing(0, 1.3f);
        cardStatus.addView(statusCard);
        TextView statusHint = XtUi.tip(this, "点击本卡片刷新状态");
        statusHint.setTextSize(10);
        statusHint.setPadding(0, XtUi.dp(this, 6), 0, 0);
        cardStatus.addView(statusHint);
        // 点击整张状态卡即重新采集一次，不必退出重进
        cardStatus.setClickable(true);
        cardStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                statusCard.setText("运行状态检查中…");
                refreshStatusAsync(statusCard);
                XtToast.show(MainActivity.this, "已刷新状态");
            }
        });
        XtUi.attachScale(cardStatus);
        root.addView(cardStatus);
        refreshStatusAsync(statusCard);

        // ============ 必看 ============
        LinearLayout cardMust = XtUi.cardWithSection(this, getString(R.string.section_must));
        XtUi.addButton(cardMust, this, getString(R.string.btn_must_read), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWarningDialog();
            }
        });
        XtUi.addButton(cardMust, this, getString(R.string.btn_usage_guide), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, UsageGuideActivity.class));
            }
        });
        root.addView(cardMust);

        // ============ 授权 ============
        LinearLayout cardAuth = XtUi.cardWithSection(this, getString(R.string.section_auth));
        XtUi.addButton(cardAuth, this, "权限管理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, xiaote.AnQuan.PermissionManager.Activity.class));
            }
        });
        XtUi.addButton(cardAuth, this, "权限增强", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, xiaote.AnQuan.PermissionManager.EnhanceActivity.class));
            }
        });
        root.addView(cardAuth);

        // ============ 功能 ============
        LinearLayout cardFunc = XtUi.cardWithSection(this, getString(R.string.section_func));
        XtUi.addButton(cardFunc, this, getString(R.string.btn_protection), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ProtectionSettingsActivity.class));
            }
        });
        XtUi.addButton(cardFunc, this, getString(R.string.btn_float_settings), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, FloatingButtonSettingsActivity.class));
            }
        });
        XtUi.addButton(cardFunc, this, "保活增强", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, KeepAliveActivity.class));
            }
        });
        XtUi.addButton(cardFunc, this, "名单管理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ListManagerActivity.class));
            }
        });
        XtUi.addButton(cardFunc, this, getString(R.string.btn_security_scan), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SecurityScanActivity.class));
            }
        });
        XtUi.addButton(cardFunc, this, "更多小工具", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, MoreToolsActivity.class));
            }
        });
        root.addView(cardFunc);

        // ============ 应用设置 ============
        LinearLayout cardApp = XtUi.cardWithSection(this, "应用设置");
        XtUi.addButton(cardApp, this, "设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, xiaote.AnQuan.Setting.SettingActivity.class));
            }
        });
        XtUi.addButton(cardApp, this, "切换到 H5 界面", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showH5ExperimentalDialog();
            }
        });
        root.addView(cardApp);

        // ============ 关于应用 ============
        LinearLayout cardAbout = XtUi.cardWithSection(this, getString(R.string.section_about));
        XtUi.addButton(cardAbout, this, getString(R.string.btn_blog), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://xiaote.data.blog/xtsafe")));
                } catch (Throwable ignored) {}
            }
        });
        XtUi.addButton(cardAbout, this, getString(R.string.btn_opensource), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xiaote-xt/xtsafe")));
                } catch (Throwable ignored) {}
            }
        });
        XtUi.addButton(cardAbout, this, getString(R.string.btn_update_log), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showUpdateLog();
            }
        });
        XtUi.addButton(cardAbout, this, getString(R.string.btn_check_update), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate();
            }
        });
        root.addView(cardAbout);

        // 底部提示
        TextView footer = new TextView(this);
        footer.setText(R.string.XINGTE);
        footer.setTextColor(XtUi.colorMuted(this));
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, XtUi.dp(this, 24), 0, XtUi.dp(this, 10));
        root.addView(footer);

        setContentView(scrollView);

        // 申请通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2000);
            } catch (Exception e) {}
        }

        // 适配预测性返回手势（Android 13+）
        root.post(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= 33) {
                    try {
                        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                                new android.window.OnBackInvokedCallback() {
                                    @Override
                                    public void onBackInvoked() { finish(); }
                                });
                    } catch (Exception e) {}
                }
            }
        });

        // 左滑返回
        SwipeBackHelper.attach(this);

        // 首次运行：免责声明（与使用须知分开记录，各自只弹一次）
        if (!prefs.getBoolean("has_seen_disclaimer", false)) {
            android.app.AlertDialog d = XtDialog.builder(MainActivity.this, "免责声明",
                            "1. 本应用为设备安全辅助工具，按「现状」提供，不对任何直接或间接损失承担责任。\n\n"
                                    + "2. 应用内的病毒名单、风险规则、拦截判定均为启发式判断，存在误报与漏报可能，不能替代专业安全软件，也无法保证设备绝对不被锁机、被卸载、被提权。\n\n"
                                    + "3. 冻结、停用、强制停止、阻止卸载等功能会修改系统状态，操作前请自行确认目标应用，误操作导致的后果由使用者自行承担。\n\n"
                                    + "4. 涉及提权（Shizuku / Dhizuku / Device Owner / Root）的操作，请确认你了解其含义；因提权造成的系统异常、数据丢失，本应用不负责。\n\n"
                                    + "5. 继续使用即表示你已阅读并同意以上内容。")
                    .setPositiveButton("我已阅读并同意", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            prefs.edit().putBoolean("has_seen_disclaimer", true).apply();
                        }
                    })
                    .setNegativeButton("退出", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            finishAffinity();
                        }
                    })
                    .create();
            d.setCancelable(false);
            d.show();
            XtDialog.tint(d);
        }

        // 首次运行显示使用须知
        if (!prefs.getBoolean("has_seen_warning", false)) {
            android.app.AlertDialog d = XtDialog.builder(MainActivity.this,
                            getString(R.string.warning_title), getString(R.string.warning_body))
                    .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            prefs.edit().putBoolean("has_seen_warning", true).apply();
                            if (!isPasswordSet()) {
                                showSetPasswordDialog();
                            }
                        }
                    })
                    .create();
            d.setCancelable(false);
            d.show();
            XtDialog.tint(d);
        } else {
            // 已经看过，正常走密码设置提醒
            if (!isPasswordSet() && !prefs.getBoolean("skip_password_setup", false)) {
                showSetPasswordDialog();
            }
        }

        // 检查算数题验证
        if (getIntent().getBooleanExtra("show_math_verify", false)) {
            getWindow().getDecorView().post(new Runnable() {
                @Override
                public void run() {
                    showMathVerifyDialog();
                }
            });
        }

        // 自动请求 Shizuku 权限
        if (Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        }
        // 自动提权开启无障碍（应用启动时自动恢复）
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            try {
                String component = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
                String cmd1 = "settings put secure enabled_accessibility_services '" + component + "'";
                String cmd2 = "settings put secure accessibility_enabled 1";
                Shizuku.newProcess(new String[]{"sh", "-c", cmd1 + " && " + cmd2}, null, null);
                // 静默开启，不打扰用户
            } catch (Exception ignored) {}
        }

        // 特殊日子纪念弹窗
        SpecialDayChecker.check(this);

        // 版本号含 test/debug 时提示测试版
        checkDebugBuild();

        // 专版 Shizuku 签名校验：不是正版就提示去网盘下载
        checkStellarSignature();
    }

    // ==================== 专版 Shizuku 签名校验 ====================

    /**
     * 校验专版 Shizuku（Stellar）是否为预期签名。
     *
     * 只在「未安装」或「装了但签名 MD5 不匹配」时弹窗提醒：
     * 恶意应用可以顶着同名包名混进来，靠 Shizuku 通道执行特权命令，
     * 所以必须比对签名而不是只看包名是否可见。
     *
     * 取签名与路径都是跨进程调用，整体丢到工作线程，避免卡开屏。
     * 用户勾过「不再提示」后不再打扰。
     */
    private void checkStellarSignature() {
        try {
            if (prefs.getBoolean(KEY_STELLAR_WARN_OFF, false)) return;
        } catch (Throwable ignored) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok;
                try {
                    ok = isStellarGenuine();
                } catch (Throwable t) {
                    return;
                }
                if (ok) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        showStellarWarnDialog();
                    }
                });
            }
        }, "stellar-sign-check").start();
    }

    /**
     * 专版 Shizuku 是否已安装且签名匹配。
     *
     * 未安装、取不到 apk 路径、签名读取失败，一律返回 false ——
     * 宁可多提示一次，也不要因为查询异常放过一个被顶替的包。
     */
    private boolean isStellarGenuine() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(STELLAR_PACKAGE, 0);
            String path = (pi != null && pi.applicationInfo != null)
                    ? pi.applicationInfo.sourceDir : null;
            if (path == null || path.isEmpty()) return false;
            String md5 = SecurityScanActivity.getSignatureMd5(getPackageManager(), path);
            return STELLAR_SIGN_MD5.equalsIgnoreCase(md5);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 专版 Shizuku 校验未通过提示。
     *
     * 按钮从左到右：不再提示 / 下载 / 确定。
     * AlertDialog 三按钮的实际排布就是 neutral → negative → positive，
     * 所以这里对应 setNeutralButton / setNegativeButton / setPositiveButton。
     *
     * 「不再提示」只写本地偏好，不影响任何功能，也不阻止用户之后手动去下载。
     */
    private void showStellarWarnDialog() {
        android.app.AlertDialog dlg = XtDialog.builder(MainActivity.this, "专版 Shizuku 校验未通过",
                        "专版Shizuku未安装，恶意应用可能通过Shizuku执行恶意命令\n\n"
                                + "点击下载前往网盘下载，下载后检查签名MD5是否为"
                                + STELLAR_SIGN_MD5 + "，接着激活\n"
                                + "下载密码：" + STELLAR_DOWNLOAD_PWD)
                .setNeutralButton("不再提示", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putBoolean(KEY_STELLAR_WARN_OFF, true).apply();
                    }
                })
                .setNegativeButton("下载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(STELLAR_DOWNLOAD_URL)));
                        } catch (Throwable t) {
                            XtToast.show(MainActivity.this, "无法打开链接");
                        }
                    }
                })
                .setPositiveButton("确定", null)
                .create();
        dlg.show();
        XtDialog.tint(dlg);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("show_math_verify", false)) {
            getWindow().getDecorView().post(new Runnable() {
                @Override
                public void run() {
                    showMathVerifyDialog();
                }
            });
        }
    }

    /**
     * 版本号包含 test 或 debug（不区分大小写）时，弹窗提示为测试版。
     * 文案用字面量，避免 AIDE 对新增字符串资源的索引延迟。
     */
    private void checkDebugBuild() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (v == null) return;
            String lower = v.toLowerCase();
            if (lower.contains("test") || lower.contains("debug")) {
                XtDialog.show(MainActivity.this, "测试版提示",
                        "当前版本为测试版，可能存在不稳定和未知bug");
            }
        } catch (Exception ignored) {}
    }

    // ==================== 算数题验证 ====================

    private String toMixedNumber(int n) {
        if (n < 1 || n > 20) return String.valueOf(n);
        String[] circled = {"①","②","③","④","⑤","⑥","⑦","⑧","⑨","⑩","⓫","⓬","⓭","⓮","⓯","⓰","⓱","⓲","⓳","⓴"};
        String[] fullwidth = {"０","１","２","３","４","５","６","７","８","９","１０","１１","１２","１３","１４","１５","１６","１７","１８","１９","２０"};
        String[] chinese = {"一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七","十八","十九","二十"};
        int idx = n - 1;
        String[] styles = { String.valueOf(n), circled[idx], fullwidth[idx], chinese[idx] };
        return styles[(int) (Math.random() * styles.length)];
    }

    private String toMixedOperator() {
        String[] ops = {"+", "➕", "＋", "﹢", "⁺"};
        return ops[(int) (Math.random() * ops.length)];
    }

    private void showMathVerifyDialog() {
        if (mathVerifyShowing) return;
        mathVerifyShowing = true;
        final int a = (int) (Math.random() * 20) + 1;
        final int b = (int) (Math.random() * 20) + 1;
        final int answer = a + b;

        String displayA = toMixedNumber(a);
        String displayB = toMixedNumber(b);
        String displayOp = toMixedOperator();
        String displayEq = (int) (Math.random() * 2) == 0 ? "=" : "＝";

        final EditText input = XtDialog.input(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.input_result);

        android.app.AlertDialog dlg = XtDialog.inputBuilder(this,
                        getString(R.string.math_verify_title),
                        getString(R.string.math_verify_msg, displayA, displayOp, displayB, displayEq),
                        input)
                .setPositiveButton(R.string.confirm_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String text = input.getText().toString().trim();
                        try {
                            int userAnswer = Integer.parseInt(text);
                            if (userAnswer == answer) {
                                long unlockTime = System.currentTimeMillis() + 60000;
                                prefs.edit().putLong("admin_unlock_time", unlockTime).apply();
                                dialog.dismiss();
                                XtToast.showLong(MainActivity.this, R.string.math_verify_ok);
                            } else {
                                XtToast.show(MainActivity.this, R.string.math_verify_wrong);
                                input.setText("");
                                input.requestFocus();
                            }
                        } catch (NumberFormatException e) {
                            XtToast.show(MainActivity.this, R.string.enter_valid_number);
                            input.setText("");
                            input.requestFocus();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        mathVerifyShowing = false;
                    }
                })
                .create();
        dlg.setCancelable(false);
        dlg.show();
        XtDialog.tint(dlg);
    }

    // ==================== 密码管理 ====================

    private boolean isPasswordSet() {
        return prefs.contains("app_password_hash");
    }

    private String hashPassword(String password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void showSetPasswordDialog() {
        final EditText input = XtDialog.input(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(getString(R.string.set_password_hint));

        android.app.AlertDialog pwdDlg = XtDialog.inputBuilder(this,
                        getString(R.string.set_password_title),
                        getString(R.string.set_password_msg),
                        input)
                .setPositiveButton(R.string.confirm_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pwd = input.getText().toString().trim();
                        if (pwd.length() < 4) {
                            XtToast.show(MainActivity.this, R.string.password_too_short);
                            showSetPasswordDialog();
                            return;
                        }
                        prefs.edit().putString("app_password_hash", hashPassword(pwd)).apply();
                        prefs.edit().putLong("unlock_until", System.currentTimeMillis() + 5000).apply();
                        XtToast.show(MainActivity.this, R.string.password_set);
                    }
                })
                .setNegativeButton(R.string.skip, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        XtToast.showLong(MainActivity.this, R.string.suggest_set_password);
                    }
                })
                .setNeutralButton(R.string.no_more_prompt, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putBoolean("skip_password_setup", true).apply();
                        XtToast.show(MainActivity.this, R.string.password_prompt_off);
                    }
                })
                .create();
        pwdDlg.setCancelable(false);
        pwdDlg.show();
        XtDialog.tint(pwdDlg);
    }

    private void showEnterPasswordDialog(final Runnable onSuccess) {
        final EditText input = XtDialog.input(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(getString(R.string.enter_password_hint));

        android.app.AlertDialog enterDlg = XtDialog.inputBuilder(this,
                        getString(R.string.verify_password_title),
                        getString(R.string.enter_password),
                        input)
                .setPositiveButton(R.string.confirm_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pwd = input.getText().toString().trim();
                        String saved = prefs.getString("app_password_hash", "");
                        if (hashPassword(pwd).equals(saved)) {
                            if (onSuccess != null) onSuccess.run();
                        } else {
                            XtToast.show(MainActivity.this, R.string.password_wrong);
                            showEnterPasswordDialog(onSuccess);
                        }
                    }
                })
                .setNegativeButton(R.string.exit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finishAffinity();
                    }
                })
                .create();
        enterDlg.setCancelable(false);
        enterDlg.show();
        XtDialog.tint(enterDlg);
    }

    // ==================== 无障碍 / Root ====================

    private void enableAccessibilityByShell() {
        String component = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
        String cmd1 = "settings put secure enabled_accessibility_services '" + component + "'";
        String cmd2 = "settings put secure accessibility_enabled 1";
        boolean done = false;

        if (Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            try {
                Shizuku.newProcess(new String[]{"sh", "-c", cmd1 + " && " + cmd2}, null, null);
                done = true;
            } catch (Exception e) {}
        }

        if (!done && isRootAvailable()) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd1 + " && " + cmd2});
                p.waitFor();
                done = true;
            } catch (Exception e) {}
        }

        if (done) {
            XtToast.showLong(MainActivity.this, R.string.acc_enabled_check);
        } else {
            XtToast.show(MainActivity.this, R.string.need_shizuku_root);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { Shizuku.removeRequestPermissionResultListener(permissionListener); } catch (Throwable ignored) {}
    }

    private boolean isRootAvailable() {
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            br.close();
            p.destroy();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 运行状态采集 ====================

    /** 无障碍组件两种写法，与 XTSafeCoreService / XTSafeMainService 保持一致 */
    private static final String ACC_COMPONENT_STD = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
    private static final String ACC_COMPONENT_SHORT = "xiaote.AnQuan/.XTSafeMainService";
    /** 专版 Shizuku（Stellar）状态查询 authority */
    private static final String STELLAR_AUTHORITY = "content://roro.stellar.manager.status";
    /** 专版 Shizuku（Stellar）包名 */
    private static final String STELLAR_PACKAGE = "roro.stellar.manager";
    /** 专版 Shizuku 的预期签名 MD5 */
    private static final String STELLAR_SIGN_MD5 = "259f172bcb9692517ee05ca7f589d635";
    /** 「不再提示」偏好键 */
    private static final String KEY_STELLAR_WARN_OFF = "stellar_warn_off";
    /** 专版 Shizuku 下载地址与网盘密码 */
    private static final String STELLAR_DOWNLOAD_URL = "https://xiaote1.lanzouw.com/b016l5h36b";
    private static final String STELLAR_DOWNLOAD_PWD = "xiaote";

    /**
     * 异步采集运行状态并回填到 target。
     *
     * 采集项：Shizuku / Root / 无障碍 / 通知权限 / 无障碍保活 / 专版 Shizuku。
     * 其中 Root 探测要起 su 进程、专版 Shizuku 查询走 ContentResolver.call
     * （其内部在管理器刚被拉起时最多等 1.2 秒），都是阻塞调用；
     * 放在 onCreate 主线程会卡开屏，所以整体丢到工作线程，采完回主线程一次性写文本。
     */
    private void refreshStatusAsync(final TextView target) {
        if (target == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder sb = new StringBuilder();
                try {
                    sb.append("Shizuku: ").append(shizukuStatusText());
                    sb.append("\nRoot: ").append(isRootAvailable()
                            ? getString(R.string.root_authorized)
                            : getString(R.string.root_unauthorized));
                    sb.append("\n无障碍: ").append(isAccessibilityOn() ? "已开启" : "未开启");
                    sb.append("\n通知权限: ").append(notificationStatusText());
                    sb.append("\n无障碍保活: ").append(isKeepAliveOn() ? "已开启" : "未开启");
                    sb.append("\n专版 Shizuku: ").append(stellarStatusText());
                    sb.append("\n保活助手: ").append(helperStatusText());
                } catch (Throwable ignored) {}
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || target == null) return;
                        target.setText(sb.toString());
                    }
                });
            }
        }, "main-status").start();
    }

    /** Shizuku 状态文案 */
    private String shizukuStatusText() {
        try {
            if (!Shizuku.pingBinder()) return getString(R.string.toast_shizuku_norun);
            return (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                    ? getString(R.string.toast_shizuku_ok) : "未授权";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /**
     * 保活助手状态文案。
     *
     * 四种情况分开表述，便于一眼看出该做什么：
     *   未开启保活开关  —— 说明功能被关，不误导为「未安装」；
     *   未安装          —— 需要开启开关后自动安装；
     *   装了但不是正版  —— 包名对但签名不符，提示可能被顶替；
     *   正常            —— 带上当前唤醒间隔。
     */
    private String helperStatusText() {
        try {
            if (!HelperWakeSender.isEnabled(this)) return "未开启保活";
            if (!HelperWakeSender.isHelperInstalled(this)) return "未安装";
            if (!HelperWakeSender.isGenuineHelperInstalled(this)) return "签名异常（非正版）";
            return "已安装（间隔 " + formatInterval(HelperWakeSender.readInterval(this)) + "）";
        } catch (Throwable t) {
            return "未知";
        }
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

    /** 通知权限文案（Android 13 以下无需单独申请） */
    private String notificationStatusText() {
        try {
            if (Build.VERSION.SDK_INT < 33) return "无需授权";
            return (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED) ? "已授权" : "未授权";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** 本应用无障碍服务是否在系统启用列表中 */
    private boolean isAccessibilityOn() {
        try {
            String list = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return false;
            for (String item : list.split(":")) {
                if (item.equals(ACC_COMPONENT_STD) || item.equals(ACC_COMPONENT_SHORT)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 无障碍保活开关状态（与核心服务共用同一份偏好） */
    private boolean isKeepAliveOn() {
        try {
            return prefs.getBoolean(XTSafeCoreService.KEY_ACC_KEEP_ALIVE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 专版 Shizuku（Stellar）是否在运行。
     *
     * 通过 ContentResolver.call 查询其公开状态 provider，无需权限，
     * 也无需在 Manifest 里声明 queries。未安装或未运行时 provider 不可达，
     * call 会抛异常，统一按「未运行」处理；返回内容只有运行状态与身份，
     * 不涉及敏感信息。
     */
    private String stellarStatusText() {
        try {
            android.os.Bundle reply = getContentResolver().call(
                    Uri.parse(STELLAR_AUTHORITY), "isRunning", null, null);
            if (reply == null) return "未安装";
            if (!reply.getBoolean("running", false)) return "未运行";

            int uid = reply.getInt("uid", -1);
            String kind;
            if (uid == 0) kind = "Root";
            else if (uid == 2000) kind = "ADB";
            else if (uid > 0) kind = "uid " + uid;
            else kind = "未知身份";

            String vn = null;
            try { vn = reply.getString("versionName"); } catch (Throwable ignored) {}
            return "运行中（" + kind + (vn != null && !vn.isEmpty() ? " " + vn : "") + "）";
        } catch (Throwable t) {
            return "未运行";
        }
    }

    private void showWarningDialog() {
        XtDialog.show(MainActivity.this, getString(R.string.warning_title),
                getString(R.string.warning_body), getString(R.string.got_it), null);
    }

    /**
     * H5 界面为实验性功能：切换前先提示风险，用户确认后再写入 ui_mode 并进入。
     */
    private void showH5ExperimentalDialog() {
        XtDialog.show(this, "实验性功能",
                "H5 界面仍在开发中，属于实验性功能，可能存在以下情况：\n\n"
                        + "1. 部分页面功能尚未完善或与原生界面存在差异\n"
                        + "2. 个别机型上可能出现显示异常或卡顿\n"
                        + "3. 后续版本中界面结构可能调整\n\n"
                        + "如需返回原生界面，可在 H5 主页的「界面」区域切换回原生。",
                "仍然切换", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putString("ui_mode", "h5").apply();
                        try {
                            startActivity(new Intent(MainActivity.this, H5MainActivity.class));
                        } catch (Throwable ignored) {}
                        finish();
                    }
                },
                getString(R.string.cancel), null);
    }

    // ==================== 更新日志 ====================

    private volatile boolean isSettingDeviceOwner = false;

    /** 应用内更新日志查看器（跳转新 Activity 显示） */
    private void showUpdateLog() {
        UpdateLogActivity.start(this);
    }

    /** 更新检测：拉取日志全文，解析出数值最大的版本号与当前版本比对 */
    private void checkUpdate() {
        final String urlStr = "https://raw.githubusercontent.com/xiaote-XT/xtsafe/refs/heads/main/Update-Log.txt";
        XtToast.show(this, R.string.checking_update);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String remoteVersion = null;
                try {
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        InputStream is = conn.getInputStream();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        String line;
                        StringBuilder log = new StringBuilder();
                        while ((line = reader.readLine()) != null) {
                            log.append(line).append("\n");
                            // 匹配版本行，如 v2.16 / V2.10 / 2.16
                            String t = line.trim();
                            String ver = null;
                            if (t.matches("[vV]?\\d+(\\.\\d+)+")) {
                                ver = t.replaceFirst("^[vV]", "").trim();
                            }
                            if (ver != null) {
                                if (remoteVersion == null || compareVersions(ver, remoteVersion) > 0) {
                                    remoteVersion = ver;
                                }
                            }
                        }
                        reader.close();
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    remoteVersion = null;
                }
                final String latest = remoteVersion;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (latest == null) {
                            XtToast.showLong(MainActivity.this, R.string.check_update_fail);
                            return;
                        }
                        String currentVersion = "";
                        try {
                            currentVersion = getPackageManager()
                                    .getPackageInfo(getPackageName(), 0).versionName;
                        } catch (Exception e) {}
                        if (currentVersion == null) currentVersion = "";
                        String cur = currentVersion.replaceFirst("^[vV]", "").trim();
                        boolean isNew = compareVersions(latest, cur) > 0;
                        if (isNew) {
                            XtDialog.show(MainActivity.this,
                                    getString(R.string.new_version_found),
                                    getString(R.string.latest_version, latest, currentVersion),
                                    getString(R.string.view_log), new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            showUpdateLog();
                                        }
                                    },
                                    getString(R.string.ignore), null);
                        } else {
                            XtToast.showLong(MainActivity.this,
                                    getString(R.string.already_latest, currentVersion));
                        }
                    }
                });
            }
        }).start();
    }

    /** 比较两个版本号字符串，a>b 返回正数，a<b 返回负数，相等返回0 */
    private int compareVersions(String a, String b) {
        try {
            String[] pa = a.trim().replaceFirst("^[vV]", "").split("\\.");
            String[] pb = b.trim().replaceFirst("^[vV]", "").split("\\.");
            int len = Math.max(pa.length, pb.length);
            for (int i = 0; i < len; i++) {
                int na = i < pa.length ? Integer.parseInt(pa[i].trim()) : 0;
                int nb = i < pb.length ? Integer.parseInt(pb[i].trim()) : 0;
                if (na != nb) return na - nb;
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
