package com.stepx.gapps.installer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把用户在文件选择器里点中的东西变成一组本地 APK 文件。
 *
 * 为什么要先落到私有目录，而不是直接拿 Uri 干活：
 * 后面要读 EOCD（在文件尾）、要按包名分组、还要把同一批分片一次送进一个安装会话，
 * 都需要能随机访问的实体文件。ContentResolver 的流只能顺序读一次。
 *
 * 顺带支持 .zip/.apkm/.xapk 这类「一个包里塞了 base + 分片」的下载产物 ——
 * 判据是根目录没有 AndroidManifest.xml 但有 *.apk 条目。
 */
final class ApkImport {

    static final class Loaded {
        String name;
        File file;
    }

    private ApkImport() { }

    static List<Loaded> load(Context ctx, List<Uri> uris, File work, Reporter r) {
        List<Loaded> out = new ArrayList<>();
        int i = 0;
        for (Uri u : uris) {
            String name = displayName(ctx, u);
            int seq = i;
            File raw = new File(work, "in-" + (i++) + ".bin");
            try {
                long bytes = copy(ctx, u, raw);
                r.line("  ↓ " + name + "  " + Installer.mb(bytes), Reporter.INFO);
                unpack(raw, name, work, out, r, seq);
            } catch (Throwable t) {
                r.line("  读不了 " + name + "：" + t, Reporter.BAD);
            } finally {
                if (raw.exists()) raw.delete();
            }
        }
        return out;
    }

    /** APK 原样留着；zip 就展开里面所有 .apk。 */
    private static void unpack(File raw, String name, File work, List<Loaded> out, Reporter r, int seq)
            throws IOException {
        try (ZipFile z = new ZipFile(raw)) {
            if (z.getEntry("AndroidManifest.xml") != null) {
                File dst = new File(work, safe(name));
                move(raw, dst);
                out.add(item(name, dst));
                return;
            }
            int n = 0;
            Enumeration<? extends ZipEntry> es = z.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".apk")) continue;
                String base = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                // 序号必须带上「第几个压缩包」：GMS 和 Play 的 apkm 里都有 base.apk，
                // 只按包内序号命名的话，两个 z-0-base.apk 会互相覆盖 ——
                // 表现是选完包一装就报「GMS 的 base.apk 数量是 2」，Play 一个 base 都没有。
                File dst = new File(work, "z" + seq + "-" + (n++) + "-" + safe(base));
                try (InputStream in = z.getInputStream(e);
                     OutputStream os = new FileOutputStream(dst)) {
                    copy(in, os);
                }
                // 用 zip 内的完整路径当显示名：三件套的 base.apk 同名，
                // 只写文件名的话日志里会出现三个一模一样的 "base.apk"
                out.add(item(e.getName(), dst));
            }
            if (n == 0) r.line("  " + name + " 里没有 .apk", Reporter.BAD);
            else r.line("  " + name + " 是压缩包，展开出 " + n + " 个 apk", Reporter.INFO);
        }
    }

    private static Loaded item(String name, File f) {
        Loaded l = new Loaded();
        l.name = name;
        l.file = f;
        return l;
    }

    /**
     * 清掉上一轮留下的缓存，返回腾出来的字节数。
     *
     * 这些文件是「用户所选 Uri 的一次性副本」——原文件还在 Download 里，
     * 副本的用途只有当前这一次安装。所以装完、或者进程重启时扫到，就该删干净。
     */
    static long clear(File work) {
        File[] fs = work.listFiles();
        if (fs == null) return 0;
        long freed = 0;
        for (File f : fs) {
            if (!f.isFile()) continue;
            long n = f.length();
            if (f.delete()) freed += n;
        }
        return freed;
    }

    private static String displayName(Context ctx, Uri u) {
        try (Cursor c = ctx.getContentResolver()
                .query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
            // 有些 provider 不给列，退回 Uri 末段
        }
        String p = u.getLastPathSegment();
        return p == null ? "apk" : p;
    }

    private static long copy(Context ctx, Uri u, File dst) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(u);
             OutputStream os = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("provider 打开了空流");
            return copy(in, os);
        }
    }

    private static long copy(InputStream in, OutputStream os) throws IOException {
        byte[] b = new byte[128 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(b)) > 0) {
            os.write(b, 0, n);
            total += n;
        }
        return total;
    }

    private static void move(File from, File to) throws IOException {
        if (!from.renameTo(to)) {
            try (InputStream in = new FileInputStream(from);
                 OutputStream os = new FileOutputStream(to)) {
                copy(in, os);
            }
            from.delete();
        }
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
