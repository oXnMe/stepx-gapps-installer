"""把 Play 的本地认证拦截列表清空。

由 boot.py 的 play scope 执行：身份是 Play 的 uid + u:r:untrusted_app:s0:...，
euid 0（DAC 上等同 root），SELinux 域是 Play 自己 —— 这样才能读写 Play 的私有目录。

改两处，都是「把拦截列表置空」，不碰别的 flag：
  1. files/experiment-flags-regular-*  目标记录的 byte 数组改成长度 0
  2. databases/phenotype.db            写一条 active 的 flag_overrides（值为空 BLOB）
     并把它挂到当前已提交的 experiment_state 上

三条边界：
  * 只改这一个 flag 名，别的记录原样复制；解析不通过就整体不动（宁可失败不写半份）
  * 落盘前留原始字节（zlib + base64）到 Play 私有目录下的 journal，回滚靠它
  * 只关掉本地的拦截页。搜索、Play Pass、结账是服务端判定，这里治不了

跑法由 STATE/play-compat-mode 决定：patch（默认）或 rollback。
结果写进 out['java_result']，由 App 侧的 CertFix 解析。
"""
import base64
import json
import os
import sqlite3
import struct
import time
import zlib

BASE = '/data/user/0/com.android.vending'
FILES = BASE + '/files'
DB = BASE + '/databases/phenotype.db'
JOURNAL_DIR = FILES + '/stepx-google-compat'

TARGET = 'GmscoreCompliance__activities_to_block_on_uncertified_devices'
TARGET_B = TARGET.encode('ascii')
NAMESPACE = 'com.google.android.finsky.regular'
FORMAT = 1
COLUMNS = ('override_id', 'config_package_id', 'config_package_name', 'account_id',
           'active', 'name', 'value', 'type', 'source')
FIXED = {1: 1, 2: 2, 3: 4, 4: 8, 7: 8, 8: 1}

report = {'ok': False, 'mode': 'patch', 'files': [], 'db': None, 'error': ''}


def fail(text):
    report['ok'] = False
    report['error'] = text
    out['java_result'] = json.dumps(report, ensure_ascii=False, sort_keys=True)


def u16(data, pos):
    return struct.unpack_from('>H', data, pos)[0]


def scan(data):
    """整份解析一遍，返回目标记录的 (size 字段位置, 当前长度)。

    解析要么走到终止符、要么抛异常 —— 调用方据此保证「只改一个记录，其余逐字不动」。
    """
    if len(data) < 4 or data[0] != 1:
        raise ValueError('bad header')
    pos = 1
    for i in range(3):
        if pos + 2 > len(data):
            raise ValueError('header %d truncated' % i)
        pos += 2 + u16(data, pos)
    hit = None
    while True:
        if pos >= len(data):
            raise ValueError('no terminator')
        kind = data[pos]
        pos += 1
        if kind == 0:
            if pos != len(data):
                raise ValueError('trailing %d bytes' % (len(data) - pos))
            if hit is None:
                raise ValueError('target missing')
            return hit
        if pos + 2 > len(data):
            raise ValueError('name truncated')
        n = u16(data, pos)
        name = data[pos + 2:pos + 2 + n]
        if len(name) != n:
            raise ValueError('name truncated')
        pos += 2 + n
        if kind == 5:
            pos += 2 + u16(data, pos)
        elif kind == 6:
            if pos + 4 > len(data):
                raise ValueError('array truncated')
            size = struct.unpack_from('>i', data, pos)[0]
            if size < 0 or pos + 4 + size > len(data):
                raise ValueError('array size %d' % size)
            if name == TARGET_B:
                if hit is not None:
                    raise ValueError('duplicate target')
                hit = (pos, size)
            pos += 4 + size
        elif kind in FIXED:
            pos += FIXED[kind]
        else:
            raise ValueError('type %d' % kind)


def patch_bytes(data):
    """返回 (新内容, 改动字节数)。已经是空的就原样返回。"""
    pos, size = scan(data)
    if size == 0:
        return data, 0
    return data[:pos] + b'\x00\x00\x00\x00' + data[pos + 4 + size:], 4 + size


def atomic(path, data, mode=None):
    temp = path + '.stepx-new'
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode or 0o600)
    try:
        os.write(fd, data)
        os.fsync(fd)
    finally:
        os.close(fd)
    if mode is not None:
        os.chmod(temp, mode)
    os.replace(temp, path)


def sha(data):
    import hashlib
    return hashlib.sha256(data).hexdigest()


# ------------------------------- 文件侧 -------------------------------

def cache_paths():
    names = sorted(n for n in os.listdir(FILES) if n.startswith('experiment-flags-regular-'))
    if not 1 <= len(names) <= 5:
        raise ValueError('unexpected cache layout (%d files)' % len(names))
    return [FILES + '/' + n for n in names]


