package xiaote.dhizukutool;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.lang.reflect.Method;

/**
 * 远程进程接口（AIDL 手写实现）。
 * 对应 Dhizuku 服务端以 DO 权限启动的子进程。
 * <p>
 * 官方 AIDL 用 Parcel.writeTypedObject / readTypedObject 传递 ParcelFileDescriptor，
 * 这两个方法是 API 30 才公开的，而且不同版本线格式不同（Android 13 起会多写一个非空标志位）。
 * 因此这里用反射调用系统同名方法，保证与 Dhizuku 服务端字节级一致；
 * 反射不可用时退回“直接 writeToParcel / CREATOR.createFromParcel”的旧格式。
 * <p>
 * 事务码：1=getOutputStream 2=getInputStream 3=getErrorStream
 * 4=exitValue 5=destroy 6=alive 7=waitFor 8=waitForTimeout
 */
public interface IDhizukuRemoteProcess extends IInterface {

    String DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizukuRemoteProcess";

    ParcelFileDescriptor getOutputStream() throws RemoteException;

    ParcelFileDescriptor getInputStream() throws RemoteException;

    ParcelFileDescriptor getErrorStream() throws RemoteException;

    int exitValue() throws RemoteException;

    void destroy() throws RemoteException;

    boolean alive() throws RemoteException;

    int waitFor() throws RemoteException;

    boolean waitForTimeout(long timeout, String unit) throws RemoteException;

    abstract class Stub extends Binder implements IDhizukuRemoteProcess {
        static final int TRANSACTION_getOutputStream = IBinder.FIRST_CALL_TRANSACTION;
        static final int TRANSACTION_getInputStream = IBinder.FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_getErrorStream = IBinder.FIRST_CALL_TRANSACTION + 2;
        static final int TRANSACTION_exitValue = IBinder.FIRST_CALL_TRANSACTION + 3;
        static final int TRANSACTION_destroy = IBinder.FIRST_CALL_TRANSACTION + 4;
        static final int TRANSACTION_alive = IBinder.FIRST_CALL_TRANSACTION + 5;
        static final int TRANSACTION_waitFor = IBinder.FIRST_CALL_TRANSACTION + 6;
        static final int TRANSACTION_waitForTimeout = IBinder.FIRST_CALL_TRANSACTION + 7;

        private static final Method WRITE_TYPED_OBJECT;
        private static final Method READ_TYPED_OBJECT;

        static {
            Method w = null;
            Method r = null;
            try {
                w = Parcel.class.getMethod("writeTypedObject", Parcelable.class, int.class);
                r = Parcel.class.getMethod("readTypedObject", Parcelable.Creator.class);
            } catch (Throwable ignored) {
                // 老系统没有这两个方法，走回退分支
            }
            WRITE_TYPED_OBJECT = w;
            READ_TYPED_OBJECT = r;
        }

        static void writePfd(Parcel reply, ParcelFileDescriptor pfd) {
            if (WRITE_TYPED_OBJECT != null) {
                try {
                    WRITE_TYPED_OBJECT.invoke(reply, pfd, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    return;
                } catch (Throwable ignored) {
                }
            }
            if (pfd != null) {
                pfd.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            }
        }

        static ParcelFileDescriptor readPfd(Parcel reply) {
            if (READ_TYPED_OBJECT != null) {
                try {
                    return (ParcelFileDescriptor) READ_TYPED_OBJECT.invoke(reply, ParcelFileDescriptor.CREATOR);
                } catch (Throwable ignored) {
                }
            }
            return ParcelFileDescriptor.CREATOR.createFromParcel(reply);
        }

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IDhizukuRemoteProcess asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IDhizukuRemoteProcess) return (IDhizukuRemoteProcess) iin;
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
                case TRANSACTION_getOutputStream: {
                    data.enforceInterface(DESCRIPTOR);
                    ParcelFileDescriptor r = getOutputStream();
                    reply.writeNoException();
                    writePfd(reply, r);
                    return true;
                }
                case TRANSACTION_getInputStream: {
                    data.enforceInterface(DESCRIPTOR);
                    ParcelFileDescriptor r = getInputStream();
                    reply.writeNoException();
                    writePfd(reply, r);
                    return true;
                }
                case TRANSACTION_getErrorStream: {
                    data.enforceInterface(DESCRIPTOR);
                    ParcelFileDescriptor r = getErrorStream();
                    reply.writeNoException();
                    writePfd(reply, r);
                    return true;
                }
                case TRANSACTION_exitValue: {
                    data.enforceInterface(DESCRIPTOR);
                    int r = exitValue();
                    reply.writeNoException();
                    reply.writeInt(r);
                    return true;
                }
                case TRANSACTION_destroy: {
                    data.enforceInterface(DESCRIPTOR);
                    destroy();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_alive: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean r = alive();
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                case TRANSACTION_waitFor: {
                    data.enforceInterface(DESCRIPTOR);
                    int r = waitFor();
                    reply.writeNoException();
                    reply.writeInt(r);
                    return true;
                }
                case TRANSACTION_waitForTimeout: {
                    data.enforceInterface(DESCRIPTOR);
                    long arg0 = data.readLong();
                    String arg1 = data.readString();
                    boolean r = waitForTimeout(arg0, arg1);
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IDhizukuRemoteProcess {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public ParcelFileDescriptor getOutputStream() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getOutputStream, data, reply, 0);
                    reply.readException();
                    return readPfd(reply);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public ParcelFileDescriptor getInputStream() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getInputStream, data, reply, 0);
                    reply.readException();
                    return readPfd(reply);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public ParcelFileDescriptor getErrorStream() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getErrorStream, data, reply, 0);
                    reply.readException();
                    return readPfd(reply);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public int exitValue() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_exitValue, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void destroy() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_destroy, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public boolean alive() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_alive, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public int waitFor() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_waitFor, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public boolean waitForTimeout(long timeout, String unit) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeLong(timeout);
                    data.writeString(unit);
                    mRemote.transact(TRANSACTION_waitForTimeout, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        }
    }
}
