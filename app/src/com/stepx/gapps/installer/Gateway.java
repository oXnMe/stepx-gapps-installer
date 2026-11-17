package com.stepx.gapps.installer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * StepClaw exec 网关（127.0.0.1:18789）。
 *
 * 只监听回环，任何 uid 都能连；真正的门是 token —— HMAC-SHA256(salt, "stepclaw-gateway-v1|" + raw android_id)。
 * raw android_id 只有 shell 读得到，所以 token 必须在拿到 shell 之后才能算。
 *
 * 网关的命令白名单：python, python3, node, sh, bash, echo, cat, ls, pwd, whoami,
 * uname, env, which, head, tail, wc。`id` 会被拒。要跑别的，一律包成 sh -c。
 */
final class Gateway {

    static final String ROOT = "http://127.0.0.1:18789/";
    static final String RUN = ROOT + "plugins/stepclaw-exec/run";
    /** 注入入口：跑它会让整个进程树重启，特权权限重新写回。 */
    static final String RECOVER = "/usr/local/lib/stepx-gms/recover.py";

    private static final String SALT = "stepclaw-gateway-token-salt-v1";
    private static final String MSG_PREFIX = "stepclaw-gateway-v1|";

    final String androidId;
    final String token;

    private Gateway(String androidId, String token) {
        this.androidId = androidId;
        this.token = token;
    }

    /** 借 shell 身份读原始 android_id，再算 token。 */
    static Gateway from(Shell sh) throws Exception {
        String aid = sh.exec("settings get secure android_id", 20).stdout();
        if (aid.isEmpty() || "null".equals(aid)) {
            throw new IllegalStateException("读不到 android_id —— shell 身份没生效");
        }
        return new Gateway(aid, hmac(SALT, MSG_PREFIX + aid));
    }

    /**
     * 网关在不在监听。**不需要 token**：连不上就是不在，连上了状态码本身没意义
     * （根路径永远返回 405/JSON 错误）。
     *
     * 端口写在 {@link #ROOT} 里，容器一起网关就没了 —— 这是「部署 / 注入 / 修补
     * 全都做不了」的唯一根因，值得占一个状态格。
     */
    static boolean ping() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(ROOT).openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            c.setRequestMethod("GET");
            return c.getResponseCode() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 网关活着没有。用根路径探，比 POST 便宜。 */
    boolean reachable() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(ROOT).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestMethod("GET");
            return c.getResponseCode() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    Resp run(String[] argv, int timeoutMs, int readSec) {
        StringBuilder body = new StringBuilder("{\"command\":[");
        for (int i = 0; i < argv.length; i++) {
            if (i > 0) body.append(',');
            body.append(quote(argv[i]));
        }
        body.append("],\"timeoutMs\":").append(timeoutMs).append('}');
        return post(body.toString(), readSec);
    }

    /** 白名单命令一律包 sh -c 的便捷入口。 */
    Resp runSh(String cmd, int timeoutMs, int readSec) {
        return run(new String[]{"/bin/sh", "-c", cmd}, timeoutMs, readSec);
    }

    private Resp post(String body, int readSec) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(RUN).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(Math.max(5, readSec) * 1000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
            }
            return new Resp(code, sb.toString().trim(), null);
        } catch (Throwable t) {
            return new Resp(-1, "", String.valueOf(t));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String hmac(String key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] d = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    static final class Resp {
        final int code;
        final String body;
        final String error;

        Resp(int code, String body, String error) {
            this.code = code;
            this.body = body;
            this.error = error;
        }

        boolean ok() {
            return error == null && code >= 200 && code < 300;
        }

        String summary() {
            if (error != null) return "连接失败 " + error;
            String b = body;
            if (b.length() > 400) b = b.substring(0, 400) + "…";
            return "HTTP " + code + " " + b;
        }
    }
}
