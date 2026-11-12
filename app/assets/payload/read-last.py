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
    if data.get('status') != 'success':
        ok = False
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
