package xiaote.AnQuan;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * 阻止卸载管理器（shell service call 路径）。
 *
 * 通过 Shizuku（uid 2000）执行
 *   service call package &lt;TID&gt; s16 &lt;pkg&gt; i32 &lt;1|0&gt; i32 0
 * 调用 PackageManager 隐藏事务 setBlockUninstallForUser。
 *
 * 两种周期任务（互斥，不能同时开启）：
 *   1. 阻止卸载（i32 1）：阻止所有应用被卸载，防止恶意应用卸载游戏。
 *   2. 阻止阻止卸载（i32 0）：阻止一些应用拦截卸载（不含无障碍自动点击拦截），
 *      避免恶意应用无法被卸载。
 *
 * 检查频率以 100 毫秒为一个 tick，最低 0.1 秒执行一次。
 * 自动执行会写入日志（filesDir/block_uninstall.log），超过 2MB 自动清空重写，
 * 不弹 Toast。
 */
public final class BlockUninstallManager {

    public static final String PREFS = "dot_config";
    /** 用户覆盖的事务号（未覆盖时不存在） */
    public static final String KEY_TID = "shizuku_tid";
    /** 是否开启“阻止卸载”周期任务 */
    public static final String KEY_BATCH_ENABLE = "block_other_uninstall";
    /** “阻止卸载”检查频率（tick，1 tick = 100ms） */
    public static final String KEY_BATCH_INTERVAL_TICKS = "block_other_uninstall_ticks";
    /** 上次“阻止卸载”执行时间 */
    public static final String KEY_BATCH_LAST = "block_other_uninstall_last";
    /** 是否开启“阻止阻止卸载”周期任务 */
    public static final String KEY_UNBLOCK_ENABLE = "unblock_other_blocked";
    /** “阻止阻止卸载”检查频率（tick，1 tick = 100ms） */
    public static final String KEY_UNBLOCK_INTERVAL_TICKS = "unblock_other_blocked_ticks";
    /** 上次“阻止阻止卸载”执行时间 */
    public static final String KEY_UNBLOCK_LAST = "unblock_other_blocked_last";
    /** 上次自身阻止状态（回读失败时的兜底显示） */
    public static final String KEY_SELF_BLOCKED = "shizuku_block_uninstall";

    /** 一个 tick = 100 毫秒，即最低 0.1 秒执行一次 */
    public static final long TICK_MS = 100L;
    public static final int MIN_INTERVAL_TICKS = 1;
    /** 上限 120 分钟 */
    public static final int MAX_INTERVAL_TICKS = 72000;
    public static final int DEFAULT_INTERVAL_TICKS = 3000;         // 5 分钟
    public static final int DEFAULT_UNBLOCK_INTERVAL_TICKS = 6000; // 10 分钟

    /** 自动执行日志文件 */
    public static final String LOG_FILE = "block_uninstall.log";
    /** 日志大小上限 2MB，超出即清空重写 */
    public static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;

    private BlockUninstallManager() {}

    // ==================== 事务号 ====================

    /** 按 SDK 推导 service call package 的事务号；未收录返回 -1 */
    public static int defaultTid() {
        switch (Build.VERSION.SDK_INT) {
            case 28: return 151;
            case 29: return 156;
            case 30: case 31: case 32: return 136;
            case 33: return 133;
            case 34: return 134;
            case 35: return 138;
            case 36: return 139;
            default: return -1;
        }
    }

    /** 实际使用的事务号：用户覆盖优先，否则按 SDK 推导 */
    public static int getTid(Context ctx) {
        if (ctx == null) return defaultTid();
        try {
            int v = prefs(ctx).getInt(KEY_TID, -1);
            if (v > 0) return v;
        } catch (Throwable ignored) {}
        return defaultTid();
    }

