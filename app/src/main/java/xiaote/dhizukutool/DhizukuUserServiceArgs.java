package xiaote.dhizukutool;

import android.content.ComponentName;
import android.os.Bundle;

/**
 * 用户服务参数构建器（Builder 模式）。
 * 用于 startUserService / stopUserService / bindUserService。
 */
public class DhizukuUserServiceArgs {

    private final Bundle bundle;

    public DhizukuUserServiceArgs(DhizukuUserServiceArgs args) {
        this.bundle = new Bundle(args.bundle);
    }

    public DhizukuUserServiceArgs(ComponentName componentName) {
        this(new Bundle());
        setComponentName(componentName);
    }

    public DhizukuUserServiceArgs(Bundle bundle) {
        this.bundle = bundle != null ? bundle : new Bundle();
    }

    public DhizukuUserServiceArgs setComponentName(ComponentName name) {
        bundle.putParcelable(DhizukuVariables.PARAM_COMPONENT, name);
        return this;
    }

    @SuppressWarnings("deprecation")
    public ComponentName getComponentName() {
        return (ComponentName) bundle.getParcelable(DhizukuVariables.PARAM_COMPONENT);
    }

    public Bundle build() {
        return bundle;
    }

    public String flattenToShortString() {
        ComponentName name = getComponentName();
        return name != null ? name.flattenToShortString() : "";
    }
}
