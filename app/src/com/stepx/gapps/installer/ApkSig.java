package com.stepx.gapps.installer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读 APK 的 v2 / v3 签名证书 SHA-256 —— tools/apksig.py 的 Java 移植。
 *
 * 为什么不用 `dumpsys package` 里的 signatures:[xxxxxxxx]：那是 8 字节短哈希，
 * 不是证书 SHA-256 前缀，跟这里的期望值没法比。
 *
 * 结构：
 *   EOCD（在文件尾 64KB 内）→ central directory 偏移 → 往前 24 字节是签名块尾
 *   （"APK Sig Block 42" + u64 size），再往前 size+8 字节是签名块本体，
 *   本体里是一串 { u64 len, u32 id, value }。
 */
final class ApkSig {

    static final int V2 = 0x7109871a;
    static final int V3 = 0xf05368c0;
    static final int V31 = 0x1b93ad61;

    static final String SIGN_GMS = "5f2391277b1dbd489000467e4c2fa6af802430080457dce2f618992e9dfb5402";
    static final String SIGN_PLAY = "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053";

    private static final byte[] MAGIC = "APK Sig Block 42".getBytes();

    private ApkSig() { }

    /** scheme → 证书 SHA-256（小写十六进制）。 */
    static Map<String, String> read(File apk) throws IOException {
        long size = apk.length();
        if (size < 64) throw new IOException("文件太小");

        try (RandomAccessFile f = new RandomAccessFile(apk, "r")) {
            int tailLen = (int) Math.min(65536, size);
            byte[] tail = new byte[tailLen];
            f.seek(size - tailLen);
            f.readFully(tail);

            int eocd = lastIndexOf(tail, new byte[]{0x50, 0x4b, 0x05, 0x06});
            if (eocd < 0) throw new IOException("不是合法 ZIP（找不到 EOCD）");

            long cdOff = u32(tail, eocd + 16);
            if (cdOff < 32 || cdOff > size) throw new IOException("central directory 偏移不合法");

            byte[] footer = new byte[24];
            f.seek(cdOff - 24);
            f.readFully(footer);
            for (int i = 0; i < MAGIC.length; i++) {
                if (footer[8 + i] != MAGIC[i]) {
                    throw new IOException("没有 APK Signing Block（v1 签名或未签名）");
                }
            }

            long blockSize = u64(footer, 0);
            long blockStart = cdOff - blockSize - 8;
            if (blockStart < 0 || blockSize > 4L * 1024 * 1024) {
                throw new IOException("签名块长度不合常理：" + blockSize);
            }
            byte[] block = new byte[(int) blockSize + 8];
            f.seek(blockStart);
            f.readFully(block);

            // body 去掉首尾各 8 字节的长度字段与尾部 magic
            int bodyOff = 8;
            int bodyEnd = block.length - 24;

            Map<String, String> out = new LinkedHashMap<>();
            int off = bodyOff;
            while (off + 12 <= bodyEnd) {
                long plen = u64(block, off);
                int id = (int) u32(block, off + 8);
                if (plen < 4 || off + 8 + plen > bodyEnd) break;
                int valueOff = off + 12;
                int valueLen = (int) plen - 4;
                String name = id == V2 ? "v2" : id == V3 ? "v3" : id == V31 ? "v3.1" : null;
                if (name != null) {
                    try {
                        out.put(name, sha256(firstCert(block, valueOff, valueLen)));
                    } catch (RuntimeException ignored) {
                        // 单个 scheme 解析失败不影响其余；最终由调用方按空结果处理
                    }
                }
                off += 8 + (int) plen;
            }
            return out;
        }
    }

    /** 当前生效的那一个（v3.1 > v3 > v2），和 apksig.py 的选择顺序一致。 */
    static String active(Map<String, String> m) {
        String s = m.get("v3.1");
        if (s == null) s = m.get("v3");
        if (s == null) s = m.get("v2");
        return s == null ? "" : s;
    }

    /** signers[0] → signer → signed data → certificates[0] */
    private static byte[] firstCert(byte[] b, int off, int len) {
        int[] p = {off};
        int end = off + len;

        byte[] signers = lp(b, p, end);
        p[0] = 0;
        byte[] signer = lp(signers, p, signers.length);
        p[0] = 0;
        byte[] signed = lp(signer, p, signer.length);

        p[0] = 0;
        lp(signed, p, signed.length);          // digests，跳过
        byte[] certs = lp(signed, p, signed.length);
        p[0] = 0;
        return lp(certs, p, certs.length);
    }

    /** length-prefixed：u32 长度 + 内容 */
    private static byte[] lp(byte[] b, int[] p, int end) {
        int at = p[0];
        if (at + 4 > end) throw new IllegalArgumentException("LP 越界");
        int n = (int) u32(b, at);
        if (n < 0 || at + 4 + n > end) throw new IllegalArgumentException("LP 长度越界：" + n);
        byte[] out = new byte[n];
        System.arraycopy(b, at + 4, out, 0, n);
        p[0] = at + 4 + n;
        return out;
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder s = new StringBuilder(d.length * 2);
            for (byte x : d) s.append(String.format("%02x", x & 0xff));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int lastIndexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = hay.length - needle.length; i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xff))
                | ((long) (b[o + 1] & 0xff) << 8)
                | ((long) (b[o + 2] & 0xff) << 16)
                | ((long) (b[o + 3] & 0xff) << 24);
    }

    private static long u64(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xff);
        return v;
    }
}
