"""v1.203: fixed Git blobs, preserved results, new pure file-input probes only."""
import copy
import hashlib
import json
import pathlib
import posixpath
import subprocess
import types

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(__file__).resolve().parent
SHA = '09f6bd4acc43351efba4100598f6d3d40da16050'
EV = 'docs/evidence/'
SC = EV + 'dec-scorecard-source-v2-20261120/'
P7 = EV + 'dec-registry-v3-p7v6-20261120/'
checks, probes = [], []


def git(*args):
    return subprocess.check_output(['git', '-c', 'core.quotepath=false', *args], cwd=ROOT)


def raw(path, sha=SHA):
    return git('show', f'{sha}:{posixpath.normpath(path)}')


def obj(path):
    return json.loads(raw(path))


def check(name, ok, detail=None):
    checks.append(dict(name=name, pass_=bool(ok), detail=detail))


def module(path):
    m = types.ModuleType('review_' + pathlib.Path(path).stem)
    m.__file__ = str(ROOT / path)
    exec(compile(raw(path), m.__file__, 'exec'), m.__dict__)
    return m


def probe(name, source, result, observed):
    probes.append(dict(name=name, source=source, result=result, reproduced=bool(observed)))


def dump(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str) + '\n', encoding='utf-8')


check('fixed_HEAD', git('rev-parse', 'HEAD').decode().strip() == SHA)
refs = git('rev-parse', 'refs/remotes/origin/main').decode().strip()
check('saved_remote_ref_is_parent_no_network_check', refs == git('rev-parse', SHA + '^').decode().strip(), refs)
paths = git('diff-tree', '--no-commit-id', '--name-only', '-r', SHA).decode().splitlines()
check('submission_docs_only', len(paths) == 116 and all(p.startswith('docs/') for p in paths), len(paths))
evidence = [p for p in paths if p.startswith(EV)]
bad = [p for p in evidence if raw(p) != (ROOT / p).read_bytes()]
check('evidence_committed_bytes', not bad, dict(total=len(evidence), mismatches=bad))
for folder in (SC, P7, EV + 'review-95a93293/'):
    rows = raw(folder + 'SHA256SUMS').decode().splitlines()
    bad = []
    for line in rows:
        digest, rel = line.split(None, 1)
        rel = rel.strip().lstrip('*')
        path = posixpath.normpath(folder + rel)
        if hashlib.sha256(raw(path)).hexdigest() != digest:
            bad.append(path)
    check(folder + 'manifest', not bad, dict(total=len(rows), mismatches=bad))
for label, path in (
    ('scorecard_v3', EV + 'dec-scorecard-v3-20261119/scorecard/scorecard.py'),
    ('P7_v5', EV + 'dec-reconcile-v5-20261118/p7v5/reconcile.py'),
    ('RUNBOOK', 'docs/LIMITED_LIVE_TEST_RUNBOOK.md'),
    ('D81D4R', EV + 'd81d4r-20261120/runner/extract-cd8bd76f/U0.sh')):
    check(label + '_unchanged', raw(path) == raw(path, SHA + '^'))
for p, expected in ((SC + 'run1/cases.out.txt', '27'), (P7 + 'p7v6/run-v6/cases.out.txt', '29'),
                    (P7 + 'p7v6/run-impact/impact.out.txt', '260')):
    last = raw(p).decode().splitlines()[-1]
    check('preserved_result_' + expected, last == f'합계 {expected} 통과 {expected} 실패 0', last)

C = module(SC + 'convert.py')
V = module(EV + 'dec-scorecard-v3-20261119/scorecard/scorecard.py')
P = module(P7 + 'p7v6/reconcile.py')

# New case: unknown boundary BUY amounts are still used as confirmed SELL cost by v3.
s = obj(SC + 'run1/cases/01.json')['source']
s['sessions'][0]['orders'][0]['captures'] = [dict(s['sessions'][0]['orders'][0]['captures'][0],
    captured_at='2027-01-02T10:00:00+09:00', reported_observed_at='2027-01-02T09:00:00+09:00', funds='20000', fee='10')]
s['sessions'][0]['orders'][1]['captures'] = s['sessions'][0]['orders'][1]['captures'][:1]
i, r = C.convert(copy.deepcopy(s))
card = V.build(copy.deepcopy(i))['sessions'][0]
probe('S1_unknown_buy_cost_used_for_confirmed_realized', s,
      dict(converted_trade=i['sessions'][0]['trades'][0], card=card, order_report=r['sessions'][0]['orders']),
      i['sessions'][0]['trades'][0]['buy']['settled_at'] is None and card['realized_in_period'] == '-10015')

