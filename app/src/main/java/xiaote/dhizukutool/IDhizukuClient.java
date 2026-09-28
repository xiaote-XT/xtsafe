package xiaote.dhizukutool;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 客户端标识接口（AIDL 手写实现）。
 * 服务端通过此接口校验客户端身份，仅需返回版本号。
 */
public interface IDhizukuClient extends IInterface {

    String DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizukuClient";

    int getVersionCode() throws RemoteException;

    abstract class Stub extends Binder implements IDhizukuClient {
        static final int TRANSACTION_getVersionCode = IBinder.FIRST_CALL_TRANSACTION;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IDhizukuClient asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IDhizukuClient) return (IDhizukuClient) iin;
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
                    int result = getVersionCode();
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IDhizukuClient {
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
        }
    }
}
