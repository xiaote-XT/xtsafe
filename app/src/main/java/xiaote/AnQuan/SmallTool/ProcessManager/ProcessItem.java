package xiaote.AnQuan.SmallTool.ProcessManager;

/**
 * 单条后台进程信息。
 *
 * name        原始进程名，如 com.tencent.mm:push
 * packageName 去掉 :xxx 后缀后的包名，如 com.tencent.mm
 * rssKb       常驻内存（KB），来自 ps 的 RSS 列
 * isSystem    是否为系统应用（FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP）
 * isSystemUi  是否为 com.android.systemui（仅列出，禁止任何操作）
 * isUnknown   进程名不是已安装包名（如 system_server、surfaceflinger、
 *             shizuku_server、zygote64、HAL 进程等），查不到 ApplicationInfo。
 *             此类进程一律按系统级处理，操作前必须二次确认。
 * appName     可读应用名，扫描后由界面层填充；取不到时留空
 */
public class ProcessItem {
    public int pid;
    public String name = "";
    public String packageName = "";
    public int rssKb;
    public boolean isSystem;
    public boolean isSystemUi;
    public boolean isUnknown;
    public String appName = "";
}
