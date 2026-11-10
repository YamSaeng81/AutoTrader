# 모의·실전 세션 성과표 집계(2026-11-10). 사용: python -I -X utf8 score.py <raw.txt> <raw2.txt> <raw3.txt> <출력 폴더>
# 입력은 extract.sql 의 읽기 전용 추출 원문 하나. 사전 등록 66세션(522~587)은 추출 단계에서 성과가 빠져 있다.
import csv, io, json, re, statistics, sys, pathlib
from collections import defaultdict
from datetime import datetime
from decimal import Decimal as D

raw = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
raw2 = pathlib.Path(sys.argv[2]).read_text(encoding='utf-8')
raw3 = pathlib.Path(sys.argv[3]).read_text(encoding='utf-8')
out = pathlib.Path(sys.argv[4]); out.mkdir(parents=True, exist_ok=True)
sec, cur = {}, None
for line in raw.splitlines(keepends=True):
    if line.startswith('@@'):
        cur = line[2:].strip(); sec[cur] = []
    elif cur:
        sec[cur].append(line)
T = {k: list(csv.DictReader(io.StringIO(''.join(v)))) for k, v in sec.items()}
for k, v in T.items():
    (out / (k + '.csv')).write_text(''.join(sec[k]), encoding='utf-8', newline='')
snap = T['meta'][0]['snapshot_ts']

def dec(x): return D(x) if x not in (None, '') else None
def ts(x): return datetime.fromisoformat(x[:26]) if x else None
def pct(a, b): return float(a / b * 100) if b else None

# ---- 세션 통일 ----
S = {}
for r in T['paper_sessions']:
    if r['prereg_blind'] == 't':
        continue
    S[('PAPER', r['id'])] = dict(engine='PAPER', sid=r['id'], strategy=r['strategy_name'], tf=r['timeframe'], coin=r['coin_pair'],
                                status=r['status'], cap=dec(r['initial_capital']), avail=dec(r['available_krw']), total=dec(r['total_krw']),
                                sess_pnl=dec(r['realized_pnl']), sess_fee=dec(r['total_fee']), start=ts(r['started_at']), stop=ts(r['stopped_at']))
for r in T['dynamic_sessions']:
    eng = 'DYN_PAPER' if r['trading_mode'] == 'PAPER' else 'DYN_REAL'
    S[(eng, r['id'])] = dict(engine=eng, sid=r['id'], strategy=r['strategy_type'], tf=r['timeframe'], coin='(동적)', status=r['status'],
                            cap=dec(r['initial_capital']), avail=dec(r['available_krw']), total=dec(r['total_asset_krw']), sess_pnl=None, sess_fee=None,
                            start=ts(r['started_at']), stop=ts(r['stopped_at']), cb=r['circuit_breaker_reason'])
for r in T['live_sessions']:
    S[('LIVE', r['id'])] = dict(engine='LIVE', sid=r['id'], strategy=r['strategy_type'], tf=r['timeframe'], coin=r['coin_pair'], status=r['status'],
                               cap=dec(r['initial_capital']), avail=dec(r['available_krw']), total=dec(r['total_asset_krw']), sess_pnl=None, sess_fee=None,
                               start=ts(r['started_at']), stop=ts(r['stopped_at']), cb=r['circuit_breaker_reason'])

# ---- 포지션 ----
P = []
kind2eng = {'DYN_PAPER': 'DYN_PAPER', 'DYNAMIC': 'DYN_REAL', 'LIVE': 'LIVE'}
orphan = defaultdict(int)
for r in T['paper_positions']:
    P.append(dict(r, engine='PAPER'))
for r in T['public_positions']:
    P.append(dict(r, engine=kind2eng[r['session_kind']]))
trades = []
for r in P:
    key = (r['engine'], r['session_id'])
    if key not in S:
        orphan[r['engine']] += 1; continue
    pnl, fee = dec(r['realized_pnl']) or D(0), dec(r['position_fee']) or D(0)
    inv = dec(r['invested_krw']) or dec(r.get('entry_allocation_krw')) or ((dec(r['size']) or D(0)) * (dec(r['avg_price']) or D(0)))
    o, c = ts(r['opened_at']), ts(r['closed_at'])
    trades.append(dict(key=key, pnl=pnl, fee=fee, inv=inv, ret=pct(pnl, inv), hold=(c - o).total_seconds() / 3600 if o and c else None,
                       exit=r['exit_reason'] or '(미기록)', status=r['status'], closed=c, opened=o, coin=r['coin_pair'], regime=r['market_regime'] or '(미기록)'))
