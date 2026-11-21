"""Audit preserved PG/P0/N03 evidence and static read-runner gaps without executing candidates."""
import hashlib
import json
from pathlib import Path
import posixpath
import re
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BASE = '40a2fda56665f5131b243634323278687fd74481'
PM = 'docs/evidence/parallel-monitor-check-20261121/'
N03 = 'docs/evidence/n03-1121-2200/'


def blob(p, rev=BASE):
    return subprocess.run(['git', 'show', rev + ':' + p], cwd=ROOT, capture_output=True, check=True).stdout


def saved(p):
    return json.loads(blob(p))


if (HERE / 'checks.json').exists():
    raise SystemExit('Existing audit output; do not overwrite')
checks, rows, counts = [], [], {}


def check(name, ok, detail=None):
    checks.append({'name': name, 'ok': bool(ok), 'detail': detail})


for folder in ('parallel-monitor-check-20261121', 'n03-1121-2200', 'parallel-monitor-draft-20261121',
               'weekend-local-fix-20261121', 'weekend-local-20261120'):
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

for run, expected in (('pg-run1', 0), ('pg-run2', 4), ('p0-run1', 5)):
    d = saved(PM + 'runs/' + run + '/results.json')
    cs = d['boundaries'] if run.startswith('pg-') else d['cases']
    check('Saved ' + run, sum(c['ok'] is True for c in cs) == expected and len(cs) == (5 if run == 'p0-run1' else 4),
          {'passed': sum(c['ok'] is True for c in cs), 'total': len(cs)})

sql = blob(PM + 'sql/state-2.sql').decode('utf-8')
check('DYN_PAPER branch exists', "'DYNAMIC','DYN_PAPER'" in sql and 'KIND_MODE_MISMATCH' in sql)
check('Confirmed current-mode filtering remains',
      "WHEN o.session_kind IN ('DYNAMIC','DYN_PAPER') AND d.trading_mode = 'PAPER' THEN 'DYN_PAPER'" in sql and
      "WHERE NOT (o.session_kind IN ('DYNAMIC','DYN_PAPER') AND d.trading_mode IS NOT DISTINCT FROM 'PAPER')" in sql,
      'Finding: persisted DYNAMIC rows can be hidden after current session mode becomes PAPER; not an acceptance check')
fixture = blob(PM + 'runs/pg-run2/B3_registered_dynamic_now_paper/fixture.sql').decode('utf-8')
output = blob(PM + 'runs/pg-run2/B3_registered_dynamic_now_paper/in/state.tsv').decode('utf-8')
check('Saved B3 has no emitted order/position rows', not any(l.startswith(('O\t', 'P\t')) for l in output.splitlines()),
      {'fixture_sha256': hashlib.sha256(fixture.encode()).hexdigest(),
       'note': 'Saved PG observation; no new SQL execution. Unknown actual production mode-change path.'})

runner = blob(PM + 'approval/pm-read.sh').decode('utf-8')
checks_absent = {name: literal not in runner for name, literal in
                 (('expected_head', '0e1d2a02'), ('expected_backend', '2c5d8427'))}
check('Documented HEAD/image guards absent from runner', all(checks_absent.values()), checks_absent)
check('Read/post both explicitly finish with zero', runner.count('exit 0') == 2,
      {'count': runner.count('exit 0'), 'note': 'Static finding; no shell runner or SSH executed'})

server = blob(N03 + 'server-files.txt').decode('utf-8')
matches = {}
for line in server.splitlines():
    m = re.fullmatch(r'([a-f0-9]{64})\s+(.+)', line)
    if m and not m[2].endswith('.gz'):
        matches[m[2]] = hashlib.sha256(blob(N03 + m[2])).hexdigest() == m[1]
check('N03 four server hashes', len(matches) == 4 and all(matches.values()), matches)
check('N03 fixed reader', hashlib.sha256(blob(N03 + 'read.sh')).hexdigest() ==
      '41e4f33747f47ed4089ffe8ca192964529cfea24b8d150a7b1a8300c572f73ad')
check('N03 saved single successful read', blob(N03 + 'pc-time.txt').count(b'read rc=0') == 1
      and len(blob(N03 + 'read-stderr.txt')) == 0)

out = {'baseline': BASE, 'checks': checks, 'passed': sum(c['ok'] for c in checks), 'total': len(checks),
       'contains_confirmed_gap_checks': True, 'not_a_zero_defect_claim': True,
       'candidate_tests_rerun': False, 'sql_executed': False, 'shell_runner_executed': False,
       'production_access': False, 'settings_content_read_or_changed': False}
(HERE / 'checks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps({'passed': out['passed'], 'total': out['total'], 'manifest_entries': len(rows),
                  'failed': [c['name'] for c in checks if not c['ok']]}))
raise SystemExit(any(not c['ok'] for c in checks))
