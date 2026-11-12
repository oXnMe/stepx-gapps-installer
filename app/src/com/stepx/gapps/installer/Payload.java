package com.stepx.gapps.installer;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * APK 里那份注入载荷。
 *
 * 为什么要打进 APK：这套工具的前提就是「没有电脑」。载荷如果还要用户自己下载，
 * 就等于把最难的一步留给了用户。五个文件加起来 40KB，值得。
 *
 * 但**源头仍然是 stepx-play-trio/scripts**，这里的副本必须逐字节一致 ——
 * 部署清单里记的是 sha256，两边一旦不一致，装完的机器会静默跑旧代码。
 *
 * 来源（逐件都注明，别混着算）：
 * <pre>
 *   inject.py        逐字节取自 xydzf520/stepx 的 handoff/google-play-stepx/module/inject.py
 *   boot.py          同目录 module/boot.py 的衍生：加了只读探测槽位与 rootfd 入参
 *   boot-adapter.py  同目录 module/launcher.py 的衍生：STOCK 指向被保留的原厂原件
 *   recover / repair / read-last / deploy* / stepx-fix.sh   本项目自有
 * </pre>
 * 上游以 AGPL-3.0-only 发布，因此整个应用按同一许可对待。改载荷前先读 docs/DESIGN.md §20。
 */
final class Payload {

    /** 要落到设备上的文件；顺序即写入顺序（boot-adapter 最后）。 */
    static final String[] DEPLOY = {
            "boot.py", "inject.py", "recover.py", "repair.py", "boot-adapter.py",
    };

    /** 只在容器 /tmp 里跑、不落 /usr/local 的辅助脚本。 */
    static final String RUNNER = "deploy-run.py";
    static final String DEPLOY_SCRIPT = "deploy.py";
    static final String READ_LAST = "read-last.py";

    /**
     * 兜底脚本，落到 /sdcard（不是 /usr/local）。
     *
     * 它和 DEPLOY 那批不是一回事：那些是注入链路的一部分，落容器里；
     * 这个是在「app 被卸载 / Shizuku 起不来 / 手上只剩一台电脑」时，
     * 还能用任意 shell 工具跑 `sh /sdcard/stepx-fix.sh` 自救。
     * 所以它必须留在用户够得着的地方，而不是 root 才读得到的目录。
     */
    static final String FALLBACK = "stepx-fix.sh";

    private Payload() { }

    static byte[] read(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open("payload/" + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Throwable t) {
            throw new IllegalStateException("APK 里缺载荷 " + name + "：" + t);
        }
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            throw new IllegalStateException("算不了 sha256：" + t);
        }
    }

    static String text(Context ctx, String name) {
        return new String(read(ctx, name), StandardCharsets.UTF_8);
    }
}
