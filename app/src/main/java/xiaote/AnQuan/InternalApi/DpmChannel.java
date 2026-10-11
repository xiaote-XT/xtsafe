package xiaote.AnQuan.InternalApi;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import java.lang.reflect.InvocationTargetException;

import xiaote.AnQuan.DeviceAdmin;
import xiaote.dhizukutool.Dhizuku;
import xiaote.dhizukutool.DhizukuDpm;

/**
 * DevicePolicyManager 通道：把「以设备所有者身份调用 DPM 接口」这件事收敛到一处。
 *
 * 本类只有两条可用路径，对应 Capability 里的 DHIZUKU 与 DEVICE_OWNER：
 *
 *   DHIZUKU      —— 星特安全自身不是设备所有者，但设备上装了 Dhizuku 且已授权。
 *                   通过 DhizukuDpm 把系统 DPM 的 binder 包一层，以 Dhizuku 的
 *                   owner 身份发 transact。
 *   DEVICE_OWNER —— 星特安全自己就是设备所有者，直接用公开 API 调。
 *
 * 没有 SHIZUKU / ROOT 分支：这两级是 shell 通道，跑不了 DPM 接口，
 * 相关能力由 Exec 承担。
 *
 * 所有方法都是跨进程调用，必须在工作线程使用；本类不做线程调度。
 */
public final class DpmChannel {

    private DpmChannel() {}

    // ==================== 可用通道 ====================

    /**
     * 按优先级返回当前可用的 DPM 通道：Dhizuku 优先，其次本应用自任 DeviceOwner。
     *
     * 顺序理由：Dhizuku 场景下设备所有者是 Dhizuku，星特安全拿不到 DO 身份，
     * 两者天然互斥；谁可用就用谁，不存在同时可用的情形。
     */
    public static Capability[] usable(Context ctx) {
        if (Capability.isDhizukuReady(ctx)) return new Capability[]{ Capability.DHIZUKU };
        if (Capability.isDeviceOwner(ctx)) return new Capability[]{ Capability.DEVICE_OWNER };
        return new Capability[0];
    }

    /** 当前是否有任一 DPM 通道可用 */
    public static boolean anyReady(Context ctx) {
        return Capability.isDhizukuReady(ctx) || Capability.isDeviceOwner(ctx);
    }

    // ==================== 统一调用 ====================

    /**
     * 禁止 / 允许卸载指定应用。
     *
     * DHIZUKU      → DhizukuDpm.setUninstallBlocked（内部已做 binder 包装）
     * DEVICE_OWNER → DevicePolicyManager.setUninstallBlocked（公开 API，API 21+）
     *
     * 失败抛异常，由调用方按 ApiResult 包装，本类不吞异常 —— 调用方需要
     * 知道失败原因才能决定是否降级到下一级通道。
     */
    public static void setUninstallBlocked(Context ctx, Capability cap,
                                           String pkg, boolean blocked) throws Exception {
        if (ctx == null || pkg == null || pkg.length() == 0) {
            throw new IllegalArgumentException("ctx / pkg is empty");
        }
        if (cap == Capability.DHIZUKU) {
            DhizukuDpm.setUninstallBlocked(ctx, pkg, blocked);
            return;
        }
        if (cap == Capability.DEVICE_OWNER) {
            DevicePolicyManager dpm = dpm(ctx);
            dpm.setUninstallBlocked(admin(ctx), pkg, blocked);
            return;
        }
        throw new UnsupportedOperationException(cap.label + " 不支持 DPM 接口");
    }

