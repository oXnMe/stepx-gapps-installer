package stepx;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;

/**
 * 开机把被 StepOS 置成「已禁用」的三件套改回默认启用态。
 *
 * <p>为什么不在 shell 里做：StepOS 的开机流程以 {@code android}（uid 1000）身份写
 * {@code enabled=2}，AOSP 的 {@code PackageManagerService.setEnabledSettings} 只放行
 * 「shell 回滚自己禁用的包」，所以 uid 2000 的 pm enable 永久被拒（
 * {@code Shell cannot change component state}）。{@code pm install-existing} 同样无效，
 * 唯一被验证过的 shell 解法是回装整包 —— 慢、会丢 GSF 数据、还要连带重装 Play。
 *
 * <p>这个类跑在 system_server 进程里（boot.py 经 ptrace 注入），调用身份就是 uid 1000，
 * 自带 {@code CHANGE_COMPONENT_ENABLED_STATE}，因此可以越过那条 shell 限制，
 * 直接把用户态启用标志写回 {@code COMPONENT_ENABLED_STATE_DEFAULT}。
 *
 * <p>只做「恢复默认」这一件事：不动 suspended、不清数据、不改签名、不 force-stop
 * 之外的任何状态。包本来就没被禁用时保持原样 —— 幂等，可以每次开机都跑。
 */
public final class EnableProbe {

    private static final String[] PACKAGES = {
            "com.google.android.gsf",
            "com.google.android.gms",
            "com.android.vending",
    };

    /** COMPONENT_ENABLED_STATE_ENABLED=1 与 DEFAULT=0 都算正常，其余（2/3/4）都要改回 0。 */
    private static final int DEFAULT = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    private static final int ENABLED = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

    private EnableProbe() {
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    public static String run() {
        StringBuilder out = new StringBuilder();
        try {
            // 同进程取 PMS 实体：拿它的 Context 再走公开 API，避免依赖 @hide 的
            // ActivityThread.currentActivityThread()。
            Object binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "package");
            if (binder == null) {
                return "ERROR package service missing\nENABLE_RESULT ok=0 fail=3\n";
            }
            Object pms = field(binder, "this$0");
            // Android 16 的 PackageManagerService 没有 getContext()（GmsProbe 走的是
            // PermissionManagerService 那条链），所以先从 PMS 自己的字段拿，拿不到再退到
            // ActivityThread.currentActivityThread().getSystemContext()。
            Context context;
            try {
                context = (Context) field(pms, "mContext");
            } catch (Throwable missing) {
                Object thread = Class.forName("android.app.ActivityThread")
                        .getMethod("currentActivityThread").invoke(null);
                context = (Context) thread.getClass()
                        .getMethod("getSystemContext").invoke(thread);
            }
            if (context == null) {
                return "ERROR system context missing\nENABLE_RESULT ok=0 fail=3\n";
            }
            PackageManager pm = context.getPackageManager();

            int ok = 0;
            int fail = 0;
            for (String pkg : PACKAGES) {
                try {
                    int before = pm.getApplicationEnabledSetting(pkg);
                    int after = before;
                    if (before != DEFAULT && before != ENABLED) {
                        pm.setApplicationEnabledSetting(pkg, DEFAULT, 0);
                        after = pm.getApplicationEnabledSetting(pkg);
                    }
                    boolean good = after == DEFAULT || after == ENABLED;
                    if (good) {
                        ok++;
                    } else {
                        fail++;
                    }
                    out.append("enable ").append(pkg)
                            .append(' ').append(before).append("->").append(after)
                            .append(good ? "" : " FAILED").append('\n');
                } catch (Throwable one) {
                    fail++;
                    out.append("enable ").append(pkg).append(" error ")
                            .append(one.getClass().getSimpleName()).append(": ")
                            .append(String.valueOf(one.getMessage())).append('\n');
                }
            }
            out.append("ENABLE_RESULT ok=").append(ok).append(" fail=").append(fail);
            return out.toString();
        } catch (Throwable t) {
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            return "ERROR " + trace + "ENABLE_RESULT ok=0 fail=3\n";
        }
    }
}
