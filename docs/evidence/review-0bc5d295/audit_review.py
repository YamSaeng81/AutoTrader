"""Director v1.192: fixed blobs, saved XML/JSON, pure scorecard calculations.
SQL counterexamples use the extracted candidate SQL in SQLite memory, NOT PostgreSQL,
Spring or production. No network, Docker, Java test, build, commit or push.
"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
E = ROOT / 'docs/evidence'
HEAD = '0bc5d295e590959e99a01de674cf156c355148f0'
BASE = '0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d'
CAND = 'b861bdf90048cc94b6a2e239b3d1f545a56ed4cb'
INTEG = '9cbbda62817eb13cfe900e2bc018e6a230c1ca2c'
checks, obs, anchors = [], {}, {}


def ck(name, ok, detail=None):
    checks.append({'check': name, 'passed': bool(ok), 'detail': detail})


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, stderr=subprocess.DEVNULL)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    return json.loads(path.read_text('utf-8'))


def manifest(name, expected):
    folder = E / name
    rows = [x.split(maxsplit=1) for x in (folder / 'SHA256SUMS').read_text('utf-8').splitlines()]
    names = [x[1].lstrip('*').removeprefix('./') for x in rows]
    ck('manifest count ' + name, len(rows) == len(set(names)) == expected)
    for (want, _), rel in zip(rows, names):
        ck('manifest hash ' + name + '/' + rel, sha((folder / rel).read_bytes()) == want)
    prefix = folder.relative_to(ROOT).as_posix()
    paths = [p for p in git('ls-tree', '-rz', '--name-only', HEAD, '--', prefix).decode().split('\0') if p]
    extras = {prefix + '/' + n for n in ('SHA256SUMS', 'README.md', '.gitattributes') if (folder / n).exists()}
    ck('manifest coverage ' + name, set(paths) == {prefix + '/' + n for n in names} | extras)
    missing = sorted({prefix + '/' + n for n in names} - set(paths))
    obs['uncommitted_manifest_entries_' + name] = missing
    data = subprocess.check_output(['git', 'cat-file', '--batch'], cwd=ROOT,
                                  input=''.join(HEAD + ':' + p + '\n' for p in paths).encode())
    at = 0
    for path in paths:
        end = data.index(b'\n', at)
        header = data[at:end].split()
        assert header[1] == b'blob'
        n = int(header[2])
        blob = data[end + 1:end + 1 + n]
        ck('fixed blob ' + path, blob == (ROOT / path).read_bytes())
        at = end + 2 + n
    anchors[name] = {'manifest_entries': expected, 'tracked_files': len(paths)}


def xml(path):
    root = ET.parse(path).getroot()
    return {k: int(root.attrib.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')}


def xmls(folder):
    files = sorted(folder.glob('TEST-*.xml'))
    return {'files': len(files), **{k: sum(xml(p)[k] for p in files)
                                   for k in ('tests', 'failures', 'errors', 'skipped')}}


ck('HEAD', git('rev-parse', 'HEAD').decode().strip() == HEAD)
ck('saved origin/main', git('rev-parse', 'origin/main').decode().strip() == HEAD)
for name, count in [('dec-costbasis-20261119', 66), ('dec-scorecard-v3-20261119', 75),
                    ('dec-inclusion-20261119', 4), ('n03-1118-2200', 17), ('review-16595682', 6)]:
    manifest(name, count)

cost = E / 'dec-costbasis-20261119'
for folder, tests, failures in [('base-fail2', 1, 1), ('run-final', 1, 0),
                               ('affected-h2', 215, 1), ('affected-h2/rerun-PositionLimitTest', 13, 0),
                               ('affected-pg', 35, 0)]:
    got = xmls(cost / folder)
    ck('saved XML ' + folder, got['tests'] == tests and got['failures'] == failures and got['errors'] == 0, got)
    obs['xml_' + folder] = got
for folder, passed in [('base-fail2', 15), ('run-final', 48)]:
    got = read(cost / folder / 'cost_basis_checks.json')
    ck('saved 48 assertions ' + folder, len(got['checks']) == 48 and sum(x['pass'] for x in got['checks']) == passed)
both = read(cost / 'base_vs_candidate.json')
ck('base vs candidate 48 pairs', len(both) == 48 and sum(x['base_pass'] for x in both) == 15
   and all(x['candidate_pass'] for x in both))
ck('candidate source test fixed', git('show', CAND + ':web-api/src/test/java/com/cryptoautotrader/api/service/CostBasisRiskPgTest.java')
   == (cost / 'source/CostBasisRiskPgTest.java').read_bytes())
paths = git('diff', '--name-only', BASE, CAND).decode().splitlines()
ck('candidate paths product6 test8 only', len(paths) == 14 and sum('/src/main/java/' in p for p in paths) == 6
   and sum('/src/test/java/' in p for p in paths) == 8)
for kind, key in [('main', 'candidate-product.diff'), ('test', 'candidate-test.diff')]:
    actual = git('diff', BASE, CAND, '--', 'web-api/src/' + kind + '/java')
    ck('candidate ' + key, actual.replace(b'\r\n', b'\n') == (cost / key).read_bytes().replace(b'\r\n', b'\n'))

for label, commit, base, filename in [
        ('pnl original', '7750b547', BASE, 'RealizedPnlDefinitionPgTest.java'),
        ('amount original', 'd16ea176', 'aff357e2', 'AmountBtcPgTest.java')]:
    path = 'web-api/src/test/java/com/cryptoautotrader/api/service/' + filename
    changed = git('diff', '--name-only', base, commit).decode().splitlines()
    folder = 'dec-pnl-definition-20261119' if 'pnl' in label else 'dec-amtbtc-pg-20261119'
    ck('frozen test-only ' + label, changed == [path])
    ck('frozen original bytes ' + label, git('show', commit + ':' + path) == (E / folder / 'source' / filename).read_bytes())
    ck('saved remote branch contains ' + label,
       bool(git('branch', '-r', '--contains', commit).decode().strip()))
for commit in ['aff357e2', 'dfe3b9f7']:
    ck('saved remote contains ' + commit, bool(git('branch', '-r', '--contains', commit).decode().strip()))
listed = (E / 'dec-inclusion-20261119/integration-commits.txt').read_text('utf-8').splitlines()
actual = git('log', '-9', '--format=%H %s', INTEG).decode().splitlines()
ck('integration commit list fixed', listed == actual)
for original, relocated in zip(['dfe3b9f7', '09bf2eb5', 'd55fa916', '43017efa', '1744bb4b', 'b861bdf9'],
                               reversed([line.split()[0] for line in listed[:6]])):
    a, b = git('show', '--format=', original), git('show', '--format=', relocated)
    pa = subprocess.check_output(['git', 'patch-id', '--stable'], input=a, cwd=ROOT).split()[0]
    pb = subprocess.check_output(['git', 'patch-id', '--stable'], input=b, cwd=ROOT).split()[0]
    ck('integration patch-id ' + original, pa == pb)

scdir = E / 'dec-scorecard-v3-20261119/scorecard'
spec = importlib.util.spec_from_file_location('director_sc3', scdir / 'scorecard.py')
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)
saved_count = {'outputs': 0, 'rejections': 0}
for path in sorted((scdir / 'run1').glob('*.json')):
    got = read(path)
    if 'input' not in got:
        continue
    if 'result' in got:
        ck('scorecard full output ' + path.stem, sc.build(copy.deepcopy(got['input'])) == got['result'])
        saved_count['outputs'] += 1
    elif 'rejected' in got:
        try:
            sc.build(copy.deepcopy(got['input']))
            ck('scorecard rejection ' + path.stem, False)
        except sc.Bad as exc:
            ck('scorecard rejection ' + path.stem, str(exc) == got['rejected'])
        saved_count['rejections'] += 1
obs['scorecard_saved_calculations'] = saved_count
demo = read(scdir / 'run1/demo.input.json')
ck('scorecard full demo', sc.build(demo) == read(scdir / 'run1/demo/scorecard.json'))
text = (scdir / 'run1/cases.out.txt').read_text('utf-8')
ck('saved cases 60 no failure', '합계 60 통과 60 실패 0' in text and not any(x.startswith('FAIL') for x in text.splitlines()))
text = (scdir / 'run1/mutate.out.txt').read_text('utf-8')
obs['scorecard_mutation_summary'] = text.splitlines()[-1]

# Extract exact Java annotation string expressions, including the shared SQL constant.
rp = 'web-api/src/main/java/com/cryptoautotrader/api/repository/OrderRepository.java'
repo = git('show', CAND + ':' + rp).decode()
anchors['candidate_repository_sha256'] = sha(repo.encode())


def java_strings(expr, shared=''):
    return ''.join(shared if t == 'BUY_COST_BY_POSITION' else json.loads(t)
                   for t in re.findall(r'"(?:\\.|[^"\\])*"|BUY_COST_BY_POSITION', expr))


shared = java_strings(re.search(r'String BUY_COST_BY_POSITION\s*=\s*(.*?);', repo, re.S).group(1))


def query(method):
    upto = repo[:repo.index(' ' + method + '(')]
    annotation = upto[upto.rindex('@Query(value = '):]
    expr = annotation[len('@Query(value = '):].rsplit(', nativeQuery', 1)[0]
    # The count query has a newline before nativeQuery.
    expr = re.split(r',\s*nativeQuery', expr)[0]
    return java_strings(expr, shared)


qs = {m: query(m) for m in ['countRealSessionCostUndeterminedSince', 'sumLiveSessionRealizedLossSince',
                           'costBasisPartsByPosition']}
con = sqlite3.connect(':memory:')
con.executescript('CREATE TABLE position(id INTEGER, session_kind TEXT, session_id INTEGER, status TEXT, closed_at TEXT, realized_pnl REAL);'
                  'CREATE TABLE "order"(position_id INTEGER, side TEXT, session_kind TEXT, session_id INTEGER, filled_quantity REAL,'
                  'applied_executed_funds REAL, applied_paid_fee REAL, sell_applied_at TEXT);')
params = {'kind': 'LIVE', 'since': '2026-10-20', 'ids': 1}
con.execute("INSERT INTO position VALUES(1,'LIVE',1,'CLOSED','2026-11-19',1)")
no_rows = {'global_undetermined_count': con.execute(qs['countRealSessionCostUndeterminedSince'], params).fetchone()[0],
           'global_loss_sum': con.execute(qs['sumLiveSessionRealizedLossSince'], params).fetchone()[0],
           'cb_parts': con.execute(qs['costBasisPartsByPosition'], params).fetchone(),
           'stored_pnl': 1}
ck('counterexample no BUY no SELL global count is zero', no_rows['global_undetermined_count'] == 0)
ck('counterexample no source CB fallback exists', 'if (buyN == 0 && sellN == 0)' in git('show', CAND + ':web-api/src/main/java/com/cryptoautotrader/api/service/RiskManagementService.java').decode())
con.execute("INSERT INTO \"order\" VALUES(1,'BUY','LIVE',1,25,7500,3.75,NULL)")
con.execute("INSERT INTO \"order\" VALUES(1,'SELL','LIVE',1,25,7504.75,NULL,'2026-11-19')")
null_fee = {'global_undetermined_count': con.execute(qs['countRealSessionCostUndeterminedSince'], params).fetchone()[0],
            'global_loss_sum': con.execute(qs['sumLiveSessionRealizedLossSince'], params).fetchone()[0],
            'cb_parts': con.execute(qs['costBasisPartsByPosition'], params).fetchone(),
            'cost_pnl_if_actual_sell_fee_3_75': '-2.75'}
ck('counterexample NULL sell fee treated known', null_fee['global_undetermined_count'] == 0 and null_fee['global_loss_sum'] == 0
   and null_fee['cb_parts'][6] == 7504.75)
obs['source_counterexamples'] = {'method': 'Exact extracted candidate SQL executed in SQLite memory; Java fallback inspected, not executed. PostgreSQL/Spring regression required.',
                                'queries': qs, 'closed_no_order_source': no_rows, 'sell_fee_unknown': null_fee}
for name, value in [('checks.json', {'passed': sum(x['passed'] for x in checks), 'failed': sum(not x['passed'] for x in checks),
                                    'checks': checks, 'anchors': anchors}), ('observations.json', obs)]:
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps({'passed': sum(x['passed'] for x in checks), 'failed': sum(not x['passed'] for x in checks)}, ensure_ascii=False))
for item in checks:
    if not item['passed']:
        print(json.dumps(item, ensure_ascii=False))
