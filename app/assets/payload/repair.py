"""Device-side recovery: force the StepX injection to re-run without a PC.

Runs as root (via apt-get Pre-Invoke). Detaches from the apt process tree first,
then removes the boot-id de-dup state and kills openclaw_env so init respawns it,
which re-enters /usr/local/bin/ubuntu_zygote -> boot.py -> inject.py.

Deliberately does NOT touch system_server or package state directly.
"""
import os
import subprocess
import time

STATE = '/var/lib/stepx-gms'
LOG = STATE + '/repair.log'


def log(msg):
    try:
        fd = os.open(LOG, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        os.write(fd, (time.strftime('[%m-%d %H:%M:%S] ') + msg + '\n').encode())
        os.close(fd)
    except OSError:
        pass


# Detach from the apt hook / openclaw_env process tree.
if os.fork():
    os._exit(0)
os.setsid()
if os.fork():
    os._exit(0)

log('---- repair armed, pid=%d ----' % os.getpid())
time.sleep(3)

for path in (STATE + '/last.json', STATE + '/lock'):
    try:
        os.unlink(path)
        log('removed ' + path)
    except OSError as exc:
        log('skip %s (%s)' % (path, exc.strerror))

r = subprocess.run(['pkill', '-9', '-f', 'openclaw_env'],
                   capture_output=True, text=True)
log('pkill rc=%d %s' % (r.returncode, r.stderr.strip()))

# Watch for the tree coming back and the injection landing.
for i in range(40):
    time.sleep(3)
    try:
        pid = subprocess.run(['pgrep', '-f', 'openclaw_env'],
                             capture_output=True, text=True).stdout.split()
    except Exception:
        pid = []
    state = '-'
    try:
        with open(STATE + '/last.json') as stream:
            head = stream.read(200)
        if '"success"' in head:
            state = 'success'
        elif '"started"' in head:
            state = 'started'
        elif head:
            state = 'partial'
    except OSError:
        pass
    log('t+%02ds env=%s last.json=%s' % ((i + 1) * 3, ','.join(pid) or '-', state))
    if state == 'success' and pid:
        log('DONE')
        break