by_sess = defaultdict(list)
for t in trades: by_sess[t['key']].append(t)

# ---- 세션별 ----
rows = []
for key, s in sorted(S.items(), key=lambda kv: (kv[0][0], int(kv[0][1]))):
    ts_ = sorted(by_sess[key], key=lambda t: t['closed'] or datetime.max)
    openn = sum(1 for t in ts_ if t['status'] != 'CLOSED')
    pnl = sum((t['pnl'] for t in ts_), D(0)); fee = sum((t['fee'] for t in ts_), D(0))
    eq_pnl = (s['avail'] - s['cap']) if (s['avail'] is not None and s['cap'] and openn == 0) else None
    eq, peak, mdd = s['cap'] or D(0), s['cap'] or D(0), 0.0
    for t in ts_:
        eq += t['pnl']; peak = max(peak, eq)
        if peak: mdd = min(mdd, float((eq - peak) / peak * 100))
    end = s['stop'] or datetime.fromisoformat(snap[:26])
    days = (end - s['start']).total_seconds() / 86400 if s['start'] else None
    wins = [t for t in ts_ if t['pnl'] > 0]
    rows.append(dict(engine=s['engine'], sid=s['sid'], strategy=s['strategy'], tf=s['tf'], coin=s['coin'], status=s['status'],
                     start=s['start'].isoformat(sep=' ')[:16] if s['start'] else '', stop=s['stop'].isoformat(sep=' ')[:16] if s['stop'] else '',
                     days=round(days, 1) if days is not None else None, cap=s['cap'], trades=len(ts_), open=openn,
                     pnl_net=pnl, fee=fee, ret_pct=pct(pnl, s['cap']), eq_pnl=eq_pnl,
                     eq_gap=(eq_pnl - pnl) if eq_pnl is not None else None, sess_pnl_gap=(s['sess_pnl'] - pnl) if s['sess_pnl'] is not None else None,
                     win=len(wins), mdd_realized_pct=round(mdd, 3), hold_med_h=statistics.median([t['hold'] for t in ts_ if t['hold'] is not None]) if ts_ else None,
                     cb=s.get('cb') or ''))
with open(out / 'per_session.csv', 'w', encoding='utf-8', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0].keys())); w.writeheader()
    for r in rows: w.writerow({k: (f'{v:.2f}' if isinstance(v, (D, float)) and k not in ('mdd_realized_pct',) else v) for k, v in r.items()})

# ---- 묶음별 ----
def agg(rs, tr):
    traded = [r for r in rs if r['trades']]
    cap = sum((r['cap'] for r in rs if r['cap']), D(0))
    capT = sum((r['cap'] for r in traded), D(0))
    pnl = sum((r['pnl_net'] for r in rs), D(0)); fee = sum((r['fee'] for r in rs), D(0))
    gw = sum((t['pnl'] for t in tr if t['pnl'] > 0), D(0)); gl = -sum((t['pnl'] for t in tr if t['pnl'] < 0), D(0))
    rets = [t['ret'] for t in tr if t['ret'] is not None]
    sret = [r['ret_pct'] for r in traded if r['ret_pct'] is not None]
    sdays = sum(r['days'] or 0 for r in rs)
    return dict(sessions=len(rs), traded=len(traded), trades=len(tr), session_days=round(sdays), trades_per_100d=round(len(tr) / sdays * 100, 1) if sdays else None,
                pnl_net=round(float(pnl)), fee=round(float(fee)), gross_before_fee=round(float(pnl + fee)),
                ret_on_traded_cap_pct=round(pct(pnl, capT), 3) if capT else None,
                sess_pos=sum(1 for x in sret if x > 0), sess_ret_median=round(statistics.median(sret), 3) if sret else None,
                win_rate=round(sum(1 for t in tr if t['pnl'] > 0) / len(tr) * 100, 1) if tr else None,
                avg_trade_ret=round(statistics.mean(rets), 3) if rets else None, med_trade_ret=round(statistics.median(rets), 3) if rets else None,
                profit_factor=round(float(gw / gl), 2) if gl else None,
                hold_med_h=round(statistics.median([t['hold'] for t in tr if t['hold'] is not None]), 1) if tr else None,
                mdd_worst=min((r['mdd_realized_pct'] for r in traded), default=None))
