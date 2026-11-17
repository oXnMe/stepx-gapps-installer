"""root 侧：把 /var/lib/stepx-gms/last.json 摘成几行可读结论。

last.json 是 root:600，uid 1000 读不到，只能借同一条 Pre-Invoke 通道拿。
摘要里只留验收需要的字段，原文另存一份到 /tmp/stepx-deploy/last.json 备查。
"""
import json
import os
import shutil

STAGE = '/tmp/stepx-deploy'
OUT = STAGE + '/out.txt'
SRC = '/var/lib/stepx-gms/last.json'
DST = STAGE + '/last.json'

lines = []


def say(text):
    lines.append(text)


ok = True
try:
    # 记录不存在是正常状态（重置后没注入过、本轮跳过注入），
    # 报成异常会让上层把它当成「核对不通过」，整条流程无故标红。
    if not os.path.exists(SRC):
        say('state missing')
        ok = None
    else:
        shutil.copyfile(SRC, DST)
        os.chmod(DST, 0o644)
        with open(DST) as stream:
            data = json.load(stream)
        say('status ' + str(data.get('status')).lower())
        say('identity ' + str(data.get('identity')))
        say('target_pid ' + str(data.get('target_pid')))
        say('detached ' + str(data.get('detached')).lower())
        say('unix_time ' + str(data.get('unix_time')))
        say('error ' + str(data.get('error') or '-'))
        java = data.get('java_result') or ''
        rows = java.splitlines()
        checks = [r for r in rows if r.startswith('check ')]
        bad = [r for r in checks if not r.rstrip().endswith('=0')]
        say('head1 ' + (rows[0] if len(rows) > 0 else '-'))
        say('head2 ' + (rows[1] if len(rows) > 1 else '-'))
        say('checks ' + str(len(checks)))
        say('checks_failed ' + str(len(bad)))
        # 这里不判 status：本脚本只负责「把记录读出来」，
        # 记录本身好不好由调用方按字段判 —— 混在一起会让「读到了但上次没成功」
        # 被报成「读取失败」，看不出到底是哪一步出了问题。
except BaseException as error:
    say('fail ' + repr(error))
    ok = False

if ok is None:
    say('RESULT none')
else:
    say('RESULT ' + ('ok' if ok else 'fail'))

text = '\n'.join(lines) + '\n'
tmp = OUT + '.stepx-new'
fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
os.write(fd, text.encode())
os.close(fd)
os.chmod(tmp, 0o644)
os.replace(tmp, OUT)
