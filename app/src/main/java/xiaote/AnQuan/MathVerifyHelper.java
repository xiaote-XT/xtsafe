package xiaote.AnQuan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.Toast;

public class MathVerifyHelper {
    private final Activity activity;
    private final SharedPreferences prefs;
    private boolean showing = false;

    public MathVerifyHelper(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("dot_config", Activity.MODE_PRIVATE);
    }

    public void show() {
        if (showing) return;
        showing = true;
        final int a = (int)(Math.random() * 20) + 1;
        final int b = (int)(Math.random() * 20) + 1;
        final int answer = a + b;
        final String displayA = toMixedNumber(a);
        final String displayB = toMixedNumber(b);
        final String displayOp = toMixedOperator();
        final String displayEq = (int)(Math.random() * 2) == 0 ? "=" : "＝";

        final EditText input = new EditText(activity);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.input_result);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(R.string.math_verify_title);
        builder.setMessage(activity.getString(R.string.math_verify_msg, displayA, displayOp, displayB, displayEq));
        builder.setView(input);

        builder.setPositiveButton(R.string.confirm_ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String text = input.getText().toString().trim();
                    try {
                        int userAnswer = Integer.parseInt(text);
                        if (userAnswer == answer) {
                            long unlockTime = System.currentTimeMillis() + 60000;
                            prefs.edit().putLong("admin_unlock_time", unlockTime).apply();
                            dialog.dismiss();
                            Toast.makeText(activity, R.string.math_verify_ok, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(activity, R.string.math_verify_wrong, Toast.LENGTH_SHORT).show();
                            input.setText("");
                            input.requestFocus();
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(activity, R.string.enter_valid_number, Toast.LENGTH_SHORT).show();
                        input.setText("");
                        input.requestFocus();
                    }
                }
            });

        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });

        builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    showing = false;
                }
            });

        builder.setCancelable(false);
        builder.show();
    }


    private String toMixedNumber(int n) {
        if (n < 1 || n > 20) return String.valueOf(n);
        String[] circled = {"①","②","③","④","⑤","⑥","⑦","⑧","⑨","⑩","⓫","⓬","⓭","⓮","⓯","⓰","⓱","⓲","⓳","⓴"};
        String[] fullwidth = {"０","１","２","３","４","５","６","７","８","９","１０","１１","１２","１３","１４","１５","１６","１７","１８","１９","２０"};
        String[] chinese = {"一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七","十八","十九","二十"};
        int idx = n - 1;
        String[] styles = { String.valueOf(n), circled[idx], fullwidth[idx], chinese[idx] };
        return styles[(int)(Math.random() * styles.length)];
    }

    private String toMixedOperator() {
        String[] ops = {"+", "➕", "＋", "﹢", "⁺"};
        return ops[(int)(Math.random() * ops.length)];
    }
}
