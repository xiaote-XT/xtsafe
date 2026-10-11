package xiaote.dhizukutool;

/**
 * 客户端桩实现。服务端通过此 Binder 校验调用方身份。
 * VERSION_CODE 必须与服务端兼容（2.6.0 = 1）。
 */
public class DhizukuClient extends IDhizukuClient.Stub {

    public static final int VERSION_CODE = 1;

    @Override
    public int getVersionCode() {
        return VERSION_CODE;
    }
}
