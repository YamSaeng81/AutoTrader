"""Director v1.187: fixed Git/evidence and pure file-input probes only.
No network, DB, Java, Docker, product edits, or re-running PG scenarios.
"""
import copy
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SUB = 'c2a09f9c7a95473f3a163d29dadf21e3626f64b5'
BASE = '0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d'
COOL = '893364406e4cbbf2ca129680af18d8995e7c90a9'
SRC = 'docs/evidence/dec-reconcile-20261118'
checks = []


def git(*args, data=None):
    return subprocess.check_output(['git', *args], cwd=ROOT, input=data, stderr=subprocess.DEVNULL)


def blob(path, sha=SUB):
    return git('show', f'{sha}:{path}')


def check(name, ok, detail=None):
    checks.append({'check': name, 'pass': bool(ok), 'detail': detail})


def manifest(folder):
    raw = blob(folder + '/SHA256SUMS').decode('utf-8')
    names = []
    for line in raw.splitlines():
        h, name = line.split(maxsplit=1)
        name = str(PurePosixPath(name.lstrip('*')))
        path = folder + '/' + name
        b = blob(path)
        check('manifest:' + path, hashlib.sha256(b).hexdigest() == h and (ROOT / path).read_bytes() == b)
        names.append(path)
    tracked = set(git('ls-tree', '-r', '--name-only', SUB, '--', folder).decode().splitlines())
    check('manifest coverage:' + folder, tracked == set(names) | {folder + '/SHA256SUMS', folder + '/.gitattributes'}, len(names))
    return len(names)


submitted = manifest(SRC)
prior = manifest('docs/evidence/review-76387b25')
check('local HEAD and saved origin/main', git('rev-parse', 'HEAD').decode().strip() == SUB == git('rev-parse', 'origin/main').decode().strip())
for branch, sha in [('candidate/dynamic-mode-strict-0e1d2a02', '18f3a7956ae28b59a6f4235889fe60cb00c399fa'),
                    ('test/p1-cap-boundary-0e1d2a02', '2302104c5bc78f82c7f890960a67d39a21540dac')]:
    check('saved remote candidate:' + branch, git('rev-parse', branch).decode().strip() == sha == git('rev-parse', 'origin/' + branch).decode().strip())
for name, expected, count in [('mode-18f3a795', 48, 7), ('p1-2302104c', 8, 1)]:
    paths = list((ROOT / SRC / 'raw' / name / 'xml').glob('*.xml'))
    totals = [0, 0, 0, 0]
    for path in paths:
        r = ET.fromstring(path.read_bytes())
        vals = [int(r.get(k, '0')) for k in ('tests', 'failures', 'errors', 'skipped')]
        totals = [a+b for a, b in zip(totals, vals)]
    check('original XML:' + name, len(paths) == count and totals == [expected, 0, 0, 0], totals)
    source = blob(f'{SRC}/raw/{name}/source.txt').decode()
    check('source linkage:' + name, name.split('-')[1] in source and 'tracked_dirty 0' in source)
    folder = ROOT / SRC / 'raw' / name / 'logs'
    for line in (folder / 'original-logs.sha256').read_text().splitlines():
        h, n = line.split(maxsplit=1)
        n = n.lstrip('*')
        gz = folder / (n + '.gz')
        check('original gzip:' + name + '/' + n, hashlib.sha256(gzip.decompress(gz.read_bytes()) if gz.exists() else b'').hexdigest() == h)

