package xiaote.dhizukutool;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 用户服务连接回调接口（AIDL 手写实现）。
 */
public interface IDhizukuUserServiceConnection extends IInterface {

    String DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizukuUserServiceConnection";

    void connected(Bundle args, IBinder service) throws RemoteException;

    void died(Bundle args) throws RemoteException;

    abstract class Stub extends Binder implements IDhizukuUserServiceConnection {
        static final int TRANSACTION_connected = IBinder.FIRST_CALL_TRANSACTION;
        static final int TRANSACTION_died = IBinder.FIRST_CALL_TRANSACTION + 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IDhizukuUserServiceConnection asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IDhizukuUserServiceConnection)
                return (IDhizukuUserServiceConnection) iin;
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
                case TRANSACTION_connected: {
                    data.enforceInterface(DESCRIPTOR);
                    Bundle arg0 = data.readBundle(getClass().getClassLoader());
                    IBinder arg1 = data.readStrongBinder();
                    connected(arg0, arg1);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_died: {
                    data.enforceInterface(DESCRIPTOR);
                    Bundle arg0 = data.readBundle(getClass().getClassLoader());
                    died(arg0);
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IDhizukuUserServiceConnection {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void connected(Bundle args, IBinder service) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeBundle(args);
                    data.writeStrongBinder(service);
                    mRemote.transact(TRANSACTION_connected, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void died(Bundle args) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeBundle(args);
                    mRemote.transact(TRANSACTION_died, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        }
    }
}