G = defaultdict(lambda: ([], []))
for r in rows:
    k = (r['engine'], r['strategy'], r['tf']); G[k][0].append(r)
for t in trades:
    s = S[t['key']]; G[(s['engine'], s['strategy'], s['tf'])][1].append(t)
grp = [dict(engine=k[0], strategy=k[1], tf=k[2], **agg(*v)) for k, v in sorted(G.items())]
E = defaultdict(lambda: ([], []))
for r in rows: E[r['engine']][0].append(r)
for t in trades: E[t['key'][0]][1].append(t)
eng = [dict(engine=k, **agg(*v)) for k, v in sorted(E.items())]
SG = defaultdict(lambda: ([], []))   # 엔진 무관 전략·주기
for r in rows:
    if r['engine'] in ('PAPER', 'DYN_PAPER'): SG[(r['strategy'], r['tf'])][0].append(r)
for t in trades:
    s = S[t['key']]
    if s['engine'] in ('PAPER', 'DYN_PAPER'): SG[(s['strategy'], s['tf'])][1].append(t)
sgrp = [dict(strategy=k[0], tf=k[1], **agg(*v)) for k, v in sorted(SG.items())]

# 청산 사유
X = defaultdict(list)
for t in trades: X[(t['key'][0], t['exit'])].append(t)
exits = [dict(engine=k[0], exit=k[1], n=len(v), pnl=round(float(sum((t['pnl'] for t in v), D(0)))),
              win_rate=round(sum(1 for t in v if t['pnl'] > 0) / len(v) * 100, 1),
              avg_ret=round(statistics.mean([t['ret'] for t in v if t['ret'] is not None]), 3) if any(t['ret'] is not None for t in v) else None,
              hold_med_h=round(statistics.median([t['hold'] for t in v if t['hold'] is not None]), 1) if any(t['hold'] is not None for t in v) else None)
         for k, v in sorted(X.items())]
# 월별(청산 월)
M = defaultdict(list)
for t in trades:
    if t['closed'] and t['key'][0] in ('PAPER', 'DYN_PAPER'): M[(t['key'][0], t['closed'].strftime('%Y-%m'))].append(t)
months = [dict(engine=k[0], month=k[1], n=len(v), pnl=round(float(sum((t['pnl'] for t in v), D(0)))), fee=round(float(sum((t['fee'] for t in v), D(0)))),
               win_rate=round(sum(1 for t in v if t['pnl'] > 0) / len(v) * 100, 1),
               avg_ret=round(statistics.mean([t['ret'] for t in v if t['ret'] is not None]), 3)) for k, v in sorted(M.items())]
# 시장 국면(진입 시 기록)
R = defaultdict(list)
for t in trades:
    if t['key'][0] in ('PAPER', 'DYN_PAPER'): R[(t['key'][0], t['regime'])].append(t)
regimes = [dict(engine=k[0], regime=k[1], n=len(v), pnl=round(float(sum((t['pnl'] for t in v), D(0)))),
                win_rate=round(sum(1 for t in v if t['pnl'] > 0) / len(v) * 100, 1),
                avg_ret=round(statistics.mean([t['ret'] for t in v if t['ret'] is not None]), 3)) for k, v in sorted(R.items())]

# ---- 신호→실행 ----
def bcat(s):
    if not s: return ''
    for pat, name in [(r'^이미 포지션 보유|POSITION_MONITORING|이미 보유 중', '이미 보유'), (r'^청산할 포지션 없음|SCANNING', '청산 대상 없음'),
                      (r'BLACK_SWAN', 'BLACK_SWAN 쿨다운'), (r'EMA200', 'EMA200 레짐 필터'), (r'동일코인 노출', '동일 코인 노출 상한'),
                      (r'최대 포지션 수', '최대 포지션 수'), (r'손실 청산 쿨다운', '손실 청산 쿨다운'), (r'보유시간 미달', '최소 보유시간'),
                      (r'잔액|자금|가용|최소 주문|풀', '자금·최소 주문'), (r'BTC', 'BTC 관련 관문'), (r'서킷|circuit|MDD|일일 손실', '손실 한도·서킷'),
                      (r'신선|stale|오래된|캔들|데이터|미확인|판정 불가', '입력 부족·판정 불가')]:
        if re.search(pat, s, re.I): return name
    return '기타:' + re.sub(r'[0-9.\-]+', '#', s)[:40]
