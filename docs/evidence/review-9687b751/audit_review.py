"""Fixed-blob review only; saved results and exact SQL in SQLite, no PG/app/remote calls."""
from pathlib import Path
import hashlib, json, re, sqlite3, subprocess, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SUB = '9687b751'
CAND = '32d5dafd'
BASE = 'b861bdf9'
checks, obs = [], {}

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def source(rev, path):
    return git('show', rev + ':' + path).decode('utf-8')

def ck(name, ok, actual=None):
    checks.append({'name': name, 'passed': bool(ok), 'actual': actual})

def manifest(folder, name='SHA256SUMS'):
    rel = 'docs/evidence/' + folder
    entries = []
    for line in source(SUB, rel + '/' + name).splitlines():
        m = re.fullmatch(r'([a-fA-F0-9]{64})\s+\*?(.+)', line)
        if not m: raise ValueError(line)
        p = m[2].removeprefix('./')
        data = git('show', SUB + ':' + rel + '/' + p)
        ck(folder + ' blob ' + p, hashlib.sha256(data).hexdigest() == m[1])
        ck(folder + ' workspace ' + p, (ROOT / rel / p).read_bytes() == data)
        entries.append(p)
    tracked = git('ls-tree', '-r', '--name-only', SUB, '--', rel).decode().splitlines()
    not_listed = [p[len(rel)+1:] for p in tracked if p[len(rel)+1:] not in entries + [name, '.gitattributes', 'SHA256SUMS']]
    obs[folder] = {'manifest': name, 'entries': len(entries), 'tracked_files': len(tracked), 'not_listed': not_listed}
    return entries

anchors = {x: git('rev-parse', x).decode().strip() for x in [SUB, BASE, CAND, 'origin/main']}
ck('local HEAD fixed submission', git('rev-parse', 'HEAD').decode().strip() == anchors[SUB])
ck('saved origin/main is prior N03, no network query', anchors['origin/main'].startswith('f55f5af0'), anchors['origin/main'])
entries = manifest('dec-costbasis-boundary-20261119')
ck('boundary tracked coverage', not obs['dec-costbasis-boundary-20261119']['not_listed'])
manifest('review-0bc5d295')
manifest('review-f55f5af0')
score_entries = manifest('dec-scorecard-v3-20261119', 'SHA256SUMS.submitted')
ck('scorecard submission excludes pyc', len(score_entries) == 74 and all(not p.endswith('.pyc') for p in score_entries))

# Preserve the thirteen previously missing raw logs, without rerunning tests.
missing = ['dec-costbasis-20261119/' + p for p in [
    'affected-h2/gradle.log',
    'affected-pg/PartialSellRealizedPnlPgTest.log',
    'affected-pg/PhantomKeepsConfirmedPnlPgTest.log',
    'affected-pg/RealLossThreeFormulaPgTest.log',
    'affected-pg/SettlementRoundingPgTest.log',
    'affected-pg/attempt1-SettlementRounding/SettlementRoundingPgTest.log',
    'affected-pg/attempt2-SettlementRounding/SettlementRoundingPgTest.log',
    'attempt1-measure/gradle.log', 'attempt2-noxml/gradle.log',
    'base-fail/gradle.log', 'base-fail2/gradle.log', 'run-final/gradle.log']]
missing.append('dec-inclusion-20261119/compile.log')
for item in missing:
    folder, path = item.split('/', 1)
    lines = source(SUB, 'docs/evidence/' + folder + '/SHA256SUMS').splitlines()
    wanted = next(line[:64] for line in lines if line.split(maxsplit=1)[1].lstrip('*').removeprefix('./') == path)
    data = git('show', SUB + ':docs/evidence/' + item)
    ck('restored raw log ' + item, hashlib.sha256(data).hexdigest() == wanted)

prod = git('diff', BASE, CAND, '--', 'web-api/src/main').decode('utf-8')
test = git('diff', BASE, CAND, '--', 'web-api/src/test').decode('utf-8')
for name, value in [('product.diff', prod), ('test.diff', test)]:
    ck('saved ' + name + ' equals fixed candidate diff', source(SUB, 'docs/evidence/dec-costbasis-boundary-20261119/' + name).replace('\r\n', '\n') == value.replace('\r\n', '\n'))
paths = git('diff', '--name-only', BASE, CAND).decode().splitlines()
ck('candidate scope four product six test files', len(paths) == 10 and sum('/src/main/' in p for p in paths) == 4 and sum('/src/test/' in p for p in paths) == 6, paths)

