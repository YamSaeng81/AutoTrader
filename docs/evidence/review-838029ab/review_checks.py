"""Review fixed local evidence and new boundary probes, no DB/network/test suites."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import posixpath
import re
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
EV = ROOT / 'docs/evidence'
SHA = '838029abab85578956f3fcd5055ffa5d514d06c2'
WK = EV / 'weekend-local-20261120'
checks, probes, sources = [], {}, {}

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.quotepath=false', *args], cwd=ROOT)

def sha(b):
    return hashlib.sha256(b).hexdigest()

def check(name, ok, detail=None):
    checks.append({'name': name, 'ok': bool(ok), 'detail': detail})

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m

check('HEAD and saved origin/main', all(git('rev-parse', x).decode().strip() == SHA for x in ('HEAD', 'origin/main')),
      'Saved references only; no new remote query')
paths = git('diff', '--name-only', '7e090d82', SHA).decode().splitlines()
check('Only approved candidate/evidence and management paths', all(p.startswith('docs/evidence/') or p in
      ('docs/LIMITED_LIVE_TEST_PLAN.md', 'docs/CURRENT_HANDOFF.md', 'docs/PROGRESS.md') for p in paths),
      {'paths': len(paths), 'settings_included': '.claude/settings.local.json' in paths})
manifests, requests = [], []
for folder in ('weekend-local-20261120', 'n03-1120-2200', 'weekend-work-order-20261120'):
    base, rows = 'docs/evidence/' + folder + '/', []
    for line in git('show', SHA + ':' + base + 'SHA256SUMS').decode('utf-8-sig').splitlines():
        if not line.strip():
            continue
        h, rel = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?(.+)', line).groups()
        p = posixpath.normpath(base + rel)
        rows.append((p, h))
        requests.append(p)
    manifests.append((folder, rows))
unique = list(dict.fromkeys(requests))
batch = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT,
                       input=''.join(SHA + ':' + p + '\n' for p in unique).encode(), capture_output=True, check=True).stdout
data, cur = {}, 0
for p in unique:
    end = batch.index(b'\n', cur)
    header = batch[cur:end].split()
    if header[-1] == b'missing':
        data[p] = None
        cur = end + 1
        continue
    size = int(header[-1])
    data[p] = batch[end + 1:end + 1 + size]
    cur = end + 1 + size + 1
for folder, rows in manifests:
    bad = [p for p, h in rows if data[p] is None or sha(data[p]) != h or (ROOT / p).read_bytes() != data[p]]
    check('Manifest ' + folder, not bad, {'entries': len(rows), 'mismatches': bad})

pins = read(EV / 'weekend-work-order-20261120/source-pins.json')['sources']
for k, pin in pins.items():
    if 'git_revision' in pin:
        raw = git('show', pin['git_revision'] + ':' + pin['path'])
    else:
        raw = (ROOT / pin['path']).read_bytes()
    sources[k] = {'path': pin['path'], 'sha256': sha(raw)}
check('Seven accepted sources unchanged', all(sources[k]['sha256'] == pin['sha256'] for k, pin in pins.items()))
saved = {}
for run, count in (('w1-run2', 8), ('w2-run2', 6), ('w3-run1', 6), ('w4-run2', 6), ('w5-run3', 8), ('w6-run1', 4)):
    d = read(WK / 'runs' / run / 'results.json')
    saved[run] = {'cases': len(d['cases']), 'ok': sum(x.get('ok') is True for x in d['cases'])}
    check('Preserved results ' + run, d['synthetic'] is True and saved[run]['cases'] == saved[run]['ok'] == count,
          saved[run])

n03 = EV / 'n03-1120-2200'
server = (n03 / 'server-files.txt').read_text(encoding='utf-8')
bad = []
for n in ('20261120-2200.txt', '20261120-2200.txt.msg', '20261120-2200.txt.raw', 'last_send'):
    h = sha((n03 / n).read_bytes())
    if not re.search(r'^' + h + r'\s+' + re.escape(n) + r'$', server, re.M):
        bad.append(n)
check('Four saved report files match server hashes', not bad, bad)
report = (n03 / '20261120-2200.txt').read_text(encoding='utf-8')
raw = (n03 / '20261120-2200.txt.raw').read_text(encoding='utf-8')
check('N03 script, read exit, and empty error/stderr files', sha((n03 / 'read.sh').read_bytes()) ==
      '41e4f33747f47ed4089ffe8ca192964529cfea24b8d150a7b1a8300c572f73ad'
      and 'read rc=0' in (n03 / 'pc-time.txt').read_text() and not (n03 / 'read-stderr.txt').read_bytes()
      and not (n03 / 'error_lines.txt').read_bytes())
check('N03 reported SID203, real exposure, and BTC inputs', all(x in raw for x in
      ('sid_status|CREATED', 'sid_available_krw|20200.00', 'real_open_positions|0', 'real_unresolved_orders|0',
       's66_running|63', 's66_total|66', 'btc_h1_ok|true', 'btc_h1_daily|20')))
check('N03 send success and existing BLAST warning', '22:00:04 KST' in report and 'KRW-BLAST' in report
      and report.count('12') >= 1 and 'HTTP 403' in (n03 / 'observations.txt').read_text(encoding='utf-8'))

sys.path.insert(0, str(WK / 'candidate'))
W4 = load(WK / 'candidate/build_monthly.py', 'review_monthly')
W6 = load(WK / 'candidate/run_offline.py', 'review_runner')
base_dir = WK / 'runs/w4-run2/N1_normal_round_trip/in'
base = read(base_dir / 'manifest.json')
def absolute_refs(x):
    if isinstance(x, dict):
        if set(x) == {'file'}:
            return {'file': str((base_dir / x['file']).resolve())}
        return {k: absolute_refs(v) for k, v in x.items()}
    if isinstance(x, list):
        return [absolute_refs(v) for v in x]
    return x
probe_root = HERE / 'new-boundaries'
probe_root.mkdir(exist_ok=False)
monthly = []
for case in ('orders_not_provided', 'orders_key_missing'):
    d = probe_root / case
    d.mkdir()
    man = absolute_refs(copy.deepcopy(base))
    if case == 'orders_not_provided':
        man['sessions'][0]['orders'] = None
    else:
        bad_orders = d / 'empty-object.json'
        bad_orders.write_text('{}\n', encoding='utf-8')
        man['sessions'][0]['orders'] = {'file': str(bad_orders)}
    mp = d / 'manifest.json'
    mp.write_text(json.dumps(man, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    rc = W4.main(['--manifest', str(mp), '--out', str(d / 'out')])
    result, card = read(d / 'out/result.json'), read(d / 'out/scorecard.json')
    c = card['sessions'][0]
    monthly.append({'case': case, 'returncode': rc, 'verdict': result['verdict'],
        'orders_source_missing': result.get('orders_source_missing'),
        'card': {k: c.get(k) for k in ('session', 'month_pnl', 'realized_in_period', 'fees_paid', 'buy_funds',
                                    'round_trips_closed', 'wins', 'losses', 'trade_state', 'identity_check')},
        'included': card.get('included'), 'aggregates': card.get('aggregates')})
probes['w4_missing_order_sources'] = monthly
check('W4 missing source becomes zero-trade card (defect reproduced)', all(x['card']['round_trips_closed'] == 0
      and str(x['card']['realized_in_period']) in ('0.00', '0') for x in monthly), monthly)

summary = read(WK / 'runs/w6-run1/I1_normal_parallel/out/summary.json')
baseline = {k: {**v, 'not_ready_codes': []} for k, v in summary['modules'].items()}
runner = []
for key, rc in (('w1', 42), ('w3', 40)):
    v = copy.deepcopy(baseline)
    v[key]['rc'] = rc
    mods, overall = W6.classify(v)
    runner.append({'changed_module': key, 'actual_rc': rc, 'json_verdict': v[key]['verdict'],
                   'classified': mods[key]['status'], 'overall': overall})
probes['w6_exit_code_ignored'] = runner
check('W6 accepts success verdict despite failing child rc (defect reproduced)', all(x['classified'] == 'LOCAL_VERIFIED'
      and x['overall'] == 'NEEDS_APPROVAL' for x in runner), runner)

out = {'sha': SHA, 'passed': sum(c['ok'] for c in checks), 'failed': sum(not c['ok'] for c in checks),
       'manifest_entries': sum(len(rows) for _, rows in manifests), 'saved_case_totals': saved, 'sources': sources,
       'checks': checks, 'note': 'Passing checks include successful reproduction of two defects; not zero product defects'}
(HERE / 'checks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(HERE / 'contract-probes.json').write_text(json.dumps(probes, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: out[k] for k in ('passed', 'failed', 'manifest_entries')}))
print(json.dumps({'w4': [{'case': x['case'], 'rc': x['returncode'], 'verdict': x['verdict'], 'card': x['card']}
                       for x in monthly], 'w6': runner}, ensure_ascii=True))
raise SystemExit(0 if out['failed'] == 0 else 1)
