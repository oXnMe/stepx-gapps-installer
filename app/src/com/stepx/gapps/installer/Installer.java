package com.stepx.gapps.installer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 阶段 1（验签）+ 阶段 2（装包）。
 *
 * 三条从真机踩出来的硬约束：
 *
 *  1. **APK 必须放 /data/local/tmp** —— `pm install` 是 system_server 去读文件，
 *     它读不了 FUSE，/sdcard 上的 APK 一律 `INSTALL_FAILED_INVALID_URI`（坑 7 / 坑 3）。
 *  2. **shell 侧的 `pm install` 走的是 StepOS 那道「USB 安装」闸门**（实测）：
 *     `UsbInstallInterception` 只认 INSTALL_FROM_ADB(0x20)，而 `pm install` 恰好带这个 flag。
 *     所以装之前必须先写 `usb_install_item_<包名>` = `<标签>:1`，否则：
 *       已有键且为 -1 → 立刻 INSTALL_FAILED_USER_RESTRICTED
 *       完全没有键     → 卡满 15 秒等弹窗，然后同样失败
 *  3. **分片包要一次装完** —— GMS / Play 都是 base + 若干 config 分片，
 *     只装 base 会被 PMS 以 requiredSplitTypes 拒掉。
 */
final class Installer {

    /** 临时落地点。不能用 /sdcard，见类注释第 1 条。 */
    static final String TMP = "/data/local/tmp/stepx-install";

    /** 安装顺序固定：GSF 是 GMS 的前置，Play 依赖 GMS。 */
    static final String[] ORDER = {SelfHeal.GSF, SelfHeal.GMS, SelfHeal.PLAY};

    private Installer() { }

    /** 一个待装文件。 */
    static final class Item {
        String displayName = "";
        File file;
        long size;
        String pkg = "";
        String split = "";
        String versionName = "";
        long versionCode;
        String signer = "";
        String expect = "";
        boolean verified;
        String problem = "";

        boolean isBase() {
            return split.isEmpty();
        }

        String remoteName() {
            return isBase() ? "base.apk" : "split_" + split.replaceAll("[^A-Za-z0-9._-]", "_") + ".apk";
        }

        String label() {
            if (!problem.isEmpty()) return displayName + " → " + problem;
            return displayName + " → " + pkg + (isBase() ? " base" : " " + split)
                    + " v" + versionCode;
        }
    }

    static String expectSigner(String pkg) {
        if (SelfHeal.GMS.equals(pkg)) return ApkSig.SIGN_GMS;
        if (SelfHeal.PLAY.equals(pkg) || SelfHeal.GSF.equals(pkg)) return ApkSig.SIGN_PLAY;
        return "";
    }

    static String friendly(String pkg) {
        if (SelfHeal.GSF.equals(pkg)) return "GSF";
        if (SelfHeal.GMS.equals(pkg)) return "GMS";
        if (SelfHeal.PLAY.equals(pkg)) return "Play";
        return pkg;
    }

    // ------------------------------ 阶段 1：验签 ------------------------------

    static Item inspect(File f, String displayName) {
        Item it = new Item();
        it.file = f;
        it.displayName = displayName;
        it.size = f.length();

        try {
            byte[] manifest;
            try (ZipFile z = new ZipFile(f)) {
                ZipEntry e = z.getEntry("AndroidManifest.xml");
                if (e == null) throw new IOException("没有 AndroidManifest.xml，不是 APK");
                try (InputStream in = z.getInputStream(e)) {
                    manifest = readAll(in, 4 * 1024 * 1024);
                }
            }
            Axml.Manifest m = Axml.parse(manifest);
            it.pkg = m.pkg;
            it.split = m.split;
            it.versionCode = m.versionCode;
            it.versionName = m.versionName;
        } catch (Throwable t) {
            it.problem = "清单读不出来：" + shortMsg(t);
            return it;
        }

        try {
            Map<String, String> sigs = ApkSig.read(f);
            it.signer = ApkSig.active(sigs);
            if (it.signer.isEmpty()) it.problem = "只有 v1 签名或未签名";
        } catch (Throwable t) {
            it.problem = "签名读不出来：" + shortMsg(t);
            return it;
        }

        it.expect = expectSigner(it.pkg);
        if (it.expect.isEmpty()) {
            it.problem = "不是三件套里的包";
            return it;
        }
        if (!it.expect.equals(it.signer)) {
            it.problem = "签名不符（期望 " + head(it.expect) + "，实际 " + head(it.signer) + "）";
            return it;
        }
        it.verified = true;
        return it;
    }

