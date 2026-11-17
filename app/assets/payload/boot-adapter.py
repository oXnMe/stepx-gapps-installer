#!/usr/bin/python3
"""Boot adapter: hand control straight back to the factory launcher, and run exactly
one bounded GMS permission repair in an isolated child.

Mirrors the StepX module, with one deliberate change: STOCK is the preserved factory
binary rather than the replaced path, because this file now occupies that path.

Contract:
  * Every failure path ends in execv(STOCK) -- the phone must always boot normally.
  * The child is detached from the service session and cgroup, so restarting or
    killing openclaw_env cannot take it down mid-repair.
  * boot.py enforces boot-id de-duplication, so this costs one attempt per boot.
"""
import os

STOCK = '/usr/local/bin/ubuntu_zygote.stepx-stock'
# 上一版把固件日期写进了文件名。两个名字都认，**但不改名** —— 改名会让此刻
# 正在跑的旧适配器 execv 一个已经不存在的路径，容器直接起不来（真机踩过）。
if not os.path.exists(STOCK):
    STOCK = '/usr/local/bin/ubuntu_zygote.stepx-stock-20261021'
BASE = '/usr/local/lib/stepx-gms'
STATE = '/var/lib/stepx-gms'

try:
    host = '/proc/' + str(os.getppid()) + '/root'
    rootfd = os.open(host, os.O_RDONLY | os.O_DIRECTORY)
    child = os.fork()
except BaseException:
    os.execv(STOCK, ['/usr/local/bin/ubuntu_zygote'])

if child:
    os.close(rootfd)
    os.execv(STOCK, ['/usr/local/bin/ubuntu_zygote'])

try:
    os.setsid()
    fd = os.open(host + '/sys/fs/cgroup/cgroup.procs', os.O_WRONLY)
    os.write(fd, str(os.getpid()).encode())
    os.close(fd)
    # The original root directory stays valid even if openclaw_env restarts.
    for n in (0, 1, 2):
        null = os.open('/dev/null', os.O_RDWR)
        os.dup2(null, n)
        if null > 2:
            os.close(null)
    code = open(BASE + '/boot.py').read()
    exec(compile(code, BASE + '/boot.py', 'exec'), globals())
except BaseException as error:
    try:
        os.makedirs(STATE, mode=0o700, exist_ok=True)
        with open(STATE + '/launcher-error.txt', 'w') as stream:
            stream.write(repr(error))
    except BaseException:
        pass
os._exit(0)
