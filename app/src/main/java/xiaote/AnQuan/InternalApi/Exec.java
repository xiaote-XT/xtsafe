package xiaote.AnQuan.InternalApi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicInteger;

import rikka.shizuku.Shizuku;

/**
 * shell 通道：把一条命令分别交给 Shizuku / Root 执行。
 *
 * ===== 为什么没有 Dhizuku / DeviceOwner 分支 =====
 *
 * Dhizuku 服务端是一个普通应用进程，只是额外持有设备所有者身份；
 * 它的 newProcess 起出来的子进程 uid 仍是应用 uid，跑不了 pm / am /
 * settings / dumpsys。DevicePolicyManager 也只暴露 DPM 那套接口，
 * 没有执行任意 shell 的能力。
 *
 * 所以凡是「执行命令」的操作，可用通道只有 Shizuku 与 Root 两级，
 * 这里不硬写第三、第四级。
 *
 * 所有方法都是阻塞式跨进程调用，必须在工作线程使用。
 */
public final class Exec {

    /** 单条命令默认超时。su 未授权时会弹窗等用户点允许，必须限时，否则挂死调用线程 */
    public static final long DEFAULT_TIMEOUT_MS = 8000L;

    /** 超时哨兵值，与真实退出码区分开 */
    private static final int EXIT_TIMEOUT = Integer.MIN_VALUE;

    private Exec() {}

    /** 能跑 shell 的通道，按优先级排列：Shizuku → Root */
    public static Capability[] shellChain() {
        return new Capability[]{ Capability.SHIZUKU, Capability.ROOT };
    }

    // ==================== 结果 ====================

    /** 一次命令执行的结果 */
    public static final class Out {
        public final boolean ok;
        /** stdout + stderr，已 trim */
        public final String text;
        /** 退出码；EXIT_TIMEOUT 表示超时，-1 表示启动失败 */
        public final int exit;

        Out(boolean ok, String text, int exit) {
            this.ok = ok;
            this.text = text;
            this.exit = exit;
        }

        public boolean timeout() {
            return exit == EXIT_TIMEOUT;
        }

        @Override
        public String toString() {
            if (timeout()) return "超时";
            String body = (text == null || text.isEmpty()) ? "" : ("：" + text);
            return (ok ? "成功" : "失败") + body;
        }
    }

    // ==================== 单通道 ====================

    public static Out run(Capability cap, String cmd) {
        return run(cap, cmd, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 用指定通道执行命令。
     *
     * 该通道不具备 shell 能力时返回 null —— 与「执行失败」严格区分，
     * 调用方据此判断是「换个通道再试」还是「这条通道确实不支持」。
     */
    public static Out run(Capability cap, String cmd, long timeoutMs) {
        if (cap == null || cmd == null || cmd.length() == 0) return null;
        if (cap == Capability.SHIZUKU) return runShizuku(cmd, timeoutMs);
        if (cap == Capability.ROOT) return runRoot(cmd, timeoutMs);
        return null;
    }

    /** Shizuku 通道：uid 2000，pm / am / settings / dumpsys 都能跑 */
    public static Out runShizuku(String cmd, long timeoutMs) {
        if (!Capability.isShizukuReady()) return new Out(false, "Shizuku 未就绪", -1);
        rikka.shizuku.ShizukuRemoteProcess p = null;
        try {
            p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            if (p == null) return new Out(false, "newProcess 返回 null", -1);

            final rikka.shizuku.ShizukuRemoteProcess fp = p;
            final StringBuilder sb = new StringBuilder();
            final AtomicInteger exit = new AtomicInteger(EXIT_TIMEOUT);

            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(fp.getInputStream()));
                        String l;
                        while ((l = r.readLine()) != null) sb.append(l).append('\n');
                        r.close();
                        BufferedReader e = new BufferedReader(
                                new InputStreamReader(fp.getErrorStream()));
                        while ((l = e.readLine()) != null) sb.append(l).append('\n');
                        e.close();
                        exit.set(fp.waitFor());
                    } catch (Throwable ignored) {}
                }
            });
            reader.setDaemon(true);
            reader.start();
            reader.join(timeoutMs);

            int code = exit.get();
            if (code == EXIT_TIMEOUT) return new Out(false, trim(sb), EXIT_TIMEOUT);
            return new Out(code == 0, trim(sb), code);
        } catch (Throwable t) {
            return new Out(false, DpmChannel.describe(t), -1);
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Root 通道：uid 0 */
    public static Out runRoot(String cmd, long timeoutMs) {
        if (!Capability.isRootReady()) return new Out(false, "Root 未就绪", -1);
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            final Process fp = p;
            final StringBuilder sb = new StringBuilder();
            final AtomicInteger exit = new AtomicInteger(EXIT_TIMEOUT);

            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(fp.getInputStream()));
                        String l;
                        while ((l = r.readLine()) != null) sb.append(l).append('\n');
                        r.close();
                        BufferedReader e = new BufferedReader(
                                new InputStreamReader(fp.getErrorStream()));
                        while ((l = e.readLine()) != null) sb.append(l).append('\n');
                        e.close();
                        exit.set(fp.waitFor());
                    } catch (Throwable ignored) {}
                }
            });
            reader.setDaemon(true);
            reader.start();
            reader.join(timeoutMs);

            int code = exit.get();
            if (code == EXIT_TIMEOUT) return new Out(false, trim(sb), EXIT_TIMEOUT);
            return new Out(code == 0, trim(sb), code);
        } catch (Throwable t) {
            return new Out(false, DpmChannel.describe(t), -1);
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 不带提权直接跑（本应用自身的权限）。
     * 只用于那些应用自己就能做的事，不要拿它冒充提权通道。
     */
    public static Out runPlain(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            r.close();
            int code = p.waitFor();
            return new Out(code == 0, trim(sb), code);
        } catch (Throwable t) {
            return new Out(false, DpmChannel.describe(t), -1);
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    // ==================== 链式（只含 shell 两级） ====================

    /**
     * 按 shellChain 顺序执行，返回第一条成功的通道与输出。
     *
     * 失败时 ApiResult.via 是最后尝试的通道，detail 汇总每一级的失败原因，
     * 便于一眼看出是「都没授权」还是「都授权了但被系统拒绝」。
     */
    public static ApiResult runFirst(String cmd) {
        if (cmd == null || cmd.length() == 0) return ApiResult.unsupported("命令为空");
        Capability last = null;
        StringBuilder err = new StringBuilder();
        for (Capability c : shellChain()) {
            last = c;
            if (!c.isReady(null)) {
                err.append(c.label).append(" 未就绪; ");
                continue;
            }
            Out o = run(c, cmd);
            if (o != null && o.ok) return ApiResult.success(c, o.text);
            err.append(c.label).append(' ').append(o == null ? "不支持" : o.toString()).append("; ");
        }
        return ApiResult.failure(last, err.toString());
    }

    /** 按 shellChain 顺序执行，返回第一条成功的输出；全失败返回 null */
    public static String runFirstText(String cmd) {
        if (cmd == null || cmd.length() == 0) return null;
        for (Capability c : shellChain()) {
            if (!c.isReady(null)) continue;
            Out o = run(c, cmd);
            if (o != null && o.ok) return o.text;
        }
        return null;
    }

    // ==================== 工具 ====================

    private static String trim(StringBuilder sb) {
        if (sb == null) return "";
        return sb.toString().trim();
    }
}
