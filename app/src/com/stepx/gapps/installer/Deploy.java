package com.stepx.gapps.installer;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段 3：把注入链路部署到 StepX 的 OpenClaw 容器里。
 *
 * 落点（都在容器里，adb shell 看不见）：
 *   /usr/local/bin/ubuntu_zygote           ← boot-adapter.py 顶替原厂启动器
 *   /usr/local/bin/ubuntu_zygote.stepx-stock  ← 原厂备份（旧名 …-20261021 仍被认）
 *   /usr/local/lib/stepx-gms/{boot,inject,recover,repair}.py
 *   /var/lib/stepx-gms/                    ← 注入状态（last.json / lock）
 *
 * 权限模型（决定了这套代码的形状）：
 *   /usr/local/bin 属主 system(1000) → uid 1000 能替换适配器
 *   /usr/local/lib/stepx-gms 属主 root:root → **载荷必须过 root 才能写**
 *   而 uid 1000 唯一能提权的通道是 apt 的 Pre-Invoke 钩子（openclaw 的 sudo 只放行 apt）
 *
 * 所以流程是：uid 1000 把五个文件暂存到 /tmp/stepx-deploy（777，可写）→
 * 用一个 15 行的 runner 借 apt 钩子把 deploy.py 拉成 root → root 侧完成
 * 备份、原子替换、chown/chmod、写清单 → 结果写 out.txt 回传给 app。
 *
 * 为什么不让 uid 1000 直接建目录自己写：那样 /usr/local/lib/stepx-gms 归 uid 1000，
 * 而 boot.py 是 root 在开机时执行的 —— 等于留了一条本地提权路径。
 */
final class Deploy {

    static final String STAGE = "/tmp/stepx-deploy";
    static final String LIB = "/usr/local/lib/stepx-gms";
    static final String VAR = "/var/lib/stepx-gms";
    static final String BIN = "/usr/local/bin";
    static final String ZYGOTE = BIN + "/ubuntu_zygote";
    /**
     * 原厂启动器的备份槽位。
     *
     * 以前叫 `ubuntu_zygote.stepx-stock-20261021` —— 名字里那个日期是当年手写进去的，
     * 换一台固件不同的机器就变成了假信息（它本来只是个槽位名，跟备份的是哪天固件无关）。
     * 现在固定成不带日期的槽位；旧名保留为兼容路径，deploy.py 首次部署时会把旧的
     * rename 成新名，boot-adapter 两边都认。
     */
    static final String STOCK = BIN + "/ubuntu_zygote.stepx-stock";
    static final String STOCK_LEGACY = BIN + "/ubuntu_zygote.stepx-stock-20261021";
    static final String MANIFEST = LIB + "/.deployed.json";

    private Deploy() { }

    // ------------------------------- 只读探测 -------------------------------

    static final class State {
        boolean adapter;       // ubuntu_zygote 就是我们的适配器
        boolean stock;         // 原厂备份在
        /**
         * 备份里躺的其实是我们的适配器 —— 老 bug 留下的现场：适配器一更新，
         * sha 比较失配，deploy 就把旧适配器当成原厂件备份了。这种状态要判不健康，
         * 才会再跑一次部署，让 deploy.py 从 archive 里把真原厂件找回来。
         */
        boolean stockOurs;
        boolean libRoot;       // 目录属主是 root
        int matched;           // 清单里哈希对得上的文件数
        String note = "";

        boolean ok() {
            return adapter && stock && !stockOurs && libRoot
                    && matched == Payload.DEPLOY.length;
        }
    }