F = defaultdict(lambda: defaultdict(int)); other = defaultdict(int)
eng_of = {'PAPER': 'PAPER', 'DYN_PAPER': 'DYN_PAPER', 'DYNAMIC': 'DYN_REAL', 'LIVE': 'LIVE'}
for r in T['signal_summary']:
    k = (eng_of.get(r['session_type'], r['session_type']), r['strategy_name']); n = int(r['n'])
    sig, ex = r['signal'], r['was_executed'] == 't'
    F[k][sig] += n
    if ex: F[k][sig + '_실행'] += n
    elif sig in ('BUY', 'SELL'):
        c = bcat(r['blocked_reason']) or '(사유 없음)'
        F[k][sig + '_미실행:' + c] += n
        if c.startswith('기타:'): other[c] += n
funnel = [dict(engine=k[0], strategy=k[1], **dict(sorted(v.items()))) for k, v in sorted(F.items())]

# ---- 보충: 규칙 해시·독립 거래·손절 폭 ----
rh = {}
for r in csv.DictReader(io.StringIO(raw2.split('@@ruleset\n', 1)[1])):
    rh[('PAPER' if r['src'] == 'PAPER' else kind2eng[r['src']], r['id'])] = r['ruleset_hash']
RH = defaultdict(lambda: defaultdict(int))
for r in P:
    if (r['engine'], r['session_id']) in S:
        s_ = S[(r['engine'], r['session_id'])]
        RH[(r['engine'], s_['strategy'])][rh.get((r['engine'], r['id']), '?')[:12] or '(없음)'] += 1
ruleset = [dict(engine=k[0], strategy=k[1], hashes=dict(v)) for k, v in sorted(RH.items())]
def indep(eng):
    u = {}
    for t, r in ((t, t) for t in trades if t['key'][0] == eng):
        k = (t['coin'], t['opened'].isoformat()[:16] if t['opened'] else '', t['closed'].isoformat()[:16] if t['closed'] else '')
        u.setdefault(k, []).append(t)
    rs = [v[0]['ret'] for v in u.values() if v[0]['ret'] is not None]
    dup = sum(1 for v in u.values() if len({S[t['key']]['strategy'] for t in v}) > 1)
    return dict(engine=eng, trades=sum(len(v) for v in u.values()), independent=len(u), multi_strategy_groups=dup,
                mean_ret=round(statistics.mean(rs), 3), median_ret=round(statistics.median(rs), 3),
                win_rate=round(sum(1 for x in rs if x > 0) / len(rs) * 100, 1),
                exits=dict(sorted(defaultdict(int, {e: sum(1 for v in u.values() if v[0]['exit'] == e) for e in {v[0]['exit'] for v in u.values()}}).items())))
independent = [indep(e) for e in ('PAPER', 'DYN_PAPER')]
def q3(a): qs = statistics.quantiles(a, n=10); return [round(qs[0], 2), round(statistics.median(a), 2), round(qs[8], 2)]
stops = {}
for en in ("PAPER", "DYN_PAPER"):
    src = [r for r in P if r["engine"] == en and (en, r["session_id"]) in S]
    sl = [float((D(r['entry_price']) - D(r['stop_loss_price'])) / D(r['entry_price']) * 100) for r in src if r['stop_loss_price']]
    tp = [float((D(r['take_profit_price']) - D(r['entry_price'])) / D(r['entry_price']) * 100) for r in src if r['take_profit_price']]
    mins = [t['hold'] * 60 for t in trades if t["key"][0] == en and t["exit"] == "STOP_LOSS" and t['hold'] is not None]
    stops[en] = dict(sl_width_pct_p10_p50_p90=q3(sl), tp_width_pct_p10_p50_p90=q3(tp), n=len(sl),
                      stop_exit_minutes_p10_p50_p90=q3(mins), stop_exit_within_60min_pct=round(sum(1 for m in mins if m <= 60) / len(mins) * 100, 1))
    # 손절 폭 구간별 성과(독립 거래 기준 아님, 세션 거래 기준)
    bands = defaultdict(list)
    for r in src:
        if not r['stop_loss_price']: continue
        w = float((D(r['entry_price']) - D(r['stop_loss_price'])) / D(r['entry_price']) * 100)
        b = '<0.5%' if w < 0.5 else '0.5~1%' if w < 1 else '1~3%' if w < 3 else '>=3%'
        bands[b].append(D(r['realized_pnl']) / (D(r['invested_krw'] or 0) or D(r['size']) * D(r['avg_price'])) * 100)
    stops[en]['by_sl_band'] = {b: dict(n=len(v), mean_ret=round(float(sum(v) / len(v)), 3), win_rate=round(sum(1 for x in v if x > 0) / len(v) * 100, 1)) for b, v in sorted(bands.items())}

