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
# 名字里的日期只是历史遗留的槽位名，不是「只备份那一天的原厂件」——
# 它会随 OTA 刷新（见上面第二条约束）。改名要连 boot-adapter.py 一起动，
# 那是开机路径，不划算。
STOCK = BIN + '/ubuntu_zygote.stepx-stock-20261021'
STAGE = '/tmp/stepx-deploy'
OUT = STAGE + '/out.txt'
VERSION = '0.6'

FILES = [
    ('boot.py', LIB + '/boot.py', 0o644),
    ('inject.py', LIB + '/inject.py', 0o644),
    ('recover.py', LIB + '/recover.py', 0o755),
    ('repair.py', LIB + '/repair.py', 0o700),
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
    if current is None:
        say('warn 找不到 ' + ZYGOTE + '，没有可备份的原厂二进制')
        ok = False
    elif adapter is not None and current == adapter:
        if not os.path.exists(STOCK):
            say('warn ubuntu_zygote 已经是本适配器，但 stock 备份不在；原厂二进制已无处可取')
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
