"""Review saved follow-up and N03 evidence; no runner, database, SSH, or product executions."""
import hashlib
import json
from pathlib import Path
import posixpath
import re
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BASE = '1df256c7f50934a547d3f33d96523dd1c424e163'
PM = 'docs/evidence/parallel-monitor-followup-20261121/'


def blob(p):
    return subprocess.run(['git', 'show', BASE + ':' + p], cwd=ROOT, capture_output=True, check=True).stdout


if (HERE / 'checks.json').exists():
    raise SystemExit('Existing audit output; do not overwrite')
checks, rows, counts = [], [], {}


def check(name, ok, detail=None):
    checks.append({'name': name, 'ok': bool(ok), 'detail': detail})


for folder in ('parallel-monitor-followup-20261121', 'n03-1122-1000', 'n03-1122-2200'):
    base = 'docs/evidence/' + folder + '/'
    start = len(rows)
    for line in blob(base + 'SHA256SUMS').decode('utf-8-sig').splitlines():
        if not line.strip():
            continue
        h, p = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?(.+)', line).groups()
        rows.append((posixpath.normpath(base + p), h.lower()))
    counts[folder] = len(rows) - start
b = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT,
                   input=''.join(BASE + ':' + p + '\n' for p, _ in rows).encode(), capture_output=True, check=True).stdout
cur, bad, working_bad = 0, [], []
for path, h in rows:
    end = b.index(b'\n', cur)
    header = b[cur:end].split()
    if header[-1] == b'missing':
        bad.append(path)
        cur = end + 1
        continue
    n = int(header[-1])
    data = b[end + 1:end + 1 + n]
    cur = end + n + 2
    if hashlib.sha256(data).hexdigest() != h:
        bad.append(path)
    p = ROOT / path
    if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest() != h:
        working_bad.append(path)
check('Committed and working manifests', not bad and not working_bad,
      {'entries': len(rows), 'folders': counts, 'committed_mismatches': bad, 'working_mismatches': working_bad})

pg = json.loads(blob(PM + 'runs/pg-run1/results.json'))
check('Saved PG 2 boundaries', len(pg['boundaries']) == 2 and all(c['ok'] is True for c in pg['boundaries']))
lk = json.loads(blob(PM + 'runs/link-run1/results.json'))
check('Saved synthetic link 4 cases', len(lk['cases']) == 4 and all(c['ok'] is True for c in lk['cases']))
fake = blob(PM + 'runs/fake-ssh-run3/out.txt').decode('utf-8')
check('Saved final fake SSH 8 cases', len(re.findall(r'^PASS V[1-8]_', fake, re.M)) == 8
      and not re.search(r'^FAIL ', fake, re.M))
check('Saved critical failure distinctions',
      'PASS V6_ssh_failed read=255' in fake and 'PASS V8_last_write_failed read=4' in fake
      and 'PASS V7_log_failed read=6' in fake)

pins = {
    'sql/state-3.sql': '6d71df61a59d144afe9cfa1affc250c6e219c6195612aa97d2580fa8de1bec6b',
    'candidate/convert_state_4.py': '1416564e5df04cc7b59b5e4eaafce50a096c02a7ce333dd661ffb8d8be8edfcd',
    'candidate/make_aux2.py': 'bde58ccb40af3a18d65d624b98edb5065486fee4f082d366291e16a2f5c50cbd',
    'approval/pm-read-2.sh': 'fa767e7d2337d6fcbee0636d2ff270bafab9f0121c52f0b2089f482306ce1e1c',
}
check('Fixed read source hashes', all(hashlib.sha256(blob(PM + p)).hexdigest() == h for p, h in pins.items()), pins)
sql = blob(PM + 'sql/state-3.sql').decode('utf-8')
check('Stored REAL kind retained on current-mode mismatch',
      "WHEN o.session_kind = 'DYNAMIC' AND d.trading_mode IS DISTINCT FROM 'REAL' THEN 'KIND_MODE_MISMATCH'" in sql
      and "WHERE NOT (o.session_kind = 'DYN_PAPER' AND d.id IS NOT NULL AND d.trading_mode IS NOT DISTINCT FROM 'PAPER')" in sql)
runner = blob(PM + 'approval/pm-read-2.sh').decode('utf-8')
check('Explicit read pins and server until',
      'EXP_HEAD=0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d' in runner
      and 'EXP_IMG=sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f' in runner
      and 'EXP_FLYWAY=96' in runner and "'date -u +%FT%TZ'" in runner)
check('Post and read completion markers present', 'post-INCOMPLETE.txt' in runner and 'read-status.txt' in runner
      and 'exit "$code"' in runner and 'exit "$FINAL"' in runner)

for folder in ('n03-1122-1000', 'n03-1122-2200'):
    base = 'docs/evidence/' + folder + '/'
    matches = {}
    for line in blob(base + 'server-files.txt').decode('utf-8').splitlines():
        m = re.fullmatch(r'([a-f0-9]{64})\s+(.+)', line)
        if m and not m[2].endswith('.gz'):
            matches[m[2]] = hashlib.sha256(blob(base + m[2])).hexdigest() == m[1]
    check(folder + ' four server hashes', len(matches) == 4 and all(matches.values()))
    check(folder + ' fixed reader', hashlib.sha256(blob(base + 'read.sh')).hexdigest() ==
          '41e4f33747f47ed4089ffe8ca192964529cfea24b8d150a7b1a8300c572f73ad')
day_fe = blob('docs/evidence/n03-1122-1000/frontend-image.txt')
night_fe = blob('docs/evidence/n03-1122-2200/frontend-image.txt')
check('Day FE unknown and night FE observed are separate', b'Connection closed' in day_fe
      and b'9d7f123ad6afd75b46d954b7c7031267df5afb3bf6e99660d5a53a3c5d43798e' in night_fe)
check('Night saved successful read and empty stderr', b'rc: 0' in blob('docs/evidence/n03-1122-2200/pc-time.txt')
      and len(blob('docs/evidence/n03-1122-2200/read-stderr.txt')) == 0)

out = {'baseline': BASE, 'checks': checks, 'passed': sum(c['ok'] for c in checks), 'total': len(checks),
       'tests_rerun': False, 'production_access': False, 'settings_read_or_changed': False,
       'compose_env_on_production_verified': False,
       'official_compose_reference': 'https://docs.docker.com/reference/cli/docker/compose/exec/'}
(HERE / 'checks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps({'passed': out['passed'], 'total': out['total'], 'manifest_entries': len(rows),
                  'failed': [c['name'] for c in checks if not c['ok']]}))
raise SystemExit(any(not c['ok'] for c in checks))
