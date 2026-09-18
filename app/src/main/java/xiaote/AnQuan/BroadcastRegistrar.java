package xiaote.AnQuan;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.widget.Toast;

/**
 * 广播注册与接收（拆自 XTSafeMainService）：
 *   屏幕亮灭、应急键盘唤起、应用安装、安装通知操作、自动授权、自动设置管理员。
 */
public class BroadcastRegistrar {

    public static final String ACTION_SHOW_UNINSTALL = "xiaote.AnQuan.SHOW_UNINSTALL";
    private static final String ACTION_NOTIFY_UNINSTALL = "xiaote.AnQuan.NOTIFY_UNINSTALL";
    private static final String ACTION_NOTIFY_MANAGE = "xiaote.AnQuan.NOTIFY_MANAGE";
    private static final String ACTION_NOTIFY_IGNORE = "xiaote.AnQuan.NOTIFY_IGNORE";
    private static final String ACTION_AUTO_AUTHORIZE = "xiaote.AnQuan.AUTO_AUTHORIZE";
    private static final String ACTION_AUTO_SETUP_ADMIN = "xiaote.AnQuan.AUTO_SETUP_ADMIN";

    /** 屏幕亮灭回调：由主类负责智能恢复与无障碍模式重算 */
    public interface Host {
        void onScreenOff();
        void onScreenOn();
    }

    private final AccessibilityService service;
    private final Handler handler;
    private final SharedPreferences dotPrefs;
    private final OverlayManager overlayManager;
    private final VirusManager virusManager;
    private final AutoClickHelper autoClickHelper;
    private final Host host;
    private final PackageManager pm;

    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver imeReceiver;
    private BroadcastReceiver packageReceiver;
    private BroadcastReceiver notificationActionReceiver;
    private BroadcastReceiver autoAuthorizeReceiver;
    private BroadcastReceiver autoSetupAdminReceiver;

    public BroadcastRegistrar(AccessibilityService service, Handler handler, SharedPreferences dotPrefs,
                              OverlayManager overlayManager, VirusManager virusManager,
                              AutoClickHelper autoClickHelper, Host host) {
        this.service = service;
        this.handler = handler;
        this.dotPrefs = dotPrefs;
        this.overlayManager = overlayManager;
        this.virusManager = virusManager;
        this.autoClickHelper = autoClickHelper;
        this.host = host;
        this.pm = service.getPackageManager();
    }

    public void registerAll() {
        registerScreenReceiver();
        registerIMEReceiver();
        registerPackageReceiver();
        registerNotificationActionReceiver();
        registerAutoAuthorizeReceiver();
        registerAutoSetupAdminReceiver();
    }

    public void unregisterAll() {
        unregisterScreenReceiver();
        unregisterIMEReceiver();
        unregisterPackageReceiver();
        unregisterNotificationActionReceiver();
        unregisterAutoAuthorizeReceiver();
        unregisterAutoSetupAdminReceiver();
    }

    /** 开关变化后重新注册安装广播 */
    public void reregisterPackageReceiver() {
        unregisterPackageReceiver();
        registerPackageReceiver();
    }

