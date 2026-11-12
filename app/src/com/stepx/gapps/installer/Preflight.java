package com.stepx.gapps.installer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段 0：安全阀。
 *
 * inject.py 把三样东西写死在代码里 —— libc.so / libart.so 的 SHA-256，以及三个偏移
 * （libc+0x79170 / libc+0xd77c0 / libart+0x669a4c）。**固件一 OTA 就必然失配**，
 * 那时注入会在 system_server 里 assert 失败。所以装包之前先把这几样算出来比一遍。
 *
 * 为什么能算：shell 读不了 /proc/<system_server>/maps（Permission denied），
 * 但 system_server 映射的就是 apex 里那两份库，直接对文件算哈希即可（实测两边逐字相同）。
 */
final class Preflight {

    static final String LIBC_SHA = "76a754389a8242328cec7c1fed868289a27eb843d49175e1e0ada83f9b60f6da";
    static final String LIBART_SHA = "5d6a675fc9931595266623b7b682ec5c513128739f8194b64db289b3fddb0836";

    /** 已知能跑机型的 device 名。列表外不拦，只警告 —— 别人家的 StepX 可能换了代号。 */
    private static final String[] KNOWN_DEVICES = {"pandora"};

    private static final int SDK = 36;

    private Preflight() { }

    /** 返回 true 才允许进下一阶段。 */
    static boolean run(Shell sh, Reporter r, String selfPkg) {
        r.line("阶段 0：自检", Reporter.STEP);
        r.line("  后端：" + sh.name(), Reporter.INFO);

        boolean ok = true;

        String id = sh.exec("id", 20).stdout();
        boolean shell = id.contains("uid=2000");
        r.line("  身份：" + (id.isEmpty() ? "读不到" : id) , shell ? Reporter.OK : Reporter.BAD);
        if (!shell) {
            r.line("  → 不是 shell（uid 2000）。网关 token 要用 shell 才能读到的 android_id，"
                    + "没有它后面全停。", Reporter.BAD);
            return false;
        }

        Map<String, String> p = probe(sh);
        if (p.isEmpty()) {
            r.line("  固件探测失败", Reporter.BAD);
            return false;
        }

        String dev = p.get("DEV"), sdk = p.get("SDK"), abi = p.get("ABI");
        r.line("  机型：" + p.get("MODEL") + " / " + dev + " / Android " + p.get("SDK")
                + " / " + abi, Reporter.INFO);
        r.line("  固件：" + p.get("BUILD"), Reporter.INFO);
        r.line("  指纹：" + p.get("FP"), Reporter.INFO);

        if (!String.valueOf(SDK).equals(sdk)) {
            r.line("  → SDK " + sdk + "，注入链只覆盖 " + SDK, Reporter.BAD);
            ok = false;
        }
        if (!"arm64-v8a".equals(abi)) {
            r.line("  → ABI " + abi + "，偏移是按 arm64 算的", Reporter.BAD);
            ok = false;
        }
        boolean known = false;
        for (String d : KNOWN_DEVICES) if (d.equals(dev)) known = true;
        if (!known) {
            r.line("  → device=" + dev + " 不在已知列表里，注入偏移未在这台机器上验证过",
                    Reporter.WARN);
        }

        String libc = p.get("LIBC_SHA"), art = p.get("ART_SHA");
        ok &= hash(r, "libc.so", p.get("LIBC_PATH"), libc, LIBC_SHA);
        ok &= hash(r, "libart.so", p.get("ART_PATH"), art, LIBART_SHA);
        if (!ok) {
            r.line("  → 原生库对不上，注入必定在 system_server 里 assert 失败。"
                    + "固件 OTA 过就得重新定位三个偏移，别硬装。", Reporter.BAD);
        }

        ok &= gateway(sh, r);
        ok &= channel(sh, r);
        ok &= whitelistWritable(sh, r, selfPkg);
        return ok;
    }

    /**
     * 写通道：APK 必须落到 /data/local/tmp 才装得上（system_server 读不了 /sdcard），
     * 而 app 自己写不了那里 —— 只能把字节流灌进 shell 的 stdin。这条通道坏了，
     * 表现是「读包正常、安装步骤直接失败」，所以提前试一次。
     */
    private static boolean channel(Shell sh, Reporter r) {
        String path = "/data/local/tmp/stepx-channel.bin";
        final int size = 2 * 1024 * 1024;
        Shell.Exec e = sh.put(new java.io.ByteArrayInputStream(new byte[size]), path, 90);
        String got = sh.exec("stat -c %s " + path + " 2>/dev/null; rm -f " + path, 30).stdout();
        boolean ok = e.ok() && String.valueOf(size).equals(got.trim());
        r.line("  写通道：" + (ok ? "2MB 往返正常" : "失败 —— " + e.describe() + " / " + got),
                ok ? Reporter.OK : Reporter.BAD);
        return ok;
    }

