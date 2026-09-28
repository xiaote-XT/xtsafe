package xiaote.AnQuan.SmallTool.KillAd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import xiaote.AnQuan.R;

/**
 * 广告拦截 VPN 服务（DNS 层）。
 *
 * 工作方式：
 *   1. 建立 tun，声明虚拟 DNS 地址 DNS_PROXY 并只把该地址 /32 路由进隧道；
 *      其余流量不进 tun，不影响正常上网。
 *   2. 系统把 DNS 查询发到 DNS_PROXY，以 UDP 包形式出现在 tun 中；
 *   3. 命中拦截列表 → 本地构造应答（A 记录 0.0.0.0）；
 *   4. 未命中 → 异步转发给「建立 VPN 之前采集到的系统真实 DNS」，
 *      每个查询用独立 socket，响应校验事务 ID 后写回 tun。
 *
 * 为什么必须 addDnsServer：
 *   VPN 建立后本网络会成为系统默认网络，DNS 解析交给 VPN 网络。
 *   若 VPN 不声明 DNS 服务器，系统就没有可用 DNS，所有域名都解析失败
 *   （ERR_NAME_NOT_RESOLVED）。用户侧看到的「私有 DNS/网络 DNS」会显示
 *   为虚拟地址，但实际解析结果仍来自系统原本的真实 DNS。
 *
 * 为什么转发要异步且校验 ID：
 *   单个 socket 串行 send/receive 时，系统并发发出的多个查询，
 *   收到的响应可能属于另一个查询，包头被套错导致客户端丢弃、超时。
 *   每个查询独立 socket + 事务 ID 校验可彻底避免错配。
 *
 * 只处理 UDP/53，不实现 TCP/IP 栈。代价是只能拦走域名的广告，
 * 走 IP 直连或 DoH/DoT 的拦不到。
 *
 * 暂停时关闭 tun（不再拦截）但保留前台通知，用户可随时恢复。
 */
public class KillAdVpnService extends VpnService {

    public static final String ACTION_START = "xiaote.AnQuan.KillAd.START";
    public static final String ACTION_STOP = "xiaote.AnQuan.KillAd.STOP";
    public static final String ACTION_PAUSE = "xiaote.AnQuan.KillAd.PAUSE";
    public static final String ACTION_RESUME = "xiaote.AnQuan.KillAd.RESUME";

    public static final String KEY_BLOCKED = "killad_blocked_count";
    public static final String KEY_TOTAL = "killad_total_count";
    public static final String KEY_SAVED = "killad_saved_bytes";
    public static final String KEY_PAUSED = "killad_paused";

    /** tun 本机地址 */
    private static final String TUN_ADDR = "10.111.222.1";
    /** 虚拟 DNS 服务器地址，系统 DNS 请求会被路由到这里 */
    private static final String DNS_PROXY = "10.111.222.2";
    private static final int DNS_PORT = 53;

    private static final String CHANNEL_ID = "killad_vpn";
    private static final int NOTIFY_ID = 0x4B4B;
    private static final int MAX_PACKET = 32767;

    /** 每次拦截估算节省的流量（字节） */
    private static final long SAVED_PER_BLOCK = 5120L;

    /** 异步转发并发上限，超出直接丢弃（客户端会自行重试） */
    private static final int MAX_PENDING_FORWARDS = 64;
    /** 单次转发超时 */
    private static final int FORWARD_TIMEOUT_MS = 4000;

    private ParcelFileDescriptor tun;
    private Thread worker;
    private volatile boolean running = false;
    private volatile boolean paused = false;

    private volatile FileOutputStream tunOut;
    private final Object tunWriteLock = new Object();

    private volatile int blockedCount = 0;
    private volatile int totalCount = 0;
    private volatile long savedBytes = 0L;
    /** 本次会话已写入日志的命中域名条数，上限 500，避免日志爆炸 */
    private volatile int dnsLogCount = 0;

