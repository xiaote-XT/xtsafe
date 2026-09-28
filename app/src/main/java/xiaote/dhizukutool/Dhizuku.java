package xiaote.dhizukutool;

import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Dhizuku API 主入口（全静态方法），无需 SDK，手写 AIDL 通信。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>{@link #init(Context)} — 通过 ContentProvider.call 获取服务端 IBinder</li>
 *   <li>{@link #isPermissionGranted()} — 检查是否已授权</li>
 *   <li>{@link #requestPermission(DhizukuRequestPermissionListener)} — 弹窗请求权限</li>
 *   <li>{@link #newProcess} / {@link #remoteTransact} / {@link #binderWrapper} 等 — 执行特权操作</li>
 * </ol>
 */
public class Dhizuku {

    private static final String TAG = "Dhizuku";

    @SuppressLint("StaticFieldLeak")
    private static Context mContext;
    private static ComponentName mOwnerComponent;
    private static IDhizuku remote;

    private Dhizuku() {
    }

    // ======================== Owner Component ========================

    /**
     * 从 DevicePolicyManager 查找 DeviceOwner / ProfileOwner 组件。
     */
    public static ComponentName getOwnerComponent(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return getOwnerComponent(dpm);
    }

    public static ComponentName getOwnerComponent(DevicePolicyManager dpm) {
        if (dpm == null) return null;
        List<ComponentName> admins = dpm.getActiveAdmins();
        if (admins == null) return null;
        for (ComponentName admin : admins) {
            String pkg = admin.getPackageName();
            if (dpm.isDeviceOwnerApp(pkg)) return admin;
            if (dpm.isProfileOwnerApp(pkg)) return admin;
        }
        return null;
    }

    public static ComponentName getOwnerComponent() {
        return mOwnerComponent;
    }

    public static String getOwnerPackageName() {
        if (mOwnerComponent == null) return null;
        return mOwnerComponent.getPackageName();
    }

    // ======================== Init ========================

    /**
     * 使用当前应用上下文初始化（通过反射获取 ActivityThread.currentApplication）。
     */
    public static boolean init() {
        Context context = null;
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Method m1 = cls.getMethod("currentActivityThread");
            Object thread = m1.invoke(null);
            Method m2 = cls.getMethod("getApplication");
            context = (Context) m2.invoke(thread);
        } catch (Exception e) {
            Log.e(TAG, "init: failed to get application context", e);
        }
        if (context == null) return false;
        return init(context);
    }

    /**
     * 初始化：查找 Dhizuku 服务端，通过 ContentProvider 获取 IBinder。
     *
     * @return true 表示成功连接到 Dhizuku 服务端
     */
    @SuppressLint("StaticFieldLeak")
    public static boolean init(Context context) {
        mContext = context.getApplicationContext();
        mOwnerComponent = getOwnerComponent(mContext);
        if (mOwnerComponent == null) {
            Log.e(TAG, "init: Dhizuku owner component not found");
            return false;
        }
        try {
            remote = requireServer();
        } catch (Exception e) {
            Log.e(TAG, "init: requireServer failed", e);
            return false;
        }
        return remote != null;
    }

    /**
     * 通过 ContentResolver.call 获取服务端 IBinder 并包装为 IDhizuku。
     * 内部 linkToDeath 自动重连。
     */
    private static IDhizuku requireServer() {
        if (mContext == null || mOwnerComponent == null) {
            throw new IllegalStateException("Dhizuku haven't been initialized");
        }
        String ownerPackageName = mOwnerComponent.getPackageName();
        IBinder binder = null;
        try {
            String authority = DhizukuVariables.getProviderAuthorityName(ownerPackageName);
            Uri uri = new Uri.Builder()
                    .scheme("content")
                    .authority(authority)
                    .build();

            Bundle extras = new Bundle();
            extras.putBinder(DhizukuVariables.EXTRA_CLIENT, new DhizukuClient());

            Bundle result = mContext.getContentResolver().call(
                    uri, DhizukuVariables.PROVIDER_METHOD_CLIENT, null, extras);

            if (result != null) {
                binder = result.getBinder(DhizukuVariables.PARAM_DHIZUKU_BINDER);
            }
        } catch (Exception e) {
            Log.e(TAG, "requireServer error", e);
        }

        if (binder == null) {
            throw new IllegalStateException("binder haven't been received");
        }

        final IBinder finalBinder = binder;
        try {
            finalBinder.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.w(TAG, "server binder died, re-initializing");
                    remote = null;
                    if (mContext != null) {
                        init(mContext);
                    }
                }
            }, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "linkToDeath failed", e);
        }

        return IDhizuku.Stub.asInterface(finalBinder);
    }

    // ======================== Server Info ========================

    public static int getVersionCode() {
        try {
            return requireServer().getVersionCode();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getVersionName() {
        try {
            return requireServer().getVersionName();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    // ======================== Permission ========================

    public static boolean isPermissionGranted() {
        try {
            return requireServer().isPermissionGranted();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 请求 Dhizuku API 权限（类似 Shizuku 的授权弹窗）。
     * 结果通过 listener 回调：PackageManager.PERMISSION_GRANTED(0) 或 PERMISSION_DENIED(-1)。
     */
    public static void requestPermission(DhizukuRequestPermissionListener listener) {
        if (mContext == null) {
            throw new IllegalStateException("Dhizuku haven't been initialized");
        }
        requestPermission(mContext, listener);
    }

    private static void requestPermission(Context context, DhizukuRequestPermissionListener listener) {
        String ownerPackageName = getOwnerPackageName();
        if (ownerPackageName == null) {
            throw new IllegalStateException("owner component haven't been received");
        }

        String action = DhizukuVariables.getActionRequestPermission(ownerPackageName);
        Intent intent = new Intent(action);
        intent.setPackage(ownerPackageName);

        ApplicationInfo info = context.getApplicationInfo();
        Bundle bundle = new Bundle();
        bundle.putInt(DhizukuVariables.PARAM_CLIENT_UID, info.uid);
        bundle.putBinder(DhizukuVariables.PARAM_CLIENT_REQUEST_PERMISSION_BINDER, listener);
        bundle.putStringArray(DhizukuVariables.PARAM_CLIENT_REQUEST_PERMISSIONS,
                new String[]{DhizukuVariables.PERMISSION_API});

        intent.putExtras(bundle);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "requestPermission: startActivity failed", e);
            try {
                listener.onRequestPermission(PackageManager.PERMISSION_DENIED);
            } catch (RemoteException ignored) {
            }
        }
    }

    // ======================== Remote Binder Transact ========================

    /**
     * 通过 Dhizuku 服务端代理执行 Binder transact（以 DO 权限）。
     *
     * @param target 目标 IBinder（如系统服务的 Binder 代理）
     * @param code   transact code
     * @param data   请求数据 Parcel
     * @param reply  响应 Parcel
     * @param flags  transact flags
     * @return transact 结果
     */
    public static boolean remoteTransact(IBinder target, int code, Parcel data, Parcel reply, int flags) {
        IDhizuku server = requireServer();
        Parcel remoteData = Parcel.obtain();
        try {
            remoteData.writeInterfaceToken(DhizukuVariables.BINDER_DESCRIPTOR);
            remoteData.writeStrongBinder(target);
            remoteData.writeInt(code);
            // 关键：官方实现会先写入调用方 Parcel 的长度，服务端据此读取，缺了这 4 字节服务端解析会错位
            remoteData.writeInt(data.dataSize());
            remoteData.appendFrom(data, 0, data.dataSize());
            return server.asBinder().transact(
                    DhizukuVariables.TRANSACT_CODE_REMOTE_BINDER, remoteData, reply, flags);
        } catch (RemoteException e) {
            e.printStackTrace();
            return false;
        } finally {
            remoteData.recycle();
        }
    }

    /**
     * 包装目标 Binder，使所有 transact 自动通过 Dhizuku 代理。
     */
    public static IBinder binderWrapper(IBinder target) {
        return new DhizukuBinderWrapper(target);
    }

    // ======================== Remote Process ========================

    /**
     * 以 DO 权限执行命令，返回标准 {@link Process}。
     *
     * @param cmd 命令数组，如 {"sh","-c","input keyevent 26"}
     * @param env 环境变量（可为 null）
     * @param dir 工作目录（可为 null）
     */
    public static DhizukuRemoteProcess newProcess(String[] cmd, String[] env, File dir) {
        try {
            String dirPath = dir != null ? dir.getPath() : null;
            IDhizukuRemoteProcess process = requireServer().remoteProcess(cmd, env, dirPath);
            return new DhizukuRemoteProcess(process);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    // ======================== User Service ========================

    public static void startUserService(DhizukuUserServiceArgs args) {
        try {
            requireServer().bindUserService(null, args.build());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public static void stopUserService(DhizukuUserServiceArgs args) {
        try {
            requireServer().unbindUserService(args.build());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 绑定用户服务。ServiceConnection 的回调由 Dhizuku 服务端触发。
     */
    public static boolean bindUserService(final DhizukuUserServiceArgs args, final ServiceConnection connection) {
        IDhizukuUserServiceConnection conn = new IDhizukuUserServiceConnection.Stub() {
            @Override
            public void connected(Bundle bundle, IBinder service) throws RemoteException {
                ComponentName name = args.getComponentName();
                connection.onServiceConnected(name, service);
            }

            @Override
            public void died(Bundle bundle) throws RemoteException {
                connection.onServiceDisconnected(args.getComponentName());
            }
        };
        try {
            requireServer().bindUserService(conn, args.build());
            return true;
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean unbindUserService(ServiceConnection connection) {
        try {
            requireServer().unbindUserService(null);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    // ======================== Delegated Scopes ========================

    public static String[] getDelegatedScopes() {
        try {
            return requireServer().getDelegatedScopes(DhizukuVariables.PERMISSION_API);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setDelegatedScopes(String[] scopes) {
        try {
            requireServer().setDelegatedScopes(DhizukuVariables.PERMISSION_API, scopes);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}
