"""Director v1.190: fixed blobs, saved inputs, local pure functions only.
No network, DB, Docker, Java tests, build, commit or push.
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
HEAD = 'f14ff7576fe58564ab95f3158e33e9d3130872e3'
BASE = '0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d'
checks, anchors, observations = [], {}, {}


def check(name, ok, detail=None):
    checks.append({'check': name, 'passed': bool(ok), 'detail': detail})


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, stderr=subprocess.DEVNULL)


def digest(b):
    return hashlib.sha256(b).hexdigest()


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    anchors[str(path.relative_to(ROOT))] = digest(path.read_bytes())
    return m


def read(p):
    return json.loads(p.read_text('utf-8'))


def manifest(name, expected):
    folder = E / name
    rows = [x.split(maxsplit=1) for x in (folder / 'SHA256SUMS').read_text('utf-8').splitlines()]
    names = [x[1].lstrip('*') for x in rows]
    check('manifest count:' + name, len(rows) == len(set(names)) == expected, len(rows))
    for (h, _), n in zip(rows, names):
        check('file hash:' + name + '/' + n, digest((folder / n).read_bytes()) == h)
    rel = folder.relative_to(ROOT).as_posix()
    paths = [p for p in git('ls-tree', '-rz', '--name-only', HEAD, '--', rel).decode().split('\0') if p]
    check('manifest coverage:' + name, set(paths) == {rel + '/' + n for n in names} | {rel + '/SHA256SUMS', rel + '/.gitattributes'})
    requests = b''.join((HEAD + ':' + p + '\n').encode() for p in paths)
    data = subprocess.check_output(['git', 'cat-file', '--batch'], input=requests, cwd=ROOT)
    at = 0
    for p in paths:
        end = data.index(b'\n', at)
        header = data[at:end].split()
        assert header[1] == b'blob'
        n = int(header[2])
        at = end + 1
        check('fixed blob:' + p, data[at:at+n] == (ROOT / p).read_bytes())
        at += n + 1
    assert at == len(data)


def xml_summary(folder):
    roots = [ET.parse(p).getroot() for p in sorted(folder.glob('TEST-*.xml'))]
    return {'classes': len(roots), **{k: sum(int(r.get(k, 0)) for r in roots) for k in ('tests', 'failures', 'errors', 'skipped')}}


check('fixed HEAD', git('rev-parse', 'HEAD').decode().strip() == HEAD)
check('saved origin/main', git('rev-parse', 'origin/main').decode().strip() == HEAD)
for name, n in [('dec-reconcile-v5-20261118', 152), ('dec-amtbtc-20261118', 21),
                ('dec-scorecard-20261118', 28), ('dec-pg-sell-20261118', 66), ('review-87126a31', 6)]:
    manifest(name, n)

v5 = E / 'dec-reconcile-v5-20261118/p7v5'
m = module('director_p7v5', v5 / 'reconcile.py')
saved = pure = 0
for p in sorted((v5 / 'run-v5/cases').glob('*.json')):
    d = read(p)
    saved += 1
    if 'input' not in d:
        check('saved file exit:' + p.stem, d['rc'] == m.EXIT[d['result']['overall']])
        continue
    pure += 1
    i = d['input']
    r = m.evaluate(copy.deepcopy(i['registry']), copy.deepcopy(i['base']), copy.deepcopy(i['now']))
    check('v5 saved full output:' + p.stem, r == d['result'])
check('v5 saved records', saved == 65 and pure == 62, [saved, pure])
cf = module('director_v5_cases', v5 / 'cases_v5.py')
r_count = 0
for name, d in cf.r_cases():
    i = cf.synth(d['input'])
    orig = m.order_effects
    if name == 'N11_internal_error_is_error':
        message = d['result']['message']
        def injected(_):
            raise RuntimeError(message)
        m.order_effects = injected
    r = m.evaluate(copy.deepcopy(i['registry']), copy.deepcopy(i['base']), copy.deepcopy(i['now']))
    m.order_effects = orig
    check('v4 full output under explicit synthetic axes:' + name, cf.strip_clock(r) == d['result'])
    r_count += 1
check('v4 synthetic records', r_count == 99)
check('saved v5 summary', '263' in (v5 / 'run-v5/cases.out.txt').read_text('utf-8') and '실패 0' in (v5 / 'run-v5/cases.out.txt').read_text('utf-8'))
check('saved clock mutants', (v5 / 'mutation.out.txt').read_text('utf-8').count('KILLED |') == 12)

am = E / 'dec-amtbtc-20261118'
for folder, want in [('red', (1,23,19)), ('green', (9,69,0)), ('integration', (5,59,0))]:
    x = xml_summary(am / folder)
    observations['amount_xml_' + folder] = x
    check('saved amount XML:' + folder, (x['classes'], x['tests'], x['failures']) == want and x['errors'] == x['skipped'] == 0, x)
check('amount parent', git('rev-parse', 'c70a8330^').decode().strip() == BASE)
check('integration parent', git('rev-parse', 'aff357e2^').decode().strip() == git('rev-parse', 'ac52715e').decode().strip())
for label, a, b in [('amount', BASE, 'c70a8330'), ('integration', 'ac52715e', 'aff357e2')]:
    paths = git('diff', '--name-only', a, b).decode().splitlines()
    check(label + ' exact paths', set(paths) == {
        'web-api/src/main/java/com/cryptoautotrader/api/service/DynamicEntryLimits.java',
        'web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java',
        'web-api/src/test/java/com/cryptoautotrader/api/service/DynamicEntryLimitsTest.java'}, paths)
    diff = git('diff', '-U0', a, b)
    pid = subprocess.check_output(['git', 'patch-id', '--stable'], input=diff, cwd=ROOT).decode().split()[0]
    anchors[label + '_patch_id'] = pid
check('amount patches same', anchors['amount_patch_id'] == anchors['integration_patch_id'])
check('preserved diff', git('diff', BASE, 'c70a8330').decode().replace('\r\n','\n') == (am / 'c70a8330.diff').read_text('utf-8'))

sell = E / 'dec-pg-sell-20261118'
x = xml_summary(sell / 'run1')
check('saved sell PG XML', x == {'classes':1,'tests':1,'failures':0,'errors':0,'skipped':0}, x)
count = 0
for p in sorted((sell / 'run1/pairs').glob('*.json')):
    d = read(p)
    if 'input' not in d:
        continue
    i = d['input']
    r = m.evaluate(copy.deepcopy(i['registry']), copy.deepcopy(i['base']), copy.deepcopy(i['now']))
    check('sell saved full output:' + p.stem, r == d['result'])
    for which in ('base_snapshot', 'now_snapshot'):
        sn = d[which]
        check('sell input snapshot:' + p.stem + '/' + which, digest((sell / 'run1/snaps' / (sn['label'] + '.json')).read_bytes()) == sn['sha256'])
    count += 1
check('sell pairs count', count == 24)
observations['sell_realized'] = read(sell / 'run1/pairs/realized_check.json')
for name, v in observations['sell_realized'].items():
    q = D(v['position'][0]['size'])
    # Partial sale is 10 of 25; closed position retains remaining size 15.
    fraction = D('0.4') if name.endswith('partial') else D(1)
    check('saved fee difference:' + name, D(v['stored_minus_orders']) == D(v['buy_fee']) * fraction)

scpath = E / 'dec-scorecard-20261118/scorecard'
sc = module('director_scorecard', scpath / 'scorecard.py')
sc_cases = module('director_scorecard_cases', scpath / 'cases_scorecard.py')
for name, why, i, expect in sc_cases.CASES:
    r = sc.build(copy.deepcopy(i))
    check('saved scorecard expectation:' + name, all(expect(r)))

def probe(name, why, inp, evidence):
    result = sc.build(copy.deepcopy(inp))
    observations[name] = {'why': why, 'input': inp, 'result': result, 'evidence': evidence(result)}
    check('counterexample reproduced:' + name, observations[name]['evidence'])

# All following values are synthetic local inputs, not production observations.
s = sc_cases.sess(901, start='110', end='120', trades=[sc_cases.rt(
    buy_at='2026-11-25T09:00:00+09:00', bf='100', bfee='0', sf='120', sfee='0', qty='1', stored='20')])
s['start'] = {'available_krw':'0','positions':[{'id':1,'coin':'KRW-RND','status':'OPEN','size':'1','price':'110'}]}
probe('S1_carry_in_unrealized', 'Month profit 10, lifetime realized 20, no ending positions: end unrealized must be zero; -10 is change in unrealized, not ending unrealized.',
      sc_cases.inp([s]), lambda r: r['sessions'][0]['month_pnl']=='10' and r['sessions'][0]['unrealized_derived']=='-10')
s = sc_cases.sess(902,start='100',end='100',trades=[],flows=[{'at':'2027-01-02T00:00:00+09:00','amount_krw':'50','kind':'ALLOCATE'}])
probe('S2_out_of_period_flow', 'An out-of-month allocation is accepted and subtracted from December profit.', sc_cases.inp([s]),
      lambda r: r['sessions'][0]['month_pnl']=='-50')
real = sc_cases.sess(903,start='100',end='110',trades=[],kind='LIVE')
paper = sc_cases.sess(904,start='100',end='110',trades=[],kind='PAPER',mode='PAPER')
acc = {'start':{'krw':'100','coins':{},'prices':{}},'end':{'krw':'110','coins':{},'prices':{}},'net_deposits_krw':'0'}
probe('S3_paper_real_account_mix', 'Physical account profit 10 matches REAL profit 10, but PAPER profit 10 enters the all-session subtraction, creating a false -10 gap.',
      sc_cases.inp([real,paper],account=acc), lambda r: r['account_minus_sessions']=='-10')
s = sc_cases.sess(905,trades=[],daily_equity=[{'date':f'2026-12-0{i+1}','equity':v} for i,v in enumerate(['100','50','1000','600'])])
probe('S4_drawdown_percentage', 'Largest absolute drawdown is 400, largest percentage drawdown is 50%; selecting percentage only at largest absolute drawdown reports 40%.',
      sc_cases.inp([s]), lambda r: r['sessions'][0]['drawdown']['pct']=='-40.00')

for path in ['web-api/src/main/java/com/cryptoautotrader/api/repository/OrderRepository.java',
             'web-api/src/main/java/com/cryptoautotrader/api/service/RiskManagementService.java']:
    b = git('show', BASE + ':' + path)
    anchors[BASE + ':' + path] = digest(b)
    text = b.decode()
    if 'OrderRepository' in path:
        check('risk query uses fee-exclusive REAL avg price', 'o.filled_quantity * p.avg_price' in text and 'sumDynamicRealSessionRealizedLossSince' in text and 'sumLiveSessionRealizedLossSince' in text)
    else:
        check('consecutive loss uses stored realized', 'pnlNewestFirst.add(pos.getRealizedPnl())' in text)
observations['risk_boundary_arithmetic_only'] = {
    'buy_funds':'7500', 'buy_fee':'3.75', 'net_sell_proceeds':'7501',
    'legacy_REAL_realized':'1', 'net_cash_realized':'-2.75',
    'scope':'Decimal arithmetic plus fixed source consumer map; not an executed product/PG risk test.'}
check('risk sign boundary arithmetic', D('7501')-D('7500') == 1 and D('7501')-D('7500')-D('3.75') == D('-2.75'))

(OUT / 'observations.json').write_text(json.dumps(observations, ensure_ascii=False, indent=1) + '\n', 'utf-8', newline='\n')
report = {'fixed_head': HEAD, 'anchors': anchors, 'passed': sum(x['passed'] for x in checks),
          'failed': sum(not x['passed'] for x in checks), 'checks': checks}
(OUT / 'checks.json').write_text(json.dumps(report, ensure_ascii=False, indent=1) + '\n', 'utf-8', newline='\n')
print(json.dumps({k:report[k] for k in ('fixed_head','passed','failed')}))
for c in checks:
    if not c['passed']:
        print(json.dumps(c, ensure_ascii=False))
raise SystemExit(bool(report['failed']))
