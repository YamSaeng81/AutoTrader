"""Local runner fault probe. Fake SSH only; never evaluate the remote command."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SHA = '474d103343fb4ccc531136b79e9ece5b4d9cc912'
BASE = 'docs/evidence/d81d4-20261120/runner/'
BASH = Path('C:/Program Files/Git/bin/bash.exe')
ROUND = 'round2'
WORK = HERE / 'work' / ROUND
assert ROOT in WORK.resolve().parents and not WORK.exists(), 'Use a new preserved run directory.'
WORK.mkdir(parents=True)
def git(*a):
    return subprocess.check_output(['git', *a], cwd=ROOT)
def shellpath(p):
    s = str(p.resolve()).replace('\\', '/')
    return '/' + s[0].lower() + s[2:] if len(s) > 1 and s[1] == ':' else s

extract = WORK / 'extract.py'
extract.write_bytes(git('show', SHA + ':' + BASE + 'extract.py'))
runner = WORK / 'run.sh'
runner.write_bytes(git('show', SHA + ':' + BASE + 'run.sh'))
setup = WORK / 'extracted'
r = subprocess.run([sys.executable, '-I', str(extract), str(setup), SHA], cwd=ROOT,
    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
(HERE / ('extract-probe-' + ROUND + '.txt')).write_bytes(r.stdout + r.stderr)
assert r.returncode == 0
blocks = setup / 'blocks-d81'
# extract.py writes the block files directly to its output directory.
assert not blocks.exists()
binpath = WORK / 'bin'
binpath.mkdir()
fake_ssh = binpath / 'fake-ssh'
fake_ssh.write_text('#!/bin/bash\nprintf "mock-call\\n" >> "$D4RUN/ssh-calls.txt"\nprintf "mock remote success\\n"\nexit "${FAKE_SSH_RC:-0}"\n', encoding='utf-8', newline='\n')
(binpath / 'ssh').write_bytes(fake_ssh.read_bytes())
(binpath / 'tee').write_text('''#!/bin/bash
n=0
[ ! -f "$D4RUN/tee-count" ] || n=$(cat "$D4RUN/tee-count")
n=$((n+1)); printf '%s\\n' "$n" > "$D4RUN/tee-count"
if [ "$n" = "${FAIL_TEE_N:-0}" ]; then cat >/dev/null; echo 'injected tee write failure' >&2; exit 73; fi
exec /usr/bin/tee "$@"
''', encoding='utf-8', newline='\n')
pyexe = str(Path(sys.executable)).replace('\\', '/')
(binpath / 'python').write_text('#!/bin/bash\nexec "' + pyexe + '" "$@"\n', encoding='utf-8', newline='\n')
records = []
for name, fail_tee, rc_directory, remote_rc in [('first-log', 1, False, 0), ('remote-output', 2, False, 0),
    ('last-log', 3, False, 0), ('exit-record', 0, True, 0), ('remote-failure-and-log', 3, False, 7)]:
    case = WORK / name
    (case / 'blocks-d81').mkdir(parents=True)
    for f in setup.iterdir():
        if f.is_file():
            (case / 'blocks-d81' / f.name).write_bytes(f.read_bytes())
    if rc_directory:
        (case / 'out' / 'exit-codes.txt').mkdir(parents=True)
    env = os.environ.copy()
    env.update({'D4RUN': shellpath(case), 'D4SSH': 'fake-ssh', 'FAIL_TEE_N': str(fail_tee),
        'FAKE_SSH_RC': str(remote_rc), 'PATH': str(binpath) + os.pathsep + env.get('PATH', ''),
        'PYTHONIOENCODING': 'utf-8'})
    # Resolve SSH to a local file explicitly; the PATH fallback is fake as well.
    assert env['D4SSH'] == 'fake-ssh' and (binpath / 'ssh').read_bytes() == fake_ssh.read_bytes()
    command = 'export PATH="' + shellpath(binpath) + ':$PATH"; [ "$(command -v fake-ssh)" = "' + shellpath(fake_ssh) + '" ] || exit 92; bash "' + shellpath(runner) + '" U1 deploy'
    r = subprocess.run([str(BASH), '-c', command], cwd=ROOT, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    calls = (case / 'ssh-calls.txt').read_text().splitlines() if (case / 'ssh-calls.txt').is_file() else []
    (HERE / ('runner-' + ROUND + '-' + name + '.txt')).write_bytes(r.stdout + b'\n--- STDERR ---\n' + r.stderr)
    records.append({'case': name, 'tee_failure_call': fail_tee, 'exit_record_is_directory': rc_directory,
        'fake_remote_rc': remote_rc, 'runner_rc': r.returncode, 'fake_ssh_calls': len(calls),
        'log_record_exists': (case / 'out' / 'out-U1-deploy.txt').is_file(),
        'exit_record_file_exists': (case / 'out' / 'exit-codes.txt').is_file()})
(HERE / ('runner-record-probe-' + ROUND + '.json')).write_text(json.dumps({'source': SHA,
    'runner_sha256': hashlib.sha256(runner.read_bytes()).hexdigest(), 'cases': records,
    'limits': 'All SSH commands were fake; no remote script or server operation was executed.'}, indent=2) + '\n', encoding='utf-8')
print(json.dumps(records))
