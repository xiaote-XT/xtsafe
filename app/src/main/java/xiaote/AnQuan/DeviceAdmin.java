package xiaote.AnQuan;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public class DeviceAdmin extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {}

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        String appName;
        try {
            appName = context.getString(R.string.app_name);
        } catch (Exception e) {
            appName = "XTsafe";
        }
        return appName + context.getString(R.string.admin_desc_suffix);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {}
}
