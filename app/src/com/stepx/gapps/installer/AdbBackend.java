package com.stepx.gapps.installer;

/**
 * 内置 adb 后端 —— 目前只有骨架，故意不假装能用。
 *
 * 想做完整形态，得从零落这些东西（本机没有 Gradle、没有第三方库可用）：
 *   1. mDNS 发现 _adb-tls-pairing._tcp（NsdManager 可用）
 *   2. SPAKE2-B over P-256：Y = y*G + w*N，K = y*(X - w*M)
 *      —— JCA 只给标量乘，点加/点减要用 BigInteger 手写 secp256r1
 *   3. HKDF-SHA256 派生，再套一层 TLS 1.3（自定义 TrustManager 全放行）
 *   4. adb 自身协议：CNXN / AUTH(RSASSA-PKCS1) / OPEN / OKAY / WRTE / CLSE
 *   合计约 800 行，且没有参考实现可拷。
 *
 * 关键判断：**Shizuku 已经能覆盖「没有电脑」这个场景** —— 它自己用无线调试配对启动，
 * 手机内完成。所以内置 adb 不是必需品，只是「用户不肯装 Shizuku」时的备胎。
 * 先不做，把接口留在这里，等有人真的需要再补。
 */
final class AdbBackend implements Shell {

    @Override
    public String name() {
        return "内置 adb";
    }

    @Override
    public boolean ready() {
        return false;
    }

    @Override
    public String why() {
        return "未实现（需先落 SPAKE2 配对 + TLS + adb 协议，见 DESIGN.md §7）";
    }

    @Override
    public Exec exec(String cmd, int timeoutSec) {
        return Exec.fail(why());
    }

    @Override
    public Exec put(java.io.InputStream in, String remotePath, int timeoutSec) {
        return Exec.fail(why());
    }
}
