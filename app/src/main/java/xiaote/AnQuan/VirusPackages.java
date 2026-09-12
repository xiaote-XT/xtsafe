package xiaote.AnQuan;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 疑似病毒/锁机应用包名管理
 * 硬编码包名 + 用户自定义包名（SharedPreferences）
 */
public class VirusPackages {

    // 病毒包名列表由动态加载，此处置空
    public static String[] BUILTIN = {};
    private static long lastLoadTime = 0;
    private static final long LOAD_INTERVAL_MS = 5000; // 5秒缓存，保证实时性

    public static void setBuiltin(String[] list) {
        if (list != null) {
            BUILTIN = list;
        }
    }

    public static final String PREFS_NAME = "dot_config";
    public static final String KEY_CUSTOM = "custom_virus_packages";
    public static final String KEY_MANAGED = "managed_packages";

    /** 获取全部病毒包名集合（规则文件 + 用户自定义） */
    public static Set<String> getVirusPackages(Context context) {
        // 缓存失效或从未加载时，从规则文件重新加载
        if (System.currentTimeMillis() - lastLoadTime > LOAD_INTERVAL_MS || BUILTIN.length == 0) {
            loadFromRules(context);
        }
        Set<String> set = new HashSet<String>();
        for (String p : BUILTIN) {
            if (p != null && !p.isEmpty()) set.add(p.trim());
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_CUSTOM, "");
            if (raw != null && !raw.isEmpty()) {
                String[] arr = raw.split("[,，\\n]");
                for (String s : arr) {
                    String p = s.trim();
                    if (!p.isEmpty()) set.add(p);
                }
            }
        } catch (Exception e) {}
        return set;
    }

    /** 从规则文件动态加载病毒包名列表（优先级：导入 > 云端 > 内置） */
    private static void loadFromRules(Context context) {
        // 逐个尝试，一个失败不影响下一个
        String json = tryRead(context, "scan_rules_imported.json");
        if (json == null) json = tryRead(context, "scan_rules_cloud.json");
        if (json == null) json = tryReadAssets(context, "scan_rules.json");
        if (json != null) {
            try {
                JSONObject root = new JSONObject(json);
                JSONArray arr = root.optJSONArray("virusPackages");
                if (arr != null && arr.length() > 0) {
                    String[] list = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) list[i] = arr.getString(i);
                    BUILTIN = list;
                    lastLoadTime = System.currentTimeMillis();
                }
            } catch (Exception ignored) {}
        }
    }

    /** 安全读取文件，失败返回null */
    private static String tryRead(Context context, String fileName) {
        try {
            File f = new File(context.getFilesDir(), fileName);
            if (f.exists()) return readFile(f);
        } catch (Exception ignored) {}
        return null;
    }

    /** 安全读取assets文件，失败返回null */
    private static String tryReadAssets(Context context, String fileName) {
        try {
            InputStream is = context.getAssets().open(fileName);
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            return sb.toString();
        } catch (Exception ignored) {}
        return null;
    }

    private static String readFile(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        BufferedReader r = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        return sb.toString();
    }

    /** 保存自定义病毒包名（逗号分隔） */
    public static void saveCustomVirusPackages(Context context, String raw) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_CUSTOM, raw == null ? "" : raw.trim()).apply();
    }

    // ==================== 管控列表 ====================
    // 管控与病毒不同：管控不禁止安装，只是单独展示管理

    /** 获取管控包名集合 */
    public static Set<String> getManagedPackages(Context context) {
        Set<String> set = new HashSet<String>();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_MANAGED, "");
            if (raw != null && !raw.isEmpty()) {
                String[] arr = raw.split("[,，\\n]");
                for (String s : arr) {
                    String p = s.trim();
                    if (!p.isEmpty()) set.add(p);
                }
            }
        } catch (Exception e) {}
        return set;
    }

    /** 判断是否管控 */
    public static boolean isManaged(Context context, String pkg) {
        return getManagedPackages(context).contains(pkg);
    }

    /** 添加为管控 */
    public static void addManagedPackage(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        String p = pkg.trim();
        Set<String> set = getManagedPackages(context);
        set.add(p);
        saveManagedPackages(context, set);
        // 被管控应用默认设为智能管理模式（锁屏关闭+亮屏询问恢复）
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt("acc_mode_" + p, AccessibilityManagerActivity.MODE_SMART).apply();
    }

    /** 移除管控 */
    public static void removeManagedPackage(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> set = getManagedPackages(context);
        set.remove(pkg.trim());
        saveManagedPackages(context, set);
    }

    private static void saveManagedPackages(Context context, Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String p : set) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_MANAGED, sb.toString()).apply();
    }

    /** 获取自定义病毒包名字符串 */
    public static String getCustomVirusPackages(Context context) {
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_CUSTOM, "");
        } catch (Exception e) {
            return "";
        }
    }
}
