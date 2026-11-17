package com.stepx.gapps.installer;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证拦截修补（play scope）。
 *
 * 设备没通过 Google 的「保护机制认证」时，Play 会长出一个本地拦截页
 * （UncertifiedDeviceActivity），搜索、详情、结账一律先被它挡住。这个拦截是 Play
 * 自己的本地 flag 决定的，住在 Play 的私有目录里，跟 system_server 无关 ——
 * 所以「重新注入」治不了它，App 以前只能报出「被拦截」然后停在那儿。
 *
 * 要改它，前提是身份：Play 的私有目录只在它自己的 SELinux 域里可读写，而那个域只有
 * boot.py 的 child 拿得到（chroot 之后、exec 之前）。于是整条路固定成五步：
 *
 * <pre>
 *   停 Play → 布置标记 → 重启 openclaw 容器 → boot.py 切到 Play 身份
 *          → play-compat.py 改 cache + phenotype.db → 回读结果
 * </pre>
 *
 * 「停 Play」不是可选项：它把这份清单缓存在内存里，进程活着时会用自己的副本
 * 覆盖我们写下去的文件，改了等于没改。
 *
 * 这个动作只关掉本地拦截页。搜索、Play Pass、结账三个面是服务端判定，
 * 补丁救不了，日志里必须说清楚，别让人以为点了就「认证通过了」。
 */
final class CertFix {

    /** 容器重启 + 12 秒等待 + 打补丁，实测 40 秒左右；给到 150 秒容忍慢机。 */
    private static final int WAIT_SEC = 150;

    private CertFix() { }

    /** 只有真被拦了才给入口 —— 没被拦时点它没有意义。 */
    static boolean ready(SelfHeal.Status st) {
        return st != null && !st.cert.isEmpty();
    }

    static boolean run(Context ctx, Shell sh, Gateway gw, Reporter r) {
        return run(ctx, sh, gw, r, false);
    }

    /**
     * @param rollback true = 把上次补丁逆回去（载荷的 rollback 分支），
     *                 false = 清空拦截清单（patch 分支）
     */
    static boolean run(Context ctx, Shell sh, Gateway gw, Reporter r, boolean rollback) {
        if (rollback) {
            r.line("── 认证修补回滚 ──", Reporter.STEP);
            r.line("把 cache 与 phenotype.db 恢复成上次补丁前的样子；之后拦截页会回来。",
                    Reporter.INFO);
        } else {
            r.line("── 认证拦截修补 ──", Reporter.STEP);
            r.line("只关 Play 本地的拦截页；搜索、Play Pass、结账是服务端判定。", Reporter.INFO);
        }

        // 载荷得是最新的：修补走的是 boot.py 的 probe 通道，旧版 boot.py 没有这条分支
        Deploy.State st = Deploy.probe(ctx, gw);
        if (!st.ok()) {
            r.line("  注入链路不是这一版，先补一次部署", Reporter.INFO);
            if (!Deploy.run(ctx, sh, gw, r)) {
                r.line("部署没成 —— 修补依赖 boot.py 的 probe 通道，得先把它更新上来。", Reporter.BAD);
                return false;
            }
        }

        r.line("  停 Play（它会用内存副本把文件覆盖回去）", Reporter.INFO);
        sh.exec("am force-stop " + SelfHeal.PLAY, 30);
        sleep(1200);

        if (!Deploy.prepareStage(gw, r)) return false;
        for (String name : new String[]{Payload.ARM_COMPAT, Payload.READ_COMPAT}) {
            if (!Deploy.stage(gw, name, Payload.read(ctx, name), r)) return false;
        }
        if (!Deploy.stage(gw, "compat-mode",
                ((rollback ? "rollback" : "patch") + "\n").getBytes(StandardCharsets.UTF_8), r)) {
            return false;
        }

        r.line("  布置标记 · scope=play · payload=play-compat.py · mode="
                + (rollback ? "rollback" : "patch"), Reporter.INFO);
        String text = root(gw, Payload.ARM_COMPAT, 40);
        if (text == null || !text.contains("RESULT ok")) {
            r.line("布置失败：" + (text == null ? "没有拿到结果" : first(text)), Reporter.BAD);
            return false;
        }

        // 重启容器会让网关跟着断一下：这是预期，不是错误，所以只看返回码不判失败
        r.line("  重启容器开窗口（网关会断十几秒）", Reporter.INFO);
        gw.run(new String[]{"/bin/sh", "-c",
                "python3 " + Deploy.LIB + "/recover.py"}, 45000, 50);

        r.line("  等开机窗口执行（约 40 秒）", Reporter.INFO);
        String out = waitResult(gw);
        if (out == null) {
            r.line("等了 " + WAIT_SEC + " 秒还没拿到结果。", Reporter.BAD);
            r.line("→ 诊断里看 repair.log 与 last.json；" + (rollback
                    ? "回滚没跑完时补丁仍在，Play 表现不变。"
                    : "补丁没打上时 Play 仍是原样，不影响使用。"), Reporter.WARN);
            return false;
        }
        return report(r, out, rollback);
    }

    // ------------------------------- 内部 -------------------------------

