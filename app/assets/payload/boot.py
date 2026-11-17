"""No daemon or socket. One attempt per system_server lifetime, with a deadline."""
import json, ctypes, time, signal, hashlib, base64, struct, mmap, fcntl, sqlite3, zlib

STATE = '/var/lib/stepx-gms'
LIB = '/usr/local/lib/stepx-gms'
# 开机解禁载荷：把 StepOS 置成 enabled=2 的三件套改回默认启用态。
# 单独一份，因为 inject.py 是上游原样件，它的输出契约一个字都不能动。
ENABLE = LIB + '/inject-enable.py'
lockfd = os.open(STATE + '/lock', os.O_RDWR | os.O_CREAT, 0o600)
try:
    fcntl.flock(lockfd, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    os._exit(0)

host = '/proc/self/fd/' + str(rootfd)
boot_id = open(host + '/proc/sys/kernel/random/boot_id').read().strip()
identity = boot_id
try:
    previous = json.load(open(STATE + '/last.json'))
    if previous.get('identity') == identity and (previous.get('status') != 'success' or os.path.exists(host + '/proc/' + str(previous.get('target_pid', 0)))):
        os._exit(0)
except (FileNotFoundError, ValueError):
    pass

def save(result):
    result.update(identity=identity, unix_time=int(time.time()))
    temp = STATE + '/last.new'
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(result, stream, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, STATE + '/last.json')

save({'status': 'started'})
# Give Android boot and its package/permission services time to become ready.
time.sleep(12)
# Opt-in side channel. When STATE/probe exists we run an out-of-band Java probe
# inside system_server instead of the privilege grant, and we accept whatever the
# probe prints. Without the marker this file behaves exactly as before: the
# factory payload and its fixed validation contract are untouched.
PROBE = STATE + '/probe'
probe = os.path.exists(PROBE)
# The child chroots to the Android root, where /var/lib does not exist, so the
# probe entry point has to ride across the fork as environment.
try:
    probe_mode = open(STATE + '/probe-mode').read().strip() or 'run'
except OSError:
    probe_mode = 'run'
os.environ['STEPX_PROBE_MODE'] = probe_mode
# Which probe payload to run. Defaults to the account probe so an existing
# marker keeps working; every probe capsule lives next to the factory payload.
try:
    probe_payload = open(STATE + '/probe-payload').read().strip() or 'inject-account.py'
except OSError:
    probe_payload = 'inject-account.py'
# Which identity the probe runs under. Empty (the default) keeps the factory
# path: system_server. 'play' switches to Play's own uid + SELinux domain, which
# is the only way to touch its private files; that decision has to be read
# before the chroot, because /var/lib does not exist inside it.
try:
    scope = open(STATE + '/probe-scope').read().strip() if probe else ''
except OSError:
    scope = ''
payload = open(LIB + '/' + (probe_payload if probe else 'inject.py')).read()
shared = mmap.mmap(-1, 256 * 1024, flags=mmap.MAP_SHARED | mmap.MAP_ANONYMOUS)
lib = ctypes.CDLL(None, use_errno=True)
json.dumps({'warmup': True}); hashlib.sha256(b'warmup'); base64.b64decode('AA==')

def attempt(code, label, alarm, deadline, strict, probe_channel=False):
    """一次注入尝试：fork → chroot → 换身份 → 跑载荷 → 收回它的 out。

    每次都拿一份独立的 globals 副本，只把 out 换成本次的 —— 同一台机器一次开机
    要跑两份载荷（注入 + 解禁），它们里面重名的变量（pid / t / read / write …）
    不能互相踩。失败一律以 dict 返回，不往调用方抛：开机路径上没人接得住异常。
    """
    shared[0:4] = b'\x00\x00\x00\x00'
    child = os.fork()
    if child == 0:
        out = {}
        def expired(signum, frame):
            raise TimeoutError('GMS repair deadline')
        signal.signal(signal.SIGALRM, expired)
        signal.alarm(alarm)
        try:
            os.fchdir(rootfd); os.chroot('.'); os.chdir('/')
            if scope == 'play':
                # Play 的私有目录只在它自己的域里可读写。uid 取目录属主（不用 dumpsys，
                # Play 此刻已经被停掉了），MLS 类别由 uid 推出，与 u0_aNNN 的标签一致；
                # euid 留 0 是为了 DAC —— SELinux 域才是真正的闸门。
                owner = os.stat('/data/user/0/com.android.vending').st_uid
                span = owner - 10000
                os.setresgid(owner, 0, 0); os.setresuid(owner, 0, 0)
                lib.setfsgid(owner); lib.setfsuid(owner)
                with open('/proc/self/attr/current', 'w') as stream:
                    stream.write('u:r:untrusted_app:s0:c%d,c%d,c512,c768'
                                 % (span & 0xff, 256 + (span >> 8)))
                out.update(uid=os.getuid(), euid=os.geteuid(), phase='payload')
            else:
                os.setresgid(1000, 0, 0); os.setresuid(1000, 0, 0)
                lib.setfsgid(1000); lib.setfsuid(1000)
                for context in ('u:r:system_server_startup:s0', 'u:r:system_server:s0'):
                    with open('/proc/self/attr/current', 'w') as stream:
                        stream.write(context)
                lib.setfsuid(0)
                candidates = []
                for item in os.listdir('/proc'):
                    if item.isdigit():
                        try:
                            if open('/proc/' + item + '/comm').read().strip() == 'system_server':
                                candidates.append(int(item))
                        except OSError:
                            pass
                assert len(candidates) == 1, 'No unique system_server'
                os.environ['STEPX_SYSTEM_SERVER_PID'] = str(candidates[0])
            capsule = globals().copy()
            capsule['out'] = out
            exec(compile(code, label, 'exec'), capsule)
            answer = out.get('java_result', '')
            if probe_channel:
                out['probe_result'] = answer
                out['status'] = 'compat' if scope == 'play' else 'probe'
            elif strict:
                checks = [line for line in answer.splitlines() if line.startswith('check ')]
                if not answer.startswith('GRANT_RESULT\nSYSTEM_GMS=true\n') or len(checks) < 120 or not all(line.endswith('=0') for line in checks):
                    raise RuntimeError('Scoped grant validation failed')
                out['status'] = 'success'
            else:
                out['status'] = 'failed' if 'ERROR' in answer else 'ok'
        except BaseException as error:
            out.update(status='failed', error=repr(error))
        finally:
            signal.alarm(0)
            data = json.dumps(out).encode()
            shared[:4] = len(data).to_bytes(4, 'little')
            shared[4:4 + len(data)] = data
            os._exit(0)
    while True:
        waited, status = os.waitpid(child, os.WNOHANG)
        if waited:
            break
        if time.monotonic() > deadline:
            os.kill(child, signal.SIGKILL)
            os.waitpid(child, 0)
            return {'status': 'failed', 'error': 'Worker hard deadline'}
        time.sleep(.02)
    length = int.from_bytes(shared[:4], 'little')
    return json.loads(shared[4:4 + length]) if length else {'status': 'failed', 'child_status': status}

result = attempt(payload, 'inject.py', 60 if scope == 'play' else 25,
                 time.monotonic() + (70 if scope == 'play' else 32), not probe, probe)

# 解禁排在注入之后：注入是主线，它的契约校验必须干净；解禁只是补救动作，
# 自己出错也不该把这一轮的注入结论一起抹掉。probe 是调试通道，不顺手改包的启用态。
# 硬超时说明 system_server 已经不对劲，这时候再 attach 一次只会更糟。
if not probe and result.get('error') != 'Worker hard deadline':
    time.sleep(1)
    try:
        result['enable'] = attempt(open(ENABLE).read(), 'inject-enable.py', 20,
                                   time.monotonic() + 26, False)
    except BaseException as error:
        result['enable'] = {'status': 'failed', 'error': repr(error)}
save(result)
if probe:
    # One shot: never let a stale marker hijack a later boot's privilege grant.
    try:
        os.unlink(PROBE)
    except OSError:
        pass
