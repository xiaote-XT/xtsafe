package xiaote.AnQuan.SmallTool.KillAd;

import java.nio.charset.StandardCharsets;

/**
 * 极简 DNS 报文处理。
 *
 * 只做两件事：
 *   1. 解析「单问题查询」报文的域名 / 类型 / 问题段结束位置；
 *   2. 构造应答：A 查询返回 0.0.0.0，其余类型返回 NOERROR 空应答。
 *
 * 不做压缩指针全量解析（问题段实践中不使用压缩指针），不做 EDNS 处理，
 * 因为命中广告域名时直接本地应答，未命中时整包原样转发给上游。
 */
final class DnsPacket {

    int id;
    int flags;
    String name = "";
    int qType;
    int qClass;
    /** 问题段结束偏移（相对报文起点，含 QTYPE 与 QCLASS） */
    int questionEnd;
    /** 原始报文引用（用于复制问题段） */
    byte[] raw;

    private DnsPacket() {}

    /** 解析查询报文；不是有效单问题查询时返回 null */
    static DnsPacket parseQuery(byte[] buf, int len) {
        if (buf == null || len < 12 || len > buf.length) return null;
        DnsPacket p = new DnsPacket();
        p.raw = buf;
        p.id = u16(buf, 0);
        p.flags = u16(buf, 2);
        // QR=1 是响应报文，丢弃
        if ((p.flags & 0x8000) != 0) return null;
        // 只处理单问题查询
        if (u16(buf, 4) != 1) return null;

        int pos = 12;
        StringBuilder sb = new StringBuilder();
        int guard = 0;
        while (pos < len) {
            int l = buf[pos] & 0xFF;
            if (l == 0) { pos++; break; }
            // 问题段出现压缩指针或保留位，放弃解析
            if ((l & 0xC0) != 0) return null;
            if (l > 63 || pos + 1 + l > len) return null;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(buf, pos + 1, l, StandardCharsets.US_ASCII));
            pos += 1 + l;
            if (++guard > 64) return null;
        }
        if (pos + 4 > len) return null;

        p.name = sb.toString().toLowerCase();
        if (p.name.isEmpty()) return null;
        p.qType = u16(buf, pos);
        p.qClass = u16(buf, pos + 2);
        p.questionEnd = pos + 4;
        return p;
    }

    /**
     * 构造拦截应答。
     * A 查询（type=1）：返回一条 0.0.0.0 的 A 记录；
     * 其他类型（AAAA 等）：返回 NOERROR 且 ANCOUNT=0，让调用方快速失败而不是重试。
     */
    static byte[] buildBlocked(DnsPacket q) {
        if (q == null || q.raw == null) return null;
        boolean answerA = (q.qType == 1);
        int extra = answerA ? 16 : 0;
        byte[] out = new byte[q.questionEnd + extra];

        out[0] = (byte) (q.id >> 8);
        out[1] = (byte) q.id;
        // 0x8180：标准响应 + RD + RA + NOERROR
        out[2] = (byte) 0x81;
        out[3] = (byte) 0x80;
        out[4] = 0;
        out[5] = 1;                                  // QDCOUNT = 1
        out[6] = 0;
        out[7] = (byte) (answerA ? 1 : 0);           // ANCOUNT

        System.arraycopy(q.raw, 12, out, 12, q.questionEnd - 12);

        if (answerA) {
            int p = q.questionEnd;
            out[p] = (byte) 0xC0;                    // 名字指针 -> 偏移 0x0C
            out[p + 1] = 0x0C;
            out[p + 2] = 0;
            out[p + 3] = 1;                          // TYPE  = A
            out[p + 4] = 0;
            out[p + 5] = 1;                          // CLASS = IN
            out[p + 6] = 0;
            out[p + 7] = 0;
            out[p + 8] = 0;
            out[p + 9] = 60;                         // TTL = 60s
            out[p + 10] = 0;
            out[p + 11] = 4;                         // RDLENGTH = 4
            // RDATA 全 0，即 0.0.0.0
        }
        return out;
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }
}