# New case: BUY fee correction does not refresh reportedObservedAt in applySettlementIfVersionMatches.
s = obj(SC + 'run1/cases/01.json')['source']
s['sessions'][0]['orders'][1]['captures'] = s['sessions'][0]['orders'][1]['captures'][:1]
b = s['sessions'][0]['orders'][0]['captures'][0]
b['captured_at'] = s['period']['to']
b['settlement_final'] = False
s['sessions'][0]['orders'][0]['captures'].append(dict(b, ref='jan-fee-correction',
    captured_at='2027-01-01T10:20:00+09:00', fee='6', settlement_final=True))
i, r = C.convert(copy.deepcopy(s))
card = V.build(copy.deepcopy(i))['sessions'][0]
probe('S2_January_correction_also_in_December_confirmed_fee', s,
      dict(trades=i['sessions'][0]['trades'], card=card, report=r['sessions'][0]),
      card['fees_paid'] == '11' and r['sessions'][0]['corrections'][0]['attribution'] == '2027-01')
repo = raw('web-api/src/main/java/com/cryptoautotrader/api/repository/OrderRepository.java', 'd81c3f2f').decode()
update = repo[repo.index('o.reportedExecutedFunds = :reportedFunds'):repo.index('int applySettlementIfVersionMatches')]
check('product_settlement_correction_does_not_refresh_observation_time', 'o.appliedPaidFee = :appliedFee' in update and 'reportedObservedAt' not in update,
      dict(source='d81c3f2f:OrderRepository.applySettlementIfVersionMatches', sha256=hashlib.sha256(repo.encode()).hexdigest()))

# Boundary source is unchanged except an event is on a different, unconfirmed clock axis.
s = obj(SC + 'run1/file/source.json')
s['registry_events'][0]['axis'] = 'db'
i, r = C.convert(copy.deepcopy(s))
probe('S3_event_axis_without_relation_is_confirmed', s, r['sessions'][0]['flows'],
      r['sessions'][0]['flows']['status'] == '확정')

# Use saved input; copy the explicitly synthetic same-clock assumption of v5/synth.
base = obj(EV + 'dec-reconcile-v4-20261118/p7v4/run-v4/cases/G3_exact_two_sessions.json')['input']
base['registry'] = obj(P7 + 'p7v6/run-v6/cases/00.json')['registry']
for side in ('base', 'now'):
    base[side]['meta']['clock'] = {'version': 'p7-clock-1', 'samples': [], 'same_source': [
        {'axes': [a, b], 'basis': 'review synthetic same-clock fixture; not an operational measurement'} for a, b in
        (('collector', 'db'), ('collector', 'app'), ('collector', 'exchange'), ('db', 'app'), ('db', 'exchange'), ('app', 'exchange'))]}


def evaluate(d):
    return P.evaluate(d['registry'], d['base'], d['now'])


positive = evaluate(copy.deepcopy(base))
probe('P0_positive_control', base, positive, positive['overall'] == 'OK' and positive['account_sum']['status'] == '일치')
for name, edit in (
    ('P1_unconfirmed_ruleset_sum_still_matches', lambda rs: rs.update(confirmed=None, confirmed_source=None)),
    ('P2_mismatched_ruleset_sum_still_matches', lambda rs: rs.update(confirmed='b' * 32))):
    d = copy.deepcopy(base)
    edit(d['registry']['sessions'][1]['ruleset'])
    r = evaluate(d)
    probe(name, d, r, r['overall'] != 'OK' and r['account_sum']['status'] == '일치')
d = copy.deepcopy(base)
for key in ('cash_base', 'holdings_base'):
    d['registry'][key]['base_at'] = '2030-01-01T00:00:00Z'
d['registry']['cash_base'].update(amount_krw='59800', includes_event_refs=['alloc-203', 'alloc-900'])
r = evaluate(d)
probe('P3_future_bases_accepted', d, r, r['overall'] == 'OK' and r['account_sum']['status'] == '일치')
d = copy.deepcopy(base)
for side in ('exchange_before', 'exchange_after'):
    d['now'][side]['done_query']['complete'] = False
r = evaluate(d)
probe('P4_incomplete_exchange_coverage_sum_still_matches', d, r,
      r['overall'] == 'HOLD' and r['account_sum']['status'] == '일치')

dump('checks.json', dict(commit=SHA, pass_count=sum(c['pass_'] for c in checks), fail_count=sum(not c['pass_'] for c in checks), checks=checks))
dump('contract-probes.json', dict(commit=SHA, reproduced=sum(p['reproduced'] for p in probes), total=len(probes), probes=probes))
print(json.dumps(dict(checks=len(checks), failures=[c['name'] for c in checks if not c['pass_']],
                      probes=len(probes), not_reproduced=[p['name'] for p in probes if not p['reproduced']]), ensure_ascii=False))
