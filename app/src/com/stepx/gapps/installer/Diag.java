package com.stepx.gapps.installer;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 诊断面板：全量输出的唯一出口。
 *
 * 主界面只留最近几条活动，细节全在这里；默认不打开，所以不会挤占正常使用的视线。
 * 内容是可选中文本，复制和导出各给一个按钮 —— 导出走 SAF，app 不需要任何存储权限。
 */
final class Diag {

    interface Host {
        /** 取当前完整报告（含设备信息头 + 全量日志）。 */
        String report();

        /**
         * 需要 shell 才能读到的补充段（部署产物、网关、进程…）。
         * 采集可能要一两秒，所以是异步的：先让面板显示已有一半，采完再整篇替换。
         */
        void collect(java.util.function.Consumer<String> done);

        /** 发起 SAF 保存，具体实现由 Activity 做（它才收得到 result）。 */
        void export(String text);
    }

    private Diag() {}

    static void show(Activity a, Host host) {
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

        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(a, "诊断信息", 16, th.text, true));
        TextView sub = Ui.label(a, "FULL LOG / EXPORTABLE");
        sub.setPadding(0, Ui.dp(a, 4), 0, 0);
        titles.addView(sub);        head.addView(titles, new LinearLayout.LayoutParams(0,
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
        final TextView body = Ui.mono(a, host.report(), 10.5f, th.text, false);
        body.setTextIsSelectable(true);
        body.setLineSpacing(Ui.dp(a, 3), 1f);
        body.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 16));

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

        // 用户不知道这份东西该给谁、给了有没有用 —— 一句话说清用途
        TextView hint = Ui.text(a,
                "出问题把这整份发出去。",
                11, th.sub, false);
        hint.setLineSpacing(Ui.dp(a, 3), 1f);
        hint.setPadding(0, 0, 0, Ui.dp(a, 10));
        foot.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View copy = Ui.button(a, "复制全文", Ui.GHOST);
        View exp = Ui.button(a, "导出文件", Ui.PRIMARY);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("stepx-diag", host.report()));
            Toast.makeText(a, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
        exp.setOnClickListener(v -> {
            String text = host.report();
            dlg.dismiss();
            host.export(text);
        });
        foot.addView(Ui.row(a, Ui.dp(a, 8), copy, exp),
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

        // 打开就开采：面板先给已有的一半，shell 那段回来之后整篇替换。
        // 用 isShowing() 挡一下 —— 用户可能两秒内就关了面板。
        body.setText(host.report() + "\n\n（正在读设备实况：部署产物 / 网关 / 进程…）\n");
        host.collect(text -> {
            if (dlg.isShowing()) body.setText(text);
        });
    }

    private static View divider(Activity a, Ui.Theme th) {
        View v = new View(a);
        v.setBackgroundColor(th.line);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(a, 0.7f))));
        return v;
    }
}
