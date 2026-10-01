package xiaote.AnQuan;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 安全操作集合（「音量超阈值」与「连按十下音量-」触发）。
 *
 * 可多选组合，三项彼此独立：
 *   1. 强制停止所有应用
 *   2. 关闭所有应用无障碍（保留星特安全自身）
 *   3. 冻结拥有无障碍权限应用
 *
 * 关键约定：
 *   · 白名单应用不参与冻结（快照阶段与冻结阶段都会过滤）；
 *   · 「关闭所有应用无障碍」会清空 ENABLED_ACCESSIBILITY_SERVICES，
 *     所以必须在执行前先做包名快照，否则冻结拿不到目标；
 *   · 冻结用 pm disable-user / pm disable 逐级回退，并用输出或
 *     pm list packages -d 回读判定，避免部分 ROM 返回 0 造成误判；
 *   · 执行结果写 filesDir/safe_action.log（超过 2MB 清空重写），不弹 Toast；
 *   · 可通过 Callback 拿到简短执行摘要，供音量键提示使用。
 */
public final class SafeActionManager {

    public static final String PREFS = "dot_config";
    /** 已选操作位掩码 */
    public static final String KEY_ACTIONS = "safe_actions";

    public static final int ACTION_FORCE_STOP_ALL = 1;
    public static final int ACTION_DISABLE_ALL_ACCESSIBILITY = 1 << 1;
    public static final int ACTION_FREEZE_ACCESSIBILITY_APPS = 1 << 2;

    public static final String[] ACTION_NAMES = {
            "强制停止所有应用",
            "关闭所有应用无障碍",
            "冻结拥有无障碍权限应用"
    };
    public static final int[] ACTION_VALUES = {
            ACTION_FORCE_STOP_ALL,
            ACTION_DISABLE_ALL_ACCESSIBILITY,
            ACTION_FREEZE_ACCESSIBILITY_APPS
    };

    /** 默认全选 */
    public static final int DEFAULT_MASK = ACTION_FORCE_STOP_ALL
            | ACTION_DISABLE_ALL_ACCESSIBILITY
            | ACTION_FREEZE_ACCESSIBILITY_APPS;

    private static final String LOG_FILE = "safe_action.log";
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;

    /** 执行完成回调（主线程） */
    public interface Callback {
        void onDone(String summary);
    }

    private SafeActionManager() {}

    // ==================== 配置 ====================

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getMask(Context ctx) {
        if (ctx == null) return DEFAULT_MASK;
        try { return prefs(ctx).getInt(KEY_ACTIONS, DEFAULT_MASK); } catch (Throwable t) { return DEFAULT_MASK; }
    }

    public static void setMask(Context ctx, int mask) {
        if (ctx == null) return;
        try { prefs(ctx).edit().putInt(KEY_ACTIONS, mask).apply(); } catch (Throwable ignored) {}
    }

    public static boolean isEnabled(Context ctx, int action) {
        return (getMask(ctx) & action) != 0;
    }

