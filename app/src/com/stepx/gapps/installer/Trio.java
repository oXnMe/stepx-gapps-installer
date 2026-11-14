package com.stepx.gapps.installer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Inflater;

/**
 * 三件套：不随 APK 分发，装的时候才去 MindTheGapps 的 release zip 里取。
 *
 * 为什么不是「打进 assets」：三个包里 GMS 一个就 196MB，摊平后 350MB，
 * 塞进 APK 等于让每个拿到安装器的人都先传一遍自己可能永远用不上的字节。
 * 上游也是运行时下载的 —— 但它整包下载 500MB 再解压，这里只下用得上的那三段。
 *
 * 怎么做到「只下三段」：zip 的中央目录在文件尾部，先 Range 读回目录（几 KB），
 * 拿到每个条目的 local header 偏移和压缩长度，再对每个条目发一次 Range 拿到
 * 它的压缩数据，用 raw deflate 解开。整包 499.6 MiB 里我们实际只走 133.2 MiB，
 * 其余 360 MiB 是别的 Gapps（Google app、TTS、语音服务…），一眼都不用看。
 *
 * 三条不变的前提：
 *  1. 包必须是原厂签名。下完立刻比 sha256（清单里的值来自上游 target.json），
 *     不匹配就不落盘 —— 下载通道不等于可信通道。
 *  2. 一定要落成实体文件再装。验签要随机读 EOCD，`pm install` 要按路径读，
 *     流式下载完直接装这条路走不通。
 *  3. 这批文件是「临时」不是「常驻」：装完就清（{@link #clear}），
 *     进程重启时再扫一遍残留（见 MainActivity#sweepCaches）。
 *
 * 通道可换：GitHub 直连取不到字节时，整段 URL 拼到 {@link #PROXY} 后面再走一遍。
 * 反代只搬字节，第 1 条照样把关，所以「经谁的手」不影响包的可信度。
 *
 * 幂等靠「盖章」：文件落盘时在旁边写 .stamp（内容就是 sha256）。
 * 同一个进程里连点两次不必重下 133MB；跨进程不再指望缓存 ——
 * 那份文件除了这一次安装没有别的用途。
 */
final class Trio {

    /** 释放 / 下载的落地目录（cacheDir 下）。 */
    static final String DIR = "trio";
    /** 清单：URL、整包大小、三个条目的 zip 内路径与 sha256。构建时打进 assets。 */
    private static final String SPEC = "google.json";

    /**
     * 加速通道。GitHub 直连拿不到字节时经它中转，拼法是「反代前缀 + 完整原 URL」。
     *
     * 它只转发，不改变字节 —— 落盘前照样按清单里的 sha256 校验，所以走不走它
     * 只影响通不通，不影响包的可信度。也正因为它是个第三方，默认不走，由用户点选。
     */
    static final String PROXY = "https://gh-proxy.com/";

    /** 读 EOCD 用的尾巴长度。zip 注释最长 64KB，从尾部这个范围里一定能找到。 */
    private static final long TAIL = 64 * 1024;
    /**
     * 单次 Range 请求的数据量。原来 8MB，为了让进度条不是每 8MB 才跳一格改成 4MB ——
     * GMS 那 99.6MB 压缩数据切 25 块，慢网下也能一两秒动一次。
     */
    private static final int CHUNK = 4 << 20;
    /** 一次 Range 最多接多少字节，防着服务器忽略 Range 直接把 500MB 灌过来。 */
    private static final int MOST = 32 << 20;

    /** 一个条目：包里叫什么、在哪、多大、什么哈希。 */
    static final class Part {
        String pkg = "";
        String label = "";
        /** zip 内路径。 */
        String path = "";
        String sha = "";
        /** 解压后的字节数（落到磁盘的大小）。 */
        long size;
        /** zip 内压缩后的字节数（要下载的量）。 */
        long packed;
        /** 落地后的实体文件，ensure 成功才有值。 */
        File file;

