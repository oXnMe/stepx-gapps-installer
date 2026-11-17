"""root 侧：取回 play-compat 的结果，并把一次性标记清掉。

和 read-last.py 同一套路（last.json 是 root:600，uid 1000 读不到），差别在判据：
这里要的是 status=compat 且带 probe_result，而不是注入的 success。

原文整份复制到 /tmp/stepx-deploy/compat.json（644）给 App 解析 —— 报告里有每个文件
改了多少字节、数据库是新写还是已有，比几行摘要有用。

App 会轮询着反复跑这个脚本，所以它必须幂等：成功一次就把标记清掉，之后
再来只会看到已经清空的标记和不变的结果。
"""
import json
import os
import shutil

STAGE = '/tmp/stepx-deploy'
OUT = STAGE + '/out.txt'
SRC = '/var/lib/stepx-gms/last.json'
DST = STAGE + '/compat.json'
VAR = '/var/lib/stepx-gms'

lines = []


def say(text):
    lines.append(text)


ok = True
try:
    data = json.load(open(SRC))
    status = str(data.get('status'))
    body = data.get('probe_result') or ''
    say('status ' + status)
    say('identity ' + str(data.get('identity')))
    say('unix_time ' + str(data.get('unix_time')))
    say('error ' + str(data.get('error') or '-'))
    say('bytes ' + str(len(body)))
    if status != 'compat' or not body:
        ok = False
    else:
        shutil.copyfile(SRC, DST)
        os.chmod(DST, 0o644)
        # 摘要用「一行一件事」的纯文本，App 侧按空格切就能读；
        # 完整报告留在 compat.json 里给出问题时看。
        try:
            report = json.loads(body)
        except ValueError as error:
            report = {}
            say('fail json ' + repr(error))
        say('mode ' + str(report.get('mode')))
        say('report_ok ' + str(report.get('ok')))
        say('report_error ' + (str(report.get('error') or '-')[:160]))
        for item in report.get('files') or []:
            # state 只有回滚会写（already / restored）；patch 侧看 dropped
            say('file %s bytes=%s dropped=%s state=%s' % (
                item.get('name'), item.get('bytes'), item.get('dropped'), item.get('state')))
        db = report.get('db') or {}
        say('db changed=%s already=%s reverted=%s account=%s created=%s value=%s link=%s' % (
            db.get('changed'), db.get('already'), db.get('reverted'), db.get('account'),
            db.get('created'), db.get('value'), db.get('link')))
        if not report.get('ok'):
            ok = False
        # 一次性：留着标记的话，下一次容器重启会再打一遍（虽然幂等，但没必要）
        for name in ('probe-scope', 'probe-payload', 'play-compat-mode'):
            path = VAR + '/' + name
            if os.path.exists(path):
                os.unlink(path)
                say('del ' + name)
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
