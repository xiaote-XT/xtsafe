package xiaote.AnQuan;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
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

public class UpdateLogActivity extends Activity {

    private static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/xiaote-XT/xtsafe/refs/heads/main/Update-Log.txt";

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, UpdateLogActivity.class));
    }

    private TextView contentView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        root.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        contentView = new TextView(this);
        contentView.setTextSize(14);
        contentView.setTextColor(0xFF333333);
        contentView.setLineSpacing(0, 1.2f);
        contentView.setGravity(Gravity.START | Gravity.TOP);
        scroll.addView(contentView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button copyBtn = new Button(this);
        copyBtn.setText(R.string.copy_log);
        copyBtn.setAllCaps(false);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.update_log_label), contentView.getText().toString()));
                    Toast.makeText(UpdateLogActivity.this, R.string.copied, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
        root.addView(copyBtn);

        setContentView(root);
        load(DEFAULT_URL);
    }

    private void load(final String urlStr) {
        contentView.setText(R.string.loading_log);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = fetch(urlStr);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (text == null || text.trim().isEmpty()) {
                            contentView.setText(R.string.fetch_log_fail);
                            Toast.makeText(UpdateLogActivity.this, R.string.fetch_log_fail, Toast.LENGTH_LONG).show();
                        } else {
                            contentView.setText(text);
                        }
                    }
                });
            }
        }).start();
    }

    private String fetch(String urlStr) {
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
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                conn.disconnect();
                return sb.toString();
            }
            conn.disconnect();
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}