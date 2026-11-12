package com.stepx.gapps.installer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 关于：把「哪几条是 StepX Companion 的思路、哪几条是本项目自有」写在用户看得到的地方。
 *
 * 为什么要做成 App 内的一页而不是只在仓库文档里：这个包的注入载荷（inject.py / boot.py /
 * launcher.py）直接来自上游，上游是 AGPL-3.0-only。谁把 APK 转给别人，就该能在手机上看清
 * 来源和许可，而不是只能翻源码目录。
 *
 * 内容分三段：取自上游（逐条给出上游文件路径，可核对）／本项目自有／许可与边界。
 * 每条都写「上游怎么做、我们改了什么」，不写「参考了某某」这种对不上号的话。
 */
final class About {

    static final String REPO = "https://github.com/xydzf520/stepx";
    static final String SELF_REPO = "https://github.com/oXnMe/stepx-gapps-installer";
    static final String HANDOFF =
            "https://github.com/xydzf520/stepx/tree/main/handoff/google-play-stepx";

    private About() {}

    static void show(Activity a) {
        Ui.Theme th = Ui.T;

        Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout shell = new FrameLayout(a);
        shell.setPadding(Ui.dp(a, 14), Ui.dp(a, 28), Ui.dp(a, 14), Ui.dp(a, 28));

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(th.card, Ui.dp(a, 16)));

