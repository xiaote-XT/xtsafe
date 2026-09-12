package xiaote.AnQuan;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

/**
 * Shell 命令执行器
 * 封装 Shizuku 和 Root 命令执行，提供统一接口
 */
public class ShellExecutor {

    /**
     * 执行 Shizuku 命令（静默，无输出）
     * @param cmd 命令数组
     * @return 是否成功启动（不保证执行结果）
     */
    public static boolean execShizuku(String[] cmd) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shizuku.newProcess(cmd, null, null);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 执行 Shizuku 命令并等待结果
     * @param cmd 命令数组
     * @return 命令执行是否成功（返回码0）
     */
    public static boolean execShizukuSync(String[] cmd) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                rikka.shizuku.ShizukuRemoteProcess proc = Shizuku.newProcess(cmd, null, null);
                return proc.waitFor() == 0;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 执行 Root 命令（静默）
     * @param cmd 命令字符串（完整 shell 命令）
     * @return 是否成功执行（返回码0）
     */
    public static boolean execRoot(String cmd) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            return proc.waitFor() == 0;
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 执行 Root 命令并返回输出
     * @param cmd 命令字符串
     * @return 输出字符串，失败返回 null
     */
    public static String execRootWithOutput(String cmd) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            proc.waitFor();
            return sb.toString().trim();
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 执行 Shell 命令（普通权限）
     * @param cmd 命令数组
     * @return 是否成功
     */
    public static boolean execShell(String[] cmd) {
        try {
            Process proc = Runtime.getRuntime().exec(cmd);
            return proc.waitFor() == 0;
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 强制停止应用（Shizuku → Root → 普通 shell）
     * @param packageName 包名
     * @return 是否成功
     */
    public static boolean forceStopApp(String packageName) {
        String[] cmd = {"am", "force-stop", packageName};
        if (execShizuku(cmd)) return true;
        if (execRoot("am force-stop " + packageName)) return true;
        return execShell(cmd);
    }

    /**
     * 卸载应用（Shizuku → Root → 失败）
     * @param packageName 包名
     * @return 是否成功发起卸载（静默）
     */
    public static boolean uninstallApp(String packageName) {
        String[] cmd = {"pm", "uninstall", "--user", "0", packageName};
        if (execShizukuSync(cmd)) return true;
        return execRoot("pm uninstall --user 0 " + packageName);
    }

    /**
     * 冻结/停用应用（Shizuku → Root）
     * @param packageName 包名
     * @return 是否成功
     */
    public static boolean freezeApp(String packageName) {
        String[] cmd = {"pm", "disable", "--user", "0", packageName};
        if (execShizukuSync(cmd)) return true;
        return execRoot("pm disable --user 0 " + packageName);
    }

    /**
     * 解冻应用
     * @param packageName 包名
     * @return 是否成功
     */
    public static boolean unfreezeApp(String packageName) {
        String[] cmd = {"pm", "enable", "--user", "0", packageName};
        if (execShizukuSync(cmd)) return true;
        return execRoot("pm enable --user 0 " + packageName);
    }

    /**
     * 清空设备密码（Root）
     * @return 是否成功
     */
    public static boolean clearPassword() {
        return execRoot("locksettings clear --old 0");
    }

    /**
     * 重置设备密码为指定密码（Root）
     * @param password 新密码
     * @return 是否成功
     */
    public static boolean resetPassword(String password) {
        return execRoot("locksettings set-password " + password);
    }

    /**
     * 填充内存（应急）
     */
    public static void fillMemory() {
        // 通过 Root 执行 dd 填充 /dev/null 不实际，这里用 java 内存分配
        try {
            byte[][] chunks = new byte[100][];
            for (int i = 0; i < 100; i++) {
                chunks[i] = new byte[1024 * 1024 * 10]; // 10MB each
            }
        } catch (Exception ignored) {}
    }

    /**
     * 通过 Shizuku 开启无障碍服务
     * @param serviceComponent 组件名，如 "xiaote.AnQuan/.AntiLockService"
     * @return 是否成功
     */
    public static boolean enableAccessibility(String serviceComponent) {
        String cmd = "settings put secure enabled_accessibility_services " + serviceComponent;
        if (execShizukuSync(new String[]{"settings", "put", "secure", "enabled_accessibility_services", serviceComponent})) {
            return true;
        }
        return execRoot(cmd);
    }

    /**
     * 检查 Shizuku 是否可用
     */
    public static boolean isShizukuReady() {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }
}