    /**
     * 回读是否已禁止卸载。
     *
     * @return 已禁止 / 未禁止；两条通道都查不到时返回 null
     */
    public static Boolean isUninstallBlocked(Context ctx, Capability cap, String pkg) {
        if (ctx == null || pkg == null || pkg.length() == 0) return null;
        try {
            if (cap == Capability.DHIZUKU) {
                return DhizukuDpm.isUninstallBlocked(ctx, pkg);
            }
            if (cap == Capability.DEVICE_OWNER) {
                return dpm(ctx).isUninstallBlocked(admin(ctx), pkg);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 隐藏 / 恢复应用（等价于「冻结」的 DPM 写法）。
     *
     * DHIZUKU      → 走代理调 setApplicationHidden
     * DEVICE_OWNER → 公开 API（API 21+）
     *
     * 注意：hidden 与 pm disable 不是一回事 —— hidden 只是从桌面与列表中消失，
     * 进程仍可被拉起；disable 才是真正停用。所以冻结操作优先走 shell，
     * 本方法只在 shell 通道都不可用时作为降级手段。
     */
    public static void setApplicationHidden(Context ctx, Capability cap,
                                            String pkg, boolean hidden) throws Exception {
        if (ctx == null || pkg == null || pkg.length() == 0) {
            throw new IllegalArgumentException("ctx / pkg is empty");
        }
        if (cap == Capability.DHIZUKU) {
            invokeProxy(ctx, "setApplicationHidden",
                    new Class[]{ComponentName.class, String.class, String.class, boolean.class},
                    new Object[]{dhizukuAdmin(), dhizukuCaller(), pkg, hidden});
            return;
        }
        if (cap == Capability.DEVICE_OWNER) {
            dpm(ctx).setApplicationHidden(admin(ctx), pkg, hidden);
            return;
        }
        throw new UnsupportedOperationException(cap.label + " 不支持 DPM 接口");
    }

    /**
     * 撤销 / 授予某个运行时权限（设备所有者专属能力）。
     *
     * DHIZUKU      → 走代理调 setPermissionGrantState
     * DEVICE_OWNER → 公开 API（API 23+）
     *
     * @param state DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT / GRANTED / DENIED
     */
    public static void setPermissionGrantState(Context ctx, Capability cap,
                                               String pkg, String permission, int state) throws Exception {
        if (ctx == null || pkg == null || permission == null) {
            throw new IllegalArgumentException("ctx / pkg / permission is empty");
        }
        if (Build.VERSION.SDK_INT < 23) {
            throw new UnsupportedOperationException("setPermissionGrantState 需要 API 23+");
        }
        if (cap == Capability.DHIZUKU) {
            invokeProxy(ctx, "setPermissionGrantState",
                    new Class[]{ComponentName.class, String.class, String.class, String.class, int.class},
                    new Object[]{dhizukuAdmin(), dhizukuCaller(), pkg, permission, state});
            return;
        }
        if (cap == Capability.DEVICE_OWNER) {
            dpm(ctx).setPermissionGrantState(admin(ctx), pkg, permission, state);
            return;
        }
        throw new UnsupportedOperationException(cap.label + " 不支持 DPM 接口");
    }

    // ==================== Dhizuku 代理调用 ====================

    /**
     * 通过 Dhizuku 代理调用任意 DPM 方法。
     *
     * 与 DhizukuDpm 内部实现同一套路：取系统 DPM 的 binder，包一层后
     * asInterface 出 Proxy，再反射调用目标方法 —— 事务码由 Proxy 自己持有，
     * 不依赖任何隐藏常量。
     *
     * 这里只覆盖 DpmChannel 需要的几个方法；更多方法请在 DhizukuDpm 里加专用封装，
     * 不要在本类堆反射逻辑。
     */
    private static void invokeProxy(Context ctx, String name, Class[] types, Object[] args)
            throws Exception {
        android.os.IBinder raw = DhizukuDpm.getDevicePolicyBinder(ctx);
        if (raw == null) throw new IllegalStateException("DPM binder 不可用");
        android.os.IBinder wrapped = Dhizuku.binderWrapper(raw);
        Class<?> stub = Class.forName("android.app.admin.IDevicePolicyManager$Stub");
        java.lang.reflect.Method asInterface = stub.getMethod("asInterface", android.os.IBinder.class);
        asInterface.setAccessible(true);
        Object proxy = asInterface.invoke(null, wrapped);
        if (proxy == null) throw new IllegalStateException("asInterface 返回 null");

        java.lang.reflect.Method target = null;
        for (java.lang.reflect.Method m : proxy.getClass().getMethods()) {
            if (!name.equals(m.getName())) continue;
            if (m.getParameterTypes().length != types.length) continue;
            target = m;
            break;
        }
        if (target == null) {
            throw new NoSuchMethodException(name + " 参数个数 " + types.length + " 未找到");
        }
        target.setAccessible(true);
        target.invoke(proxy, args);
    }

    private static ComponentName dhizukuAdmin() {
        ComponentName c = Dhizuku.getOwnerComponent();
        if (c != null) return c;
        return xiaote.dhizukutool.DhizukuVariables.COMPONENT_NAME;
    }

    private static String dhizukuCaller() {
        String p = Dhizuku.getOwnerPackageName();
        if (p == null) throw new IllegalStateException("Dhizuku owner 包名未知");
        return p;
    }

    // ==================== 工具 ====================

    /** 本应用自身的管理员组件（DEVICE_OWNER 通道用） */
    public static ComponentName admin(Context ctx) {
        return new ComponentName(ctx, DeviceAdmin.class);
    }

    public static DevicePolicyManager dpm(Context ctx) {
        return (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    /**
     * 把异常压成一行可读文本，供 ApiResult.detail 使用。
     * Exec 也调它，保证全项目错误描述格式一致。
     */
    public static String describe(Throwable t) {
        if (t == null) return "null";
        Throwable c = (t instanceof InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg == null ? "" : (": " + msg));
    }
}
