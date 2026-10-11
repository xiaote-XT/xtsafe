package xiaote.AnQuan;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import xiaote.AnQuan.InternalApi.Capability;
import xiaote.AnQuan.InternalApi.Exec;

/**
 * 保活助手准备与安装。
 *
 * 保活开关打开时，每次星特安全启动都会检查助手是否已安装；
 * 不存在就把内置 assets/xt_helper.apk 释放到本应用的外部私有目录并安装。
 * 开关关闭时什么都不做，也不会去停止或卸载助手。
 *
 * 开关直接复用保活总开关 HelperWakeSender.KEY_ENABLED：
 * 用户在保活增强页拨动开关就同时生效，不再分叉。
 *
 * 释放位置：Android/data/xiaote.AnQuan/files/tmp/xtsafe/
 * 安装时先 cp 到 /data/local/tmp（shell 完全掌控的目录）再 pm install，
 * 避开个别 ROM 对跨 uid 读取 Android/data 的 FUSE 限制；
 * cp 或安装失败时，自动回退到「APK 内容 base64 走 shell 管道」的老路。
 *
 * 装成功后立刻调 PowerSaveHelper.applyBoth()，把自身与助手一起加入电池白名单，
 * 避免助手刚上线就被系统按停。
 */
public final class HelperInstaller {

    private static final String TAG = "XTHelperInstall";

    /** 内置助手 APK 的资源路径 */
    private static final String ASSET_APK = "xt_helper.apk";

    /** 释放子目录（相对 getExternalFilesDir(null)） */
    private static final String RELEASE_SUBDIR = "tmp/xtsafe";
    /** 释放文件名 */
    private static final String RELEASE_NAME = "xt_helper.apk";
    /** 安装用的临时路径（shell 可读写） */
    private static final String TMP_APK = "/data/local/tmp/xt_helper.apk";

    /** 同一时刻只允许一次安装尝试，避免多入口并发重复执行 */
    private static volatile boolean installing = false;

    private HelperInstaller() {}

    /** 是否启用自动准备。直接复用保活总开关。 */
    public static boolean isEnabled(Context c) {
        return HelperWakeSender.isEnabled(c);
    }

    /** 写开关；等价于写保活总开关 */
    public static void setEnabled(Context c, boolean on) {
        HelperWakeSender.setEnabled(c, on);
    }

