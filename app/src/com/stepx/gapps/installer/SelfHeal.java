package com.stepx.gapps.installer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * stepx-fix.sh 的 Java 移植。
 *
 * 为什么要移植而不是「把脚本 push 到 /sdcard 再 sh 它」：
 *   - 脚本跑起来只有一行行文本，UI 拿不到结构化状态，八项体检没法逐项上色；
 *   - 脚本里那段 80 秒的盲等，移植后可以改成轮询权限，通常 40–60 秒就能进下一步；
 *   - 用户在 app 里读完日志就能看到结论，不用再去翻终端。
 *
 * 但**语义必须和脚本一致**，四条要点，改任何一条都要同步改脚本：
 *   1. G 段（appops）故意不置 NEED —— appops 不触发 PMS 权限重算，不该白等一次重注入
 *   2. 失败分支不推进版本记账 —— 版本变了但没救回来时，下次要再强制一次
 *   3. 顺序固定：禁用包 → FLAG_SINGLE_USER → 权限 → appops → 重注入 → GMS 重启
 *   4. 退出码 0–6 含义不变，见下方常量
 *
 * 对应脚本：stepx-play-trio/scripts/stepx-fix.sh（那边是唯一事实来源）
 */
final class SelfHeal {

    static final String GSF = "com.google.android.gsf";
    static final String GMS = "com.google.android.gms";
    static final String PLAY = "com.android.vending";

    /** 和脚本共用同一个状态文件，两边谁先跑都认对方的记账。 */
    static final String STATE = "/sdcard/stepx-fix.state";
    static final String APKS = "/data/local/tmp/stepx-apks";

    static final int OK = 0;
    static final int E_PERM = 1;      // 权限没救回
    static final int E_GMS = 2;       // GMS 进程 / binder 没救回
    static final int E_DISABLED = 3;  // 包仍禁用
    static final int E_REBOOT = 4;    // 需重启
    static final int E_CERT = 5;      // 认证拦截页（只报不修）
    static final int E_FGS = 6;       // 下载服务 appop 没补上

    private static final Pattern STATE_RE =
            Pattern.compile("versions=gms:(\\d+)\\s+play:(\\d+)");

    private SelfHeal() { }

    // ------------------------------- 状态快照 -------------------------------

    static final class Status {
        String gsfState = "";
        String gmsState = "";
        String playState = "";
        long gmsVer;
        long playVer;
        boolean permPlay;
        boolean permGms;
        boolean gmsAlive;
        boolean playAlive;
        String gmsPid = "";
        String playPid = "";
        boolean fgsPlay;
        boolean fgsGms;
        boolean pwrPlay;
        boolean pwrGms;
        boolean suGate;
        int dead;
        int total;
        String cert = "";

        static boolean off(String s) {
            return "2".equals(s) || "3".equals(s) || "4".equals(s);
        }

        boolean gsfOff() { return off(gsfState); }
        boolean gmsOff() { return off(gmsState); }
        boolean playOff() { return off(playState); }
    }

