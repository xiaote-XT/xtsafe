package xiaote.AnQuan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 显示安装检测结果的 Dialog，点击通知后弹出
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

        String title = getString(R.string.scan_result_title);
        if (packageName != null && !packageName.isEmpty()) {
            title = getString(R.string.scan_result_title_pkg, packageName);
        }
        final String content = summary != null ? summary : getString(R.string.no_result);

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
                showDetailDialog(packageName, detail != null ? detail : content);
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
    private void showDetailDialog(String packageName, final String detailText) {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(detailText);
        tv.setTextIsSelectable(true);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextColor(0xFF333333);
        sv.addView(tv);

        AlertDialog detailDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.detail_title, packageName != null ? packageName : ""))
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