    /**
     * StepOS 的「USB 安装」闸门对 `pm install` 一样生效（实测：允许=秒过、拒绝=秒失败、
     * 无键=卡 15 秒后失败），放行靠 `usb_install_item_<包名>`。这里只验证 shell 能不能写
     * secure settings —— 拿 app 自己的键试，写完读回来核对，原本没有的键写完删掉还原。
     */
    private static boolean whitelistWritable(Shell sh, Reporter r, String selfPkg) {
        // 用自己的包名当探针键：这条键本来就是我们自己安装时要写的那条，
        // 拿别人的键去测会在别人的安装记录上留痕。
        String key = "usb_install_item_" + selfPkg;
        String cur = sh.exec("settings get secure " + key, 20).stdout().trim();
        boolean existed = !cur.isEmpty() && !"null".equals(cur);
        String value = existed ? cur : "Installer:1";

        sh.exec("settings put secure " + key + " " + Shell.q(value), 20);
        String back = sh.exec("settings get secure " + key, 20).stdout().trim();
        boolean ok = value.equals(back);
        if (!existed) sh.exec("settings delete secure " + key, 20);

        r.line("  白名单写权限：" + (ok ? "可以（settings put secure 生效）"
                        : "不行 —— 装包会卡 15 秒后被 StepOS 拒"),
                ok ? Reporter.OK : Reporter.BAD);
        return ok;
    }

    private static boolean hash(Reporter r, String name, String path, String got, String want) {
        if (path == null || path.isEmpty()) {
            r.line("  " + name + "：找不到文件", Reporter.BAD);
            return false;
        }
        boolean same = want.equals(got);
        r.line("  " + name + "：" + (got == null || got.isEmpty() ? "算不出" : got),
                same ? Reporter.OK : Reporter.BAD);
        if (!same) {
            r.line("    期望 " + want, Reporter.INFO);
            r.line("    路径 " + path, Reporter.INFO);
        }
        return same;
    }

    private static boolean gateway(Shell sh, Reporter r) {
        String aid = sh.exec("settings get secure android_id", 20).stdout();
        if (aid.isEmpty() || "null".equals(aid)) {
            r.line("  android_id：读不到 —— shell 身份没生效", Reporter.BAD);
            return false;
        }
        r.line("  android_id：" + aid, Reporter.INFO);

        Gateway gw;
        try {
            gw = Gateway.from(sh);
        } catch (Throwable t) {
            r.line("  token：算不出来（" + t.getMessage() + "）", Reporter.BAD);
            return false;
        }
        r.line("  token：" + gw.token.substring(0, 8) + "…"
                + gw.token.substring(gw.token.length() - 8), Reporter.INFO);

        if (!gw.reachable()) {
            r.line("  网关：127.0.0.1:18789 无响应", Reporter.BAD);
            r.line("  → openclaw_env 没起来。装包还能做，注入做不了。", Reporter.WARN);
            return false;
        }
        Gateway.Resp echo = gw.run(new String[]{"echo", "GATEWAY_OK"}, 15000, 20);
        r.line("  gateway/echo：" + echo.summary(), echo.ok() ? Reporter.OK : Reporter.BAD);

        Gateway.Resp py = gw.run(new String[]{"python3", "-c", "print(2)"}, 15000, 20);
        boolean pyok = py.ok() && py.body.contains("2");
        r.line("  gateway/python3：" + py.summary(), pyok ? Reporter.OK : Reporter.BAD);
        return echo.ok() && pyok;
    }

    /** 一次往返把固件信息全取回来：十几次 exec 会慢十倍，而且中间态会互相打架。 */
    private static Map<String, String> probe(Shell sh) {
        String script = String.join("\n",
                "pick(){ for x in \"$@\"; do [ -f \"$x\" ] && { echo \"$x\"; return; }; done; echo; }",
                "L=$(pick /apex/com.android.runtime/lib64/bionic/libc.so"
                        + " /system/lib64/bionic/libc.so /system/lib64/libc.so)",
                "A=$(pick /apex/com.android.art/lib64/libart.so /system/lib64/libart.so)",
                "echo DEV=$(getprop ro.product.device)",
                "echo MODEL=$(getprop ro.product.model)",
                "echo SDK=$(getprop ro.build.version.sdk)",
                "echo ABI=$(getprop ro.product.cpu.abi)",
                "echo BUILD=$(getprop ro.build.display.id)",
                "echo FP=$(getprop ro.build.fingerprint)",
                "echo LIBC_PATH=$L",
                "echo LIBC_SHA=$([ -n \"$L\" ] && sha256sum \"$L\" | cut -d' ' -f1)",
                "echo ART_PATH=$A",
                "echo ART_SHA=$([ -n \"$A\" ] && sha256sum \"$A\" | cut -d' ' -f1)");

        Map<String, String> m = new LinkedHashMap<>();
        Shell.Exec e = sh.exec(script, 120);
        for (String ln : e.out.split("\n")) {
            int i = ln.indexOf('=');
            if (i <= 0) continue;
            m.put(ln.substring(0, i).trim(), ln.substring(i + 1).trim());
        }
        return m;
    }
}
