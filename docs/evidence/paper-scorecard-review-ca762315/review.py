"""Local review of preserved scorecard input; never connects to a service."""
import csv, hashlib, io, json, re, statistics, subprocess, sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from decimal import Decimal as D
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
B = ROOT / 'docs/evidence/paper-scorecard-20261110'
HERE = Path(__file__).resolve().parent
WORK = ROOT / 'core-engine/build/paper-scorecard-review-ca762315'
WORK.mkdir(parents=True, exist_ok=True)
SHA = 'ca762315'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def h(b):
    return hashlib.sha256(b).hexdigest()

manifest = []
for line in (B / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
    want, rel = line.split(' *', 1)
    p = B / rel
    blob = git('show', SHA + ':' + p.relative_to(ROOT).as_posix())
    manifest.append(dict(path=rel, expected=want, local=h(p.read_bytes()), blob=h(blob)))
assert len(manifest) == 18
assert all(x['expected'] == x['local'] == x['blob'] for x in manifest)

replay = WORK / 'replay'
run = subprocess.run([sys.executable, '-I', '-B', '-X', 'utf8', str(B/'score.py'),
                      str(B/'raw/raw.txt'), str(B/'raw/raw2.txt'), str(B/'raw/raw3.txt'), str(replay)],
                     cwd=ROOT, capture_output=True)
(WORK/'replay-stdout.txt').write_bytes(run.stdout)
(WORK/'replay-stderr.txt').write_bytes(run.stderr)
assert run.returncode == 0, run.stderr
outputs = [dict(path=p.name, equal=p.read_bytes() == (replay/p.name).read_bytes()) for p in sorted((B/'out').iterdir()) if p.is_file()]
assert len(outputs) == 9 and all(x['equal'] for x in outputs)

def rows(name):
    return list(csv.DictReader((B/'out'/name).open(encoding='utf-8', newline='')))

ps, pp, ds, ls, qp, signals = [rows(x) for x in ('paper_sessions.csv','paper_positions.csv','dynamic_sessions.csv','live_sessions.csv','public_positions.csv','signal_summary.csv')]
per = rows('per_session.csv')
report = json.loads((B/'out/scorecard.json').read_text(encoding='utf-8'))
blind = [r for r in ps if 522 <= int(r['id']) <= 587]
assert len(blind) == 66
assert all(not r[c] for r in blind for c in ('total_krw','available_krw','realized_pnl','total_fee'))
assert not any(r['session_id'] and 522 <= int(r['session_id']) <= 587 for r in pp)
assert not any(r['session_type'] == 'PAPER' and r['session_id'] and 522 <= int(r['session_id']) <= 587 for r in signals)

engkind = {'PAPER':'PAPER','DYN_PAPER':'DYN_PAPER','DYNAMIC':'DYN_REAL','LIVE':'LIVE'}
S = {(r['engine'],r['sid']):r for r in per}
P = [dict(r, engine='PAPER') for r in pp] + [dict(r,engine=engkind[r['session_kind']]) for r in qp]
P = [r for r in P if (r['engine'],r['session_id']) in S]
totals = {}
for e in sorted({r['engine'] for r in P}):
    ts = [r for r in P if r['engine'] == e]
    totals[e] = dict(n=len(ts), states=dict(Counter(r['status'] for r in ts)),
                     net=str(sum((D(r['realized_pnl'] or '0') for r in ts),D(0))),
                     fees=str(sum((D(r['position_fee'] or '0') for r in ts),D(0))),
                     null_pnl=sum(not r['realized_pnl'] for r in ts), null_fee=sum(not r['position_fee'] for r in ts))

stop = {}
for e in ('PAPER','DYN_PAPER'):
    ts = [r for r in P if r['engine']==e and r['stop_loss_price']]
    w = [(D(r['entry_price'])-D(r['stop_loss_price']))/D(r['entry_price'])*100 for r in ts]
    stop[e] = dict(n=len(w), current_stored_width_median=str(statistics.median(w)),
                   min=str(min(w)), max=str(max(w)), above_entry=sum(v<0 for v in w), at_entry=sum(v==0 for v in w),
                   definition='current persisted stop, not reconstructed initial stop')

# The submitted "independent" key is only a minute bucket. Verify within-bucket disagreements.
cluster = {}
for e in ('PAPER','DYN_PAPER'):
    bins = defaultdict(list)
    for r in P:
        if r['engine']==e:
            bins[(r['coin_pair'],r['opened_at'][:16],r['closed_at'][:16])].append(r)
    multi = [v for v in bins.values() if len(v)>1]
    returns = lambda v: [D(r['realized_pnl'])/(D(r['invested_krw'] or '0') or D(r['size'])*D(r['avg_price']))*100 for r in v]
    cluster[e] = dict(minute_buckets=len(bins), multi_row_buckets=len(multi),
                      different_returns=sum(len(set(returns(v)))>1 for v in multi),
                      different_exit_reasons=sum(len({r['exit_reason'] for r in v})>1 for v in multi),
                      different_timeframes=sum(len({S[(e,r['session_id'])]['tf'] for r in v})>1 for v in multi),
                      first_return_dependence='setdefault selects first input row; not a statistical independence estimator')

linked = defaultdict(Counter)
unlinked = defaultdict(Counter)
funnel_by_strategy = defaultdict(Counter)
for r in signals:
    e = engkind.get(r['session_type'],r['session_type'])
    target = linked if (e,r['session_id']) in S else unlinked
    target[e][r['signal']] += int(r['n'])
    if r['signal']=='BUY':
        label='linked' if (e,r['session_id']) in S else 'unlinked'
        funnel_by_strategy[(e,r['strategy_name'])][label] += int(r['n'])

# Distinguish win-rate population from the two-exit break-even illustration.
dyn = [r for r in P if r['engine']=='DYN_PAPER']
dynSLTP = [r for r in dyn if r['exit_reason'] in ('STOP_LOSS','TAKE_PROFIT')]
win = lambda v: sum(D(r['realized_pnl'])>0 for r in v)
dyn_compare = dict(all_n=len(dyn), all_winners=win(dyn), sltp_n=len(dynSLTP), sltp_winners=win(dynSLTP),
                   exits=dict(Counter(r['exit_reason'] for r in dyn)),
                   warning='42% illustration uses two conditional exit means; 33% uses all exits, not the same population')

text = (B/'raw/raw3.txt').read_text(encoding='utf-8')
bh = list(csv.DictReader(io.StringIO(text.split('@@bh\n',1)[1])))
bh_after = Counter()
for r in bh:
    s = S.get((r['engine'],r['id']))
    if not s or not r['t_end'] or not s['trades'] or s['trades']=='0': continue
    # Per-session display truncates stop to minutes; count only stopped sessions
    # whose selected H1 close would become known after the displayed stop minute.
    if s['stop']:
        t1=datetime.fromisoformat(r['t_end'])
        end=datetime.fromisoformat(s['stop'])
        if t1+timedelta(hours=1)>end+timedelta(minutes=1): bh_after[r['engine']]+=1

source = {}
def source_range(path, start, end):
    lines=git('show','b5cd1d0:'+path).decode('utf-8').splitlines()
    return '\n'.join(f'{i+1}: {lines[i]}' for i in range(start-1,min(end,len(lines))))
path='web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java'
code=git('show','b5cd1d0:'+path).decode('utf-8').splitlines()
hits=[i for i,l in enumerate(code) if 'pos.setStopLossPrice(updatedLevels' in l]
assert hits
source['mutable_stop']= '\n'.join(f'{j+1}: {code[j]}' for i in hits for j in range(max(0,i-6),min(len(code),i+4)))
dto='web-api/src/main/java/com/cryptoautotrader/api/dto/PaperTradingStartRequest.java'
req=git('show','b5cd1d0:'+dto).decode('utf-8')
assert '@DecimalMin("100000")' in req and 'maxInvestment' not in req
source['paper_request_min_initial_capital']='100000; no maxInvestment field at b5cd1d0'
source['paper_request_sha256']=h(req.encode('utf-8'))
checker_path='core-engine/src/main/java/com/cryptoautotrader/core/risk/ExitRuleChecker.java'
checker=git('show','b5cd1d0:'+checker_path).decode('utf-8').splitlines()
start=next(i for i,l in enumerate(checker) if 'StopLevels updateTrailingStops' in l)
finish=next(i for i in range(start,len(checker)) if 'return new StopLevels(currentSl, newTp)' in checker[i])
source['current_stop_helper']='\n'.join(f'{j+1}: {checker[j]}' for j in range(start,finish+2))
old_diff=git('show','ddc6de2c85a83a5df3ee382018805ffff5bd345a','--',checker_path).decode('utf-8')
source['historic_fix_commit']='ddc6de2c85a83a5df3ee382018805ffff5bd345a (2026-09-08); commit time is not a verified production deployment boundary'
source['historic_fix_diff_sha256']=h(old_diff.encode('utf-8'))
(HERE/'historical-trailing-fix.patch').write_text(old_diff,encoding='utf-8')
assert 'return new StopLevels(currentSl, newTp)' in source['current_stop_helper']

result=dict(source_commit=git('rev-parse',SHA).decode().strip(), manifest=manifest, replay=dict(rc=run.returncode, outputs=outputs,stderr_bytes=len(run.stderr)),
            blind_protection=dict(sessions=66,financial_columns_empty=True,positions_absent=True,signals_absent=True,
                                  disclosed_count='79 closed positions aggregate was seen once; not recomputed or used'),
            position_totals=totals, stop_fields=stop, minute_clusters=cluster,
            funnel=dict(linked={k:dict(v) for k,v in linked.items()},unlinked={k:dict(v) for k,v in unlinked.items()},
                        buy_by_strategy=[dict(engine=k[0],strategy=k[1],**dict(v)) for k,v in sorted(funnel_by_strategy.items())]),
            conditional_winrate=dyn_compare, benchmark_close_after_stop_lower_bound=dict(bh_after), source_checks=source)
(HERE/'review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k not in ('manifest','replay','source_checks')},ensure_ascii=False,indent=2))
print('manifest 18/18, replay 9/9, b5cd1d0 source checks PASS')
