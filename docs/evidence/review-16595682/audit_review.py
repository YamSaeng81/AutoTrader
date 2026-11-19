"""Director v1.191. Fixed Git blobs, saved results, local pure calculations.
No network, database, Docker, Java tests, builds, commit or push.
"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
from decimal import Decimal as D

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
E = ROOT / 'docs/evidence'
HEAD = '16595682af4f22b281242273f813e8fe11bbbdc7'
BASE = '0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d'
ALERT = 'dfe3b9f7d3f8b3950afc299806d48c5aae7494bf'
checks, anchors, observations = [], {}, {}


def check(name, ok, detail=None):
    checks.append({'check': name, 'passed': bool(ok), 'detail': detail})


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, stderr=subprocess.DEVNULL)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    return json.loads(path.read_text('utf-8'))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    anchors[path.relative_to(ROOT).as_posix()] = digest(path.read_bytes())
    return module


def manifest(name, count):
    folder = E / name
    rows = [line.split(maxsplit=1) for line in (folder / 'SHA256SUMS').read_text('utf-8').splitlines()]
    names = [Path(x[1].lstrip('*')).as_posix().removeprefix('./') for x in rows]
    check('manifest count ' + name, len(rows) == len(set(names)) == count)
    for (expected, _), rel in zip(rows, names):
        check('manifest hash ' + name + '/' + rel, digest((folder / rel).read_bytes()) == expected)
    prefix = folder.relative_to(ROOT).as_posix()
    paths = [p for p in git('ls-tree', '-rz', '--name-only', HEAD, '--', prefix).decode().split('\0') if p]
    check('manifest coverage ' + name,
          set(paths) == {prefix + '/' + n for n in names} | {prefix + '/SHA256SUMS', prefix + '/README.md', prefix + '/.gitattributes'})
    # Some earlier director manifests include README and .gitattributes themselves.
    requests = ''.join(HEAD + ':' + p + '\n' for p in paths).encode()
    data = subprocess.check_output(['git', 'cat-file', '--batch'], input=requests, cwd=ROOT)
    at = 0
    for path in paths:
        end = data.index(b'\n', at)
        header = data[at:end].split()
        assert header[1] == b'blob'
        n = int(header[2])
        start = end + 1
        blob = data[start:start + n]
        check('fixed blob ' + path, (ROOT / path).read_bytes() == blob)
        at = start + n + 1
    anchors[name] = {'manifest_entries': count, 'tracked_files': len(paths),
                     'manifest_sha256': digest((folder / 'SHA256SUMS').read_bytes())}


def xml(path):
    root = ET.parse(path).getroot()
    return {k: int(root.attrib.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')}


check('HEAD', git('rev-parse', 'HEAD').decode().strip() == HEAD)
check('saved origin/main', git('rev-parse', 'origin/main').decode().strip() == HEAD)
for name, count in (
        ('dec-pnl-definition-20261119', 4), ('dec-scorecard-v2-20261118', 65),
        ('dec-amtbtc-pg-20261119', 4), ('dec-min-alert-20261119', 10),
        ('review-f14ff757', 5)):
    manifest(name, count)
plan_path = 'docs/evidence/dec-ops-plan-20261119/README.md'
check('fixed operations proposal', git('show', HEAD + ':' + plan_path) == (ROOT / plan_path).read_bytes())
anchors[plan_path] = digest((ROOT / plan_path).read_bytes())

scdir = E / 'dec-scorecard-v2-20261118/scorecard'
sc = load('director_scorecard_v2', scdir / 'scorecard.py')
cases = load('director_scorecard_cases_v2', scdir / 'cases_scorecard.py')
saved_counts = {'calculated': 0, 'rejected': 0}
for path in sorted((scdir / 'run1').glob('*.json')):
    saved = read(path)
    if 'input' not in saved:
        continue
    if 'result' in saved:
        check('saved scorecard full output ' + path.stem, sc.build(copy.deepcopy(saved['input'])) == saved['result'])
        saved_counts['calculated'] += 1
    elif 'rejected' in saved:
        try:
            sc.build(copy.deepcopy(saved['input']))
            message = None
        except sc.Bad as exc:
            message = str(exc)
        check('saved scorecard rejection ' + path.stem, message == saved['rejected'], message)
        saved_counts['rejected'] += 1
for name, _, inp, predicate in cases.CASES:
    check('scorecard expectation ' + name, all(predicate(sc.build(copy.deepcopy(inp)))))
for name, _, inp, needle in cases.BADCASES:
    try:
        sc.build(copy.deepcopy(inp))
        message = ''
    except sc.Bad as exc:
        message = str(exc)
    check('scorecard negative expectation ' + name, needle in message, message)
check('saved scorecard 52 summary', '합계 52 통과 52 실패 0' in (scdir / 'run1/cases.out.txt').read_text('utf-8'))
check('saved scorecard 10 mutations', '변형 10 검출 10 놓침 0' in (scdir / 'run1/mutate.out.txt').read_text('utf-8'))
check('saved v1 four counterexamples', '반례 4 중 v1 실패 재현 4' in (scdir / 'run0-v1-red/red.out.txt').read_text('utf-8'))
check('saved demo full output', sc.build(read(scdir / 'run1/demo.input.json')) == read(scdir / 'run1/demo/scorecard.json'))
observations['scorecard_recalculated'] = saved_counts

# Two additional file-input observations. These are synthetic calculations.
inp = copy.deepcopy(cases.inp([cases.sess(991)]))
inp['sessions'][0]['trades'][0]['buy']['settled_at'] = None
r = sc.build(inp)
c = r['sessions'][0]
check('missing buy time leaves numeric fee subtotal', c['fees_paid'] == '26' and bool(c['unconfirmed_reasons']))
observations['fee_time_unknown'] = {'input': inp, 'result': r,
    'expected_contract': 'Buy fee 25 may belong inside or outside December. Known sell fee 26 is a partial sum, not a confirmed total.'}
inp2 = copy.deepcopy(cases.inp([cases.sess(992)]))
inp2['sessions'][0]['trades'][0]['sells'][0]['settled_at'] = '2026-12-01T09:00:00+09:00'
r2 = sc.build(inp2)
check('sell earlier than buy accepted with negative holding hours',
      r2['sessions'][0]['holding_hours_median'] == -24.0 and r2['sessions'][0]['round_trips_closed'] == 1)
observations['sell_before_buy'] = {'input': inp2, 'result': r2,
    'expected_contract': 'Reject impossible chronology or mark it unconfirmed before counting the completed round trip.'}

pg = E / 'dec-pnl-definition-20261119'
pnl = read(pg / 'run1/pnl_consumers.json')
check('saved pnl PG XML', xml(pg / 'run1/TEST-com.cryptoautotrader.api.service.RealizedPnlDefinitionPgTest.xml') ==
      {'tests': 1, 'failures': 0, 'errors': 0, 'skipped': 0})
a, b = pnl['A_sign_flip'], pnl['B_true_loss']
for key in ('live', 'dynamic_real'):
    x, y = a[key], b[key]
    check('A cost arithmetic ' + key, D(x['net_sell_proceeds']) - D(x['invested_krw']) == D(x['cost_basis_realized_arith']) == D('-2.75'))
    check('A stored positive ' + key, D(x['stored_realized_pnl']) == D('1'))
    check('B cost arithmetic ' + key, D(y['net_sell_proceeds']) - D(y['invested_krw']) == D('-107.45'))
    check('B omitted buy fee ' + key, D(y['stored_realized_pnl']) - D(y['cost_basis_realized_arith']) == D('3.75'))
g = a['guards_at_0_01pct']
check('A both loss sources zero', all(D(g[k]) == 0 for k in ('sumLiveSessionRealizedLossSince', 'sumDynamicRealSessionRealizedLossSince')))
check('A guards approve', all(g[k]['approved'] for k in ('checkRisk', 'checkRealLossGuard')))
check('A breakers differ by mode', [x['triggered'] for x in a['circuit_breakers_limit1']] == [False, False, True])
check('B loss SQL observations', all(D(b['guards_since_B_at_0_01pct'][k]) == D('-103.70')
      for k in ('sumLiveSessionRealizedLossSince', 'sumDynamicRealSessionRealizedLossSince')))
check('C static stop observations', not pnl['C_stop_loss_line']['stopHit_real'] and pnl['C_stop_loss_line']['stopHit_cost'])
observations['pnl_saved'] = pnl
for name in ('dec-pnl-definition-20261119', 'dec-amtbtc-pg-20261119'):
    guard = (E / name / 'run1/NET-GUARD-summary.txt').read_text('utf-8')
    check('saved no external attempts ' + name, 'installed_during_run=true' in guard and 'blocked_external_attempts=0' in guard)
amtdir = E / 'dec-amtbtc-pg-20261119'
check('saved amount PG XML', xml(amtdir / 'run1/TEST-com.cryptoautotrader.api.service.AmountBtcPgTest.xml') ==
      {'tests': 1, 'failures': 0, 'errors': 0, 'skipped': 0})
amt = read(amtdir / 'run1/amount_btc_pg.json')
j = amt['1_jsonb_restore_fingerprint']
check('PG canonical JSON and fingerprints', j['jsonb_a'] == j['jsonb_c_other_order'] and
      j['hash_at_create'] == j['hash_after_reload'] == j['hash_c'] == '895ab6b8dc16' and j['hash_fixed_no_keys'] == 'ea1ed76bc345')
check('PG cap and reservation', D(amt['2_cap_reservation']['capped_order_row']['reserved_krw']) == D('10000') and
      D(amt['2_cap_reservation']['capped_available_after']) == D('15001') and amt['2_cap_reservation']['plain_buy_orders'] == 0)
check('PG BTC gate and existing holding sale', amt['3_btc_exclusion_gate']['btc_buy_orders'] == 0 and
      amt['3_btc_exclusion_gate']['tick_btc_candles_missing_buys'] == 0 and
      amt['3_btc_exclusion_gate']['tick_btc_candles_fresh_coins'] == ['KRW-IOTA'] and
      amt['4_existing_btc_holding_real_sell']['position_status'] == 'CLOSED')
observations['amount_saved'] = amt

ad = E / 'dec-min-alert-20261119'
check('alert candidate parent', git('rev-parse', ALERT + '^').decode().strip() == BASE)
diff = git('diff', BASE, ALERT)
check('alert submitted diff', diff.replace(b'\r\n', b'\n') == (ad / 'candidate-dfe3b9f7.diff').read_bytes().replace(b'\r\n', b'\n'))
testpath = 'web-api/src/test/java/com/cryptoautotrader/api/service/TelegramMinimalAlertTest.java'
check('alert test fixed blob', git('show', ALERT + ':' + testpath).replace(b'\r\n', b'\n') ==
      (ad / 'source/TelegramMinimalAlertTest.java').read_bytes().replace(b'\r\n', b'\n'))
stats = [xml(p) for p in (ad / 'run1').glob('TEST-*.xml')]
totals = {key: sum(s[key] for s in stats) for key in ('tests', 'failures', 'errors', 'skipped')}
check('alert saved XML 8 classes 81 pass', len(stats) == 8 and totals == {'tests': 81, 'failures': 0, 'errors': 0, 'skipped': 0}, totals)
observations['alert_xml'] = {'classes': len(stats), **totals}

paper = git('show', BASE + ':web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java')
check('LIVE_ALIGNED already uses fill price for decisions', b'avgPriceForDecision = alignedMode ? price : avgPriceWithFee' in paper)
check('LIVE_ALIGNED old risk adds back fee difference', b'return p.getRealizedPnl().add(p.getInvestedKrw().subtract(p.getSize().multiply(p.getAvgPrice())))' in paper)
anchors['fixed_paper_service'] = digest(paper)
observations['aligned_scope'] = 'Source confirms fill-price decision average already exists. Submitted PG does not execute LIVE_ALIGNED. Static stopHit with a cost average is not its engine-path reproduction.'

OUT.mkdir(parents=True, exist_ok=True)
summary = {'fixed_head': HEAD, 'scope': 'Fixed blobs, saved XML/JSON and pure file calculations; no Java/PG execution.',
           'passed': sum(x['passed'] for x in checks), 'failed': sum(not x['passed'] for x in checks),
           'anchors': anchors, 'checks': checks}
for name, value in (('checks.json', summary), ('observations.json', observations)):
    with (OUT / name).open('w', encoding='utf-8', newline='\n') as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.write('\n')
print(json.dumps({'passed': summary['passed'], 'failed': summary['failed'],
                  'failures': [c for c in checks if not c['passed']], 'scorecard': saved_counts}, ensure_ascii=False))
raise SystemExit(1 if summary['failed'] else 0)
