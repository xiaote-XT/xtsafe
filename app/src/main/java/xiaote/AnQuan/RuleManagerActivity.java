package xiaote.AnQuan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 规则管理 - 三源独立存储：
 *   assets/scan_rules.json            - 内置规则（不可删除）
 *   filesDir/scan_rules_cloud.json    - 云端更新规则（可删除）
 *   filesDir/scan_rules_imported.json - 导入规则（可删除）
 */
public class RuleManagerActivity extends Activity {

    private static final String CLOUD_FILE = "scan_rules_cloud.json";
    private static final String IMPORT_FILE = "scan_rules_imported.json";
    private static final String DEFAULT_URL = "https://raw.githubusercontent.com/xiaote-XT/xtsafe/refs/heads/main/config.json";
    private TextView statusText;
    private Button deleteImportBtn;
    private Button deleteCloudBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rule_manager);

        statusText = (TextView) findViewById(R.id.statusText);
        Button editBtn = (Button) findViewById(R.id.editRulesBtn);
        Button importBtn = (Button) findViewById(R.id.importRulesBtn);
        Button linkImportBtn = (Button) findViewById(R.id.linkImportBtn);
        Button updateBtn = (Button) findViewById(R.id.checkUpdateBtn);
        Button autoUpdateBtn = (Button) findViewById(R.id.autoUpdateBtn);
        Button backBtn = (Button) findViewById(R.id.backBtn);

        // 新增：删除导入规则按钮
        deleteImportBtn = new Button(this);
        deleteImportBtn.setText(R.string.rule_delete_import);
        deleteImportBtn.setTextSize(14);
        deleteImportBtn.setVisibility(View.GONE);
        deleteImportBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete(getString(R.string.rule_import_label), IMPORT_FILE);
            }
        });

        // 新增：删除云端规则按钮
        deleteCloudBtn = new Button(this);
        deleteCloudBtn.setText(R.string.rule_delete_cloud);
        deleteCloudBtn.setTextSize(14);
        deleteCloudBtn.setVisibility(View.GONE);
        deleteCloudBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete(getString(R.string.rule_cloud_label), CLOUD_FILE);
            }
        });

        // 将删除按钮添加到布局中（在 autoUpdateBtn 后面）
        LinearLayout parentLayout = (LinearLayout) autoUpdateBtn.getParent();
        if (parentLayout != null) {
            int index = parentLayout.indexOfChild(autoUpdateBtn);
            parentLayout.addView(deleteImportBtn, index + 1);
            parentLayout.addView(deleteCloudBtn, index + 2);
        }

        updateStatus();

        editBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor();
            }
        });

        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                startActivityForResult(intent, 1000);
            }
        });

        linkImportBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final android.widget.EditText input = new android.widget.EditText(RuleManagerActivity.this);
                input.setHint(R.string.rule_url_hint);
                input.setText(DEFAULT_URL);
                new AlertDialog.Builder(RuleManagerActivity.this)
                        .setTitle(R.string.rule_link_import)
                        .setMessage(R.string.rule_link_import_msg)
                        .setView(input)
                        .setPositiveButton(R.string.import_btn, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String url = input.getText().toString().trim();
                                if (!url.isEmpty()) {
                                    fetchAndImport(url);
                                }
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });

        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate(DEFAULT_URL);
            }
        });

        autoUpdateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int current = getSharedPreferences("dot_config", MODE_PRIVATE).getInt("auto_update_frequency", 0);
                final String[] items = {getString(R.string.auto_off), getString(R.string.auto_daily), getString(R.string.auto_weekly), getString(R.string.auto_every_open)};
                new AlertDialog.Builder(RuleManagerActivity.this)
                        .setTitle(R.string.auto_update_freq)
                        .setSingleChoiceItems(items, current, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                getSharedPreferences("dot_config", MODE_PRIVATE)
                                        .edit().putInt("auto_update_frequency", which).apply();
                                dialog.dismiss();
                                Toast.makeText(RuleManagerActivity.this, getString(R.string.auto_set_done, items[which]), Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });

        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    /**
     * 更新状态显示，判断当前使用的规则来源
     */
    private void updateStatus() {
        boolean hasCloud = new File(getFilesDir(), CLOUD_FILE).exists();
        boolean hasImport = new File(getFilesDir(), IMPORT_FILE).exists();

        StringBuilder sb = new StringBuilder();
        sb.append(R.string.rule_source_builtin);

        if (hasImport) {
            sb.append(R.string.rule_source_import);
        }
        if (hasCloud) {
            sb.append(R.string.rule_source_cloud);
        }
        if (!hasCloud && !hasImport) {
            sb.append(R.string.rule_source_default);
        }
        sb.append("\n");
        sb.append(R.string.rule_priority);

        statusText.setText(sb.toString());

        // 控制删除按钮显示
        deleteImportBtn.setVisibility(hasImport ? View.VISIBLE : View.GONE);
        deleteCloudBtn.setVisibility(hasCloud ? View.VISIBLE : View.GONE);
    }

    /**
     * 确认删除规则文件
     */
    private void confirmDelete(final String label, final String fileName) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_delete_title, label))
                .setMessage(getString(R.string.confirm_delete_msg, label))
                .setPositiveButton(R.string.remove, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            new File(getFilesDir(), fileName).delete();
                            Toast.makeText(RuleManagerActivity.this, getString(R.string.rule_deleted, label), Toast.LENGTH_SHORT).show();
                            updateStatus();
                        } catch (Exception e) {
                            Toast.makeText(RuleManagerActivity.this, getString(R.string.delete_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 编辑规则（打开编辑器，可选择保存到哪个源）
     */
    private void openEditor() {
        final android.widget.EditText editText = new android.widget.EditText(this);
        editText.setSingleLine(false);
        editText.setMinLines(20);
        editText.setTextSize(12);
        editText.setGravity(Gravity.START | Gravity.TOP);

        // 加载当前规则内容（显示合并后的效果）
        try {
            String content = readCurrentRules();
            editText.setText(content);
            editText.setSelection(0);
        } catch (Exception e) {
            Toast.makeText(this, R.string.read_rules_fail, Toast.LENGTH_SHORT).show();
            return;
        }

        // 询问保存到哪个来源
        new AlertDialog.Builder(this)
                .setTitle(R.string.edit_rules_title)
                .setMessage(R.string.save_to_source)
                .setView(editText)
                .setPositiveButton(R.string.save_as_import, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            saveToFile(IMPORT_FILE, editText.getText().toString());
                            Toast.makeText(RuleManagerActivity.this, R.string.saved_as_import, Toast.LENGTH_SHORT).show();
                            updateStatus();
                        } catch (Exception e) {
                            Toast.makeText(RuleManagerActivity.this, getString(R.string.save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 读取当前生效的规则（按优先级合并后的内容）
     */
    private String readCurrentRules() throws Exception {
        // 优先级：导入 > 云端 > 内置
        File importFile = new File(getFilesDir(), IMPORT_FILE);
        if (importFile.exists()) {
            return readFile(importFile);
        }
        File cloudFile = new File(getFilesDir(), CLOUD_FILE);
        if (cloudFile.exists()) {
            return readFile(cloudFile);
        }
        // 从 assets 读取默认规则
        InputStream is = getAssets().open("scan_rules.json");
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    private String readFile(File file) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    private void saveToFile(String fileName, String content) throws Exception {
        FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), fileName));
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1000 && resultCode == RESULT_OK) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    // 保存为导入规则（独立文件，不影响内置和云端）
                    saveToFile(IMPORT_FILE, sb.toString());
                    Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show();
                    updateStatus();
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.import_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * 从链接导入（保存为导入规则，非云端更新）
     */
    private void fetchAndImport(final String urlStr) {
        statusText.setText(R.string.downloading_rules);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setRequestMethod("GET");
                    final int code = conn.getResponseCode();
                    String result = null;
                    if (code == 200) {
                        InputStream is = conn.getInputStream();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();
                        conn.disconnect();
                        result = sb.toString();
                    } else {
                        conn.disconnect();
                    }

                    final String finalResult = result;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (finalResult == null) {
                                statusText.setText(getString(R.string.download_fail_conn, code));
                                Toast.makeText(RuleManagerActivity.this, R.string.download_fail_short, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            // 验证是否是有效的 JSON
                            try {
                                new org.json.JSONObject(finalResult);
                            } catch (Exception e) {
                                statusText.setText(R.string.download_fail_invalid);
                                Toast.makeText(RuleManagerActivity.this, R.string.invalid_rule_format, Toast.LENGTH_LONG).show();
                                return;
                            }
                            // 保存为导入规则
                            try {
                                saveToFile(IMPORT_FILE, finalResult);
                                Toast.makeText(RuleManagerActivity.this, R.string.imported_as_import, Toast.LENGTH_SHORT).show();
                                updateStatus();
                            } catch (Exception e) {
                                Toast.makeText(RuleManagerActivity.this, getString(R.string.save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText(getString(R.string.download_failed, e.getMessage()));
                            Toast.makeText(RuleManagerActivity.this, getString(R.string.download_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 云端更新检查（保存到云端规则文件，独立于导入规则和内置规则）
     */
    private void checkUpdate(final String urlStr) {
        statusText.setText(R.string.checking_cloud);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setRequestMethod("GET");
                    final int code = conn.getResponseCode();
                    String result = null;
                    if (code == 200) {
                        InputStream is = conn.getInputStream();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();
                        conn.disconnect();
                        result = sb.toString();
                    } else {
                        conn.disconnect();
                    }

                    final String finalResult = result;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (finalResult == null) {
                                statusText.setText(getString(R.string.check_fail_conn, code));
                                Toast.makeText(RuleManagerActivity.this, R.string.check_fail_short, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            // 验证是否是有效的 JSON
                            try {
                                new org.json.JSONObject(finalResult);
                            } catch (Exception e) {
                                statusText.setText(R.string.check_fail_invalid);
                                Toast.makeText(RuleManagerActivity.this, R.string.invalid_rule_format, Toast.LENGTH_LONG).show();
                                return;
                            }

                            // 与已有的云端规则比较，避免重复提示
                            boolean same = false;
                            try {
                                File cloudFile = new File(getFilesDir(), CLOUD_FILE);
                                if (cloudFile.exists()) {
                                    String localContent = readFile(cloudFile).replace("\n", "");
                                    String remoteFlat = finalResult.replace("\n", "");
                                    if (localContent.equals(remoteFlat)) {
                                        same = true;
                                    }
                                }
                            } catch (Exception e) {}

                            if (same) {
                                new AlertDialog.Builder(RuleManagerActivity.this)
                                        .setTitle(R.string.cloud_latest)
                                        .setMessage(R.string.cloud_latest_msg)
                                        .setPositiveButton(R.string.confirm_ok, null)
                                        .show();
                                statusText.setText(R.string.cloud_latest);
                                return;
                            }

                            // 询问是否更新
                            new AlertDialog.Builder(RuleManagerActivity.this)
                                    .setTitle(R.string.new_rules_found)
                                    .setMessage(R.string.update_cloud_msg)
                                    .setPositiveButton(R.string.update_btn, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            try {
                                                saveToFile(CLOUD_FILE, finalResult);
                                                Toast.makeText(RuleManagerActivity.this, R.string.cloud_updated, Toast.LENGTH_SHORT).show();
                                                updateStatus();
                                            } catch (Exception e) {
                                                Toast.makeText(RuleManagerActivity.this, getString(R.string.update_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                                            }
                                        }
                                    })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                            statusText.setText(R.string.new_rules_ask);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText(getString(R.string.check_failed, e.getMessage()));
                            Toast.makeText(RuleManagerActivity.this, getString(R.string.check_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }
}