    private static final String PROBE = String.join("\n",
            "import hashlib, json, os",
            "LIB='" + LIB + "'",
            "def sha(p):",
            "    try:",
            "        h = hashlib.sha256()",
            "        f = open(p, 'rb')",
            "        while True:",
            "            c = f.read(1048576)",
            "            if not c: break",
            "            h.update(c)",
            "        f.close()",
            "        return h.hexdigest()",
            "    except OSError:",
            "        return '-'",
            "print('ZYGOTE=' + sha('" + ZYGOTE + "'))",
            // 新槽位优先，旧槽位（带去日期的老名字）也认 —— 已经部署过的机器不用重跑
            "STOCK='" + STOCK + "' if os.path.exists('" + STOCK + "') else '"
                    + STOCK_LEGACY + "'",
            "print('STOCK=' + ('1' if os.path.exists(STOCK) else '0'))",
            "print('STOCK_PATH=' + STOCK)",
            // STOCK 里躺着我们的适配器 = 备份被顶替过，得让它重新跑一次部署去修复
            "def _ours(p):",
            "    try:",
            "        return b'/usr/local/lib/stepx-gms' in open(p, 'rb').read(4096)",
            "    except OSError:",
            "        return False",
            "print('STOCK_OURS=' + ('1' if _ours(STOCK) else '0'))",
            "try:",
            "    print('LIB_UID=' + str(os.stat(LIB).st_uid))",
            "except OSError:",
            "    print('LIB_UID=-1')",
            "m = {}",
            "try:",
            "    m = (json.load(open('" + MANIFEST + "')) or {}).get('sha') or {}",
            "except Exception:",
            "    m = {}",
            "for k in " + names() + ":",
            "    print('SHA:' + k + '=' + str(m.get(k) or '-'))");

