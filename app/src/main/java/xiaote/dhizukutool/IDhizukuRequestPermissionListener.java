package xiaote.dhizukutool;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 权限请求回调接口（AIDL 手写实现）。
 * onRequestPermission 的 requestCode 使用 PackageManager.PERMISSION_GRANTED / PERMISSION_DENIED。
 */
public interface IDhizukuRequestPermissionListener extends IInterface {

    String DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener";

    void onRequestPermission(int requestCode) throws RemoteException;

    abstract class Stub extends Binder implements IDhizukuRequestPermissionListener {
        static final int TRANSACTION_onRequestPermission = IBinder.FIRST_CALL_TRANSACTION;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IDhizukuRequestPermissionListener asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IDhizukuRequestPermissionListener)
                return (IDhizukuRequestPermissionListener) iin;
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
                case TRANSACTION_onRequestPermission: {
                    data.enforceInterface(DESCRIPTOR);
                    int arg = data.readInt();
                    onRequestPermission(arg);
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IDhizukuRequestPermissionListener {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void onRequestPermission(int requestCode) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(requestCode);
                    mRemote.transact(TRANSACTION_onRequestPermission, data, reply, 0);
                    reply.readException();
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        }
    }
}