link = blob(SRC + '/links/draft-test-links-v2.txt').decode('utf-8')
rows = [l.split(' | ') for l in link.splitlines() if ' | ' in l and l.split(' | ')[0].endswith('Test')]
oldxml = ROOT / 'docs/evidence/v168-final-20261115/runs/full-0e1d2a02/xml'
oldtot = [0, 0, 0, 0]
for row in rows:
    paths = list(oldxml.glob('*/TEST-*.' + row[0] + '.xml'))
    nums = [int(ET.fromstring(paths[0].read_bytes()).get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')] if len(paths) == 1 else []
    oldtot = [a+b for a, b in zip(oldtot, nums)]
    check('old XML linkage:' + row[0], len(paths) == 1 and hashlib.sha256(paths[0].read_bytes()).hexdigest().startswith(row[4]) and nums == [int(n) for n in row[2].split('/')])
check('56 unique classes / 49 success / 7 skipped', len(rows) == len({r[0] for r in rows}) == 56 and sum(r[3] == '성공' for r in rows) == 49 and sum(r[3] == '전부 건너뜀' for r in rows) == 7 and oldtot == [437, 0, 0, 43], oldtot)
java = 'web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java'
check('cooldown parent', git('rev-parse', COOL + '^').decode().strip() == BASE)
def patchid(base, head):
    patch = git('diff', base, head, '--', java)
    return git('patch-id', '--stable', data=patch).decode().split()[0]
check('cooldown product patch identity', patchid(BASE, COOL) == patchid('81b0e7a7^', '81b0e7a7'))
patch = git('diff', BASE, COOL).replace(b'\r\n', b'\n')
check('cooldown submitted diff', patch == blob(SRC + '/cooldown/candidate-89336440.diff').replace(b'\r\n', b'\n'))
cs = [ET.fromstring(p.read_bytes()) for p in (ROOT / SRC / 'cooldown/run-89336440/xml').glob('*.xml')]
ct = [sum(int(x.get(k, 0)) for x in cs) for k in ('tests', 'failures', 'errors', 'skipped')]
check('cooldown 5 classes / 53 successful', len(cs) == 5 and ct == [53, 0, 0, 0], ct)

p7 = ROOT / SRC / 'p7v2'
spec = importlib.util.spec_from_file_location('reviewed_p7v2', p7 / 'reconcile.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
saved = {}
for path in sorted((p7 / 'out').glob('*.json')):
    d = json.loads(path.read_text(encoding='utf-8'))
    result = m.reconcile(d['scenario']['reg'], d['base'], d['now'])
    check('replay pure saved input:' + path.stem, result == d['result'], result['overall'])
    saved[path.stem] = d
check('35 saved scenario inputs', len(saved) == 35)
mut = blob(SRC + '/p7v2/mutation.out.txt').decode()
check('seven saved mutation outcomes', len(mut.splitlines()) == 7 and all('rc=1 잡힌 시나리오 ' in l and '시나리오 0:' not in l for l in mut.splitlines()))

probes = []
def probe(name, stem, edit, expected, finding):
    d = copy.deepcopy(saved[stem])
    edit(d)
    try:
        r = m.reconcile(d['scenario']['reg'], d['base'], d['now'])
    except Exception as e:
        r = {'error': type(e).__name__, 'message': str(e)}
    got = r.get('overall', r.get('error'))
    probes.append({'name': name, 'source': stem, 'finding': finding, 'observed': got,
                   'input': {'registry': d['scenario']['reg'], 'base': d['base'], 'now': d['now']}, 'result': r})
    check('counterexample observed:' + name, got == expected, {'observed': got, 'finding': finding})

probe('P0_control', 'A1_eth_hold', lambda d: None, 'OK', 'Unchanged saved valid input')
probe('P1_wrong_code', 'A1_eth_hold', lambda d: d['scenario']['reg']['sessions'][1].update(code_sha='wrong-code'), 'OK', 'Registry code SHA is required but never matched to source evidence')
def nofp(d):
    for t in ('base', 'now'):
        d[t].pop('db_fp_pre', None); d[t].pop('db_fp_post', None); d[t]['db'].pop('fingerprint', None)
probe('P2_missing_fingerprints', 'A1_eth_hold', nofp, 'OK', 'Three missing fingerprints compare equal; observation_stable=True')
probe('P3_reversed_time', 'A1_eth_hold', lambda d: d['now']['db'].update(observed_at='2020-01-01T00:00:00Z'), 'OK', 'Current observation predates baseline')
probe('P4_known_residual_hidden', 'G2_unexplained_1_50_two_sessions', lambda d: d['now']['db']['positions'][0].update(status='CLOSING'), 'HOLD', 'Stable records, final orders, unexplained KRW -1.50; CLOSING globally suppresses account anomaly')
probe('P5_unbacked_hidden', 'F4_coin_withdraw_declared', lambda d: d['now']['db']['positions'][0].update(status='CLOSING'), 'HOLD', 'Exchange BTC=0 versus internal 0.0002; CLOSING suppresses backing anomaly')
def empty_done(d):
    for side in ('exchange_before', 'exchange_after'):
        d['now'][side]['done_order_ids'] = []
probe('P6_missing_exchange_coverage', 'A1_eth_hold', empty_done, 'OK', 'DB filled UUID not present in purported complete exchange list; only reverse direction checked')
probe('P7_invalid_rule_number', 'A1_eth_hold', lambda d: d['scenario']['reg']['sessions'][1]['rules'].update(max_buy_request_krw='NaN'), 'InvalidOperation', 'Invalid registry numeric value escapes structured verdict')

summary = {'fixed_submission': SUB, 'submitted_manifest_files': submitted, 'prior_manifest_files': prior,
           'checks_passed': sum(c['pass'] for c in checks), 'checks_failed': sum(not c['pass'] for c in checks),
           'scope': 'Git and saved files; pure P7 input replay/probes. No new Java/PG/network runs.', 'checks': checks}
for name, value in [('checks.json', summary), ('counterexamples.json', probes)]:
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps({k: v for k, v in summary.items() if k != 'checks'}, ensure_ascii=False))
raise SystemExit(bool(summary['checks_failed']))
