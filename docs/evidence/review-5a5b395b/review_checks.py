"""Audit committed evidence and saved boundary results; no candidate or network runs."""
import hashlib
import json
from pathlib import Path
import posixpath
import re
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BASE = '5a5b395bef154674ff1ef1009f3c858239f1550e'
FOLDER = 'docs/evidence/weekend-local-fix-20261121/'


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, check=True).stdout


def saved(rel):
    return json.loads(git('show', BASE + ':' + rel).decode('utf-8-sig'))


if (HERE / 'checks.json').exists():
    raise SystemExit('Existing audit output; do not overwrite')
checks, rows, manifests = [], [], {}


def check(name, ok, detail=None):
    checks.append({'name': name, 'ok': bool(ok), 'detail': detail})


for folder in ('weekend-local-fix-20261121', 'weekend-local-20261120',
               'n03-1121-1000', 'review-838029ab'):
    base = 'docs/evidence/' + folder + '/'
    lines = git('show', BASE + ':' + base + 'SHA256SUMS').decode('utf-8-sig').splitlines()
    start = len(rows)
    for line in lines:
        if not line.strip():
            continue
        h, rel = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?(.+)', line).groups()
        rows.append((posixpath.normpath(base + rel), h.lower()))
    manifests[folder] = len(rows) - start
b = git('cat-file', '--batch') if not rows else subprocess.run(
    ['git', 'cat-file', '--batch'], cwd=ROOT,
    input=''.join(BASE + ':' + p + '\n' for p, _ in rows).encode(), capture_output=True, check=True).stdout
cur, bad, working_bad = 0, [], []
for path, h in rows:
    end = b.index(b'\n', cur)
    head = b[cur:end].split()
    if head[-1] == b'missing':
        bad.append(path)
        cur = end + 1
        continue
    n = int(head[-1])
    data = b[end + 1:end + 1 + n]
    cur = end + n + 2
    if hashlib.sha256(data).hexdigest() != h:
        bad.append(path)
    p = ROOT / path
    if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest() != h:
        working_bad.append(path)
check('Committed manifests', not bad, {'entries': len(rows), 'by_folder': manifests, 'mismatches': bad})
check('Working evidence unchanged', not working_bad, working_bad)

for run, expected, total in (('r1-run1', 4, 5), ('r1-run2', 5, 5), ('r2-run1', 5, 6), ('r2-run2', 6, 6)):
    cs = saved(FOLDER + 'runs/' + run + '/results.json')['cases']
    check('Saved ' + run, len(cs) == total and sum(c['ok'] is True for c in cs) == expected,
          {'passed': sum(c['ok'] is True for c in cs), 'total': len(cs)})

r1 = FOLDER + 'runs/r1-run2/'
card = saved(r1 + 'B1_orders_not_provided/out/scorecard.json')
c = card['sessions'][0]
check('Missing orders retains equity PnL and unknown trade metrics',
      c['month_pnl'] == '989.50' and all(c[k] is None for k in
      ('realized_in_period', 'fees_paid', 'buy_funds', 'round_trips_closed', 'wins', 'losses'))
      and card['included']['zero_trade'] == 0 and '989.5' not in c['identity_check'],
      {'month_pnl': c['month_pnl'], 'trade_state': c['trade_state'], 'identity_check': c['identity_check']})
for case, code in (('B2a_orders_key_missing', 'ORDERS_KEY_MISSING'), ('B2b_orders_type_invalid', 'ORDERS_TYPE_INVALID')):
    r = saved(r1 + case + '/out/result.json')
    check(case, r['exit'] == 20 and r['sessions_failed'][0]['error'] == code)
c = saved(r1 + 'B3_empty_orders_with_other/out/scorecard.json')
by = {x['session']: x for x in c['sessions']}
check('Explicit empty orders and other normal session', by['LIVE#203']['trade_state'] == '거래0'
      and by['DYNAMIC#900']['realized_in_period'] == '-509.75' and c['included']['zero_trade'] == 1)
