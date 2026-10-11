package xiaote.AnQuan.InternalApi;

/**
 * InternalApi 统一返回结果。
 *
 * 为什么不直接返回 boolean：
 *   同一次调用往往要按优先级试多条提权通道（Shizuku → Dhizuku → Root → DeviceOwner），
 *   调用方除了「成没成」，还常需要知道「是哪条通道做成的」用于日志与界面提示，
 *   以及「最后一条通道为什么失败」用于排查。这些信息用 boolean 表达不了。
 *
 * 约定：
 *   ok      —— 操作是否成功
 *   via     —— 实际生效的通道；失败时为最后尝试的通道；一条都没尝试（无可用通道）时为 null
 *   detail  —— 可读描述；成功时可为 null，失败时写明原因
 *   count   —— 批量操作成功条数；单目标成功为 1，失败为 0
 */
public final class ApiResult {

    public final boolean ok;
    public final Capability via;
    public final String detail;
    public final int count;

    private ApiResult(boolean ok, Capability via, String detail, int count) {
        this.ok = ok;
        this.via = via;
        this.detail = detail;
        this.count = count;
    }

    public static ApiResult success(Capability via) {
        return new ApiResult(true, via, null, 1);
    }

    public static ApiResult success(Capability via, String detail) {
        return new ApiResult(true, via, detail, 1);
    }

    public static ApiResult successCount(Capability via, int count, String detail) {
        return new ApiResult(true, via, detail, count);
    }

    public static ApiResult failure(Capability via, String detail) {
        return new ApiResult(false, via, detail, 0);
    }

    /**
     * 无可用通道。
     *
     * 与 failure 区分开：
     *   · failure      —— 通道可用，但操作被系统拒绝或没生效，用户可以重试或换目标；
     *   · unsupported  —— 压根没有通道能做这件事，界面应提示去授权，而不是提示重试。
     */
    public static ApiResult unsupported(String detail) {
        return new ApiResult(false, null, detail, 0);
    }

    /** 是否属于「没有任何通道可用」 */
    public boolean isUnsupported() {
        return !ok && via == null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(ok ? "成功" : "失败");
        if (via != null) sb.append(" via ").append(via.label);
        if (count > 1) sb.append(" count=").append(count);
        if (detail != null && detail.length() > 0) sb.append(" — ").append(detail);
        return sb.toString();
    }
}
