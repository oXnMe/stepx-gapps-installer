package com.stepx.gapps.installer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 检查并装上新版。
 *
 * 两条硬约束决定了这里的写法：
 *
 *  1. **直连不一定通，加速不一定是坏人。** 先直连 api.github.com，8 秒拿不到就改走
 *     {@link Trio#PROXY} 那台中转（和在线取三件套同一个前缀）。中转只搬字节，
 *     而这里拿到的是「哪个 tag + 哪个 apk URL」，装之前还要在本地开包核对包名与
 *     versionCode —— 所以走谁的手不影响这笔更新能不能信。
 *  2. **装包交给系统安装器**，不替用户按下去。下载完只负责把包递过去（content://
 *     出口见 {@link ApkProvider}），装不装由用户在系统确认页决定。确认之后本进程
 *     仍然会被替换掉 —— 那是覆盖自身的必然结果，不是这里能免的。
 */
final class Update {

    private static final String API =
            "https://api.github.com/repos/oXnMe/stepx-gapps-installer/releases/latest";

    /** 检查结果。version 是从 tag 里剥出来的纯数字段（`v0.35` → `0.35`）。 */
    static final class Info {
        String tag = "";
        String version = "";
        String url = "";
        long size;
        boolean proxy;
    }

    /** 最近一次 handOver() 失败的原因，给调用方拼一句人话。 */
    static String lastError = "";

    private Update() { }

    // ------------------------------ 查版本 ------------------------------

    /**
     * 取最新 Release。直连失败才走加速 —— 顺序不能反：中转是第三方，
     * 能用直连时没有任何理由让版本查询先经过它。
     */
    static Info latest(Reporter r) throws IOException {
        IOException last = null;
        for (int i = 0; i < 2; i++) {
            boolean proxy = i == 1;
            try {
                Info info = fetch(proxy);
                r.line("  版本号 · " + info.tag
                        + (proxy ? "（直连不通，走加速）" : "（直连）"), Reporter.INFO);
                return info;
            } catch (IOException e) {
                last = e;
                // 一直用 INFO：查版本失败不影响任何功能，不该被记成「最近错误」；
                // 对用户来说结果已经写在那颗红点上了。
                r.line("  " + (proxy ? "加速" : "直连") + "取版本号失败：" + brief(e),
                        Reporter.INFO);
            }
        }
        throw last == null ? new IOException("取不到版本号") : last;
    }

    private static Info fetch(boolean proxy) throws IOException {
        HttpURLConnection c = null;
        try {
            URL u = new URL((proxy ? Trio.PROXY : "") + API);
            c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            // GitHub 的 API 不带 User-Agent 会直接回 403
            c.setRequestProperty("User-Agent", "stepx-gapps-installer");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);

            JSONObject o = new JSONObject(text(c.getInputStream()));
            Info i = new Info();
            i.tag = o.optString("tag_name", "");
            i.version = version(i.tag);
            i.proxy = proxy;

            JSONArray a = o.optJSONArray("assets");
            for (int k = 0; a != null && k < a.length(); k++) {
                JSONObject it = a.optJSONObject(k);
                if (it == null) continue;
                String name = it.optString("name", "");
                if (!name.toLowerCase(Locale.US).endsWith(".apk")) continue;
                i.url = it.optString("browser_download_url", "");
                i.size = it.optLong("size");
                if (!i.url.isEmpty()) break;
            }
            if (i.version.isEmpty()) throw new IOException("tag 认不出：" + i.tag);
            if (i.url.isEmpty()) throw new IOException("这个 Release 里没有 apk");
            return i;
        } catch (org.json.JSONException e) {
            throw new IOException("返回的不是 JSON");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** `v0.35` → `0.35`。只留开头的数字段，后面带 -beta 之类也不影响比大小。 */
    static String version(String tag) {
        String s = tag == null ? "" : tag.trim();
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        int j = i;
        while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
        return s.substring(i, j);
    }

    /** remote 是否比 local 新。按点分段比数字，缺的段当 0（`0.34` 与 `0.34.0` 相等）。 */
    static boolean newer(String remote, String local) {
        long[] a = nums(remote);
        long[] b = nums(local);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? a[i] : 0;
            long y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static long[] nums(String v) {
        if (v == null || v.isEmpty()) return new long[0];
        String[] parts = v.split("\\.");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    // ------------------------------ 下载 ------------------------------

    /** 落成 cacheDir/update/app.apk。直连下不动就换加速，两条路都只搬同一个 URL。 */
    static File download(Context ctx, Info info, Reporter r) throws IOException {
        File dir = new File(ctx.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("建不了缓存目录");
        File dst = new File(dir, "app.apk");
        if (dst.exists() && !dst.delete()) throw new IOException("旧文件删不掉");

        IOException last = null;
        for (int i = 0; i < 2; i++) {
            boolean proxy = i == 1;
            try {
                pull((proxy ? Trio.PROXY : "") + info.url, dst, info, r);
                r.tick("update", null, Reporter.INFO);
                return dst;
            } catch (IOException e) {
                last = e;
                r.line("  " + (proxy ? "加速" : "直连") + "下载失败：" + brief(e),
                        i == 0 ? Reporter.WARN : Reporter.BAD);
            }
        }
        r.tick("update", null, Reporter.INFO);
        // 两条路都没成，别把半截文件留在缓存里等下次启动清
        if (dst.exists()) dst.delete();
        throw last == null ? new IOException("下不动") : last;
    }

    private static void pull(String url, File dst, Info info, Reporter r) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(12000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "stepx-gapps-installer");
            // 不声明 identity 的话，有的中转会给 apk 再套一层 gzip，解出来是坏的
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);

            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : info.size;
            try (InputStream in = c.getInputStream();
                 OutputStream os = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                long t0 = System.currentTimeMillis();
                int mark = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    got += n;
                    int pct = (int) (got * 100 / Math.max(1, total));
                    if (pct == mark) continue;
                    long ms = Math.max(1, System.currentTimeMillis() - t0);
                    r.tick("update", "新版 " + pct + "% · "
                            + Installer.mb(got * 1000 / ms) + "/s", Reporter.INFO);
                    mark = pct;
                }
            }
            if (info.size > 0 && dst.length() != info.size) {
                throw new IOException("字节数不对 " + dst.length() + "/" + info.size);
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------ 安装 ------------------------------

    /**
     * 把下载好的包交给系统安装器，由用户自己按「更新」。
     *
     * 不走 `pm install`：那是以 shell 身份替用户拍板 —— 装哪个包、装谁的包，
     * 确认页一次都不出现；覆盖本应用还会顺手把当前进程杀掉，用户看不到任何过程。
     * 交给系统安装器之后，包名、权限、签名都在系统那张确认页上，用户点下去才装。
     *
     * 文件留在 cache/update 不删：安装器是另一个进程，它读完之前删了就是装失败。
     * 残留统一在下次启动时清（见 MainActivity.sweepCaches）。
     */
    static boolean handOver(Context ctx, File apk, Reporter r) {
        lastError = "";
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(ApkProvider.uriFor(apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            lastError = "系统里没有能打开 apk 的安装器";
        } catch (Throwable t) {
            lastError = brief(t);
        }
        r.line("  " + lastError, Reporter.BAD);
        return false;
    }

    /**
     * 系统是否允许本应用发起安装。
     *
     * 走系统安装器要先有这个 appop（`REQUEST_INSTALL_PACKAGES`）；没开的话安装器
     * 只会丢一句「无法安装」，用户根本不知道该去哪儿开，所以先问一句。
     */
    static boolean canInstall(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * 清掉下载残留。装完（或放弃）之后这个 apk 再没有任何用途 ——
     * 和在线取三件套那份缓存同一个道理：不清就是白占几百 KB 到几 MB。
     */
    static long clear(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "update");
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        long n = 0;
        for (File f : fs) {
            long size = f.length();
            if (f.delete()) n += size;
        }
        return n;
    }

    // ------------------------------ 工具 ------------------------------

    private static String text(InputStream in) throws IOException {
        try (InputStream src = in) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8 * 1024];
            int n;
            while ((n = src.read(b)) > 0) {
                bo.write(b, 0, n);
                if (bo.size() > 1 << 20) throw new IOException("响应大得离谱");
            }
            return bo.toString("UTF-8");
        }
    }

    /** 异常压成一句：诊断里要能看出是超时、DNS 还是 HTTP 码，但不需要整段堆栈。 */
    private static String brief(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