    /** 把已验过的文件按包分组，并检查三件套是否齐全。返回 null 表示有阻断问题。 */
    static Map<String, List<Item>> group(List<Item> items, Reporter r) {
        Map<String, List<Item>> by = new LinkedHashMap<>();

        r.line("阶段 1：验签", Reporter.STEP);
        for (Item it : items) {
            if (it.verified) {
                r.line("  ✓ " + it.label() + "  " + head(it.signer) + "  "
                        + mb(it.size), Reporter.OK);
                by.computeIfAbsent(it.pkg, k -> new ArrayList<>()).add(it);
            } else {
                r.line("  ✗ " + it.label() + "  " + mb(it.size), Reporter.BAD);
            }
        }

        int bad = 0;
        for (Item it : items) if (!it.verified) bad++;
        if (bad > 0) {
            r.line("有 " + bad + " 个文件没过验签，先停 —— 装错包比装不上更难收尾。", Reporter.BAD);
            return null;
        }

        for (String pkg : ORDER) {
            List<Item> g = by.get(pkg);
            if (g == null) {
                r.line("缺 " + friendly(pkg) + "（" + pkg + "）的 APK", Reporter.BAD);
                return null;
            }
            int base = 0;
            for (Item it : g) if (it.isBase()) base++;
            if (base != 1) {
                r.line(friendly(pkg) + " 的 base.apk 数量是 " + base + "，应该正好 1 个",
                        Reporter.BAD);
                return null;
            }
            r.line("  " + friendly(pkg) + "：" + g.size() + " 个文件（base + "
                    + (g.size() - 1) + " 分片），共 " + mb(total(g)), Reporter.INFO);

            int foreign = 0;
            for (Item it : g) if (it.split.contains("x86")) foreign++;
            if (foreign > 0) {
                r.line("    其中 " + foreign + " 个是 x86/x86_64 分片，本机用不上；"
                        + "不影响安装，只是多占空间", Reporter.WARN);
            }
        }

        for (String pkg : by.keySet()) {
            boolean known = false;
            for (String k : ORDER) if (k.equals(pkg)) known = true;
            if (!known) r.line("忽略无关包：" + pkg, Reporter.WARN);
        }
        return by;
    }

    // ------------------------------ 阶段 2：装包 ------------------------------

    /**
     * 设备上三个包的 versionCode，一次 shell 往返取回。没装的记为 0。
     *
     * 为什么单独拎出来：除了 needInstall，还要回答另一个问题 ——
     * 「这台机器上三件套齐了吗」。齐了就意味着装包整段可以跳过，
     * 用户不该被拉去选包；不齐才真的需要他手上那份 APK。
     */
    static Map<String, Long> versions(Shell sh) {
        StringBuilder code = new StringBuilder();
        for (String pkg : ORDER) {
            code.append("echo ").append(pkg)
                .append("=$(dumpsys package ").append(pkg)
                .append(" 2>/dev/null | grep -oE 'versionCode=[0-9]+' | head -1 | cut -d= -f2)\n");
        }
        Shell.Exec e = sh.exec(code.toString(), 60);

        Map<String, Long> on = new LinkedHashMap<>();
        for (String ln : e.out.split("\\R")) {
            int i = ln.indexOf('=');
            if (i <= 0) continue;
            String v = ln.substring(i + 1).trim();
            on.put(ln.substring(0, i).trim(), v.isEmpty() ? 0L : parseLong(v));
        }
        return on;
    }

    /** 三件套是不是都在设备上（有版本号就算在，不看启停状态）。 */
    static boolean allInstalled(Shell sh) {
        Map<String, Long> on = versions(sh);
        for (String pkg : ORDER) {
            Long v = on.get(pkg);
            if (v == null || v <= 0) return false;
        }
        return true;
    }

