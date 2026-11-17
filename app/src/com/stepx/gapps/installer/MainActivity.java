package com.stepx.gapps.installer;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import rikka.shizuku.Shizuku;

/**
 * 一屏三块：状态网格 / 活动流 / 操作。
 *
 * 跟上一版的区别：
 * 1. 没有「自检」按钮了 —— 进页面就自动探一次，十二项状态直接铺在网格里。
 * 2. 运行日志只留最近四条活动，全文进「诊断」，默认不占视线，可复制可导出。
 * 3. 明暗两套主题，默认跟随系统；顶栏那个圆钮在 跟随/浅色/深色 之间切换。
 *
 * 仍然不用 AndroidX、不用资源 XML —— 配合 app/build.sh 那条无 Gradle 的构建链。
 */
public class MainActivity extends Activity implements Reporter, Diag.Host {

    private static final int REQ_SHIZUKU = 1;
    private static final int REQ_PICK = 2;
    private static final int REQ_EXPORT = 3;
    private static final String TAG = "STEPX";

    private static final String PREF = "stepx";
    private static final String KEY_THEME = "theme_mode";   // 0 跟随 1 浅 2 深
    /** 上次认证修补成功的时间戳（毫秒）。0 = 从没修补过。 */
    private static final String KEY_PATCH_AT = "patch_at";

    // 下载源里钉死的是「验证过的那三个版本」，不是各包的列表页 ——
    // 列表页让人自己从几十个变体里挑，挑错一个（arm64 变成 armeabi、nodpi 变成 480dpi）
    // 装上去就是 Play 闪退，而链接本身给不出任何提示。
    private static final String URL_GSF =
            "https://www.apkmirror.com/apk/google-inc/google-services-framework/"
                    + "google-services-framework-16-15304122-release/"
                    + "google-services-framework-16-15304122-android-apk-download/";
    private static final String URL_GMS =
            "https://www.apkmirror.com/apk/google-inc/google-play-services/"
                    + "google-play-services-26-44-63-release/"
                    + "google-play-services-26-44-63-260400-1016020991-2-android-apk-download/";
    private static final String URL_VENDING =
            "https://www.apkmirror.com/apk/google-inc/google-play-store/"
                    + "google-play-store-54-1-11-release/"
                    + "google-play-store-54-1-11-34-0-pr-1016840738-android-apk-download/";
    /** 整套 GApps：「在线获取」取的就是这个 release 里的三段，留个入口给想自己拆的人。 */
    private static final String URL_MTG =
            "https://github.com/MindTheGapps/16.0.0-arm64/releases/tag/"
                    + "MindTheGapps-16.0.0-arm64-20261021_084306";

    // 状态格：下标即布局顺序（三列，一行三个；见 statusGrid）
    private static final int C_SHIZUKU = 0;
    private static final int C_SHELL = 1;
    /**
     * 容器网关（127.0.0.1:18789）在不在。
     *
     * 排在第一行：它是部署、注入、认证修补三条路的唯一提权入口，容器一挂全都做不了，
     * 而 SHIZUKU/SHELL 这些格子照样全绿 —— 真机上就是这么被骗过一次（12/12 全正常）。
     * 放在 GApps 那几格之前，出问题第一眼就落在它身上。
     *
     * 这一格原来放的是「上次修补时间」，那个信息已经并进 CERT 那格了 ——
     * 修补状态本来就该跟着认证一起看。
     */
    private static final int C_GATEWAY = 2;
    private static final int C_GSF = 3;
    private static final int C_GMS = 4;
    private static final int C_PLAY = 5;
    private static final int C_PERM_PLAY = 6;
    private static final int C_PERM_GMS = 7;
    private static final int C_VPN = 8;
    private static final int C_BATT = 9;
    private static final int C_BINDER = 10;
    private static final int C_CERT = 11;

    private static final String[] CELLS = {
            "SHIZUKU", "SHELL", "GATEWAY", "GSF", "GMS", "PLAY",
            "PLAY·PERM", "GMS·PERM", "DL·VPN", "BATTERY", "BINDER", "CERT",
    };

    /** 状态格高度。和格子内部三行（标签/值/状态条）是绑定关系，只改一边会切字。 */
    private static final int CELL_H = 50;

    /**
     * 活动区的一行。只有「里程碑」进这里 —— 阶段开始、阶段结果、需要用户动手的提示。
     * 子系统的逐条输出（装包每个分片、部署每个文件、体十一项…）只进诊断。
     */
    private static final class Line {
        final String text;
        final int level;
        final String hm;
        /** 进度行的身份：同一个 key 在活动区里只占一行，后写覆盖前写。里程碑为 null。 */
        final String key;

        Line(String text, int level, String hm) {
            this(text, level, hm, null);
        }

        Line(String text, int level, String hm, String key) {
            this.text = text;
            this.level = level;
            this.hm = hm;
            this.key = key;
        }
    }

    /** 全量日志每行的级别字母：导出成文本后一眼分得出信息 / 警告 / 错误。 */
    private static final String[] LEVEL_TAG = {".", "+", "!", "x", "="};

    /** 一次状态探测的结果。没 shell 时只有 shell=false，其余为 null。 */
    private static final class Probe {
        boolean shell;
        /** 网关在不在。null = 还没探过（重建界面时重放用）。 */
        Boolean gw;
        SelfHeal.Status st;
        Map<String, Long> ver;
        long at;
        String err;
    }

    private Ui.Theme th;
    private SharedPreferences prefs;

    private TextView badge;
    private TextView statusSummary;
    private Glyph.Refresh refresh;
    private TextView activity;
    private TextView scanAt;
    private TextView scanSum;
    private final List<CellView> cells = new ArrayList<>();
    /** 每个状态格最后一次写入的内容，诊断里按原样输出（下标与 CELLS 对齐）。 */
    private final String[] cellDump = new String[CELLS.length];

    private View bFlow;
    private View bPick;
    private View bInst;
    private View bInject;
    private View bFix;
    private View bRoll;   // 回滚认证修补；原来这个位置是「下载源」，那份内容并进了选包窗口
    /** 只在 busy 时出现：卡住的时候得有地方点，不然只能杀进程。 */
    private TextView bStop;

    /** 活动区（里程碑）与全量日志走两个队列，活动区的内容是全量的严格子集。 */
    private final List<Line> hist = new ArrayList<>();
    private final StringBuilder full = new StringBuilder();
    /** 进度行上次写全量日志的时刻，用来节流 —— 逐块记会几秒刷几百行。 */
    private final Map<String, Long> tickAt = new java.util.HashMap<>();
    /** 量活动区高度用的替身 TextView（builder 里懒建，不挂到界面上）。 */
    private TextView probe;

    /**
     * 上次选的下载通道：true = 走 gh-proxy 中转。
     *
     * 只在内存里记，不落盘 —— 这是个「这次网络不行」的临时选择，不该变成
     * 下次进来的默认值（第三方反代，用户每次都该是有意识地选它）。
     */
    private boolean dlProxy;

    /** 子系统的逐条输出往这儿写：只落全量日志，不占活动区。 */
    private final Reporter quiet = (s, level) -> detail(s, level);

    /**
     * 介于两者之间的出口：带两格缩进的是进度明细（只进诊断，否则一次下载刷十几行），
     * 其余进活动区 —— 下 133MB 的时候不能让界面看起来像卡住。
     *
     * 进度行走 {@link #tick} 而不是 line：同一个包反复刷新同一行。
     */
    private final Reporter loud = new Reporter() {
        @Override
        public void line(String s, int level) {
            if (s.startsWith("  ")) detail(s, level);
            else MainActivity.this.line(s, level);
        }

        @Override
        public void tick(String key, String s, int level) {
            MainActivity.this.tick(key, s, level);
        }
    };

    /** 最近一条警告/错误的原文，给里程碑补一句「为什么失败」。阶段开始时清空。 */
    private String lastBad;

    /** 打开诊断时用 shell 现采的那一段（设备/部署/网关实况），采完才填。 */
    private volatile String diagExtra;

    private final long born = System.currentTimeMillis();

    private List<Installer.Item> items = new ArrayList<>();
    private Probe last;
    private String pendingExport;

    /** 启动时清掉的上一轮残留（字节），诊断里要能查到「这几百 MB 去哪了」。 */
    private long swept;

    /** 本轮 paint 里判为正常（绿）的格子数。 */
    private int goodCount;

    private volatile boolean busy;
    /** 当前任务线程：只给「停止等待」用，其余地方不读它。 */
    private volatile Thread taskThread;
    private volatile boolean probing;
    private boolean flowArmed;

    /** 计时行的身份。'\u0000' 打头，跟下载进度那些 key 不会撞。 */
    private static final String LIVE_KEY = "\u0000live";
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 当前阶段名（部署中 / 注入中 …），由各阶段自己写，计时行只负责把秒数拼上去。 */
    private volatile String livePhase = "运行中";
    private long liveStart;
    /** 正在跑的计时器；null 表示没有任务在跑。 */
    private Runnable liveTick;

