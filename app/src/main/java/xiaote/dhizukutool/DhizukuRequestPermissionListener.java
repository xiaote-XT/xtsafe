package xiaote.dhizukutool;

/**
 * 权限请求回调基类。继承自 IDhizukuRequestPermissionListener.Stub，
 * 实现 onRequestPermission 即可接收权限授予/拒绝结果。
 * <p>
 * requestCode 使用 PackageManager.PERMISSION_GRANTED(0) / PERMISSION_DENIED(-1)。
 */
public abstract class DhizukuRequestPermissionListener extends IDhizukuRequestPermissionListener.Stub {
}