    /**
     * 哪些包还需要装。
     *
     * 全流程比手点多的唯一一件事就是「别白装」：三个包都已经在设备上、版本也对，
     * 就没有任何理由把几百 MB 再灌一遍 —— 重装会把注入授予的特权权限清掉（M3 事实 4）。
     *
     * 设备版本比手上的包更新时不算需要装：那是降级，pm 会直接拒
     * （INSTALL_FAILED_VERSION_DOWNGRADE），硬装只会多一条红色日志。
     */
    static List<String> needInstall(Shell sh, Map<String, List<Item>> by, Reporter r) {
        return needInstall(sh, by, r, false);
    }

    /**
     * @param reinstall 同版本也照装。手动点了「安装」时为 true —— 用户挑好包再按下去，
     *                  要的就是覆盖装一遍，同版本跳过等于不执行他的指令；
     *                  一键全流程保持 false，那种场景下重装没有理由。
     *                  两个场景都挡降级：设备版本更高时 pm 只会回 VERSION_DOWNGRADE。
     */
    static List<String> needInstall(Shell sh, Map<String, List<Item>> by, Reporter r, boolean reinstall) {
        Map<String, Long> on = versions(sh);

        List<String> need = new ArrayList<>();
        for (String pkg : ORDER) {
            List<Item> g = by.get(pkg);
            if (g == null) continue;
            long want = g.get(0).versionCode;
            long have = on.containsKey(pkg) ? on.get(pkg) : 0L;

            if (have == 0) {
                r.line("  " + friendly(pkg) + "：设备上没有 → 要装", Reporter.INFO);
                need.add(pkg);
            } else if (have == want) {
                r.line("  " + friendly(pkg) + "：已是 v" + have
                        + (reinstall ? " → 覆盖重装" : "，跳过"), reinstall ? Reporter.INFO : Reporter.OK);
                if (reinstall) need.add(pkg);
            } else if (have > want) {
                r.line("  " + friendly(pkg) + "：设备上 v" + have + " 比手上的 v" + want
                        + " 新，跳过（降级装不上，要换新包）", Reporter.WARN);
            } else {
                r.line("  " + friendly(pkg) + "：v" + have + " → v" + want + " → 要装",
                        Reporter.INFO);
                need.add(pkg);
            }
        }
        return need;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 返回 true 表示三件套都装上了。 */
    static boolean install(Shell sh, Map<String, List<Item>> by, Reporter r) {
        return install(sh, by, r, null);
    }

    /**
     * only 非 null 时只装其中这几个包。
     *
     * 为什么需要子集：重装任何一个包都会让 PMS 重算它的权限，把注入授予的
     * MANAGE_USERS / INTERACT_ACROSS_USERS 清掉（M3 事实 4）。GSF 缺一个包时
     * 把两百多 MB 的 GMS 连带重装一遍，等于白白多丢一次权限、多等 40 秒。
     */
    static boolean install(Shell sh, Map<String, List<Item>> by, Reporter r, List<String> only) {
        r.line("阶段 2：装包", Reporter.STEP);

        long need = 0;
        for (String pkg : ORDER) {
            if (only != null && !only.contains(pkg)) continue;
            List<Item> g = by.get(pkg);
            if (g != null) need += total(g);
        }
        if (need == 0) {
            r.line("  没有要装的包", Reporter.OK);
            return true;
        }

        long avail = freeKb(sh, "/data/local/tmp");
        if (avail > 0 && avail * 1024 < need + (100L << 20)) {
            r.line("  /data 只剩 " + (avail / 1024) + " MB，装完这些要 " + mb(need)
                    + "（另需 ~100MB 余量）", Reporter.BAD);
            return false;
        }

        sh.exec("rm -rf " + TMP + " && mkdir -p " + TMP, 30);

        // 单包失败不中断整轮：GSF 坏了不该连累 GMS 和 Play，
        // 而且「哪几个成了、哪个没成」比「第一个失败就停」有用得多。
        int total = 0;
        List<String> bad = new ArrayList<>();
        for (String pkg : ORDER) {
            if (only != null && !only.contains(pkg)) continue;
            List<Item> g = by.get(pkg);
            if (g == null) return false;
            total++;

            r.line("", Reporter.INFO);
            r.line("── " + friendly(pkg) + " ──", Reporter.STEP);

            if (!allowInstall(sh, pkg, r) || !stage(sh, pkg, g, r)
                    || !pmInstall(sh, pkg, g, r) || !verify(sh, pkg, g, r)) {
                bad.add(friendly(pkg));
            }
        }

        sh.exec("rm -rf " + TMP, 30);
        r.line("", Reporter.INFO);
        lastFailed = bad.size();
        lastTotal = total;
        lastResult = (total - bad.size()) + "/" + total + " 装好"
                + (bad.isEmpty() ? "" : "（" + String.join("、", bad) + " 未装）");
        if (bad.isEmpty()) {
            r.line("装包这一段完成。", Reporter.OK);
        } else {
            r.line(bad.size() + " 个包没装上，看上面的具体原因。", Reporter.BAD);
        }
        return bad.isEmpty();
    }

    /** 上一轮 install() 的结果，给调用方拼一句人话用（不回读日志）。 */
    static String lastResult = "";
    static int lastFailed;
    static int lastTotal;

    /**
     * 写 StepOS 的「USB 安装」白名单。
     *
     * 格式是 `<标签>:<状态>`：1 允许 / -1 拒绝 / 0 或缺失走弹窗询问（15 秒后按拒绝）。
     * 标签是给系统那个弹窗显示的名字，已有就沿用，没有就用包名。
     */
    private static boolean allowInstall(Shell sh, String pkg, Reporter r) {
        String key = "usb_install_item_" + pkg;
        String cur = sh.exec("settings get secure " + key, 20).stdout();

        String label = pkg;
        if (!cur.isEmpty() && !"null".equals(cur)) {
            int c = cur.lastIndexOf(':');
            label = c > 0 ? cur.substring(0, c) : cur;
            if (cur.endsWith(":1")) {
                r.line("  白名单：已允许（" + cur + "）", Reporter.OK);
                return true;
            }
        }

        Shell.Exec put = sh.exec("settings put secure " + key + " "
                + Shell.q(label + ":1"), 20);
        String back = sh.exec("settings get secure " + key, 20).stdout();
        boolean ok = back.endsWith(":1");
        r.line("  白名单：" + (cur.isEmpty() || "null".equals(cur) ? "（原来没有）" : cur)
                        + " → " + back + (ok ? "" : " 【写失败】"),
                ok ? Reporter.OK : Reporter.BAD);
        if (!ok) {
            r.line("  没写进白名单，装包会卡 15 秒后被拒。put=" + put.text(), Reporter.BAD);
        }
        return ok;
    }

    /** 把文件灌到 /data/local/tmp/<pkg>/ 并核对字节数。 */
    private static boolean stage(Shell sh, String pkg, List<Item> g, Reporter r) {
        String dir = TMP + "/" + pkg;
        Shell.Exec mk = sh.exec("mkdir -p " + dir, 30);
        if (!mk.ok()) {
            r.line("  建目录失败：" + mk.describe(), Reporter.BAD);
            return false;
        }

        for (Item it : g) {
            String remote = dir + "/" + it.remoteName();
            long t0 = System.currentTimeMillis();
            Shell.Exec put;
            try (InputStream in = new FileInputStream(it.file)) {
                put = sh.put(in, remote, 300);
            } catch (IOException e) {
                r.line("  读不到本地文件：" + e, Reporter.BAD);
                return false;
            }
            long sec = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
            if (!put.ok()) {
                r.line("  写入 " + it.remoteName() + " 失败：" + put.describe(), Reporter.BAD);
                return false;
            }
            String sz = sh.exec("stat -c %s " + remote, 20).stdout();
            long got;
            try {
                got = Long.parseLong(sz.trim());
            } catch (NumberFormatException e) {
                got = -1;
            }
            if (got != it.size) {
                r.line("  字节数不对：" + it.remoteName() + " 本地 " + it.size
                        + " 远端 " + got, Reporter.BAD);
                return false;
            }
            r.line("  ↑ " + it.remoteName() + "  " + mb(it.size) + "  " + sec + "s",
                    Reporter.INFO);
        }
        return true;
    }

    private static boolean pmInstall(Shell sh, String pkg, List<Item> g, Reporter r) {
        String dir = TMP + "/" + pkg;
        StringBuilder cmd = new StringBuilder("pm install -r -t");
        for (Item it : g) cmd.append(' ').append(dir).append('/').append(it.remoteName());

        long t0 = System.currentTimeMillis();
        Shell.Exec e = sh.exec(cmd.toString(), 300);
        long sec = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
        String out = e.text();
        String tail = e.tail();

        if (out.contains("Success")) {
            r.line("  pm install：Success（" + sec + "s）", Reporter.OK);
            return true;
        }

        r.line("  pm install：" + (tail.isEmpty() ? e.describe() : tail)
                + "（" + sec + "s）", Reporter.BAD);
        if (tail.contains("USER_RESTRICTED")) {
            r.line("  → StepOS 的「USB 安装」闸门拒了。检查 "
                    + "settings get secure usb_install_item_" + pkg, Reporter.BAD);
        } else if (tail.contains("UPDATE_INCOMPATIBLE")) {
            // 实测这台机器上 GSF 就是这样：/data/app 里的文件自己也装不回去，
            // 因为包记录的签名身份和文件里的不一样（历史装过改签版本留下的）。
            r.line("  → 机器上已装的 " + friendly(pkg) + " 签名身份和这份 APK 不同，"
                    + "多半是以前装过改签版。换回当年那份 APK，或先卸载再装（会丢该包数据）。",
                    Reporter.BAD);
        } else if (tail.contains("VERSION_DOWNGRADE")) {
            r.line("  → 已装版本比所选 APK 新。`pm install -d` 只对 debuggable 包生效，"
                    + "降级得先卸载（会丢该包数据）。", Reporter.BAD);
        } else if (tail.contains("NO_MATCHING_ABIS")) {
            r.line("  → APK 的 ABI 跟这台机器（arm64-v8a）对不上。", Reporter.BAD);
        } else if (e.timedOut) {
            r.line("  → 超时。多半是白名单没生效、弹窗在等人点；先去通知栏看一眼。", Reporter.BAD);
        }
        return false;
    }

    private static boolean verify(Shell sh, String pkg, List<Item> g, Reporter r) {
        String paths = sh.exec("pm path " + pkg, 30).stdout();
        int lines = 0;
        for (String s : paths.split("\n")) if (s.trim().startsWith("package:")) lines++;

        String vc = sh.exec("dumpsys package " + pkg
                + " 2>/dev/null | grep -oE 'versionCode=[0-9]+' | head -1", 40).stdout();
        String en = sh.exec("dumpsys package " + pkg
                + " 2>/dev/null | grep -oE 'enabled=[0-9]' | head -1", 40).stdout();

        boolean ok = lines >= g.size();
        r.line("  核对：pm path " + lines + " 行 / 送了 " + g.size() + " 个文件"
                + "，" + (vc.isEmpty() ? "versionCode=?" : vc)
                + "，" + (en.isEmpty() ? "enabled=?" : en),
                ok ? Reporter.OK : Reporter.BAD);
        if (!ok) r.line("  → 少装了分片，PMS 可能拒了其中某个", Reporter.BAD);
        if ("enabled=2".equals(en)) {
            r.line("  → 包被禁用（2），开机禁用是 StepOS 的行为，注入之后再处理", Reporter.WARN);
        }
        return ok;
    }

    // -------------------------------- 工具 --------------------------------

    private static long freeKb(Shell sh, String path) {
        String out = sh.exec("df -k " + path + " 2>/dev/null | tail -1", 20).stdout();
        String[] f = out.trim().split("\\s+");
        try {
            return Long.parseLong(f[3]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static long total(List<Item> g) {
        long n = 0;
        for (Item it : g) n += it.size;
        return n;
    }

    static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static String head(String s) {
        return s.length() <= 8 ? s : s.substring(0, 8) + "…";
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private static byte[] readAll(InputStream in, int cap) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
            if (out.size() > cap) throw new IOException("manifest 大得离谱");
        }
        return out.toByteArray();
    }
}