    /** 已选操作的可读摘要，用于界面提示 */
    public static String describe(Context ctx) {
        int mask = getMask(ctx);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ACTION_VALUES.length; i++) {
            if ((mask & ACTION_VALUES[i]) != 0) {
                if (sb.length() > 0) sb.append("、");
                sb.append(ACTION_NAMES[i]);
            }
        }
        return sb.length() == 0 ? "未选择任何操作" : sb.toString();
    }

    // ==================== 快照 ====================

    /**
     * 收集当前开启了无障碍服务的第三方应用包名。
     * 排除：自身、系统应用、系统预装更新应用、白名单应用。
     * 必须在「关闭所有应用无障碍」之前调用，否则拿到空集合。
     */
    public static Set<String> collectAccessibilityPackages(Context ctx) {
        Set<String> pkgs = new HashSet<String>();
        if (ctx == null) return pkgs;
        try {
            String current = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null) current = "";
            String self = ctx.getPackageName();
            PackageManager pm = ctx.getPackageManager();
            for (String p : current.split(":")) {
                if (p.isEmpty()) continue;
                int idx = p.indexOf('/');
                String pkg = idx > 0 ? p.substring(0, idx) : p;
                if (pkg.isEmpty() || pkg.equals(self)) continue;
                // 白名单应用不参与冻结
                try {
                    if (WhitelistPackages.isWhitelisted(ctx, pkg)) continue;
                } catch (Throwable ignored) {}
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue;
                } catch (Throwable t) {
                    continue;
                }
                pkgs.add(pkg);
            }
        } catch (Throwable ignored) {}
        return pkgs;
    }

    // ==================== 执行 ====================

    /** 执行已选安全操作，不关心结果摘要 */
    public static void run(final Context ctx) {
        run(ctx, null);
    }

    /**
     * 执行已选安全操作。后台线程串行执行，结果写日志；
     * 传入 callback 时在主线程回调简短摘要（供音量键提示使用）。
     */
    public static void run(final Context ctx, final Callback callback) {
        if (ctx == null) return;
        final int mask = getMask(ctx);
        if (mask == 0) {
            if (callback != null) post(callback, "未选择任何操作");
            return;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder log = new StringBuilder();
                StringBuilder brief = new StringBuilder();
                try {
                    if (!ShellExecutor.isShizukuReady()) log.append("Shizuku未就绪; ");

                    // 关键：先快照无障碍包名，再执行关闭无障碍，否则冻结拿不到目标
                    Set<String> accSnapshot = collectAccessibilityPackages(app);
                    // 并入用户手动维护的「冻结应用」名单（白名单在 freezePackages 内统一过滤）
                    try { accSnapshot.addAll(FrozenPackages.get(app)); } catch (Throwable ignored) {}

                    if ((mask & ACTION_FORCE_STOP_ALL) != 0) {
                        int n = forceStopAll(app);
                        log.append("强制停止所有应用 ").append(n).append(" 个; ");
                        brief.append("停止 ").append(n).append(" 个");
                    }
                    if ((mask & ACTION_DISABLE_ALL_ACCESSIBILITY) != 0) {
                        int n = disableAllAccessibility(app);
                        log.append("关闭其他无障碍 ").append(n).append(" 个; ");
                        if (brief.length() > 0) brief.append("、");
                        brief.append("关无障碍 ").append(n).append(" 个");
                    }
                    if ((mask & ACTION_FREEZE_ACCESSIBILITY_APPS) != 0) {
                        log.append("待冻结无障碍应用 ").append(accSnapshot.size()).append(" 个 ");
                        if (!accSnapshot.isEmpty()) log.append(accSnapshot);
                        log.append("; ");
                        int n = freezePackages(app, accSnapshot);
                        log.append("冻结成功 ").append(n).append(" 个; ");
                        if (brief.length() > 0) brief.append("、");
                        brief.append("冻结 ").append(n).append(" 个");
                    }
                } catch (Throwable t) {
                    log.append("异常 ").append(t.getClass().getSimpleName()).append(": ")
                            .append(t.getMessage()).append("; ");
                }
                appendLog(app, log.toString());
                if (callback != null) {
                    post(callback, brief.length() == 0 ? "执行完成" : brief.toString());
                }
            }
        }, "safe-action").start();
    }

    private static void post(final Callback cb, final String msg) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try { cb.onDone(msg); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 强制停止全部第三方应用（排除自身与系统应用） */
    public static int forceStopAll(Context ctx) {
        int count = 0;
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            String self = ctx.getPackageName();
            for (ApplicationInfo ai : apps) {
                if (ai == null) continue;
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (self.equals(ai.packageName)) continue;
                boolean ok = ShellExecutor.execShizuku(new String[]{"am", "force-stop", ai.packageName});
                if (!ok) ok = ShellExecutor.execRoot("am force-stop " + ai.packageName);
                if (ok) count++;
            }
        } catch (Throwable ignored) {}
        return count;
    }

    /** 关闭除星特安全以外的全部无障碍服务 */
    public static int disableAllAccessibility(Context ctx) {
        int removed = 0;
        try {
            String current = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null) current = "";
            String self = ctx.getPackageName();
            StringBuilder keep = new StringBuilder();
            for (String p : current.split(":")) {
                if (p.isEmpty()) continue;
                int idx = p.indexOf('/');
                String pkg = idx > 0 ? p.substring(0, idx) : p;
                if (pkg.equals(self)) {
                    if (keep.length() > 0) keep.append(":");
                    keep.append(p);
                } else {
                    removed++;
                }
            }
            if (removed == 0) return 0;
            String result = keep.toString();
            boolean ok = ShellExecutor.execShizukuSync(new String[]{"settings", "put", "secure",
                    "enabled_accessibility_services", result});
            if (!ok) {
                ShellExecutor.execRoot("settings put secure enabled_accessibility_services '" + result + "'");
            }
        } catch (Throwable ignored) {}
        return removed;
    }

    // ==================== 冻结 ====================

    /** 冻结指定包名集合（白名单跳过），返回成功数量 */
    public static int freezePackages(Context ctx, Set<String> pkgs) {
        int count = 0;
        if (ctx == null || pkgs == null || pkgs.isEmpty()) return 0;
        List<String> targets = new ArrayList<String>(pkgs);
        for (String pkg : targets) {
            if (pkg == null || pkg.isEmpty()) continue;
            // 白名单应用不参与冻结
            try {
                if (WhitelistPackages.isWhitelisted(ctx, pkg)) continue;
            } catch (Throwable ignored) {}
            try {
                if (freezeOne(pkg)) count++;
            } catch (Throwable ignored) {}
        }
        return count;
    }

    /**
     * 冻结单个包：force-stop + 逐级尝试 disable，并用输出/回读双重判定。
     * 私有实现，供内部与公开包装共用。
     */
    private static boolean freezeOne(String pkg) {
        // 先停进程，避免 disable 期间被拉起
        try { ShellExecutor.execShizuku(new String[]{"am", "force-stop", pkg}); } catch (Throwable ignored) {}
        try { ShellExecutor.execRoot("am force-stop " + pkg); } catch (Throwable ignored) {}

        // 1. Shizuku: pm disable-user --user 0
        if (looksDisabled(execWithOutput("pm disable-user --user 0 " + pkg), pkg)) return true;
        // 2. Shizuku: pm disable --user 0
        if (looksDisabled(execWithOutput("pm disable --user 0 " + pkg), pkg)) return true;
        // 3. Root 兜底
        if (looksDisabled(execRootWithOutput("pm disable-user --user 0 " + pkg), pkg)) return true;
        if (looksDisabled(execRootWithOutput("pm disable --user 0 " + pkg), pkg)) return true;
        return false;
    }

    /** 公开冻结单个包（供名单管理「冻结应用」调用） */
    public static boolean freezeOnePublic(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return freezeOne(pkg);
    }

    /** 公开解冻单个包（供名单管理「冻结应用」调用） */
    public static boolean unfreezeOnePublic(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try { ShellExecutor.execShizuku(new String[]{"am", "force-stop", pkg}); } catch (Throwable ignored) {}
        // 1. Shizuku: pm enable --user 0
        if (looksEnabled(execWithOutput("pm enable --user 0 " + pkg), pkg)) return true;
        // 2. Shizuku: pm enable
        if (looksEnabled(execWithOutput("pm enable " + pkg), pkg)) return true;
        // 3. Root 兜底
        if (looksEnabled(execRootWithOutput("pm enable --user 0 " + pkg), pkg)) return true;
        if (looksEnabled(execRootWithOutput("pm enable " + pkg), pkg)) return true;
        return false;
    }

    /** 通过 Shizuku 执行 shell 并返回 stdout+stderr（失败返回 null） */
    private static String execWithOutput(String cmd) {
        try {
            return ShellExecutor.execShizukuWithOutput(
                    new String[]{"sh", "-c", cmd + " 2>&1"});
        } catch (Throwable t) {
            return null;
        }
    }

    /** 通过 Root 执行 shell 并返回输出 */
    private static String execRootWithOutput(String cmd) {
        try {
            return ShellExecutor.execRootWithOutput(cmd + " 2>&1");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 判定 disable 命令是否成功：
     *   · 输出含 "new state: disabled" → 成功
     *   · 输出含 error / exception / denial / not found → 失败
     *   · 其他情况（部分 ROM 不输出）→ 用 pm list packages -d 回读校验
     */
    private static boolean looksDisabled(String out, String pkg) {
        if (out != null) {
            String t = out.toLowerCase(Locale.US);
            if (t.contains("new state: disabled")) return true;
            if (t.contains("error") || t.contains("exception")
                    || t.contains("denial") || t.contains("not found")) return false;
        }
        return isDisabled(pkg);
    }

    /**
     * 判定 enable 命令是否成功：
     *   · 输出含 "new state: enabled" → 成功
     *   · 输出含 error / exception / denial / not found → 失败
     *   · 其他情况回读 pm list packages -d，不在禁用列表即视为已启用
     */
    private static boolean looksEnabled(String out, String pkg) {
        if (out != null) {
            String t = out.toLowerCase(Locale.US);
            if (t.contains("new state: enabled")) return true;
            if (t.contains("error") || t.contains("exception")
                    || t.contains("denial") || t.contains("not found")) return false;
        }
        return !isDisabled(pkg);
    }

    /** 读取系统内全部已被禁用（冻结）的包名 */
    public static Set<String> listDisabledPackages(Context ctx) {
        Set<String> set = new HashSet<String>();
        try {
            String out = ShellExecutor.execShizukuWithOutput(
                    new String[]{"sh", "-c", "pm list packages -d 2>/dev/null | sed 's/^package://'"});
            if (out == null || out.trim().isEmpty()) {
                out = ShellExecutor.execRootWithOutput("pm list packages -d | sed 's/^package://'");
            }
            if (out != null) {
                for (String line : out.split("\\r?\\n")) {
                    String p = line.trim();
                    if (!p.isEmpty()) set.add(p);
                }
            }
        } catch (Throwable ignored) {}
        return set;
    }

    /** 回读某个包是否已被禁用（pm list packages -d） */
    private static boolean isDisabled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            String out = ShellExecutor.execShizukuWithOutput(
                    new String[]{"sh", "-c", "pm list packages -d 2>/dev/null | sed 's/^package://'"});
            if (out == null || out.trim().isEmpty()) {
                out = ShellExecutor.execRootWithOutput("pm list packages -d | sed 's/^package://'");
            }
            if (out == null) return false;
            for (String line : out.split("\\r?\\n")) {
                if (pkg.equals(line.trim())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 兼容旧接口：无快照时自行读取当前无障碍包名并冻结。
     * 正常路径请走 run()，它使用执行前的快照。
     */
    public static int freezeAccessibilityApps(Context ctx) {
        return freezePackages(ctx, collectAccessibilityPackages(ctx));
    }

    // ==================== 日志 ====================

    public static File logFile(Context ctx) {
        if (ctx == null) return null;
        try { return new File(ctx.getFilesDir(), LOG_FILE); } catch (Throwable t) { return null; }
    }

    /** 追加一条执行日志；超过 2MB 直接清空重写，保证不会无限增长 */
    public static void appendLog(Context ctx, String message) {
        if (ctx == null || message == null) return;
        FileOutputStream fos = null;
        try {
            File f = logFile(ctx);
            if (f == null) return;
            if (f.exists() && f.length() > MAX_LOG_BYTES) {
                fos = new FileOutputStream(f, false);
                fos.write(new byte[0]);
                fos.close();
                fos = null;
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            fos = new FileOutputStream(f, true);
            fos.write((time + " " + message + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