    private void registerScreenReceiver() {
        try {
            if (screenReceiver != null) return;
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        if (host != null) host.onScreenOff();
                    } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                        if (host != null) host.onScreenOn();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED);
            else service.registerReceiver(screenReceiver, filter);
        } catch (Exception ignored) {}
    }

    private void unregisterScreenReceiver() {
        try {
            if (screenReceiver != null) { service.unregisterReceiver(screenReceiver); screenReceiver = null; }
        } catch (Exception ignored) {}
    }

    private void registerIMEReceiver() {
        try {
            if (imeReceiver != null) return;
            imeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_SHOW_UNINSTALL.equals(intent.getAction())) {
                        final String pkg = intent != null ? intent.getStringExtra("pkg") : null;
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (pkg != null && !pkg.isEmpty()) overlayManager.showUninstallList(pkg);
                                    else overlayManager.showUninstallList();
                                }
                            });
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(imeReceiver, new IntentFilter(ACTION_SHOW_UNINSTALL), Context.RECEIVER_NOT_EXPORTED);
            else service.registerReceiver(imeReceiver, new IntentFilter(ACTION_SHOW_UNINSTALL));
        } catch (Exception ignored) {}
    }

    private void unregisterIMEReceiver() {
        try {
            if (imeReceiver != null) { service.unregisterReceiver(imeReceiver); imeReceiver = null; }
        } catch (Exception ignored) {}
    }

    private void registerPackageReceiver() {
        try {
            if (packageReceiver != null) return;
            if (!dotPrefs.getBoolean("block_virus", true) && !dotPrefs.getBoolean("notify_install", true) && !dotPrefs.getBoolean("manage_install", true)) return;
            packageReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                        final String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
                        if (pkg == null || pkg.equals(service.getPackageName())) return;
                        boolean isVirus = VirusPackages.getVirusPackages(service).contains(pkg);
                        if (dotPrefs.getBoolean("notify_install", true) && isVirus) sendInstallNotify(pkg, true);
                        if (dotPrefs.getBoolean("manage_install", true) && !isVirus) sendInstallNotify(pkg, false);
                        if (dotPrefs.getBoolean("block_virus", true) && isVirus) {
                            handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        virusManager.blockVirusPackage(pkg);
                                    }
                                });
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED);
            else service.registerReceiver(packageReceiver, filter);
        } catch (Exception ignored) {}
    }

    private void unregisterPackageReceiver() {
        try {
            if (packageReceiver != null) { service.unregisterReceiver(packageReceiver); packageReceiver = null; }
        } catch (Exception ignored) {}
    }

    private void sendInstallNotify(String pkg, boolean isVirus) {
        try {
            final NotificationManager nm = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId = "install_notify";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(channelId, service.getString(R.string.install_channel), NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription(service.getString(R.string.install_channel));
                nm.createNotificationChannel(ch);
            }
            String appName = pkg;
            try { appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) {}

            Intent uninstallIntent = new Intent(ACTION_NOTIFY_UNINSTALL);
            uninstallIntent.putExtra("pkg", pkg);
            uninstallIntent.setPackage(service.getPackageName());
            PendingIntent uninstallPi = PendingIntent.getBroadcast(service, pkg.hashCode(), uninstallIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent manageIntent = new Intent(ACTION_NOTIFY_MANAGE);
            manageIntent.putExtra("pkg", pkg);
            manageIntent.setPackage(service.getPackageName());
            PendingIntent managePi = PendingIntent.getBroadcast(service, pkg.hashCode() + 1, manageIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent ignoreIntent = new Intent(ACTION_NOTIFY_IGNORE);
            ignoreIntent.putExtra("pkg", pkg);
            ignoreIntent.setPackage(service.getPackageName());
            PendingIntent ignorePi = PendingIntent.getBroadcast(service, pkg.hashCode() + 2, ignoreIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder = new Notification.Builder(service, channelId)
                .setContentTitle((isVirus ? service.getString(R.string.virus_detected, appName) : service.getString(R.string.new_app_installed, appName)))
                .setContentText(isVirus ? service.getString(R.string.ask_uninstall) : service.getString(R.string.ask_manage))
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setAutoCancel(true);
            if (isVirus) { builder.addAction(0, service.getString(R.string.uninstall), uninstallPi); builder.addAction(0, service.getString(R.string.allow), ignorePi); }
            else { builder.addAction(0, service.getString(R.string.join_manage), managePi); builder.addAction(0, service.getString(R.string.allow), ignorePi); }
            if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(30000L);
            final int notifyId = 2000 + pkg.hashCode() % 1000;
            nm.notify(notifyId, builder.build());
            if (Build.VERSION.SDK_INT < 26) {
                handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try { nm.cancel(notifyId); } catch (Exception ignored) {}
                        }
                    }, 30000L);
            }
        } catch (Exception ignored) {}
    }

    private void registerNotificationActionReceiver() {
        try {
            if (notificationActionReceiver != null) return;
            notificationActionReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    final String pkg = intent != null ? intent.getStringExtra("pkg") : null;
                    if (pkg == null) return;
                    try {
                        NotificationManager nm = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
                        nm.cancel(2000 + pkg.hashCode() % 1000);
                    } catch (Exception ignored) {}
                    if (ACTION_NOTIFY_UNINSTALL.equals(action)) {
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    overlayManager.uninstallApp(pkg);
                                }
                            });
                    } else if (ACTION_NOTIFY_MANAGE.equals(action)) {
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    VirusPackages.addManagedPackage(service, pkg);
                                    Toast.makeText(service, service.getString(R.string.added_managed, pkg), Toast.LENGTH_SHORT).show();
                                }
                            });
                    } else if (ACTION_NOTIFY_IGNORE.equals(action)) {
                        Toast.makeText(service, service.getString(R.string.released, pkg), Toast.LENGTH_SHORT).show();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_NOTIFY_UNINSTALL);
            filter.addAction(ACTION_NOTIFY_MANAGE);
            filter.addAction(ACTION_NOTIFY_IGNORE);
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else service.registerReceiver(notificationActionReceiver, filter);
        } catch (Exception ignored) {}
    }

    private void unregisterNotificationActionReceiver() {
        try {
            if (notificationActionReceiver != null) { service.unregisterReceiver(notificationActionReceiver); notificationActionReceiver = null; }
        } catch (Exception ignored) {}
    }

    private void registerAutoAuthorizeReceiver() {
        try {
            if (autoAuthorizeReceiver != null) return;
            autoAuthorizeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_AUTO_AUTHORIZE.equals(intent.getAction())) {
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    autoClickHelper.autoAuthorize();
                                }
                            });
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(autoAuthorizeReceiver, new IntentFilter(ACTION_AUTO_AUTHORIZE), Context.RECEIVER_NOT_EXPORTED);
            else service.registerReceiver(autoAuthorizeReceiver, new IntentFilter(ACTION_AUTO_AUTHORIZE));
        } catch (Exception ignored) {}
    }

    private void unregisterAutoAuthorizeReceiver() {
        try {
            if (autoAuthorizeReceiver != null) { service.unregisterReceiver(autoAuthorizeReceiver); autoAuthorizeReceiver = null; }
        } catch (Exception ignored) {}
    }

    private void registerAutoSetupAdminReceiver() {
        try {
            if (autoSetupAdminReceiver != null) return;
            autoSetupAdminReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_AUTO_SETUP_ADMIN.equals(intent.getAction())) {
                        handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    autoClickHelper.autoSetupAdmin();
                                }
                            });
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(autoSetupAdminReceiver, new IntentFilter(ACTION_AUTO_SETUP_ADMIN), Context.RECEIVER_NOT_EXPORTED);
            else service.registerReceiver(autoSetupAdminReceiver, new IntentFilter(ACTION_AUTO_SETUP_ADMIN));
        } catch (Exception ignored) {}
    }

    private void unregisterAutoSetupAdminReceiver() {
        try {
            if (autoSetupAdminReceiver != null) { service.unregisterReceiver(autoSetupAdminReceiver); autoSetupAdminReceiver = null; }
        } catch (Exception ignored) {}
    }
}
