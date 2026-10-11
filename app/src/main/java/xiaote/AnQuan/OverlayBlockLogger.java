package xiaote.AnQuan;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全屏覆盖拦截日志。
 * 写入外部私有目录 Android/data/xiaote.AnQuan/files/overlay_block.log，
 * 不显示在应用界面，供开发者/用户自行查看。
 */
public class OverlayBlockLogger {

    private static final String DIR_NAME = "files";
    private static final String FILE_NAME = "overlay_block.log";
    private static final long MAX_SIZE = 512 * 1024L; // 512KB 上限，超出则清空重写

    private static File logFile(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = new File(Environment.getExternalStorageDirectory(),
                    "Android/data/" + context.getPackageName() + "/" + DIR_NAME);
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    public static void log(Context context, String tag, String message) {
        try {
            File f = logFile(context);
            if (f == null) return;
            if (f.exists() && f.length() > MAX_SIZE) {
                // 超限则清空（保留单文件，避免无限增长）
                try { new FileWriter(f, false).close(); } catch (Exception ignored) {}
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            FileWriter w = new FileWriter(f, true);
            w.write(time + " [" + tag + "] " + message + "\n");
            w.close();
        } catch (Exception ignored) {}
    }
}
