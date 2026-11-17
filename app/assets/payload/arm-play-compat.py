"""root 侧：布置一次 play-compat 任务（不重启容器，重启由 App 侧再触发）。

布置就是写四个标记 —— boot.py 在开机窗口里读它们来决定「跑谁、以谁的身份」：

    probe           存在即走 probe 通道（不改注入契约）
    probe-scope     play → 切到 Play 的 uid + 域
    probe-payload   play-compat.py
    play-compat-mode patch | rollback

真正的执行发生在 openclaw 容器重启之后：init 重新拉起 ubuntu_zygote（我们的
boot-adapter），它 fork 出 boot.py。App 侧调 recover.py 就是为了制造这个重启。

之所以要分成「布置」和「重启」两步：apt 钩子拉起的这个进程活不到重启之后，
标记必须先落盘。
"""
import os

LIB = '/usr/local/lib/stepx-gms'
VAR = '/var/lib/stepx-gms'
STAGE = '/tmp/stepx-deploy'
OUT = STAGE + '/out.txt'

lines = []


def say(text):
    lines.append(text)


def put(path, data, mode=0o600):
    tmp = path + '.stepx-new'
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    os.write(fd, data)
    os.close(fd)
    os.chmod(tmp, mode)
    os.chown(tmp, 0, 0)
    os.replace(tmp, path)


ok = True
try:
    mode = 'patch'
    try:
        mode = open(STAGE + '/compat-mode').read().strip() or 'patch'
    except OSError:
        pass
    if mode not in ('patch', 'rollback'):
        raise RuntimeError('unknown mode ' + mode)

    payload = LIB + '/play-compat.py'
    if not os.path.exists(payload):
        raise RuntimeError('缺少 ' + payload + '（先跑一次部署）')

    put(VAR + '/probe', b'')
    put(VAR + '/probe-scope', b'play')
    put(VAR + '/probe-payload', b'play-compat.py')
    put(VAR + '/play-compat-mode', mode.encode())
    say('arm scope=play payload=play-compat.py mode=' + mode)
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