    private static String waitResult(Gateway gw) {
        long deadline = System.currentTimeMillis() + WAIT_SEC * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) return null;
            sleep(3000);
            // 容器刚被 kill 时网关不在，连不上就再来一轮；连上了才值得花一次 apt 提权
            Gateway.Resp alive = gw.run(new String[]{"python3", "-c", "print(1)"}, 6000, 8);
            if (!alive.ok()) continue;
            String out = root(gw, Payload.READ_COMPAT, 45);
            if (out == null) continue;
            if (out.contains("RESULT ok") || out.contains("status failed")) return out;
        }
        return null;
    }

    /** 跑一个 root 侧脚本并等 out.txt：clearOut → apt 钩子 → 轮询读回。 */
    private static String root(Gateway gw, String script, int readSec) {
        Deploy.clearOut(gw);
        Gateway.Resp resp = gw.run(new String[]{"/bin/sh", "-c",
                "python3 " + Deploy.STAGE + "/" + Payload.RUNNER + " " + script}, 60000, readSec);
        if (!resp.ok()) return null;
        for (int i = 0; i < 12; i++) {
            if (Thread.currentThread().isInterrupted()) return null;
            String text = Deploy.readOut(gw);
            if (text != null && !text.trim().isEmpty()) return text;
            sleep(700);
        }
        return null;
    }

    /**
     * 把 read-compat.py 的摘要翻成人话。
     *
     * 摘要格式是每行一件事（见该脚本）：file / db / status / error。
     * 只认这几行，别的原样丢掉 —— 导出诊断里有全文，没必要在这里复述。
     */
    private static boolean report(Reporter r, String out, boolean rollback) {
        Map<String, String> kv = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String ln : out.split("\n")) {
            String s = ln.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("file ")) {
                String[] parts = s.split(" ");
                String name = parts.length > 1 ? parts[1] : "?";
                // 回滚侧看 state（restored / already），补丁侧看 dropped（0 = 本来就是空的）
                boolean restored = s.contains("state=restored");
                boolean already = s.contains("state=already");
                boolean dropped0 = s.contains("dropped=0");
                if (restored || (!already && !dropped0)) changed.add(name);
                else skipped.add(name);
                continue;
            }
            int i = s.indexOf(' ');
            if (i > 0) kv.put(s.substring(0, i), s.substring(i + 1).trim());
        }

        if (kv.containsKey("fail") || "False".equals(kv.get("report_ok"))) {
            String why = or(kv.get("report_error"), kv.get("fail"));
            if (rollback && why != null && why.contains("no journal")) {
                // 这是正常的「没有可退的东西」：从没打过补丁，或已经回过一次
                r.line("没有可回滚的记录 · 没打过补丁，或已经回滚过", Reporter.OK);
                return true;
            }
            r.line((rollback ? "回滚没跑成：" : "补丁没打上：") + why, Reporter.BAD);
            r.line(rollback
                    ? "→ 补丁仍在，Play 表现不变。要再试一次就再点一遍回滚。"
                    : "→ Play 仍是原样，不影响使用。要再试一次就再点一遍修补。", Reporter.WARN);
            return false;
        }

        if (rollback) {
            for (String name : changed) r.line("已还原 · " + name, Reporter.OK);
            for (String name : skipped) r.line("  本来就是原样 · " + name, Reporter.INFO);
            String db = kv.get("db");
            if (db != null && db.contains("reverted=True")) {
                r.line("phenotype.db · 已撤回上次写的那条 override", Reporter.OK);
            } else if (db != null && db.contains("reverted=False")) {
                r.line("  phenotype.db 没有要撤的（那条 override 已经不在了）", Reporter.INFO);
            }
            if (changed.isEmpty()) {
                r.line("清单本来就是原样 · 只撤了数据库那一侧", Reporter.OK);
            } else {
                r.line("回滚完了。重开 Play 会重新看到拦截页，这是原样。", Reporter.OK);
            }
            return true;
        }

        for (String name : changed) {
            r.line("改 cache · " + name, Reporter.OK);
        }
        for (String name : skipped) {
            r.line("  已是最空 · " + name, Reporter.INFO);
        }

        String db = kv.get("db");
        if (db != null) {
            if (db.contains("changed=True")) {
                r.line("写 phenotype.db · 绑定到当前已提交 state", Reporter.OK);
            } else if (db.contains("already=True")) {
                r.line("  phenotype.db 本来就是空的", Reporter.INFO);
            } else {
                r.line("  phenotype.db 没写成功：" + db, Reporter.WARN);
            }
        }

        boolean touched = !changed.isEmpty() || (db != null && db.contains("changed=True"));
        if (!touched) {
            // 幂等：文件已经是空的、数据库也已经挂上，这次什么都不用改。
            // 这不是失败，也不该说成「服务端判定」—— 它只是早就清过了。
            r.line("拦截清单本来就是空的 · 没有改动", Reporter.OK);
            return true;
        }
        r.line("改完了。现在打开 Play 商店看一眼。", Reporter.OK);
        r.line("→ 还弹拦截页的话：先完全退出 Play 再进（设置里强行停止），仍有就是服务端判定。",
                Reporter.WARN);
        return true;
    }

    private static String or(String a, String b) {
        return a != null && !a.isEmpty() && !"-".equals(a) ? a : String.valueOf(b);
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
}