        // ---- 头 ----
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14));

        Glyph.Mark mark = new Glyph.Mark(a, th.brand, th.brand2);
        LinearLayout.LayoutParams mkLp = new LinearLayout.LayoutParams(Ui.dp(a, 26), Ui.dp(a, 26));
        mkLp.setMarginEnd(Ui.dp(a, 10));
        head.addView(mark, mkLp);

        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(a, "关于", 16, th.text, true));
        TextView sub = Ui.label(a, "PROVENANCE / SOURCES");
        sub.setPadding(0, Ui.dp(a, 4), 0, 0);
        titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = Ui.text(a, "\u2715", 15, th.sub, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));
        close.setClickable(true);
        close.setOnClickListener(v -> dlg.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));

        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(divider(a, th));

        // ---- 正文 ----
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 4));

        body.addView(Ui.mono(a, "StepX GApps 安装器  v" + verName(a), 12, th.text, true));
        body.addView(para(a, "在手机上装好 GSF / GMS / Play，再把特权权限注入 system_server。"
                + "重启后权限会掉，用「修复」补回。", th.sub, 6, 6));

        body.addView(para(a, "注入链路源自社区项目 github.com/xydzf520/stepx（AGPL-3.0-only）。"
                + "下面按来源分三类。", th.sub, 0, 4));

        // ---------- 一、文件级取自上游 ----------
        body.addView(section(a, "01  文件级取自上游"));
        body.addView(para(a, "一个原样搬入，两个在它上面改。", th.sub, 0, 8));

        TextView repo = link(a, REPO, th.brand);
        repo.setPadding(0, 0, 0, Ui.dp(a, 6));
        body.addView(repo);

        TextView base = Ui.mono(a, "路径基准  上游仓库根目录", 9.5f, th.sub, false);
        base.setPadding(0, 0, 0, Ui.dp(a, 12));
        body.addView(base);

        body.addView(item(a, "原样", th.ok, "注入本体",
                "ptrace 进 system_server 跑 DEX，改 PackageManager 的内存态权限位。整份搬入。",
                "module/inject.py · 30,229 B · sha256 27930528…", th));
        body.addView(item(a, "衍生", th.info, "开机启动适配",
                "接管 ubuntu_zygote：父进程 execv 原厂原件，子进程 setsid 后跑 boot.py。"
                        + "只改了三处：抽常量、补建状态目录、失败回原件。",
                "module/launcher.py → boot-adapter.py", th));
        body.addView(item(a, "衍生", th.info, "注入时序",
                "boot_id 去重 + flock + fork → chroot → 写 /proc/self/attr/current 到 "
                        + "system_server 域。只加了只读探测槽位。",
                "module/boot.py → boot.py", th));

        // ---------- 二、思路同源、实现自有 ----------
        body.addView(section(a, "02  只借思路，代码自己写"));
        body.addView(para(a, "上游有对应做法，这里是独立实现。标「对照」的是上游文件，不是搬过来的代码。",
                th.sub, 0, 8));

        body.addView(item(a, "思路", th.warn, "官方签名、不重签",
                "上游按 target.json 下 MindTheGapps 的 zip 取原件。重签会断掉 GMS 与 Play "
                        + "之间的签名互信。本项目的验签是自写的。",
                "对照 target.json", th));
        body.addView(item(a, "思路", th.warn, "只补缺项",
                "先读设备状态再出计划，已到位的不重装。逐包比 versionCode。",
                "对照 tools/install_recovery.py", th));
        body.addView(item(a, "思路", th.warn, "分开核验、未确认不算成功",
                "权限、进程、启动域、版本各算一项，未知状态不报成功。",
                "对照 tools/health.py", th));
        body.addView(item(a, "思路", th.warn, "版本记账",
                "/sdcard/stepx-fix.state 记 versionCode；部署侧用 .deployed.json 记 sha，"
                        + "OTA 后能看出哪件变了。",
                "对照 tools/package_state.py", th));
        body.addView(item(a, "手法不同", th.warn, "借固件自带的 root 通道",
                "上游走 RootBridge；本项目走网关 + openclaw sudo 拿 uid 0，再自己建目录。",
                "对照 tools/root_worker.py", th));

        // ---------- 三、完全自有 ----------
        body.addView(section(a, "03  完全自有"));
        body.addView(bullet(a, "手机侧独立 App + Shizuku 后端，不需要电脑。", th));
        body.addView(bullet(a, "七阶段编排、一键全流程、SelfHeal 自愈。", th));
        body.addView(bullet(a, "/sdcard/stepx-fix.sh 兜底脚本 · 走 shell（uid 2000），网关挂了也能跑。", th));
        body.addView(bullet(a, "APK 解析验签、split 分包安装、StepOS 白名单预写。", th));
        // 体积念的是下载量，构建时写进 assets/google.json，换了 release 就跟着变。
        Trio.Spec sp = Trio.spec(a);
        body.addView(bullet(a, "在线取三件套 · 只下 MindTheGapps 里用得上的三段"
                + (sp.ok() ? "（" + Installer.mb(sp.download) + "）" : "")
                + "，装完即清。", th));
        body.addView(bullet(a, "下载源里钉的是验证过的那三个版本，另附 MindTheGapps 整套入口。", th));
        body.addView(bullet(a, "12 格状态面板、诊断导出、明暗双主题。", th));

        // ============ 许可 ============
        body.addView(section(a, "04  许可与边界"));
        body.addView(para(a, "上游以 AGPL-3.0-only 发布。本应用内嵌其 inject.py、带有 boot.py / "
                + "launcher.py 的衍生文件，同样按 AGPL-3.0-only 对待；只自用、不对外分发时不涉及分发义务。",
                th.sub, 0, 8));
        body.addView(para(a, "与 STEPX、Amoo、Google 均无隶属关系。装好三件套不等于通过 "
                + "Google 设备认证。", th.sub, 0, 8));
        body.addView(para(a, "三件套版权归 Google。本应用只按原厂签名取用，不改包、不重签。",
                th.sub, 0, 8));
        body.addView(para(a, "注入改的是内存态，重启即失效；固件 OTA 后哈希与偏移会不匹配，自检会拦住。",
                th.sub, 0, 4));

        ScrollView sc = new ScrollView(a);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        card.addView(divider(a, th));

        // ---- 底 ----
        LinearLayout foot = new LinearLayout(a);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 14));

        // 右上角的 ✕ 已经能关，底部两个钮都指向仓库：左边上游（这一页在讲它的来源），
        // 右边本项目（来看来源的人多半也想知道更新与问题反馈去哪）。
        View up = Ui.button(a, "上游仓库", Ui.GHOST);
        View self = Ui.button(a, "本项目仓库", Ui.PRIMARY);
        up.setOnClickListener(v -> openUrl(a, REPO));
        self.setOnClickListener(v -> openUrl(a, SELF_REPO));
        foot.addView(Ui.row(a, Ui.dp(a, 8), up, self),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 46)));

        card.addView(foot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        shell.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dlg.setContentView(shell);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.5f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dlg.show();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    // ------------------------------ 排版小件 ------------------------------

    private static View section(Activity a, String s) {
        Ui.Theme th = Ui.T;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 6));

        // 分组标题带中文，不能套 Ui.label 的 0.09 字距 —— 中英混排时中文字会被拉开，
        // 看着像「一  ·  文件级取自上游」这种散架效果。等宽加粗 + 零字距更紧凑。
        TextView t = Ui.mono(a, s, 10, th.step, true);
        t.setLetterSpacing(0f);
        box.addView(t);
        return box;
    }

    /**
     * 一条来源：标签 + 标题 + 说明 + 上游路径（等宽小字）。
     *
     * 标签是这一页的重点：同样挂在「和上游有关系」下面，到底是原样搬的、只改几处的，
     * 还是只借了思路，必须一眼分得出来，否则「有出处」和「抄来的」就混成一句话。
     */
    private static View item(Activity a, String tag, int tagTone, String title, String body,
                             String src, Ui.Theme th) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, Ui.dp(a, 12));

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView chip = Ui.text(a, tag, 8.5f, tagTone, true);
        chip.setIncludeFontPadding(false);
        chip.setPadding(Ui.dp(a, 6), Ui.dp(a, 2), Ui.dp(a, 6), Ui.dp(a, 3));
        chip.setBackground(Ui.round((tagTone & 0x00FFFFFF) | 0x1C000000, Ui.dp(a, 4)));
        head.addView(chip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = Ui.text(a, title, 12.5f, th.text, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(Ui.dp(a, 7));
        head.addView(t, tlp);
        box.addView(head);

        TextView b = para(a, body, th.sub, 0, 0);
        b.setPadding(0, Ui.dp(a, 4), 0, 0);
        box.addView(b);

        if (src != null && !src.isEmpty()) {
            TextView s = Ui.mono(a, src, 9.5f, th.info, false);
            s.setLineSpacing(Ui.dp(a, 2), 1f);
            s.setPadding(0, Ui.dp(a, 4), 0, 0);
            box.addView(s);
        }
        return box;
    }

    private static View bullet(Activity a, String s, Ui.Theme th) {
        TextView t = para(a, "· " + s, th.sub, 0, 0);
        t.setPadding(0, 0, 0, Ui.dp(a, 6));
        return t;
    }

    private static TextView para(Activity a, String s, int color, int top, int bottom) {
        TextView t = Ui.text(a, s, 11.5f, color, false);
        t.setLineSpacing(Ui.dp(a, 3.5f), 1f);
        t.setPadding(0, Ui.dp(a, top), 0, Ui.dp(a, bottom));
        return t;
    }

    private static TextView link(Activity a, String url, int color) {
        TextView t = Ui.mono(a, url, 10, color, false);
        t.setLineSpacing(Ui.dp(a, 2), 1f);
        t.setClickable(true);
        t.setBackground(Ui.ripple(Ui.round(0x00000000, Ui.dp(a, 6)), Ui.T.ripple));
        t.setOnClickListener(v -> openUrl(a, url));
        return t;
    }

    private static void openUrl(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
            // 没有浏览器不是错误，界面上已经写了完整 URL
        }
    }

    private static String verName(Activity a) {
        try {
            PackageInfo p = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return p.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static View divider(Activity a, Ui.Theme th) {
        View v = new View(a);
        v.setBackgroundColor(th.line);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(a, 0.7f))));
        return v;
    }
}