    /** 记录用户覆盖的事务号；传 &lt;=0 表示清除覆盖、回到 SDK 推导 */
    public static void setTidOverride(Context ctx, int tid) {
        if (ctx == null) return;
        try {
            SharedPreferences.Editor e = prefs(ctx).edit();
            if (tid > 0 && tid != defaultTid()) e.putInt(KEY_TID, tid);
            else e.remove(KEY_TID);
            e.apply();
        } catch (Throwable ignored) {}
    }

    /** 是否处于用户手动覆盖事务号的状态 */
    public static boolean hasTidOverride(Context ctx) {
        if (ctx == null) return false;
        try { return prefs(ctx).getInt(KEY_TID, -1) > 0; } catch (Throwable ignored) { return false; }
    }

    // ==================== 基础 ====================

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 执行 shell 命令，返回 stdout + stderr；Shizuku 不可用返回 null */
    public static String exec(String cmd) {
        ShizukuRemoteProcess proc = null;
        try {
            if (!isShizukuReady()) return null;
            proc = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            StringBuilder sb = new StringBuilder();
            BufferedReader r1 = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String l;
            while ((l = r1.readLine()) != null) sb.append(l).append("\n");
            r1.close();
            BufferedReader r2 = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
            while ((l = r2.readLine()) != null) sb.append(l).append("\n");
            r2.close();
            proc.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 执行 service call，Shizuku 不行再走 Root；返回原始输出（可能为 null） */
    public static String execCall(String cmd) {
        String out = exec(cmd);
        if (isParcelOk(out)) return out;
        try {
            String rout = ShellExecutor.execRootWithOutput(cmd);
            if (isParcelOk(rout)) return rout;
        } catch (Throwable ignored) {}
        return out;
    }

    /** service call 成功判据：异常码为 0 */
    public static boolean isParcelOk(String out) {
        return out != null && out.contains("Parcel(00000000");
    }

    // ==================== 单包操作 ====================

    /** 阻止 / 取消阻止单个包。返回是否调用成功 */
    public static boolean setBlocked(Context ctx, String pkg, boolean blocked) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return false;
        int tid = getTid(ctx);
        if (tid <= 0) return false;
        String cmd = "service call package " + tid
                + " s16 " + pkg
                + " i32 " + (blocked ? 1 : 0) + " i32 0";
        return isParcelOk(execCall(cmd));
    }

    // ==================== 真实回读 ====================

    /** 读指定包是否被阻止卸载；读不到返回 null */
    public static Boolean readBlocked(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        String script = "if [ -r /data/system/packages.xml ]; then "
                + "sed -n '\\#<package name=\"" + pkg + "\"#,\\#</package>#p' /data/system/packages.xml 2>/dev/null "
                + "| grep -cE 'block-uninstall[^>]*userId=\"0\"'; "
                + "else echo NA; fi";
        Boolean r = parseCount(exec(script));
        if (r != null) return r;
        try {
            r = parseCount(ShellExecutor.execRootWithOutput(script));
        } catch (Throwable ignored) {}
        return r;
    }

    private static Boolean parseCount(String out) {
        if (out == null) return null;
        String t = out.trim();
        if (t.isEmpty()) return null;
        String[] lines = t.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (l.equalsIgnoreCase("NA")) return null;
            if (l.matches("\\d+")) {
                try { return Integer.parseInt(l) > 0; } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /** 读取系统内被阻止卸载的包数量：{总数, 其中自身}；读不到返回 null */
    public static int[] readBlockedCounts(Context ctx) {
        if (ctx == null) return null;
        String pkg = ctx.getPackageName();
        String script = "if [ -r /data/system/packages.xml ]; then "
                + "f=/data/system/packages.xml; "
                + "echo \"XT_TOTAL $(grep -c 'block-uninstall' $f 2>/dev/null)\"; "
                + "echo \"XT_SELF $(sed -n '\\#<package name=\"" + pkg + "\"#,\\#</package>#p' $f 2>/dev/null | grep -c 'block-uninstall')\"; "
                + "else echo NA; fi";
        int[] r = parseCounts(exec(script));
        if (r != null) return r;
        try {
            r = parseCounts(ShellExecutor.execRootWithOutput(script));
        } catch (Throwable ignored) {}
        return r;
    }

    private static int[] parseCounts(String out) {
        if (out == null) return null;
        int total = -1, self = -1;
        for (String line : out.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("XT_TOTAL ")) {
                try { total = Integer.parseInt(l.substring(9).trim()); } catch (Exception ignored) {}
            } else if (l.startsWith("XT_SELF ")) {
                try { self = Integer.parseInt(l.substring(8).trim()); } catch (Exception ignored) {}
            } else if (l.equalsIgnoreCase("NA")) {
                return null;
            }
        }
        if (total < 0) return null;
        if (self < 0) self = 0;
        return new int[]{total, self};
    }

    // ==================== 批量操作 ====================

    /** 需要处理的第三方应用包名（排除系统应用与自身） */
    public static List<String> listThirdParty(Context ctx) {
        List<String> list = new ArrayList<String>();
        if (ctx == null) return list;
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            String self = ctx.getPackageName();
            for (ApplicationInfo ai : apps) {
                if (ai == null) continue;
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue;
                if (self.equals(ai.packageName)) continue;
                list.add(ai.packageName);
            }
        } catch (Throwable ignored) {}
        return list;
    }

    /** 批量命令：一次 shell 进程内遍历第三方应用，排除自身，逐个 service call */
    public static String buildBatchCmd(Context ctx, boolean blocked) {
        int tid = getTid(ctx);
        String self = ctx.getPackageName();
        return "TID=" + tid + "; SELF=" + self + "; "
                + "for p in $(pm list packages -3 | sed 's/^package://'); do "
                + "[ \"$p\" = \"$SELF\" ] && continue; "
                + "r=$(service call package $TID s16 \"$p\" i32 " + (blocked ? 1 : 0) + " i32 0 2>/dev/null); "
                + "case \"$r\" in *00000001*) echo \"XT_OK $p\";; *) echo \"XT_FAIL $p\";; esac; "
                + "done; echo XT_DONE";
    }

    /** 执行批量阻止 / 取消阻止，返回 {成功数, 失败数}；执行失败返回 null */
    public static int[] runBatch(Context ctx, boolean blocked) {
        if (ctx == null) return null;
        if (getTid(ctx) <= 0) return null;
        String cmd = buildBatchCmd(ctx, blocked);
        int[] r = parseBatch(exec(cmd));
        if (r != null) return r;
        try {
            r = parseBatch(ShellExecutor.execRootWithOutput(cmd));
        } catch (Throwable ignored) {}
        return r;
    }

    private static int[] parseBatch(String out) {
        if (out == null) return null;
        int ok = 0, fail = 0;
        boolean done = false;
        for (String line : out.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("XT_OK ")) ok++;
            else if (l.startsWith("XT_FAIL ")) fail++;
            else if (l.equals("XT_DONE")) done = true;
        }
        if (!done && ok == 0 && fail == 0) return null;
        return new int[]{ok, fail};
    }

