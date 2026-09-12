package xiaote.AnQuan;

import xiaote.AnQuan.R;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

import rikka.shizuku.Shizuku;

/** 自动点击授权助手 提取自 AntiLockService 的自动点击授权方法 */
public class AutoClickHelper {

    private final AccessibilityService service;
    private final Handler handler;

    public AutoClickHelper(AccessibilityService service, Handler handler) {
        this.service = service;
        this.handler = handler;
    }

    /** 模糊查找文本并点击按钮 */
    public boolean autoClickButton(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null) {
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                AccessibilityNodeInfo target = findClickableParent(node);
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 向上查找可点击的父节点（不限层级，最多10层） */
    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current.isClickable()) {
                return current;
            }
            current = current.getParent();
            depth++;
        }
        return null;
    }

    /** 自动设置设备管理员：打开设置页面 -> 点击应用名 -> 点击启用 */
    public void autoSetupAdmin() {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) service.getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName admin = new android.content.ComponentName(service, DeviceAdmin.class);
            if (dpm.isAdminActive(admin)) {
                Toast.makeText(service, "设备管理员已激活", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception ignored) {
        }

        try {
            Intent intent = new Intent();
            intent.setClassName("com.android.settings", "com.android.settings.Settings$DeviceAdminSettingsActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
        } catch (Exception e) {
        }

        final String appName = service.getString(R.string.app_name);
        // 打开设备管理员页面后，等待1500ms再查找"星特安全"
        handler.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        tryAutoClickAdminApp(0, appName);
                    }
                },
                1500);
    }

    private void tryAutoClickAdminApp(final int attempt, final String appName) {
        if (attempt > 15) return;
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                // root为null，等待300ms重试
                handler.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                tryAutoClickAdminApp(attempt + 1, appName);
                            }
                        },
                        300);
                return;
            }
            List<AccessibilityNodeInfo> title = root.findAccessibilityNodeInfosByText("设备管理");
            if (title == null || title.isEmpty()) {
                // 没找到"设备管理"标题，等待300ms重试
                root.recycle();
                handler.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                tryAutoClickAdminApp(attempt + 1, appName);
                            }
                        },
                        300);
                return;
            }
            List<AccessibilityNodeInfo> appNodes = root.findAccessibilityNodeInfosByText(appName);
            boolean clicked = false;
            if (appNodes != null) {
                for (AccessibilityNodeInfo n : appNodes) {
                    if (n == null) continue;
                    AccessibilityNodeInfo target = findClickableParent(n);
                    if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        clicked = true;
                        break;
                    }
                }
            }
            root.recycle();
            if (clicked) {
                // 点击"星特安全"成功后，等待3000ms进入详情页再查找"启用"按钮
                handler.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                tryAutoClickEnableAdmin(0);
                            }
                        },
                        800);
            }
        } catch (Exception ignored) {
        }
    }

    public void tryAutoClickEnableAdmin(final int attempt) {
        if (attempt > 20) return;
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                // root为null，等待500ms重试
                handler.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                tryAutoClickEnableAdmin(attempt + 1);
                            }
                        },
                        500);
                return;
            }

            boolean clicked = false;

            // 1. 先尝试精确文本"启用此设备管理应用"
            clicked = autoClickButton(root, "启用此设备管理应用");

            // 2. 如果失败，尝试模糊关键词"启用"、"激活"、"允许"
            if (!clicked) {
                String[] keywords = {"启用", "激活", "允许"};
                for (String keyword : keywords) {
                    clicked = autoClickButton(root, keyword);
                    if (clicked) break;
                }
            }

            // 3. 深度搜索所有可点击节点，查找包含"启用"的节点
            if (!clicked) {
                clicked = deepSearchClickable(root, "启用");
            }

            if (clicked) {
                Toast.makeText(service, "设备管理员已启用", Toast.LENGTH_SHORT).show();
            }
            root.recycle();
            if (!clicked) {
                // 没找到"启用"按钮，等待500ms重试
                handler.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                tryAutoClickEnableAdmin(attempt + 1);
                            }
                        },
                        500);
            }
        } catch (Exception ignored) {
        }
    }

    /** 深度遍历查找包含关键词的可点击节点 */
    private boolean deepSearchClickable(AccessibilityNodeInfo node, String keyword) {
        if (node == null) return false;

        CharSequence text = node.getText();
        if (text != null && text.toString().contains(keyword) && node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (deepSearchClickable(child, keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 自动授权（用户主动触发） */
    public void autoAuthorize() {
        final String appName = service.getString(R.string.app_name);
        Toast.makeText(service, "自动授权开始...", Toast.LENGTH_SHORT).show();

        // 1. 通知权限（Shizuku shell 优先）
        ShellExecutor.execShizuku(new String[] {"appops", "set", service.getPackageName(), "POST_NOTIFICATIONS", "allow"});
        ShellExecutor.execRoot("appops set " + service.getPackageName() + " POST_NOTIFICATIONS allow");

        // 2. 开启无障碍（通过 Shizuku）
        enableAccessibilityByShell();

        // 3. 申请省电优化，等待2000ms后打开省电优化设置页面
        handler.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(android.net.Uri.parse("package:" + service.getPackageName()));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            service.startActivity(intent);
                            Toast.makeText(service, "申请省电优化中...", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                        }
                    }
                },
                2000);

        // 4. 等待省电优化弹窗出现，4000ms后查找"允许"按钮
        handler.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        tryAutoClickBattery(0, appName);
                    }
                },
                4000);

        // 5. 打开设备管理员设置，等待7000ms后打开
        handler.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent intent = new Intent();
                            intent.setClassName("com.android.settings", "com.android.settings.Settings$DeviceAdminSettingsActivity");
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            service.startActivity(intent);
                        } catch (Exception e) {
                        }
                    }
                },
                7000);

        // 6. 等待设备管理员页面出现，9000ms后查找"星特安全"
        handler.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        tryAutoClickDeviceAdmin(0, appName);
                    }
                },
                9000);
    }

    private void tryAutoClickBattery(final int attempt, final String appName) {
        if (attempt > 10) return;
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                // root为null，等待300ms重试
                handler.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                tryAutoClickBattery(attempt + 1, appName);
                            }
                        },
                        300);
                return;
            }
            List<AccessibilityNodeInfo> confirm = root.findAccessibilityNodeInfosByText("允许");
            boolean isBattery = confirm != null && !confirm.isEmpty();
            if (isBattery) {
                List<AccessibilityNodeInfo> confirm2 = root.findAccessibilityNodeInfosByText("始终在后台运行");
                isBattery = confirm2 != null && !confirm2.isEmpty();
            }
            if (!isBattery) {
                List<AccessibilityNodeInfo> confirm2 = root.findAccessibilityNodeInfosByText(appName);
                isBattery = confirm2 != null && !confirm2.isEmpty();
            }
            if (isBattery) {
                // 省电优化弹窗，点击"允许"，如果没有则点击"确定"
                boolean clicked = autoClickButton(root, "允许");
                if (!clicked) clicked = autoClickButton(root, "确定");
                if (clicked) {
                    Toast.makeText(service, "省电优化已允许", Toast.LENGTH_SHORT).show();
                }
            }
            root.recycle();
        } catch (Exception ignored) {
        }
    }

    private void tryAutoClickDeviceAdmin(final int attempt, final String appName) {
        if (attempt > 10) return;
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                // root为null，等待300ms重试
                handler.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                tryAutoClickDeviceAdmin(attempt + 1, appName);
                            }
                        },
                        300);
                return;
            }
            List<AccessibilityNodeInfo> title = root.findAccessibilityNodeInfosByText("设备管理应用");
            if (title == null || title.isEmpty()) {
                // 没找到"设备管理应用"标题，等待300ms重试
                root.recycle();
                handler.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                tryAutoClickDeviceAdmin(attempt + 1, appName);
                            }
                        },
                        300);
                return;
            }
            List<AccessibilityNodeInfo> appNodes = root.findAccessibilityNodeInfosByText(appName);
            boolean clicked = false;
            if (appNodes != null) {
                for (AccessibilityNodeInfo n : appNodes) {
                    if (n == null) continue;
                    AccessibilityNodeInfo target = findClickableParent(n);
                    if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        clicked = true;
                        break;
                    }
                }
            }
            root.recycle();
            if (clicked) {
                Toast.makeText(service, "已进入设备管理员详情", Toast.LENGTH_SHORT).show();
                // 点击"星特安全"成功后，等待800ms进入详情页再查找"启用"按钮
                handler.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                tryAutoClickEnableAdmin(0);
                            }
                        },
                        800);
            }
        } catch (Exception ignored) {
        }
    }

    public void enableAccessibilityByShell() {
        String component = "xiaote.AnQuan/xiaote.AnQuan.AntiLockService";
        String cmd1 = "settings put secure enabled_accessibility_services '" + component + "'";
        String cmd2 = "settings put secure accessibility_enabled 1";
        ShellExecutor.execShizuku(new String[] {"sh", "-c", cmd1 + " && " + cmd2});
        ShellExecutor.execRoot(cmd1 + " && " + cmd2);
    }
}