    /**
     * 一次 shell 往返把所有状态取回来。
     * 拆成十几次 exec 会慢十倍（每次都要过一遍 binder + 起进程），而且中间态会互相打架。
     */
    static Status snapshot(Shell sh) {
        String script = String.join("\n",
                "GMS=" + GMS,
                "PLAY=" + PLAY,
                "GSF=" + GSF,
                "en(){ dumpsys package \"$1\" 2>/dev/null | grep -oE 'enabled=[0-9]' | head -1 | cut -d= -f2; }",
                "vc(){ dumpsys package \"$1\" 2>/dev/null | grep -oE 'versionCode=[0-9]+' | head -1 | cut -d= -f2; }",
                "echo GSF_ST=$(en $GSF)",
                "echo GMS_ST=$(en $GMS)",
                "echo PLAY_ST=$(en $PLAY)",
                "echo VER_GMS=$(vc $GMS)",
                "echo VER_PLAY=$(vc $PLAY)",
                "dumpsys package $PLAY 2>/dev/null | grep -q 'MANAGE_USERS: granted=true' && echo PERM_PLAY=1 || echo PERM_PLAY=0",
                "dumpsys package $GMS 2>/dev/null | grep -q 'MANAGE_USERS: granted=true' && echo PERM_GMS=1 || echo PERM_GMS=0",
                "echo GMS_PID=$(pidof $GMS 2>/dev/null | cut -d' ' -f1)",
                "echo PLAY_PID=$(pidof $PLAY 2>/dev/null | cut -d' ' -f1)",
                "appops get $PLAY ACTIVATE_VPN 2>/dev/null | grep -q 'ACTIVATE_VPN: allow' && echo FGS_PLAY=1 || echo FGS_PLAY=0",
                "appops get $GMS ACTIVATE_VPN 2>/dev/null | grep -q 'ACTIVATE_VPN: allow' && echo FGS_GMS=1 || echo FGS_GMS=0",
                "appops get $PLAY SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS 2>/dev/null | grep -q ': allow' && echo PWR_PLAY=1 || echo PWR_PLAY=0",
                "appops get $GMS SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS 2>/dev/null | grep -q ': allow' && echo PWR_GMS=1 || echo PWR_GMS=0",
                "P=$(dumpsys activity processes 2>/dev/null | grep 'ConnectionRecord.*com.google.android.gms')",
                "echo DEAD=$(echo \"$P\" | grep -c DEAD)",
                "echo TOTAL=$(echo \"$P\" | grep -c ConnectionRecord)",
                "echo CERT=$(dumpsys activity activities 2>/dev/null"
                        + " | grep -E 'topResumedActivity=|mResumedActivity:'"
                        + " | grep -oE '[A-Za-z0-9_.]*UncertifiedDeviceActivity' | head -1)");

        Status st = new Status();
        Shell.Exec e = sh.exec(script, 120);
        for (String ln : e.out.split("\n")) {
            int i = ln.indexOf('=');
            if (i <= 0) continue;
            String k = ln.substring(0, i).trim();
            String v = ln.substring(i + 1).trim();
            switch (k) {
                case "GSF_ST":  st.gsfState = v; break;
                case "GMS_ST":  st.gmsState = v; break;
                case "PLAY_ST": st.playState = v; break;
                case "VER_GMS": st.gmsVer = num(v); break;
                case "VER_PLAY": st.playVer = num(v); break;
                case "PERM_PLAY": st.permPlay = "1".equals(v); break;
                case "PERM_GMS":  st.permGms = "1".equals(v); break;
                case "GMS_PID":   st.gmsPid = v; st.gmsAlive = !v.isEmpty(); break;
                case "PLAY_PID":  st.playPid = v; st.playAlive = !v.isEmpty(); break;
                case "FGS_PLAY":  st.fgsPlay = "1".equals(v); break;
                case "FGS_GMS":   st.fgsGms = "1".equals(v); break;
                case "PWR_PLAY":  st.pwrPlay = "1".equals(v); break;
                case "PWR_GMS":   st.pwrGms = "1".equals(v); break;
                case "DEAD":      st.dead = (int) num(v); break;
                case "TOTAL":     st.total = (int) num(v); break;
                case "CERT":      st.cert = v; break;
                default: break;
            }
        }
        // logcat 全量 dump 很贵，只在 GMS 不在时才查（脚本也是这个条件）
        if (!st.gmsAlive) {
            st.suGate = "1".equals(sh.exec(
                    "logcat -d 2>/dev/null | grep -q FLAG_SINGLE_USER && echo 1 || echo 0", 45).stdout());
        }
        return st;
    }