    // ==================== 开关与频率 ====================

    /** 是否开启“阻止卸载”周期任务 */
    public static boolean isBatchEnabled(Context ctx) {
        if (ctx == null) return false;
        try { return prefs(ctx).getBoolean(KEY_BATCH_ENABLE, false); } catch (Throwable t) { return false; }
    }

    /**
     * 设置“阻止卸载”。与“阻止阻止卸载”互斥：
     * 开启本项时会自动关闭另一项，避免两套相反的批量命令互相覆盖。
     */
    public static void setBatchEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        try {
            SharedPreferences.Editor e = prefs(ctx).edit();
            e.putBoolean(KEY_BATCH_ENABLE, enabled);
            if (enabled) e.putBoolean(KEY_UNBLOCK_ENABLE, false);
            e.apply();
        } catch (Throwable ignored) {}
    }

    /** 是否开启“阻止阻止卸载”周期任务 */
    public static boolean isUnblockBatchEnabled(Context ctx) {
        if (ctx == null) return false;
        try { return prefs(ctx).getBoolean(KEY_UNBLOCK_ENABLE, false); } catch (Throwable t) { return false; }
    }

    /**
     * 设置“阻止阻止卸载”。与“阻止卸载”互斥：
     * 开启本项时会自动关闭另一项。
     */
    public static void setUnblockBatchEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        try {
            SharedPreferences.Editor e = prefs(ctx).edit();
            e.putBoolean(KEY_UNBLOCK_ENABLE, enabled);
            if (enabled) e.putBoolean(KEY_BATCH_ENABLE, false);
            e.apply();
        } catch (Throwable ignored) {}
    }

    private static int clampTicks(int v) {
        if (v < MIN_INTERVAL_TICKS) return MIN_INTERVAL_TICKS;
        if (v > MAX_INTERVAL_TICKS) return MAX_INTERVAL_TICKS;
        return v;
    }

    /** “阻止卸载”检查频率（tick，1 tick = 100ms） */
    public static int getBatchIntervalTicks(Context ctx) {
        if (ctx == null) return DEFAULT_INTERVAL_TICKS;
        try {
            return clampTicks(prefs(ctx).getInt(KEY_BATCH_INTERVAL_TICKS, DEFAULT_INTERVAL_TICKS));
        } catch (Throwable t) { return DEFAULT_INTERVAL_TICKS; }
    }

    public static void setBatchIntervalTicks(Context ctx, int ticks) {
        if (ctx == null) return;
        try { prefs(ctx).edit().putInt(KEY_BATCH_INTERVAL_TICKS, clampTicks(ticks)).apply(); } catch (Throwable ignored) {}
    }

    /** “阻止阻止卸载”检查频率（tick，1 tick = 100ms） */
    public static int getUnblockBatchIntervalTicks(Context ctx) {
        if (ctx == null) return DEFAULT_UNBLOCK_INTERVAL_TICKS;
        try {
            return clampTicks(prefs(ctx).getInt(KEY_UNBLOCK_INTERVAL_TICKS, DEFAULT_UNBLOCK_INTERVAL_TICKS));
        } catch (Throwable t) { return DEFAULT_UNBLOCK_INTERVAL_TICKS; }
    }

    public static void setUnblockBatchIntervalTicks(Context ctx, int ticks) {
        if (ctx == null) return;
        try { prefs(ctx).edit().putInt(KEY_UNBLOCK_INTERVAL_TICKS, clampTicks(ticks)).apply(); } catch (Throwable ignored) {}
    }

    /** 把 tick 数格式化成可读的“0.1 秒 / 3 秒 / 5 分钟” */
    public static String formatInterval(int ticks) {
        long ms = (long) clampTicks(ticks) * TICK_MS;
        if (ms < 1000L) {
            return String.format(Locale.US, "%.1f 秒", ms / 1000.0);
        }
        if (ms < 60000L) {
            double s = ms / 1000.0;
            if (Math.abs(s - Math.rint(s)) < 0.05) return ((long) Math.rint(s)) + " 秒";
            return String.format(Locale.US, "%.1f 秒", s);
        }
        double min = ms / 60000.0;
        if (Math.abs(min - Math.rint(min)) < 0.01) return ((long) Math.rint(min)) + " 分钟";
        return String.format(Locale.US, "%.1f 分钟", min);
    }

    /** 自身阻止状态（回读失败时的兜底显示） */
    public static boolean isSelfBlocked(Context ctx) {
        if (ctx == null) return false;
        try { return prefs(ctx).getBoolean(KEY_SELF_BLOCKED, false); } catch (Throwable t) { return false; }
    }

    public static void setSelfBlocked(Context ctx, boolean blocked) {
        if (ctx == null) return;
        try { prefs(ctx).edit().putBoolean(KEY_SELF_BLOCKED, blocked).apply(); } catch (Throwable ignored) {}
    }

    // ==================== 自动执行日志 ====================

    /** 日志文件 */
    public static File logFile(Context ctx) {
        if (ctx == null) return null;
        try { return new File(ctx.getFilesDir(), LOG_FILE); } catch (Throwable t) { return null; }
    }

    /**
     * 追加一条自动执行日志。
     * 文件超过 2MB 时先清空再写，保证日志不会无限增长。
     */
    public static void appendLog(Context ctx, String message) {
        if (ctx == null || message == null) return;
        FileOutputStream fos = null;
        try {
            File f = logFile(ctx);
            if (f == null) return;
            if (f.exists() && f.length() > MAX_LOG_BYTES) {
                // 超过上限：清空重写（不使用删除操作，直接截断）
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

    // ==================== 周期性执行 ====================

    /**
     * 供服务调度线程高频调用（最低 100ms 一次），内部自带节流，
     * 只有到达设定间隔才真正执行批量命令。
     * 自动执行结果写入日志，不弹 Toast。
     * 两项互斥，即使配置异常同时为真也只会执行“阻止卸载”。
     */
    public static void maybeRunPeriodic(final Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences p = prefs(ctx);
            if (getTid(ctx) <= 0) return;
            long now = System.currentTimeMillis();

            boolean blockOn = p.getBoolean(KEY_BATCH_ENABLE, false);
            boolean unblockOn = p.getBoolean(KEY_UNBLOCK_ENABLE, false);
            // 互斥：同时开启时只执行“阻止卸载”
            if (blockOn) {
                int interval = clampTicks(p.getInt(KEY_BATCH_INTERVAL_TICKS, DEFAULT_INTERVAL_TICKS));
                long last = p.getLong(KEY_BATCH_LAST, 0L);
                if (now - last >= interval * TICK_MS) {
                    // 先写时间戳，避免并发重复触发
                    p.edit().putLong(KEY_BATCH_LAST, now).apply();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            int[] r = null;
                            String err = null;
                            try {
                                r = runBatch(ctx, true);
                            } catch (Throwable t) {
                                err = t.getClass().getSimpleName();
                            }
                            if (err != null) {
                                appendLog(ctx, "自动阻止卸载异常：" + err);
                            } else if (r == null) {
                                appendLog(ctx, "自动阻止卸载：执行失败（检查 Shizuku 权限与设备所有者）");
                            } else {
                                appendLog(ctx, "自动阻止卸载：成功 " + r[0] + " 个，失败 " + r[1] + " 个");
                            }
                        }
                    }, "block-uninstall-batch").start();
                }
            } else if (unblockOn) {
                int interval = clampTicks(p.getInt(KEY_UNBLOCK_INTERVAL_TICKS, DEFAULT_UNBLOCK_INTERVAL_TICKS));
                long last = p.getLong(KEY_UNBLOCK_LAST, 0L);
                if (now - last >= interval * TICK_MS) {
                    p.edit().putLong(KEY_UNBLOCK_LAST, now).apply();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            int[] r = null;
                            String err = null;
                            try {
                                r = runBatch(ctx, false);
                            } catch (Throwable t) {
                                err = t.getClass().getSimpleName();
                            }
                            if (err != null) {
                                appendLog(ctx, "自动阻止阻止卸载异常：" + err);
                            } else if (r == null) {
                                appendLog(ctx, "自动阻止阻止卸载：执行失败（检查 Shizuku 权限与设备所有者）");
                            } else {
                                appendLog(ctx, "自动阻止阻止卸载：成功 " + r[0] + " 个，失败 " + r[1] + " 个");
                            }
                        }
                    }, "unblock-uninstall-batch").start();
                }
            }
        } catch (Throwable ignored) {}
    }
}
