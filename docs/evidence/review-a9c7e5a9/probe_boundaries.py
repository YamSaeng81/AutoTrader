"""Independent local counterexample checks; SSH replacement never evaluates commands."""
from pathlib import Path
import json
import os
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SRC = 'cd8bd76fdd4d46bac893a98f4eab36cceb990c99'
P = 'docs/evidence/d81d4r-20261120/'
WORK = HERE / 'work' / 'round1'
assert ROOT in WORK.resolve().parents and not WORK.exists(), 'Choose a new preserved round.'
WORK.mkdir(parents=True)
BASH = Path('C:/Program Files/Git/bin/bash.exe')

def blob(path):
    return subprocess.check_output(['git', 'show', SRC + ':' + P + path], cwd=ROOT)

def sp(p):
    s = str(p.resolve()).replace('\\', '/')
    return '/' + s[0].lower() + s[2:] if len(s) > 1 and s[1] == ':' else s

uv = blob('d4/blocks-d81/U변수.sh').decode()
code = re.search(r"pf3ok\(\) \{ python3 - \"\$1\" <<'PYEOF'\n(.*?)\nPYEOF", uv, re.S)[1]
py = WORK / 'pf3.py'; py.write_text(code, encoding='utf-8', newline='\n')
prefix = ['LIVE | 0', 'DYNAMIC | 0', 'LIVE | 0', 'DYNAMIC | 0']
row = ['10', '1', '0', '100', '1', '1', '99', '1', '0', '0', 'LIVE', '1', '2026-11-01 00:00:00+00', '-1', 'STOP_LOSS', '1']
parsers = []
for name, change, expected in [('normal', {}, 0), ('bad-cost', {3: 'not-a-number'}, 3),
        ('infinite-qty', {7: 'Infinity'}, 3), ('bad-flag', {9: '2'}, 3), ('missing-net', {6: ''}, 1)]:
    rr = row.copy()
    for k, v in change.items(): rr[k] = v
    inp = WORK / (name + '.txt'); inp.write_bytes(('\n'.join(prefix + [' | '.join(rr)]) + '\n').encode())
    r = subprocess.run([sys.executable, '-I', '-X', 'utf8', str(py), str(inp)], capture_output=True, timeout=10)
    (HERE / ('pf3-' + name + '.txt')).write_bytes(r.stdout + r.stderr)
    parsers.append({'case': name, 'rc': r.returncode, 'expected': expected, 'pass': r.returncode == expected and not r.stderr and all(v.encode() not in r.stdout for v in change.values() if v)})

extract = WORK / 'extract.py'; extract.write_bytes(blob('runner/extract.py'))
run = WORK / 'run.sh'; run.write_bytes(blob('runner/run.sh'))
extracted = WORK / 'extracted'
r = subprocess.run([sys.executable, '-I', '-X', 'utf8', str(extract), str(extracted), SRC], cwd=ROOT, capture_output=True)
(HERE / 'extract-probe.txt').write_bytes(r.stdout + r.stderr)
assert r.returncode == 0
binpath = WORK / 'bin'; binpath.mkdir()
fake = '#!/bin/bash\nprintf "mock-call\\n" >> "$D4RUN/ssh-calls.txt"\necho "mock output"\nexit "${FAKE_SSH_RC:-0}"\n'
for n in ('fake-ssh', 'ssh'):
    (binpath / n).write_text(fake, encoding='utf-8', newline='\n')
(binpath / 'tee').write_text('''#!/bin/bash
n=0; [ ! -f "$D4RUN/tee-count" ] || n=$(cat "$D4RUN/tee-count")
n=$((n+1)); printf '%s\\n' "$n" > "$D4RUN/tee-count"
if [ "$n" = "${FAIL_TEE_N:-0}" ]; then cat >/dev/null; echo 'injected write failure' >&2; exit 73; fi
exec /usr/bin/tee "$@"
''', encoding='utf-8', newline='\n')
(binpath / 'python').write_text('#!/bin/bash\nexec "' + str(Path(sys.executable)).replace('\\', '/') + '" "$@"\n', encoding='utf-8', newline='\n')
runners = []
for name, failtee, directory, remote, expected, calls in [
        ('normal', 0, False, 0, 0, 1), ('marker-write', 1, False, 0, 4, 0),
        ('start-write', 2, False, 0, 4, 0), ('remote-write', 3, False, 0, 4, 1),
        ('end-write', 4, False, 0, 4, 1), ('exit-directory', 0, True, 0, 4, 1),
        ('remote7-end-write', 4, False, 7, 7, 1), ('remote255-write', 3, False, 255, 255, 1)]:
    case = WORK / name; blocks = case / 'blocks-d81'; blocks.mkdir(parents=True)
    for f in extracted.iterdir():
        if f.is_file(): (blocks / f.name).write_bytes(f.read_bytes())
    if directory: (case / 'out' / 'exit-codes.txt').mkdir(parents=True)
    env = os.environ.copy(); env.update({'D4RUN': sp(case), 'D4SSH': 'fake-ssh',
        'FAIL_TEE_N': str(failtee), 'FAKE_SSH_RC': str(remote)})
    command_prefix = 'export PATH="' + sp(binpath) + ':$PATH"; [ "$(command -v fake-ssh)" = "' + sp(binpath / 'fake-ssh') + '" ] || exit 92; '
    def invoke(block, suffix):
        rr = subprocess.run([str(BASH), '-c', command_prefix + 'bash "' + sp(run) + '" ' + block + ' deploy'], cwd=ROOT, env=env, capture_output=True, timeout=30)
        (HERE / ('runner-' + name + suffix + '.txt')).write_bytes(rr.stdout + rr.stderr)
        nn = len((case / 'ssh-calls.txt').read_text().splitlines()) if (case / 'ssh-calls.txt').is_file() else 0
        return rr, nn
    r, nc = invoke('U1', '')
    marked = (case / 'out' / 'INCOMPLETE.txt').exists()
    want_mark = calls == 1 and expected != 0
    runners.append({'case': name, 'rc': r.returncode, 'expected': expected, 'fake_ssh_calls': nc, 'expected_calls': calls,
        'incomplete': marked, 'pass': r.returncode == expected and nc == calls and marked == want_mark})
    if name in ('remote-write', 'exit-directory'):
        env['FAIL_TEE_N'] = '0'; env['FAKE_SSH_RC'] = '0'
        rr, nn = invoke('U2', '-next')
        runners.append({'case': name + '-next-U2', 'rc': rr.returncode, 'expected': 2, 'new_fake_ssh_calls': nn - nc, 'pass': rr.returncode == 2 and nn == nc})
result = {'source': SRC, 'limits': 'Synthetic text and fake SSH only. No remote command executed.',
    'parser': parsers, 'runner': runners, 'pass': sum(x['pass'] for x in parsers + runners), 'fail': sum(not x['pass'] for x in parsers + runners)}
(HERE / 'boundary-probe.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'pass': result['pass'], 'fail': result['fail'], 'failed': [x for x in parsers + runners if not x['pass']]}))
