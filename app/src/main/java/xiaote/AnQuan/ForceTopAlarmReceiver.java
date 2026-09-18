package xiaote.AnQuan;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * 强制置顶兜底闹钟：
 * 由系统 Alarm 定时调度，独立于服务进程存活。服务被系统冻结/杀死后，
 * 闹钟仍会拉起本接收器，检查无障碍服务是否在列表且运行，不在则写回，
 * 触发系统重启服务，从而让强制置顶循环自动恢复。
 *
 * 性能策略：
 *  - 60 秒一次，Doze 兼容（setAndAllowWhileIdle），不持锁、只做轻量读+判断
 *  - 仅当 force_top 开关开启时才检查并续订；关闭后闹钟自然停止，不耗电
 *  - 服务正常运行时不产生任何写操作
 */
public class ForceTopAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION = "xiaote.AnQuan.FORCE_TOP_ALARM";
    private static final long INTERVAL_MS = 60_000L; // 60 秒

    private static final String COMPONENT_STD = "xiaote.AnQuan/xiaote.AnQuan.XTSafeMainService";
    private static final String COMPONENT_SHORT = "xiaote.AnQuan/.XTSafeMainService";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            boolean forceTop = context.getSharedPreferences("dot_config", Context.MODE_PRIVATE)
                    .getBoolean("force_top", true);
            if (forceTop) {
                checkAndRecover(context);
                schedule(context); // 开启状态下续订下一个闹钟
            }
            // force_top 关闭：不再续订，闹钟自然消亡
        } catch (Exception ignored) {}
    }

    /** 检查无障碍服务是否在列表且真正运行，不在则写回触发系统重启服务 */
    private void checkAndRecover(Context context) {
        try {
            String list = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean inList = false;
            if (list != null) {
                for (String item : list.split(":")) {
                    if (item.equals(COMPONENT_STD) || item.equals(COMPONENT_SHORT)) { inList = true; break; }
                }
            }

            boolean running = false;
            try {
                AccessibilityManager am = (AccessibilityManager) context
                        .getSystemService(Context.ACCESSIBILITY_SERVICE);
                if (am != null) {
                    List<android.accessibilityservice.AccessibilityServiceInfo> enabled =
                            am.getEnabledAccessibilityServiceList(
                                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                    if (enabled != null) {
                        for (android.accessibilityservice.AccessibilityServiceInfo info : enabled) {
                            String id = info.getId();
                            if (COMPONENT_STD.equals(id) || COMPONENT_SHORT.equals(id)) { running = true; break; }
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 不在列表，或列表在但服务没在运行：用标准写法写回，触发系统重启服务
            if (!running) {
                String clean = removeSelf(list);
                String target = clean.isEmpty() ? COMPONENT_STD : clean + ":" + COMPONENT_STD;
                boolean sOk = ShellExecutor.execShizuku(new String[]{"settings", "put", "secure", "enabled_accessibility_services", target});
                boolean rOk = ShellExecutor.execRoot("settings put secure enabled_accessibility_services '" + target + "'");
                android.util.Log.d("AntiLock", "alarm: 服务未运行，写回拉活 inList=" + inList
                        + " shizuku=" + sOk + " root=" + rOk);
            }
        } catch (Exception ignored) {}
    }

    private static String removeSelf(String list) {
        if (list == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : list.split(":")) {
            if (p.equals(COMPONENT_STD) || p.equals(COMPONENT_SHORT)) continue;
            if (sb.length() > 0) sb.append(":");
            sb.append(p);
        }
        return sb.toString();
    }

    /** 调度下一次兜底闹钟（Doze 兼容，不触发强制置顶移除加回逻辑） */
    public static void schedule(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(context, ForceTopAlarmReceiver.class);
            i.setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long next = SystemClock.elapsedRealtime() + INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi);
            }
        } catch (Exception ignored) {}
    }
}