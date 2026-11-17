"""root 侧部署：把暂存在 /tmp/stepx-deploy 的载荷放进 /usr/local，并做幂等校验。

由 uid 1000 通过 apt 的 Pre-Invoke 钩子以 root 拉起（见 deploy-run.py）。
结果不写 stdout（apt 会把钩子的输出吞掉），统一写 /tmp/stepx-deploy/out.txt。
最后一行固定是 "RESULT ok" 或 "RESULT fail"。

三条硬约束：
  * 原厂 ubuntu_zygote 必须先备份成 STOCK 再覆盖，而且绝不能用我们自己的适配器当备份
    （那样适配器会 execv 到自己，开机起一个 fork 循环）
  * STOCK 要跟着系统走：OTA 换过原厂件之后必须刷新它，否则 boot-adapter 出错时
    execv 的是旧固件的启动器。旧份按内容哈希留成 .stepx-stock-<sha12>，不删
  * 文件一律「写临时名 → os.replace」原子替换，避免 boot 期间读到半个文件
  * 目录属主必须是 root：/usr/local/lib/stepx-gms 若被 uid 1000 拥有，
    uid 1000 就能改 boot.py，而 boot.py 是 root 在开机时执行的
"""
import hashlib
import json
import os
import shutil

BIN = '/usr/local/bin'
LIB = '/usr/local/lib/stepx-gms'
VAR = '/var/lib/stepx-gms'
ZYGOTE = BIN + '/ubuntu_zygote'
# 槽位名不带日期：它跟「备份的是哪天固件」无关，写死一个日期只会在别的机器上变成假信息。
# LEGACY 是上一版的名字，只为了已经部署过的机器不用重来一遍。
STOCK = BIN + '/ubuntu_zygote.stepx-stock'
LEGACY = BIN + '/ubuntu_zygote.stepx-stock-20261021'
STAGE = '/tmp/stepx-deploy'
OUT = STAGE + '/out.txt'
VERSION = '0.7'

FILES = [
    ('boot.py', LIB + '/boot.py', 0o644),
    ('inject.py', LIB + '/inject.py', 0o644),
    # 开机解禁载荷：boot.py 在注入之后跑它，把 StepOS 置成 enabled=2 的三件套
    # 改回默认启用态。缺这个文件不影响注入，只是重启后要用户手点「恢复三件套」。
    ('inject-enable.py', LIB + '/inject-enable.py', 0o644),
    ('recover.py', LIB + '/recover.py', 0o755),
    ('repair.py', LIB + '/repair.py', 0o700),
    # 认证修补载荷：不在开机默认路径上，只在 probe 标记指向它时才被 boot.py 读起来
    ('play-compat.py', LIB + '/play-compat.py', 0o644),
    ('boot-adapter.py', ZYGOTE, 0o755),
]

lines = []


def say(text):
    lines.append(text)


def sha(path):
    try:
        h = hashlib.sha256()
        with open(path, 'rb') as stream:
            for chunk in iter(lambda: stream.read(1 << 20), b''):
                h.update(chunk)
        return h.hexdigest()
    except OSError:
        return None


def put(src, dst, mode):
    tmp = dst + '.stepx-new'
    shutil.copyfile(src, tmp)
    os.chmod(tmp, mode)
    os.chown(tmp, 0, 0)
    os.replace(tmp, dst)


def archive(path):
    """按内容哈希留一份原厂件，返回留档路径。同内容只存一次。"""
    digest = sha(path)
    dst = BIN + '/ubuntu_zygote.stepx-stock-' + (digest or 'unknown')[:12]
    if not os.path.exists(dst):
        shutil.copy2(path, dst)
        os.chmod(dst, 0o755)
        os.chown(dst, 0, 0)
    return dst


MARK = b'/usr/local/lib/stepx-gms'


def is_ours(path):
    """这一份 ubuntu_zygote 是不是我们自己的适配器 —— 任何版本都算。

    不能用 sha 和 APK 里那份比：适配器一改（哪怕只改一行注释），
    设备上跑的还是旧适配器，sha 就不相等，于是看起来「这不是我们的东西」。
    真正要区分的是「原厂 ELF 二进制」和「我们的 python 适配器」，
    所以看文件头里那个固定路径就够了。
    """
    try:
        with open(path, 'rb') as stream:
            return MARK in stream.read(4096)
    except OSError:
        return False


def recover_stock():
    """STOCK 里躺着我们的适配器时，从 archive 里把真正的原厂件找回来。

    历史 bug（v0.32 之前）：适配器一更新，上面的 sha 比较就会失配，
    deploy 以为 ZYGOTE 是原厂件，于是「把旧适配器备份成 STOCK」，
    真原厂件被挤进 archive。原厂件没丢，按 mtime 取最新的一份非适配器放回去。
    """
    best, best_at = None, -1
    try:
        names = os.listdir(BIN)
    except OSError:
        return None
    for name in names:
        if not name.startswith('ubuntu_zygote.stepx-stock-'):
            continue
        path = os.path.join(BIN, name)
        if os.path.abspath(path) == os.path.abspath(STOCK) or is_ours(path):
            continue
        try:
            at = os.path.getmtime(path)
        except OSError:
            continue
        if at > best_at:
            best, best_at = path, at
    if best is None:
        return None
    put(best, STOCK, 0o755)
    return best


