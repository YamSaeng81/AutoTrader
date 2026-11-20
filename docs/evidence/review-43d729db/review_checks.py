"""v1.204: fixed blobs, preserved result audit, new pure file/model probes. No DB, network, app or old suites."""
import copy
import hashlib
import json
import pathlib
import posixpath
import subprocess
import types

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(__file__).resolve().parent
SHA = '43d729dbe6341ba306489b89762b56d71cb505ba'
EV = 'docs/evidence/'
SC = EV + 'dec-scorecard-source-v3-20261120/'
P7 = EV + 'dec-registry-p7v7-20261120/'
RD = EV + 'dec-read-plan-20261120/'
checks, probes = [], []


def git(*a):
    return subprocess.check_output(['git', '-c', 'core.quotepath=false', *a], cwd=ROOT)


def raw(p, sha=SHA):
    return git('show', f'{sha}:{posixpath.normpath(p)}')


def obj(p):
    return json.loads(raw(p))


def load(p):
    m = types.ModuleType('review_' + pathlib.Path(p).stem)
    m.__file__ = str(ROOT / p)
    exec(compile(raw(p), m.__file__, 'exec'), m.__dict__)
    return m


def check(name, ok, detail=None):
    checks.append(dict(name=name, pass_=bool(ok), detail=detail))


def probe(name, source, result, reproduced):
    probes.append(dict(name=name, source=source, result=result, reproduced=bool(reproduced)))


def dump(name, data):
    (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2, default=str) + '\n', encoding='utf-8')


check('HEAD_and_saved_origin_no_network', git('rev-parse', 'HEAD').decode().strip() == SHA == git('rev-parse', 'origin/main').decode().strip())
paths = git('diff-tree', '--no-commit-id', '--name-only', '-r', SHA).decode().splitlines()
check('docs_only', len(paths) == 41 and all(p.startswith('docs/') for p in paths), len(paths))
ev = [p for p in paths if p.startswith(EV)]
bad = [p for p in ev if raw(p) != (ROOT / p).read_bytes()]
check('committed_evidence_bytes', not bad, dict(total=len(ev), mismatches=bad))
for folder in (SC, P7, RD, EV + 'review-09f6bd4a/'):
    rows = raw(folder + 'SHA256SUMS').decode().splitlines()
    bad = []
    for row in rows:
        h, p = row.split(None, 1)
        path = posixpath.normpath(folder + p.strip().lstrip('*'))
        if hashlib.sha256(raw(path)).hexdigest() != h:
            bad.append(path)
    check(folder + 'manifest', not bad, dict(total=len(rows), mismatches=bad))
for label, path in (
    ('original_converter', EV + 'dec-scorecard-source-v2-20261120/convert.py'),
    ('original_scorecard', EV + 'dec-scorecard-v3-20261119/scorecard/scorecard.py'),
    ('P7_v6', EV + 'dec-registry-v3-p7v6-20261120/p7v6/reconcile.py'),
    ('RUNBOOK', 'docs/LIMITED_LIVE_TEST_RUNBOOK.md'),
    ('D81D4R', EV + 'd81d4r-20261120/runner/extract-cd8bd76f/U0.sh')):
    check(label + '_unchanged', raw(path) == raw(path, SHA + '^'))
for folder, file, count in ((SC, 'run1/cases.out.txt', 24), (P7, 'p7v7/run-v7/cases.out.txt', 12), (RD, 'run1/tests.out.txt', 8)):
    last = raw(folder + file).decode().splitlines()[-1]
    check('preserved_' + str(count), last == f'합계 {count} 통과 {count} 실패 0', last)
for cls in ('LiveTradingSessionEntity', 'DynamicSessionEntity', 'OrderEntity', 'PositionEntity', 'OrderSettlementDiscrepancyEntity'):
    p = f'web-api/src/main/java/com/cryptoautotrader/api/entity/{cls}.java'
    check(cls + '_schema_source_same', raw(p, '0e1d2a02') == raw(p, 'd81c3f2f'))

C, V = load(SC + 'convert.py'), load(SC + 'scorecard/scorecard.py')
P, F = load(P7 + 'p7v7/reconcile.py'), load(P7 + 'p7v7/f3.py')
old = {p['name'][:2]: p for p in obj(EV + 'review-09f6bd4a/contract-probes.json')['probes'] if p['name'].startswith('P')}
fixture = old['P0']['source']

# Code candidate positive control (not rerunning the developer suites).
r = P.evaluate(copy.deepcopy(fixture['registry']), copy.deepcopy(fixture['base']), copy.deepcopy(fixture['now']))
probe('P0_positive_control', 'saved synthetic P0', dict(overall=r['overall'], f3=F.judge(r)), r['overall'] == 'OK' and F.judge(r)[0])