prefix = 'docs/evidence/dec-costbasis-boundary-20261119/'
obs['assertion_runs'] = {}
for run, wanted in [('base-fail', (54,39)), ('run1',(54,54)), ('fix1-n2b-fail',(59,56)), ('attempt-run2-null-logic',(59,54)), ('run3',(59,59))]:
    j = json.loads(source(SUB, prefix + run + '/cost_basis_boundary_checks.json'))
    values = j['checks']
    counts = (len(values), sum(v['pass'] for v in values))
    ck('saved assertions ' + run, counts == wanted, counts)
    obs['assertion_runs'][run] = {'total': counts[0], 'passed': counts[1], 'failed_labels': [v['check'] for v in values if not v['pass']]}
obs['xml'] = {}
for run, expected in [('run3',(1,0,0)), ('affected-h2',(215,0,0)), ('affected-pg',(36,0,0))]:
    names = [p for p in entries if p.startswith(run + '/') and p.endswith('.xml')]
    totals = [0,0,0]
    for p in names:
        tree = ET.fromstring(git('show', SUB + ':' + prefix + p))
        totals[0] += int(tree.attrib['tests'])
        totals[1] += int(tree.attrib['failures']) + int(tree.attrib['errors'])
        totals[2] += int(tree.attrib['skipped'])
    ck('saved XML ' + run, tuple(totals) == expected, {'files':len(names), 'tests_fail_skip': totals})
    obs['xml'][run] = {'files': len(names), 'tests_fail_skip': totals}
old48 = json.loads(source(SUB, prefix + 'affected-pg/cost_basis_checks.json'))['checks']
ck('prior PG assertion set preserved 48/48', len(old48) == 48 and all(x['pass'] for x in old48))

repo_path = 'web-api/src/main/java/com/cryptoautotrader/api/repository/OrderRepository.java'
repo = source(CAND, repo_path)
const = {}
def java_concat(expr):
    return ''.join(json.loads(t) if t.startswith('"') else const[t] for t in re.findall(r'"(?:\\.|[^"\\])*"|\b(?:SELL_FIELDS_KNOWN|CONFIRMED_NO_TRADE|BUY_COST_BY_POSITION)\b', expr))
for name in ['SELL_FIELDS_KNOWN','CONFIRMED_NO_TRADE','BUY_COST_BY_POSITION']:
    const[name] = java_concat(re.search(r'String ' + name + r'\s*=\s*(.*?);', repo, re.S)[1])
def query(name):
    end = repo.index(' ' + name + '(')
    frag = repo[:end]
    return java_concat(re.split(r',\s*nativeQuery', frag[frag.rindex('@Query(value = ') + len('@Query(value = '):])[0])
queries = {n:query(n) for n in ['countRealSessionCostUndeterminedSince','costBasisPartsByPosition','sumLiveSessionRealizedLossSince']}
con = sqlite3.connect(':memory:')
con.executescript('CREATE TABLE position(id INTEGER, session_kind TEXT, session_id INTEGER, status TEXT, closed_at TEXT, realized_pnl REAL, size REAL, exit_reason TEXT);'
                  'CREATE TABLE "order"(id INTEGER, position_id INTEGER, side TEXT, session_kind TEXT, session_id INTEGER, filled_quantity REAL, applied_executed_funds REAL, applied_paid_fee REAL, sell_applied_at TEXT, state TEXT, fee_source TEXT, cancel_confirmed_at TEXT);')
params = {'kind':'LIVE','since':'2026-10-20','ids':1}
def state():
    return {'count':con.execute(queries['countRealSessionCostUndeterminedSince'],params).fetchone()[0], 'parts':con.execute(queries['costBasisPartsByPosition'],params).fetchone(), 'loss':con.execute(queries['sumLiveSessionRealizedLossSince'], params).fetchone()[0]}