    /** 转发上游：建立 VPN 前采集到的系统真实 DNS（IPv4），跨恢复复用 */
    private volatile List<InetAddress> upstreamDnsList = new ArrayList<InetAddress>();
    /** 配置里指定的上游 DNS 解析结果（建立 VPN 前解析好，避免生效后自解析成环） */
    private volatile List<InetAddress> configUpstream = new ArrayList<InetAddress>();
    /** 上游轮询下标 */
    private final AtomicInteger upstreamIndex = new AtomicInteger(0);
    /** 正在转发中的查询数 */
    private final AtomicInteger pendingForwards = new AtomicInteger(0);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pauseVpn();
            return START_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            resumeVpn();
            return START_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        stopVpn();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    // ==================== 系统 DNS 采集 ====================

    /** 读取系统当前生效的 IPv4 DNS 服务器，读不到返回空列表 */
    private List<InetAddress> collectSystemDns() {
        List<InetAddress> list = new ArrayList<InetAddress>();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return list;
            if (Build.VERSION.SDK_INT >= 23) {
                Network net = cm.getActiveNetwork();
                if (net != null) {
                    LinkProperties lp = cm.getLinkProperties(net);
                    if (lp != null) {
                        for (InetAddress a : lp.getDnsServers()) {
                            if (a instanceof Inet4Address && !isVirtual(a)) list.add(a);
                        }
                    }
                }
            }
            if (list.isEmpty()) {
                for (Network net : cm.getAllNetworks()) {
                    LinkProperties lp = cm.getLinkProperties(net);
                    if (lp == null) continue;
                    for (InetAddress a : lp.getDnsServers()) {
                        if (a instanceof Inet4Address && !isVirtual(a) && !list.contains(a)) list.add(a);
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    /** 是否是本 VPN 自己的虚拟地址（恢复时避免把虚拟 DNS 当成上游） */
    private static boolean isVirtual(InetAddress a) {
        if (a == null) return false;
        String ip = a.getHostAddress();
        return DNS_PROXY.equals(ip) || TUN_ADDR.equals(ip);
    }

    /** 在建立 VPN 之前解析配置里的上游 DNS，避免生效后自解析成环 */
    private void resolveConfigUpstream() {
        List<InetAddress> list = new ArrayList<InetAddress>();
        try {
            InetAddress a = InetAddress.getByName(KillAdRules.getUpstream(this));
            if (a instanceof Inet4Address) list.add(a);
        } catch (Exception ignored) {}
        if (list.isEmpty()) {
            try {
                InetAddress a = InetAddress.getByName(KillAdRules.DEFAULT_UPSTREAM);
                if (a instanceof Inet4Address) list.add(a);
            } catch (Exception ignored) {}
        }
        configUpstream = list;
    }

    /** 转发目标列表：优先系统真实 DNS，其次配置上游 */
    private List<InetAddress> forwardTargets() {
        List<InetAddress> list = upstreamDnsList;
        if (list != null && !list.isEmpty()) return list;
        return configUpstream;
    }

    // ==================== VPN 建立 ====================

    private ParcelFileDescriptor buildTun() {
        // 必须在 establish 之前采集，否则拿到的是本 VPN 的虚拟 DNS
        List<InetAddress> fresh = collectSystemDns();
        if (!fresh.isEmpty()) upstreamDnsList = fresh;

        Builder b = new Builder();
        b.setSession(getString(R.string.app_name) + " - KillAd");
        b.addAddress(TUN_ADDR, 32);
        // 必须声明 DNS 服务器：VPN 成为默认网络后，系统 DNS 就靠这个地址
        b.addDnsServer(DNS_PROXY);
        b.addRoute(DNS_PROXY, 32);
        // 常见公共 DNS 也路由进 tun：部分应用会自己指定 DNS 服务器，
        // 不走系统配置，只有把这些地址也收进隧道才能拦到它们的查询。
        // 这些查询同样转发给建立 VPN 前采集到的系统真实 DNS，不会环回。
        String[] extraDns = {
                "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1",
                "9.9.9.9", "9.9.9.10", "208.67.222.222", "208.67.220.220",
                "114.114.114.114", "114.114.115.115",
                "119.29.29.29", "182.254.116.116",
                "180.76.76.76", "223.5.5.5", "223.6.6.6"
        };
        for (String ip : extraDns) {
            try { b.addRoute(ip, 32); } catch (Exception ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 29) {
            b.setMetered(false);
        }

        try {
            ParcelFileDescriptor fd = b.establish();
            if (fd != null) {
                KillAdLogger.log(this, "SVC", "VPN 建立成功，DNS 代理=" + DNS_PROXY
                        + "，上游=" + describeTargets(forwardTargets()));
            } else {
                KillAdLogger.log(this, "ERR", "VPN 建立失败：establish 返回 null");
            }
            return fd;
        } catch (Exception e) {
            KillAdLogger.log(this, "ERR", "VPN 建立异常: " + e);
            return null;
        }
    }

    private static String describeTargets(List<InetAddress> list) {
        if (list == null || list.isEmpty()) return "无（解析将失败）";
        StringBuilder sb = new StringBuilder();
        for (InetAddress a : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(a.getHostAddress());
        }
        return sb.toString();
    }

    // ==================== 启停 / 暂停 / 恢复 ====================

    private synchronized void startVpn() {
        if (running) {
            updateNotification();
            return;
        }
        // 先进入前台：startForegroundService 启动后必须在 5 秒内 startForeground
        startForegroundNotification();

        resolveConfigUpstream();
        tun = buildTun();
        if (tun == null) {
            running = false;
            KillAdLogger.log(this, "ERR", "启动失败：tun 建立失败，服务退出");
            persistStats();
            try { stopForeground(true); } catch (Exception ignored) {}
            try { stopSelf(); } catch (Exception ignored) {}
            return;
        }

        running = true;
        paused = false;
        SharedPreferences p = getSharedPreferences(KillAdRules.PREFS, MODE_PRIVATE);
        blockedCount = p.getInt(KEY_BLOCKED, 0);
        totalCount = p.getInt(KEY_TOTAL, 0);
        savedBytes = p.getLong(KEY_SAVED, 0L);
        persistStats();
        startForegroundNotification();
        KillAdLogger.log(this, "SVC", "服务启动，tun 就绪");

        startLoopThread();
    }

    private void startLoopThread() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "killad-vpn");
        worker.start();
    }

    private synchronized void pauseVpn() {
        if (!running) return;
        paused = true;
        KillAdLogger.log(this, "SVC", "服务已暂停，关闭 tun 停止拦截，通知保留");
        closeTun();
        persistStats();
        updateNotification();
    }

    private synchronized void resumeVpn() {
        if (!running) {
            startVpn();
            return;
        }
        if (!paused) {
            updateNotification();
            return;
        }
        // 恢复时系统 DNS 已是虚拟地址，不要重新采集覆盖上游
        tun = buildTun();
        if (tun == null) {
            paused = true;
            KillAdLogger.log(this, "ERR", "恢复失败：tun 重建失败，保持暂停状态");
            updateNotification();
            return;
        }
        paused = false;
        KillAdLogger.log(this, "SVC", "服务已恢复，tun 重建成功，继续拦截，上游="
                + describeTargets(forwardTargets()));
        persistStats();
        updateNotification();
        startLoopThread();
    }

    private synchronized void stopVpn() {
        running = false;
        paused = false;
        closeTun();
        worker = null;
        KillAdLogger.log(this, "SVC", "服务已停止，tun 关闭，通知撤下。累计拦截 "
                + blockedCount + " 次，估算节省 " + formatBytes(savedBytes));
        persistStats();
        try { stopForeground(true); } catch (Exception ignored) {}
        try { stopSelf(); } catch (Exception ignored) {}
    }

    private void closeTun() {
        synchronized (tunWriteLock) {
            tunOut = null;
        }
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) {}
            tun = null;
        }
    }

    public static boolean isRunning(Context ctx) {
        return ctx.getSharedPreferences(KillAdRules.PREFS, Context.MODE_PRIVATE)
                .getBoolean("killad_running", false);
    }

    public static boolean isPaused(Context ctx) {
        return ctx.getSharedPreferences(KillAdRules.PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PAUSED, false);
    }

    public static long getSavedBytes(Context ctx) {
        return ctx.getSharedPreferences(KillAdRules.PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_SAVED, 0L);
    }

    private void persistStats() {
        try {
            SharedPreferences p = getSharedPreferences(KillAdRules.PREFS, MODE_PRIVATE);
            p.edit()
                    .putInt(KEY_BLOCKED, blockedCount)
                    .putInt(KEY_TOTAL, totalCount)
                    .putLong(KEY_SAVED, savedBytes)
                    .putBoolean("killad_running", running)
                    .putBoolean(KEY_PAUSED, paused)
                    .apply();
        } catch (Exception ignored) {}
    }

    // ==================== 通知 ====================

    private void startForegroundNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.killad_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.enableVibration(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            startForeground(NOTIFY_ID, buildNotification());
        } catch (Exception ignored) {}
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(NOTIFY_ID, buildNotification());
        } catch (Exception ignored) {}
    }

    private Notification buildNotification() {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;

        Intent content = new Intent(this, KillAdActivity.class);
        content.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, content, piFlags);

        Intent toggle = new Intent(this, KillAdVpnService.class);
        toggle.setAction(paused ? ACTION_RESUME : ACTION_PAUSE);
        PendingIntent togglePi = PendingIntent.getService(this, 1, toggle, piFlags);
        String toggleLabel = paused
                ? getString(R.string.killad_notify_resume)
                : getString(R.string.killad_notify_pause);

        String title = paused
                ? getString(R.string.killad_notify_paused_title)
                : getString(R.string.killad_notify_title);
        String statsText = "拦截次数：" + formatCount(blockedCount)
                + "丨节省流量：" + formatBytes(savedBytes);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(title)
                .setContentText(statsText)
                .addAction(0, toggleLabel, togglePi)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(0)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1048576L) return String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0);
        if (bytes < 1073741824L) return String.format(java.util.Locale.US, "%.1fMB", bytes / 1048576.0);
        return String.format(java.util.Locale.US, "%.2fGB", bytes / 1073741824.0);
    }

    public static String formatCount(int count) {
        if (count < 10000) return String.valueOf(count);
        if (count < 100000000) {
            return String.format(java.util.Locale.US, "%.1f万", count / 10000.0);
        }
        return String.format(java.util.Locale.US, "%.2f亿", count / 100000000.0);
    }

    // ==================== 主循环 ====================

    private void loop() {
        FileInputStream in;
        FileOutputStream out;
        try {
            in = new FileInputStream(tun.getFileDescriptor());
            out = new FileOutputStream(tun.getFileDescriptor());
        } catch (Exception e) {
            running = false;
            persistStats();
            return;
        }
        synchronized (tunWriteLock) {
            tunOut = out;
        }

        byte[] buf = new byte[MAX_PACKET];
        byte[] outBuf = new byte[MAX_PACKET];
        byte[] dns = new byte[MAX_PACKET];

        while (running && !paused) {
            int len;
            try {
                len = in.read(buf);
            } catch (Exception e) {
                break;
            }
            if (len < 28) continue;

            // ---- IPv4 头 ----
            if (((buf[0] >> 4) & 0xF) != 4) continue;
            int ihl = (buf[0] & 0xF) * 4;
            if (ihl < 20 || len < ihl + 8) continue;
            if ((buf[9] & 0xFF) != 17) continue;   // 只处理 UDP

            int udpOff = ihl;
            int dstPort = ((buf[udpOff + 2] & 0xFF) << 8) | (buf[udpOff + 3] & 0xFF);
            if (dstPort != DNS_PORT) continue;

            int udpLen = ((buf[udpOff + 4] & 0xFF) << 8) | (buf[udpOff + 5] & 0xFF);
            if (udpLen < 12 || udpOff + udpLen > len) continue;

            int dnsLen = udpLen - 8;
            if (dnsLen > dns.length) continue;
            System.arraycopy(buf, udpOff + 8, dns, 0, dnsLen);

            totalCount++;

            DnsPacket q;
            try {
                q = DnsPacket.parseQuery(dns, dnsLen);
            } catch (Exception e) {
                q = null;
            }
            if (q == null) continue;

            // ---- 命中拦截：本地应答 ----
            if (KillAdRules.isBlocked(this, q.name)) {
                byte[] resp;
                try {
                    resp = DnsPacket.buildBlocked(q);
                } catch (Exception e) {
                    resp = null;
                }
                if (resp != null) {
                    int n = buildResponse(outBuf, buf, ihl, udpOff, resp);
                    if (n > 0) {
                        FileOutputStream o = tunOut;
                        if (o != null) {
                            try {
                                synchronized (tunWriteLock) { o.write(outBuf, 0, n); }
                            } catch (Exception e) { break; }
                            blockedCount++;
                            savedBytes += SAVED_PER_BLOCK;
                            if (dnsLogCount < 500) {
                                dnsLogCount++;
                                KillAdLogger.log(this, "DNS", "拦截 " + q.name);
                            }
                            if ((blockedCount & 0x0F) == 0) {
                                persistStats();
                                updateNotification();
                            }
                        }
                    }
                }
                continue;
            }

            // ---- 未命中：异步转发，每个查询独立 socket + 事务 ID 校验 ----
            // 只需要 IP 头 + UDP 头用于构造回包（buildResponse 读 in[12..19] 与 in[udpOff..udpOff+3]）
            int headerLen = udpOff + 8;
            if (headerLen > buf.length) continue;
            byte[] header = new byte[headerLen];
            System.arraycopy(buf, 0, header, 0, headerLen);
            byte[] dnsCopy = new byte[dnsLen];
            System.arraycopy(dns, 0, dnsCopy, 0, dnsLen);
            forwardAsync(header, ihl, udpOff, dnsCopy);
        }

        synchronized (tunWriteLock) {
            tunOut = null;
        }
        try { out.close(); } catch (Exception ignored) {}
        persistStats();
    }

    /** 异步转发一个 DNS 查询：独立 socket、独立线程、校验事务 ID */
    private void forwardAsync(final byte[] header, final int ihl, final int udpOff, final byte[] dns) {
        final List<InetAddress> targets = forwardTargets();
        if (targets.isEmpty()) return;
        if (pendingForwards.incrementAndGet() > MAX_PENDING_FORWARDS) {
            pendingForwards.decrementAndGet();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket s = null;
                try {
                    int idx = Math.abs(upstreamIndex.getAndIncrement()) % targets.size();
                    InetAddress target = targets.get(idx);
                    s = new DatagramSocket();
                    // protect 防止上游查询又走回 tun 形成循环
                    protect(s);
                    s.setSoTimeout(FORWARD_TIMEOUT_MS);

                    DatagramPacket send = new DatagramPacket(dns, 0, dns.length, target, DNS_PORT);
                    s.send(send);

                    byte[] respBuf = new byte[MAX_PACKET];
                    DatagramPacket recv = new DatagramPacket(respBuf, respBuf.length);
                    s.receive(recv);
                    int recvLen = recv.getLength();
                    if (recvLen < 12) return;

                    // 事务 ID 校验：不匹配就丢弃，交给客户端重试
                    if (dns.length >= 2) {
                        if (respBuf[0] != dns[0] || respBuf[1] != dns[1]) return;
                    }

                    byte[] outBuf = new byte[MAX_PACKET];
                    int n = buildResponse(outBuf, header, ihl, udpOff, respBuf, recvLen);
                    if (n > 0) {
                        FileOutputStream o = tunOut;
                        if (o != null) {
                            synchronized (tunWriteLock) { o.write(outBuf, 0, n); }
                        }
                    }
                } catch (Exception ignored) {
                    // 超时/网络异常：丢弃，客户端自行重试
                } finally {
                    if (s != null) {
                        try { s.close(); } catch (Exception ignored) {}
                    }
                    pendingForwards.decrementAndGet();
                }
            }
        }, "killad-fwd").start();
    }

    // ==================== 报文构造 ====================

    private int buildResponse(byte[] out, byte[] in, int ihl, int udpOff, byte[] payload) {
        return buildResponse(out, in, ihl, udpOff, payload, payload.length);
    }

    /**
     * 构造回包：交换源/目的 IP 与端口，重算 IP 校验和与 UDP 校验和。
     *
     * IP 头偏移是绝对的：源地址 12..15，目的地址 16..19。
     * ihl 只用于定位 UDP 头，不能参与 IP 头字段寻址。
     *
     * @return 写入 out 的字节数，失败返回 0
     */
    private int buildResponse(byte[] out, byte[] in, int ihl, int udpOff,
                              byte[] payload, int payloadLen) {
        if (payloadLen < 0 || payloadLen > payload.length) return 0;
        int total = 20 + 8 + payloadLen;
        if (total > out.length) return 0;
        if (in.length < udpOff + 4) return 0;

        out[0] = 0x45;
        out[1] = 0;
        out[2] = (byte) (total >> 8);
        out[3] = (byte) total;
        out[4] = 0; out[5] = 0;
        out[6] = 0; out[7] = 0;
        out[8] = 64;                 // TTL
        out[9] = 17;                 // UDP
        out[10] = 0; out[11] = 0;
        // 源 = 原目标 IP（绝对偏移 16..19）
        out[12] = in[16];
        out[13] = in[17];
        out[14] = in[18];
        out[15] = in[19];
        // 目标 = 原源 IP（绝对偏移 12..15）
        out[16] = in[12];
        out[17] = in[13];
        out[18] = in[14];
        out[19] = in[15];

        int sum = ipChecksum(out, 20);
        out[10] = (byte) (sum >> 8);
        out[11] = (byte) sum;

        int u = 20;
        out[u] = in[udpOff + 2];       // 源端口 = 原目标端口
        out[u + 1] = in[udpOff + 3];
        out[u + 2] = in[udpOff];       // 目标端口 = 原源端口
        out[u + 3] = in[udpOff + 1];
        int ulen = 8 + payloadLen;
        out[u + 4] = (byte) (ulen >> 8);
        out[u + 5] = (byte) ulen;
        out[u + 6] = 0;
        out[u + 7] = 0;

        System.arraycopy(payload, 0, out, u + 8, payloadLen);

        int uc = udpChecksum(out, ulen);
        out[u + 6] = (byte) (uc >> 8);
        out[u + 7] = (byte) uc;

        return total;
    }

    /** IPv4 头校验和（len 为头长度，偶数） */
    private static int ipChecksum(byte[] b, int len) {
        int sum = 0;
        for (int i = 0; i < len; i += 2) {
            sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (~sum) & 0xFFFF;
    }

    /** UDP 校验和（含伪首部，输出包 IP 头固定从 0 开始） */
    private static int udpChecksum(byte[] pkt, int udpLen) {
        int sum = 0;
        for (int i = 12; i < 20; i += 2) {
            sum += ((pkt[i] & 0xFF) << 8) | (pkt[i + 1] & 0xFF);
        }
        sum += 17;
        sum += udpLen;
        int u = 20;
        for (int i = 0; i < udpLen; i += 2) {
            int hi = pkt[u + i] & 0xFF;
            int lo = (i + 1 < udpLen) ? (pkt[u + i + 1] & 0xFF) : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        int c = (~sum) & 0xFFFF;
        return c == 0 ? 0xFFFF : c;
    }
}