        String fileName() {
            return label.toLowerCase(Locale.US) + ".apk";
        }
    }

    /** 清单本体。 */
    static final class Spec {
        String name = "";
        String url = "";
        /** 整个 zip 的字节数 —— Range 的边界靠它算。 */
        long total;
        /** 三个条目压缩后合计，也就是实际要下的量。 */
        long download;
        /** 三个条目解压后合计，也就是要落盘的量。 */
        long bytes;
        final List<Part> parts = new ArrayList<>();

        boolean ok() {
            return !url.isEmpty() && total > 0 && !parts.isEmpty();
        }
    }

    private Trio() { }

    // ------------------------------ 清单 ------------------------------

    static Spec spec(Context ctx) {
        Spec s = new Spec();
        InputStream in = null;
        try {
            in = ctx.getAssets().open(SPEC);
            JSONObject o = new JSONObject(readText(in));
            s.name = o.optString("name");
            s.url = o.optString("url");
            s.total = o.optLong("size");
            s.download = o.optLong("downloadBytes");
            s.bytes = o.optLong("bytes");
            JSONArray a = o.optJSONArray("apks");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i);
                Part t = new Part();
                t.pkg = p.optString("pkg");
                t.label = p.optString("label");
                t.path = p.optString("path");
                t.sha = p.optString("sha256");
                t.size = p.optLong("bytes");
                t.packed = p.optLong("packed");
                if (t.pkg.isEmpty() || t.path.isEmpty() || t.sha.isEmpty() || t.size <= 0) continue;
                s.parts.add(t);
            }
        } catch (Throwable ignored) {
            // 清单都没有就等于没配置，交给调用方报错，不在这里喊
        } finally {
            close(in);
        }
        return s;
    }

    /** 已经落好几个。诊断里念「已就绪 2/3」，不触发下载。 */
    static int readyCount(Context ctx, Spec s) {
        File dir = new File(ctx.getCacheDir(), DIR);
        int n = 0;
        for (Part p : s.parts) if (ready(new File(dir, p.fileName()), p)) n++;
        return n;
    }

    // ------------------------------ 下载 ------------------------------

    /**
     * 确保三个包都落在 cacheDir/trio 下，返回带 file 的清单。
     *
     * 已就绪的跳过（同一个进程里点了两次「在线获取」不该重下 133MB）。
     * 任何一步失败返回 null —— 宁可报错也不能把「只取了一半」的清单交给装包流程。
     *
     * 日志按包一行：GSF 4.2 MB / GMS 187.1 MB / Play 84.6 MB，几百行进度对用户没意义。
     * 下载过程中的百分比 / 速度走 {@link Reporter#tick}，同一个包始终占同一行。
     */
    static List<Part> ensure(Context ctx, Reporter r) {
        return ensure(ctx, r, false);
    }

    /**
     * @param proxy 经 {@link #PROXY} 中转取字节。反代只负责把字节送到，
     *              落地前仍逐字比对 sha256，所以它决定的是「能不能下下来」。
     */
    static List<Part> ensure(Context ctx, Reporter r, boolean proxy) {
        Spec s = spec(ctx);
        if (!s.ok()) {
            r.line("下载清单缺失 · assets/" + SPEC, Reporter.BAD);
            return null;
        }

        File dir = new File(ctx.getCacheDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            r.line("建不了目录 · " + dir, Reporter.BAD);
            return null;
        }

        int done = 0;
        for (Part p : s.parts) {
            File f = new File(dir, p.fileName());
            if (ready(f, p)) {
                p.file = f;
                done++;
            }
        }
        if (done == s.parts.size()) {
            r.line("三个包已在缓存 · 不用重下", Reporter.OK);
            return s.parts;
        }

        // 落地 275.8 MB + 装包临时占用的余量。空间不够就别开始下 —— 下到一半没地方写更糟。
        long free = dir.getUsableSpace();
        long need = s.bytes + (200L << 20);
        if (free > 0 && free < need) {
            r.line("存储不够 · 要 " + Installer.mb(need) + "，只剩 " + Installer.mb(free), Reporter.BAD);
            return null;
        }

        String url = proxy ? PROXY + s.url : s.url;
        // 行尾只留「直连 / 加速」两个词：活动区按 37 字截行，写成「经 gh-proxy」
        // 会正好被截成「经…」—— 两种通道反而一个看得清、一个看不清。
        r.line("从 MindTheGapps 取三件套 · " + Installer.mb(s.download)
                + (proxy ? " · 加速" : " · 直连"), Reporter.INFO);
        // 完整出处只进诊断：活动区那行要短，但排查「下不动」时得知道走没走反代
        if (proxy) r.line("  经 " + PROXY + " 中转", Reporter.INFO);

        List<Entry> cd;
        // 读目录要两趟 Range，经反代时首次回源能等上几十秒 —— 没有这行就像卡死
        r.tick("dl:zip", "读包目录 · 等服务器响应", Reporter.INFO);
        try {
            cd = centralDir(s, url);
        } catch (Throwable t) {
            r.line("读不到 zip 目录 · " + t, Reporter.BAD);
            return null;
        } finally {
            r.tick("dl:zip", null, Reporter.INFO);
        }

        for (Part p : s.parts) {
            if (p.file != null) {
                r.line(p.label + " 已在缓存", Reporter.OK);
                continue;
            }
            Entry e = find(cd, p.path);
            if (e == null) {
                r.line(p.label + " 在 zip 里找不到 · " + p.path, Reporter.BAD);
                return null;
            }
            if (fetch(url, e, p, new File(dir, p.fileName()), r)) continue;
            return null;
        }
        return s.parts;
    }

    /** 删掉下载下来的全部文件，返回腾出来的字节数。 */
    static long clear(Context ctx) {
        File root = new File(ctx.getCacheDir(), DIR);
        long freed = wipe(root);
        root.delete();
        return freed;
    }

    // ------------------------------ zip over http ------------------------------

    /** 中央目录里的一条。offset 是 local header 的绝对位置。 */
    private static final class Entry {
        String name = "";
        int method;
        long csz, usz, off;
    }

    /**
     * 只读尾部 64KB + 中央目录，把整包 44 个条目列出来。
     *
     * 不做 zip64：这个包 499MB，远在 4GB 以内，多了反而是没测过的分支。
     */
    private static List<Entry> centralDir(Spec s, String url) throws Exception {
        byte[] tail = range(url, s.total - TAIL, s.total - 1);
        int i = lastEocd(tail);
        if (i < 0) throw new IOException("找不到 EOCD");
        long cdSize = u32(tail, i + 12);
        long cdOff = u32(tail, i + 16);
        if (cdSize <= 0 || cdOff <= 0 || cdOff + cdSize > s.total) throw new IOException("目录越界");

        byte[] d = range(url, cdOff, cdOff + cdSize - 1);
        List<Entry> out = new ArrayList<>();
        int p = 0;
        while (p + 46 <= d.length && u32(d, p) == 0x02014b50L) {
            Entry e = new Entry();
            e.method = u16(d, p + 10);
            e.csz = u32(d, p + 20);
            e.usz = u32(d, p + 24);
            int nlen = u16(d, p + 28), elen = u16(d, p + 30), clen = u16(d, p + 32);
            e.off = u32(d, p + 42);
            if (p + 46 + nlen > d.length) break;
            e.name = new String(d, p + 46, nlen, StandardCharsets.UTF_8);
            out.add(e);
            p += 46 + nlen + elen + clen;
        }
        if (out.isEmpty()) throw new IOException("目录解析为空");
        return out;
    }

    private static Entry find(List<Entry> cd, String path) {
        for (Entry e : cd) if (e.name.equalsIgnoreCase(path)) return e;
        return null;
    }

    /**
     * 下载一个文件：写 .part → 边解压边算 sha256 → 校验 → 改名 → 盖章。
     *
     * 进度行走 {@link Reporter#tick} 原地刷新，收尾（成功或失败）都撤掉 ——
     * 活动区该留的是每个包一行结果，不该留一个停在 87% 的残影。
     */
    private static boolean fetch(String url, Entry e, Part p, File dst, Reporter r) {
        String key = "dl:" + p.label;
        try {
            return fetchBody(url, e, p, dst, r, key);
        } finally {
            r.tick(key, null, Reporter.INFO);
        }
    }

    private static boolean fetchBody(String url, Entry e, Part p, File dst, Reporter r, String key) {
        File tmp = new File(dst.getPath() + ".part");
        MessageDigest md;
        long dataStart;
        try {
            md = MessageDigest.getInstance("SHA-256");
            // local header 的 extra 段长度只有 header 自己知道，所以得先读一遍它。
            byte[] lh = range(url, e.off, e.off + 29);
            if (lh.length < 30) throw new IOException("本地头读不到");
            dataStart = e.off + 30 + u16(lh, 26) + u16(lh, 28);
        } catch (Throwable t) {
            r.line("  " + p.label + " 读不到 · " + t, Reporter.BAD);
            return false;
        }

        long t0 = System.currentTimeMillis();
        long written = 0;
        try (OutputStream os = new FileOutputStream(tmp)) {
            written = e.method == 8
                    ? inflate(url, e, dataStart, os, md, p, t0, key, r)
                    : store(url, e, dataStart, os, md, p, t0, key, r);
        } catch (Throwable t) {
            tmp.delete();
            r.line("  " + p.label + " 下载失败 · " + t, Reporter.BAD);
            return false;
        }

        String got = hex(md.digest());
        if (written != p.size || !got.equalsIgnoreCase(p.sha)) {
            tmp.delete();
            r.line("  " + p.label + " 哈希对不上 · 清单 " + head8(p.sha) + " 实际 " + head8(got)
                    + "（" + written + " B）", Reporter.BAD);
            return false;
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            r.line("  改名失败 · " + dst, Reporter.BAD);
            return false;
        }
        stamp(dst, p);
        p.file = dst;
        r.line(p.label + " " + Installer.mb(p.size) + " · "
                + (System.currentTimeMillis() - t0) + "ms", Reporter.OK);
        return true;
    }

    /** deflate 流：逐块 Range 取压缩数据，喂给一个 Inflater，边解边写边算哈希。 */
    private static long inflate(String url, Entry e, long dataStart, OutputStream os,
                                MessageDigest md, Part p, long t0, String key, Reporter r)
            throws Exception {
        Inflater inf = new Inflater(true);
        byte[] out = new byte[256 * 1024];
        long pos = dataStart, left = e.csz, written = 0;
        int mark = -1;
        try {
            while (left > 0) {
                byte[] chunk = range(url, pos, pos + Math.min(CHUNK, left) - 1);
                if (chunk.length == 0) throw new IOException("连接断了");
                left -= chunk.length;
                pos += chunk.length;
                inf.setInput(chunk);
                while (!inf.needsInput()) {
                    int n = inf.inflate(out);
                    if (n <= 0) break;
                    os.write(out, 0, n);
                    md.update(out, 0, n);
                    written += n;
                }
                mark = progress(e, pos - dataStart, p, t0, mark, key, r);
            }
            if (written != e.usz) throw new IOException("解压长度不符 " + written + "/" + e.usz);
        } finally {
            inf.end();
        }
        return written;
    }

    /** 没压缩的条目：直接把 Range 分块抄下来。 */
    private static long store(String url, Entry e, long dataStart, OutputStream os,
                              MessageDigest md, Part p, long t0, String key, Reporter r)
            throws Exception {
        long pos = dataStart, left = e.csz, written = 0;
        int mark = -1;
        while (left > 0) {
            byte[] chunk = range(url, pos, pos + Math.min(CHUNK, left) - 1);
            if (chunk.length == 0) throw new IOException("连接断了");
            left -= chunk.length;
            pos += chunk.length;
            os.write(chunk);
            md.update(chunk);
            written += chunk.length;
            mark = progress(e, pos - dataStart, p, t0, mark, key, r);
        }
        return written;
    }

    /**
     * 同一个包始终改同一行：`GMS 62% · 4.8 MB/s`。
     *
     * 只在百分比变了才刷 —— 4MB 一块时同一块内可能回调多次，重复改写同一行没意义。
     * 百分比按「已收到的压缩字节」算，也就是用户感知的那条网速曲线。
     *
     * @return 上一次写过的百分比，调用方原样带回来
     */
    private static int progress(Entry e, long got, Part p, long t0, int mark,
                                String key, Reporter r) {
        int pct = (int) (got * 100 / Math.max(1, e.csz));
        if (pct == mark) return mark;
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        r.tick(key, p.label + " " + pct + "% · " + Installer.mb(got * 1000 / ms) + "/s",
                Reporter.INFO);
        return pct;
    }

    /**
     * Range 取一段。要求 206：CDN 忽略 Range 直接回 200 时，只在从 0 开始时接受，
     * 否则整包 500MB 会顺着流灌进来。
     */
    private static byte[] range(String url, long from, long to) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("Range", "bytes=" + from + "-" + to);
                // 不声明 identity 的话，有的 CDN 会给 zip 再套一层 gzip，解出来是坏的
                c.setRequestProperty("Accept-Encoding", "identity");
                int code = c.getResponseCode();
                if (code == 206) return read(c, (int) Math.min(MOST, to - from + 1));
                if (code == 200 && from == 0) return read(c, MOST);
                if (code == 200) throw new IOException("服务器不支持分段");
                throw new IOException("HTTP " + code);
            } catch (IOException t) {
                last = t;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last == null ? new IOException("取不到数据") : last;
    }

    private static byte[] read(HttpURLConnection c, int limit) throws IOException {
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.min(limit, 1 << 20));
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
            if (bo.size() >= limit) break;
        }
        in.close();
        return bo.toByteArray();
    }

    // ------------------------------ 就绪 / 盖章 ------------------------------

    private static boolean ready(File dst, Part p) {
        if (!dst.isFile() || dst.length() != p.size) return false;
        File st = stampFile(dst);
        if (!st.isFile()) return false;
        InputStream in = null;
        try {
            in = new FileInputStream(st);
            byte[] b = new byte[80];
            int n = in.read(b);
            return n > 0 && new String(b, 0, n, StandardCharsets.UTF_8).trim().equalsIgnoreCase(p.sha);
        } catch (Throwable t) {
            return false;
        } finally {
            close(in);
        }
    }

    private static File stampFile(File dst) {
        return new File(dst.getParentFile(), dst.getName() + ".stamp");
    }

    private static void stamp(File dst, Part p) {
        try (OutputStream os = new FileOutputStream(stampFile(dst))) {
            os.write((p.sha + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // 盖不上章最多下次重下一遍，不影响这次装
        }
    }

    /** 递归删，只算文件字节。 */
    private static long wipe(File f) {
        if (!f.exists()) return 0;
        if (f.isFile()) {
            long n = f.length();
            return f.delete() ? n : 0;
        }
        File[] fs = f.listFiles();
        long n = 0;
        if (fs != null) for (File c : fs) n += wipe(c);
        f.delete();
        return n;
    }

    // ------------------------------ 小工具 ------------------------------

    private static int lastEocd(byte[] b) {
        for (int i = b.length - 22; i >= 0; i--) {
            if (u32(b, i) == 0x06054b50L) return i;
        }
        return -1;
    }

    private static int u16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int p) {
        if (p + 4 > b.length) return -1;
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8)
                | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24);
    }

    private static String readText(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    private static String head8(String s) {
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    private static void close(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // 关不掉不影响上下文
        }
    }
}
