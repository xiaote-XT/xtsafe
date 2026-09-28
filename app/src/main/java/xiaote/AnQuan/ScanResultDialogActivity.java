package xiaote.AnQuan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 显示安装检测结果的 Dialog，点击通知后弹出。
 *
 * 标题与内容顶部优先显示应用名：通知里只带了包名，这里用 PackageManager
 * 反查应用名，查不到时退回显示包名。
 */
public class ScanResultDialogActivity extends Activity {

    // 标记：点R.string.detail_btn时先关闭主框，避免触发 finish
    private boolean switchingToDetail = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        final String packageName = intent.getStringExtra("package_name");
        final String summary = intent.getStringExtra("summary");
        final String detail = intent.getStringExtra("detail");

        // 反查应用名，拿不到就退回包名。
        // 匿名内部类（setButton 回调）里要捕获 appName，
        // 所以先用临时变量做可变的空值兜底，最后再赋给 final 的 appName。
        String resolvedName = null;
        if (packageName != null && !packageName.isEmpty()) {
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
                resolvedName = pm.getApplicationLabel(ai).toString();
            } catch (Exception ignored) {}
        }
        if (resolvedName == null || resolvedName.isEmpty()) resolvedName = packageName;
        final String appName = resolvedName;

        String title = getString(R.string.scan_result_title);
        if (appName != null && !appName.isEmpty()) {
            title = getString(R.string.scan_result_title_pkg, appName);
        }

        String body = summary != null ? summary : getString(R.string.no_result);
        // 内容顶部补上应用名与包名
        StringBuilder head = new StringBuilder();
        if (appName != null && !appName.isEmpty()) head.append("应用: ").append(appName).append('\n');
        if (packageName != null && !packageName.isEmpty()) head.append("包名: ").append(packageName).append("\n\n");
        final String content = head.toString() + body;

        // 主框内容：可滚动、可选中复制
        final ScrollView scrollView = new ScrollView(this);
        final TextView textView = new TextView(this);
        textView.setText(content);
        textView.setTextIsSelectable(true);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        textView.setPadding(pad, pad, pad, pad);
        textView.setTextColor(0xFF333333);
        scrollView.addView(textView);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scrollView)
                .setCancelable(true)
                .create();

        // 卸载：唤起系统卸载页后关闭
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.uninstall), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                try {
                    if (packageName != null && !packageName.isEmpty()) {
                        Intent uninstall = new Intent(Intent.ACTION_DELETE);
                        uninstall.setData(Uri.parse("package:" + packageName));
                        uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(uninstall);
                    }
                } catch (Exception e) {
                    Toast.makeText(ScanResultDialogActivity.this, R.string.uninstall_error, Toast.LENGTH_SHORT).show();
                }
                finish();
            }
        });

        // 忽略：直接关闭
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.ignore), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                finish();
            }
        });

        // 详细：先关闭主框，再弹出独立详情框（避免双框叠加导致异常无法弹出）
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.detail_btn), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                switchingToDetail = true;
                d.dismiss();
                showDetailDialog(appName, detail != null ? detail : content);
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                // 切详情时不结束 Activity；其余关闭方式一律结束
                if (!switchingToDetail) {
                    finish();
                }
            }
        });

        dialog.show();
    }

    /** 弹出独立的详细信息弹窗 */
    private void showDetailDialog(String appName, final String detailText) {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(detailText);
        tv.setTextIsSelectable(true);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextColor(0xFF333333);
        sv.addView(tv);

        AlertDialog detailDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.detail_title, appName != null ? appName : ""))
                .setView(sv)
                .setCancelable(true)
                .create();

        detailDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.copy_btn), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.scan_detail_label), detailText));
                    Toast.makeText(ScanResultDialogActivity.this, getString(R.string.copied), Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    // ignore
                }
            }
        });

        detailDialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.close_btn), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                finish();
            }
        });

        detailDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                finish();
            }
        });

        detailDialog.show();
    }
}
