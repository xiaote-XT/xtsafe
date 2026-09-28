package xiaote.dhizukutool;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 通过 Dhizuku 代理调用系统 DevicePolicyManager 的特权方法。
 * <p>
 * 原理：Dhizuku 服务端本身是设备所有者(DeviceOwner)。把系统 DPM 的 binder
 * 包进 {@link DhizukuBinderWrapper} 后，所有 transact 都会经由 Dhizuku 服务端
 * 进程执行，于是具备 device owner 权限。
 * <p>
 * <b>transact 码不写死</b>：运行时反射当前设备 framework 里
 * {@code android.app.admin.IDevicePolicyManager$Stub} 的 TRANSACTION_xxx 常量。
 * 不同 Android 版本该常量会变（DPM 接口持续新增方法，编号会重排），
 * 反射保证跨版本自动正确。反射失败时抛出明确异常，不用错误的魔法数字蒙混。
 * <p>
 * 注意：本方式依赖隐藏 API 反射，仅当宿主 targetSdk &lt;= 27 时不受
 * Android 9+ 隐藏 API 黑名单限制。本工程 targetSdk=26。
 */
public final class DhizukuDevicePolicy {

    private static final String TAG = "DhizukuDevicePolicy";

    /** 系统 DPM 的 AIDL 接口描述符。 */
    private static final String DESCRIPTOR = "android.app.admin.IDevicePolicyManager";

    /** 承载 TRANSACTION_xxx 常量的系统类。 */
    private static final String STUB_CLASS = "android.app.admin.IDevicePolicyManager$Stub";

    private DhizukuDevicePolicy() {
    }

    // ======================== 基础工具 ========================

    /**
     * 取系统 DevicePolicyManager 背后的原始 IBinder。
     * DevicePolicyManager 内部持有 mService 字段（@hide 的 IDevicePolicyManager 代理）。
     *
     * @return 拿不到时返回 null
     */
    public static IBinder getDevicePolicyBinder(Context context) {
        if (context == null) return null;
        try {
            Object dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return null;
            Field f = dpm.getClass().getDeclaredField("mService");
            f.setAccessible(true);
            Object service = f.get(dpm);
            if (service == null) return null;
            Method asBinder = service.getClass().getMethod("asBinder");
            asBinder.setAccessible(true);
            return (IBinder) asBinder.invoke(service);
        } catch (Throwable t) {
            Log.e(TAG, "getDevicePolicyBinder failed", t);
            return null;
        }
    }

    /**
     * 反射读取当前设备上 IDhizukuDevicePolicyManager$Stub 的某个 TRANSACTION_ 常量。
     * <p>
     * 不返回回退值：拿不到就抛异常并说明原因。错误的 transact 码会导致
     * 服务端走 super.onTransact，表现为静默失败或返回 null，比直接报错更难排查。
     *
     * @param fieldName 形如 TRANSACTION_lockNow
     */
    private static int transactCode(String fieldName) {
        Throwable cause = null;
        try {
            Class<?> stub = Class.forName(STUB_CLASS);
            Field f = stub.getDeclaredField(fieldName);
            f.setAccessible(true);
            int code = f.getInt(null);
            Log.i(TAG, "resolve " + fieldName + " = " + code
                    + " (SDK=" + Build.VERSION.SDK_INT + ")");
            return code;
        } catch (Throwable t) {
            cause = t;
        }
        throw new IllegalStateException(
                "无法从当前设备解析 " + fieldName
                        + "（Android SDK=" + Build.VERSION.SDK_INT + "）。"
                        + "常见原因：宿主 targetSdk>=28 触发隐藏 API 反射限制，"
                        + "或该 Android 版本未暴露此常量。"
                        + "原始错误: " + cause,
                cause);
    }

    /**
     * 按 AIDL 生成的 writeTypedObject 线格式写 Parcelable。
     * 先写 int 非空标志(1/0)，非空才 writeToParcel。
     */
    private static void writeTypedParcelable(Parcel p, Parcelable v) {
        if (v != null) {
            p.writeInt(1);
            v.writeToParcel(p, 0);
        } else {
            p.writeInt(0);
        }
    }

    /**
     * 取 Dhizuku 自己作为 admin 的 ComponentName，用于 DPM 参数。
     * 必须在 {@link Dhizuku#init(Context)} 之后调用。
     */
    private static ComponentName requireAdmin() {
        ComponentName owner = Dhizuku.getOwnerComponent();
        if (owner == null) {
            throw new IllegalStateException(
                    "Dhizuku owner component unknown; call Dhizuku.init() first");
        }
        // Dhizuku 应用内可能注册了多个 active admin receiver，getActiveAdmins()
        // 遍历得到的那个未必是系统认定的 DeviceOwner 组件。若 admin 参数不是
        // 真正的 DO，system_server 会走非 DO 分支，并抛出形如
        //   SecurityException: Caller with uid X is not <packageName>
        // 的异常（校验的是"调用者是否等于目标包名"）。
        // 官方 SDK 在 DhizukuVariables 中硬编码了 DO 组件，优先采用它。
        ComponentName official = DhizukuVariables.COMPONENT_NAME;
        if (official.getPackageName().equals(owner.getPackageName())) {
            Log.i(TAG, "admin: official=" + official.flattenToShortString()
                    + " owner=" + owner.flattenToShortString()
                    + " -> 使用 official");
            return official;
        }
        Log.w(TAG, "admin: owner package " + owner.getPackageName()
                + " != official " + official.getPackageName()
                + "，退回使用 owner 组件");
        return owner;
    }