# ---- 보충 3: 같은 기간 보유(비교 지표, 판정 문턱 아님) ----
BH = {}
for r in csv.DictReader(io.StringIO(raw3.split('@@bh\n', 1)[1])):
    s_ = S.get((r['engine'], r['id']))
    if not s_ or not r['close_start'] or not r['close_end']: continue
    t0, t1 = ts(r['t_start']), ts(r['t_end'])
    end = s_['stop'] or datetime.fromisoformat(snap[:26])
    if s_['start'] is None or (t0 - s_['start']).total_seconds() > 7200 or (end - t1).total_seconds() > 7200 or t1 <= t0:
        continue   # 시세 범위가 세션 기간을 덮지 못하면 비교하지 않는다
    BH[(r['engine'], r['id'])] = float((D(r['close_end']) / D(r['close_start']) - 1) * 100)
bhg = defaultdict(list)
for r in rows:
    k = (r['engine'], r['sid'])
    if k in BH and r['trades']:
        bhg[(r['engine'], r['strategy'], r['tf'])].append((r['ret_pct'], BH[k]))
bh_rows = [dict(engine=k[0], strategy=k[1], tf=k[2], n=len(v), sess_ret_median=round(statistics.median(a for a, _ in v), 3),
                hold_ret_median=round(statistics.median(b for _, b in v), 3), beat_hold=sum(1 for a, b in v if a > b),
                both_negative=sum(1 for a, b in v if a < 0 and b < 0)) for k, v in sorted(bhg.items())]
bh_all = {}
for e in ('PAPER', 'DYN_PAPER', 'DYN_REAL', 'LIVE'):
    v = [(r['ret_pct'], BH[(r['engine'], r['sid'])]) for r in rows if r['engine'] == e and r['trades'] and (r['engine'], r['sid']) in BH]
    if v: bh_all[e] = dict(n=len(v), sess_ret_median=round(statistics.median(a for a, _ in v), 3), hold_ret_median=round(statistics.median(b for _, b in v), 3),
                           beat_hold=sum(1 for a, b in v if a > b), hold_negative=sum(1 for _, b in v if b < 0))
for r in rows: r['hold_ret_pct'] = round(BH[(r['engine'], r['sid'])], 3) if (r['engine'], r['sid']) in BH else None

res = dict(snapshot_ts=snap, hold_compare=dict(by_engine=bh_all, by_group=bh_rows), ruleset=ruleset, independent=independent, stops=stops, excluded='paper_trading 522~587(사전 등록 66세션) 성과 비열람', orphan_positions=dict(orphan),
           engines=eng, groups=grp, paper_strategy=sgrp, exits=exits, months=months, regimes=regimes, funnel=funnel,
           blocked_other_top=sorted(other.items(), key=lambda kv: -kv[1])[:30],
           consistency=dict(eq_gap_nonzero=[(r['engine'], r['sid'], float(r['eq_gap'])) for r in rows if r['eq_gap'] is not None and abs(r['eq_gap']) > 1],
                            sess_pnl_gap_nonzero=[(r['sid'], float(r['sess_pnl_gap'])) for r in rows if r['sess_pnl_gap'] is not None and abs(r['sess_pnl_gap']) > 1],
                            eq_unknown=[(r['engine'], r['sid']) for r in rows if r['eq_pnl'] is None]))
with open(out / 'per_session.csv', 'w', encoding='utf-8', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0].keys())); w.writeheader()
    for r in rows: w.writerow({k: (f'{v:.2f}' if isinstance(v, (D, float)) and k not in ('mdd_realized_pct', 'hold_ret_pct') else v) for k, v in r.items()})
(out / 'scorecard.json').write_text(json.dumps(res, ensure_ascii=False, indent=1, default=str), encoding='utf-8')
print(json.dumps(dict(hold=bh_all, independent=independent, stops=stops, engines=[{k: e[k] for k in ('engine','sessions','traded','trades','pnl_net','fee')} for e in eng], orphan=dict(orphan), n_eq_gap=len(res['consistency']['eq_gap_nonzero']),
                      n_sess_gap=len(res['consistency']['sess_pnl_gap_nonzero']), n_eq_unknown=len(res['consistency']['eq_unknown'])), ensure_ascii=False, indent=1))
