"""Review fixed Git blobs and preserved artifacts only; no app, PG, build or network."""
from pathlib import Path
import csv
import hashlib
import io
import json
import re
import subprocess
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SHA = 'e97ca31b23dec54db7c962395d2f8e60cc92afb0'
EV = 'docs/evidence/'
checks, observations = [], {}

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def check(name, passed, actual=None):
    checks.append({'check': name, 'pass': bool(passed), 'actual': actual})

def source(sha, name):
    return git('show', sha + ':' + name).decode('utf-8')

def batch(paths):
    result = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT,
                            input=''.join(SHA + ':' + p + '\n' for p in paths).encode(),
                            stdout=subprocess.PIPE, check=True).stdout
    stream, out = io.BytesIO(result), {}
    for path in paths:
        header = stream.readline().decode().split()
        assert len(header) == 3 and header[1] == 'blob', (path, header)
        out[path] = stream.read(int(header[2]))
        assert stream.read(1) == b'\n'
    assert not stream.read()
    return out

names = git('diff-tree', '--no-commit-id', '--name-only', '-r', SHA).decode().splitlines()
blobs = batch(names)
check('submission changed files', len(names) == 614, len(names))
evidence_blobs = {p: data for p, data in blobs.items() if p.startswith(EV)}
bad_local = [p for p, data in evidence_blobs.items() if not (ROOT / p).is_file() or (ROOT / p).read_bytes() != data]
check('611 submitted evidence blobs equal local preserved files', len(evidence_blobs) == 611 and not bad_local, bad_local)
check('permission settings not in submission', '.claude/settings.local.json' not in names)
roots = {
    'dec-costbasis-followup-20261119': 81,
    'dec-integ-abc-20261119': 349,
    'dec-n3-oldbinary-20261119': 48,
    'dec-predeploy-parity-20261119': 20,
    'dec-real-buy-summary-20261119': 87,
    'review-9687b751': 5,
    'review-abc-progress-20261119': 5,
    'review-abc-regression-20261119': 6,
}
manifest_total = 0
for folder, expected in roots.items():
    prefix = EV + folder + '/'
    lines = blobs[prefix + 'SHA256SUMS'].decode('utf-8-sig').splitlines()
    entries = [re.fullmatch(r'([0-9a-f]{64}) [ *](.+)', line) for line in lines]
    assert all(entries), folder
    bad = [m[2] for m in entries if hashlib.sha256(blobs[prefix + m[2]]).hexdigest() != m[1]]
    check(folder + ' committed manifest', len(entries) == expected and not bad,
          {'listed': len(entries), 'mismatches': bad})
    manifest_total += len(entries)
check('manifest entries total', manifest_total == 601, manifest_total)

def data(folder, path):
    return blobs[EV + folder + '/' + path]

def obj(folder, path):
    return json.loads(data(folder, path))

def xml_stats(folder, prefix):
    full = EV + folder + '/' + prefix
    files = [p for p in blobs if p.startswith(full) and '/TEST-' in p and p.endswith('.xml')]
    totals, failures = [0, 0, 0, 0], []
    for p in files:
        node = ET.fromstring(blobs[p])
        totals = [a + int(node.get(k, 0)) for a, k in zip(totals, ('tests', 'failures', 'errors', 'skipped'))]
        for case in node.iter('testcase'):
            for f in list(case.findall('failure')) + list(case.findall('error')):
                failures.append({'test': case.get('name'), 'message': f.get('message', '')[:400]})
    return {'files': len(files), 'totals': totals, 'failures': failures}

integ = 'dec-integ-abc-20261119'
runs = {
    'sma20-A-aff357e2': [24, 0, 0, 0], 'sma20-C-b408a223': [24, 1, 0, 0],
    'sma20-fx-09fa2d1c': [24, 0, 0, 0], 'lw-base-0e1d2a02': [7, 0, 0, 0],
    'lw-C-b408a223': [7, 6, 0, 0], 'lw-base-60df3437': [7, 6, 0, 0],
}
observations['minimal_runs'] = {}
for run, expected in runs.items():
    stats = xml_stats(integ, 'regression-cause/' + run + '/xml/')
    check(run + ' XML', stats['files'] == 1 and stats['totals'] == expected, stats['totals'])
    observations['minimal_runs'][run] = stats