    /** 十一项体检，措辞和脚本保持一致，方便两边对日志。 */
    static void show(Status st, Reporter r) {
        r.line("  GSF 启用  : " + (st.gsfOff() ? "被禁用（" + st.gsfState + "）" : "正常（" + st.gsfState + "）"),
                st.gsfOff() ? Reporter.BAD : Reporter.OK);
        r.line("  GMS 启用  : " + (st.gmsOff() ? "被禁用（" + st.gmsState + "）" : "正常（" + st.gmsState + "）"),
                st.gmsOff() ? Reporter.BAD : Reporter.OK);
        r.line("  Play 启用 : " + (st.playOff() ? "被禁用（" + st.playState + "）" : "正常（" + st.playState + "）"),
                st.playOff() ? Reporter.BAD : Reporter.OK);
        r.line("  Play 权限 : " + (st.permPlay ? "正常" : "缺失（MANAGE_USERS）"),
                st.permPlay ? Reporter.OK : Reporter.BAD);
        r.line("  GMS 权限  : " + (st.permGms ? "正常" : "缺失（MANAGE_USERS）"),
                st.permGms ? Reporter.OK : Reporter.BAD);
        r.line("  GMS 进程  : " + (st.gmsAlive ? "在（" + st.gmsPid + "）" : "不在"),
                st.gmsAlive ? Reporter.OK : Reporter.BAD);
        if (!st.gmsAlive && st.suGate) {
            r.line("  GMS 单用户: 被拦（FLAG_SINGLE_USER，见 README §8.7）", Reporter.BAD);
        }
        r.line("  Play 进程 : " + (st.playAlive ? "在（" + st.playPid + "）" : "不在"),
                st.playAlive ? Reporter.OK : Reporter.WARN);
        r.line("  下载服务  : " + (st.fgsPlay
                        ? "正常（ACTIVATE_VPN allow）"
                        : "会被拦（ACTIVATE_VPN 不是 allow，下载必失败）"),
                st.fgsPlay ? Reporter.OK : Reporter.BAD);
        r.line("  Play 认证 : " + (st.cert.isEmpty() ? "未被拦截" : "被拦截（" + st.cert + "）"),
                st.cert.isEmpty() ? Reporter.OK : Reporter.BAD);
        r.line("  GMS 服务  : " + (st.dead > 10
                        ? "异常（" + st.dead + "/" + st.total + " 条 binder 已死）"
                        : "正常（" + st.dead + " 条 dead，共 " + st.total + " 条连接）"),
                st.dead > 10 ? Reporter.BAD : Reporter.OK);
    }

    // ---------------------------- 该按哪个按钮 ----------------------------

    /**
     * 「重新注入」和「修复」不是同一件事，用户分不清很正常 ——
     * 注入是部署 payload 再重写特权权限 + 认证补丁；修复只补状态
     * （包被禁用、appops、GMS 进程），权限丢了才顺手重注入一次。
     *
     * 判定只认一条：特权权限还在不在。
     *   权限没了 → PMS 重算过，说明包被换过（装上新的、或 Play 自动更新），只能重注入
     *   权限还在 → 毛病都在运行时，重启/补 appops 就够
     *
     * 包被禁用排在权限前面：这时候重注入也白搭，修复会先回装再补权限。
     */
    static final int V_OK = 0;       // 不用动
    static final int V_INJECT = 1;   // 需重新注入
    static final int V_FIX = 2;      // 需修复

    static int verdict(Status st) {
        if (st == null) return V_OK;
        if (st.gsfOff() || st.gmsOff() || st.playOff()) return V_FIX;
        if (!st.permGms || !st.permPlay) return V_INJECT;
        if (!st.gmsAlive || st.dead > 10) return V_FIX;
        if (!st.fgsPlay) return V_FIX;
        return V_OK;
    }

    /** 面板右上角那五个字。 */
    static String verdictWord(int v) {
        switch (v) {
            case V_INJECT: return "需重新注入";
            case V_FIX:    return "需修复";
            default:       return "不用动";
        }
    }

