package xiaote.dhizukutool;

import android.content.ComponentName;

/**
 * Dhizuku 共享常量。从 Dhizuku-API 2.6.0 反编译提取，值与官方 SDK 完全一致。
 */
public final class DhizukuVariables {

    public static final String OFFICIAL_PACKAGE_NAME = "com.rosan.dhizuku";
    public static final String PACKAGE_NAME = OFFICIAL_PACKAGE_NAME;

    public static final ComponentName OFFICIAL_COMPONENT_NAME =
            new ComponentName(OFFICIAL_PACKAGE_NAME, "com.rosan.dhizuku.server.DhizukuDAReceiver");
    public static final ComponentName COMPONENT_NAME = OFFICIAL_COMPONENT_NAME;

    public static final String OFFICIAL_PERMISSION_API = "com.rosan.dhizuku.permission.API";
    public static final String PERMISSION_API = OFFICIAL_PERMISSION_API;

    public static final String BINDER_DESCRIPTOR = "com.rosan.dhizuku.server";

    public static final String PROVIDER_METHOD_CLIENT = "client";
    public static final String EXTRA_CLIENT = "client";

    public static final String PARAM_DHIZUKU_BINDER = "dhizuku_binder";
    public static final String PARAM_CLIENT_UID = "uid";
    public static final String PARAM_CLIENT_REQUEST_PERMISSION_BINDER = "request_permission_binder";
    public static final String PARAM_CLIENT_REQUEST_PERMISSIONS = "request_permissions";
    public static final String PARAM_COMPONENT = "component";

    /** 远程 Binder 代理的 transact 码，固定为 11。 */
    public static final int TRANSACT_CODE_REMOTE_BINDER = 11;

    private DhizukuVariables() {}

    /**
     * 根据服务端包名生成 ContentProvider authority。
     * 官方包名使用硬编码 authority，非官方则动态拼接。
     */
    public static String getProviderAuthorityName(String packageName) {
        if (OFFICIAL_PACKAGE_NAME.equals(packageName)) {
            return "com.rosan.dhizuku.server.provider";
        }
        return packageName + ".dhizuku_server.provider";
    }

    /**
     * 根据服务端包名生成请求权限的 Intent action。
     */
    public static String getActionRequestPermission(String packageName) {
        if (OFFICIAL_PACKAGE_NAME.equals(packageName)) {
            return "com.rosan.dhizuku.action.request.permission";
        }
        return packageName + ".action.REQUEST_DHIZUKU_PERMISSION";
    }
}
