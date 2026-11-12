package com.stepx.gapps.installer;

import java.nio.charset.Charset;

/**
 * 只认三件事的二进制 AndroidManifest 解析器：包名、versionCode、split 名。
 *
 * 为什么非要解析 —— 光看签名分不出 Play 和 GSF（两者同一个证书 7ce83c1b…），
 * 用户把 Play 放进 GSF 的位置时会静默装错。包名是唯一能对上号的字段。
 *
 * 格式（frameworks/base/libs/androidfw/ResourceTypes.h）：
 *   文件头   ResChunk_header { u16 type=0x0003, u16 headerSize, u32 size }
 *   字符串池 type=0x0001：stringCount / flags(0x100=UTF8) / stringsStart + 偏移表
 *   起始元素 type=0x0102：name 是池下标，属性从 off+headerSize+attributeStart 起，
 *                        每条 attributeSize 字节 { ns, name, rawValue, typedValue }
 */
final class Axml {

    static final class Manifest {
        String pkg = "";
        String split = "";
        String versionName = "";
        long versionCode;

        boolean isSplit() {
            return !split.isEmpty();
        }
    }

    private Axml() { }

    static Manifest parse(byte[] b) {
        if (b == null || b.length < 8 + 8) throw new IllegalArgumentException("manifest 太短");
        if (u16(b, 0) != 0x0003) throw new IllegalArgumentException("不是二进制 XML");

        int fileSize = (int) u32(b, 4);
        if (fileSize <= 0 || fileSize > b.length) fileSize = b.length;

        Pool pool = null;
        int off = u16(b, 2);
        Manifest out = null;

        while (off + 8 <= fileSize) {
            int type = u16(b, off);
            int hdr = u16(b, off + 2);
            int size = (int) u32(b, off + 4);
            if (size <= 0 || off + size > fileSize) break;

            if (type == 0x0001) {
                pool = new Pool(b, off);
            } else if (type == 0x0102 && pool != null) {
                Manifest m = element(b, off, hdr, pool);
                if (m != null) { out = m; break; }   // 第一个 <manifest> 就是它
            }
            off += size;
        }
        if (out == null) throw new IllegalArgumentException("没找到 <manifest> 元素");
        return out;
    }

    /** 只处理名字叫 manifest 的起始元素。 */
    private static Manifest element(byte[] b, int off, int hdr, Pool pool) {
        // ResXMLTree_node 头是 16 字节（chunk 头 8 + lineNumber + comment），
        // attrExt 紧跟其后：ns @+0、name @+4，所以 name 在 off+hdr+4。
        int nameIdx = (int) u32(b, off + hdr + 4);
        if (!"manifest".equals(pool.get(nameIdx))) return null;

        int p = off + hdr;
        int attrStart = u16(b, p + 8);
        int attrSize = u16(b, p + 10);
        int attrCount = u16(b, p + 12);
        if (attrSize < 20) return null;

        Manifest m = new Manifest();
        for (int i = 0; i < attrCount; i++) {
            int a = p + attrStart + i * attrSize;
            if (a + 20 > b.length) break;
            String an = pool.get((int) u32(b, a + 4));
            if (an == null) continue;
            long raw = u32(b, a + 8);
            int type = b[a + 15] & 0xff;
            long data = u32(b, a + 16);

            switch (an) {
                case "package":
                    m.pkg = str(pool, raw, type, data);
                    break;
                case "split":
                    m.split = str(pool, raw, type, data);
                    break;
                case "versionCode":
                    m.versionCode = data;
                    break;
                case "versionName":
                    m.versionName = str(pool, raw, type, data);
                    break;
                default:
                    break;
            }
        }
        return m.pkg.isEmpty() ? null : m;
    }

    private static String str(Pool pool, long raw, int type, long data) {
        // 字符串属性：rawValue 就是池下标；rawValue 缺失时才退回 typedValue.data
        String s = raw != 0xFFFFFFFFL ? pool.get((int) raw) : null;
        if (s == null && type == 0x03) s = pool.get((int) data);
        return s == null ? "" : s;
    }

    /** ResStringPool */
    private static final class Pool {
        private final byte[] b;
        private final int base;
        private final int hdrSize;
        private final int count;
        private final boolean utf8;
        private final int stringsBase;

        Pool(byte[] b, int base) {
            this.b = b;
            this.base = base;
            this.hdrSize = u16(b, base + 2);
            this.count = (int) u32(b, base + 8);
            long flags = u32(b, base + 16);
            this.utf8 = (flags & 0x100) != 0;
            this.stringsBase = base + (int) u32(b, base + 20);
        }

        String get(int idx) {
            if (idx < 0 || idx >= count) return null;
            int offOff = base + hdrSize + idx * 4;
            if (offOff + 4 > b.length) return null;
            int at = stringsBase + (int) u32(b, offOff);
            if (at < 0 || at >= b.length) return null;
            return utf8 ? readUtf8(at) : readUtf16(at);
        }

        private String readUtf8(int at) {
            int[] p = {at};
            skipLen(p);                       // utf16 长度，用不上
            int n = readLen(p);
            if (n < 0 || p[0] + n > b.length) return null;
            return new String(b, p[0], n, Charset.forName("UTF-8"));
        }

        /**
         * UTF-16 池的长度是**定长 u16**，不是 UTF-8 那种 1–2 字节变长。
         * 按变长读会少吃掉一个字节，整串字符双双错位（"theme" 读成 "琀栀攀洀攀"）。
         */
        private String readUtf16(int at) {
            if (at + 2 > b.length) return null;
            int n = u16(b, at);
            int p = at + 2;
            if (n < 0 || p + n * 2 > b.length) return null;
            return new String(b, p, n * 2, Charset.forName("UTF-16LE"));
        }

        private void skipLen(int[] p) {
            readLen(p);
        }

        /** 1 或 2 字节变长长度 */
        private int readLen(int[] p) {
            int v = b[p[0]] & 0xff;
            if ((v & 0x80) != 0) {
                v = ((v & 0x7f) << 8) | (b[p[0] + 1] & 0xff);
                p[0] += 2;
            } else {
                p[0] += 1;
            }
            return v;
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xff))
                | ((long) (b[o + 1] & 0xff) << 8)
                | ((long) (b[o + 2] & 0xff) << 16)
                | ((long) (b[o + 3] & 0xff) << 24);
    }
}
