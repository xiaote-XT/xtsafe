package xiaote.AnQuan;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.widget.EditText;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import rikka.shizuku.Shizuku;

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
					Toast.makeText(MainActivity.this, "Shizuku " + getString(R.string.toast_shizuku_ok), Toast.LENGTH_SHORT).show();
				}
			}
		}
	};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Shizuku.addRequestPermissionResultListener(permissionListener);
        prefs = getSharedPreferences("dot_config", MODE_PRIVATE);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(40, 40, 40, 40);

        // 标题
        TextView title = new TextView(this);
        title.setText(R.string.main_title);
        title.setTextSize(28);
        title.setPadding(0, 0, 0, 10);
        title.setTextColor(getTextColor());
        root.addView(title);

        // 状态说明
        String shizukuStatus;
        if (Shizuku.pingBinder()) {
            shizukuStatus = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
				? getString(R.string.toast_shizuku_ok) : "Shizuku \u672A\u6388\u6743";
        } else {
            shizukuStatus = getString(R.string.toast_shizuku_norun);
        }
        TextView statusView = new TextView(this);
        statusView.setText("Shizuku: " + shizukuStatus + "\nRoot: " + (isRootAvailable() ? getString(R.string.root_authorized) : getString(R.string.root_unauthorized)));
        statusView.setTextColor(getSecondaryTextColor());
        statusView.setTextSize(11);
        statusView.setPadding(0, 0, 0, 20);
        root.addView(statusView);

        // ============ 授权区 ============
        

        // ============ 必看 ============
        TextView sectionMust = new TextView(this);
        sectionMust.setText(R.string.section_must);
        sectionMust.setTextColor(getSecondaryTextColor());
        sectionMust.setTextSize(13);
        sectionMust.setGravity(Gravity.CENTER);
        sectionMust.setPadding(0, 30, 0, 20);
        root.addView(sectionMust);

        addEntryButton(root, getString(R.string.btn_must_read), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWarningDialog();
            }
        });

        addEntryButton(root, getString(R.string.btn_usage_guide), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(MainActivity.this, UsageGuideActivity.class));
			}
		});

        // ============ 授权 ============
        TextView sectionAuth = new TextView(this);
        sectionAuth.setText(R.string.section_auth);
        sectionAuth.setTextColor(getSecondaryTextColor());
        sectionAuth.setTextSize(13);
        sectionAuth.setGravity(Gravity.CENTER);
        sectionAuth.setPadding(0, 30, 0, 20);
        root.addView(sectionAuth);

        addEntryButton(root, getString(R.string.btn_shizuku), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (Shizuku.pingBinder()) {
					if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
						Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
					} else {
						Toast.makeText(MainActivity.this, "Shizuku " + getString(R.string.toast_shizuku_ok), Toast.LENGTH_SHORT).show();
					}
				} else {
					Toast.makeText(MainActivity.this, R.string.toast_shizuku_norun, Toast.LENGTH_SHORT).show();
				}
			}
		});



        addEntryButton(root, getString(R.string.btn_accessibility), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				// 手动进入无障碍设置，1分钟内不拦截
				prefs.edit().putLong("admin_unlock_time", System.currentTimeMillis() + 60000).apply();
				startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
			}
		});

        addEntryButton(root, getString(R.string.btn_enable_acc_oneclick), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				enableAccessibilityByShell();
			}
		});

        addEntryButton(root, getString(R.string.btn_notification_perm), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (Build.VERSION.SDK_INT >= 33) {
					requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2000);
				} else {
					Toast.makeText(MainActivity.this, R.string.no_notification_needed, Toast.LENGTH_SHORT).show();
				}
			}
		});

        addEntryButton(root, getString(R.string.btn_admin), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				// 发送广播给 AntiLockService 自动设置设备管理员
				Intent intent = new Intent("xiaote.AnQuan.AUTO_SETUP_ADMIN");
				intent.setPackage(getPackageName());
				sendBroadcast(intent);
				Toast.makeText(MainActivity.this, R.string.opening_admin_settings, Toast.LENGTH_SHORT).show();
			}
		});

        addEntryButton(root, getString(R.string.btn_device_owner), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				MainActivity.this.setDeviceOwner();
			}
		});

        addEntryButton(root, getString(R.string.btn_battery), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				try {
					Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
					intent.setData(android.net.Uri.parse("package:" + getPackageName()));
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					startActivity(intent);
				} catch (Exception e) {
					Toast.makeText(MainActivity.this, R.string.cannot_open_settings, Toast.LENGTH_SHORT).show();
				}
			}
		});

        // ============ 功能 ============
        TextView sectionFunc = new TextView(this);
        sectionFunc.setText(R.string.section_func);
        sectionFunc.setTextColor(getSecondaryTextColor());
        sectionFunc.setTextSize(13);
        sectionFunc.setGravity(Gravity.CENTER);
        sectionFunc.setPadding(0, 30, 0, 20);
        root.addView(sectionFunc);

        addEntryButton(root, getString(R.string.btn_float_settings), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(MainActivity.this, FloatingButtonSettingsActivity.class));
			}
		});

        addEntryButton(root, getString(R.string.btn_protection), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(MainActivity.this, ProtectionSettingsActivity.class));
			}
		});

        addEntryButton(root, getString(R.string.btn_acc_manager), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(MainActivity.this, AccessibilityManagerActivity.class));
			}
		});

        addEntryButton(root, getString(R.string.btn_managed_list), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(MainActivity.this, ManagedListActivity.class));
			}
		});

        addEntryButton(root, getString(R.string.btn_sensitive_apps), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(MainActivity.this, SensitiveAppsActivity.class));
			}
		});

        addEntryButton(root, getString(R.string.btn_security_scan), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(MainActivity.this, SecurityScanActivity.class));
			}
		});

        // ============ 关于应用 ============
        TextView sectionAbout = new TextView(this);
        sectionAbout.setText(R.string.section_about);
        sectionAbout.setTextColor(getSecondaryTextColor());
        sectionAbout.setTextSize(13);
        sectionAbout.setGravity(Gravity.CENTER);
        sectionAbout.setPadding(0, 30, 0, 20);
        root.addView(sectionAbout);

        addEntryButton(root, getString(R.string.btn_blog), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://xiaote.data.blog/xtsafe"));
				startActivity(i);
			}
		});

        addEntryButton(root, getString(R.string.btn_opensource), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xiaote-xt/xtsafe"));
				startActivity(i);
			}
		});

        addEntryButton(root, getString(R.string.btn_update_log), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				showUpdateLog();
			}
		});

        addEntryButton(root, getString(R.string.btn_check_update), new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				checkUpdate();
			}
		});

        // 底部提示
        TextView footer = new TextView(this);
        footer.setText(R.string.footer_settings);
        footer.setTextColor(getSecondaryTextColor());
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, 30, 0, 10);
        root.addView(footer);

        scrollView.addView(root);
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
        scrollView.post(new java.lang.Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= 33) {
                    try {
                        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                                new android.window.OnBackInvokedCallback() {
                                    @Override
                                    public void onBackInvoked() {
                                        finish();
                                    }
                                });
                    } catch (Exception e) {}
                }
            }
        });

        // 左滑返回
        SwipeBackHelper.attach(this);

        // 首次运行显示使用须知
        if (!prefs.getBoolean("has_seen_warning", false)) {
            new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.warning_title)
                    .setMessage(getString(R.string.warning_body))
                    .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            prefs.edit().putBoolean("has_seen_warning", true).apply();
                            // 然后弹出密码设置对话框（如果有）
                            if (!isPasswordSet()) {
                                showSetPasswordDialog();
                            }
                        }
                    })
                    .setCancelable(false)
                    .show();
        } else {
            // 已经看过，正常走密码验证
            if (!isPasswordSet() && !prefs.getBoolean("skip_password_setup", false)) {
                showSetPasswordDialog();
            }
        }

        // 检查算数题验证
        if (getIntent().getBooleanExtra("show_math_verify", false)) {
            getWindow().getDecorView().post(new java.lang.Runnable() {
                @Override
                public void run() {
                    showMathVerifyDialog();
                }
            });
        }

        // 自动请求Shizuku权限
        if (Shizuku.pingBinder()
			&& Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        }
        // 自动提权开启无障碍（应用启动时自动恢复）
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            try {
                String component = "xiaote.AnQuan/xiaote.AnQuan.AntiLockService";
                String cmd1 = "settings put secure enabled_accessibility_services '" + component + "'";
                String cmd2 = "settings put secure accessibility_enabled 1";
                Shizuku.newProcess(new String[]{"sh", "-c", cmd1 + " && " + cmd2}, null, null);
                // 静默开启，不打扰用户
            } catch (Exception ignored) {}
        }

        // 特殊日子纪念弹窗
        SpecialDayChecker.check(this);
    }

	private void addEntryButton(LinearLayout root, String string) {
	}

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("show_math_verify", false)) {
            getWindow().getDecorView().post(new java.lang.Runnable() {
                @Override
                public void run() {
                    showMathVerifyDialog();
                }
            });
        }
        }

    private String toMixedNumber(int n) {
        // 超出范围直接回退到阿拉伯数字
        if (n < 1 || n > 20) return String.valueOf(n);
        String[] circled = {"①","②","③","④","⑤","⑥","⑦","⑧","⑨","⑩","⓫","⓬","⓭","⓮","⓯","⓰","⓱","⓲","⓳","⓴"};
        String[] fullwidth = {"０","１","２","３","４","５","６","７","８","９","１０","１１","１２","１３","１４","１５","１６","１７","１８","１９","２０"};
        String[] chinese = {"一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七","十八","十九","二十"};
        int idx = n - 1;
        String[] styles = {
            String.valueOf(n),
            circled[idx],
            fullwidth[idx],
            chinese[idx]
        };
        return styles[(int)(Math.random() * styles.length)];
    }

    private String toMixedOperator() {
        String[] ops = {"+", "➕", "＋", "﹢", "⁺"};
        return ops[(int)(Math.random() * ops.length)];
    }

    private void showMathVerifyDialog() {
        if (mathVerifyShowing) return;
        mathVerifyShowing = true;
        final int a = (int)(Math.random() * 20) + 1;
        final int b = (int)(Math.random() * 20) + 1;
        final int answer = a + b;

        String displayA = toMixedNumber(a);
        String displayB = toMixedNumber(b);
        String displayOp = toMixedOperator();
        String displayEq = (int)(Math.random() * 2) == 0 ? "=" : "＝";

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.input_result);

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.math_verify_title)
                .setMessage(getString(R.string.math_verify_msg, displayA, displayOp, displayB, displayEq))
                .setView(input)
                .setPositiveButton(R.string.confirm_ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String text = input.getText().toString().trim();
                        try {
                            int userAnswer = Integer.parseInt(text);
                            if (userAnswer == answer) {
                                long unlockTime = System.currentTimeMillis() + 60000;
                                prefs.edit().putLong("admin_unlock_time", unlockTime).apply();
                                dialog.dismiss();
                                Toast.makeText(MainActivity.this, R.string.math_verify_ok,
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(MainActivity.this, R.string.math_verify_wrong, Toast.LENGTH_SHORT).show();
                                input.setText("");
                                input.requestFocus();
                            }
                        } catch (NumberFormatException e) {
                            Toast.makeText(MainActivity.this, R.string.enter_valid_number, Toast.LENGTH_SHORT).show();
                            input.setText("");
                            input.requestFocus();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(android.content.DialogInterface dialog) {
                        mathVerifyShowing = false;
                    }
                })
                .setCancelable(false)
                .show();
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
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(getString(R.string.set_password_hint));

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.set_password_title)
                .setMessage(R.string.set_password_msg)
                .setView(input)
                .setPositiveButton(R.string.confirm_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pwd = input.getText().toString().trim();
                        if (pwd.length() < 4) {
                            Toast.makeText(MainActivity.this, R.string.password_too_short, Toast.LENGTH_SHORT).show();
                            showSetPasswordDialog();
                            return;
                        }
                        prefs.edit().putString("app_password_hash", hashPassword(pwd)).apply();
                        prefs.edit().putLong("unlock_until", System.currentTimeMillis() + 5000).apply();
                        Toast.makeText(MainActivity.this, R.string.password_set, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.skip, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Toast.makeText(MainActivity.this, R.string.suggest_set_password, Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton(R.string.no_more_prompt, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putBoolean("skip_password_setup", true).apply();
                        Toast.makeText(MainActivity.this, R.string.password_prompt_off, Toast.LENGTH_SHORT).show();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void showEnterPasswordDialog(final java.lang.Runnable onSuccess) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(getString(R.string.enter_password_hint));

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.verify_password_title)
                .setMessage(R.string.enter_password)
                .setView(input)
                .setPositiveButton(R.string.confirm_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pwd = input.getText().toString().trim();
                        String saved = prefs.getString("app_password_hash", "");
                        if (hashPassword(pwd).equals(saved)) {
                            if (onSuccess != null) onSuccess.run();
                        } else {
                            Toast.makeText(MainActivity.this, R.string.password_wrong, Toast.LENGTH_SHORT).show();
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
                .setCancelable(false)
                .show();
    }

    private void addEntryButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setAllCaps(false);
        btn.setOnClickListener(listener);
        root.addView(btn);
    }

    // 一键授权：通知 + 无障碍 + 省电 + 管理员
    private void oneClickAuthorize() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2000);
            } catch (Exception e) {}
        }
        enableAccessibilityByShell();
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {}
        try {
            Intent intent = new Intent();
            intent.setClassName("com.android.settings",
                    "com.android.settings.Settings$DeviceAdminSettingsActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {}
    }

    // 一键开启无障碍：通过 Shizuku 或 Root 执行 shell
    private void enableAccessibilityByShell() {
        String component = "xiaote.AnQuan/xiaote.AnQuan.AntiLockService";
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
            Toast.makeText(MainActivity.this, R.string.acc_enabled_check, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(MainActivity.this, R.string.need_shizuku_root, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permissionListener);
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

    private int getTextColor() {
        return Color.argb(255, 30, 30, 30);
    }

    private int getSecondaryTextColor() {
        return Color.argb(150, 90, 90, 90);
    }

    private void showWarningDialog() {
        String message = getString(R.string.warning_body);

        new android.app.AlertDialog.Builder(MainActivity.this)
                .setTitle(R.string.warning_title)
                .setMessage(message)
                .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // 手动点击关闭
                    }
                })
                .setCancelable(false)
                .show();
    }

    private volatile boolean isSettingDeviceOwner = false;

    private void setDeviceOwner() {
        if (isSettingDeviceOwner) {
            return;
        }
        isSettingDeviceOwner = true;
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                try {
                    String cmd = "dpm set-device-owner xiaote.AnQuan/.DeviceAdmin";
                    Process process = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream()));
                    StringBuilder output = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                    }
                    int exitCode = process.waitFor();
                    String result = output.toString().trim();
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(MainActivity.this);
                    if (exitCode == 0) {
                        builder.setTitle(R.string.device_owner_ok_title);
                        builder.setMessage(R.string.device_owner_success);
                    } else {
                        builder.setTitle(R.string.device_owner_fail_title);
                        builder.setMessage(getString(R.string.device_owner_fail, exitCode, result.isEmpty() ? getString(R.string.no_output) : result));
                    }
                    builder.setPositiveButton(R.string.confirm_ok, null);
                    builder.show();
                } catch (Exception e) {
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(MainActivity.this);
                    builder.setTitle(R.string.device_owner_exception);
                    builder.setMessage(e.getMessage());
                    builder.setPositiveButton(R.string.confirm_ok, null);
                    builder.show();
                }
            } else {
                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(MainActivity.this);
                builder.setTitle(R.string.shizuku_not_authorized);
                builder.setMessage(R.string.device_owner_need_shizuku);
                builder.setPositiveButton(R.string.confirm_ok, null);
                builder.show();
            }
        } finally {
            isSettingDeviceOwner = false;
        }
    }

    // ==================== 更新时间 ====================

    /** 应用内更新日志查看器（跳转新 Activity 显示） */
    private void showUpdateLog() {
        UpdateLogActivity.start(this);
    }

    /** 更新检测：拉取日志全文，解析出数值最大的版本号与当前版本比对 */
    private void checkUpdate() {
        final String urlStr = "https://raw.githubusercontent.com/xiaote-XT/xtsafe/refs/heads/main/Update-Log.txt";
        Toast.makeText(this, R.string.checking_update, Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(MainActivity.this, R.string.check_update_fail, Toast.LENGTH_LONG).show();
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
                            new android.app.AlertDialog.Builder(MainActivity.this)
                                    .setTitle(R.string.new_version_found)
                                    .setMessage(getString(R.string.latest_version, latest, currentVersion))
                                    .setPositiveButton(R.string.view_log, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            showUpdateLog();
                                        }
                                    })
                                    .setNegativeButton(R.string.ignore, null)
                                    .show();
                        } else {
                            Toast.makeText(MainActivity.this, getString(R.string.already_latest, currentVersion),
                                    Toast.LENGTH_LONG).show();
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
