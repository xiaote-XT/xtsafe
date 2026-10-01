package xiaote.AnQuan.SmallTool.ProcessManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import xiaote.AnQuan.ShellExecutor;

/**
 * 后台进程扫描器。
 *
 * 通过 Shizuku 执行：
 *   ps -A -o PID,NAME,RSS | sort -k3 -n -r | head -80
 *
 * ps 输出每行三列（空格分隔，首列右对齐）：
 *   PID  NAME  RSS(KB)
 * 表头行 PID/NAME/RSS 与非数字开头的行一律跳过。
 *
 * ===== 为什么不再用 grep 前缀过滤 =====
 *
 * 早期版本用 `grep -E 'com\.|org\.|me\.|bin\.|top\.|hello\.'` 过滤，实测会漏掉三类进程：
 *   1. 非 com./org. 前缀的第三方，如 xiaote.AnQuan、shizuku_server、rikka.appops；
 *   2. 系统原生进程，如 system_server、surfaceflinger、zygote64、netd、installd；
 *   3. 全部 android.hardware.* HAL 进程与 android.ext.services。
 * 同时 head -40 会把真实占用榜上靠前的进程挤掉。
 *
 * 现在不做任何前缀过滤，只按 RSS 降序取前 80，保证覆盖完整。
 * 进程名不是包名的行（system_server 等）由界面层 fillAppInfo 标记为未知应用，
 * 只列出、不提供操作入口。
 */
public final class ProcessScanner {

    /** 全量进程，按常驻内存降序取前 80；不做包名前缀过滤 */
    private static final String CMD =
            "ps -A -o PID,NAME,RSS | sort -k3 -n -r | head -80";

    private ProcessScanner() {}

    /** 是否具备执行条件（Shizuku 已授权） */
    public static boolean isAvailable() {
        return ShellExecutor.isShizukuReady();
    }

    /** 同步执行扫描，失败返回空列表（必须在工作线程调用） */
    public static List<ProcessItem> scan() {
        List<ProcessItem> list = new ArrayList<ProcessItem>();
        String out = ShellExecutor.execShizukuWithOutput(
                new String[]{"sh", "-c", CMD});
        if (out == null || out.isEmpty()) return list;
        for (String line : out.split("\n")) {
            ProcessItem it = parseLine(line);
            if (it != null) list.add(it);
        }
        // 兜底再按 RSS 降序排一次，防止某些 ROM 的 ps 排序行为不同
        Collections.sort(list, new Comparator<ProcessItem>() {
            @Override
            public int compare(ProcessItem a, ProcessItem b) {
                if (a.rssKb != b.rssKb) return b.rssKb - a.rssKb;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return list;
    }

    /** 解析一行 ps 输出，非数据行返回 null */
    static ProcessItem parseLine(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty()) return null;
        // 表头行：PID NAME RSS
        if (s.startsWith("PID")) return null;

        String[] parts = s.split("\\s+");
        if (parts.length < 3) return null;

        int pid;
        try {
            pid = Integer.parseInt(parts[0]);
        } catch (Exception e) {
            return null;
        }

        int rss;
        try {
            rss = Integer.parseInt(parts[parts.length - 1]);
        } catch (Exception e) {
            rss = 0;
        }

        // 中间部分是进程名（正常不含空格，但保险起见拼接）
        StringBuilder nameSb = new StringBuilder();
        for (int i = 1; i < parts.length - 1; i++) {
            if (nameSb.length() > 0) nameSb.append(' ');
            nameSb.append(parts[i]);
        }
        String name = nameSb.toString();
        if (name.isEmpty()) return null;

        ProcessItem it = new ProcessItem();
        it.pid = pid;
        it.name = name;
        it.rssKb = rss;
        it.packageName = toPackageName(name);
        return it;
    }

    /** 进程名去子进程后缀：com.tencent.mm:push -> com.tencent.mm */
    static String toPackageName(String name) {
        if (name == null) return "";
        int i = name.indexOf(':');
        return i > 0 ? name.substring(0, i) : name;
    }
}
