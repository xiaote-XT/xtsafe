package xiaote.dhizukutool;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Dhizuku 服务端主接口（AIDL 手写实现）。
 * 通过 ContentProvider 获取 IBinder 后用 Stub.asInterface 包装。
 * <p>
 * 方法 transact 码按声明顺序从 FIRST_CALL_TRANSACTION(1) 递增：
 * 1=getVersionCode 2=getVersionName 3=isPermissionGranted
 * 4=remoteProcess 5=bindUserService 6=unbindUserService
 * 7=unbindUserServiceByConnection 8=getDelegatedScopes 9=setDelegatedScopes
 * 另有 TRANSACT_CODE_REMOTE_BINDER=11 用于 remoteTransact 代理。
 */
public interface IDhizuku extends IInterface {

    String DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizuku";

    int getVersionCode() throws RemoteException;

    String getVersionName() throws RemoteException;

    boolean isPermissionGranted() throws RemoteException;

    IDhizukuRemoteProcess remoteProcess(String[] cmd, String[] env, String dir) throws RemoteException;

    void bindUserService(IDhizukuUserServiceConnection connection, Bundle args) throws RemoteException;

    void unbindUserService(Bundle args) throws RemoteException;

    void unbindUserServiceByConnection(IDhizukuUserServiceConnection connection, Bundle args) throws RemoteException;

    String[] getDelegatedScopes(String key) throws RemoteException;

    void setDelegatedScopes(String key, String[] scopes) throws RemoteException;

    abstract class Stub extends Binder implements IDhizuku {
        // 注意：官方 Dhizuku 的 remoteProcess 及之后的方法 transact 码并非连续
        // （1~3 之后跳到 12），必须与 IDhizuku$Stub 的常量完全一致。
        static final int TRANSACTION_getVersionCode = 1;
        static final int TRANSACTION_getVersionName = 2;
        static final int TRANSACTION_isPermissionGranted = 3;
        static final int TRANSACTION_remoteProcess = 12;
        static final int TRANSACTION_bindUserService = 13;
        static final int TRANSACTION_unbindUserService = 14;
        static final int TRANSACTION_unbindUserServiceByConnection = 15;
        static final int TRANSACTION_getDelegatedScopes = 16;
        static final int TRANSACTION_setDelegatedScopes = 17;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IDhizuku asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IDhizuku) return (IDhizuku) iin;
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            switch (code) {
                case TRANSACTION_getVersionCode: {
                    data.enforceInterface(DESCRIPTOR);
                    int r = getVersionCode();
                    reply.writeNoException();
                    reply.writeInt(r);
                    return true;
                }
                case TRANSACTION_getVersionName: {
                    data.enforceInterface(DESCRIPTOR);
                    String r = getVersionName();
                    reply.writeNoException();
                    reply.writeString(r);
                    return true;
                }
                case TRANSACTION_isPermissionGranted: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean r = isPermissionGranted();
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                case TRANSACTION_remoteProcess: {
                    data.enforceInterface(DESCRIPTOR);
                    String[] arg0 = data.createStringArray();
                    String[] arg1 = data.createStringArray();
                    String arg2 = data.readString();
                    IDhizukuRemoteProcess r = remoteProcess(arg0, arg1, arg2);
                    reply.writeNoException();
                    reply.writeStrongBinder(r == null ? null : r.asBinder());
                    return true;
                }
                case TRANSACTION_bindUserService: {
                    data.enforceInterface(DESCRIPTOR);
                    IDhizukuUserServiceConnection arg0 =
                            IDhizukuUserServiceConnection.Stub.asInterface(data.readStrongBinder());
                    Bundle arg1 = data.readBundle(getClass().getClassLoader());
                    bindUserService(arg0, arg1);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_unbindUserService: {
                    data.enforceInterface(DESCRIPTOR);
                    Bundle arg0 = data.readBundle(getClass().getClassLoader());
                    unbindUserService(arg0);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_unbindUserServiceByConnection: {
                    data.enforceInterface(DESCRIPTOR);
                    IDhizukuUserServiceConnection arg0 =
                            IDhizukuUserServiceConnection.Stub.asInterface(data.readStrongBinder());
                    Bundle arg1 = data.readBundle(getClass().getClassLoader());
                    unbindUserServiceByConnection(arg0, arg1);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_getDelegatedScopes: {
                    data.enforceInterface(DESCRIPTOR);
                    String arg0 = data.readString();
                    String[] r = getDelegatedScopes(arg0);
                    reply.writeNoException();
                    reply.writeStringArray(r);
                    return true;
                }
                case TRANSACTION_setDelegatedScopes: {
                    data.enforceInterface(DESCRIPTOR);
                    String arg0 = data.readString();
                    String[] arg1 = data.createStringArray();
                    setDelegatedScopes(arg0, arg1);
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IDhizuku {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public int getVersionCode() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getVersionCode, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public String getVersionName() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getVersionName, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public boolean isPermissionGranted() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_isPermissionGranted, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public IDhizukuRemoteProcess remoteProcess(String[] cmd, String[] env, String dir) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStringArray(cmd);
                    data.writeStringArray(env);
                    data.writeString(dir);
                    mRemote.transact(TRANSACTION_remoteProcess, data, reply, 0);
                    reply.readException();
                    return IDhizukuRemoteProcess.Stub.asInterface(reply.readStrongBinder());
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void bindUserService(IDhizukuUserServiceConnection connection, Bundle args) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(connection == null ? null : connection.asBinder());
                    data.writeBundle(args);
                    mRemote.transact(TRANSACTION_bindUserService, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void unbindUserService(Bundle args) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeBundle(args);
                    mRemote.transact(TRANSACTION_unbindUserService, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void unbindUserServiceByConnection(IDhizukuUserServiceConnection connection, Bundle args) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(connection == null ? null : connection.asBinder());
                    data.writeBundle(args);
                    mRemote.transact(TRANSACTION_unbindUserServiceByConnection, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public String[] getDelegatedScopes(String key) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(key);
                    mRemote.transact(TRANSACTION_getDelegatedScopes, data, reply, 0);
                    reply.readException();
                    return reply.createStringArray();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void setDelegatedScopes(String key, String[] scopes) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(key);
                    data.writeStringArray(scopes);
                    mRemote.transact(TRANSACTION_setDelegatedScopes, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        }
    }
}