# Negative unallocated cash is independently known, while a partial reservation is unknown.
d = copy.deepcopy(fixture)
d['registry']['cash_base']['amount_krw'] = '10000'
d['now']['db']['sessions'][0]['available_krw'] = '3000.00'
d['now']['db']['orders'].append(dict(d['now']['db']['orders'][1], id=89, position_id=None,
    state='PARTIAL_FILLED', filled_quantity='0.001', reserved_krw='1000', exchange_order_id='u-89', client_order_id='c-89'))
r = P.evaluate(d['registry'], d['base'], d['now'])
codes = [x['code'] for x in r['anomalies']]
probe('P1_negative_unallocated_hidden_by_reservation_hold', d, r,
      r['account_sum']['currencies']['KRW']['unallocated'] == '-10000' and 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED' not in codes)

# Output file logical contradictions must not be accepted as F3 completion.
for name, edit in (
    ('F1_OK_with_anomaly_accepted', lambda x: x['anomalies'].append({'code': 'ACCOUNT_SUM_NEGATIVE_UNALLOCATED'})),
    ('F2_OK_with_hold_accepted', lambda x: x['holds'].append({'code': 'RULESET_UNCONFIRMED', 'blocks': []})),
    ('F3_code_mismatch_accepted', lambda x: x['code'].update(now='a' * 40))):
    x = P.evaluate(copy.deepcopy(fixture['registry']), copy.deepcopy(fixture['base']), copy.deepcopy(fixture['now']))
    edit(x)
    probe(name, x, F.judge(x), F.judge(x)[0])

# Creation time is on app, but membership/newness is still compared to a db period without a clock relation.
s = obj(SC + 'run1/file/source.json')
s['clock'] = {'same_source': []}
s['period']['axis'] = 'db'
s['sessions'][0]['created_at'] = s['period']['from']
s['sessions'][0]['boundary']['start'] = None
s['sessions'][0]['orders'] = []
s['registry_events'] = []
i, report = C.convert(copy.deepcopy(s))
card = V.build(copy.deepcopy(i))['sessions'][0]
probe('S1_unrelated_creation_axis_still_assigns_zero_start', s,
      dict(input=i, report=report, card=card), i['sessions'][0].get('started_in_period') is True and card['start_equity'] == '0')

# A known sale amount with unknown membership is counted as zero definite closed rounds/wins.
s = obj(SC + 'run1/file/source.json')
s['clock'] = {'same_source': []}
i, report = C.convert(copy.deepcopy(s))
card = V.build(copy.deepcopy(i))['sessions'][0]
probe('S2_unknown_sell_month_definite_round_count', s,
      dict(card=card, trades=i['sessions'][0]['trades']),
      card['realized_in_period'] is None and card['round_trips_closed'] == 0 and card['wins'] == 0)

# SQL three-valued logic model; no SQL engine is run. This is static counterexample evidence, not PG validation.
def sql_not(x):
    return None if x is None else not x


def sql_and(a, b):
    return False if a is False or b is False else (None if a is None or b is None else True)


def sql_or(a, b):
    return True if a is True or b is True else (None if a is None or b is None else False)


membership = sql_or(sql_and(True, None), sql_and(False, None))
gate_condition = sql_not(membership)
probe('R1_NULL_owner_dropped_from_SQL_gate', dict(kind='LIVE', session_id=None, state='SUBMITTED'),
      dict(not_registered=gate_condition, included_in_gate_count=gate_condition is True, phase1_owner='NO_SESSION_ID'),
      gate_condition is None)

probe('R2_registered_DYNAMIC_PAPER_reaches_phase2', dict(dyn_ids=[900], row=dict(id=900, trading_mode='PAPER')),
      dict(in_phase1=False, bad_gate_count=0, selected_by_phase2_id=True),
      "FROM dynamic_session WHERE id = ANY(:'dyn_ids'::bigint[])" in raw(RD + 'read.sql').decode())

probe('R3_old_order_settled_in_window_omitted', dict(state='FILLED', created_at='2026-11-18T00:00:00Z',
    window_from='2026-11-20T00:00:00Z', sell_applied_at='2026-11-20T00:01:00Z', position_status='CLOSED'),
      dict(active_state=False, created_in_window=False, linked_open_position=False, included=False),
      "OR o.position_id IN (SELECT id FROM position WHERE status <> 'CLOSED')" in raw(RD + 'read.sql').decode())

dump('checks.json', dict(commit=SHA, pass_count=sum(x['pass_'] for x in checks), fail_count=sum(not x['pass_'] for x in checks), checks=checks))
dump('contract-probes.json', dict(commit=SHA, total=len(probes), reproduced=sum(x['reproduced'] for x in probes), probes=probes))
print(json.dumps(dict(checks=len(checks), failures=[x['name'] for x in checks if not x['pass_']],
    probes=len(probes), not_reproduced=[x['name'] for x in probes if not x['reproduced']]), ensure_ascii=False))