con.execute("INSERT INTO position VALUES(1,'LIVE',1,'CLOSED','2026-11-19',1,0,NULL)")
no_order = state()
ck('exact SQL prior no-source counterexample now counted', no_order['count'] > 0 and no_order['parts'][9] == 0, no_order)
con.execute("INSERT INTO \"order\" VALUES(1,1,'BUY','LIVE',1,25,7500,3.75,NULL,'FILLED','REPORTED',NULL)")
con.execute("INSERT INTO \"order\" VALUES(2,1,'SELL','LIVE',1,25,7504.75,NULL,'2026-11-19','FILLED','REPORTED',NULL)")
null_fee = state()
ck('exact SQL prior NULL sell fee counterexample now counted', null_fee['count'] > 0 and null_fee['parts'][8] == 1, null_fee)
con.execute('UPDATE "order" SET applied_paid_fee=0 WHERE id=2')
known_zero = state()
ck('exact SQL confirmed zero releases unknown', known_zero['count'] == 0 and known_zero['parts'][8] == 0, known_zero)
con.execute('UPDATE "order" SET applied_paid_fee=3.75 WHERE id=2')
known_fee = state()
ck('exact SQL confirmed fee restores -2.75', known_fee['count'] == 0 and abs(known_fee['loss'] + 2.75) < 1e-8, known_fee)
con.execute('DELETE FROM "order"')
con.execute("UPDATE position SET session_kind='DYNAMIC',exit_reason='ENTRY_REJECTED'")
params['kind'] = 'DYNAMIC'
marker = state()
ck('marker plus zero size plus no orders is no-trade', marker['count'] == 0 and marker['parts'][9] == 1, marker)
con.execute('UPDATE position SET size=1')
ck('marker alone cannot exempt nonzero size', state()['count'] > 0)
con.execute("UPDATE position SET size=0,exit_reason=NULL,session_kind='LIVE'")
params['kind'] = 'LIVE'
con.execute("INSERT INTO \"order\" VALUES(3,1,'BUY','LIVE',1,NULL,NULL,NULL,NULL,'CANCELLED','REPORTED','2026-11-19')")
missing_buy_qty = state()
ck('new probe: NULL BUY quantity can currently be treated as zero', missing_buy_qty['count'] == 0 and missing_buy_qty['parts'][9] == 1, missing_buy_qty)
con.execute('UPDATE "order" SET filled_quantity=0')
explicit_zero_qty = state()
ck('comparison: explicit zero BUY quantity is no-trade', explicit_zero_qty['parts'][9] == 1)
obs['sql_probes'] = {'method':'Unmodified Java annotation SQL extracted from fixed candidate, executed only in SQLite memory. Not a PostgreSQL or engine run.', 'no_source':no_order,'NULL_sell_fee':null_fee,'confirmed_zero':known_zero,'confirmed_fee':known_fee,'marker':marker,'NULL_buy_qty_cancel_confirmed':missing_buy_qty,'zero_buy_qty':explicit_zero_qty,'queries':queries}

enum_path = 'web-api/src/main/java/com/cryptoautotrader/api/entity/ExitReason.java'
enum = source(CAND, enum_path)
old_enum = source('0e1d2a02', enum_path)
ck('marker currently included by strategy-performance predicate', 'return this != FORCED_STOP && this != UNKNOWN;' in enum and 'ENTRY_REJECTED' in enum)
analyzer = source(CAND,'web-api/src/main/java/com/cryptoautotrader/api/report/LogAnalyzerService.java')
ck('report counts every supplied CLOSED row, zero pnl as loss', 'p.closedCount = closedPos.size();' in analyzer and 'p.lossCount = p.closedCount - p.winCount;' in analyzer)
entity = source(CAND,'web-api/src/main/java/com/cryptoautotrader/api/entity/PositionEntity.java')
ck('old production enum lacks marker, STRING mapping requires rollback review', 'ENTRY_REJECTED' not in old_enum and '@Enumerated(EnumType.STRING)' in entity)
obs['findings'] = {
    'F1':'ENTRY_REJECTED passes countsTowardStrategyPerformance; unfiltered LogAnalyzer row count includes it and lossCount classifies zero as loss. Source observation, not engine reproduction.',
    'F2':'Prior production ExitReason has no ENTRY_REJECTED while PositionEntity uses EnumType.STRING. Old-binary entity hydration compatibility is a static risk; not run against old jar.',
    'F3':'CONFIRMED_NO_TRADE coalesces NULL BUY filled_quantity to zero, including CANCELLED with cancel_confirmed_at. Exact SQL counterexample above; PG reproduction required before treating it as confirmed no-trade.'}

result = {'anchors':anchors,'passed':sum(c['passed'] for c in checks),'failed':sum(not c['passed'] for c in checks),'checks':checks}
for name,value in [('checks.json',result),('observations.json',obs)]:
    (OUT/name).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
print(json.dumps({'passed':result['passed'],'failed':result['failed'],'findings':list(obs['findings'])}, ensure_ascii=False))
for c in checks:
    if not c['passed']: print(json.dumps(c,ensure_ascii=False))
