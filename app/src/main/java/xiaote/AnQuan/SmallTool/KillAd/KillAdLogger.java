package xiaote.AnQuan.SmallTool.KillAd;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 广告拦截日志。
 *
 * 写入外部私有目录 Android/data/xiaote.AnQuan/files/killad.log，
 * 不显示在应用界面，供开发者/用户自行查看。
 *
 * 与 OverlayBlockLogger 同风格：单文件、超限清空重写、不抛异常。
 * 日志级别：
 *   [SVC]  服务生命周期（启动 / 暂停 / 恢复 / 停止 / 建立失败）
 *   [RULE] 规则加载与更新
 *   [DNS]  拦截命中（默认只记域名，量大会限流）
 *   [ERR]  异常
 */
public final class KillAdLogger {

    private static final String FILE_NAME = "killad.log";
    /** 单文件上限 1MB，超出则清空重写，避免无限增长 */
    private static final long MAX_SIZE = 1024 * 1024L;

    private KillAdLogger() {}

    /** 日志文件，取不到返回 null */
    public static File logFile(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) {
                dir = new File(Environment.getExternalStorageDirectory(),
                        "Android/data/" + context.getPackageName() + "/files");
            }
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    /** 日志文件路径（用于界面展示），取不到返回空串 */
    public static String logPath(Context context) {
        File f = logFile(context);
        return f == null ? "" : f.getAbsolutePath();
    }

    /** 清空日志文件，成功返回 true */
    public static boolean clear(Context context) {
        if (context == null) return false;
        try {
            File f = logFile(context);
            if (f == null) return false;
            new FileWriter(f, false).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前日志文件字节数，取不到返回 0 */
    public static long size(Context context) {
        try {
            File f = logFile(context);
            return f == null ? 0L : f.length();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 写一行日志，tag 建议用 SVC / RULE / DNS / ERR */
    public static void log(Context context, String tag, String message) {
        if (context == null) return;
        try {
            File f = logFile(context);
            if (f == null) return;
            if (f.exists() && f.length() > MAX_SIZE) {
                // 超限清空（保留单文件，避免无限增长）
                try { new FileWriter(f, false).close(); } catch (Exception ignored) {}
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            FileWriter w = new FileWriter(f, true);
            w.write(time + " [" + tag + "] " + message + "\n");
            w.close();
        } catch (Exception ignored) {}
    }
}