def patch_cache():
    entries = []
    for path in cache_paths():
        original = open(path, 'rb').read()
        updated, dropped = patch_bytes(original)
        item = {'name': os.path.basename(path), 'bytes': len(original),
                'dropped': dropped, 'changed': dropped > 0}
        if dropped:
            entries.append({'path': path, 'before_sha': sha(original),
                            'before': base64.b64encode(zlib.compress(original, 9)).decode('ascii')})
        report['files'].append(item)
    if not entries:
        return None
    stamp = time.strftime('%Y%m%d-%H%M%S')
    journal = {'format': 1, 'stamp': stamp, 'entries': entries}
    if not os.path.isdir(JOURNAL_DIR):
        os.makedirs(JOURNAL_DIR, 0o700)
    # journal 先落盘再改文件：中途断电最多留下「有 journal 没改动」，回滚时是空操作
    path = JOURNAL_DIR + '/' + stamp + '-cache.json'
    atomic(path, json.dumps(journal).encode())
    for item in report['files']:
        if not item['changed']:
            continue
        path = FILES + '/' + item['name']
        updated, dropped = patch_bytes(open(path, 'rb').read())
        atomic(path, updated)
        # 写完立刻回读：SELinux/DAC 都放行不代表落盘的内容就是我们想写的那份，
        # 而这份文件是 Play 唯一的本地判定来源，写坏了比没写更糟。
        _, left = scan(open(path, 'rb').read())
        item['after'] = left
        if left != 0:
            raise ValueError('%s 回读仍有 %d 字节' % (item['name'], left))
    return journal


def restore_cache(journal):
    done = []
    for entry in journal['entries']:
        path = entry['path']
        original = zlib.decompress(base64.b64decode(entry['before']))
        current = open(path, 'rb').read()
        if sha(current) == entry['before_sha']:
            done.append({'name': os.path.basename(path), 'state': 'already'})
            continue
        atomic(path, original)
        done.append({'name': os.path.basename(path), 'state': 'restored'})
    return done


# ------------------------------- 数据库侧 -------------------------------

def rows(con, sql, args=()):
    cur = con.execute(sql, args)
    cols = [d[0] for d in cur.description]
    return [dict(zip(cols, r)) for r in cur.fetchall()]


def read_plan(con):
    configs = rows(con, 'SELECT config_package_id,committed_experiment_state_id '
                        'FROM config_packages WHERE name=?', (NAMESPACE,))
    if len(configs) != 1:
        raise ValueError('config_packages rows=%d' % len(configs))
    config = configs[0]
    states = rows(con, 'SELECT experiment_state_id,account_id,config_package_id '
                       'FROM experiment_states WHERE experiment_state_id=?',
                  (config['committed_experiment_state_id'],))
    if len(states) != 1 or states[0]['config_package_id'] != config['config_package_id']:
        raise ValueError('committed state mismatch')
    state = states[0]
    account = state['account_id']
    if not isinstance(account, int) or account < 0:
        raise ValueError('bad account id')
    if con.execute('SELECT count(*) FROM accounts WHERE account_id=?', (account,)).fetchone()[0] != 1:
        raise ValueError('account row missing')
    plan = {'config': config, 'state': state, 'override': None, 'links': []}
    cands = rows(con,
                 'SELECT ' + ','.join('o.' + c for c in COLUMNS) + ' '
                 'FROM flag_overrides o WHERE o.name=? AND ('
                 '((o.config_package_id=? OR o.config_package_name=?) AND o.account_id IN (?,0) '
                 'AND o.active IS 1) OR EXISTS (SELECT 1 FROM experiment_states_to_overrides l '
                 'WHERE l.override_id=o.override_id AND l.experiment_state_id=?)) '
                 'ORDER BY o.override_id',
                 (TARGET, config['config_package_id'], NAMESPACE, account,
                  config['committed_experiment_state_id']))
    if len(cands) > 1:
        raise ValueError('ambiguous override rows')
    if cands:
        row = cands[0]
        # account_id=0 是全局作用域：Play 自己维护的那条就落在 0 上，覆盖所有账号，
        # 比账号级更宽，目的照样达成。只认当前账号会把它判成「作用域不符」，
        # 结果文件侧都清完了，db 侧却抛错退出。
        if not (row['config_package_id'] == config['config_package_id']
                and row['config_package_name'] is None
                and row['account_id'] in (account, 0)
                and row['active'] == 1 and row['type'] == 5 and isinstance(row['value'], bytes)):
            raise ValueError('existing override not scoped to committed account')
        plan['override'] = row
        plan['links'] = rows(con, 'SELECT l.experiment_state_id,l.override_id,'
                                  's.account_id,s.config_package_id '
                                  'FROM experiment_states_to_overrides l LEFT JOIN experiment_states s '
                                  'ON s.experiment_state_id=l.experiment_state_id WHERE l.override_id=?',
                             (row['override_id'],))
    return plan