    /** 本进程里是否已经自动弹过一次授权框。只防重复弹，手动点徽标不受它限制。 */
    private boolean askedGrant;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        th = theme();
        buildUi();

        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            refreshBadge();
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                line("Shizuku 授权通过", OK);
                probe();
            } else {
                // 拒过之后 requestPermission 不会再弹，只能手动开，所以把路径写清楚
                line("授权被拒 · 去 Shizuku「授权管理」里打开本应用", BAD);
                detail("Shizuku 授权被拒 · 装不了包", BAD);
            }
        });
        Shizuku.addBinderReceivedListenerSticky(() -> runOnUiThread(() -> {
            refreshBadge();
            probe();
        }));

        // 原来要点「自检」才探测。现在进页面就探，按钮省了。
        line("就绪 · 点「一键全流程」", INFO);
        sweepCaches();
        probe();
    }

    @Override
    protected void onDestroy() {
        // 计时行挂在主线程 Handler 上，页面走了必须摘掉，否则它会攥着这一屏的 View 不放。
        stopTicker();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        // 只有「跟随系统」才需要重建；固定浅/深色的不受系统影响。
        //
        // 这里必须用回调参数 cfg，不能用 getResources().getConfiguration()：
        // Activity 的 Resources 在这一刻还是旧值，读出来会得到切换前的模式，
        // 表现就是「系统切回浅色，app 还停在深色」（实测踩过）。
        if (themeMode() == 0) {
            boolean night = (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            Ui.Theme want = night ? Ui.Theme.dark() : Ui.Theme.light();
            if (want.dark != th.dark) {
                th = want;
                buildUi();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 兜底：有些系统改动不会走 onConfigurationChanged（比如从设置页返回），
        // 回到前台时再对一次账，避免一直停在旧主题上。
        if (themeMode() == 0) {
            boolean night = (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            if (night != th.dark) {
                th = night ? Ui.Theme.dark() : Ui.Theme.light();
                buildUi();
            }
        }
    }

    // ------------------------------ 主题 ------------------------------

    private int themeMode() {
        return prefs.getInt(KEY_THEME, 0);
    }

    private Ui.Theme theme() {
        int m = themeMode();
        if (m == 1) return Ui.Theme.light();
        if (m == 2) return Ui.Theme.dark();
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night ? Ui.Theme.dark() : Ui.Theme.light();
    }

    private void cycleTheme() {
        int next = (themeMode() + 1) % 3;
        prefs.edit().putInt(KEY_THEME, next).apply();
        th = theme();
        buildUi();
        Toast.makeText(this, next == 0 ? "跟随系统" : next == 1 ? "浅色" : "深色",
                Toast.LENGTH_SHORT).show();
    }

    private String themeName() {
        switch (themeMode()) {
            case 1:  return "浅色（固定）";
            case 2:  return "深色（固定）";
            default: return "跟随系统 → " + (th.dark ? "深色" : "浅色");
        }
    }

    // ------------------------------ 界面 ------------------------------

    private void buildUi() {
        Ui.T = th;

        final int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.backdrop(this));
        root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(pad, pad + bars.top, pad, pad + bars.bottom);
            return insets;
        });

        root.addView(header(), Ui.lp(dp(52), 0));
        root.addView(accentLine(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7)));

        // 状态区压缩成 3 列 × 4 行（原来是 2 列 × 6 行，白占 160dp）。
        // 高度固定、不吸剩余空间：多出来的地方全给日志卡，一屏才不会下半截空着。
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(0, dp(10), 0, 0);
        status.addView(sectionHead(), Ui.lp(dp(20), 0));
        status.addView(statusGrid(), Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(7)));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 日志卡吃掉状态区以下的全部剩余空间，按钮直接贴在它下面。
        //
        // 之前这里写死 200dp，再拿一个 weight=1 的空 View 把按钮顶到底 —— 结果
        // 日志框和主按钮之间横着一条一百多 dp 的空白带，看着像少了块东西。
        // 现在改成卡片自己长：多出来的高度进了日志区（能多显示几行），
        // 卡片有描边和头尾栏，内部留白读作内边距，而不是「中间一个洞」。
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.topMargin = dp(10);
        root.addView(activityCard(), cardLp);

        bFlow = Ui.button(this, "一键全流程", Ui.PRIMARY);
        bFlow.setOnClickListener(v -> startFlow());
        root.addView(bFlow, Ui.lp(dp(54), dp(10)));

        bPick = Ui.button(this, "选包", Ui.GHOST);
        bInst = Ui.button(this, "安装", Ui.GHOST);
        bInject = Ui.button(this, "注入", Ui.GHOST);
        bFix = Ui.button(this, "修复", Ui.GHOST);
        // 认证相关的两个动作（打补丁 / 回滚）放在同一个入口里 ——
        // 它们是一件事的两头，分两个按钮容易让人「补丁打坏了却找不到退路」。
        bRoll = Ui.button(this, "认证", Ui.GHOST);

        bPick.setOnClickListener(v -> {
            // 这里原来有一条「已经选齐了就直接跳选择器」的捷径，去掉了：
            // 点「选包」的意图经常是「换一套包」，直接甩进系统文件选择器，
            // 等于把「在线获取」这条更省事的路藏起来 —— 用户看到的是
            // 自己刚点了在线获取，再点选包就被弹到下载目录里翻文件。
            // 现在每次都先出弹窗，由用户在「去选包 / 在线获取」里自己挑。
            showPickTip(this::launchPicker, null);
        });
        bInst.setOnClickListener(v -> runTask("安装三件套", this::doInstall));
        bInject.setOnClickListener(v -> runTask("部署 + 注入", this::doInject));
        bFix.setOnClickListener(v -> runTask("一键修复", this::doFix));
        bRoll.setOnClickListener(v -> showCertSheet());

        root.addView(Ui.row(this, dp(8), bPick, bInst, bInject, bFix, bRoll),
                Ui.lp(dp(44), dp(8)));

        setContentView(root);
        applyBusy();
        renderActivity();
        // 重放上一次探测结果。announce=false：重建界面不该再往日志里写一条
        // 「状态已刷新」，否则每切一次主题就多一条噪音。
        if (last != null) paint(last, false);

        // edge-to-edge 下状态栏是透明的，图标颜色得自己挑 —— 深色主题用浅图标，
        // 否则白底黑字反过来，黑底上还是黑字，等于看不见。
        android.view.WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ic.setSystemBarsAppearance(th.dark ? 0 : mask, mask);
        }
    }

    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        Glyph.Mark mark = new Glyph.Mark(this, th.brand, th.brand2);
        LinearLayout.LayoutParams mkLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        mkLp.setMarginEnd(dp(10));
        bar.addView(mark, mkLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        // 长名字拆两行：一行放不下「StepX GApps 安装器」，硬塞会把右边的
        // 主题钮和连接徽标挤扁。上行说平台，下行说装什么。
        titles.addView(Ui.text(this, "StepX GApps", 18.5f, th.text, true));
        TextView sub = Ui.label(this, "三件套安装器 · v" + verName());
        sub.setPadding(0, dp(3), 0, 0);
        titles.addView(sub);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 主题钮：emoji 走系统的彩色字形，颜色由字体自带，所以 textColor 只是
        // 给没有彩色字体的机器兜底（那种机器上会退化成单色字形，颜色仍然正确）。
        TextView mode = Ui.text(this, modeEmoji(), 18, modeTint(), false);
        mode.setGravity(Gravity.CENTER);
        mode.setIncludeFontPadding(false);
        mode.setBackground(Ui.ripple(
                Ui.outline(th.cardAlt, dp(18), Math.max(1, dp(1)), th.line), th.ripple));
        mode.setClickable(true);
        mode.setOnClickListener(v -> cycleTheme());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(36), dp(36));
        mlp.setMarginEnd(dp(8));
        bar.addView(mode, mlp);

        badge = Ui.text(this, "\u25CF 待检测", 11.5f, th.sub, true);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(Ui.round(soft(th.sub), dp(20)));
        // 徽标本身就是「授权 / 连接状态」的唯一入口：没授权时点它弹授权框，
        // 已授权时点它等于刷新一次状态。比让用户去别处找按钮直观。
        badge.setClickable(true);
        badge.setBackground(Ui.ripple(Ui.round(soft(th.sub), dp(20)), th.ripple));
        badge.setOnClickListener(v -> {
            if (needsGrant()) {
                if (!askShizuku()) probe();
            } else {
                probe();
            }
        });
        bar.addView(badge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    /**
     * 当前主题档位对应的 emoji。
     *
     * 用转义而不是直接写字符：源码里放 emoji 会引入代理对，某些编辑器/构建编码下
     * 会被截断成半个字符（渲染成豆腐块）。U+1F317 = 🌗，U+1F319 = 🌙。
     */
    private String modeEmoji() {
        switch (themeMode()) {
            case 1:  return "\u2600\uFE0F";             // ☀️ 浅色
            case 2:  return "\uD83C\uDF19";             // 🌙 深色
            default: return "\uD83C\uDF17";             // 🌗 跟随
        }
    }

    /** 三种模式给三种色，emoji 万一退化成单色字形也还能分辨档位。 */
    private int modeTint() {
        switch (themeMode()) {
            case 1:  return th.dark ? 0xFFF5B53D : 0xFFB45309;   // 太阳：琥珀
            case 2:  return th.dark ? 0xFFA78BFA : 0xFF6D28D9;   // 月牙：紫
            default: return th.brand;                            // 跟随：主色
        }
    }

    /** 一条 2dp 渐隐线，左端带一颗发光节点 —— 代替常见的实心分隔条。 */
    private View accentLine() {
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);

        View bar = new View(this);
        bar.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{th.brand, th.brand2, th.brand & 0x00FFFFFF}));
        android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, dp(2)));
        blp.gravity = Gravity.CENTER_VERTICAL;
        blp.rightMargin = dp(28);
        box.addView(bar, blp);

        View node = new Glyph.Led(this, th.brand);
        android.widget.FrameLayout.LayoutParams nlp = new android.widget.FrameLayout.LayoutParams(
                dp(11), dp(11));
        nlp.gravity = Gravity.CENTER_VERTICAL;
        box.addView(node, nlp);
        return box;
    }

    private View sectionHead() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new Glyph.Led(this, th.brand);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.setMarginEnd(dp(7));
        r.addView(dot, dlp);

        r.addView(Ui.label(this, "STATUS"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusSummary = Ui.mono(this, "探测中…", 10, th.sub, false);
        r.addView(statusSummary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 手动刷新：探测是只读的，跑着也不影响别的动作，所以不跟着 busy 禁用。
        // 20dp 的图标 + 4dp 内缩的点击区，是这一行字里能给出的最大热区。
        refresh = new Glyph.Refresh(this, th.sub, 1.5f);
        refresh.setClickable(true);
        refresh.setBackground(Ui.ripple(Ui.round(0x00000000, dp(10)), th.ripple));
        refresh.setOnClickListener(v -> probe());
        // 切主题会重建这一整块，探测可能还在路上；重建后接着转，别让它看起来是静止的
        if (probing) refresh.spinning(true);
        // 视觉直径收到 17dp：原来 22dp 时它比「12/12 正常」高出快一倍，在这一行里像个纽扣。
        // 但视图仍给 20dp，多出来的 1.5dp 是内缩留白 —— 图形变小，手指不用跟着变准。
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(20), dp(20));
        rlp.setMarginStart(dp(4));
        r.addView(refresh, rlp);
        return r;
    }

    private View statusGrid() {
        cells.clear();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 3 列：12 项正好 4 行。每格 144dp 宽，足够放 "v264463035 · 在跑" 这种最长值，
        // 再窄就得省略号了 —— 状态值被截断比挤一点更糟。
        final int cols = 3;
        for (int i = 0; i < CELLS.length; i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < cols && i + j < CELLS.length; j++) {
                CellView cv = new CellView(CELLS[i + j]);
                cells.add(cv);
                // 十二格全是读数，一格都不可点。
                // CERT 那格原来能点开修补弹窗 —— 但「状态格可点」这件事本身没提示，
                // 用户不会去戳一个数字，而且把「被拦截」和「现在修补」混成了同一个动作。
                // 修补已经并进「修复」按钮的菜单，格子回到只报状态。
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(CELL_H), 1f);
                if (j > 0) lp.setMarginStart(dp(7));
                row.addView(cv.root, lp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(CELL_H));
            if (i > 0) rlp.topMargin = dp(8);
            box.addView(row, rlp);
        }
        return box;
    }

    private View activityCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        // 日志卡吸满剩余空间，但给个下限：小屏上不能把按钮挤出去，
        // 也不能把卡片压到只剩标题栏（那看起来像坏了）。
        card.setMinimumHeight(dp(96));
        card.setBackground(Ui.outline(th.card, dp(12), Math.max(1, dp(1)), th.line));
        card.setPadding(dp(12), dp(8), dp(12), dp(6));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.label(this, "ACTIVITY"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 关于：来源与许可写在用户看得到的地方。它和「诊断」是同一类东西（低频、
        // 跟主流程无关），所以并排放在卡片标题栏，不占底部那排动作按钮的位置。
        //
        // 垂直 padding 只给 3dp、关掉字体上下留白：11.5sp 在 520dpi 上连行高带内边距
        // 要 21dp 多，塞进 20dp 的行里会被切掉半截笔画（原来的「关于」底部就被切了）。
        TextView about = Ui.text(this, "关于", 11.5f, th.sub, false);
        about.setIncludeFontPadding(false);
        about.setPadding(dp(8), dp(3), dp(6), dp(3));
        about.setClickable(true);
        about.setBackground(Ui.ripple(Ui.round(0x00000000, dp(6)), th.ripple));
        about.setOnClickListener(v -> About.show(this));
        bar.addView(about, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 停止只在有任务时出现。放这一排而不是底部：底部那排会被 busy 整体禁掉，
        // 而这正是唯一需要它的时刻。
        bStop = Ui.text(this, "停止等待", 11.5f, th.warn, true);
        bStop.setIncludeFontPadding(false);
        bStop.setPadding(dp(8), dp(3), dp(6), dp(3));
        bStop.setClickable(true);
        bStop.setBackground(Ui.ripple(Ui.round(0x00000000, dp(6)), th.ripple));
        bStop.setOnClickListener(v -> stopWaiting());
        bStop.setVisibility(busy ? View.VISIBLE : View.GONE);
        bar.addView(bStop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView open = Ui.text(this, "诊断 \u203A", 11.5f, th.brand, true);
        open.setIncludeFontPadding(false);
        open.setPadding(dp(8), dp(3), dp(2), dp(3));
        open.setClickable(true);
        open.setOnClickListener(v -> Diag.show(this, this));
        bar.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(bar, Ui.lp(dp(24), 0));

        activity = Ui.mono(this, "", 11, th.text, false);
        activity.setLineSpacing(dp(2), 1f);
        // 关掉字体上下留白：量高的替身（probeView）也关着它，两边口径必须一致，
        // 否则量出来的比实际矮，最后一行又被切掉。
        activity.setIncludeFontPadding(false);
        activity.setEllipsize(TextUtils.TruncateAt.END);
        // 高度定下来之后才知道能放几行，所以布局一变就重排一次
        activity.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) renderActivity();
        });
        card.addView(activity, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底栏：日志区空着的时候，这一行让卡片看起来是「留白」而不是「没做完」。
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, dp(2), 0, 0);

        scanAt = Ui.mono(this, "LAST —", 9.5f, th.sub, false);
        foot.addView(scanAt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        scanSum = Ui.mono(this, "", 9.5f, th.sub, false);
        foot.addView(scanSum, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(foot, Ui.lp(dp(18), 0));
        return card;
    }

    /**
     * 一个状态格：LED 点 + 大写标签 + 状态词（标签行右侧）+ 版本号 + 底部状态条。
     *
     * 版本号和状态词分行放是有原因的：3 列布局下 "v264463035 · 在跑" 这种
     * 「长数字 + 两个中文字」拼在一起必然被省略号截断（中文字宽是英文的两倍），
     * 而用户真正要读的是后半段状态。拆开之后两边都不挤。
     */
    private final class CellView {
        final LinearLayout root;
        final Glyph.Led dot;
        final TextView note;
        final TextView value;
        final View strip;

        CellView(String label) {
            root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(8), dp(4), dp(8), dp(4));
            root.setBackground(Ui.outline(th.card, dp(8), Math.max(1, dp(1)), th.line));

            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            dot = new Glyph.Led(MainActivity.this, th.sub);
            top.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));

            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            llp.setMarginStart(dp(5));
            TextView lab = Ui.label(MainActivity.this, label);
            // 9.5sp 在 3 列里会把 "PLAY·PERM" 顶到格边，压到 9 才够
            lab.setTextSize(9f);
            // "SHIZUKU" + "已授权" 挤在同一行时会差几个 dp，末尾那个 U 被硬裁成半边
            // （singleLine 且没设 ellipsize 时是直接裁，不显示省略号）。
            // 长标签把字距收到 0.02，7 字符就能省出约 5dp；短标签不动，保持原来的呼吸感。
            if (label.length() >= 7) lab.setLetterSpacing(0.02f);
            top.addView(lab, llp);

            note = Ui.mono(MainActivity.this, "", 9f, th.sub, false);
            note.setSingleLine(true);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 标签本身有 weight=1，可它自己长的时候（SHIZUKU）会把状态词顶到边上，
            // 两者粘成一个词。留 5dp 是最低的区隔。
            nlp.setMarginStart(dp(5));
            top.addView(note, nlp);
            root.addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            value = Ui.mono(MainActivity.this, "—", 11f, th.text, false);
            value.setSingleLine(true);
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setPadding(0, dp(2), 0, 0);
            root.addView(value, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            strip = new View(MainActivity.this);
            strip.setBackground(Ui.round(th.line, dp(1)));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, dp(2)));
            slp.topMargin = dp(3);
            root.addView(strip, slp);
        }
    }

    private void setCell(int idx, int tone, String text) {
        setCell(idx, tone, text, null);
    }

    /**
     * @param note 标签行右侧的状态词（在跑 / 缺失 / 禁用…），null 表示这一格不需要
     */
    private void setCell(int idx, int tone, String text, String note) {
        if (idx < 0 || idx >= cells.size()) return;
        CellView cv = cells.get(idx);
        cv.dot.setColor(tone);
        cv.value.setText(text);
        cv.value.setTextColor(th.text);
        cv.note.setText(note == null ? "" : note);
        cv.note.setTextColor(tone);
        cv.strip.setBackground(Ui.round((tone & 0x00FFFFFF) | 0xCC000000, dp(1)));
        if (tone == th.ok) goodCount++;
        // 诊断里要能把这一屏原样读出来：界面上看到的和导出的是同一份判断
        cellDump[idx] = pad(CELLS[idx], 9) + pad(text, 13)
                + pad(note == null ? "" : note, 8) + toneWord(tone);
    }

    private String toneWord(int tone) {
        if (tone == th.ok) return "OK";
        if (tone == th.warn) return "WARN";
        if (tone == th.bad) return "BAD";
        return "-";
    }

    private void refreshBadge() {
        if (badge == null) return;
        String text;
        int tone;
        try {
            if (!Shizuku.pingBinder()) {
                text = "\u25CF 未连接";
                tone = th.bad;
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                text = "\u25CF 待授权";
                tone = th.warn;
            } else {
                text = "\u25CF 已连接";
                tone = th.ok;
            }
        } catch (Throwable t) {
            text = "\u25CF 不可用";
            tone = th.bad;
        }
        badge.setText(text);
        badge.setTextColor(tone);
        // 底色带 ripple：徽标可点（授权 / 刷新），没有按压反馈就像个纯装饰标签
        badge.setBackground(Ui.ripple(Ui.round(soft(tone), dp(20)), th.ripple));
    }

    /** Shizuku 在跑、但本应用还没被授权。 */
    private boolean needsGrant() {
        return pingBinder() && !granted();
    }

    private boolean pingBinder() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean granted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 没授权就主动弹一次系统授权框。
     *
     * 三种状态必须分开处理，否则用户看到的是「点了没反应」：
     *   服务没起        → 弹不出来，只写一行提示（别去 call requestPermission，它会抛）
     *   已经授权        → 什么都不做
     *   之前拒过一次    → Shizuku 的 requestPermission 会抛 IllegalStateException，
     *                     这时只能告诉用户去 Shizuku 里手动打开
     *
     * @return true 表示确实把授权框弹出来了
     */
    private boolean askShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                line("Shizuku 没在跑 · 先打开 Shizuku，再点这个徽标", WARN);
                return false;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return false;
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                line("授权弹不出来了 · 去 Shizuku「授权管理」里打开本应用", WARN);
                return false;
            }
            line("正在申请 Shizuku 授权 · 点「允许」后自动重探", INFO);
            Shizuku.requestPermission(REQ_SHIZUKU);
            return true;
        } catch (Throwable t) {
            line("授权框没弹出来 · " + t, WARN);
            return false;
        }
    }

    private void applyBusy() {
        if (bStop != null) bStop.setVisibility(busy ? View.VISIBLE : View.GONE);
        Ui.enabled(bFlow, !busy);
        Ui.enabled(bPick, !busy);
        Ui.enabled(bInject, !busy);
        Ui.enabled(bFix, !busy);
        Ui.enabled(bRoll, !busy);
        Ui.enabled(bInst, !busy && canInstall());
    }

    // ------------------------------ 状态探测 ------------------------------

    private void probe() {
        if (probing) return;
        probing = true;
        // 必须切回 UI 线程再动控件：probe() 大多是从 runTask 的后台线程里调的，
        // 直接 setText 会抛 CalledFromWrongThreadException，而且异常会打断方法，
        // probing 卡在 true，之后所有探测都被这一句拦掉（实测踩过）。
        runOnUiThread(() -> {
            if (statusSummary != null) statusSummary.setText("探测中…");
            if (refresh != null) refresh.spinning(true);
        });

        new Thread(() -> {
            Probe p = new Probe();
            // 网关和 shell 是两条独立的路：Shizuku 好好的、shell 也能用，容器却可能已经
            // 死了 —— 那时部署、注入、认证修补一律失效，而十二格里只有这一格会红。
            p.gw = Gateway.ping();
            try {
                Shell sh = Shell.pick();
                p.shell = sh.ready();
                if (p.shell) {
                    p.st = SelfHeal.snapshot(sh);
                    p.ver = Installer.versions(sh);
                }
            } catch (Throwable t) {
                p.err = String.valueOf(t);
            }
            p.at = System.currentTimeMillis();
            runOnUiThread(() -> {
                probing = false;
                if (refresh != null) refresh.spinning(false);
                last = p;
                paint(p, true);
                // 进页面发现「Shizuku 在跑但本应用没被授权」，主动弹一次授权框。
                // 只弹一次：用户拒绝之后 requestPermission 会抛，反复弹只会刷屏。
                if (!askedGrant && needsGrant()) {
                    askedGrant = true;
                    askShizuku();
                }
            });
        }, "stepx-probe").start();
    }

    /**
     * @param announce 是否往日志写「状态已刷新」。只有真的探测完才写，
     *                 重建界面时重放不写 —— 不然切一次主题就多一条重复记录。
     */
    /**
     * 网关那一格。三态：没探过 / 通 / 不通。
     *
     * 不写端口号：写出来除了让人去浏览器里试，没有别的用处；诊断里会写全。
     */
    private void paintGateway(Probe p) {
        if (p == null || p.gw == null) {
            setCell(C_GATEWAY, th.sub, "—");
        } else if (p.gw) {
            setCell(C_GATEWAY, th.ok, "可用");
        } else {
            setCell(C_GATEWAY, th.bad, "不通", "提权断");
        }
    }

    private void paint(Probe p, boolean announce) {
        goodCount = 0;
        refreshBadge();
        if (scanAt != null) {
            scanAt.setText(p.at > 0
                    ? "LAST " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(p.at))
                    : "LAST —");
        }

        try {
            if (!Shizuku.pingBinder()) {
                setCell(C_SHIZUKU, th.bad, "未运行");
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                setCell(C_SHIZUKU, th.warn, "v" + Shizuku.getVersion(), "未授权");
            } else {
                setCell(C_SHIZUKU, th.ok, "v" + Shizuku.getVersion(), "已授权");
            }
        } catch (Throwable t) {
            setCell(C_SHIZUKU, th.bad, "不可用");
        }

        // 网关独立于 shell，先画：容器挂掉的时候 Shizuku 和 shell 往往都是好的，
        // 只看「SHELL 可用」会以为一切正常（真机上就被骗过）。
        paintGateway(p);

        if (!p.shell) {
            setCell(C_SHELL, th.bad, "不可用");
            for (int i = C_GSF; i <= C_CERT; i++) setCell(i, th.sub, "—");
            // 「无 shell」把三种完全不同的处境糊成一句话，用户不知道该修哪一步。
            // 拆开说：服务没起 / 起来了没授权 / 授权了但后端仍不可用。
            boolean binder = pingBinder();
            boolean granted = binder && granted();
            String why = p.err != null ? "探测异常"
                    : !binder ? "Shizuku 未运行"
                    : !granted ? "Shizuku 待授权"
                    : "无 shell";
            if (statusSummary != null) {
                statusSummary.setText(why);
                statusSummary.setTextColor(p.err == null && binder && !granted
                        ? th.warn : th.bad);
            }
            if (scanSum != null) {
                scanSum.setText(!binder ? "NO SHIZUKU" : !granted ? "NEED GRANT" : "NO SHELL");
                scanSum.setTextColor(th.bad);
            }
            if (announce) {
                if (p.err != null) line("状态探测异常 · " + p.err, BAD);
                else if (!binder) line("Shizuku 没在跑 · 先打开 Shizuku 再回来", WARN);
                else if (!granted) line("Shizuku 待授权 · 点顶部徽标申请", WARN);
            }
            return;
        }

        setCell(C_SHELL, th.ok, "可用");

        SelfHeal.Status st = p.st;
        long gsf = val(p.ver, SelfHeal.GSF);
        long gms = val(p.ver, SelfHeal.GMS);
        long play = val(p.ver, SelfHeal.PLAY);

        setCell(C_GSF, st != null && st.gsfOff() ? th.bad : gsf > 0 ? th.ok : th.warn,
                gsf > 0 ? "v" + gsf : "未安装",
                gsf > 0 ? (st != null && st.gsfOff() ? "禁用" : "启用") : null);
        // GMS 和 Play 的进程语义不一样，不能共用一条判定：
        //   GMS 本该常驻。不在跑 = 谁 bind 它都得先起一遍，翻车了 Play 就用不了，
        //   所以「未运行」记黄，和十一项体检里的「GMS 进程」保持一致。
        //   Play 是界面进程，退到后台被回收是正常事，写「未运行」反而像故障，改「空闲」。
        boolean gmsUp = st != null && st.gmsAlive;
        boolean playUp = st != null && st.playAlive;
        setCell(C_GMS, st != null && st.gmsOff() ? th.bad : gms <= 0 ? th.warn : gmsUp ? th.ok : th.warn,
                gms > 0 ? "v" + gms : "未安装",
                gms > 0 ? (st != null && st.gmsOff() ? "禁用" : gmsUp ? "在跑" : "未运行") : null);
        setCell(C_PLAY, st != null && st.playOff() ? th.bad : play > 0 ? th.ok : th.warn,
                play > 0 ? "v" + play : "未安装",
                play > 0 ? (playUp ? "在跑" : "空闲") : null);

        // 上次打过补丁没有：只当 CERT 那格的注脚用，不再单独占一格。
        long patchAt = prefs == null ? 0L : prefs.getLong(KEY_PATCH_AT, 0L);

        if (st == null) {
            for (int i = C_PERM_PLAY; i <= C_CERT; i++) setCell(i, th.sub, "—");
        } else {
            setCell(C_PERM_PLAY, st.permPlay ? th.ok : th.bad, st.permPlay ? "已授予" : "缺失");
            setCell(C_PERM_GMS, st.permGms ? th.ok : th.bad, st.permGms ? "已授予" : "缺失");
            setCell(C_VPN, st.fgsPlay ? th.ok : th.warn, st.fgsPlay ? "已放行" : "未放行");
            setCell(C_BATT, st.pwrPlay && st.pwrGms ? th.ok : th.warn,
                    st.pwrPlay && st.pwrGms ? "已豁免" : "未豁免");
            // 分母是「此刻有多少个客户端绑着 GMS」，随 Play 起落而变 ——
            // 空闲时实测 24 秒内就在 4~16 之间跳，把它写在格子里会让人
            // 以为那是个应有值。健康时只念分子，分母挪到诊断里。
            if (st.dead == 0) {
                setCell(C_BINDER, th.ok, "0", "正常");
            } else {
                setCell(C_BINDER, st.dead > 10 ? th.bad : th.warn, st.dead + "/" + st.total, "dead");
            }
            // 修补状态并进 CERT：note 只说「打过没打过」，颜色和数值仍然由 Play
            // 此刻拦不拦决定 —— 不再各自派生「待修补/已修补」两套话，也就不会
            // 两格一起变红、看着像两处故障。
            setCell(C_CERT, st.cert.isEmpty() ? th.ok : th.bad,
                    st.cert.isEmpty() ? "未拦截" : "被拦截",
                    patchAt > 0 ? "已补" : "未补");
        }

        int total = cells.size();
        // 右上角除了计数，还要回答「那我现在该按哪个」。计数只说「有几格不对」，
        // 而这一屏真正的问题永远是「重新注入还是修复」—— 判定在 SelfHeal 里，
        // 这里只负责把它显示出来，不另做一套。
        int verdict = SelfHeal.verdict(st);
        if (statusSummary != null) {
            if (verdict == SelfHeal.V_OK) {
                statusSummary.setText(goodCount + "/" + total + " 正常");
                statusSummary.setTextColor(goodCount == total ? th.ok : th.warn);
            } else {
                statusSummary.setText(goodCount + "/" + total + " "
                        + SelfHeal.verdictWord(verdict));
                statusSummary.setTextColor(verdict == SelfHeal.V_INJECT ? th.bad : th.warn);
            }
        }
        if (scanSum != null) {
            scanSum.setText(goodCount == total ? "ALL OK" : (total - goodCount) + " FAIL");
            scanSum.setTextColor(goodCount == total ? th.ok : th.warn);
        }

        // 网关不通时上面那套计数会显得「只差一格」—— 实际是整条提权通道没了：
        // 部署、注入、认证修补一件都做不成，只有装包还能用。这时结论要盖过计数。
        boolean gwDown = Boolean.FALSE.equals(p.gw);
        if (gwDown) {
            if (statusSummary != null) {
                statusSummary.setText("网关不通 · 只能装包");
                statusSummary.setTextColor(th.bad);
            }
            if (scanSum != null) {
                scanSum.setText("NO GATEWAY");
                scanSum.setTextColor(th.bad);
            }
        }

        // 十二项体检全文只进诊断，不占活动区；同样只在真探测时写一次
        if (announce && st != null) SelfHeal.show(st, quiet);
        if (announce) {
            // 补丁时间挪出格子后还得能查到：它说明「这一台手工动过什么」
            detail("认证补丁 " + (patchAt > 0
                    ? fmt("yyyy-MM-dd HH:mm", patchAt) : "从没打过")
                    + " · 只关 Play 本地拦截页，搜索/结账仍是服务端判定",
                    patchAt > 0 ? OK : INFO);

            if (gwDown) {
                line("容器网关不通 · 部署 / 注入 / 认证修补都做不了（装包不受影响）", BAD);
                detail("网关 127.0.0.1:18789 无响应；它由 StepOS 的 openclaw 容器提供，"
                        + "容器没起来时本机没有任何提权入口", BAD);
            } else if (verdict == SelfHeal.V_OK) {
                line("状态 " + goodCount + "/" + total + " 全正常", OK);
            } else {
                // 「缺哪格」不写在这儿（格子自己会说），这句只回答下一步做什么。
                line(SelfHeal.verdictWhy(st, verdict) + " · " + SelfHeal.verdictHow(verdict),
                        verdict == SelfHeal.V_INJECT ? BAD : WARN);
            }
        }
    }

    private static long val(Map<String, Long> m, String k) {
        Long v = m == null ? null : m.get(k);
        return v == null ? 0L : v;
    }

    private String verName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------ 活动 / 诊断 ------------------------------

    /**
     * 里程碑：会出现在活动区的那些。只有「阶段开始 / 阶段结果 / 需要人动手」才配用。
     * 想写细节请用 {@link #detail}。
     */
    @Override
    public void line(String s, int level) {
        runOnUiThread(() -> log(s, level, true));
    }

    /** 细节：只落全量日志（诊断里能看到全部），不占活动区。可以在任意线程调。 */
    private void detail(String s, int level) {
        runOnUiThread(() -> log(s, level, false));
    }

    private void detail(String s) {
        detail(s, INFO);
    }

    /** 只在 UI 线程调用。全量日志每行带时间戳和级别字母，导出的文件自带时序。 */
    private void log(String s, int level, boolean milestone) {
        long now = System.currentTimeMillis();
        appendFull(s, level, now);
        Log.i(TAG, s);
        // STEP 是阶段标题，不是错误 —— 只有 WARN / BAD 才算「最近错误」
        if ((level == WARN || level == BAD) && !s.trim().isEmpty()) lastBad = s;
        if (milestone) {
            hist.add(new Line(s, level, fmt("HH:mm", now)));
            if (hist.size() > 600) hist.remove(0);
            renderActivity();
        }
    }

    private void appendFull(String s, int level, long now) {
        full.append('[').append(fmt("HH:mm:ss.SSS", now)).append("] ")
                .append(LEVEL_TAG[level]).append(' ').append(s).append('\n');
        if (full.length() > 600_000) full.delete(0, full.length() - 200_000);
    }

    /**
     * 同一行原地刷新（下载进度）。同一个 key 在活动区里始终只占一行，
     * 传 null 把这一行撤掉 —— 下载结束用，别让活动区停在一个 87% 的残影上。
     *
     * 全量日志按秒节流：133MB 逐块记下来是几百行噪音，一秒一条照样能回溯卡在哪段。
     */
    @Override
    public void tick(String key, String s, int level) {
        runOnUiThread(() -> {
            long now = System.currentTimeMillis();
            int at = -1;
            for (int i = hist.size() - 1; i >= 0; i--) {
                if (key.equals(hist.get(i).key)) {
                    at = i;
                    break;
                }
            }
            if (s == null) {
                tickAt.remove(key);
                if (at >= 0) {
                    hist.remove(at);
                    renderActivity();
                }
                return;
            }
            Line l = new Line(s, level, fmt("HH:mm", now), key);
            if (at >= 0) {
                // 一律挪到最末：中间插了里程碑之后，原地改会把计时行留在旧行上面，
                // 界面看起来就像「跑到一半停了」。进度行本来就该贴着底部走。
                hist.remove(at);
                hist.add(l);
            } else {
                hist.add(l);
                if (hist.size() > 600) hist.remove(0);
            }
            Long was = tickAt.get(key);
            if (was == null || now - was >= 1000) {
                tickAt.put(key, now);
                appendFull(s, level, now);
            }
            renderActivity();
        });
    }

    private void renderActivity() {
        if (activity == null) return;

        // 候选行（从旧到新）。上限 40 足够填满任何屏幕，再多的也放不下。
        List<Line> cand = new ArrayList<>();
        for (int i = hist.size() - 1; i >= 0 && cand.size() < 40; i--) {
            Line l = hist.get(i);
            if (l.text.trim().isEmpty()) continue;
            cand.add(0, l);
        }

        int avail = activity.getHeight()
                - activity.getPaddingTop() - activity.getPaddingBottom();
        int width = activity.getWidth()
                - activity.getPaddingLeft() - activity.getPaddingRight();

        // 放不下就把最旧的丢掉，一直到整块排得下。
        //
        // 之前的错在于「按逻辑行数估容量」：一条长行在界面上会被折成两行，
        // 估的时候却只当一行，于是总量必然超出，最后一行被拦腰切断 ——
        // 真机截图里 "状态 12/12 全正常" 只剩上半截笔画，就是这么来的。
        int keep = cand.size();
        SpannableStringBuilder sb = buildActivity(cand, 0);
        while (keep > 1) {
            if (width <= 0 || avail <= 0) break;          // 还没测量出来，先按全量铺
            sb = buildActivity(cand, cand.size() - keep);
            if (needHeight(sb, width) <= avail) break;
            keep--;
        }
        if (keep == 1) sb = buildActivity(cand, Math.max(0, cand.size() - 1));
        activity.setText(sb);
    }

    /**
     * 这段文字要占多高。
     *
     * 用一只不挂到界面上的同规格 TextView 去量，而不是自己拼 StaticLayout：
     * StaticLayout 的行高和 TextView 实际画出来的总有零点几 dp 的差，
     * 十来行攒下来正好把最后一行顶进「半行」区。真机上调了两次都对不上，
     * 索性不再两套口径比数 —— 让 TextView 自己排一遍，量它的结果。
     */
    private int needHeight(CharSequence text, int width) {
        if (text.length() == 0) return 0;
        TextView probe = probeView();
        probe.setText(text);
        probe.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return probe.getMeasuredHeight();
    }

    /** 量高用的替身：字号、字体、行距、字距全部跟活动区一致，只差没内容。 */
    private TextView probeView() {
        if (probe != null) return probe;
        probe = new TextView(this);
        probe.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, activity.getTextSize());
        probe.setTypeface(activity.getTypeface());
        probe.setTextScaleX(activity.getTextScaleX());
        probe.setLetterSpacing(activity.getLetterSpacing());
        probe.setLineSpacing(dp(2), 1f);
        probe.setIncludeFontPadding(false);
        probe.setPadding(0, 0, 0, 0);
        // 不挂到界面上的 TextView 没有 LayoutParams，setText 里 checkForRelayout()
        // 会直接 NPE（读 LayoutParams.width）。给一份占位的就行 —— 反正我们只读高度。
        probe.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return probe;
    }

    /** @param from 起始下标，比它更旧的候选行丢弃 */
    private SpannableStringBuilder buildActivity(List<Line> cand, int from) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = Math.max(0, from); i < cand.size(); i++) {
            Line l = cand.get(i);
            // 行间用分隔符，末尾不留 —— 结尾那个 '\n' 会多排出一个空行，
            // 白占一行的位置，还容易把最后一行顶出去。
            if (sb.length() > 0) sb.append('\n');
            int st = sb.length();
            sb.append(l.hm).append(' ').append(clip(l.text));
            sb.setSpan(new ForegroundColorSpan(th.tone(l.level)), st, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private static String clip(String s) {
        String t = s.trim();
        // 40 → 37：行首多了 "10:31 " 五个字符，不缩一点最后两个字会被挤掉
        return t.length() > 37 ? t.substring(0, 36) + "…" : t;
    }

    /**
     * 一个阶段收尾时用：`装包 4.2s · 跳过（版本都对）`。
     *
     * 耗时写进去是有用的 —— 远程判断「卡住」和「就是慢」全靠它，
     * 而活动区又只给几行，把时间塞进这一行比另起一行划算。
     */
    private void say(String what, long since, String result, int level) {
        line(what + " " + dur(System.currentTimeMillis() - since) + " · " + result, level);
    }

    /** 最近一条警告/错误的片段，接在失败的里程碑后面，免得活动区只报「失败了」。 */
    private String why() {
        String s = lastBad == null ? "" : lastBad.replace('\n', ' ').trim();
        if (s.isEmpty()) return "";
        if (s.length() > 26) s = s.substring(0, 25) + "…";
        return " · " + s;
    }

    private static String dur(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format(Locale.US, "%.1fs", ms / 1000.0);
        return (ms / 60_000) + "m" + String.format(Locale.US, "%02ds", (ms / 1000) % 60);
    }

    private static String fmt(String pattern, long at) {
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(at));
    }

    private static String pad(String s, int w) {
        StringBuilder b = new StringBuilder(s == null ? "" : s);
        while (b.length() < w) b.append(' ');
        return b.append(' ').toString();
    }

    private String verCode() {
        try {
            return String.valueOf(getPackageManager()
                    .getPackageInfo(getPackageName(), 0).getLongVersionCode());
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------ 诊断报告 ------------------------------

    /**
     * 报告的目标读者是「隔着屏幕的我」：新设备上装完出问题，用户把这份文本发过来，
     * 我不用再追问机型、Shizuku 版本、哪个包没装上、卡在哪一步。
     *
     * 所以分三层：
     *   结论   —— 一眼看出整机状态和缺的东西
     *   事实   —— 应用/设备/Shizuku/shell/部署产物/已选包，全部结构化，不靠日志猜
     *   过程   —— 里程碑 + 带时间戳的全量日志
     */
    @Override
    public String report() {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder(32_768);

        b.append("StepX GApps 安装器 · 诊断报告\n");
        b.append("生成 ").append(fmt("yyyy-MM-dd HH:mm:ss", now))
                .append("    运行 ").append(dur(now - born))
                .append("    日志 ").append(countLines(full)).append(" 行\n");

        // ---- 结论 ----
        b.append("\n==== 结论 ====\n");
        b.append("状态     ").append(stateWord()).append('\n');
        b.append("上次探测 ").append(last == null || last.at <= 0 ? "未探测"
                : fmt("HH:mm:ss", last.at) + "（" + dur(Math.max(0, now - last.at)) + "前）")
                .append('\n');
        b.append("三件套   ").append(pkgWord()).append('\n');
        b.append("缺包     ").append(missWord()).append('\n');
        // 报告是给别人看的：他第一眼要知道「这机器该按哪个按钮」，而不是自己去读
        // 十二格和十一项。
        int vd = last == null ? SelfHeal.V_OK : SelfHeal.verdict(last.st);
        boolean gwDead = last != null && Boolean.FALSE.equals(last.gw);
        b.append("下一步   ").append(gwDead ? "先恢复容器（网关不通，部署/注入/修补都做不了）"
                : vd == SelfHeal.V_OK ? "不用动"
                : SelfHeal.verdictWord(vd) + "（" + SelfHeal.verdictHow(vd) + "）").append('\n');
        b.append("已选     ").append(items.isEmpty() ? "0 个文件" : items.size() + " 个文件").append('\n');
        b.append("最近错误 ").append(lastBad == null ? "无" : lastBad).append('\n');

        // ---- 应用 ----
        b.append("\n==== 应用 ====\n");
        b.append("包名     ").append(getPackageName()).append('\n');
        b.append("版本     ").append(verName()).append(" (versionCode ").append(verCode()).append(")\n");
        b.append("主题     ").append(themeName()).append('\n');
        b.append("语言     ").append(Locale.getDefault())
                .append("    时区 ").append(java.util.TimeZone.getDefault().getID()).append('\n');
        b.append("载荷     ").append(payloadLine());
        // 在线包也进诊断：别人发来一份报告，第一个要判断的就是「这版会不会自己下包、
        // 下的是哪一版、下好了没有」—— 少了这几行，前三个问题都得回头问人。
        Trio.Spec sp = Trio.spec(this);
        if (!sp.ok()) {
            b.append("\n在线包   清单缺失 · assets/google.json");
        } else {
            b.append("\n在线包   ").append(sp.parts.size()).append(" 个包 · 下 ")
                    .append(Installer.mb(sp.download)).append(" · 落地 ")
                    .append(Installer.mb(sp.bytes));
            b.append("\n         已就绪 ").append(Trio.readyCount(this, sp))
                    .append("/").append(sp.parts.size());
        }
        // 缓存占用不管带不带包都要写：别人报「装完少了 400MB」时，
        // 先要能看出这台机器上到底有没有残留、启动时清掉了多少。
        long held = dirSize(new File(getCacheDir(), Trio.DIR))
                + dirSize(new File(getCacheDir(), "apk"));
        b.append("\n缓存     ").append(swept > 0
                ? "启动时清掉 " + Installer.mb(swept) + " 残留" : "启动时无残留")
                .append("    当前 ").append(held > 0 ? Installer.mb(held) : "空");
        // 来源也进诊断：报告会被转给别人，光有 sha 对不上「这包是谁的」。
        // 一行写不完（等宽 10.5sp 约 48 个半角格），拆三行，续行对齐「载荷」的值列。
        b.append("\n来源     stepx-companion (AGPL-3.0-only)");
        b.append("\n         其 module/{inject,boot,launcher}.py");
        b.append("\n         github.com/xydzf520/stepx\n");

        // ---- 设备 ----
        b.append("\n==== 设备 ====\n");
        b.append("机型     ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("    ").append(Build.DEVICE).append('\n');
        b.append("产品     ").append(Build.PRODUCT).append(" / ").append(Build.BOARD)
                .append(" / ").append(Build.HARDWARE).append('\n');
        b.append("ABI      ").append(joinArr(Build.SUPPORTED_ABIS)).append('\n');
        b.append("系统     Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
                .append("    incremental ").append(Build.VERSION.INCREMENTAL).append('\n');
        b.append("补丁     ").append(Build.VERSION.SECURITY_PATCH)
                .append("    构建 ").append(Build.TYPE).append(' ').append(Build.TAGS).append('\n');
        b.append("指纹     ").append(Build.FINGERPRINT).append('\n');
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        b.append("屏幕     ").append(dm.widthPixels).append('x').append(dm.heightPixels)
                .append(" @").append(dm.densityDpi).append("dpi")
                .append(" (").append(Math.round(dm.widthPixels / dm.density)).append('x')
                .append(Math.round(dm.heightPixels / dm.density)).append("dp)").append('\n');
        b.append("Java     ").append(System.getProperty("java.vm.version"))
                .append("    进程 uid ").append(android.os.Process.myUid()).append('\n');

        // ---- Shizuku ----
        b.append("\n==== Shizuku / shell ====\n");
        b.append("binder   ").append(pingBinder() ? "在跑" : "没起").append('\n');
        try {
            b.append("版本     ").append(Shizuku.getVersion())
                    .append("    uid ").append(Shizuku.getUid())
                    .append("    selinux ").append(nullSafe(Shizuku.getSELinuxContext())).append('\n');
        } catch (Throwable t) {
            b.append("版本     读不到（").append(t).append(")\n");
        }
        b.append("授权     ").append(granted() ? "已授予" : "未授予")
                .append("    rationale ").append(rationale()).append('\n');
        try {
            Shell sh = Shell.pick();
            b.append("后端     ").append(sh.name()).append(sh.ready() ? "" : "（不可用：" + sh.why() + "）")
                    .append('\n');
        } catch (Throwable t) {
            b.append("后端     探测异常 ").append(t).append('\n');
        }

        // ---- 状态快照 ----
        b.append("\n==== 状态快照 ====\n");
        b.append(last == null ? "（还没探过）"
                : (goodCount + "/" + cells.size() + " 正常    " + fmt("HH:mm:ss", last.at))).append('\n');
        for (int i = 0; i < CELLS.length; i++) {
            b.append("  ").append(cellDump[i] == null
                    ? pad(CELLS[i], 9) + pad("—", 13) + "-" : cellDump[i]).append('\n');
        }
        // BINDER 那一格健康时只念分子，连接总数放这儿 —— 它随 Play 起落而变，
        // 不是应有值，但排查「GMS 是不是真的死了」时得知道分母有多大。
        if (last != null && last.st != null) {
            b.append("  BINDER 明细  ").append(last.st.dead).append(" 条 DEAD / 共 ")
                    .append(last.st.total).append(" 条客户端连接\n");
        }

        // ---- 已选安装包 ----
        b.append("\n==== 已选安装包 ====\n");
        if (items.isEmpty()) {
            b.append("（没有选包）\n");
        } else {
            for (Installer.Item it : items) {
                b.append("  ").append(it.pkg.isEmpty() ? "?" : it.pkg)
                        .append("  v").append(it.versionCode).append("/").append(it.versionName)
                        .append("  ").append(it.split.isEmpty() ? "base" : "split:" + it.split)
                        .append("  ").append(kb(it.size))
                        .append("  ").append(it.verified ? "验签过" : "验签没过")
                        .append(it.problem.isEmpty() ? "" : "（" + it.problem + "）").append('\n');
                b.append("     文件 ").append(it.displayName)
                        .append(it.file != null && it.file.getPath().contains("/" + Trio.DIR + "/")
                                ? "  [在线]" : "")
                        .append("    签名 ").append(shortHex(it.signer)).append('\n');
            }
        }

        // ---- 运行时采集（shell 现采） ----
        b.append("\n==== 运行时采集 ====\n");
        b.append(diagExtra == null ? "（打开诊断时自动采集，未完成前这里是空的）\n" : diagExtra);

        // ---- 里程碑 ----
        b.append("\n==== 里程碑 ====\n");
        if (hist.isEmpty()) b.append("（无）\n");
        for (Line l : hist) b.append("  ").append(l.hm).append(' ').append(l.text).append('\n');

        // ---- 全量日志 ----
        b.append("\n==== 全量日志 ====\n");
        b.append(full);
        return b.toString();
    }

    private String stateWord() {
        if (last == null) return "未探测";
        if (last.err != null) return "探测异常：" + last.err;
        // 网关先于 shell 说：容器挂了的时候 shell 依然「可用」，
        // 只报 shell 会让人以为这台机器没事（真机上被这么骗过一次）。
        if (Boolean.FALSE.equals(last.gw)) {
            return (last.shell ? "网关不通（部署/注入/修补做不了），" : "")
                    + goodCount + "/" + cells.size() + " 正常";
        }
        if (last.shell) return "shell 可用，" + goodCount + "/" + cells.size() + " 正常";
        if (!pingBinder()) return "Shizuku 未运行";
        if (!granted()) return "Shizuku 待授权";
        return "无 shell";
    }

    private String pkgWord() {
        if (last == null || last.ver == null) return "未读到（没有 shell）";
        return "GSF " + val(last.ver, SelfHeal.GSF)
                + "   GMS " + val(last.ver, SelfHeal.GMS)
                + "   Play " + val(last.ver, SelfHeal.PLAY);
    }

    private String missWord() {
        if (last == null || last.ver == null) return "—";
        String miss = (val(last.ver, SelfHeal.GSF) > 0 ? "" : " GSF")
                + (val(last.ver, SelfHeal.GMS) > 0 ? "" : " GMS")
                + (val(last.ver, SelfHeal.PLAY) > 0 ? "" : " Play");
        return miss.isEmpty() ? "无" : miss.trim();
    }

    /** 每个载荷一行：名字、字节数、sha 前 8 位。长短不一的六个文件挤一行会顶到折行。 */
    private String payloadLine() {
        StringBuilder b = new StringBuilder();
        for (String n : Payload.DEPLOY) b.append("\n         ").append(n).append(' ')
                .append(payloadSize(n));
        for (String n : new String[]{Payload.DEPLOY_SCRIPT, Payload.ARM_COMPAT, Payload.READ_COMPAT}) {
            b.append("\n         ").append(n).append(' ').append(payloadSize(n));
        }
        return b.append('\n').toString();
    }

    private String payloadSize(String name) {
        try {
            byte[] d = Payload.read(this, name);
            return d.length + "B/" + Payload.sha256(d).substring(0, 8);
        } catch (Throwable t) {
            return "读不到";
        }
    }

    private String rationale() {
        try {
            return Shizuku.shouldShowRequestPermissionRationale() ? "已拒绝过" : "没拒过";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String nullSafe(String s) {
        return s == null || s.trim().isEmpty() ? "?" : s.trim();
    }

    private static String shortHex(String s) {
        if (s == null || s.isEmpty()) return "?";
        return s.length() > 16 ? s.substring(0, 16) + "…" : s;
    }

    private static String joinArr(String[] a) {
        StringBuilder b = new StringBuilder();
        for (String s : a) {
            if (b.length() > 0) b.append('/');
            b.append(s);
        }
        return b.toString();
    }

    private static int countLines(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    // ------------------------------ 运行时采集 ------------------------------

    /**
     * 打开诊断时补的一段：只有拿到 shell 才读得到的事实。
     *
     * 走一条组合命令（不是十几次 exec）—— 每次 exec 都要过一遍 binder 起一个进程，
     * 十来次就是好几秒，用户盯着「采集中…」会以为卡死。
     */
    @Override
    public void collect(java.util.function.Consumer<String> done) {
        new Thread(() -> {
            String text;
            try {
                text = collectNow();
            } catch (Throwable t) {
                text = "采集失败：" + t + "\n";
            }
            final String out = text;
            runOnUiThread(() -> {
                diagExtra = out;
                done.accept(report());
            });
        }, "stepx-diag").start();
    }

    private String collectNow() {
        Shell sh = Shell.pick();
        if (!sh.ready()) return "shell 不可用：" + sh.why() + "\n";

        // 只有 shell 能看到的那部分（/sdcard、进程、android_id 原始值）。
        // 部署产物不在这儿 —— /usr/local/lib 只在容器的 mount namespace 里，
        // shell 看不见，必须走网关读，见下面 gatewayPart()。
        String script = String.join("\n",
                "echo @id",
                "echo uid=$(id -u)",
                "echo user=$(id -un)",
                "echo context=$(cat /proc/self/attr/current 2>/dev/null)",
                "echo enforce=$(getenforce 2>/dev/null)",
                "echo kernel=$(uname -r 2>/dev/null)",
                "echo arch=$(uname -m 2>/dev/null)",
                "echo boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)",
                "echo ss=$(pidof system_server 2>/dev/null | tr ' ' ',')",
                "echo gms=$(pidof " + SelfHeal.GMS + " 2>/dev/null)",
                "echo vending=$(pidof " + SelfHeal.PLAY + " 2>/dev/null)",
                "echo aid=$(settings get secure android_id 2>/dev/null)",
                "echo @state",
                "cat " + SelfHeal.STATE + " 2>/dev/null",
                "echo @pkg",
                "dumpsys package " + SelfHeal.GMS + " 2>/dev/null "
                        + "| grep -oE 'versionName=[^ ]+|lastUpdateTime=[^ ]+' | head -3",
                "echo @disabled",
                "pm list packages -d 2>/dev/null | grep -E 'google|vending' | head -8",
                "echo @end");

        long t0 = System.currentTimeMillis();
        Shell.Exec e = sh.exec(script, 90);
        StringBuilder b = new StringBuilder();
        b.append("shell 后端 ").append(sh.name()).append("    exit ").append(e.code)
                .append("    ").append(dur(System.currentTimeMillis() - t0)).append('\n');
        if (!e.ok()) b.append("  命令输出 ").append(squash(e.text())).append('\n');

        Map<String, String> m = sections(clean(e.out));
        Map<String, String> f = flat(m);
        b.append("身份     uid ").append(kv(f, "uid")).append(" · ").append(kv(f, "user")).append('\n');
        b.append("selinux  ").append(kv(f, "context")).append(" · ").append(kv(f, "enforce")).append('\n');
        b.append("内核     ").append(kv(f, "kernel")).append('\n');
        b.append("启动     ").append(kv(f, "boot_id")).append('\n');
        b.append("         system_server ").append(kv(f, "ss")).append('\n');
        b.append("进程     gms ").append(kv(f, "gms"))
                .append(" · vending ").append(kv(f, "vending")).append('\n');
        b.append("状态文件 ").append(one(m.get("state"))).append('\n');
        b.append("GMS 包   ").append(multi(m.get("pkg"), "         ")).append('\n');
        b.append("禁用的包 ").append(one(m.get("disabled"))).append('\n');
        // android_id 是网关 token 的原料，报告里只留两头，中间打码
        b.append("android_id ").append(mask(kv(f, "aid"))).append('\n');

        b.append(gatewayPart(sh));
        return b.toString();
    }

    /** 部署产物只在容器的 mount namespace 里 —— 只有网关（root）读得到。 */
    private String gatewayPart(Shell sh) {
        StringBuilder b = new StringBuilder();
        Gateway gw;
        try {
            gw = Gateway.from(sh);
        } catch (Throwable t) {
            return "网关     token 算不出来 · " + t.getMessage() + '\n';
        }
        boolean up = gw.reachable();
        b.append("网关     ").append(Gateway.ROOT).append(up ? " 可达" : " 连不上")
                .append(" · token ").append(mask(gw.token)).append('\n');
        if (!up) {
            b.append("         网关不通 —— 部署产物读不到，注入也做不了\n");
            return b.toString();
        }

        String script = String.join("\n",
                "echo @py",
                "python3 -V 2>&1",
                "echo @lib",
                "ls -1 " + Deploy.LIB + " 2>/dev/null",
                "echo @sha",
                // 清单是 JSON，整份贴进来既占地方又要人肉读；只列「文件:sha 前 8 位」
                "python3 -c \"import json;d=json.load(open('" + Deploy.MANIFEST + "'));"
                        + "print(' '.join(k+':'+v[:8] for k,v in (d.get('sha') or {}).items()))\" 2>&1",
                "echo @bin",
                "ls -1 " + Deploy.BIN + " 2>/dev/null | head -20",
                "echo @var",
                "ls -1 " + Deploy.VAR + " 2>/dev/null",
                "echo @end");
        long t0 = System.currentTimeMillis();
        Gateway.Resp r = gw.runSh(script, 20000, 25);
        if (!r.ok()) {
            b.append("容器     读不到（").append(r.summary()).append("）\n");
            return b.toString();
        }
        Map<String, String> m = sections(clean(Deploy.stdout(r)));
        // 文件清单一行一个：跟「应该有几个文件」对得上，一眼看得出缺什么
        b.append("容器     ").append(one(m.get("py")))
                .append("    ").append(dur(System.currentTimeMillis() - t0)).append('\n');
        b.append("部署目录 ").append(Deploy.LIB).append('\n');
        b.append("         ").append(multi(m.get("lib"), "         ")).append('\n');
        b.append("清单     ").append(one(m.get("sha"))).append('\n');
        b.append("bin      ").append(multi(m.get("bin"), "         ")).append('\n');
        b.append("状态目录 ").append(Deploy.VAR).append('\n');
        b.append("         ").append(multi(m.get("var"), "         ")).append('\n');
        return b.toString();
    }

    /** 把 `@name` 分节的输出拆成 map。 */
    private static Map<String, String> sections(String out) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        String cur = null;
        StringBuilder sb = new StringBuilder();
        for (String ln : out.split("\n")) {
            if (ln.startsWith("@")) {
                if (cur != null) m.put(cur, sb.toString().trim());
                cur = ln.substring(1).trim();
                sb.setLength(0);
            } else if (cur != null) {
                sb.append(ln).append('\n');
            }
        }
        if (cur != null) m.put(cur, sb.toString().trim());
        return m;
    }

    /** 所有 `k=v` 行拉平成一个 map —— 报告里要按名字取值，不想关心它落在哪一节。 */
    private static Map<String, String> flat(Map<String, String> sec) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String body : sec.values()) {
            for (String ln : body.split("\n")) {
                int i = ln.indexOf('=');
                if (i <= 0) continue;
                String k = ln.substring(0, i).trim();
                if (!k.matches("[a-z_]+")) continue;
                if (!m.containsKey(k)) m.put(k, ln.substring(i + 1).trim());
            }
        }
        return m;
    }

    private static String kv(Map<String, String> m, String k) {
        String s = m.get(k);
        return s == null || s.trim().isEmpty() ? "—" : s.trim();
    }

    /** toybox 的输出偶尔带 \r，留着会在报告里顶出一个空行。 */
    private static String clean(String s) {
        return s == null ? "" : s.replace("\r", "");
    }

    private static String squash(String s) {
        return clean(s).replace('\n', ' ').trim();
    }

    private static String one(String s) {
        if (s == null || s.trim().isEmpty()) return "—";
        String[] ls = s.trim().split("\n");
        return ls[0].trim();
    }

    private static String multi(String s, String indent) {
        if (s == null || s.trim().isEmpty()) return "—";
        return s.trim().replace("\n", "\n" + indent);
    }

    private static String mask(String s) {
        if (s == null || s.isEmpty() || "—".equals(s)) return "—";
        if (s.length() <= 8) return s.charAt(0) + "***";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    @Override
    public void export(String text) {
        pendingExport = text;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "stepx-diag-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".txt");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) {
            line("打不开保存对话框：" + t, BAD);
        }
    }

    // ------------------------------ 业务动作 ------------------------------

    /**
     * 活动区只放里程碑，所以这里的每一句都是「用户需要知道的结果」。
     * 装包/部署/注入/核对的逐条输出全走 quiet（只进诊断）——
     * 那几十行对用户没有信息量，对我排查却一条都不能少。
     */
    private void doInstall() throws Exception {
        if (items.isEmpty()) {
            line("还没放包 · 点「选包」→ 在线获取", WARN);
            return;
        }
        List<String> miss = missing();
        if (!miss.isEmpty()) {
            line("缺 " + join(miss) + " · 三个要一起装", BAD);
            return;
        }
        Shell sh = needShell();
        if (sh == null) return;

        lastBad = null;
        long t = System.currentTimeMillis();
        phase("自检中");
        if (!Preflight.run(sh, quiet, getPackageName())) {
            line("自检没过" + why(), BAD);
            return;
        }
        say("自检", t, "通过", OK);

        phase("安装中");
        Map<String, List<Installer.Item>> by = Installer.group(items, quiet);
        if (by == null) {
            line("装包没开始 · 分组失败" + why(), BAD);
            return;
        }
        t = System.currentTimeMillis();
        // 在线取的包可能比机器上已装的旧（MindTheGapps 的节奏慢于 Play 自己更新），
        // 所以这里也先比 versionCode：装不上还硬装，只会换来一条 INSTALL_FAILED_VERSION_DOWNGRADE。
        // 但同版本不跳过 —— 手动点「安装」就是要覆盖重装，跳过等于没执行。
        List<String> need = Installer.needInstall(sh, by, quiet, true);
        if (need.isEmpty()) {
            String note = reclaim();
            items = new ArrayList<>();
            say("安装", t, "跳过 · 版本都对" + note, OK);
        } else if (Installer.install(sh, by, quiet, need)) {
            // 装成了就不必继续占缓存；失败时留着，方便直接重试。
            // items 必须一起清 —— 它们指着刚被删的文件，留着会让下次点安装拿失效路径炸。
            String note = reclaim();
            items = new ArrayList<>();
            say("安装", t, "完成" + note, OK);
        } else if (Installer.lastFailed < Installer.lastTotal) {
            // 装成了几个：没装上的通常是签名冲突（GSF），重试也装不上，
            // 缓存留着只是白占几百 MB。
            String note = reclaim();
            items = new ArrayList<>();
            say("安装", t, Installer.lastResult + note, WARN);
            detail(why().trim());
        } else {
            line("安装失败" + why() + " · 可直接重试", BAD);
        }
        probe();
    }

    private void doInject() throws Exception {
        Shell sh = needShell();
        if (sh == null) return;
        lastBad = null;
        Gateway gw = gateway(sh);
        if (gw == null) {
            line("网关不通 · 跳过部署和注入" + why(), WARN);
            return;
        }
        deployAndInject(sh, gw);
        probe();
    }

    private void doFix() throws Exception {
        Shell sh = needShell();
        if (sh == null) return;
        Gateway gw = gateway(sh);
        if (gw == null) return;
        lastBad = null;
        long t = System.currentTimeMillis();
        phase("修复中");
        int rc = SelfHeal.repair(sh, gw, quiet);
        say("修复", t, explain(rc), rc == SelfHeal.OK ? OK : BAD);
        probe();
    }

    /**
     * 「认证」按钮：Play 认证拦截的两个动作。
     *
     * 打补丁和回滚是一件事的两头，放在同一个入口里：
     *   - 补丁清空 Play 本地的拦截清单（搜索/详情/结账那个整页提示）；
     *   - 回滚把补丁改过的 cache 与 phenotype.db 恢复成打补丁之前。
     *
     * 原来补丁入口藏在 CERT 状态格里 —— 状态格看起来不可点，没人会去戳一个数字，
     * 而且「被拦截」和「现在修补」被混成了同一个动作。分开之后：
     * CERT 只报状态，动作都在这儿。
     */
    private void showCertSheet() {
        Sheet s = new Sheet();
        s.card.addView(Ui.text(this, "认证拦截", 17, th.text, true));

        SelfHeal.Status st = last == null ? null : last.st;
        boolean blocked = st != null && !st.cert.isEmpty();

        TextView lead = Ui.text(this, blocked
                        ? "现在被拦着。补丁会清空 Play 本地那份「未认证设备要拦哪些页面」的清单。"
                        : "设备没过 Google 保护机制认证时，Play 会用本地清单把整页挡住。"
                        + "现在没被拦，通常不需要补。",
                12, th.sub, false);
        lead.setLineSpacing(dp(3), 1f);
        lead.setPadding(0, dp(6), 0, dp(12));
        s.card.addView(lead);

        // 修补和回滚都要经容器里的网关跑（Play 的进程身份只有它能给）。
        // 网关不通时先说明白，别让人点下去才看见「网关无响应」。
        boolean gwDead = last != null && Boolean.FALSE.equals(last.gw);
        if (gwDead) {
            TextView warn = Ui.text(this, "网关不通：修补与回滚都要经容器执行，现在点下去只会报错。"
                    + "先把 StepOS 的智能体环境恢复起来。", 11.5f, th.bad, false);
            warn.setLineSpacing(dp(2), 1f);
            warn.setPadding(0, 0, 0, dp(10));
            s.card.addView(warn);
        }

        View cert = actionRow(blocked ? "修补认证拦截 · 正被拦" : "修补认证拦截",
                "会停掉 Play 并重启一次容器，约 40 秒 · 只关本地拦截页，搜索、Play Pass、"
                        + "结账仍是服务端判定",
                blocked, () -> {
                    s.dlg.dismiss();
                    doCertFix(false);
                });
        View back = actionRow("回滚到打补丁之前",
                "把 cache 与 phenotype.db 恢复原样 · 拦截页会回来；没打过补丁时会提示没有可回滚的记录",
                false, () -> {
                    s.dlg.dismiss();
                    doCertFix(true);
                });

        s.card.addView(cert, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        s.card.addView(back, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(8)));

        View cancel = Ui.button(this, "关闭", Ui.GHOST);
        cancel.setOnClickListener(v -> s.dlg.dismiss());
        s.card.addView(Ui.row(this, dp(8), cancel), Ui.lp(dp(46), dp(14)));
        s.show();
    }

    /**
     * 弹窗里的一个可选动作：标题 + 一行说明，整块可点。
     *
     * @param primary 当前更推荐的那一项，描边和标题走品牌色
     */
    private View actionRow(String title, String desc, boolean primary, Runnable go) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(11), dp(14), dp(11));
        box.setBackground(Ui.ripple(Ui.outline(primary ? soft(th.brand) : th.cardAlt,
                dp(10), Math.max(1, dp(1)), primary ? th.brand : th.line), th.ripple));
        box.setClickable(true);
        box.addView(Ui.text(this, title, 14, primary ? th.brand : th.text, true));
        TextView d = Ui.text(this, desc, 11, th.sub, false);
        d.setLineSpacing(dp(2), 1f);
        d.setPadding(0, dp(4), 0, 0);
        box.addView(d);
        box.setOnClickListener(v -> go.run());
        return box;
    }

    /** @param rollback true = 回滚上次补丁，false = 打补丁 */
    private void doCertFix(boolean rollback) {
        String what = rollback ? "认证回滚" : "认证修补";
        runTask(what, () -> {
            Shell sh = needShell();
            if (sh == null) return;
            Gateway gw = gateway(sh);
            if (gw == null) return;
            lastBad = null;
            long t = System.currentTimeMillis();
            phase(rollback ? "回滚中" : "修补中");
            boolean ok = CertFix.run(this, sh, gw, loud, rollback);
            if (ok && prefs != null) {
                if (rollback) {
                    // 回滚之后就不该再显示「上次修补」时间了 —— 那份补丁已经不在了
                    prefs.edit().remove(KEY_PATCH_AT).apply();
                } else {
                    // 只记「做过」的时刻：当前有没有被拦永远看 CERT 那格，
                    // 这里只是让 PATCH 格能说出「上次是哪天补的」。
                    prefs.edit().putLong(KEY_PATCH_AT, System.currentTimeMillis()).apply();
                }
            }
            say(what, t, ok ? "完成" : "没完成", ok ? OK : BAD);
            probe();
        });
    }

    private void startFlow() {
        if (busy) return;
        runTask("全流程", () -> {
            Shell sh = needShell();
            if (sh == null) return;

            if (items.isEmpty() && !Installer.allInstalled(sh)) {
                // 在线包是默认来源，一键全流程不该停下来问「要不要下载」——
                // 只有下不动（没网、存储不够、哈希不符）才退回选包。
                // 通道沿用上次在「在线获取」里选的，没选过就是直连。
                phase("取包中");
                boolean got = onlineItems(dlProxy) != null;
                if (!got) {
                    flowArmed = true;
                    line("三件套不全 · 先选包或换个源再取", WARN);
                    // 选择器前先讲清楚要三个，别让用户选了一个回来才发现不对
                    runOnUiThread(() -> showPickTip(this::launchPicker, () -> {
                        flowArmed = false;
                        line("已停下 · 没选包", WARN);
                    }));
                    return;
                }
            }
            if (items.isEmpty()) detail("这次没有选包，走设备上现成的三件套");
            flowBody(sh);
            probe();
        });
    }

    private void flowBody(Shell sh) throws Exception {
        detail("自检 → 装包 → 部署 → 注入 → 核对");
        lastBad = null;

        long t = System.currentTimeMillis();
        phase("自检中");
        if (!Preflight.run(sh, quiet, getPackageName())) {
            line("自检没过" + why(), BAD);
            return;
        }
        say("自检", t, "通过", OK);

        t = System.currentTimeMillis();
        phase("装包中");
        if (items.isEmpty()) {
            say("装包", t, "跳过 · 设备上已齐", OK);
        } else {
            Map<String, List<Installer.Item>> by = Installer.group(items, quiet);
            if (by == null) {
                line("装包没开始 · 分组失败" + why(), BAD);
                return;
            }
            List<String> need = Installer.needInstall(sh, by, quiet);
            if (need.isEmpty()) {
                // 版本都对、一个都不用装 —— 那释放出来的包同样已经没用了
                String note = reclaim();
                items = new ArrayList<>();
                say("装包", t, "跳过 · 版本都对" + note, OK);
            } else if (!Installer.install(sh, by, quiet, need)) {
                // 只失败一个（签名冲突那种）时不该停掉整条流程：已经装上的包
                // 照样需要后面的部署和注入才可用。
                if (Installer.lastFailed >= Installer.lastTotal) {
                    line("装包失败" + why(), BAD);
                    return;
                }
                String note = reclaim();
                items = new ArrayList<>();
                say("装包", t, Installer.lastResult + note, WARN);
            } else {
                String note = reclaim();
                items = new ArrayList<>();
                say("装包", t, "完成" + note, OK);
            }
        }

        boolean passed = false;
        Gateway gw = gateway(sh);
        if (gw == null) {
            line("网关不通 · 跳过部署和注入" + why(), WARN);
        } else {
            passed = deployAndInject(sh, gw);
        }

        if (!passed) {
            line("没跑完" + why(), BAD);
        } else {
            line("全流程完成", OK);
        }
    }

    private boolean deployAndInject(Shell sh, Gateway gw) {
        long t = System.currentTimeMillis();
        phase("部署中");
        if (!Deploy.run(this, sh, gw, quiet)) {
            line("部署失败" + why(), BAD);
            return false;
        }
        say("部署", t, "完成", OK);

        SelfHeal.Status st = SelfHeal.snapshot(sh);
        boolean injected = false;
        t = System.currentTimeMillis();
        phase("注入中");
        if (st.permPlay && st.permGms && st.gmsAlive) {
            say("注入", t, "跳过 · 权限已就位", OK);
        } else {
            int rc = SelfHeal.repair(sh, gw, quiet);
            say("注入", t, explain(rc), rc == SelfHeal.OK ? OK : BAD);
            if (rc != SelfHeal.OK) return false;
            injected = true;
        }
        t = System.currentTimeMillis();
        phase("核对中");
        int rc = Deploy.checkLast(this, gw, sh, quiet, injected);
        // 「没东西可核」和「核不过」是两种事：前者本轮根本没注入，
        // 拿上一次的记录去判失败只会让面板 12/12 正常时流程却标红。
        String word = rc == Deploy.CHECK_OK ? "通过"
                : rc == Deploy.CHECK_SKIP ? "跳过 · 本轮没注入" : "没通过" + why();
        say("核对", t, word, rc == Deploy.CHECK_FAIL ? BAD : OK);
        return rc != Deploy.CHECK_FAIL;
    }

    // ------------------------------ 选包 / 在线包 / 导出 ------------------------------

    /**
     * 把一批验过签的 Item 收作当前待装集合，并按「三件套齐没齐」汇报。
     *
     * 在线取的和自己选的最后都落到这里：两条来源的判定标准必须完全一致，
     * 否则「在线包能装、选来的不能装」这种差异会变成排查地狱。
     */
    private void adopt(List<Installer.Item> found, long t0, String how) {
        items = found;

        int good = 0;
        for (Installer.Item it : found) if (it.verified) good++;
        // 只报「验签 x/x 全通过」是不够的：只选一个包时它也是 1/1 全通过，
        // 但装不了。判断标准必须是「三个包齐没齐」，不是「文件过没过验签」。
        // 一个包现在带几十个分片，所以「几个文件」和「几个包」必须分开说。
        List<String> miss = missing();
        int pkgs = 0;
        for (String p : Installer.ORDER) {
            for (Installer.Item it : found) if (it.verified && p.equals(it.pkg)) { pkgs++; break; }
        }
        if (miss.isEmpty()) {
            say(how, t0, "已就绪 · " + good + " 个文件", OK);
        } else if (good == 0) {
            line(how + " " + found.size() + " 个文件 · 都不是三件套", BAD);
        } else {
            line("缺 " + join(miss) + " · " + pkgs + "/3", BAD);
        }
        // 逐条明细只进诊断
        for (Installer.Item it : found) {
            detail((it.verified ? "  ✓ " : "  ✗ ") + it.label(), it.verified ? OK : BAD);
        }
    }

    /**
     * 在线取三件套：从 MindTheGapps 的 release zip 里只下三段，落成实体文件，再走同一套验签。
     *
     * 一次要下 133MB、落 276MB，所以放在后台线程。
     * 日志按包一行 —— 逐块进度走 {@link #tick}，同一个包在同一行里刷新百分比和速度。
     *
     * @param proxy true = 经 gh-proxy 中转
     * @return 验过签的三个文件；下不下来返回 null（原因已经进了日志）
     */
    private List<Installer.Item> onlineItems(boolean proxy) {
        long t = System.currentTimeMillis();
        List<Trio.Part> parts = Trio.ensure(this, loud, proxy);
        if (parts == null || parts.isEmpty()) return null;
        List<Installer.Item> found = new ArrayList<>();
        for (Trio.Part p : parts) found.add(Installer.inspect(p.file, p.label));
        adopt(found, t, "在线包");
        return found;
    }

    private void useOnline(boolean proxy) {
        dlProxy = proxy;
        runTask("在线取包", () -> {
            if (onlineItems(proxy) == null) {
                line("没取到包 · 换加速再试，或点「选包」", WARN);
            }
        });
    }

    /**
     * 启动时清掉上一轮遗留的临时文件：在线下的三个包 + 选包时拷进来的副本。
     *
     * 场景很具体：点「在线获取」下 133MB、落 275.8MB，或者选了三个包拷进 cache，
     * 还没装就退出了 / 装到一半被杀掉。进程一死 items 就没了，缓存里的文件
     * 却还在，除了去系统设置里「清除缓存」之外没有任何入口能收回来 ——
     * 那台机器就白少几百 MB。
     *
     * 新进程启动时不可能有合法的引用（items 永远是空的），所以无条件清，
     * 不需要问用户。放后台线程：删几百 MB 在 UFS 上是毫秒级，但不必占 UI 线程。
     */
    private void sweepCaches() {
        new Thread(() -> {
            File cache = getCacheDir();
            File trio = new File(cache, Trio.DIR);
            File picked = new File(cache, "apk");
            long was = dirSize(trio) + dirSize(picked);
            if (was <= 0) return;

            long freed = Trio.clear(this) + ApkImport.clear(picked);
            swept = freed;
            runOnUiThread(() -> {
                if (freed > 0) {
                    line("上次残留 · 已清 " + Installer.mb(freed), OK);
                } else {
                    line("上次残留删不掉 · " + Installer.mb(was), WARN);
                }
            });
        }, "cache-sweep").start();
    }

    /** 目录下所有常驻文件加起来多大。递归算 —— 将来要是又分了子目录也不会漏。 */
    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        long n = 0;
        for (File f : fs) n += dirSize(f);
        return n;
    }

    /**
     * 这一轮不再需要缓存了：把选来的包和在线下的三个包一起删掉。
     *
     * 在线包在 cache 里躺 275.8MB，唯一的用途是这一次安装；不清，
     * 每装一台机器就白少几百 MB。
     *
     * @return 一句可以直接接在里程碑后面的尾注；没东西可清时返回空串
     */
    private String reclaim() {
        ApkImport.clear(new File(getCacheDir(), "apk"));
        long freed = Trio.clear(this);
        return freed > 0 ? " · 已清 " + Installer.mb(freed) : "";
    }

    private void launchPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        // 直接落在「下载」目录：APK 基本都是浏览器下载下来的
        i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/"
                        + "primary%3ADownload"));
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == REQ_EXPORT) {
            writeExport(res, data);
            return;
        }
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            line("没选到文件", WARN);
            return;
        }

        runTask("选包", () -> {
            File work = new File(getCacheDir(), "apk");
            ApkImport.clear(work);
            if (!work.isDirectory() && !work.mkdirs()) {
                line("建不了缓存目录 · " + work, BAD);
                return;
            }
            long t = System.currentTimeMillis();
            List<ApkImport.Loaded> loaded = ApkImport.load(this, uris, work, quiet);
            if (loaded.isEmpty()) {
                line("没有可用的 APK · 选中的文件里没有一个能读出来", BAD);
                return;
            }

            List<Installer.Item> found = new ArrayList<>();
            for (ApkImport.Loaded l : loaded) found.add(Installer.inspect(l.file, l.name));
            adopt(found, t, "选包");
        }, () -> {
            if (!flowArmed) return;
            flowArmed = false;
            if (items.isEmpty()) line("全流程停在这里 · 没选到可用的包", WARN);
            else if (!canInstall()) line("全流程停在这里 · 缺 " + join(missing()), BAD);
            else startFlow();
        });
    }

    private void writeExport(int res, Intent data) {
        if (res != RESULT_OK || data == null || data.getData() == null || pendingExport == null) {
            detail("导出已取消", WARN);
            return;
        }
        Uri uri = data.getData();
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("打不开输出流");
            os.write(pendingExport.getBytes(StandardCharsets.UTF_8));
            line("诊断已导出 · " + uri.getLastPathSegment() + "（" + pendingExport.length() + " 字）", OK);
            Toast.makeText(this, "已导出", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            line("导出失败 · " + t, BAD);
        } finally {
            pendingExport = null;
        }
    }

    // ------------------------------ 弹窗 ------------------------------

    /**
     * 给弹窗用的滚动容器：高度最多到屏幕的 86%，再高就在里面滚。
     *
     * 直接用 ScrollView 不行 —— 外层窗口是 wrap_content，内容多高它就多高，
     * 溢出屏幕时窗口照样被切，滚动没有意义。这里把测量上限钉死，
     * 窗口才真的会「到顶就停」。
     */
    private static final class SheetScroll extends android.widget.ScrollView {
        private final int cap;

        SheetScroll(android.content.Context c, int cap) {
            super(c);
            this.cap = cap;
        }

        @Override
        protected void onMeasure(int w, int h) {
            int capped = android.view.View.MeasureSpec.makeMeasureSpec(
                    cap, android.view.View.MeasureSpec.AT_MOST);
            super.onMeasure(w, capped);
        }
    }

    /**
     * 弹窗外壳：透明窗口 + 一张圆角卡片。
     *
     * 卡片外面套一层 ScrollView：选包那屏现在同时放着三件套的下载页入口和下载源，
     * 小屏（或者大字体）上会顶出屏幕 —— 溢出时能滚，比裁掉按钮好。
     * 内容装得下时 ScrollView 不产生任何视觉差异。
     */
    private final class Sheet {
        final android.app.Dialog dlg;
        final LinearLayout card;

        Sheet() {
            dlg = new android.app.Dialog(MainActivity.this);
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

            android.widget.FrameLayout shell = new android.widget.FrameLayout(MainActivity.this);
            shell.setPadding(dp(18), dp(18), dp(18), dp(18));

            card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(18), dp(18), dp(18), dp(16));
            card.setBackground(Ui.round(th.card, dp(18)));

            android.widget.ScrollView scroll = new SheetScroll(MainActivity.this,
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.86f));
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(card, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            shell.addView(scroll, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dlg.setContentView(shell);
        }

        void show() {
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                        android.graphics.Color.TRANSPARENT));
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                lp.dimAmount = 0.5f;
                w.setAttributes(lp);
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            dlg.show();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
    }

    /**
     * 选包前的硬提醒：三件套必须一次选齐。
     *
     * 这件事只靠选完之后的日志说是不够的 —— 那时候用户已经在选择器里点过「打开」，
     * 得退回来重选一遍。打开选择器之前先说，成本最低。
     *
     * @param cancel 取消时要收回的东西（全流程那条路上要清 flowArmed），可为 null
     */
    private void showPickTip(Runnable go, Runnable cancel) {
        Sheet s = new Sheet();

        // 两个入口共用同一个弹窗，是因为用户真正要理解的那件事是一样的：
        // 三个包必须成套，缺哪个都装不出能用的 Play。
        Trio.Spec sp = Trio.spec(this);

        s.card.addView(Ui.text(this, "三件套从哪来", 17, th.text, true));
        TextView lead = Ui.text(this,
                "在线获取只下用得上的三段；也可以点条目自己去下载页拿，回来再选包。",
                12, th.sub, false);
        lead.setLineSpacing(dp(3), 1f);
        lead.setPadding(0, dp(6), 0, dp(12));
        s.card.addView(lead);

        // 这四行就是原来「下载源」窗口的全部内容：验证过的版本号 + 各自的下载页。
        // 并到这儿是因为它和「在线获取」本来就是同一个决定 —— 想自己下的人，
        // 和点「在线获取」的人，卡在同一个问题上：拿哪三个版本。
        s.card.addView(linkRow("1", "Google Services Framework",
                "16-15304122", URL_GSF, s.dlg), Ui.lp(dp(48), 0));
        s.card.addView(linkRow("2", "Google Play services",
                "26.44.63 · 260400", URL_GMS, s.dlg), Ui.lp(dp(48), dp(6)));
        s.card.addView(linkRow("3", "Google Play Store",
                "54.1.11 · 34-0 PR", URL_VENDING, s.dlg), Ui.lp(dp(48), dp(6)));
        s.card.addView(linkRow("M", "MindTheGapps 16.0.0 arm64",
                "整套 · 与「在线获取」同源", URL_MTG, s.dlg), Ui.lp(dp(48), dp(6)));

        // 下载源：以前是点「在线获取」之后再弹一层问走哪条路，现在摊在这儿。
        // 少一个窗口，也少了「选完才发现还有一步」。
        s.card.addView(Ui.text(this, "下载源", 10, th.sub, true), Ui.lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(14)));

        final TextView[] chips = {chip("直连 GitHub"), chip("gh-proxy 加速")};
        // 点击只改选择，重画统一走 paintChips —— 少了这一步，胶囊会一直是
        // 打开弹窗时那一副样子（点下去像没反应）。
        chips[0].setOnClickListener(v -> {
            dlProxy = false;
            paintChips(chips);
        });
        chips[1].setOnClickListener(v -> {
            dlProxy = true;
            paintChips(chips);
        });
        LinearLayout src = new LinearLayout(this);
        src.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < chips.length; i++) {
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) clp.setMarginStart(dp(8));
            src.addView(chips[i], clp);
        }
        paintChips(chips);
        s.card.addView(src, Ui.lp(dp(38), dp(6)));

        TextView tail = Ui.text(this,
                "直连下不动就换加速。加速经 gh-proxy.com 中转，两条路的包都按清单校验 sha256。",
                11, th.sub, false);
        tail.setLineSpacing(dp(3), 1f);
        tail.setPadding(0, dp(8), 0, 0);
        s.card.addView(tail);

        if (sp.ok()) {
            TextView note = Ui.text(this, "下载 " + Installer.mb(sp.download) + "（装完即清）",
                    11.5f, th.sub, false);
            note.setLineSpacing(dp(2), 1f);
            note.setPadding(0, dp(6), 0, 0);
            s.card.addView(note);
        }

        View cancelBtn = Ui.button(this, "取消", Ui.GHOST);
        cancelBtn.setOnClickListener(v -> s.dlg.dismiss());
        View ok = Ui.button(this, "去选包", Ui.GHOST);
        View use = Ui.button(this, "在线获取", Ui.PRIMARY);

        // 点这两个按钮都会走 dismiss，得留个标记才能和「取消」分开
        final boolean[] went = {false};
        ok.setOnClickListener(v -> {
            went[0] = true;
            s.dlg.dismiss();
            go.run();
        });
        use.setOnClickListener(v -> {
            went[0] = true;
            s.dlg.dismiss();
            // 通道已经在这一屏选好了，直接开始下 133MB
            useOnline(dlProxy);
        });
        s.card.addView(Ui.row(this, dp(8), cancelBtn, ok, use), Ui.lp(dp(46), dp(16)));

        s.dlg.setOnDismissListener(d -> {
            if (went[0]) return;
            if (cancel != null) cancel.run();
            else detail("没去选包");
        });
        s.show();
    }

    /** 下载源的一个胶囊。外观由 {@link #paintChips} 按当前选择统一画。 */
    private TextView chip(String label) {
        TextView t = Ui.text(this, label, 12.5f, th.text, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setClickable(true);
        return t;
    }

    private void paintChips(TextView[] chips) {
        for (int i = 0; i < chips.length; i++) {
            boolean on = (i == 1) == dlProxy;
            chips[i].setTextColor(on ? 0xFFFFFFFF : th.text);
            chips[i].setBackground(on ? Ui.round(th.brand, dp(9))
                    : Ui.ripple(Ui.outline(th.cardAlt, dp(9), Math.max(1, dp(1)), th.line),
                            th.ripple));
        }
    }

    /** 弹窗里的一行「序号 + 名称 + 版本」，点开该版本的下载页。 */
    private View linkRow(String no, String name, String desc, String url,
                         android.app.Dialog dlg) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setBackground(Ui.ripple(
                Ui.outline(th.cardAlt, dp(10), Math.max(1, dp(1)), th.line), th.ripple));

        TextView n = Ui.text(this, no, 11.5f, th.brand, true);
        n.setGravity(Gravity.CENTER);
        n.setBackground(Ui.round(soft(th.brand), dp(8)));
        row.addView(n, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        midLp.setMarginStart(dp(10));
        mid.addView(Ui.text(this, name, 13, th.text, true));
        TextView dsc = Ui.text(this, desc, 11, th.sub, false);
        dsc.setPadding(0, dp(2), 0, 0);
        mid.addView(dsc);
        row.addView(mid, midLp);

        row.addView(Ui.text(this, "\u2197", 15, th.sub, false),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setClickable(true);
        row.setOnClickListener(v -> {
            openUrl(url);
            dlg.dismiss();
        });
        return row;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            line("打不开浏览器 · " + t, BAD);
        }
    }

    // ------------------------------ 工具 ------------------------------

    private Gateway gateway(Shell sh) {
        try {
            return Gateway.from(sh);
        } catch (Throwable t) {
            line("算不出网关 token · shell 身份没生效", BAD);
            detail("Gateway.from 抛：" + t.getMessage(), BAD);
            return null;
        }
    }

    private Shell needShell() {
        Shell sh = Shell.pick();
        if (sh.ready()) return sh;
        line("拿不到 shell · " + sh.why(), BAD);
        try {
            if (Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                detail("正在弹授权框 · 点「允许」");
                Shizuku.requestPermission(REQ_SHIZUKU);
            }
        } catch (Throwable ignored) {
            // 没有 binder 时 requestPermission 会抛，说明连 Shizuku 都没起
        }
        return null;
    }

    /**
     * 已选文件里还缺哪几个包（按 GSF / GMS / Play 的顺序，只认验签通过的）。
     *
     * 判定单位是「包」不是「文件」：GMS 和 Play 各带七八个分片，
     * 数文件个数会把只选了一个 GMS 分包的情况误判成「选够了」。
     */
    private List<String> missing() {
        List<String> miss = new ArrayList<>();
        for (String pkg : Installer.ORDER) {
            boolean hit = false;
            for (Installer.Item it : items) {
                if (it.verified && pkg.equals(it.pkg)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) miss.add(Installer.friendly(pkg));
        }
        return miss;
    }

    /** 三个包都在、且都过了验签，才允许点「安装」。 */
    private boolean canInstall() {
        return !items.isEmpty() && missing().isEmpty();
    }

    private static String join(List<String> list) {
        StringBuilder b = new StringBuilder();
        for (String s : list) {
            if (b.length() > 0) b.append(" + ");
            b.append(s);
        }
        return b.toString();
    }

    private interface Task {
        void run() throws Exception;
    }

    private void runTask(String title, Task task) {
        runTask(title, task, null);
    }

    /**
     * 长动作的计时行：从 0s 开始每半秒刷新一次，跑到哪一步由 {@link #phase} 说明。
     *
     * 部署、注入、修补动不动十几秒到一分钟，之前活动区在整段跑完之前一个字都不写，
     * 用户看到的就是「点了没反应」—— 明明在干活，界面却像死了。
     * 现在行随秒数长，至少能判断「还在动」和「真卡住了」。
     */
    private void startLive() {
        liveStart = System.currentTimeMillis();
        livePhase = "运行中";
        stopTicker();
        liveTick = new Runnable() {
            @Override
            public void run() {
                tick(LIVE_KEY, dur(System.currentTimeMillis() - liveStart) + " · " + livePhase, STEP);
                ui.postDelayed(this, 500);
            }
        };
        ui.post(liveTick);
    }

    /** 换阶段名。下一个半秒的刷新就会带上它。 */
    private void phase(String p) {
        livePhase = p;
    }

    /** 收工：停表并把这行撤掉 —— 阶段结果那一行已经带了耗时，留着是两份时间打架。 */
    private void stopLive() {
        stopTicker();
        tick(LIVE_KEY, null, INFO);
    }

    private void stopTicker() {
        if (liveTick != null) {
            ui.removeCallbacks(liveTick);
            liveTick = null;
        }
    }

    private void runTask(String title, Task task, Runnable then) {
        if (busy) return;
        busy = true;
        applyBusy();
        line("▶ " + title, STEP);
        startLive();
        // 持有线程句柄，是为了让「停止」能真的打断等待循环 ——
        // 系统卡死时 sh.exec 可能永远不返回，光靠各段自己检查标志位不够。
        Thread worker = new Thread(() -> {
            final Thread self = Thread.currentThread();
            try {
                task.run();
            } catch (Throwable t) {
                // 堆栈留给诊断 —— 这一行只告诉用户「崩了，去看诊断」
                detail("异常 " + t, BAD);
                for (StackTraceElement el : t.getStackTrace()) detail("    at " + el, BAD);
                line("出错了 · " + t + "（详情见诊断）", BAD);
            } finally {
                runOnUiThread(() -> {
                    stopLive();
                    // 只清自己的状态：点过「停止等待」之后 busy 已经放开，
                    // 用户可能已经开了新任务，别把它的状态踩掉。
                    if (taskThread == self) {
                        taskThread = null;
                        busy = false;
                        applyBusy();
                    }
                    // then 必须等 busy 放开之后再跑，否则它里面再 runTask 会被挡住
                    if (then != null) then.run();
                });
            }
        }, "stepx-" + title);
        taskThread = worker;
        worker.start();
    }

    /**
     * 停止等待。
     *
     * 只打断「等待/轮询」，不试图撤销已经发出去的操作：注入、重启容器这些
     * 一旦开始就不会因为这条线程中断而回退，能撤回的是「界面继续陪着等」。
     * 所以按钮上写的是「停止等待」而不是「取消」。
     */
    private void stopWaiting() {
        Thread t = taskThread;
        if (t == null) return;
        t.interrupt();
        // 立刻放开界面。sh.exec 卡在 binder 上时是打断不了的，那时还攥着 busy
        // 就等于把用户锁死在「什么都点不动」里 —— 这才是真正要修的那件事。
        taskThread = null;
        busy = false;
        applyBusy();
        // 计时行也要停 —— 否则界面放开了，表还在替一个已经放弃的任务数秒。
        stopLive();
        line("已停止等待 · 后台动作可能还在跑，稍后刷新状态看结果", WARN);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private static int soft(int color) {
        return (color & 0x00FFFFFF) | 0x22000000;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String kb(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
    }

    private static String explain(int rc) {
        switch (rc) {
            case SelfHeal.OK:         return "正常，可以直接打开 Play";
            case SelfHeal.E_PERM:     return "权限没救回，再跑一次；仍失败就重启";
            case SelfHeal.E_GMS:      return "GMS 进程或 binder 没救回";
            case SelfHeal.E_DISABLED: return "包仍被禁用，需要手工启用";
            case SelfHeal.E_REBOOT:   return "需要重启手机，解锁后立刻再跑一次";
            case SelfHeal.E_CERT:     return "被认证拦截页挡住，重注入治不了";
            case SelfHeal.E_FGS:      return "下载服务的 ACTIVATE_VPN 没补上";
            default:                  return "未知结果";
        }
    }
}
