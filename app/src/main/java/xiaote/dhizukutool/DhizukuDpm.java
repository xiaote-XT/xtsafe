package xiaote.dhizukutool;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 通过 Dhizuku 代理调用系统 DevicePolicyManager 的特权方法。
 *
 * 原理：Dhizuku 服务端本身是设备所有者(DeviceOwner)。把系统 DPM 的 binder
 * 包进 {@link DhizukuBinderWrapper} 后，所有 transact 都会经由 Dhizuku 服务端
 * 进程执行，于是具备 device owner 权限。
 *
 * ===== 为什么不用 TRANSACTION_* 反射 =====
 *
 * 早期实现反射 {@code android.app.admin.IDevicePolicyManager$Stub} 的
 * TRANSACTION_setUninstallBlocked 常量来拿事务码。实测在 Android 13 上
 * 直接抛：
 *
 *   NoSuchFieldException: No field TRANSACTION_setUninstallBlocked in class
 *   Landroid/app/admin/IDevicePolicyManager$Stub;
 *
 * 原因：新版 AIDL 后端不再为 Stub 生成 TRANSACTION_ 静态字段，事务码只存在于
 * 编译好的 Proxy 字节码里。所以改用：
 *
 *   1. Class.forName("IDevicePolicyManager$Stub").getMethod("asInterface", IBinder)
 *      → 传入被 Dhizuku 包过的 binder，得到 AIDL 生成的 Proxy 对象
 *   2. 反射调 Proxy 上的公开方法 setUninstallBlocked / isUninstallBlocked
 *      → Proxy 内部自己按正确的 code 发 transact，我们完全不需要知道码值
 *
 * 这条路径不依赖任何隐藏常量，跨版本稳定；不同 Android 版本新增的
 * userId 参数用「按参数个数匹配」兼容。
 *
 * ===== 关于 hidden API =====
 *
 * 仍需反射 DPM.mService 字段与 IDevicePolicyManager 类本身，
 * 因此进程早期仍尝试调用 {@link #ensureHiddenApiExempt()}。
 * 豁免失败也不必然致命：宿主 targetSdk 36 下若系统只拦「方法/字段访问」
 * 而不拦 Class.forName 与 getDeclaredField，本类仍可正常工作。
 */
public final class DhizukuDpm {

    private static final String TAG = "DhizukuDpm";

    /** 系统 DPM 的 AIDL 接口描述符（仅记录用途，不再用于拼事务码）。 */
    private static final String DESCRIPTOR = "android.app.admin.IDevicePolicyManager";

    /** 承载 asInterface 的系统类。 */
    private static final String STUB_CLASS = "android.app.admin.IDevicePolicyManager$Stub";

    private static volatile boolean sExemptTried = false;
    private static volatile boolean sExemptOk = false;

    /** 最近一次失败的描述，供界面展示。 */
    private static volatile String sReflectDiag = null;

    private DhizukuDpm() {
    }

    // ======================== hidden API 豁免（best-effort） ========================

    /**
     * 尝试豁免本进程的 hidden API 检查。
     *
     * 做法：dalvik.system.VMRuntime.setHiddenApiExemptions(new String[]{"L"})
     * 把以 L 开头的所有类（即全部）加入豁免。
     *
     * 失败不抛，只标记状态，调用方照常继续。
     * 幂等，可重复调用。
     */
    public static void ensureHiddenApiExempt() {
        if (sExemptTried) return;
        sExemptTried = true;
        try {
            // 经典 meta-reflection 绕过：通过 Class 自身的 forName / getDeclaredMethod
            // 反射调用来加载 VMRuntime 类。
            //
            // 直接 Class.forName("dalvik.system.VMRuntime") + getDeclaredMethod
            // 在 targetSdk >= 28 时会被 hidden API 拦截，表现为反射失败：
            //   getDeclaredField 抛 NoSuchFieldException（即使字段实际存在）
            //   getDeclaredMethod 抛 NoSuchMethodException
            //
            // meta-reflection 技巧的调用者是 java.lang.Class 本身，Class 属于
            // 平台核心类，不受 hidden API 检查限制，因此可以拿到受限的
            // VMRuntime.setHiddenApiExemptions 方法。这是 Android 9~15 通用的
            // 绕过方式。
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "getRuntime", new Class[0]);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});
            Object vmRuntime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(vmRuntime, new Object[]{new String[]{"L"}});
            sExemptOk = true;
            Log.i(TAG, "hidden API 豁免成功（meta-reflection）");
        } catch (Throwable t) {
            sExemptOk = false;
            Log.w(TAG, "hidden API 豁免失败（不致命，继续尝试反射）", t);
        }
    }

    public static boolean isHiddenApiExempt() {
        return sExemptOk;
    }

    /** 取最近一次失败的描述，没有则返回 null。 */
    public static String getReflectDiagnostic() {
        return sReflectDiag;
    }

    // ======================== 基础工具 ========================

    /**
     * 取系统 DevicePolicyManager 背后的原始 IBinder。
     * DevicePolicyManager 内部持有 mService 字段（@hide 的 IDevicePolicyManager 代理）。
     */
    public static IBinder getDevicePolicyBinder(Context context) {
        if (context == null) return null;
        ensureHiddenApiExempt();
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
            sReflectDiag = "取 DPM.mService 失败: " + describe(t);
            Log.e(TAG, "getDevicePolicyBinder failed", t);
            return null;
        }
    }

    private static String describe(Throwable t) {
        if (t == null) return "null";
        Throwable c = (t instanceof InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
        return c.getClass().getSimpleName()
                + (c.getMessage() == null ? "" : (": " + c.getMessage()));
    }

    /**
     * 取 DPM 的 AIDL Proxy，所有 transact 都走它，事务码由它内部持有。
     *
     * @return IDevicePolicyManager 的 Proxy 实例；失败返回 null
     */
    private static Object getProxy(Context context) {
        IBinder raw = getDevicePolicyBinder(context);
        if (raw == null) {
            if (sReflectDiag == null) sReflectDiag = "DPM binder 不可用";
            return null;
        }
        try {
            IBinder wrapped = Dhizuku.binderWrapper(raw);
            Class<?> stub = Class.forName(STUB_CLASS);
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            Object proxy = asInterface.invoke(null, wrapped);
            if (proxy == null) {
                sReflectDiag = "asInterface 返回 null";
            }
            return proxy;
        } catch (Throwable t) {
            sReflectDiag = "取 IDevicePolicyManager 代理失败: " + describe(t);
            Log.e(TAG, "getProxy failed", t);
            return null;
        }
    }

    /**
     * 在代理类上按名字与参数个数找方法，兼容不同 Android 版本新增的 userId 参数。
     *
     * @param name      方法名
     * @param paramCount 期望的参数个数
     */
    private static Method findMethod(Object proxy, String name, int paramCount) {
        if (proxy == null) return null;
        for (Method m : proxy.getClass().getMethods()) {
            if (!name.equals(m.getName())) continue;
            if (m.getParameterTypes().length != paramCount) continue;
            return m;
        }
        return null;
    }

    /**
     * 探测 setUninstallBlocked / isUninstallBlocked 能否调用。
     * 界面用它在拨开关前给出明确提示。
     *
     * 必须传入 Context：本方法可能在用户首次进入页面时就被调用，
     * 此时还没有任何业务调用触发 remember()，缓存的 sAppContext 仍为 null，
     * 会导致 getProxy(null) 直接返回 null 而误判为「不支持」。
     */
    public static boolean isUninstallBlockSupported(Context context) {
        remember(context);
        try {
            Object proxy = getProxy(context);
            if (proxy == null) return false;
            // setUninstallBlocked 的参数个数：4（老版）或 5（带 userId）
            if (findMethod(proxy, "setUninstallBlocked", 4) == null
                    && findMethod(proxy, "setUninstallBlocked", 5) == null) {
                sReflectDiag = "代理类上找不到 setUninstallBlocked 方法";
                return false;
            }
            // isUninstallBlocked 的参数个数：2（老版）或 3（带 userId）
            if (findMethod(proxy, "isUninstallBlocked", 2) == null
                    && findMethod(proxy, "isUninstallBlocked", 3) == null) {
                sReflectDiag = "代理类上找不到 isUninstallBlocked 方法";
                return false;
            }
            return true;
        } catch (Throwable t) {
            sReflectDiag = "探测失败: " + describe(t);
            return false;
        }
    }

    /** 缓存的应用上下文，供无 Context 参数的探测方法使用。 */
    private static volatile Context sAppContext = null;

    /** 由任意一次调用记录应用上下文。 */
    private static void remember(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        sAppContext = (app != null) ? app : ctx;
    }

    private static Context getAppContext() {
        return sAppContext;
    }

    /** 取 Dhizuku 自己作为 admin 的 ComponentName。 */
    private static ComponentName requireAdmin() {
        ComponentName owner = Dhizuku.getOwnerComponent();
        ComponentName official = DhizukuVariables.COMPONENT_NAME;
        if (owner != null
                && official != null
                && official.getPackageName().equals(owner.getPackageName())) {
            return official;
        }
        if (owner != null) return owner;
        if (official != null) return official;
        throw new IllegalStateException(
                "Dhizuku owner component unknown; call Dhizuku.init() first");
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

    /**
     * 当前用户 id，供带 userId 的接口使用。
     *
     * 不用 UserHandle.myUserId()：该方法 API 24 才公开，项目 minSdk 23，
     * AIDE 索引不到会报「未知方法」。改用 Process.myUid() / 100000，
     * 这是 Android 多用户模型里「uid 除以 100000 得 userId」的通用换算，
     * Process.myUid() 从 API 1 就有。
     */
    private static int currentUserId() {
        try {
            return android.os.Process.myUid() / 100000;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ======================== 禁止卸载 ========================

    /**
     * 禁止或允许卸载指定应用（设备所有者权限）。
     *
     * AOSP AIDL：
     *   setUninstallBlocked(ComponentName admin,
     *                       String callerPackageName,
     *                       String packageName,
     *                       boolean uninstallBlocked)
     * 新版可能多一个 int userId 参数。
     *
     * @return true 表示调用成功返回（不代表系统一定接受，需回读确认）
     */
    public static boolean setUninstallBlocked(Context context, String packageName, boolean blocked) {
        if (packageName == null || packageName.length() == 0) {
            throw new IllegalArgumentException("packageName is empty");
        }
        remember(context);
        sReflectDiag = null;

        ComponentName admin = requireAdmin();
        String caller = callerPackage();
        Object proxy = getProxy(context);
        if (proxy == null) {
            throw new IllegalStateException(
                    "DPM proxy not available"
                            + (sReflectDiag == null ? "" : ("（" + sReflectDiag + "）")));
        }

        Method m = findMethod(proxy, "setUninstallBlocked", 5);
        boolean withUser = (m != null);
        if (m == null) m = findMethod(proxy, "setUninstallBlocked", 4);
        if (m == null) {
            throw new IllegalStateException("代理类上没有 setUninstallBlocked 方法");
        }

        Log.i(TAG, "setUninstallBlocked: pkg=" + packageName + " blocked=" + blocked
                + " admin=" + admin.flattenToShortString()
                + " caller=" + caller + " withUser=" + withUser);

        try {
            Object r;
            if (withUser) {
                r = m.invoke(proxy, admin, caller, packageName, blocked, currentUserId());
            } else {
                r = m.invoke(proxy, admin, caller, packageName, blocked);
            }
            // 返回 void，invoke 返回 null 即成功
            return true;
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            String msg = (c == null) ? e.toString()
                    : (c.getClass().getSimpleName() + ": " + c.getMessage());
            sReflectDiag = "setUninstallBlocked 被拒: " + msg;
            Log.e(TAG, "setUninstallBlocked failed", c == null ? e : c);
            throw new RuntimeException(msg, c == null ? e : c);
        } catch (Throwable t) {
            sReflectDiag = "setUninstallBlocked 异常: " + describe(t);
            Log.e(TAG, "setUninstallBlocked failed", t);
            throw new RuntimeException(t);
        }
    }

    /**
     * 查询指定应用是否被禁止卸载。
     *
     * AOSP AIDL：isUninstallBlocked(ComponentName admin, String packageName) -> Z
     * 新版可能多一个 int userId 参数。
     *
     * @return true 已禁止；false 未禁止；查询失败返回 null
     */
    public static Boolean isUninstallBlocked(Context context, String packageName) {
        if (packageName == null || packageName.length() == 0) return null;
        remember(context);

        ComponentName admin;
        Object proxy;
        try {
            admin = requireAdmin();
            proxy = getProxy(context);
        } catch (Throwable t) {
            return null;
        }
        if (proxy == null) return null;

        Method m = findMethod(proxy, "isUninstallBlocked", 3);
        boolean withUser = (m != null);
        if (m == null) m = findMethod(proxy, "isUninstallBlocked", 2);
        if (m == null) return null;

        try {
            Object r;
            if (withUser) {
                r = m.invoke(proxy, admin, packageName, currentUserId());
            } else {
                r = m.invoke(proxy, admin, packageName);
            }
            if (r instanceof Boolean) return (Boolean) r;
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "isUninstallBlocked failed", t);
            return null;
        }
    }

    // ======================== lockNow（保留，未在星特安全中使用） ========================

    /** 锁屏：以设备所有者身份调用 DevicePolicyManager.lockNow()。 */
    public static boolean lockNow(Context context) {
        remember(context);
        Object proxy = getProxy(context);
        if (proxy == null) {
            throw new IllegalStateException("DPM proxy not available");
        }
        Method m = findMethod(proxy, "lockNow", 1);
        boolean withFlags = (m != null);
        if (m == null) m = findMethod(proxy, "lockNow", 0);
        if (m == null) throw new IllegalStateException("代理类上没有 lockNow 方法");

        try {
            if (withFlags) {
                m.invoke(proxy, 0);
            } else {
                m.invoke(proxy);
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "lockNow failed", t);
            throw new RuntimeException(t);
        }
    }
}