    /** 一句话原因，进活动区时要能自己说清为什么。 */
    static String verdictWhy(Status st, int v) {
        if (st == null || v == V_OK) return "";
        if (v == V_INJECT) {
            if (!st.permGms && !st.permPlay) return "GMS 和 Play 的特权权限都没了";
            return !st.permGms ? "GMS 的特权权限没了" : "Play 的特权权限没了";
        }
        if (st.gsfOff() || st.gmsOff() || st.playOff()) return "有包被禁用";
        if (!st.gmsAlive) return "GMS 没在跑";
        if (st.dead > 10) return "GMS 的 binder 大面积失效";
        if (!st.fgsPlay) return "Play 的下载服务被拦";
        return "";
    }

    /** 该点哪个按钮 —— 和 verdict 一一对应，不要在这里另做判断。 */
    static String verdictHow(int v) {
        switch (v) {
            case V_INJECT: return "点「注入」";
            case V_FIX:    return "点「修复」";
            default:       return "";
        }
    }

    // --------------------------------- 主流程 --------------------------------

    static int repair(Shell sh, Gateway gw, Reporter r) {
        r.line("当前状态：", Reporter.STEP);
        Status st = snapshot(sh);
        show(st, r);

        // --- 版本记账：GMS / Play 一更新就无条件强制重注入 ---
        boolean force = false;
        long gv = st.gmsVer;
        long pv = st.playVer;
        String prev = readState(sh);
        if (gv <= 0 || pv <= 0) {
            r.line("读不到 versionCode（dumpsys 异常），本次跳过版本记账。", Reporter.WARN);
        } else if (prev == null) {
            r.line("首次运行：记下当前版本（GMS " + gv + " / Play " + pv + "），版本一变才自动强制。",
                    Reporter.INFO);
            writeState(sh, gv, pv);
        } else {
            long[] p = parseState(prev);
            if (p != null && (p[0] != gv || p[1] != pv)) {
                r.line("版本变化：GMS " + p[0] + " → " + gv + "，Play " + p[1] + " → " + pv, Reporter.WARN);
                r.line("→ 权限会被 PMS 重算，认证补丁也可能被服务端刷回，按强制重注入处理。",
                        Reporter.WARN);
                force = true;
            }
        }

        boolean fail = false;
        boolean need = force;

        // --- D. 包被禁用（重启后必现，必须先于权限处理）---
        if (st.gsfOff() || st.gmsOff() || st.playOff()) {
            if (!fixDisabled(sh, st, r)) fail = true;
            need = true;
            st = snapshot(sh);           // 装完包权限变了，后面几步要按新状态判断
        }

        // --- A′. FLAG_SINGLE_USER 门禁（必须在重注入之前）---
        if (!st.gmsAlive && st.suGate) {
            r.line("检测到 GMS 被 FLAG_SINGLE_USER 拦在 attach 阶段，补授 INTERACT_ACROSS_USERS...",
                    Reporter.WARN);
            Shell.Exec e = sh.exec("pm grant " + GMS + " android.permission.INTERACT_ACROSS_USERS", 60);
            r.line(e.ok() ? "  已授予" : "  授予失败：" + e.text(), e.ok() ? Reporter.OK : Reporter.BAD);
            need = true;
        }

        // --- A. 权限 ---
        if (!st.permPlay) {
            r.line("Play 权限缺失（MANAGE_USERS）—— 需要重注入", Reporter.BAD);
            need = true;
        }
        if (!st.permGms) {
            r.line("GMS 权限缺失（MANAGE_USERS）—— 需要重注入", Reporter.BAD);
            need = true;
        }

        // --- G. 前台服务类型权限 / 电池优化豁免（故意不置 need）---
        if (!fixAppOps(sh, PLAY, st.fgsPlay, st.pwrPlay, r)) fail = true;
        if (!fixAppOps(sh, GMS, st.fgsGms, st.pwrGms, r)) fail = true;

        // --- 重注入 ---
        if (need) {
            if (!recoverPerm(sh, gw, r)) fail = true;
            st = snapshot(sh);
        }

        // --- B/C. GMS 幽灵态 / binder 全死 ---
        if (!fail) {
            if (!st.gmsAlive) {
                r.line("GMS 进程不在，开始重启 GMS...", Reporter.STEP);
                gmsRestart(sh, r);
            } else if (st.dead > 10) {
                r.line("GMS binder 已大面积失效（" + st.dead + " 条 DEAD），开始重启 GMS...", Reporter.STEP);
                gmsRestart(sh, r);
            }
        }

        // --- 收尾判定（顺序和脚本一致）---
        r.line("最终状态：", Reporter.STEP);
        Status f = snapshot(sh);
        show(f, r);

        if (f.gsfOff() || f.gmsOff() || f.playOff()) {
            r.line("仍有包处于禁用态 —— 自动重置没成功。", Reporter.BAD);
            r.line("→ 进 设置 → 应用 → 对应应用 → 启用；或把同版本 APK 放进 " + APKS + "/ 再跑一次。",
                    Reporter.WARN);
            return E_DISABLED;
        }
        if (!f.permPlay) {
            r.line("Play 权限仍然缺失 —— 重注入没成功。再跑一次；仍失败则重启手机。", Reporter.BAD);
            return E_PERM;
        }
        if (!f.permGms) {
            r.line("GMS 权限仍然缺失 —— 重注入没成功。再跑一次；仍失败则重启手机。", Reporter.BAD);
            return E_PERM;
        }
        if (!f.fgsPlay) {
            r.line("Play 的 ACTIVATE_VPN 仍不是 allow —— 下载会继续失败（README §8.1.2）。", Reporter.BAD);
            r.line("→ 手动补一次：appops set " + PLAY + " ACTIVATE_VPN allow", Reporter.WARN);
            return E_FGS;
        }
        if (f.dead > 10) {
            r.line("GMS 的 binder 仍然大面积失效（" + f.dead + "/" + f.total + "）。", Reporter.BAD);
            r.line("→ 再跑一次；仍不行就重启手机。", Reporter.WARN);
            return E_GMS;
        }
        if (!f.gmsAlive) {
            if (f.suGate) {
                r.line("GMS 进程仍然不在。FLAG_SINGLE_USER 已补授，但 AMS 里那条 pid 非 0 的旧记录"
                        + "只能靠重启清掉。", Reporter.BAD);
                r.line("→ 请重启手机，解锁后立刻再跑一次。", Reporter.WARN);
                return E_REBOOT;
            }
            r.line("GMS 进程仍然不在。再跑一次；仍不行就重启手机。", Reporter.BAD);
            return E_GMS;
        }

        // 走到这里才推进版本记账；失败分支故意不记
        if (gv > 0 && pv > 0) writeState(sh, gv, pv);

        if (!f.cert.isEmpty()) {
            r.line("权限与进程都正常，但 Play 被认证拦截页挡住（" + f.cert + "）。", Reporter.BAD);
            r.line("→ 本地 flag 被刷回了，重注入治不了它；重跑 Google 兼容补丁的 play scope（README §8.8.10）。",
                    Reporter.WARN);
            return E_CERT;
        }

        r.line("完成：可以直接打开 Play 商店。", Reporter.OK);
        return OK;
    }

