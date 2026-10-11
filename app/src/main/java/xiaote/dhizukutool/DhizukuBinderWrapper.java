package xiaote.dhizukutool;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.FileDescriptor;

/**
 * Binder 包装器：将目标 IBinder 的所有 transact 调用代理到 Dhizuku 服务端执行。
 * 配合 {@link Dhizuku#remoteTransact} 使用，使调用者以 DO 权限操作系统 Binder。
 */
public class DhizukuBinderWrapper implements IBinder {

    private final IBinder target;

    public DhizukuBinderWrapper(IBinder target) {
        this.target = target;
    }

    public IBinder getTarget() {
        return target;
    }

    @Override
    public String getInterfaceDescriptor() throws RemoteException {
        return target.getInterfaceDescriptor();
    }

    @Override
    public boolean pingBinder() {
        return target.pingBinder();
    }

    @Override
    public boolean isBinderAlive() {
        return target.isBinderAlive();
    }

    @Override
    public IInterface queryLocalInterface(String descriptor) {
        // 始终返回 null，强制所有调用走 transact -> remoteTransact
        return null;
    }

    @Override
    public void dump(FileDescriptor fd, String[] args) {
        try {
            target.dump(fd, args);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void dumpAsync(FileDescriptor fd, String[] args) {
        try {
            target.dumpAsync(fd, args);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return Dhizuku.remoteTransact(target, code, data, reply, flags);
    }

    @Override
    public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
        target.linkToDeath(recipient, flags);
    }

    @Override
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
        return target.unlinkToDeath(recipient, flags);
    }
}