    /**
     * 启动时的检查入口：开关关着或正在装，都直接返回。
     * 真正的释放与安装丢到后台线程，不阻塞调用方（App.onCreate 在主线程）。
     */
    public static void installIfNeededAsync(final Context c) {
        if (c == null) return;
        if (!isEnabled(c)) return;
        /*
         * 刻意不判断「助手已安装就跳过」。
         *
         * 保活开关打开时，每次星特安全启动都要覆盖安装一次助手：
         * 助手可能在后台被系统降级、被其他安装覆盖成旧版、或被换成了
         * 同名但签名不同的包；只在缺失时安装的话，这些情况都修不回来。
         * APK 只有约 29KB，一次 pm install -r 的开销可以忽略。
         */
        if (installing) return;
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                installIfNeeded(app);
            }
        }, "xt-helper-install").start();
    }

    /**
     * 同步安装流程（必须在工作线程调用）。
     *
     * @return true 表示安装后回读到助手已存在
     */
    public static boolean installIfNeeded(Context c) {
        if (c == null) return false;
        if (!isEnabled(c)) return false;
        /*
         * hadBefore 只用于失败时的结果判定：本来就装着、只是这次覆盖没成功，
         * 不应该向调用方报「安装失败」。
         */
        final boolean hadBefore = HelperWakeSender.isHelperInstalled(c);
        if (installing) return hadBefore;
        installing = true;
        try {
            // 主通道：释放到 Android/data/<包名>/files/tmp/xtsafe/ 后安装
            File apk = releaseToExternal(c);
            if (apk != null) {
                boolean ok = installByPath(apk.getAbsolutePath());
                Log.d(TAG, "主通道安装结果=" + ok + " path=" + apk.getAbsolutePath());
                if (ok && waitInstalled(c)) {
                    // 装完立刻放开省电限制（自身 + 助手）
                    PowerSaveHelper.applyBoth(c);
                    return true;
                }
            } else {
                Log.w(TAG, "释放到外部私有目录失败，转回退通道");
            }

            // 回退通道：base64 走 shell 管道落到 /data/local/tmp 再安装
            boolean fallback = installByPipe(c);
            Log.d(TAG, "回退通道安装结果=" + fallback);
            waitInstalled(c);
            final boolean installed = HelperWakeSender.isHelperInstalled(c);
            if (installed) {
                PowerSaveHelper.applyBoth(c);
            }
            return installed || hadBefore;
        } catch (Throwable t) {
            Log.w(TAG, "助手自动安装异常", t);
            return hadBefore;
        } finally {
            installing = false;
        }
    }

    // ==================== 主通道：释放到外部私有目录 ====================

    /**
     * 把内置 APK 写到 Android/data/<包名>/files/tmp/xtsafe/，返回落地文件；失败返回 null。
     * getExternalFilesDir 在外部存储不可用时会返回 null，此时交给回退通道处理。
     */
    private static File releaseToExternal(Context c) {
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            File base = c.getExternalFilesDir(null);
            if (base == null) {
                Log.w(TAG, "getExternalFilesDir 返回 null");
                return null;
            }
            File dir = new File(base, RELEASE_SUBDIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "创建释放目录失败: " + dir.getAbsolutePath());
                return null;
            }
            File out = new File(dir, RELEASE_NAME);
            is = c.getAssets().open(ASSET_APK);
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            fos.flush();
            if (out.exists() && out.length() > 0) {
                Log.d(TAG, "已释放助手 APK: " + out.getAbsolutePath() + " (" + out.length() + " 字节)");
                return out;
            }
            Log.w(TAG, "释放后文件为空");
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "释放内置 APK 失败", t);
            return null;
        } finally {
            if (fos != null) { try { fos.close(); } catch (Throwable ignored) {} }
            if (is != null) { try { is.close(); } catch (Throwable ignored) {} }
        }
    }

    /**
     * 按文件路径安装：先 cp 到 shell 自己的目录，再就地安装。
     * -r 覆盖安装、-t 允许 testOnly 包、-d 允许版本号回退。
     */
    private static boolean installByPath(String path) {
        String cmd = "cp '" + path + "' " + TMP_APK
                + " && pm install -r -t -d " + TMP_APK
                + "; rm -f " + TMP_APK;
        return runInstallCmd(cmd, "path");
    }

    // ==================== 回退通道：base64 管道 ====================

    /**
     * 回退：把 APK 内容 base64 后直接写进 shell 命令行，由 shell 解码落到
     * /data/local/tmp，再从那里安装。
     * base64 字符集只有 A-Za-z0-9+/=，不含单引号，单引号包裹是安全的。
     */
    private static boolean installByPipe(Context c) {
        byte[] apk = readAsset(c);
        if (apk == null || apk.length == 0) {
            Log.w(TAG, "内置助手 APK 读取失败");
            return false;
        }
        String b64 = Base64.encodeToString(apk, Base64.NO_WRAP);
        String cmd = "printf '%s' '" + b64 + "' | base64 -d > " + TMP_APK
                + " && pm install -r -t -d " + TMP_APK
                + "; rm -f " + TMP_APK;
        return runInstallCmd(cmd, "pipe");
    }

    // ==================== 执行与日志 ====================

    /**
     * 执行安装命令：Shizuku → Root，并把完整的输出打进日志。
     * pm install 失败时（签名冲突、版本号更低、解析失败等）原因全在输出里。
     */
    private static boolean runInstallCmd(String cmd, String tag) {
        try {
            Exec.Out o = Exec.run(Capability.SHIZUKU, cmd);
            if (o != null && o.ok) {
                Log.d(TAG, "[" + tag + "] Shizuku 安装成功");
                return true;
            }
            Log.w(TAG, "[" + tag + "] Shizuku 安装失败: "
                    + (o == null ? "该通道不支持" : o.toString()));
        } catch (Throwable t) {
            Log.w(TAG, "[" + tag + "] Shizuku 通道异常", t);
        }
        try {
            Exec.Out o = Exec.run(Capability.ROOT, cmd);
            if (o != null && o.ok) {
                Log.d(TAG, "[" + tag + "] Root 安装成功");
                return true;
            }
            Log.w(TAG, "[" + tag + "] Root 安装失败: "
                    + (o == null ? "该通道不支持" : o.toString()));
        } catch (Throwable t) {
            Log.w(TAG, "[" + tag + "] Root 通道异常", t);
        }
        return false;
    }

    /** 从 assets 读出内置助手 APK 的全部字节 */
    private static byte[] readAsset(Context c) {
        InputStream is = null;
        try {
            AssetManager am = c.getAssets();
            is = am.open(ASSET_APK);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Throwable ignored) {}
            }
        }
    }

    // ==================== 公共 ====================

    /** 安装是异步落地的，每次 600ms、最多 5 次（约 3 秒）再回读 */
    private static boolean waitInstalled(Context c) {
        for (int i = 0; i < 5; i++) {
            try { Thread.sleep(600L); } catch (InterruptedException ignored) {}
            if (HelperWakeSender.isHelperInstalled(c)) return true;
        }
        return HelperWakeSender.isHelperInstalled(c);
    }
}