c = saved(r1 + 'B4_session_fail_with_other/out/scorecard.json')
a = next(x for x in c['aggregates'] if x['group'] == 'money' and x['name'] == '실자금')
check('Isolation preserves valid subtotal', len(c['sessions']) == 1 and c['sessions'][0]['identity_check'] == '일치'
      and a['month_pnl'] is None and a['known_part_sum'] in ('989.5', '989.50')
      and a['unconfirmed_sessions'] == ['DYNAMIC#900'], a)

r2 = FOLDER + 'runs/r2-run2/'
actual = []
for case, key, rc in (('E1a_w1_success_json_rc42', 'w1', 42), ('E1b_w3_success_json_rc40', 'w3', 40),
                      ('E2a_w1_rc0_failed_json', 'w1', 0), ('E2b_w1_rc0_json_missing', 'w1', 0)):
    s = saved(r2 + case + '/out/summary.json')
    stored = int(git('show', BASE + ':' + r2 + case + '/out/' + key + '/rc.txt'))
    check(case, s['overall'] == 'FAILED' and s['exit'] == 20 and stored == rc
          and s['raw'][key]['rc'] == rc and s['raw'][key]['contract_ok'] is False)
    actual.append({'case': case, 'saved_actual_rc': stored, 'json_exit': s['raw'][key]['json_exit']})
check('Broken W3 not quoted', saved(r2 + 'E1b_w3_success_json_rc40/out/summary.json')['w2_p7_quoted'] is False)
s = saved(r2 + 'E3_w3_contract_exit10/out/summary.json')
check('Contract-valid exit 10 remains NEEDS_DATA', s['overall'] == 'NEEDS_DATA' and s['exit'] == 10
      and s['modules']['w3']['status'] == 'NEEDS_DATA' and s['raw']['w3']['contract_ok'] is True)
s = saved(r2 + 'N1_normal_integrated/out/summary.json')
check('One saved normal integration', s['overall'] == 'NEEDS_APPROVAL' and s['exit'] == 5
      and s['operational_ready'] is False and s['w2_p7_quoted'] is True
      and all(v['contract_ok'] is True for v in s['raw'].values()))

base = 'docs/evidence/n03-1121-1000/'
server = git('show', BASE + ':' + base + 'server-files.txt').decode('utf-8')
n03_hashes = {}
for line in server.splitlines():
    m = re.fullmatch(r'([a-f0-9]{64})\s+(.+)', line)
    if m and not m[2].endswith('.gz'):
        n03_hashes[m[2]] = hashlib.sha256(git('show', BASE + ':' + base + m[2])).hexdigest() == m[1]
check('N03 four server hashes', len(n03_hashes) == 4 and all(n03_hashes.values()), n03_hashes)
check('N03 fixed read source', hashlib.sha256(git('show', BASE + ':' + base + 'read.sh')).hexdigest()
      == '41e4f33747f47ed4089ffe8ca192964529cfea24b8d150a7b1a8300c572f73ad')

settings = '.claude/settings.local.json'
settings_oid = git('rev-parse', BASE + ':' + settings).decode().strip()
settings_at_commit = git('rev-parse', 'e64591a2:' + settings).decode().strip()
check('Settings inclusion metadata only', settings_oid == settings_at_commit,
      {'commit': 'e64591a2cfe1aedc9b83fc587be56d80f2a82cd4', 'path': settings,
       'content_read': False, 'changed_by_review': False, 'authorship_proven': False})
check('Prior review present in main history', subprocess.run(
    ['git', 'merge-base', '--is-ancestor', '8309a28b', BASE], cwd=ROOT).returncode == 0)

result = {'base': BASE, 'checks': checks, 'passed': sum(c['ok'] for c in checks), 'total': len(checks),
          'saved_process_boundaries': actual, 'tests_rerun': False,
          'production_access': False, 'settings_content_read': False}
(HERE / 'checks.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps({'passed': result['passed'], 'total': result['total'], 'manifest_entries': len(rows),
                  'failed_checks': [c['name'] for c in checks if not c['ok']]}))
raise SystemExit(any(not c['ok'] for c in checks))