    // ------------------------------- 修复动作 -------------------------------

    private static boolean fixDisabled(Shell sh, Status st, Reporter r) {
        r.line("", Reporter.INFO);
        r.line("检测到包被 StepOS 禁用（开机自动写 enabled=2，lastDisabledCaller=android）", Reporter.WARN);
        boolean ok = true;
        boolean gsfRedone = false;

        // 顺序固定：GSF 先（它定义 READ_GSERVICES），再 GMS，最后 Play
        if (st.gsfOff()) {
            if (!fixPkg(sh, GSF, "GSF", r)) ok = false;
            gsfRedone = true;
        }
        if (st.gmsOff()) {
            if (!fixPkg(sh, GMS, "GMS", r)) ok = false;
        }
        // GSF 一重装，Play 的 READ_GSERVICES 就掉成 false，Play 起来会在
        // ClassicApplication 初始化阶段崩掉，所以只要动过 GSF，Play 就必须重装
        if (gsfRedone || st.playOff()) {
            if (!fixPkg(sh, PLAY, "Play", r)) ok = false;
        }
        return ok;
    }

    private static boolean fixPkg(Shell sh, String pkg, String name, Reporter r) {
        r.line("  " + name + " 被禁用（enabled=" + enabledState(sh, pkg) + "），回装自身 APK 重置",
                Reporter.INFO);
        sh.exec("am force-stop " + pkg, 40);
        sleep(2000);

        List<String> paths = pmPaths(sh, pkg);
        if (paths.isEmpty()) {
            r.line("    pm path 为空 —— 包已经被卸载了，需要手工装回去", Reporter.BAD);
            return false;
        }

        String out = sh.exec("pm install -r " + quotePaths(paths), 300).tail();
        r.line("    " + out, Reporter.INFO);
        if (!disabledPkg(sh, pkg)) return true;

        // 回装被签名校验拒绝：PMS 里记的签名和 APK 对不上，只能拷出来卸载重装
        r.line("    回装被拒（PMS 记录的签名与 APK 不符），改为 卸载 + 重装", Reporter.WARN);
        String dir = APKS + "/" + name;
        sh.exec("rm -rf " + dir + " && mkdir -p " + dir, 60);
        sh.exec("cp " + quotePaths(paths) + " " + dir + "/", 180);
        if (num(sh.exec("ls " + dir + "/*.apk 2>/dev/null | wc -l", 30).stdout()) == 0) {
            r.line("    拷贝 APK 失败", Reporter.BAD);
            return false;
        }

        sh.exec("pm uninstall " + pkg, 120);
        sleep(2000);
        // 卸载后 USB 安装白名单键会变成 *:0，不改成 1 会被 USER_RESTRICTED 挡住
        sh.exec("settings put secure usb_install_item_" + pkg + " '" + name + ":1'", 30);

        r.line("    " + sh.exec("pm install -r " + dir + "/*.apk", 300).tail(), Reporter.INFO);
        if (disabledPkg(sh, pkg)) {
            r.line("    仍然失败 —— 进 设置 → 应用 → " + name + " → 启用 手工收尾", Reporter.BAD);
            return false;
        }
        return true;
    }