ok = True
try:
    for path, mode in ((LIB, 0o755), (VAR, 0o700)):
        if not os.path.isdir(path):
            os.makedirs(path, mode)
            say('mkdir ' + path)
        os.chmod(path, mode)
        os.chown(path, 0, 0)

    current = sha(ZYGOTE)
    adapter = sha(STAGE + '/boot-adapter.py')
    zygote_ours = is_ours(ZYGOTE)

    # 老机器上备份还叫 …-20261021。两种名字都认，但**绝不改名**：
    # 改名会让此刻正在跑的那份旧适配器找不到自己的 STOCK —— 旧适配器是写死
    # 旧名的，rename 之后它 execv 一个已经不在的路径，openclaw 容器直接起不来
    # （v0.32 在真机上踩过：改名的同一秒，容器被重启，然后就再也起不来了）。
    if not os.path.exists(STOCK) and os.path.exists(LEGACY):
        STOCK = LEGACY
        say('use legacy stock ' + STOCK)

    # STOCK 里如果是我们的适配器，说明被历史上那个 bug 顶替过（见 recover_stock）——
    # 先原厂件找回来，再往下判定。
    if os.path.exists(STOCK) and is_ours(STOCK):
        back = recover_stock()
        say('recover ' + (back or '失败') + ' -> ' + STOCK + '（备份曾被适配器顶替）'
            if back else 'warn STOCK 里是我们的适配器，archive 里也没有原厂件可找回')

    if current is None:
        say('warn 找不到 ' + ZYGOTE + '，没有可备份的原厂二进制')
        ok = False
    elif adapter is not None and current == adapter:
        if not os.path.exists(STOCK):
            say('warn ubuntu_zygote 已经是本适配器，但 stock 备份不在；原厂二进制已无处可取')
    elif zygote_ours:
        # 设备上跑的是**旧版本**的适配器：这只说明载荷更新了，原厂件事先已经备份过。
        # 这里绝不能走下面的 archive + put —— 那会把旧适配器当成原厂件写进 STOCK。
        if not os.path.exists(STOCK):
            say('warn ubuntu_zygote 是本适配器的旧版本，但 stock 备份不在；原厂二进制已无处可取')
        else:
            say('skip stock（设备上是旧版适配器，原厂备份保持不动）')
    elif not os.path.exists(STOCK):
        put(ZYGOTE, STOCK, 0o755)
        say('backup ' + ZYGOTE + ' -> ' + STOCK)
    elif sha(STOCK) == current:
        say('skip stock（备份与当前原厂件一致）')
    else:
        # 现在的 ZYGOTE 不是我们的适配器，而且和已有 STOCK 不是同一份 ——
        # 说明系统 OTA 换过原厂件。此时必须刷新 STOCK：boot-adapter 出错时
        # execv 的就是它，拿旧固件的启动器兜底等于兜不住。
        # 旧的那份按哈希留档，不删。
        say('archive ' + archive(STOCK) + '（旧固件的原厂件）')
        put(ZYGOTE, STOCK, 0o755)
        say('backup ' + ZYGOTE + ' -> ' + STOCK + '（原厂件被换过，已刷新）')

    manifest = {}
    for name, dst, mode in FILES:
        src = STAGE + '/' + name
        digest = sha(src)
        if digest is None:
            say('fail 暂存缺文件 ' + name)
            ok = False
            continue
        manifest[name] = digest
        if not os.path.exists(dst):
            put(src, dst, mode)
            say('put  ' + name + '（新建 ' + dst + ' ' + oct(mode) + '）')
            continue
        st = os.stat(dst)
        if sha(dst) == digest and (st.st_mode & 0o777) == mode and st.st_uid == 0:
            say('skip ' + name + '（内容与权限都一致）')
        else:
            put(src, dst, mode)
            say('put  ' + name + '（更新 ' + dst + ' ' + oct(mode) + '）')

    body = json.dumps({'payload': VERSION, 'sha': manifest}, indent=1, sort_keys=True)
    tmp = LIB + '/.deployed.json.stepx-new'
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
    os.write(fd, body.encode())
    os.close(fd)
    os.chmod(tmp, 0o644)
    os.chown(tmp, 0, 0)
    os.replace(tmp, LIB + '/.deployed.json')
except BaseException as error:
    say('fail ' + repr(error))
    ok = False

say('RESULT ' + ('ok' if ok else 'fail'))

text = '\n'.join(lines) + '\n'
tmp = OUT + '.stepx-new'
fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
os.write(fd, text.encode())
os.close(fd)
os.chmod(tmp, 0o644)
os.replace(tmp, OUT)
