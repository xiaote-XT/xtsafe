package xiaote.dhizukutool;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * 远程进程包装器，继承 {@link Process}，用法与 {@link Runtime#exec(String)} 一致。
 * 内部通过 IDhizukuRemoteProcess AIDL 获取 stdin/stdout/stderr 的 ParcelFileDescriptor。
 */
public class DhizukuRemoteProcess extends Process {

    private static final String TAG = "DhizukuRemoteProcess";

    private final IDhizukuRemoteProcess remote;
    private OutputStream outputStream;
    private InputStream inputStream;
    private InputStream errorStream;

    public DhizukuRemoteProcess(IDhizukuRemoteProcess remote) {
        this.remote = remote;
        IBinder binder = remote.asBinder();
        try {
            binder.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.w(TAG, "remote process died");
                }
            }, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "linkToDeath failed", e);
        }
    }

    @Override
    public OutputStream getOutputStream() {
        if (outputStream == null) {
            try {
                ParcelFileDescriptor pfd = remote.getOutputStream();
                if (pfd != null)
                    outputStream = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
        return outputStream;
    }

    @Override
    public InputStream getInputStream() {
        if (inputStream == null) {
            try {
                ParcelFileDescriptor pfd = remote.getInputStream();
                if (pfd != null)
                    inputStream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
        return inputStream;
    }

    @Override
    public InputStream getErrorStream() {
        if (errorStream == null) {
            try {
                ParcelFileDescriptor pfd = remote.getErrorStream();
                if (pfd != null)
                    errorStream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
        return errorStream;
    }

    @Override
    public int exitValue() {
        try {
            return remote.exitValue();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void destroy() {
        try {
            remote.destroy();
        } catch (RemoteException e) {
            Log.e(TAG, "destroy failed", e);
        }
    }

    @Override
    public boolean isAlive() {
        try {
            return remote.alive();
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public int waitFor() {
        try {
            return remote.waitFor();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
        try {
            return remote.waitForTimeout(timeout, unit.toString());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}
