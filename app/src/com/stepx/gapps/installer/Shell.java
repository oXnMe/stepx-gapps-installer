package com.stepx.gapps.installer;

/**
 * 安装器需要的东西只有一件：以 uid 2000 的身份跑一条 shell 命令。
 *
 * 为什么必须是 shell —— 网关 token = HMAC(salt, "stepclaw-gateway-v1|" + android_id)，
 * 而 Settings.Secure.android_id 对第三方应用是按签名派生的（bfb3e9ad29e7c564），
 * 只有 shell 能读到原始值（ad18019509e32738）。网关认后者，所以拿不到 shell 就全流程停。
 *
 * 两个实现：
 *   ShizukuBackend —— 已实测可跑（M1），走 ISHizukuService.newProcess 事务码 7
 *   AdbBackend     —— 只有骨架，见 DESIGN.md §7
 */
public interface Shell {

    String name();

    /** 现在能不能真的跑命令。 */
    boolean ready();

    /** ready()==false 时说清楚卡在哪。 */
    String why();

    /**
     * 跑 `sh -c cmd` 并等它结束。不抛异常：进程失败、超时、binder 异常
     * 全部折叠进返回值，调用方只看 Exec。
     */
    Exec exec(String cmd, int timeoutSec);

    /**
     * 把本地字节流写到远端路径。
     *
     * 为什么不走 /sdcard：`pm install` 是 system_server 去读文件，它读不了 FUSE（坑 7）。
     * 所以 APK 必须落到 /data/local/tmp，而 app 的 uid 读不了也写不了那里 ——
     * 只能把字节流灌进 shell 的 stdin，由 shell 自己落盘。
     */
    Exec put(java.io.InputStream in, String remotePath, int timeoutSec);

    /** 挑一个能用的后端；都不行就返回一个 ready()==false 的壳子说明原因。 */
    static Shell pick() {
        Shell shizuku = new ShizukuBackend();
        if (shizuku.ready()) return shizuku;
        Shell adb = new AdbBackend();
        if (adb.ready()) return adb;
        return new Unavailable(shizuku.why(), adb.why());
    }

    /** sh 单引号转义：' → '\'' 。标签里带引号时（应用名什么都有）必须过一遍。 */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 一次命令执行的结果。 */
    final class Exec {
        public final int code;
        public final String out;
        public final String err;
        public final boolean timedOut;
        public final long ms;

        public Exec(int code, String out, String err, boolean timedOut, long ms) {
            this.code = code;
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
            this.timedOut = timedOut;
            this.ms = ms;
        }

        public static Exec fail(String msg) {
            return new Exec(-1, "", msg, false, 0);
        }

        public boolean ok() {
            return !timedOut && code == 0;
        }

        public String stdout() {
            return out.trim();
        }

        /** stdout + stderr，给日志用。 */
        public String text() {
            String a = out.trim();
            String b = err.trim();
            if (a.isEmpty()) return b;
            if (b.isEmpty()) return a;
            return a + "\n" + b;
        }

        /** 最后一行非空输出 —— pm / dumpsys 的结论都在最后一行。 */
        public String tail() {
            String[] ls = text().split("\n");
            for (int i = ls.length - 1; i >= 0; i--) {
                String s = ls[i].trim();
                if (!s.isEmpty()) return s;
            }
            return "";
        }

        public String describe() {
            if (timedOut) return "超时（" + ms + "ms）";
            if (code != 0) return "exit=" + code + " " + tail();
            return tail();
        }
    }

    /** 没有可用后端时的占位。 */
    final class Unavailable implements Shell {
        private final String reason;

        Unavailable(String shizukuWhy, String adbWhy) {
            this.reason = "Shizuku：" + shizukuWhy + "；内置 adb：" + adbWhy;
        }

        @Override public String name() { return "无"; }

        @Override public boolean ready() { return false; }

        @Override public String why() { return reason; }

        @Override public Exec exec(String cmd, int timeoutSec) { return Exec.fail(reason); }

        @Override public Exec put(java.io.InputStream in, String remotePath, int timeoutSec) {
            return Exec.fail(reason);
        }
    }
}