full = xml_stats(integ, 'full-60df3437/xml/')
check('original failed full regression preserved', full['files'] == 288 and full['totals'] == [1866, 1, 0, 93], full['totals'])
lw_skip = ET.fromstring(data(integ, 'full-60df3437/xml/web-api/TEST-com.cryptoautotrader.api.service.LiveLossWindowPgTest.xml'))
check('LiveLossWindow was skipped in full run', lw_skip.get('tests') == '7' and lw_skip.get('skipped') == '7')

test = 'web-api/src/test/java/com/cryptoautotrader/api/service/DailySma20LiveOrderPathTest.java'
fixture = source('09fa2d1c', test)
changed = git('diff', '--name-only', '60df3437', '09fa2d1c').decode().splitlines()
check('SMA fixture only one test file', changed == [test], changed)
check('SMA fixture net diff', git('diff', '--numstat', '60df3437', '09fa2d1c').decode().startswith('49\t1\t'))
check('confirmed BUY and SELL source and readiness assertion', all(x in fixture for x in
      ('List.of("BUY", "SELL")', '.positionId(p.getId())', '.sessionId(s.getId()).sessionKind("LIVE")',
       'filled_quantity = 0.001', 'new BigDecimal("15000")', 'new BigDecimal("15100")',
       'new BigDecimal("7.5")', 'new BigDecimal("7.55")', 'costBasisEntryBlock(fresh)', '.hasSize(1)',
       '.isEqualTo("SUBMITTED")', '.startsWith("sma20-new-")')))
helper_pattern = r'private List<OrderEntity> orders\(.*?\n    \}'
check('common orders helper preserved', re.search(helper_pattern, fixture, re.S)[0] ==
      re.search(helper_pattern, source('60df3437', test), re.S)[0])
lw = 'web-api/src/test/java/com/cryptoautotrader/api/service/LiveLossWindowPgTest.java'
check('LW fixture unchanged on prod, C and integration', git('show', '0e1d2a02:' + lw) ==
      git('show', 'b408a223:' + lw) == git('show', '60df3437:' + lw))
lw_source = source('60df3437', lw)
helper = re.search(r'private PositionEntity closingPosition\(.*?\n    \}', lw_source, re.S)[0]
check('LW position helper has no buy source and explicit zero position fee',
      'orderRepository' not in helper and '.positionFee(BigDecimal.ZERO)' in helper)

n3 = 'dec-n3-oldbinary-20261119'
for old in ('0e1d2a02', 'b5cd1d0d'):
    run = 'n3old2-' + old + '/'
    result = obj(n3, run + 'old_binary_new_enum.json')
    first = result['stage1_new_value_present']
    second = result['stage2_same_rows_reason_null']
    check(old + ' six actual query paths reject enum', len([v for k,v in first.items() if k.startswith(('P1_', 'P2_', 'P3_', 'P4_', 'P5_', 'P6_'))
          and not v['ok'] and 'No enum constant' in v.get('root', '')]) == 6)
    check(old + ' NULL controls succeed', len(second) == 6 and all(v['ok'] for v in second.values()))
    check(old + ' gate v3 passes / v4 blocks', b'ROLLBACK_GATE_OK' in data(n3, run + 'gate-v3.txt') and
          b'STOP_INCOMPATIBLE' in data(n3, run + 'gate-v4.txt') and b'rc=3' in data(n3, run + 'gate-v4.txt'))
    check(old + ' gate preserves rows', data(n3, run + 'rows_before_gate.txt') == data(n3, run + 'rows_after_gate.txt'))

parity = obj('dec-predeploy-parity-20261119', 'sqlpar3-60df3437/predeploy_v3_parity.json')
check('SQL v3 parity 96 comparisons', len(parity['parity']) == 96 and not parity['failures'] and
      all(x['match'] and x['expected_sql_v3'] == x['actual_product'] for x in parity['parity']))
tsv = data('dec-predeploy-parity-20261119', 'parity_table.tsv').decode('utf-8')
check('TSV matches JSON parity rows', list(csv.DictReader(io.StringIO(tsv), delimiter='\t')) ==
      [{**x, 'match': str(x['match'])} for x in parity['parity']])

