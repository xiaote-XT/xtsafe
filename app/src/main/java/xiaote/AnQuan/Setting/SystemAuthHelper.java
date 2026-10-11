package xiaote.AnQuan.Setting;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 系统身份验证封装。
 *
 * 密码有两种模式，存在 dot_config 的 password_type 键里：
 *   · numeric —— 默认数字密码，走本应用的 PasswordLockActivity（存 SHA-256 哈希）；
 *   · system  —— 请求系统验证，走 KeyguardManager 的锁屏凭据（PIN / 图案 / 密码 / 指纹）。
 *
 * 系统验证的好处是不用另记一套密码，且能复用手势与生物识别；
 * 代价是设备必须先设过锁屏密码，否则 createConfirmDeviceCredentialIntent
 * 返回 null，这种情况下只能退回数字密码。
 *
 * 本类只做「读模式 / 写模式 / 判断可用 / 构造 Intent」，不持有任何状态。
 */
public final class SystemAuthHelper {

    public static final String PREFS = "dot_config";

    /** 密码模式键 */
    public static final String KEY_PASSWORD_TYPE = "password_type";
    /** 默认：数字密码 */
    public static final String TYPE_NUMERIC = "numeric";
    /** 系统验证 */
    public static final String TYPE_SYSTEM = "system";

    /** 数字密码哈希键，与 MainActivity / PasswordLockActivity 共用 */
    public static final String KEY_PASSWORD_HASH = "app_password_hash";
    /** 是否启用密码锁的总开关 */
    public static final String KEY_ENABLED = "password_enabled";

    private SystemAuthHelper() {}

    /** 当前密码模式，默认 numeric */
    public static String getType(Context c) {
        if (c == null) return TYPE_NUMERIC;
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PASSWORD_TYPE, TYPE_NUMERIC);
        } catch (Throwable t) {
            return TYPE_NUMERIC;
        }
    }

    /** 写密码模式；同时把 password_enabled 置为 true */
    public static void setType(Context c, String type) {
        if (c == null) return;
        if (!TYPE_SYSTEM.equals(type)) type = TYPE_NUMERIC;
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PASSWORD_TYPE, type)
                    .putBoolean(KEY_ENABLED, true)
                    .apply();
        } catch (Throwable ignored) {}
    }

    /** 是否启用密码锁 */
    public static boolean isEnabled(Context c) {
        if (c == null) return false;
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 写密码锁总开关 */
    public static void setEnabled(Context c, boolean on) {
        if (c == null) return;
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, on).apply();
        } catch (Throwable ignored) {}
    }

    /** 当前是否系统验证模式 */
    public static boolean isSystemMode(Context c) {
        return TYPE_SYSTEM.equals(getType(c));
    }

    /** 是否已设过数字密码 */
    public static boolean hasNumericPassword(Context c) {
        if (c == null) return false;
        try {
            String h = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PASSWORD_HASH, "");
            return h != null && !h.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 写数字密码哈希 */
    public static void setNumericPasswordHash(Context c, String hash) {
        if (c == null) return;
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PASSWORD_HASH, hash == null ? "" : hash).apply();
        } catch (Throwable ignored) {}
    }

    /** 清除数字密码哈希 */
    public static void clearNumericPassword(Context c) {
        if (c == null) return;
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_PASSWORD_HASH).apply();
        } catch (Throwable ignored) {}
    }

    /** SHA-256 哈希，与 MainActivity / PasswordLockActivity 完全一致 */
    public static String hash(String password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(password.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 设备是否设有锁屏凭据（有才能走系统验证） */
    public static boolean isSystemAvailable(Context c) {
        if (c == null) return false;
        try {
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null) return false;
            // isDeviceSecure() 要 API 23；低版本退回已废弃的 isKeyguardSecure()
            if (Build.VERSION.SDK_INT >= 23) return km.isDeviceSecure();
            return km.isKeyguardSecure();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 构造系统验证 Intent；设备没设锁屏时返回 null。
     *
     * 标题与描述用本应用名义显示，系统验证成功后返回 RESULT_OK。
     */
    public static Intent buildIntent(Context c) {
        if (c == null) return null;
        try {
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null) return null;
            boolean secure = Build.VERSION.SDK_INT >= 23
                    ? km.isDeviceSecure() : km.isKeyguardSecure();
            if (!secure) return null;
            Intent i = km.createConfirmDeviceCredentialIntent(
                    "星特安全", "请验证身份以继续");
            if (i == null) return null;
            /*
             * 绝不能加 FLAG_ACTIVITY_NEW_TASK。
             *
             * 调用方 BaseActivity 用 startActivityForResult 等验证结果，
             * 而 startActivityForResult 遇到带 NEW_TASK 的 Intent 会直接抛
             * AndroidRuntimeException（"FLAG_ACTIVITY_NEW_TASK is not allowed with
             * startActivityForResult()"），应用当场崩溃 ——
             * 表现就是「选了系统验证之后应用打不开」。
             *
             * 这里由 Activity 自己发起，不需要 NEW_TASK。
             */
            return i;
        } catch (Throwable t) {
            return null;
        }
    }
}