    private static boolean fixAppOps(Shell sh, String pkg, boolean fgs, boolean pwr, Reporter r) {
        boolean ok = true;
        if (!fgs) {
            r.line(pkg + " 缺 ACTIVATE_VPN，下载服务会在 startForeground 处抛 SecurityException。补上...",
                    Reporter.WARN);
            sh.exec("appops set " + pkg + " ACTIVATE_VPN allow", 40);
            boolean now = opAllowed(sh, pkg, "ACTIVATE_VPN");
            r.line(now ? "  已授予 ACTIVATE_VPN" : "  授予失败", now ? Reporter.OK : Reporter.BAD);
            if (!now) ok = false;
        }
        if (!pwr) {
            sh.exec("appops set " + pkg + " SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS allow", 40);
            if (opAllowed(sh, pkg, "SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS")) {
                r.line("  已加电池优化豁免（" + pkg + "）", Reporter.OK);
            }
        }
        return ok;
    }

    /** D 类之外唯一的救法：让整个进程树重启，boot.py 会把 153 项特权权限重写一遍。 */
    private static boolean recoverPerm(Shell sh, Gateway gw, Reporter r) {
        if (!gw.reachable()) {
            r.line("错误：本机网关 127.0.0.1:18789 无响应。", Reporter.BAD);
            r.line("openclaw_env 可能已经不在了，这种情况只能重启手机。", Reporter.BAD);
            return false;
        }

        String oldPid = sh.exec("pgrep -f openclaw_env 2>/dev/null | head -1", 30).stdout();
        if (oldPid.isEmpty()) oldPid = "none";
        r.line("需要重注入（当前 PID: " + oldPid + "）...", Reporter.STEP);

        Gateway.Resp resp = gw.run(new String[]{"python3", Gateway.RECOVER}, 50000, 70);
        if (resp.ok()) {
            r.line("  网关：HTTP " + resp.code, Reporter.INFO);
        } else {
            // recover.py 会把 repair.py 甩到后台，然后进程树重启把网关连接一起掐掉。
            // 脚本里 curl 带 2>/dev/null，看到的也是同一种「失败」，属于预期。
            r.line("  网关连接被进程树重启掐断（预期副作用，不代表失败）", Reporter.INFO);
        }

        r.line("等待进程树重启…", Reporter.INFO);
        boolean restarted = false;
        String newPid = oldPid;
        for (int i = 0; i < 15 && !Thread.currentThread().isInterrupted(); i++) {
            sleep(3000);
            newPid = sh.exec("pgrep -f openclaw_env 2>/dev/null | head -1", 30).stdout();
            if (!newPid.isEmpty() && !newPid.equals(oldPid)) {
                restarted = true;
                break;
            }
        }
        if (!restarted) {
            r.line("进程树没有变化。请再跑一次；仍不行则重启手机。", Reporter.BAD);
            return false;
        }
        r.line("已重启（新 PID: " + newPid + "）", Reporter.OK);

        // 脚本在这里死等 80 秒。这里保留同样的量级，但改成边等边看：
        // recover.py 是把 repair.py 甩到后台跑的，网关连接被掐断后没有任何「注入完成」
        // 信号，所以只能靠时间兜底。权限早就在位也不能提前走 —— 那时 repair.py 可能
        // 正写到一半，紧接着做 GMS 重启会跟它打架。
        final long minDwellMs = 60_000L;
        long t0 = System.currentTimeMillis();
        r.line("注入中（至少等 60 秒，脚本原本是死等 80 秒）…", Reporter.INFO);
        for (int i = 1; i <= 24 && !Thread.currentThread().isInterrupted(); i++) {
            sleep(5000);
            long el = System.currentTimeMillis() - t0;
            boolean granted = permPlay(sh) && permGms(sh);
            if (granted && el >= minDwellMs) {
                r.line("注入完成（" + (el / 1000) + " 秒）", Reporter.OK);
                return true;
            }
            if (i % 4 == 0) {
                r.line("  已等 " + (el / 1000) + " 秒"
                        + (granted ? "（权限已在位，等注入收尾）" : "（权限未回）"), Reporter.INFO);
            }
        }
        r.line("注入等待 120 秒仍未收敛，交给最终判定。", Reporter.WARN);
        return true;
    }