rbs = 'dec-real-buy-summary-20261119'
for run, failed in [('rbs-base-c8cb48a0', 18), ('rbs-fix2-44b5bf4b-dirty', 0)]:
    result = obj(rbs, run + '/real_buy_summary_checks.json')
    check(run + ' 23 assertions', len(result['checks']) == 23 and sum(not c['pass'] for c in result['checks']) == failed,
          {'checks': len(result['checks']), 'failed': sum(not c['pass'] for c in result['checks'])})
prodpath = 'web-api/src/main/java/com/cryptoautotrader/api/service/OrderSettlementService.java'
patch = git('diff', '44b5bf4b', '69c81c3d', '--', prodpath)
saved = data(rbs, 'rbs-fix2-44b5bf4b-dirty/product.diff')
check('dirty successful product diff equals committed fix after CRLF normalization',
      patch.replace(b'\r\n', b'\n') == saved.replace(b'\r\n', b'\n'))
scope = git('diff', '--name-only', '60df3437', '596ed4b0').decode().splitlines()
check('BUY candidate changes one product and two test files', scope == [prodpath,
      'web-api/src/test/java/com/cryptoautotrader/api/service/AbcCrossPgTest.java',
      'web-api/src/test/java/com/cryptoautotrader/api/service/RealBuySummaryPgTest.java'], scope)
prod = source('596ed4b0', prodpath)
check('BUY buffer follows REQUIRES_NEW execution and skips nonpositive delta',
      'PROPAGATION_REQUIRES_NEW' in prod and
      'BuyIncrement applied = settlementTx.execute(st -> applyBuyFillAccounting(orderId));' in prod and
      'bufferCommittedBuy(applied);' in prod and 'a.deltaVolume().signum() <= 0' in prod)
check('BUY buffer failure leaves committed accounting', 'catch (RuntimeException e)' in prod and
      '정산은 커밋됨' in prod)
impact_specs = [('rbs-impact-69c81c3d/h2-8/', [93,0,0,0]),
                ('rbs-impact2-596ed4b0/pg-AbcCrossPgTest/', [1,0,0,0]),
                ('rbs-impact2-596ed4b0/pg-ClosingIntentAtomicityPgTest/', [5,0,0,0]),
                ('rbs-impact2-596ed4b0/pg-PartialSellRealizedPnlPgTest/', [7,0,0,0]),
                ('rbs-impact2-596ed4b0/pg-RealLossThreeFormulaPgTest/', [8,0,0,0]),
                ('rbs-impact2-596ed4b0/pg-LiveLossWindowPgTest/', [7,6,0,0])]
observations['buy_impact'] = {}
for path, expected in impact_specs:
    stats = xml_stats(rbs, path)
    check(path + ' XML', stats['files'] > 0 and stats['totals'] == expected, stats['totals'])
    observations['buy_impact'][path] = stats
cross = obj(rbs, 'rbs-impact2-596ed4b0/pg-AbcCrossPgTest/abc_cross_checks.json')
check('AbcCross 19 assertions', len(cross['checks']) == 19 and not cross['failures'] and all(c['pass'] for c in cross['checks']))
observations.update({'submission': SHA, 'changed_files': len(names), 'evidence_files': len(evidence_blobs), 'manifest_entries': manifest_total,
    'limits': ['No product test, PostgreSQL, Docker, build, SSH or network command executed.',
               'Saved XML and JSON are reviewed results, not newly rerun tests.',
               'The 23/23 BUY result was run on 44b5bf4b with a dirty product patch; patch contents match 69c81c3d after newline normalization. A clean final-SHA run remains required.',
               'LiveLossWindow fixture has not been fixed. Other skipped PG fixture coverage is not established.',
               'Three current management documents are deliberately excluded from local byte equality because this review updates them; all submitted evidence blobs are checked.',
               'Remote equality is developer-reported and local saved refs only; no remote query was made.']})
(HERE / 'checks.json').write_text(json.dumps({'passed': sum(c['pass'] for c in checks),
    'failed': sum(not c['pass'] for c in checks), 'checks': checks}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(HERE / 'observations.json').write_text(json.dumps(observations, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'passed': sum(c['pass'] for c in checks), 'failed': sum(not c['pass'] for c in checks),
                  'manifests': manifest_total, 'submission_files': len(names)}, ensure_ascii=False))
raise SystemExit(0 if all(c['pass'] for c in checks) else 1)