    /**
     * 取实际发起调用的进程包名。
     * 走 Dhizuku 代理时，system_server 看到的调用者就是 Dhizuku 服务端进程，
     * 因此凡是 AIDL 需要 callerPackageName 的方法，都填 Dhizuku 的包名。
     */
    private static String callerPackage() {
        String pkg = Dhizuku.getOwnerPackageName();
        if (pkg == null) {
            throw new IllegalStateException(
                    "Dhizuku owner package unknown; call Dhizuku.init() first");
        }
        return pkg;
    }

    /** 包装 DPM binder，准备发 transact。 */
    private static IBinder wrappedDpm(Context context) {
        IBinder dpmBinder = getDevicePolicyBinder(context);
        if (dpmBinder == null) {
            throw new IllegalStateException(
                    "DPM binder not available（反射 DevicePolicyManager.mService 失败）");
        }
        return Dhizuku.binderWrapper(dpmBinder);
    }

    // ======================== lockNow ========================

    /**
     * lockNow 是否带 int flags 参数。
     * Android 10 (API 29) 起 AIDL 签名为 lockNow(int)，之前是无参。
     */
    private static boolean lockNowHasFlags() {
        try {
            Class<?> idpm = Class.forName("android.app.admin.IDevicePolicyManager");
            for (Method m : idpm.getMethods()) {
                if ("lockNow".equals(m.getName())) {
                    return m.getParameterTypes().length > 0;
                }
            }
        } catch (Throwable ignored) {
        }
        return Build.VERSION.SDK_INT >= 29;
    }

    /**
     * 锁屏：以设备所有者身份调用 DevicePolicyManager.lockNow()。
     * <p>
     * 前置条件：{@link Dhizuku#init(Context)} 成功且已获得 Dhizuku API 权限。
     *
     * @return true 表示 transact 成功返回
     */
    public static boolean lockNow(Context context) {
        IBinder wrapped = wrappedDpm(context);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            int code = transactCode("TRANSACTION_lockNow");
            boolean hasFlags = lockNowHasFlags();
            Log.i(TAG, "lockNow: code=" + code + " hasFlags=" + hasFlags);

            data.writeInterfaceToken(DESCRIPTOR);
            if (hasFlags) {
                data.writeInt(0);
            }

            boolean ok = wrapped.transact(code, data, reply, 0);
            reply.readException();
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "lockNow failed", t);
            throw new RuntimeException(t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ======================== 禁止卸载（通用） ========================

    /**
     * 禁止或允许卸载指定应用（设备所有者权限）。
     * <p>
     * 对应 AIDL：
     * {@code setUninstallBlocked(ComponentName admin, String packageName,
     * String callerPackageName, boolean uninstallBlocked) -> void}。
     * <p>
     * 对任意包名生效，不针对特定应用。<br>
     * {@code callerPackageName} 由 {@link #callerPackage()} 填 Dhizuku 自己的包名，
     * 因为实际发起调用的进程就是 Dhizuku 服务端。
     *
     * @param packageName 目标应用包名（可以是自己、别的应用、或 com.rosan.dhizuku）
     * @param blocked     true 禁止卸载，false 解除禁止
     */
    public static void setUninstallBlocked(Context context, String packageName, boolean blocked) {
        if (packageName == null || packageName.length() == 0) {
            throw new IllegalArgumentException("packageName is empty");
        }
        ComponentName admin = requireAdmin();
        String caller = callerPackage();
        IBinder wrapped = wrappedDpm(context);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            int code = transactCode("TRANSACTION_setUninstallBlocked");
            Log.i(TAG, "setUninstallBlocked: code=" + code
                    + " pkg=" + packageName + " blocked=" + blocked
                    + " admin=" + admin.flattenToShortString()
                    + " caller=" + caller);

            data.writeInterfaceToken(DESCRIPTOR);
            writeTypedParcelable(data, admin);
            // 参数顺序必须与 AOSP 的 setUninstallBlocked 一致：
            // (admin, callerPackageName, packageName, blocked)
            // 写反会被 system_server 的 enforceCallingPackage 拒绝：
            //   SecurityException: Caller with uid X is not <包名>
            data.writeString(caller);
            data.writeString(packageName);
            data.writeInt(blocked ? 1 : 0);

            wrapped.transact(code, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.e(TAG, "setUninstallBlocked failed", t);
            throw new RuntimeException(t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 查询指定应用是否被禁止卸载。
     * <p>
     * 对应 AIDL：{@code isUninstallBlocked(ComponentName admin, String packageName) -> Z}。
     *
     * @return true 表示当前已被禁止卸载
     */
    public static boolean isUninstallBlocked(Context context, String packageName) {
        if (packageName == null || packageName.length() == 0) {
            throw new IllegalArgumentException("packageName is empty");
        }
        ComponentName admin = requireAdmin();
        IBinder wrapped = wrappedDpm(context);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            int code = transactCode("TRANSACTION_isUninstallBlocked");

            data.writeInterfaceToken(DESCRIPTOR);
            writeTypedParcelable(data, admin);
            data.writeString(packageName);

            wrapped.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Throwable t) {
            Log.e(TAG, "isUninstallBlocked failed", t);
            throw new RuntimeException(t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