def is_active(plan):
    row = plan['override']
    committed = plan['config']['committed_experiment_state_id']
    return bool(row is not None and row['active'] == 1 and row['value'] == b''
                and any(l['experiment_state_id'] == committed for l in plan['links']))


def apply_db():
    con = sqlite3.connect(DB)
    try:
        con.execute('PRAGMA foreign_keys=ON')
        con.execute('BEGIN IMMEDIATE')
        try:
            before = read_plan(con)
            if is_active(before):
                con.execute('COMMIT')
                return {'changed': False, 'already': True, 'account': before['state']['account_id']}
            row = before['override']
            created = row is None
            changed_value = row is not None and row['value'] != b''
            if created:
                cur = con.execute('INSERT INTO flag_overrides '
                                  '(config_package_id,config_package_name,account_id,active,name,value,type,source) '
                                  'VALUES (?,NULL,?,1,?,?,5,0)',
                                  (before['config']['config_package_id'],
                                   before['state']['account_id'], TARGET, b''))
                identity = cur.lastrowid
            else:
                identity = row['override_id']
                if changed_value:
                    cur = con.execute('UPDATE flag_overrides SET value=? WHERE override_id=? '
                                      "AND type=5 AND typeof(value)='blob' AND value=?",
                                      (b'', identity, row['value']))
                    if cur.rowcount != 1:
                        raise ValueError('blob update rowcount=%d' % cur.rowcount)
            committed = before['config']['committed_experiment_state_id']
            added_link = not any(l['experiment_state_id'] == committed for l in before['links'])
            if added_link:
                con.execute('INSERT INTO experiment_states_to_overrides '
                            '(experiment_state_id,override_id) VALUES (?,?)', (committed, identity))
            after = read_plan(con)
            if not is_active(after):
                raise ValueError('readback not active')
            receipt = {'before_active': False, 'created': created, 'changed_value': changed_value,
                       'added_link': added_link, 'override_id': identity,
                       'previous': (base64.b64encode(row['value']).decode('ascii')
                                    if row is not None else None)}
            con.execute('COMMIT')
            return {'changed': created or changed_value or added_link,
                    'already': False, 'account': after['state']['account_id'],
                    'created': created, 'value': changed_value, 'link': added_link,
                    'receipt': receipt}
        except BaseException:
            con.execute('ROLLBACK')
            raise
    finally:
        con.close()


def revert_db(receipt):
    con = sqlite3.connect(DB)
    try:
        con.execute('BEGIN IMMEDIATE')
        try:
            plan = read_plan(con)
            committed = plan['config']['committed_experiment_state_id']
            if receipt['added_link']:
                con.execute('DELETE FROM experiment_states_to_overrides '
                            'WHERE experiment_state_id=? AND override_id=?',
                            (committed, receipt['override_id']))
            if receipt['created']:
                con.execute('DELETE FROM flag_overrides WHERE override_id=?', (receipt['override_id'],))
            elif receipt['changed_value']:
                original = base64.b64decode(receipt['previous'])
                con.execute("UPDATE flag_overrides SET value=? WHERE override_id=? AND value=?",
                            (original, receipt['override_id'], b''))
            con.execute('COMMIT')
            return True
        except BaseException:
            con.execute('ROLLBACK')
            raise
    finally:
        con.close()


# ------------------------------- 入口 -------------------------------

mode = 'patch'
try:
    mode = open('/var/lib/stepx-gms/play-compat-mode').read().strip() or 'patch'
except OSError:
    pass
report['mode'] = mode

try:
    if mode == 'rollback':
        journals = sorted(n for n in os.listdir(JOURNAL_DIR) if n.endswith('-cache.json'))
        if not journals:
            raise ValueError('no journal to roll back')
        journal = json.load(open(JOURNAL_DIR + '/' + journals[-1]))
        report['files'] = restore_cache(journal)
        report['db'] = {'reverted': revert_db(journal['db']['receipt'])}
        os.unlink(JOURNAL_DIR + '/' + journals[-1])
        report['ok'] = True
    else:
        journal = patch_cache()
        db = apply_db()
        if journal is not None:
            journal['db'] = db
            path = JOURNAL_DIR + '/' + journal['stamp'] + '-cache.json'
            atomic(path, json.dumps(journal).encode())
        report['db'] = db
        report['ok'] = True
except BaseException as error:
    fail(repr(error))

out['java_result'] = json.dumps(report, ensure_ascii=False, sort_keys=True)