    private static void gmsRestart(Shell sh, Reporter r) {
        r.line("  1/4 结束 GMS 进程（am crash）", Reporter.INFO);
        sh.exec("am crash " + GMS, 40);
        for (int i = 0; i < 6; i++) {
            sleep(1000);
            if (!gmsAlive(sh)) break;
        }
        if (gmsAlive(sh)) {
            r.line("      am crash 没效果，改用 force-stop", Reporter.WARN);
            sh.exec("am force-stop " + GMS, 40);
            sleep(2000);
        }

        // 关键一步：进程已死时 force-stop 才会清掉 AMS 里那条卡在旧 pid 上的
        // ProcessRecord，否则之后任何拉起都会被 "launching app became null" 挡住
        r.line("  2/4 清 AMS 幽灵记录（force-stop）", Reporter.INFO);
        sh.exec("am force-stop " + GMS, 40);
        sh.exec("cmd activity force-stop " + GMS, 40);
        sleep(2000);

        r.line("  3/4 抬高 standby bucket", Reporter.INFO);
        sh.exec("cmd activity set-standby-bucket " + GMS + " active", 40);

        r.line("  4/4 拉起 GMS", Reporter.INFO);
        sh.exec("am startservice -n " + GMS + "/.ads.identifier.service.AdvertisingIdService", 40);
        for (int i = 0; i < 8; i++) {
            sleep(2000);
            if (gmsAlive(sh)) break;
        }

        if (!gmsAlive(sh)) {
            r.line("      仍未起来，让 Play 去 bind 它", Reporter.WARN);
            restartPlay(sh);
            for (int i = 0; i < 10; i++) {
                sleep(2000);
                if (gmsAlive(sh)) break;
            }
        }

        // GMS 换了新进程，Play 手里还是旧 binder，必须让它重连
        if (gmsAlive(sh)) restartPlay(sh);
    }

