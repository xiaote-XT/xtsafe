package xiaote.AnQuan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public class SecurityScanActivity extends Activity {

    private static final int RISK_LOW = 1;
    private static final int RISK_SENSITIVE = 2;
    private static final int RISK_HIGH = 3;
    private static final int RISK_EXTREME = 4;

    private ListView appListView;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button scanButton;
    private Button selectApkButton;

    private List<AppInfo> appList = new ArrayList<AppInfo>();
    private AppAdapter adapter;
    private PackageManager pm;

    private static final int REQUEST_SELECT_APK = 1001;

    // 危险权限组合规则（由 JSON 加载）
    private static PermissionRule[] PERMISSION_RULES = new PermissionRule[0];

    // 风险类名检测（由 JSON 加载）
    private static RiskClassRule[] CLASS_RULES = new RiskClassRule[0];

    // 文件检测规则（由 JSON 加载）
    private static FileRule[] FILE_RULES = new FileRule[0];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security_scan);

        pm = getPackageManager();

        appListView = (ListView) findViewById(R.id.appListView);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        statusText = (TextView) findViewById(R.id.statusText);
        scanButton = (Button) findViewById(R.id.scanButton);
        selectApkButton = (Button) findViewById(R.id.selectApkButton);

        adapter = new AppAdapter(this, appList);
        appListView.setAdapter(adapter);

        // 初始化规则（从 JSON 加载）。走 ensureRulesLoaded：
        // 若 App.onCreate 已经加载过则跳过，避免重复解析三源 JSON。
        ensureRulesLoaded(this);
        

        // 处理来自分享/打开的文件
        handleIncomingFile(getIntent());

        // 异步加载应用列表，避免ANR
        new LoadAppsTask(this).execute();

        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (appList.isEmpty()) {
                    Toast.makeText(SecurityScanActivity.this, R.string.no_app_to_scan, Toast.LENGTH_SHORT).show();
                    return;
                }
                new ScanTask(SecurityScanActivity.this).execute();
            }
        });

        selectApkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/vnd.android.package-archive");
                startActivityForResult(intent, REQUEST_SELECT_APK);
            }
        });

        Button ruleBtn = (Button) findViewById(R.id.ruleManagerBtn);
        if (ruleBtn != null) {
            ruleBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(SecurityScanActivity.this, RuleManagerActivity.class));
                }
            });
        }

        appListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final AppInfo app = appList.get(position);
                if (app.scanResult != null && !app.scanResult.isEmpty()) {
                    showScanResultDialog(app);
                } else {
                    // 未扫描，询问是否立即扫描
                    new AlertDialog.Builder(SecurityScanActivity.this)
                            .setTitle(R.string.scan_app_title)
                            .setMessage(getString(R.string.scan_app_ask, app.appName))
                            .setPositiveButton(R.string.scan_btn, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    new SingleAppScanTask(SecurityScanActivity.this, app).execute();
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SELECT_APK && resultCode == RESULT_OK) {
            Uri uri = data.getData();
            if (uri != null) {
                Toast.makeText(this, getString(R.string.selected_toast, uri.getLastPathSegment()), Toast.LENGTH_SHORT).show();
                File cacheFile = copyUriToCache(uri);
                if (cacheFile != null) {
                    new SingleScanTask(this, cacheFile).execute();
                } else {
                    Toast.makeText(this, R.string.copy_apk_fail, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private File copyUriToCache(Uri uri) {
        try {
            ContentResolver resolver = getContentResolver();
            InputStream input = resolver.openInputStream(uri);
            if (input == null) return null;
            File cacheDir = getExternalCacheDir();
            if (cacheDir == null) cacheDir = getCacheDir();
            if (cacheDir == null) return null;
            File outFile = new File(cacheDir, "temp_scan_" + System.currentTimeMillis() + ".apk");
            OutputStream output = new FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
            }
            output.close();
            input.close();
            return outFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void handleIncomingFile(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        }
        if (uri == null) return;
        String path = uri.getPath();
        if (path == null) return;
        String lower = path.toLowerCase();
        if (!lower.endsWith(".apk") && !lower.endsWith(".1") && !lower.endsWith(".xtapp")) {
            Toast.makeText(this, R.string.unsupported_format, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, getString(R.string.received_file, uri.getLastPathSegment()), Toast.LENGTH_SHORT).show();
        File cacheFile = copyUriToCache(uri);
        if (cacheFile != null) {
            new SingleScanTask(this, cacheFile).execute();
        } else {
            Toast.makeText(this, R.string.copy_file_fail, Toast.LENGTH_LONG).show();
        }
    }

    private String getAppNameFromApk(String apkPath) {
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(apkPath, 0);
            String label = getLabelFromArchive(pm, pi, apkPath);
            if (!label.isEmpty()) return label;
        } catch (Exception e) {}
        return null;
    }

    /**
     * 从未安装的 APK 里取应用名。
     *
     * 关键点：getPackageArchiveInfo 返回的 applicationInfo 没有 sourceDir /
     * publicSourceDir，PackageManager 解析不到资源表，getApplicationLabel 会
     * 抛异常或直接返回包名。必须先把这两个字段补成 apkPath，再取 label。
     *
     * 取到的仍是包名时视为失败，返回空串让调用方回退。
     *
     * @return 应用名；取不到返回空串（不是 null）
     */
    public static String getLabelFromArchive(PackageManager pm, PackageInfo pi, String apkPath) {
        if (pm == null || pi == null || pi.applicationInfo == null) return "";
        try {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai.sourceDir == null) ai.sourceDir = apkPath;
            if (ai.publicSourceDir == null) ai.publicSourceDir = apkPath;
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null && label.length() > 0) {
                String s = label.toString();
                if (!s.equals(pi.packageName)) return s;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * 取综合风险等级。
     *
     * 直接采用扫描过程中各检测点写入的 result.maxRiskLevel：
     *   · 风险签名、名称/包名仿冒（如"我的世界"非官方包名）写 RISK_EXTREME
     *   · 类名规则、权限组合、DEX 字符串特征、跨字符串话术各写自己的 riskLevel
     * 之前这里拿描述去 CLASS_RULES 里反查等级，导致跨字符串话术（描述不在
     * CLASS_RULES 中）和 specialWarning 类命中被算成 0 分，列表显示"安全"。
     */
    private int computeRiskLevel(ScanResult result) {
        if (result == null) return 0;
        return result.maxRiskLevel;
    }

    private String riskText(int risk) {
        switch (risk) {
            case RISK_EXTREME: return getString(R.string.risk_extreme);
            case RISK_HIGH: return getString(R.string.risk_high);
            case RISK_SENSITIVE: return getString(R.string.risk_sensitive);
            case RISK_LOW: return getString(R.string.risk_low);
            default: return getString(R.string.risk_safe);
        }
    }

    private int riskColor(int risk) {
        switch (risk) {
            case RISK_EXTREME: return Color.rgb(200, 30, 30);
            case RISK_HIGH: return Color.rgb(230, 90, 30);
            case RISK_SENSITIVE: return Color.rgb(240, 160, 30);
            case RISK_LOW: return Color.rgb(90, 160, 90);
            default: return Color.rgb(100, 100, 100);
        }
    }

    private void showScanResultDialog(final AppInfo app) {
        final ScanResult scan = app.scanResult;
        StringBuilder sb = new StringBuilder();
        if (scan != null && scan.malwareConfirmed) {
            sb.append("该软件已确定为恶意应用，以下是扫描结果，可能存在不准确\n\n");
        }
        sb.append("包名: ").append(app.packageName).append("\n");
        sb.append("综合评估: ").append(riskText(app.riskLevel)).append("\n\n");
        if (scan != null && scan.specialWarning != null && !scan.specialWarning.isEmpty()) {
            sb.append(scan.specialWarning).append("\n\n");
        }
        if (scan != null && scan.confirmedCalls != null && !scan.confirmedCalls.isEmpty()) {
            sb.append("已确认敏感API调用: ").append(scan.confirmedCalls.size()).append(" 项（详见详细）\n\n");
        }
        if (scan != null) {
            if (!scan.riskSignatures.isEmpty()) {
                sb.append("签名提醒:\n");
                for (String sig : scan.riskSignatures) {
                    sb.append("  · ").append(sig).append("\n");
                }
                sb.append("\n");
            }
            if (!scan.combinations.isEmpty()) {
                sb.append("权限提醒:\n");
                for (String comb : scan.combinations) {
                    sb.append("  · ").append(comb).append("\n");
                }
                sb.append("\n");
            }
            if (!scan.riskClasses.isEmpty()) {
                sb.append("类名提醒:\n");
                for (String cls : scan.riskClasses) {
                    sb.append("  · ").append(cls).append("\n");
                }
                sb.append("\n");
            }
            if (!scan.fileWarnings.isEmpty()) {
                sb.append("文件提醒:\n");
                for (String f : scan.fileWarnings) {
                    sb.append("  · ").append(f).append("\n");
                }
                sb.append("\n");
            }
        }
        if (sb.length() == 0) {
            sb.append("未发现可疑行为");
        }
        final String summary = sb.toString();
        final String detail = (scan != null) ? scan.toDetail() : "无";
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.scan_result_title_fmt, app.appName))
                .setMessage(summary)
                .setPositiveButton("卸载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_DELETE);
                            intent.setData(android.net.Uri.parse("package:" + app.packageName));
                            startActivity(intent);
                        } catch (Exception e) {
                            Toast.makeText(SecurityScanActivity.this, "无法启动卸载", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("忽略", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setNeutralButton("详细", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showCopyableDetailDialog(getString(R.string.detail_title_fmt, app.appName), detail, scan != null ? buildLogs(scan) : "");
                    }
                })
                .show();
    }

    private void showSingleScanResultDialog(final String fileName, final ScanResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.malwareConfirmed) {
            sb.append("该软件已确定为恶意应用，以下是扫描结果，可能存在不准确\n\n");
        }
        sb.append("文件: ").append(fileName).append("\n");
        sb.append("综合评估: ").append(riskText(computeRiskLevel(result))).append("\n\n");
        if (result.specialWarning != null && !result.specialWarning.isEmpty()) {
            sb.append(result.specialWarning).append("\n\n");
        }
        if (result.confirmedCalls != null && !result.confirmedCalls.isEmpty()) {
            sb.append("已确认敏感API调用: ").append(result.confirmedCalls.size()).append(" 项（详见详细）\n\n");
        }
        if (!result.riskSignatures.isEmpty()) {
            sb.append("签名提醒:\n");
            for (String sig : result.riskSignatures) {
                sb.append("  · ").append(sig).append("\n");
            }
            sb.append("\n");
        }
        if (!result.combinations.isEmpty()) {
            sb.append("权限提醒:\n");
            for (String comb : result.combinations) {
                sb.append("  · ").append(comb).append("\n");
            }
            sb.append("\n");
        }
        if (!result.riskClasses.isEmpty()) {
            sb.append("类名提醒:\n");
            for (String cls : result.riskClasses) {
                sb.append("  · ").append(cls).append("\n");
            }
            sb.append("\n");
        }
        if (!result.fileWarnings.isEmpty()) {
            sb.append("文件提醒:\n");
            for (String f : result.fileWarnings) {
                sb.append("  · ").append(f).append("\n");
            }
            sb.append("\n");
        }
        if (result.combinations.isEmpty() && result.riskClasses.isEmpty() && result.fileWarnings.isEmpty()) {
            sb.append("未发现可疑行为");
        }
        final String summary = sb.toString();
        final String detail = result.toDetail();
        new AlertDialog.Builder(this)
                .setTitle("扫描结果")
                .setMessage(summary)
                .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("忽略", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setNeutralButton("详细", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showCopyableDetailDialog(getString(R.string.detail_title_fmt, fileName), detail, buildLogs(result));
                    }
                })
                .show();
    }

    private static void addUniqueCombination(ScanResult result, String desc) {
        if (!result.combinations.contains(desc)) {
            result.combinations.add(desc);
        }
    }

    // 风险签名 MD5 列表
    private static String[] RISK_SIGNATURES = new String[]{
        "5b1d20e8804cea1b0ab21ded391d9e8e",
        "e89b158e4bcf988ebd09eb83f5378e87",
        "64843786c6ada15ca4254f4da77e4978",
        "9ae14f85672bfc35d63ac4dff3c263dc",
        "9f994020b7bf12a4f2046606ad5e2a78",
        "5c7d1be7d72940d5d8dcfe45bf09a4c2"
    };

    // 白名单：包名 + 签名MD5，两者完全匹配才跳过扫描（由规则 JSON 加载）
    private static String[][] WHITELIST = new String[0][0];

    // 名称/包名校验规则（默认内置；可被 scan_rules.json 的 namePackageRules 覆盖）
    // 木马提示文案固定写死在 Java，不随 JSON 配置变化
    private static final String TROJAN_WARNING =
            "疑似木马，可能通过无障碍权限进行盗取支付密码、偷偷贷款、转接等高风险行为，建议立即卸载，不要犹豫！";

    private static NamePackageRule[] NAME_PACKAGE_RULES = defaultNamePackageRules();

    /** 默认内置名称/包名校验规则 */
    private static NamePackageRule[] defaultNamePackageRules() {
        return new NamePackageRule[]{
                new NamePackageRule("我的世界",
                        new String[]{
                                "com.netease.mc", "com.netease.mc.aligames", "com.netease.mc.beta",
                                "com.netease.mc.huawei", "com.netease.mc.xiaomi", "com.netease.mc.oppo",
                                "com.netease.mc.vivo", "com.netease.mc.tencent", "com.netease.mc.baidu",
                                "com.netease.mc.uc", "com.netease.mc.qh360", "com.netease.mc.meizu",
                                "com.netease.mc.lenovo", "com.netease.mc.samsung"
                        },
                        TROJAN_WARNING)
        };
    }

    /** 名称与包名校验规则（应用名关键词 + 官方/渠道包名 + 不匹配提示文案） */
    private static class NamePackageRule {
        String nameKeyword;
        Set<String> officialPackages;
        String warning;

        NamePackageRule(String nameKeyword, String[] packages, String warning) {
            this.nameKeyword = nameKeyword;
            this.officialPackages = new HashSet<String>(Arrays.asList(packages));
            this.warning = warning;
        }
    }

    // DEX 字符串特征规则（默认内置；可被 scan_rules.json 的 stringFeatures 覆盖）
    private static StringFeatureRule[] STRING_FEATURE_RULES = defaultStringFeatureRules();

    /** 默认内置 DEX 字符串特征规则 */
    private static StringFeatureRule[] defaultStringFeatureRules() {
        return new StringFeatureRule[]{
                new StringFeatureRule(new String[]{"enabled_accessibility_services", "accessibility_enabled"},
                        "代码中存在无障碍强制启用特征", 1),
                new StringFeatureRule(new String[]{"captureScreen", "MediaProjection"},
                        "代码中存在屏幕录制特征", 2),
                new StringFeatureRule(new String[]{"CommandId", "OnCallbackCommand"},
                        "代码中存在远程命令执行特征", 3),
                new StringFeatureRule(new String[]{"installApk", "pm uninstall"},
                        "代码中存在静默安装/卸载特征", 3)
        };
    }

    /**
     * 跨字符串组合话术规则。
     *
     * 判定：组内每个词只要在 DEX 字符串池中任意位置出现即命中——
     * 不要求连续、不要求顺序、不要求出现在同一条字符串里。
     * 木马常把话术拆成多条字符串常量，或在同一条字符串里插换行、零宽字符，
     * 因此这里先在整池字符串上归一化（去空白与零宽字符）再逐词判断。
     *
     * 应用名（如"我的世界"）是变量，不写进规则；靠话术框架词识别。
     */
    private static PhraseGroupRule[] PHRASE_GROUP_RULES = defaultPhraseGroupRules();

    /** 默认内置跨字符串组合话术规则 */
    private static PhraseGroupRule[] defaultPhraseGroupRules() {
        return new PhraseGroupRule[]{
                new PhraseGroupRule(new String[]{"温馨提醒", "大陆网络", "权限"},
                        "疑似仿冒应用诱导开启无障碍权限（温馨提醒/大陆网络/权限）", 4),
                new PhraseGroupRule(new String[]{"温馨提醒", "受限制", "开启"},
                        "疑似仿冒应用诱导开启无障碍权限（温馨提醒/受限制/开启）", 4),
                new PhraseGroupRule(new String[]{"使用步骤", "已下载服务", "开始使用"},
                        "疑似仿冒应用诱导开启无障碍权限（使用步骤/已下载服务/开始使用）", 4),
                new PhraseGroupRule(new String[]{"使用步骤", "打开已下载", "开始使用"},
                        "疑似仿冒应用诱导开启无障碍权限（使用步骤/打开已下载/开始使用）", 4),
                new PhraseGroupRule(new String[]{"已下载服务", "开始使用", "加载"},
                        "疑似仿冒应用诱导开启无障碍权限（已下载服务/开始使用/加载）", 4),
                new PhraseGroupRule(new String[]{"打开已下载", "开始使用", "加载"},
                        "疑似仿冒应用诱导开启无障碍权限（打开已下载/开始使用/加载）", 4)
        };
    }

    /** 跨字符串组合话术规则：组内所有词都出现即命中，不要求连续或相邻 */
    private static class PhraseGroupRule {
        String[] keywords;
        String description;
        int riskLevel;

        PhraseGroupRule(String[] keywords, String description, int riskLevel) {
            this.keywords = keywords;
            this.description = description;
            this.riskLevel = riskLevel;
        }
    }

    /** DEX 字符串特征规则：任一关键词命中即计入特征，支持风险等级 */
    private static class StringFeatureRule {
        String[] keywords;
        String description;
        int riskLevel;

        StringFeatureRule(String[] keywords, String description) {
            this(keywords, description, 0);
        }

        StringFeatureRule(String[] keywords, String description, int riskLevel) {
            this.keywords = keywords;
            this.description = description;
            this.riskLevel = riskLevel;
        }
    }

    // 方法级确认敏感 API 规则（默认内置；可被 scan_rules.json 的 confirmedApiRules 覆盖）
    private static ConfirmedApiRule[] CONFIRMED_API_RULES = defaultConfirmedApiRules();

    /** 默认内置方法级确认 API 规则 */
    private static ConfirmedApiRule[] defaultConfirmedApiRules() {
        return new ConfirmedApiRule[]{
                new ConfirmedApiRule("Landroid/media/projection/MediaProjectionManager;",
                        "createScreenCaptureIntent", "屏幕录制: MediaProjectionManager.createScreenCaptureIntent()"),
                new ConfirmedApiRule("Landroid/provider/Settings$Secure;",
                        "putString", "系统设置写入: Settings.Secure.putString()"),
                new ConfirmedApiRule("Ljava/lang/Runtime;",
                        "exec", "命令执行: Runtime.exec()"),
                new ConfirmedApiRule("Ljava/lang/ProcessBuilder;",
                        "start", "命令执行: ProcessBuilder.start()")
        };
    }

    /** 方法级确认敏感 API 规则：类名 + 方法名同时匹配即确认 */
    private static class ConfirmedApiRule {
        String className;
        String methodName;
        String description;

        ConfirmedApiRule(String className, String methodName, String description) {
            this.className = className;
            this.methodName = methodName;
            this.description = description;
        }
    }

    /**
     * 名称规范化：去除所有空白（含全角空格）与零宽/不可见字符。
     * 用于防仿冒名通过加空格、零宽字符绕过关键词匹配（如 "我 的 世 界"、"我的世\u200B界"）。
     */
    private static String normalizeName(String name) {
        if (name == null) return "";
        String cleaned = name.replaceAll("\\s+", "");
        cleaned = cleaned.replace("\u200B", "").replace("\u200C", "").replace("\u200D", "")
                .replace("\u200E", "").replace("\u200F", "").replace("\uFEFF", "")
                .replace("\u2060", "").replace("\u00A0", "").replace("\u3000", "");
        return cleaned;
    }

    /**
     * 名称与包名校验：
     * 应用名包含规则关键词时，仅当包名是官方/渠道版才放行做常规扫描（不代表白名单）；
     * 否则判定为最高风险（疑似特洛伊木马）。仅提示，不触发自动卸载。
     */
    private static void checkNamePackageRisk(ScanResult result, String appName, String packageName) {
        if (result == null || appName == null || packageName == null) return;
        for (NamePackageRule rule : NAME_PACKAGE_RULES) {
            if (rule == null || rule.nameKeyword == null || rule.nameKeyword.isEmpty()) continue;
            // 规则里写的是正常应用名；这里把实际安装的应用名清理成正常名（去空格/零宽字符）后再匹配
            String normApp = normalizeName(appName);
            if (!normApp.contains(rule.nameKeyword)) continue;
            if (rule.officialPackages != null && rule.officialPackages.contains(packageName)) return;
            result.maxRiskLevel = RISK_EXTREME;
            // 提示文案固定使用 Java 内常量
            result.specialWarning = TROJAN_WARNING;
            result.scanLogs.add("名称/包名校验: 应用名「" + appName + "」含'" + rule.nameKeyword
                    + "'，但包名 " + packageName + " 不在官方/渠道列表中，判定最高风险");
            return;
        }
    }

    /** 由规则 JSON 加载白名单（替换默认） */
    public static void setWhitelist(String[][] list) {
        if (list != null && list.length > 0) {
            WHITELIST = list;
        }
    }

    /** 点号类名转 DEX 描述符格式：com.a.b.C -> Lcom/a/b/C; */
    private static String toDescriptor(String dotName) {
        if (dotName == null) return null;
        String name = dotName.trim();
        if (name.isEmpty()) return null;
        if (name.startsWith("L") && name.endsWith(";")) return name; // 已是描述符
        return "L" + name.replace('.', '/') + ";";
    }

    /**
     * 类名匹配。
     *
     * 关键修正：DEX 里的类以描述符形式存放（Lcom/xunxing/toolbox/shell/MainActivity;），
     * 旧实现直接用斜杠形式在整串里做子串匹配时，左边界是 'L'（字母），
     * 被 containsWithBoundary 判成「不合法边界」而漏掉。
     *
     * 现在的做法：
     *   1. 把规则归一化成斜杠形式与点号形式（去掉可能的 L...; 外壳）；
     *   2. 先用原始 dexString 做一次匹配（覆盖字符串常量场景）；
     *   3. 若 dexString 是 L...; 描述符，剥离外壳后再做一次匹配。
     * 边界判断保留：规则前后不能紧邻字母/数字/_/$/.，
     * 避免 com.tencent.a 误中 com.tencent.abc；斜杠 / 视为合法边界。
     */
    private static boolean matchClassName(String dexString, String ruleClass) {
        if (dexString == null || ruleClass == null) return false;
        String rule = ruleClass.trim();
        if (rule.isEmpty()) return false;

        // 归一化规则：斜杠形式（去 L...; 外壳）与点号形式
        String ruleSlash = rule.replace('.', '/');
        if (ruleSlash.startsWith("L") && ruleSlash.endsWith(";") && ruleSlash.length() > 2) {
            ruleSlash = ruleSlash.substring(1, ruleSlash.length() - 1);
        }
        String ruleDot = ruleSlash.replace('/', '.');

        // 候选 1：原始字符串（字符串常量场景，如 "com.xunxing.xxx.MainActivity"）
        if (containsWithBoundary(dexString, rule)) return true;
        if (!ruleSlash.equals(rule) && containsWithBoundary(dexString, ruleSlash)) return true;
        if (!ruleDot.equals(rule) && containsWithBoundary(dexString, ruleDot)) return true;

        // 候选 2：描述符 L...;，剥离外壳后再匹配斜杠/点号形式
        if (dexString.length() >= 2 && dexString.charAt(0) == 'L' && dexString.endsWith(";")) {
            String inner = dexString.substring(1, dexString.length() - 1);
            if (containsWithBoundary(inner, ruleSlash)) return true;
            if (!ruleDot.equals(ruleSlash) && containsWithBoundary(inner, ruleDot)) return true;
        }
        return false;
    }

    /**
     * 子串匹配 + 边界校验：
     * 匹配 rule 在 text 中出现，且 rule 前后不是合法类名续字符（字母数字下划线/$/点）
     */
    private static boolean containsWithBoundary(String text, String rule) {
        int idx = 0;
        while ((idx = text.indexOf(rule, idx)) >= 0) {
            boolean leftOk = true;
            boolean rightOk = true;
            if (idx > 0) {
                char c = text.charAt(idx - 1);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') leftOk = false;
            }
            int end = idx + rule.length();
            if (end < text.length()) {
                char c = text.charAt(end);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') rightOk = false;
            }
            if (leftOk && rightOk) return true;
            idx += rule.length();
        }
        return false;
    }

    /**
     * 计算 APK 签名 MD5。
     *
     * Android 14（API 34）起对 targetSdk 34+ 的应用强制走 GET_SIGNING_CERTIFICATES，
     * GET_SIGNATURES 会返回 null，导致签名检测静默失效（白名单签名比对、
     * 风险签名比对全部跳过）。这里按版本分支：API 28+ 优先用 signingInfo，
     * 拿不到再回退旧 API。
     */
    public static String getSignatureMd5(PackageManager pm, String apkPath) {
        try {
            byte[] cert = null;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                try {
                    PackageInfo pi = pm.getPackageArchiveInfo(apkPath,
                            PackageManager.GET_SIGNING_CERTIFICATES);
                    if (pi != null && pi.signingInfo != null) {
                        android.content.pm.Signature[] sigs = pi.signingInfo.hasMultipleSigners()
                                ? pi.signingInfo.getApkContentsSigners()
                                : pi.signingInfo.getSigningCertificateHistory();
                        if (sigs != null && sigs.length > 0) cert = sigs[0].toByteArray();
                    }
                } catch (Throwable ignored) {}
            }
            if (cert == null) {
                PackageInfo pi = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES);
                if (pi != null && pi.signatures != null && pi.signatures.length > 0) {
                    cert = pi.signatures[0].toByteArray();
                }
            }
            if (cert == null) return "";
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(cert);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // 签名是否命中风险列表
    private static boolean isRiskSignature(String md5) {
        if (md5 == null || md5.isEmpty()) return false;
        for (String s : RISK_SIGNATURES) {
            if (s.equalsIgnoreCase(md5)) return true;
        }
        return false;
    }

    // 供安装广播使用：扫描已安装应用并返回完整结果
    public static ScanResult scanInstalledAppResult(PackageManager pm, String packageName) {
        ScanResult result = new ScanResult();
        try {
            Set<String> perms = new HashSet<String>();
            PackageInfo pi = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
            if (pi != null && pi.requestedPermissions != null) {
                for (String p : pi.requestedPermissions) {
                    perms.add(p.replace("android.permission.", ""));
                }
            }
            String apkPath = "";
            try {
                ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
                if (ai != null) apkPath = ai.sourceDir;
            } catch (Exception e) { }
            result = scanApk(pm, apkPath, perms);
            // 检查病毒包名
            if (pi != null && pi.packageName != null) {
                // 用 getVirusPackages 而非 BUILTIN：前者含用户在"主动防护"里加的自定义病毒包名，
                // 后者只有内置列表，会漏掉自定义项。
                for (String virusPkg : VirusPackages.getVirusPackages(App.getContext())) {
                    if (virusPkg.equals(pi.packageName)) {
                        result.malwareConfirmed = true;
                        result.riskSignatures.add("命中病毒包名: " + pi.packageName);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            result.scanLogs.add("扫描异常: " + e.getMessage());
        }
        return result;
    }

    // 供安装广播使用：扫描已安装应用并返回摘要
    public static String scanInstalledApp(PackageManager pm, String packageName) {
        return summarize(scanInstalledAppResult(pm, packageName));
    }

    // 生成扫描摘要
    public static String summarize(ScanResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.malwareConfirmed || (result.riskSignatures != null && !result.riskSignatures.isEmpty())) {
            sb.append("该软件已确定为恶意应用，以下是扫描结果，可能存在不准确\n\n");
        }
        if (result.specialWarning != null && !result.specialWarning.isEmpty()) {
            sb.append(result.specialWarning).append("\n\n");
        }
        if (result.riskSignatures != null && !result.riskSignatures.isEmpty()) {
            for (String sig : result.riskSignatures) {
                sb.append("· ").append(sig).append("\n");
            }
            sb.append("\n");
        }
        if (result.combinations != null && !result.combinations.isEmpty()) {
            sb.append("权限提醒:\n");
            for (String comb : result.combinations) {
                sb.append("· ").append(comb).append("\n");
            }
            sb.append("\n");
        }
        if (result.riskClasses != null && !result.riskClasses.isEmpty()) {
            sb.append("类名提醒:\n");
            for (String cls : result.riskClasses) {
                sb.append("· ").append(cls).append("\n");
            }
            sb.append("\n");
        }
        if (result.fileWarnings != null && !result.fileWarnings.isEmpty()) {
            sb.append("文件提醒:\n");
            for (String f : result.fileWarnings) {
                sb.append("· ").append(f).append("\n");
            }
        }
        if (sb.toString().trim().isEmpty()) {
            sb.append("未发现可疑行为");
        }
        return sb.toString();
    }

    private String buildLogs(ScanResult scan) {
        if (scan == null || scan.scanLogs == null || scan.scanLogs.isEmpty()) return "无日志";
        StringBuilder sb = new StringBuilder();
        for (String log : scan.scanLogs) {
            sb.append(log).append("\n");
        }
        return sb.toString();
    }

    private void showCopyableDetailDialog(String title, final String content, final String logs) {
        final android.widget.EditText textView = new android.widget.EditText(this);
        textView.setText(content);
        textView.setFocusable(false);
        textView.setInputType(0);
        textView.setSingleLine(false);
        int padding = (int) (getResources().getDisplayMetrics().density * 16);
        textView.setPadding(padding, padding, padding, padding);
        textView.setTextColor(0xFF333333);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(textView)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String contentCopy = content;
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("扫描详情", contentCopy));
                        Toast.makeText(SecurityScanActivity.this, R.string.copied_to_clip, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .setNeutralButton(R.string.logs_btn, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        android.widget.EditText tv = new android.widget.EditText(SecurityScanActivity.this);
                        tv.setText(logs != null && !logs.isEmpty() ? logs : "无日志");
                        tv.setFocusable(false);
                        tv.setInputType(0);
                        tv.setSingleLine(false);
                        int pad = (int) (getResources().getDisplayMetrics().density * 16);
                        tv.setPadding(pad, pad, pad, pad);
                        tv.setTextColor(0xFF333333);
                        new AlertDialog.Builder(SecurityScanActivity.this)
                                .setTitle(R.string.scan_log_title)
                                .setView(tv)
                                .setPositiveButton("复制日志", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog2, int which2) {
                                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                                getSystemService(CLIPBOARD_SERVICE);
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.scan_log_title), logs));
                                        Toast.makeText(SecurityScanActivity.this, R.string.log_copied, Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                })
                .show();
    }

    // 异步加载应用列表
    private static class LoadAppsTask extends AsyncTask<Void, String, List<AppInfo>> {
        private WeakReference<SecurityScanActivity> activityRef;

        LoadAppsTask(SecurityScanActivity activity) {
            activityRef = new WeakReference<SecurityScanActivity>(activity);
        }

        @Override
        protected void onPreExecute() {
            SecurityScanActivity activity = activityRef.get();
            if (activity != null) {
                activity.statusText.setText(R.string.loading_apps);
            }
        }

        @Override
        protected List<AppInfo> doInBackground(Void... voids) {
            SecurityScanActivity activity = activityRef.get();
            if (activity == null) return new ArrayList<AppInfo>();
            List<ApplicationInfo> apps = activity.pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppInfo> tempList = new ArrayList<AppInfo>();
            for (ApplicationInfo info : apps) {
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    String name = activity.pm.getApplicationLabel(info).toString();
                    String pkg = info.packageName;
                    String sourceDir = info.sourceDir;
                    tempList.add(new AppInfo(name, pkg, sourceDir));
                }
            }
            return tempList;
        }

        @Override
        protected void onPostExecute(List<AppInfo> tempList) {
            SecurityScanActivity activity = activityRef.get();
            if (activity != null) {
                activity.appList.clear();
                activity.appList.addAll(tempList);
                activity.statusText.setText(activity.getString(R.string.loaded_apps, activity.appList.size()));
                activity.adapter.notifyDataSetChanged();
            }
        }
    }

    // 扫描单个 APK，declaredPermissions 为实际声明的权限
    public static ScanResult scanApk(PackageManager pm, String apkPath, Set<String> declaredPermissions) {
        ScanResult result = new ScanResult();
        result.scanLogs.add("开始扫描: " + apkPath);
        if (declaredPermissions != null) {
            result.permissions.addAll(declaredPermissions);
        }
        result.scanLogs.add("实际声明权限: " + result.permissions.size() + "个");
        // 规则自检：若这里显示 0，说明规则未加载，所有规则类检测都会失效
        result.scanLogs.add("已加载规则: 类名" + CLASS_RULES.length
                + " 权限组合" + PERMISSION_RULES.length
                + " 文件" + FILE_RULES.length
                + " 字符串特征" + STRING_FEATURE_RULES.length
                + " 话术" + PHRASE_GROUP_RULES.length
                + " 方法级API" + CONFIRMED_API_RULES.length);
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) {
            result.scanLogs.add("文件不存在: " + apkPath);
            return result;
        }

        // 先取包名与应用名，供白名单与后续规则使用
        String pkgName = "";
        String appName = "";
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(apkPath, 0);
            if (pi != null) {
                pkgName = pi.packageName != null ? pi.packageName : "";
                appName = getLabelFromArchive(pm, pi, apkPath);
            }
        } catch (Exception e) {}

        // 用户白名单：只看包名，命中即整体跳过扫描。
        // 必须放在签名检测之前——否则风险签名会先写进 riskSignatures，
        // 调用方 computeRiskLevel 见到 riskSignatures 非空直接判极高风险，
        // 白名单等于失效。
        if (!pkgName.isEmpty()) {
            try {
                if (WhitelistPackages.isWhitelisted(App.getContext(), pkgName)) {
                    result.scanLogs.add("命中用户白名单，跳过扫描: " + pkgName);
                    return result;
                }
            } catch (Throwable ignored) {}
        }

        // 签名 MD5 检测
        String sigMd5 = "";
        try {
            sigMd5 = getSignatureMd5(pm, apkPath);
            result.signatureMd5 = sigMd5;
            result.scanLogs.add("签名MD5: " + sigMd5);
            if (isRiskSignature(sigMd5)) {
                result.riskSignatures.add("应用疑似被恶意工具修改或签名被泛滥使用，风险无法评估");
                result.maxRiskLevel = Math.max(result.maxRiskLevel, RISK_EXTREME);
            }
        } catch (Exception e) {
            result.scanLogs.add("签名检测失败: " + e.getMessage());
        }

        if (!pkgName.isEmpty() && !sigMd5.isEmpty()) {
            for (String[] entry : WHITELIST) {
                if (entry.length >= 2 && pkgName.equals(entry[0])) {
                    if (sigMd5.equalsIgnoreCase(entry[1])) {
                        result.scanLogs.add("命中白名单，跳过扫描");
                        return result;
                    } else {
                        result.riskSignatures.add("白名单签名异常: " + pkgName + " 签名不匹配，疑似被修改");
                        result.scanLogs.add("白名单签名异常: 包名在名单中但签名不一致");
                    }
                    break;
                }
            }
        }

        // 名称与包名校验：应用名含"我的世界"但包名非网易官方/渠道版 → 最高风险（疑似特洛伊木马）
        checkNamePackageRisk(result, appName, pkgName);

        try {
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apkFile);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                // 文件检测
                for (FileRule rule : FILE_RULES) {
                    if (entryName.contains(rule.fileName)) {
                        if (rule.needContentCheck) {
                            String content = readEntryContent(zipFile, entry);
                            if (content != null && content.contains(rule.contentMatch)) {
                                if (!result.fileWarnings.contains(rule.warning)) {
                                    result.fileWarnings.add(rule.warning);
                                }
                                result.detailedInfo.add("文件检测: " + entryName + " 内容命中 -> " + rule.warning);
                            }
                        } else {
                            if (!result.fileWarnings.contains(rule.warning)) {
                                result.fileWarnings.add(rule.warning);
                            }
                            result.detailedInfo.add("文件检测: " + entryName + " 文件名命中 -> " + rule.warning);
                        }
                    }
                }
                // DEX 解析
                if (entryName.startsWith("classes") && entryName.endsWith(".dex")) {
                    result.scanLogs.add("解析DEX: " + entryName);
                    long dexStart = System.currentTimeMillis();
                    java.io.InputStream is = zipFile.getInputStream(entry);
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                    is.close();
                    byte[] dexBytes = baos.toByteArray();
                    if (dexBytes.length == 0) continue;
                    try {
                        DexBackedDexFile dexFile = new DexBackedDexFile(Opcodes.getDefault(), dexBytes);
                        // 第一遍：字符串池。用于字符串常量类检测（如反射引用的类名）。
                        for (String s : dexFile.getStringSection()) {
                            // 敏感字符串特征匹配（规则由 scan_rules.json 的 stringFeatures 配置）
                            for (StringFeatureRule sfr : STRING_FEATURE_RULES) {
                                if (sfr == null || sfr.keywords == null) continue;
                                boolean hit = false;
                                for (String kw : sfr.keywords) {
                                    if (kw != null && s.contains(kw)) { hit = true; break; }
                                }
                                if (hit) {
                                    addUniqueCombination(result, sfr.description);
                                    result.detailedInfo.add("DEX: " + entryName + ", 命中特征: " + s);
                                }
                            }
                            // 类名匹配（字符串池中可能出现 com.a.b.C 形式的常量）
                            for (RiskClassRule rule : CLASS_RULES) {
                                if (matchClassName(s, rule.className)) {
                                    if (!result.riskClasses.contains(rule.description)) {
                                        result.riskClasses.add(rule.description);
                                    }
                                    result.maxRiskLevel = Math.max(result.maxRiskLevel, rule.riskLevel);
                                    result.detailedInfo.add("DEX: " + entryName + ", 类名匹配(字符串池): " + rule.className + " -> " + rule.description);
                                }
                            }
                        }

                        // 第二遍：类定义表。这是权威来源。
                        // DEX 中每个类以描述符 Lcom/x/y/Z; 形式存放在 class_defs，
                        // 不依赖字符串池是否被裁剪/加固。只遍历类定义表即可覆盖
                        // 所有实际存在于 APK 中的类，避免仅靠字符串池造成的漏报。
                        try {
                            for (org.jf.dexlib2.iface.ClassDef classDef : dexFile.getClasses()) {
                                String type = classDef.getType();
                                if (type == null || type.isEmpty()) continue;
                                for (RiskClassRule rule : CLASS_RULES) {
                                    if (matchClassName(type, rule.className)) {
                                        if (!result.riskClasses.contains(rule.description)) {
                                            result.riskClasses.add(rule.description);
                                        }
                                        result.maxRiskLevel = Math.max(result.maxRiskLevel, rule.riskLevel);
                                        result.detailedInfo.add("DEX: " + entryName + ", 类名匹配(类定义表): " + rule.className + " -> " + rule.description);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                        // 跨字符串组合话术：组内所有词在整池字符串中出现即命中
                        scanPhraseGroups(dexFile, result, entryName);
                        // 轻量级方法调用级检查：确认关键敏感API是否真实被调用（降低关键词误报）
                        scanConfirmedCalls(dexFile, result, entryName);
                    } catch (Exception e) {
                        result.errors.add("解析DEX失败: " + entryName + " - " + e.getMessage());
                    }
                }
            }
            zipFile.close();
        } catch (Exception e) {
            result.errors.add("读取APK失败: " + e.getMessage());
        }

        for (PermissionRule rule : PERMISSION_RULES) {
            if (rule.matches(result.permissions)) {
                result.combinations.add(rule.description);
                if (rule.riskLevel > result.maxRiskLevel) result.maxRiskLevel = rule.riskLevel;
                // 记录详细信息：命中的权限组合
                StringBuilder sb = new StringBuilder();
                sb.append("权限组合命中: ").append(rule.description);
                sb.append(" [").append(String.join(", ", rule.requiredPermissions)).append("]");
                result.detailedInfo.add(sb.toString());
            }
        }

        result.scanLogs.add("扫描完成: 权限" + result.permissions.size() + "个, 组合" + result.combinations.size() + "个, 类名" + result.riskClasses.size() + "个, 文件" + result.fileWarnings.size() + "个");
        return result;
    }

    /**
     * 跨字符串组合话术检测。
     *
     * 把整个 DEX 字符串池归一化（去空白与零宽字符）后拼成一整段文本，
     * 再对每条分组规则逐词判断：组内所有词都出现即命中。
     * 不要求连续、不要求顺序、不要求出现在同一条字符串里——
     * 这正是为了对抗木马把话术拆成多条字符串常量、或在同一条里插换行的绕过手法。
     * 应用名（如"我的世界"）是变量，不写进规则；靠话术框架词识别。
     */
    private static void scanPhraseGroups(DexBackedDexFile dexFile, ScanResult result, String dexName) {
        if (dexFile == null || result == null) return;
        if (PHRASE_GROUP_RULES == null || PHRASE_GROUP_RULES.length == 0) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (String s : dexFile.getStringSection()) {
                if (s == null || s.isEmpty()) continue;
                sb.append(s).append('\n');
            }
            if (sb.length() == 0) return;
            String pool = normalizeForPhrase(sb.toString());
            if (pool.isEmpty()) return;
            for (PhraseGroupRule rule : PHRASE_GROUP_RULES) {
                if (rule == null || rule.keywords == null || rule.keywords.length == 0) continue;
                boolean all = true;
                for (String kw : rule.keywords) {
                    if (kw == null || !pool.contains(kw)) { all = false; break; }
                }
                if (!all) continue;
                if (!result.riskClasses.contains(rule.description)) {
                    result.riskClasses.add(rule.description);
                }
                result.maxRiskLevel = Math.max(result.maxRiskLevel, rule.riskLevel);
                result.detailedInfo.add("DEX: " + dexName + ", 跨字符串话术命中: " + rule.description);
            }
        } catch (Exception ignored) {}
    }

    /**
     * 话术匹配前的归一化：去掉所有空白（含全角空格）与常见零宽 / 不可见字符。
     * 木马常在关键词之间插换行或零宽字符让字符串匹配失败，这里统一清掉。
     */
    private static String normalizeForPhrase(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", "");
        t = t.replace("\u200B", "").replace("\u200C", "").replace("\u200D", "")
                .replace("\u200E", "").replace("\u200F", "").replace("\uFEFF", "")
                .replace("\u2060", "").replace("\u00A0", "").replace("\u3000", "");
        return t;
    }

    /**
     * 轻量级方法调用级检查：
     * 遍历 DEX 方法指令，确认关键敏感 API 是否真实被调用（而非仅字符串出现）。
     * 只做单方法指令级确认，不做跨方法调用图分析，成本远低于完整行为链分析。
     */
    private static void scanConfirmedCalls(DexBackedDexFile dexFile, ScanResult result, String dexName) {
        if (dexFile == null || result == null) return;
        try {
            for (org.jf.dexlib2.iface.ClassDef classDef : dexFile.getClasses()) {
                for (org.jf.dexlib2.iface.Method method : classDef.getMethods()) {
                    org.jf.dexlib2.iface.MethodImplementation impl = method.getImplementation();
                    if (impl == null) continue;
                    for (org.jf.dexlib2.iface.instruction.Instruction ins : impl.getInstructions()) {
                        // 引用型指令（invoke/field/type 等）都实现 ReferenceInstruction，取引用后过滤出方法调用
                        if (!(ins instanceof org.jf.dexlib2.iface.instruction.ReferenceInstruction)) continue;
                        org.jf.dexlib2.iface.reference.Reference ref =
                                ((org.jf.dexlib2.iface.instruction.ReferenceInstruction) ins).getReference();
                        if (!(ref instanceof org.jf.dexlib2.iface.reference.MethodReference)) continue;
                        org.jf.dexlib2.iface.reference.MethodReference mr =
                                (org.jf.dexlib2.iface.reference.MethodReference) ref;
                        String cls = mr.getDefiningClass();
                        String name = mr.getName();
                        if (cls == null || name == null) continue;
                        String call = null;
                        // 方法级确认规则由 scan_rules.json 的 confirmedApiRules 配置
                        for (ConfirmedApiRule ar : CONFIRMED_API_RULES) {
                            if (ar == null || ar.className == null || ar.methodName == null) continue;
                            if (ar.className.equals(cls) && ar.methodName.equals(name)) {
                                call = ar.description;
                                break;
                            }
                        }
                        if (call != null) {
                            String item = call + " [" + dexName + " " + cls + "->" + name + "]";
                            if (!result.confirmedCalls.contains(item)) {
                                result.confirmedCalls.add(item);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static String readEntryContent(java.util.zip.ZipFile zipFile, java.util.zip.ZipEntry entry) {
        try {
            InputStream is = zipFile.getInputStream(entry);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static class ScanResult {
        public boolean malwareConfirmed = false;
        public int maxRiskLevel = 0; // 扫描中记录的最高风险等级：0=安全, 1=可能安全, 2=有风险, 3=高风险, 4=极高风险
        public String specialWarning = ""; // 名称/包名校验等专属高危提示文案
        public List<String> confirmedCalls = new ArrayList<String>(); // 方法调用级确认的敏感API
        String signatureMd5 = "";
        List<String> riskSignatures = new ArrayList<String>();
        List<String> scanLogs = new ArrayList<String>();
        Set<String> permissions = new HashSet<String>();
        List<String> combinations = new ArrayList<String>();
        List<String> riskClasses = new ArrayList<String>();
        List<String> fileWarnings = new ArrayList<String>();
        List<String> errors = new ArrayList<String>();
        List<String> detailedInfo = new ArrayList<String>(); // 详细信息

        /**
         * 是否“未扫描/无任何发现”。
         *
         * 必须把所有可能产生结论的字段都算进来，否则仅命中风险签名、
         * 仅命中方法级敏感 API、仅命中名称/包名仿冒（specialWarning）
         * 或已确认恶意（malwareConfirmed）时会被判成“未扫描”，
         * 列表点击直接弹“是否立即扫描”而不是展示结果。
         */
        boolean isEmpty() {
            return !malwareConfirmed
                    && combinations.isEmpty()
                    && riskClasses.isEmpty()
                    && fileWarnings.isEmpty()
                    && errors.isEmpty()
                    && riskSignatures.isEmpty()
                    && confirmedCalls.isEmpty()
                    && (specialWarning == null || specialWarning.isEmpty());
        }

        String toDetail() {
            StringBuilder sb = new StringBuilder();
            if (!signatureMd5.isEmpty()) {
                sb.append("签名MD5: ").append(signatureMd5).append("\n");
            }
            if (specialWarning != null && !specialWarning.isEmpty()) {
                sb.append("高危提示:\n  · ").append(specialWarning).append("\n\n");
            }
            if (!riskSignatures.isEmpty()) {
                sb.append("风险签名:\n");
                for (String sig : riskSignatures) {
                    sb.append("  · ").append(sig).append("\n");
                }
                sb.append("\n");
            }
            sb.append("声明的权限:\n");
            if (permissions.isEmpty()) {
                sb.append("  (无)\n");
            } else {
                for (String p : permissions) {
                    sb.append("  · ").append(p).append("\n");
                }
            }
            sb.append("\n");
            if (!combinations.isEmpty()) {
                sb.append("匹配的权限组合:\n");
                for (String c : combinations) {
                    sb.append("  · ").append(c).append("\n");
                }
                sb.append("\n");
            }
            if (!riskClasses.isEmpty()) {
                sb.append("匹配的风险类名:\n");
                for (String c : riskClasses) {
                    sb.append("  · ").append(c).append("\n");
                }
                sb.append("\n");
            }
            if (!fileWarnings.isEmpty()) {
                sb.append("文件检测:\n");
                for (String f : fileWarnings) {
                    sb.append("  · ").append(f).append("\n");
                }
                sb.append("\n");
            }
            if (!confirmedCalls.isEmpty()) {
                sb.append("已确认敏感API调用（方法级）:\n");
                for (String c : confirmedCalls) {
                    sb.append("  · ").append(c).append("\n");
                }
                sb.append("\n");
            }
            if (!errors.isEmpty()) {
                sb.append("解析错误:\n");
                for (String e : errors) {
                    sb.append("  · ").append(e).append("\n");
                }
            }
            if (!detailedInfo.isEmpty()) {
                sb.append("\n详细命中信息:\n");
                for (String info : detailedInfo) {
                    sb.append("  · ").append(info).append("\n");
                }
            }
            return sb.toString();
        }
    }

    private static class PermissionRule {
        int riskLevel;
        String description;
        String[] requiredPermissions;

        PermissionRule(int riskLevel, String description, String permList) {
            this.riskLevel = riskLevel;
            this.description = description;
            this.requiredPermissions = permList.split(",");
        }

        boolean matches(Set<String> permissions) {
            for (String rp : requiredPermissions) {
                if (!permissions.contains(rp.trim())) {
                    return false;
                }
            }
            return true;
        }
    }

    private static class RiskClassRule {
        int riskLevel;
        String description;
        String className;

        RiskClassRule(int riskLevel, String description, String className) {
            this.riskLevel = riskLevel;
            this.description = description;
            this.className = className;
        }
    }

    private static class FileRule {
        String fileName;
        String contentMatch;
        String warning;
        boolean needContentCheck;

        FileRule(String fileName, String contentMatch, String warning, boolean needContentCheck) {
            this.fileName = fileName;
            this.contentMatch = contentMatch;
            this.warning = warning;
            this.needContentCheck = needContentCheck;
        }
    }

    private static class AppInfo {
        String appName;
        String packageName;
        String sourceDir;
        int riskLevel = 0;
        ScanResult scanResult = new ScanResult();

        AppInfo(String appName, String packageName, String sourceDir) {
            this.appName = appName;
            this.packageName = packageName;
            this.sourceDir = sourceDir;
        }

        @Override
        public String toString() {
            return appName + (scanResult.isEmpty() ? "" : " [已扫描]");
        }
    }

    private static class ScanTask extends AsyncTask<Void, String, Map<String, String>> {
        private WeakReference<SecurityScanActivity> activityRef;

        ScanTask(SecurityScanActivity activity) {
            activityRef = new WeakReference<SecurityScanActivity>(activity);
        }

        @Override
        protected void onPreExecute() {
            SecurityScanActivity activity = activityRef.get();
            if (activity != null) {
                activity.progressBar.setVisibility(View.VISIBLE);
                activity.statusText.setText(R.string.preparing_scan);
                activity.scanButton.setEnabled(false);
            }
        }

        @Override
        protected Map<String, String> doInBackground(Void... voids) {
            SecurityScanActivity activity = activityRef.get();
            if (activity == null) return null;

            Map<String, String> results = new HashMap<String, String>();
            int total = activity.appList.size();
            for (int i = 0; i < total; i++) {
                AppInfo app = activity.appList.get(i);
                publishProgress((i + 1) + "/" + total, app.appName, app.packageName);
                long start = System.currentTimeMillis();
                Set<String> perms = new HashSet<String>();
                try {
                    PackageInfo pi = activity.pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS);
                    if (pi != null && pi.requestedPermissions != null) {
                        for (String p : pi.requestedPermissions) {
                            perms.add(p.replace("android.permission.", ""));
                        }
                    }
                } catch (Exception e) {
                    perms.clear();
                }
                ScanResult scanResult = scanApk(activity.pm, app.sourceDir, perms);
                long elapsed = System.currentTimeMillis() - start;
                app.scanResult = scanResult;
                app.riskLevel = activity.computeRiskLevel(scanResult);
                scanResult.scanLogs.add(0, "应用: " + app.appName + " (" + app.packageName + ")");
                scanResult.scanLogs.add("风险等级: " + activity.riskText(app.riskLevel));
                scanResult.scanLogs.add("耗时: " + elapsed + "ms");
                // 检查是否在病毒包名列表中。
                // 用 getVirusPackages 含用户自定义项；BUILTIN 只有内置列表会漏。
                for (String virusPkg : VirusPackages.getVirusPackages(App.getContext())) {
                    if (virusPkg.equals(app.packageName)) {
                        scanResult.malwareConfirmed = true;
                        scanResult.riskSignatures.add("命中病毒包名: " + app.packageName);
                        scanResult.maxRiskLevel = Math.max(scanResult.maxRiskLevel, RISK_EXTREME);
                        break;
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (String c : scanResult.combinations) {
                    sb.append(c).append("; ");
                }
                results.put(app.packageName, sb.toString());
            }
            return results;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            SecurityScanActivity activity = activityRef.get();
            if (activity != null) {
                int percent = 0;
                try {
                    String[] parts = values[0].split("/");
                    int cur = Integer.parseInt(parts[0]);
                    int total = Integer.parseInt(parts[1]);
                    percent = cur * 100 / total;
                } catch (Exception e) {
                    // ignore
                }
                activity.statusText.setText(activity.getString(R.string.scanning_progress, percent, values[0], values[1]));
            }
        }

        @Override
        protected void onPostExecute(Map<String, String> results) {
            SecurityScanActivity activity = activityRef.get();
            if (activity != null) {
                activity.progressBar.setVisibility(View.GONE);
                activity.statusText.setText(R.string.scan_done_hint);
                activity.scanButton.setEnabled(true);
                activity.adapter.notifyDataSetChanged();
            }
        }
    }

    private static class SingleScanTask extends AsyncTask<Void, String, ScanResult> {
        private WeakReference<SecurityScanActivity> activityRef;
        private File apkFile;
        private String fileName;
        private String errorMsg;

        SingleScanTask(SecurityScanActivity activity, File apkFile) {
            activityRef = new WeakReference<SecurityScanActivity>(activity);
            this.apkFile = apkFile;
            this.fileName = apkFile.getName();
        }

        @Override
        protected void onPreExecute() {
            SecurityScanActivity activity = activityRef.get();
            if (activity != null) {
                activity.progressBar.setVisibility(View.VISIBLE);
                activity.statusText.setText(R.string.scanning_apk);
                activity.selectApkButton.setEnabled(false);
            }
        }

        @Override
        protected ScanResult doInBackground(Void... voids) {
            SecurityScanActivity activity = activityRef.get();
            if (activity == null) return new ScanResult();
            try {
                publishProgress("正在解析DEX...");
                // 获取真实权限
                Set<String> perms = new HashSet<String>();
                String pkgName = "";
                try {
                    PackageInfo pkgInfo = activity.pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_PERMISSIONS);
                    if (pkgInfo != null) {
                        pkgName = pkgInfo.packageName != null ? pkgInfo.packageName : "";
                        if (pkgInfo.requestedPermissions != null) {
                            for (String p : pkgInfo.requestedPermissions) {
                                perms.add(p.replace("android.permission.", ""));
                            }
                        }
                        // 用 getLabelFromArchive 取应用名：
                        // getPackageArchiveInfo 返回的 applicationInfo 缺 sourceDir，
                        // 直接调 getApplicationLabel 拿不到资源表，只会返回包名或抛异常。
                        String label = getLabelFromArchive(activity.pm, pkgInfo,
                                apkFile.getAbsolutePath());
                        if (!label.isEmpty()) fileName = label;
                    }
                } catch (Exception e) {
                    perms.clear();
                }
                ScanResult result = scanApk(activity.pm, apkFile.getAbsolutePath(), perms);
                // 检查病毒列表。用 getVirusPackages 含用户自定义项，BUILTIN 只有内置列表会漏。
                try {
                    if (!pkgName.isEmpty()) {
                        result.scanLogs.add("包名: " + pkgName);
                        for (String virusPkg : VirusPackages.getVirusPackages(App.getContext())) {
                            if (virusPkg.equals(pkgName)) {
                                result.malwareConfirmed = true;
                                result.riskSignatures.add("命中病毒包名: " + pkgName);
                                result.maxRiskLevel = Math.max(result.maxRiskLevel, RISK_EXTREME);
                                result.scanLogs.add("匹配病毒包名: " + pkgName);
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    result.scanLogs.add("检查病毒包名失败: " + e.getMessage());
                }
                return result;
            } catch (Exception e) {
                errorMsg = e.getMessage();
                return new ScanResult();
            } finally {
                if (apkFile != null && apkFile.exists()) {
                    apkFile.delete();
                }
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            SecurityScanActivity activity = activityRef.get();
            if (activity != null) {
                activity.statusText.setText(values[0]);
            }
        }

        @Override
        protected void onPostExecute(ScanResult result) {
            SecurityScanActivity activity = activityRef.get();
            if (activity != null) {
                activity.progressBar.setVisibility(View.GONE);
                activity.selectApkButton.setEnabled(true);
                if (errorMsg != null) {
                    activity.statusText.setText(activity.getString(R.string.scan_failed, errorMsg));
                    Toast.makeText(activity, activity.getString(R.string.scan_failed, errorMsg), Toast.LENGTH_LONG).show();
                } else {
                    activity.statusText.setText(R.string.scan_done);
                    activity.showSingleScanResultDialog(fileName, result);
                }
            }
        }
    }

    private static class SingleAppScanTask extends AsyncTask<Void, Void, ScanResult> {
        private WeakReference<SecurityScanActivity> activityRef;
        private AppInfo appInfo;

        SingleAppScanTask(SecurityScanActivity activity, AppInfo appInfo) {
            activityRef = new WeakReference<SecurityScanActivity>(activity);
            this.appInfo = appInfo;
        }

        @Override
        protected void onPreExecute() {
            SecurityScanActivity activity = activityRef.get();
            if (activity != null) {
                activity.statusText.setText(activity.getString(R.string.scanning_app, appInfo.appName));
            }
        }

        @Override
        protected ScanResult doInBackground(Void... voids) {
            SecurityScanActivity activity = activityRef.get();
            if (activity == null) return new ScanResult();
            try {
                Set<String> perms = new HashSet<String>();
                try {
                    PackageInfo pi = activity.pm.getPackageInfo(appInfo.packageName, PackageManager.GET_PERMISSIONS);
                    if (pi != null && pi.requestedPermissions != null) {
                        for (String p : pi.requestedPermissions) {
                            perms.add(p.replace("android.permission.", ""));
                        }
                    }
                } catch (Exception e) {}
                ScanResult result = scanApk(activity.pm, appInfo.sourceDir, perms);
                // 用 getVirusPackages 含用户自定义项；BUILTIN 只有内置列表会漏。
                try {
                    for (String virusPkg : VirusPackages.getVirusPackages(App.getContext())) {
                        if (virusPkg.equals(appInfo.packageName)) {
                            result.malwareConfirmed = true;
                            result.riskSignatures.add("命中病毒包名: " + appInfo.packageName);
                            result.maxRiskLevel = Math.max(result.maxRiskLevel, RISK_EXTREME);
                            result.scanLogs.add("匹配病毒包名: " + appInfo.packageName);
                            break;
                        }
                    }
                } catch (Exception e) {}
                return result;
            } catch (Exception e) {
                return new ScanResult();
            }
        }

        @Override
        protected void onPostExecute(ScanResult result) {
            SecurityScanActivity activity = activityRef.get();
            if (activity != null) {
                appInfo.scanResult = result;
                appInfo.riskLevel = activity.computeRiskLevel(result);
                activity.adapter.notifyDataSetChanged();
                activity.showScanResultDialog(appInfo);
            }
        }
    }

    private static class AppAdapter extends ArrayAdapter<AppInfo> {
        private LayoutInflater inflater;
        private SecurityScanActivity owner;

        AppAdapter(SecurityScanActivity context, List<AppInfo> objects) {
            super(context, android.R.layout.simple_list_item_2, objects);
            inflater = LayoutInflater.from(context);
            owner = context;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            AppInfo app = getItem(position);
            TextView text1 = (TextView) convertView.findViewById(android.R.id.text1);
            TextView text2 = (TextView) convertView.findViewById(android.R.id.text2);
            text1.setText(app.appName);
            StringBuilder details = new StringBuilder(app.packageName);
            if (!app.scanResult.isEmpty()) {
                details.append(" | ").append(owner.riskText(app.riskLevel));
                int total = app.scanResult.combinations.size() + app.scanResult.riskClasses.size() + app.scanResult.fileWarnings.size();
                details.append(" | ").append(total).append(" 项提醒");
            }
            text2.setText(details.toString());
            if (!app.scanResult.isEmpty()) {
                text1.setTextColor(owner.riskColor(app.riskLevel));
            } else {
                text1.setTextColor(0xFF333333);
            }
            return convertView;
        }
    }

    /**
     * 从三个独立来源加载规则，后加载的覆盖先加载的：
     *   1. 内置规则：assets/scan_rules.json（不可删除）
     *   2. 云端规则：filesDir/scan_rules_cloud.json（可删除）
     *   3. 导入规则：filesDir/scan_rules_imported.json（可删除）
     */
    /** 规则是否已加载。避免重复解析，也供 App 早期调用后跳过。 */
    private static volatile boolean sRulesLoaded = false;

    /**
     * 确保规则已加载。供 App.onCreate 早期调用。
     *
     * 为什么必须这样做：
     *   PERMISSION_RULES / CLASS_RULES / FILE_RULES / WHITELIST /
     *   NAME_PACKAGE_RULES / STRING_FEATURE_RULES / PHRASE_GROUP_RULES /
     *   CONFIRMED_API_RULES 这些数组原本只在 SecurityScanActivity.onCreate
     *   里填充。安装广播（InstallScanReceiver）可能在本进程首次启动时直接
     *   触发扫描，此时 Activity 从未创建，上述数组全是空数组，导致 JSON 里
     *   的权限组合、类名规则、文件规则、名称仿冒规则全部失效。
     *
     *   App.onCreate 里调用本方法后，任何扫描路径拿到的都是完整规则。
     */
    public static void ensureRulesLoaded(Context ctx) {
        if (sRulesLoaded) return;
        initRules(ctx);
    }

    /**
     * 加载三源规则。静态方法，接收 Context，可被 App 或 Activity 调用。
     */
    public static void initRules(Context ctx) {
        if (ctx == null) return;
        sRulesLoaded = true;
        // 先重置为默认值（避免上次残留）
        RISK_SIGNATURES = new String[]{
            "5b1d20e8804cea1b0ab21ded391d9e8e",
            "e89b158e4bcf988ebd09eb83f5378e87",
            "64843786c6ada15ca4254f4da77e4978",
            "9ae14f85672bfc35d63ac4dff3c263dc",
            "9f994020b7bf12a4f2046606ad5e2a78",
            "5c7d1be7d72940d5d8dcfe45bf09a4c2"
        };
        PERMISSION_RULES = new PermissionRule[0];
        CLASS_RULES = new RiskClassRule[0];
        FILE_RULES = new FileRule[0];
        WHITELIST = new String[0][0];
        NAME_PACKAGE_RULES = defaultNamePackageRules();
        STRING_FEATURE_RULES = defaultStringFeatureRules();
        PHRASE_GROUP_RULES = defaultPhraseGroupRules();
        CONFIRMED_API_RULES = defaultConfirmedApiRules();

        // 1. 加载内置规则（base）
        try {
            InputStream is = ctx.getAssets().open("scan_rules.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            is.close();
            parseRulesJson(sb.toString());
        } catch (Exception e) {
            // 内置规则加载失败，保留硬编码
        }

        // 2. 加载云端规则（覆盖内置）
        try {
            File cloudFile = new File(ctx.getFilesDir(), "scan_rules_cloud.json");
            if (cloudFile.exists()) {
                FileInputStream fis = new FileInputStream(cloudFile);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                fis.close();
                parseRulesJson(sb.toString());
            }
        } catch (Exception e) {
            // 云端规则加载失败，忽略
        }

        // 3. 加载导入规则（覆盖内置和云端）
        try {
            File importFile = new File(ctx.getFilesDir(), "scan_rules_imported.json");
            if (importFile.exists()) {
                FileInputStream fis = new FileInputStream(importFile);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                fis.close();
                parseRulesJson(sb.toString());
            }
        } catch (Exception e) {
            // 导入规则加载失败，忽略
        }
    }

    /** 解析规则 JSON 并覆盖当前静态数组 */
    private static void parseRulesJson(String jsonText) {
        try {
            JSONObject root = new JSONObject(jsonText);

            // 加载风险签名
            JSONArray sigs = root.optJSONArray("riskSignatures");
            if (sigs != null && sigs.length() > 0) {
                String[] loaded = new String[sigs.length()];
                for (int i = 0; i < sigs.length(); i++) {
                    loaded[i] = sigs.getString(i);
                }
                RISK_SIGNATURES = loaded;
            }

            // 加载权限规则
            JSONArray perms = root.optJSONArray("permissionRules");
            if (perms != null && perms.length() > 0) {
                List<PermissionRule> list = new ArrayList<>();
                for (int i = 0; i < perms.length(); i++) {
                    JSONObject obj = perms.getJSONObject(i);
                    int risk = obj.getInt("riskLevel");
                    String desc = obj.getString("description");
                    JSONArray pArr = obj.getJSONArray("permissions");
                    StringBuilder pStr = new StringBuilder();
                    for (int j = 0; j < pArr.length(); j++) {
                        if (j > 0) pStr.append(",");
                        pStr.append(pArr.getString(j));
                    }
                    list.add(new PermissionRule(risk, desc, pStr.toString()));
                }
                PERMISSION_RULES = list.toArray(new PermissionRule[0]);
            }

            // 加载类名规则
            JSONArray classes = root.optJSONArray("classRules");
            if (classes != null && classes.length() > 0) {
                List<RiskClassRule> list = new ArrayList<>();
                for (int i = 0; i < classes.length(); i++) {
                    JSONObject obj = classes.getJSONObject(i);
                    list.add(new RiskClassRule(
                        obj.getInt("riskLevel"),
                        obj.getString("description"),
                        obj.getString("className")
                    ));
                }
                CLASS_RULES = list.toArray(new RiskClassRule[0]);
            }

            // 加载文件规则
            JSONArray files = root.optJSONArray("fileRules");
            if (files != null && files.length() > 0) {
                List<FileRule> list = new ArrayList<>();
                for (int i = 0; i < files.length(); i++) {
                    JSONObject obj = files.getJSONObject(i);
                    String contentMatch = obj.optString("contentMatch", null);
                    if (contentMatch != null && contentMatch.isEmpty()) contentMatch = null;
                    list.add(new FileRule(
                        obj.getString("fileName"),
                        contentMatch,
                        obj.getString("warning"),
                        obj.getBoolean("needContentCheck")
                    ));
                }
                FILE_RULES = list.toArray(new FileRule[0]);
            }

            // 加载病毒包名列表
            JSONArray virusPkgs = root.optJSONArray("virusPackages");
            if (virusPkgs != null && virusPkgs.length() > 0) {
                String[] loaded = new String[virusPkgs.length()];
                for (int i = 0; i < virusPkgs.length(); i++) {
                    loaded[i] = virusPkgs.getString(i);
                }
                VirusPackages.setBuiltin(loaded);
            }

            // 加载敏感App包名列表
            JSONArray protPkgs = root.optJSONArray("protectedPackages");
            if (protPkgs != null && protPkgs.length() > 0) {
                String[] loaded = new String[protPkgs.length()];
                for (int i = 0; i < protPkgs.length(); i++) {
                    loaded[i] = protPkgs.getString(i);
                }
                ProtectedPackages.setBuiltin(loaded);
            }

            // 加载白名单（包名 + 签名MD5，两者完全匹配才跳过扫描）
            JSONArray wl = root.optJSONArray("whitelist");
            if (wl != null && wl.length() > 0) {
                List<String[]> list = new ArrayList<>();
                for (int i = 0; i < wl.length(); i++) {
                    JSONObject obj = wl.optJSONObject(i);
                    if (obj == null) continue;
                    String pkg = obj.optString("packageName", "");
                    String md5 = obj.optString("signatureMd5", "");
                    if (!pkg.isEmpty() && !md5.isEmpty()) {
                        list.add(new String[]{pkg, md5});
                    }
                }
                if (!list.isEmpty()) {
                    setWhitelist(list.toArray(new String[0][0]));
                }
            }

            // 加载名称/包名校验规则
            JSONArray nameRules = root.optJSONArray("namePackageRules");
            if (nameRules != null && nameRules.length() > 0) {
                List<NamePackageRule> list = new ArrayList<>();
                for (int i = 0; i < nameRules.length(); i++) {
                    JSONObject obj = nameRules.optJSONObject(i);
                    if (obj == null) continue;
                    String keyword = obj.optString("nameKeyword", "");
                    JSONArray pkgs = obj.optJSONArray("officialPackages");
                    if (keyword.isEmpty() || pkgs == null || pkgs.length() == 0) continue;
                    String[] arr = new String[pkgs.length()];
                    for (int j = 0; j < pkgs.length(); j++) arr[j] = pkgs.getString(j);
                    // 文案固定写死在 Java（TROJAN_WARNING），不随 JSON 变化
                    list.add(new NamePackageRule(keyword, arr, TROJAN_WARNING));
                }
                if (!list.isEmpty()) {
                    NAME_PACKAGE_RULES = list.toArray(new NamePackageRule[0]);
                }
            }

            // 加载 DEX 字符串特征规则
            JSONArray strRules = root.optJSONArray("stringFeatures");
            if (strRules != null && strRules.length() > 0) {
                List<StringFeatureRule> list = new ArrayList<>();
                for (int i = 0; i < strRules.length(); i++) {
                    JSONObject obj = strRules.optJSONObject(i);
                    if (obj == null) continue;
                    JSONArray kws = obj.optJSONArray("keywords");
                    String desc = obj.optString("description", "");
                    int risk = obj.optInt("riskLevel", 0);
                    if (kws == null || kws.length() == 0 || desc.isEmpty()) continue;
                    String[] arr = new String[kws.length()];
                    for (int j = 0; j < kws.length(); j++) arr[j] = kws.getString(j);
                    list.add(new StringFeatureRule(arr, desc, risk));
                }
                if (!list.isEmpty()) {
                    STRING_FEATURE_RULES = list.toArray(new StringFeatureRule[0]);
                }
            }

            // 加载跨字符串组合话术规则（可选；不配置则沿用 Java 内置的 6 组）
            JSONArray phraseRules = root.optJSONArray("phraseGroups");
            if (phraseRules != null && phraseRules.length() > 0) {
                List<PhraseGroupRule> list = new ArrayList<>();
                for (int i = 0; i < phraseRules.length(); i++) {
                    JSONObject obj = phraseRules.optJSONObject(i);
                    if (obj == null) continue;
                    JSONArray kws = obj.optJSONArray("keywords");
                    String desc = obj.optString("description", "");
                    int risk = obj.optInt("riskLevel", 4);
                    if (kws == null || kws.length() < 2 || desc.isEmpty()) continue;
                    String[] arr = new String[kws.length()];
                    for (int j = 0; j < kws.length(); j++) arr[j] = kws.getString(j);
                    list.add(new PhraseGroupRule(arr, desc, risk));
                }
                if (!list.isEmpty()) {
                    PHRASE_GROUP_RULES = list.toArray(new PhraseGroupRule[0]);
                }
            }

            // 加载方法级确认 API 规则
            JSONArray apiRules = root.optJSONArray("confirmedApiRules");
            if (apiRules != null && apiRules.length() > 0) {
                List<ConfirmedApiRule> list = new ArrayList<>();
                for (int i = 0; i < apiRules.length(); i++) {
                    JSONObject obj = apiRules.optJSONObject(i);
                    if (obj == null) continue;
                    String clazz = obj.optString("className", "");
                    String method = obj.optString("methodName", "");
                    String desc = obj.optString("description", "");
                    if (clazz.isEmpty() || method.isEmpty() || desc.isEmpty()) continue;
                    list.add(new ConfirmedApiRule(clazz, method, desc));
                }
                if (!list.isEmpty()) {
                    CONFIRMED_API_RULES = list.toArray(new ConfirmedApiRule[0]);
                }
            }
        } catch (Exception e) {
            // 单个规则源解析失败，不影响其他源
        }
    }
}