    private static String names() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Payload.DEPLOY.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append('\'').append(Payload.DEPLOY[i]).append('\'');
        }
        return sb.append(']').toString();
    }

    /** 只读：看看现在部署成什么样。不写任何东西。 */
    static State probe(Context ctx, Gateway gw) {
        State st = new State();
        Gateway.Resp resp = gw.run(new String[]{"python3", "-c", PROBE}, 20000, 30);
        if (!resp.ok()) {
            st.note = "探测失败：" + resp.summary();
            return st;
        }
        Map<String, String> kv = parse(stdout(resp));
        String adapter = Payload.sha256(Payload.read(ctx, "boot-adapter.py"));
        st.adapter = adapter.equals(kv.get("ZYGOTE"));
        st.stock = "1".equals(kv.get("STOCK"));
        st.stockOurs = "1".equals(kv.get("STOCK_OURS"));
        st.libRoot = "0".equals(kv.get("LIB_UID"));
        if (!st.libRoot && !"-1".equals(kv.get("LIB_UID"))) {
            st.note = LIB + " 属主是 uid " + kv.get("LIB_UID") + "（应为 root）";
        }
        for (String name : Payload.DEPLOY) {
            if (Payload.sha256(Payload.read(ctx, name)).equals(kv.get("SHA:" + name))) st.matched++;
        }
        return st;
    }

    // ------------------------------- 阶段 3 -------------------------------

    /** 幂等：已经一致就只做一次探测，不动设备。 */
    static boolean run(Context ctx, Shell sh, Gateway gw, Reporter r) {
        r.line("── 阶段 3：部署注入链路 ──", Reporter.STEP);

        Gateway.Resp py = gw.run(new String[]{"python3", "-V"}, 10000, 15);
        if (!py.ok()) {
            if (py.error != null) {
                // 连不上和「python3 起不来」是两件事，别混成一句，否则会去查错方向
                r.line("网关连不上：" + py.error, Reporter.BAD);
                r.line("→ 网关是唯一的提权入口（127.0.0.1:18789）。它不在时，"
                        + "等十几秒让 StepClaw 把它拉起来再试。", Reporter.WARN);
            } else {
                r.line("容器里的 python3 跑不起来：" + py.summary(), Reporter.BAD);
                r.line("→ openclaw 环境被改过。先在浏览器里打开 127.0.0.1:18789 看看网关还活着没。",
                        Reporter.WARN);
            }
            return false;
        }
        r.line("  容器 python：" + stdout(py).trim(), Reporter.INFO);

        State st = probe(ctx, gw);
        if (st.ok()) {
            r.line("已部署，且 " + Payload.DEPLOY.length + " 个载荷与 APK 内的一致 —— 不需要改动。",
                    Reporter.OK);
            return true;
        }
        if (!st.adapter) r.line("  ubuntu_zygote 还不是本适配器", Reporter.INFO);
        if (!st.stock) r.line("  原厂备份不在（" + STOCK + "）", Reporter.INFO);
        if (st.stockOurs) {
            r.line("  原厂备份里躺的是我们的适配器（老版本留下的现场）—— 会从 archive 找回原厂件",
                    Reporter.WARN);
        }
        if (st.matched < Payload.DEPLOY.length) {
            r.line("  载荷一致 " + st.matched + "/" + Payload.DEPLOY.length, Reporter.INFO);
        }
        if (!st.note.isEmpty()) r.line("  " + st.note, Reporter.WARN);

        if (!prepareStage(gw, r)) return false;

        for (String name : Payload.DEPLOY) {
            byte[] data = Payload.read(ctx, name);
            if (!stage(gw, name, data, r)) return false;
            r.line("  暂存 " + name + "（" + data.length + " 字节）", Reporter.INFO);
        }
        if (!stage(gw, Payload.DEPLOY_SCRIPT,
                Payload.read(ctx, Payload.DEPLOY_SCRIPT), r)) return false;
        if (!stage(gw, Payload.RUNNER, Payload.read(ctx, Payload.RUNNER), r)) return false;

        return runAsRoot(gw, Payload.DEPLOY_SCRIPT, Payload.RUNNER, r, "部署结果");
    }

    // ------------------------------- 阶段 5 -------------------------------

    /**
     * 阶段 5：核对注入记录。
     *
     * 判据来自 README §6.4：status=success、detached、target_pid 与当前 system_server 一致、
     * identity 与当前 boot_id 一致、java_result 的 check 行全部 =0。
     * 光看 dumpsys 两条权限不够 —— 权限在、但注入只做了一半的情况是存在的。
     */
    static boolean checkLast(Context ctx, Gateway gw, Shell sh, Reporter r, boolean fresh) {
        r.line("── 阶段 5：核对注入记录 ──", Reporter.STEP);

        if (!prepareStage(gw, r)) return false;
        if (!stage(gw, Payload.READ_LAST, Payload.read(ctx, Payload.READ_LAST), r)) return false;
        if (!runAsRoot(gw, Payload.READ_LAST, Payload.RUNNER, r, "读取记录")) return false;

        String bootId = sh.exec("cat /proc/sys/kernel/random/boot_id", 20).stdout().trim();
        String ssPid = sh.exec("pidof system_server", 20).stdout().trim();
        Map<String, String> kv = lastOut;

        // fresh=true：刚注入完，pid / boot_id 必须是这次的，对不上就是注入没落到当前进程
        // fresh=false：只是回看历史记录，对不上说明是上一次注入留下的，不算错
        boolean ok = true;
        ok &= expect(r, "status", kv.get("status"), "success", true);
        ok &= expect(r, "detached", kv.get("detached"), "true", true);
        ok &= expect(r, "target_pid", kv.get("target_pid"), ssPid, fresh);
        ok &= expect(r, "identity", shortId(kv.get("identity")), shortId(bootId), fresh);
        ok &= expect(r, "java_result[0]", kv.get("head1"), "GRANT_RESULT", true);
        ok &= expect(r, "java_result[1]", kv.get("head2"), "SYSTEM_GMS=true", true);

        String checks = kv.get("checks");
        String failed = kv.get("checks_failed");
        long n = num(checks);
        long bad = num(failed);
        boolean checksOk = n >= 120 && bad == 0;
        r.line("  check 行     : " + (checks == null ? "读不到" : n + " 条，失败 " + bad + " 条"),
                checksOk ? Reporter.OK : Reporter.BAD);
        ok &= checksOk;

        String err = kv.get("error");
        if (err != null && !"-".equals(err)) {
            r.line("  注入记录的 error 字段：" + err, Reporter.WARN);
        }
        return ok;
    }

    /** 上一次 root 脚本的键值输出，checkLast 要用。 */
    private static Map<String, String> lastOut = new LinkedHashMap<>();

    private static boolean expect(Reporter r, String label, String got, String want, boolean hard) {
        boolean ok = got != null && got.equals(want);
        String tail = ok ? "" : "（期望 " + want + "）";
        if (!ok && !hard) tail += " —— 记录是上一次注入的，不影响当前可用性";
        r.line(String.format("  %-13s: %s%s", label, got == null ? "读不到" : got, tail),
                ok ? Reporter.OK : (hard ? Reporter.BAD : Reporter.WARN));
        // 非 fresh 场景下 pid/boot 不匹配不算失败，返回 true 让调用方继续看权限
        return ok || !hard;
    }

    private static String shortId(String s) {
        if (s == null || s.length() < 8) return s;
        return s.substring(0, 8);
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------- 阶段 6 已删除 -------------------------------

    private static String short8(String s) {
        return s == null || s.length() < 8 ? String.valueOf(s) : s.substring(0, 8);
    }

    // ------------------------------- root 通道 -------------------------------

    /** 跑一个 root 脚本：deploy.py / read-last.py，两者都按 out.txt 回传结果。 */
    private static boolean runAsRoot(Gateway gw, String script, String runner, Reporter r,
                                    String what) {
        clearOut(gw);
        r.line("  以 root 执行 " + script + " …", Reporter.INFO);
        Gateway.Resp resp = gw.run(new String[]{"/bin/sh", "-c",
                "python3 " + STAGE + "/" + runner + " " + script}, 60000, 75);
        if (!resp.ok()) {
            r.line("  提权通道没通：" + resp.summary(), Reporter.BAD);
            return false;
        }
        String launched = stdout(resp).trim();
        if (launched.contains("LAUNCH-FAIL")) {
            r.line("  拉起失败：" + first(launched), Reporter.BAD);
            return false;
        }

        String text = null;
        for (int i = 0; i < 10; i++) {
            text = readOut(gw);
            if (text != null && !text.trim().isEmpty()) break;
            sleep(700);
        }
        if (text == null || text.trim().isEmpty()) {
            r.line("  root 脚本没有留下结果（out.txt 空）—— 钩子可能没被执行。", Reporter.BAD);
            r.line("  触发器输出：" + first(launched), Reporter.WARN);
            return false;
        }

        boolean ok = false;
        lastOut = new LinkedHashMap<>();
        for (String ln : text.split("\n")) {
            String s = ln.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("RESULT ")) {
                ok = s.endsWith("ok");
                continue;
            }
            if (s.startsWith("fail ")) {
                r.line("  " + s, Reporter.BAD);
                continue;
            }
            if (s.startsWith("warn ")) {
                r.line("  " + s, Reporter.WARN);
                continue;
            }
            if (s.startsWith("put  ") || s.startsWith("backup ") || s.startsWith("archive ")
                    || s.startsWith("mkdir ")) {
                r.line("  " + s, Reporter.OK);
                continue;
            }
            if (s.startsWith("skip ")) {
                r.line("  " + s, Reporter.INFO);
                continue;
            }
            int i = s.indexOf(' ');
            if (i > 0) lastOut.put(s.substring(0, i), s.substring(i + 1).trim());
        }
        r.line(ok ? "  " + what + "：成功" : "  " + what + "：失败",
                ok ? Reporter.OK : Reporter.BAD);
        return ok;
    }

    static boolean prepareStage(Gateway gw, Reporter r) {
        String code = String.join("\n",
                "import os",
                "p='" + STAGE + "'",
                "os.makedirs(p, exist_ok=True)",
                // 700：容器里 /tmp 是 777，别的 uid 不该能改我们即将让 root 执行的脚本
                "os.chmod(p, 0o700)",
                "print('ok')");
        Gateway.Resp resp = gw.run(new String[]{"python3", "-c", code}, 15000, 20);
        if (!resp.ok()) {
            r.line("建不了暂存目录 " + STAGE + "：" + resp.summary(), Reporter.BAD);
            return false;
        }
        return true;
    }

    static boolean stage(Gateway gw, String name, byte[] data, Reporter r) {
        String code = String.join("\n",
                "import base64, os",
                "p=" + py(STAGE + "/" + name),
                "b=base64.b64decode(" + py(Base64.getEncoder().encodeToString(data)) + ")",
                "t=p+'.stepx-new'",
                "fd=os.open(t, os.O_WRONLY|os.O_CREAT|os.O_TRUNC, 0o600)",
                "os.write(fd, b)",
                "os.close(fd)",
                "os.chmod(t, 0o600)",
                "os.replace(t, p)",
                "print('staged', len(b))");
        Gateway.Resp resp = gw.run(new String[]{"python3", "-c", code}, 40000, 60);
        if (!resp.ok() || !stdout(resp).contains(String.valueOf(data.length))) {
            r.line("  暂存 " + name + " 失败：" + resp.summary(), Reporter.BAD);
            return false;
        }
        return true;
    }

    static void clearOut(Gateway gw) {
        gw.run(new String[]{"python3", "-c",
                "import os\n"
                        + "try: os.unlink('" + STAGE + "/out.txt')\n"
                        + "except OSError: pass\n"
                        + "print('ok')"}, 10000, 15);
    }

    /** out.txt 走 base64 回传：省掉在 Java 里解析网关 JSON 的转义。 */
    static String readOut(Gateway gw) {
        Gateway.Resp resp = gw.run(new String[]{"python3", "-c",
                "import base64\n"
                        + "try:\n"
                        + "    print(base64.b64encode(open('" + STAGE + "/out.txt','rb').read()).decode())\n"
                        + "except OSError:\n"
                        + "    print('')"}, 15000, 20);
        if (!resp.ok()) return null;
        String b64 = stdout(resp).trim();
        if (b64.isEmpty()) return "";
        try {
            return new String(Base64.getMimeDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------- 小工具 -------------------------------

    private static String py(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static Map<String, String> parse(String out) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String ln : out.split("\n")) {
            int i = ln.indexOf('=');
            if (i <= 0) continue;
            kv.put(ln.substring(0, i).trim(), ln.substring(i + 1).trim());
        }
        return kv;
    }

    private static String first(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 取出网关响应里的 stdout。只处理这一种情况，不引 JSON 库 ——
     * 网关的返回形状固定，需要的是极少数几个字段，自己扫一遍比带个解析器省事。
     */
    static String stdout(Gateway.Resp resp) {
        if (resp == null || resp.body == null) return "";
        String key = "\"stdout\":\"";
        int i = resp.body.indexOf(key);
        if (i < 0) return "";
        int j = i + key.length();
        StringBuilder sb = new StringBuilder();
        while (j < resp.body.length()) {
            char c = resp.body.charAt(j);
            if (c == '\\' && j + 1 < resp.body.length()) {
                char n = resp.body.charAt(j + 1);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u':
                        if (j + 5 < resp.body.length()) {
                            try {
                                sb.append((char) Integer.parseInt(resp.body.substring(j + 2, j + 6), 16));
                            } catch (NumberFormatException ignored) {
                                // 转义坏了就当没这块，别把整条日志吞掉
                            }
                            j += 4;
                        }
                        break;
                    default: sb.append(n);
                }
                j += 2;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
            j++;
        }
        return sb.toString();
    }
}