    private static void restartPlay(Shell sh) {
        sh.exec("am force-stop " + PLAY, 40);
        sleep(3000);
        sh.exec("am start -n " + PLAY + "/com.google.android.finsky.activities.MainActivity", 40);
    }

    // --------------------------------- 小工具 --------------------------------

    static boolean gmsAlive(Shell sh) {
        return !sh.exec("pidof " + GMS + " 2>/dev/null", 20).stdout().isEmpty();
    }

    static boolean permPlay(Shell sh) {
        return "1".equals(sh.exec("dumpsys package " + PLAY
                + " 2>/dev/null | grep -q 'MANAGE_USERS: granted=true' && echo 1 || echo 0", 60).stdout());
    }

    /**
     * 为什么用 MANAGE_USERS 判 GMS，而不是 INTERACT_ACROSS_USERS：
     * 后者在本 ROM 上是默认授予的 —— 注入被 GMS 更新清掉之后它照样是 granted=true，
     * 于是面板显示「GMS·PERM 已授予」，而 GMS 其实缺了 118 个特权权限在崩溃循环里打转。
     * MANAGE_USERS 只有注入会给，一撤就掉，是唯一能反映「注入还在不在」的那个。
     */
    static boolean permGms(Shell sh) {
        return "1".equals(sh.exec("dumpsys package " + GMS
                + " 2>/dev/null | grep -q 'MANAGE_USERS: granted=true' && echo 1 || echo 0", 60).stdout());
    }

    private static boolean opAllowed(Shell sh, String pkg, String op) {
        return "1".equals(sh.exec("appops get " + pkg + " " + op
                + " 2>/dev/null | grep -q ': allow' && echo 1 || echo 0", 30).stdout());
    }

    private static String enabledState(Shell sh, String pkg) {
        return sh.exec("dumpsys package " + pkg
                + " 2>/dev/null | grep -oE 'enabled=[0-9]' | head -1 | cut -d= -f2", 60).stdout();
    }

    private static boolean disabledPkg(Shell sh, String pkg) {
        return Status.off(enabledState(sh, pkg));
    }

    private static List<String> pmPaths(Shell sh, String pkg) {
        List<String> out = new ArrayList<>();
        for (String ln : sh.exec("pm path " + pkg, 40).stdout().split("\n")) {
            String s = ln.trim();
            if (s.startsWith("package:")) s = s.substring("package:".length());
            if (!s.isEmpty() && s.endsWith(".apk")) out.add(s);
        }
        return out;
    }

    private static String quotePaths(List<String> paths) {
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('\'').append(p).append('\'');
        }
        return sb.toString();
    }

    private static String readState(Shell sh) {
        String s = sh.exec("cat " + STATE + " 2>/dev/null", 30).stdout();
        return s.isEmpty() ? null : s;
    }

    private static long[] parseState(String s) {
        Matcher m = STATE_RE.matcher(s);
        if (!m.find()) return null;
        try {
            return new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void writeState(Shell sh, long gv, long pv) {
        sh.exec("echo 'versions=gms:" + gv + " play:" + pv + "' > " + STATE, 30);
    }

    static long num(String s) {
        if (s == null) return 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
            else if (sb.length() > 0) break;
        }
        if (sb.length() == 0) return 0;
        try {
            return Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
