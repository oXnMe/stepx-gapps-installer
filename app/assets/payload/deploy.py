"""root 侧部署：把暂存在 /tmp/stepx-deploy 的载荷放进 /usr/local，并做幂等校验。

由 uid 1000 通过 apt 的 Pre-Invoke 钩子以 root 拉起（见 deploy-run.py）。
结果不写 stdout（apt 会把钩子的输出吞掉），统一写 /tmp/stepx-deploy/out.txt。
最后一行固定是 "RESULT ok" 或 "RESULT fail"。

三条硬约束：
  * 原厂 ubuntu_zygote 必须先备份成 STOCK 再覆盖，而且绝不能用我们自己的适配器当备份
    （那样适配器会 execv 到自己，开机起一个 fork 循环）
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
STOCK = BIN + '/ubuntu_zygote.stepx-stock-20261021'
STAGE = '/tmp/stepx-deploy'
OUT = STAGE + '/out.txt'
VERSION = '0.5'

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
    if not os.path.exists(STOCK):
        if current is None:
            say('warn 找不到 ' + ZYGOTE + '，没有可备份的原厂二进制')
            ok = False
        elif adapter is not None and current == adapter:
            say('warn ubuntu_zygote 已经是本适配器，但 stock 备份不在；原厂二进制已无处可取')
        else:
            shutil.copy2(ZYGOTE, STOCK)
            os.chmod(STOCK, 0o755)
            os.chown(STOCK, 0, 0)
            say('backup ' + ZYGOTE + ' -> ' + STOCK)

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
