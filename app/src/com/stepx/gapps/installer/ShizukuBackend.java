package com.stepx.gapps.installer;

import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/**
 * 用 Shizuku 的服务身份跑命令。
 *
 * Shizuku 13 已经删掉了 {@code Shizuku.newProcess()}，但 IShizukuService 上
 * newProcess 还在（事务码 7），所以直接穿过 binder 调。
 *
 * 两条约束写死在这里，不要再绕：
 *   - 命令一律包成 /system/bin/sh -c，网关和 Shizuku 都不吃「裸 argv」的假设
 *   - 必须同时收 stdout 和 stderr。只收 stdout 时，输出一多就会把 stderr 管道
 *     写满，进程卡在 write 上永远不退出，表现为「命令超时」
 */
final class ShizukuBackend implements Shell {

    private IShizukuService svc;

    private IShizukuService service() {
        if (svc != null) return svc;
        if (!Shizuku.pingBinder()) throw new IllegalStateException("Shizuku 服务没在跑");
        if (Shizuku.checkSelfPermission() != 0) throw new IllegalStateException("Shizuku 未授权");
        IShizukuService s = IShizukuService.Stub.asInterface(
                new ShizukuBinderWrapper(Shizuku.getBinder()));
        if (s == null) throw new IllegalStateException("拿不到 IShizukuService");
        svc = s;
        return s;
    }

    @Override
    public String name() {
        return "Shizuku";
    }

    @Override
    public boolean ready() {
        return why().isEmpty();
    }

    @Override
    public String why() {
        try {
            service();
            return "";
        } catch (Throwable t) {
            svc = null;                       // binder 死过就重建，别缓存坏引用
            String m = t.getMessage();
            return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
        }
    }

    @Override
    public Exec exec(String cmd, int timeoutSec) {
        long t0 = System.currentTimeMillis();
        IRemoteProcess rp;
        try {
            rp = service().newProcess(new String[]{"/system/bin/sh", "-c", cmd}, null, null);
        } catch (Throwable t) {
            svc = null;
            return new Exec(-1, "", String.valueOf(t), false, System.currentTimeMillis() - t0);
        }

        Pump out = null;
        Pump err = null;
        try {
            out = new Pump(rp.getInputStream());
            err = new Pump(rp.getErrorStream());
            out.start();
            err.start();

            long deadline = t0 + timeoutSec * 1000L;
            boolean running = true;
            while (System.currentTimeMillis() < deadline) {
                if (!rp.alive()) { running = false; break; }
                Thread.sleep(120);
            }
            boolean timedOut = running && rp.alive();
            if (timedOut) rp.destroy();

            int code;
            try {
                code = rp.waitFor();
            } catch (Throwable t) {
                code = timedOut ? -1 : -2;
            }

            join(out);
            join(err);
            return new Exec(code, out.text(), err.text(), timedOut,
                    System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            if (out != null) join(out);
            if (err != null) join(err);
            return new Exec(-1, out == null ? "" : out.text(),
                    String.valueOf(t), false, System.currentTimeMillis() - t0);
        }
    }

    private static void join(Pump p) {
        try {
            p.join(3000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * `cat > 远端路径`，把字节流灌进它的 stdin。
     *
     * 大包（GMS 分片加起来能到 200MB+）要一路可中断：写循环自己看 deadline，
     * 超时就 destroy 进程，不然用户只能等系统把 app 干掉。
     */
    @Override
    public Exec put(InputStream in, String remotePath, int timeoutSec) {
        long t0 = System.currentTimeMillis();
        long deadline = t0 + timeoutSec * 1000L;

        IRemoteProcess rp;
        try {
            rp = service().newProcess(
                    new String[]{"/system/bin/sh", "-c", "cat > " + quote(remotePath)}, null, null);
        } catch (Throwable t) {
            svc = null;
            return new Exec(-1, "", String.valueOf(t), false, System.currentTimeMillis() - t0);
        }

        // stderr 必须同时抽干：写失败时 sh 会往里吐错误，管道满了它会卡在 write 上
        Pump out;
        Pump err;
        try {
            out = new Pump(rp.getInputStream());
            err = new Pump(rp.getErrorStream());
        } catch (Throwable t) {
            // catch 块本身不在自己这条 catch 的保护范围内，destroy 的 RemoteException 要单独兜
            try {
                rp.destroy();
            } catch (Throwable ignored) {
                // 进程可能已经退了
            }
            return new Exec(-1, "", String.valueOf(t), false, System.currentTimeMillis() - t0);
        }
        out.start();
        err.start();

        boolean timedOut = false;
        String ioError = "";
        try (OutputStream os =
                     new ParcelFileDescriptor.AutoCloseOutputStream(rp.getOutputStream())) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true;
                    break;
                }
            }
            os.flush();
        } catch (Throwable t) {
            ioError = String.valueOf(t);
        }

        if (timedOut) {
            try {
                rp.destroy();
            } catch (Throwable ignored) {
                // 进程可能已经自己退了
            }
        }

        int code;
        try {
            code = rp.waitFor();
        } catch (Throwable t) {
            code = timedOut ? -1 : -2;
        }
        join(out);
        join(err);

        String e = err.text();
        if (!ioError.isEmpty()) e = e.isEmpty() ? ioError : e + "\n" + ioError;
        return new Exec(timedOut && code == 0 ? -1 : code, out.text(), e, timedOut,
                System.currentTimeMillis() - t0);
    }

    /** sh 单引号转义：' → '\'' */
    static String quote(String s) {
        return Shell.q(s);
    }

    /** 把一路输出抽干。必须独立线程，否则大输出会互相堵死。 */
    private static final class Pump extends Thread {
        private final ParcelFileDescriptor pfd;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Pump(ParcelFileDescriptor p) {
            pfd = p;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            } catch (Throwable ignored) {
                // 进程被 kill 时 read 会抛，正常路径
            }
        }

        String text() {
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
