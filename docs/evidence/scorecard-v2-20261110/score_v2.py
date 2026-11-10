# 성적표 v2 집계(2026-11-10, 총괄 v1.142 §110 보완 1~6).
# 사용: python -I -X utf8 score_v2.py <raw.txt> <raw2.txt> <raw4.txt> <raw5.txt> <출력 폴더>
# 입력: 1cb7a97b 제출 raw.txt·raw2.txt(그대로) + 이번 읽기 전용 추출 raw4.txt(ruleset_snapshot, 마감된 H1 종가)·raw5.txt(관문 HOLD 앞머리 집계).
# 사전 등록 66세션(522~587)은 세 추출 모두에서 성과·포지션·신호가 빠져 있다. 여기서도 다시 확인한다.
import csv, io, json, random, statistics, sys, pathlib
from collections import defaultdict, Counter
from datetime import datetime, timedelta
from decimal import Decimal as D

def sections(path):
    sec, cur = {}, None
    for line in pathlib.Path(path).read_text(encoding='utf-8').splitlines(keepends=True):
        if line.startswith('@@'):
            cur = line[2:].strip(); sec[cur] = []
        elif cur:
            sec[cur].append(line)
    return {k: list(csv.DictReader(io.StringIO(''.join(v)))) for k, v in sec.items()}

T, T2, T4, T5 = sections(sys.argv[1]), sections(sys.argv[2]), sections(sys.argv[3]), sections(sys.argv[4])
out = pathlib.Path(sys.argv[5]); out.mkdir(parents=True, exist_ok=True)
SNAP = datetime.fromisoformat(T['meta'][0]['snapshot_ts'][:26])

def dec(x): return D(x) if x not in (None, '') else None
def ts(x): return datetime.fromisoformat(x[:26]) if x else None
def fl(x): return float(x) if x is not None else None
def q(a, ps=(0.1, 0.25, 0.5, 0.75, 0.9)):
    if not a: return [None] * len(ps)
    s = sorted(a); n = len(s)
    return [round(s[min(n - 1, max(0, int(round(p * (n - 1)))))], 4) for p in ps]
def blind(sid): return sid not in ('', None) and 522 <= int(sid) <= 587

# ---- 사전 등록 보호 재확인 ----
assert all(r['prereg_blind'] == 't' for r in T['paper_sessions'] if blind(r['id']))
assert all(not r[c] for r in T['paper_sessions'] if blind(r['id']) for c in ('total_krw', 'available_krw', 'realized_pnl', 'total_fee'))
assert not any(blind(r['session_id']) for r in T['paper_positions'])
assert not any(r['session_type'] == 'PAPER' and blind(r['session_id']) for r in T['signal_summary'])
assert not any(r['src'] == 'PAPER' and blind(r['session_id']) for r in T2['ruleset'])
assert not any(r['session_type'] == 'PAPER' and blind(r['session_id']) for r in T5['gate_hold'])

# ---- 세션 ----
S = {}
for r in T['paper_sessions']:
    if r['prereg_blind'] == 't': continue
    S[('PAPER', r['id'])] = dict(engine='PAPER', sid=r['id'], strategy=r['strategy_name'], tf=r['timeframe'], coin=r['coin_pair'],
                                cap=dec(r['initial_capital']), start=ts(r['started_at']), stop=ts(r['stopped_at']),
                                slpct=r['stop_loss_pct'], params=r['strategy_params'])
dynmode = {}
for r in T['dynamic_sessions']:
    eng = 'DYN_PAPER' if r['trading_mode'] == 'PAPER' else 'DYN_REAL'; dynmode[r['id']] = eng
    S[(eng, r['id'])] = dict(engine=eng, sid=r['id'], strategy=r['strategy_type'], tf=r['timeframe'], coin='(동적)',
                            cap=dec(r['initial_capital']), start=ts(r['started_at']), stop=ts(r['stopped_at']), slpct=r['stop_loss_pct'], params=r['strategy_params'])
for r in T['live_sessions']:
    S[('LIVE', r['id'])] = dict(engine='LIVE', sid=r['id'], strategy=r['strategy_type'], tf=r['timeframe'], coin=r['coin_pair'],
                               cap=dec(r['initial_capital']), start=ts(r['started_at']), stop=ts(r['stopped_at']), slpct=r['stop_loss_pct'], params=r['strategy_params'])

# ---- 규칙 지문: 손절 조임 제거 키 ----
snap = {r['ruleset_hash']: r for r in T4['ruleset_snapshot']}
KEY = 'exit.slTightenOnLoss=false'
def sl_class(h):
    if not h: return 'NULL(지문 없음)'
    if h not in snap: return 'NOSNAP(스냅숏 없음)'
    return 'POST(조임 제거 키 있음)' if KEY in snap[h]['params_text'].split('\n') else 'PRE(조임 제거 키 없음)'
rh = {(r['src'], r['id']): r['ruleset_hash'] for r in T2['ruleset']}

# ---- 포지션(모든 행 보존) ----
kind2eng = {'DYN_PAPER': 'DYN_PAPER', 'DYNAMIC': 'DYN_REAL', 'LIVE': 'LIVE'}
P = []
for src, rows in (('PAPER', T['paper_positions']), ('PUB', T['public_positions'])):
    for r in rows:
        eng = 'PAPER' if src == 'PAPER' else kind2eng[r['session_kind']]
        key = (eng, r['session_id'])
        if key not in S: continue
        pnl, fee = dec(r['realized_pnl']) or D(0), dec(r['position_fee']) or D(0)
        inv = dec(r['invested_krw']) or dec(r.get('entry_allocation_krw')) or ((dec(r['size']) or D(0)) * (dec(r['avg_price']) or D(0)))
        o, c = ts(r['opened_at']), ts(r['closed_at'])
        e, sl = dec(r['entry_price']), dec(r['stop_loss_price'])
        h = rh.get(('PAPER' if src == 'PAPER' else r['session_kind'], r['id']), '')
        P.append(dict(eng=eng, key=key, id=r['id'], coin=r['coin_pair'], pnl=pnl, fee=fee, inv=inv, ret=float(pnl / inv * 100) if inv else None,
                      o=o, c=c, hold_min=(c - o).total_seconds() / 60 if o and c else None, exit=r['exit_reason'] or '(공란)',
                      sl_dist=float((e - sl) / e * 100) if e and sl else None, hash=h, cls=sl_class(h), status=r['status'],
                      strategy=S[key]['strategy'], tf=S[key]['tf']))
assert all(p['status'] == 'CLOSED' for p in P)
ENG = ['PAPER', 'DYN_PAPER', 'DYN_REAL', 'LIVE']
R = {}

# ==== 1. 손절: 지문으로 조임 제거 전후 분할 ====
def block(ps):
    stops = [p for p in ps if p['exit'] == 'STOP_LOSS']
    inv = sum((p['inv'] for p in ps), D(0)); pnl = sum((p['pnl'] for p in ps), D(0))
    return dict(n=len(ps), sessions=len({p['key'] for p in ps}),
                opened_min=min(p['o'] for p in ps).isoformat(sep=' ')[:16] if ps else None,
                opened_max=max(p['o'] for p in ps).isoformat(sep=' ')[:16] if ps else None,
                pnl=str(pnl.quantize(D('1'))), ret_on_invested_pct=round(float(pnl / inv * 100), 3) if inv else None,
                win_pct=round(sum(p['pnl'] > 0 for p in ps) / len(ps) * 100, 1) if ps else None,
                sl_dist_q10_25_50_75_90=q([p['sl_dist'] for p in ps if p['sl_dist'] is not None]),
                exits=dict(Counter(p['exit'] for p in ps).most_common()),
                stop_hold_min_median=round(statistics.median([p['hold_min'] for p in stops]), 1) if stops else None,
                stop_within_60min_pct=round(sum(p['hold_min'] <= 60 for p in stops) / len(stops) * 100, 1) if stops else None,
                session_stop_loss_pct=dict(sorted(Counter(S[k]['slpct'] for k in {p['key'] for p in ps}).items())),
                sessions_with_sl_override=sum(1 for k in {p['key'] for p in ps} if 'slAtrMultiplier' in (S[k]['params'] or '')))
R['sl_by_ruleset_class'] = {e: {c: block([p for p in P if p['eng'] == e and p['cls'] == c])
                                 for c in sorted({p['cls'] for p in P if p['eng'] == e})} for e in ENG}
post = [p for p in P if p['eng'] == 'PAPER' and p['cls'].startswith('POST')]
g = defaultdict(list)
for p in post: g[(p['strategy'], p['tf'])].append(p)
R['paper_post_by_strategy'] = [dict(strategy=k[0], tf=k[1], **block(v)) for k, v in sorted(g.items(), key=lambda kv: -len(kv[1]))]
R['ruleset_snapshot'] = [dict(hash=h, engine=r['engine'], first_seen_utc=r['first_seen_at'][:19], has_key=KEY in r['params_text'].split('\n'))
                         for h, r in snap.items()]
# PRE 와 POST 지문의 파라미터 차이(키 단위)
def params(h): return dict(l.split('=', 1) for l in snap[h]['params_text'].split('\n') if '=' in l)
pre_h = sorted({p['hash'] for p in P if p['eng'] == 'PAPER' and p['cls'].startswith('PRE')})
post_h = sorted({p['hash'] for p in post})
diff = defaultdict(set)
for a in pre_h + post_h:
    for k, v in params(a).items(): diff[k].add(v)
R['paper_param_variation'] = {k: sorted(v) for k, v in diff.items() if len(v) > 1}
R['paper_hashes'] = dict(pre=pre_h, post=post_h)

# ==== 2. 시각 묶음(모든 행 보존) ====
def mkey(p): return (p['coin'], p['o'].strftime('%Y-%m-%d %H:%M'), p['c'].strftime('%Y-%m-%d %H:%M'))
R['clusters'] = {}
for e in ENG:
    ps = [p for p in P if p['eng'] == e]; cl = defaultdict(list)
    for p in ps: cl[mkey(p)].append(p)
    multi = [v for v in cl.values() if len(v) > 1]
    same = [v for v in multi if max(p['ret'] for p in v) - min(p['ret'] for p in v) < 1e-6]
    R['clusters'][e] = dict(rows=len(ps), minute_clusters=len(cl), multi_row_clusters=len(multi), rows_in_multi=sum(len(v) for v in multi),
                            multi_same_return=len(same), multi_diff_return=len(multi) - len(same),
                            multi_diff_timeframe=sum(len({p['tf'] for p in v}) > 1 for v in multi),
                            multi_exact_pnl_ratio_differs=sum(len({p['pnl'] / p['inv'] for p in v}) > 1 for v in multi),
                            multi_spread_over_0_001pp=sum(max(p['ret'] for p in v) - min(p['ret'] for p in v) > 0.001 for v in multi),
                            multi_max_spread_pp=max((max(p['ret'] for p in v) - min(p['ret'] for p in v) for v in multi), default=None),
                            entry_hour_clusters=len({(p['coin'], p['o'].strftime('%Y-%m-%d %H')) for p in ps}),
                            entry_day_coin=len({(p['coin'], p['o'].strftime('%Y-%m-%d')) for p in ps}))
# 세션 쌍 완전 동일 거래(코인·진입분·청산분·수익률 6자리)
sig = defaultdict(list)
for k in sorted({p['key'] for p in P if p['eng'] == 'PAPER'}, key=lambda k: int(k[1])):  # 집합 순회 순서 고정(해시 무작위화)
    ps = sorted((mkey(p) + (round(p['ret'], 6),) for p in P if p['key'] == k))
    sig[tuple(ps)].append(k)
R['paper_identical_session_groups'] = [dict(sessions=[f"{S[k]['strategy']}/{S[k]['tf']}#{k[1]}" for k in v], trades=len(t))
                                       for t, v in sorted(sig.items(), key=lambda kv: int(kv[1][0][1])) if len(v) > 1 and len(t) >= 3]
# 시간 의존성 보존 근사: 진입 ISO 주 단위 블록 부트스트랩(투자금 가중 수익률). 판정용이 아닌 불확실성 규모 확인.
rng = random.Random(20261110)
R['weekly_block_bootstrap'] = {}
for e in ENG:
    ps = [p for p in P if p['eng'] == e and p['inv']]
    wk = defaultdict(list)
    for p in ps: wk[p['o'].isocalendar()[:2]].append(p)
    W = list(wk.values()); est = []
    for _ in range(2000):
        pick = [rng.choice(W) for _ in W]
        pn = sum(float(p['pnl']) for w in pick for p in w); iv = sum(float(p['inv']) for w in pick for p in w)
        est.append(pn / iv * 100)
    est.sort()
    pn = sum(float(p['pnl']) for p in ps); iv = sum(float(p['inv']) for p in ps)
    R['weekly_block_bootstrap'][e] = dict(weeks=len(W), point_pct=round(pn / iv * 100, 3), p05=round(est[100], 3), p95=round(est[1899], 3))

# 조임 제거 키가 있는 PAPER 만 같은 방식으로(이미 본 개발 자료라 평가가 아니다)
for nm, sel in (('PAPER_POST', lambda p: p['eng'] == 'PAPER' and p['cls'].startswith('POST')),
                ('PAPER_PRE', lambda p: p['eng'] == 'PAPER' and p['cls'].startswith('PRE'))):
    ps = [p for p in P if sel(p)]; wk = defaultdict(list)
    for p in ps: wk[p['o'].isocalendar()[:2]].append(p)
    W = list(wk.values()); est = []
    for _ in range(2000):
        pick = [rng.choice(W) for _ in W]
        est.append(sum(float(p['pnl']) for w in pick for p in w) / sum(float(p['inv']) for w in pick for p in w) * 100)
    est.sort()
    R['weekly_block_bootstrap'][nm] = dict(weeks=len(W), point_pct=round(sum(float(p['pnl']) for p in ps) / sum(float(p['inv']) for p in ps) * 100, 3),
                                           p05=round(est[100], 3), p95=round(est[1899], 3))
# POST 의 손절 폭 두 갈래: 세션 손절 5% 그대로 vs ATR/전략 제안
R['paper_post_sl_split'] = {}
for nm, sel in (('5%고정(±0.01)', lambda p: abs(p['sl_dist'] - 5) < 0.01), ('그 밖(ATR·전략 제안)', lambda p: abs(p['sl_dist'] - 5) >= 0.01)):
    ps = [p for p in post if p['sl_dist'] is not None and sel(p)]
    R['paper_post_sl_split'][nm] = block(ps)

# ==== 3. 신호 연결 ====
haspos = {p['key'] for p in P}
link = defaultdict(Counter); blocked = defaultdict(Counter)
for r in T['signal_summary']:
    st, sid, n = r['session_type'], r['session_id'], int(r['n'])
    eng = {'PAPER': 'PAPER', 'DYN_PAPER': 'DYN_PAPER', 'LIVE': 'LIVE'}.get(st) or dynmode.get(sid, 'DYN_?')
    key = (eng, sid)
    if key not in S: cat = '미연결: 세션이 추출 표에 없음'
    elif eng in ('PAPER', 'LIVE') and r['strategy_name'] != S[key]['strategy']: cat = '미연결: 전략 이름이 세션과 다름'
    elif key not in haspos: cat = '세션은 있으나 포지션 0'
    else: cat = '연결: 손익 표와 같은 세션'
    link[(eng, r['signal'])][cat] += n
    if cat.startswith('연결') and r['signal'] == 'BUY':
        blocked[eng]['실행' if r['was_executed'] == 't' else ('미실행:' + (r['blocked_reason'][:60] or '(사유 없음)'))] += n
R['signal_link'] = {f'{k[0]}/{k[1]}': dict(v) for k, v in sorted(link.items()) if k[1] in ('BUY', 'SELL')}
R['linked_buy_outcome_top'] = {e: dict(v.most_common(12)) for e, v in blocked.items()}
# PAPER·LIVE 관문은 BUY 를 기록 전에 HOLD 로 바꾼다 — BUY 행으로는 관문 효과를 셀 수 없다. HOLD 앞머리로 센다.
gh = defaultdict(Counter)
for r in T5['gate_hold']:
    key = (r['session_type'], r['session_id'])
    linked = key in S and key in haspos and r['strategy_name'] == S[key]['strategy']
    gh[(r['session_type'], '연결' if linked else ('세션 있음·포지션 0' if key in S else '미연결'))][r['gate']] += int(r['n'])
R['gate_hold'] = {f'{k[0]}/{k[1]}': dict(v) for k, v in sorted(gh.items())}
R['gate_vs_executed'] = {e: dict(executed_buy=blocked[e]['실행'], gated_buy_as_hold=sum(n for g, n in gh[(e, '연결')].items() if g != 'CANDLES_SHORT'))
                         for e in ('PAPER', 'LIVE')}

# ==== 4. 청산 사유별 기대손익(전체 모집단) ====
R['expectancy'] = {}
for e in ENG:
    ps = [p for p in P if p['eng'] == e]; inv = sum((p['inv'] for p in ps), D(0))
    rows = {}
    for x, v in Counter(p['exit'] for p in ps).most_common():
        vs = [p for p in ps if p['exit'] == x]
        rows[x] = dict(n=len(vs), share_pct=round(len(vs) / len(ps) * 100, 1), pnl=str(sum((p['pnl'] for p in vs), D(0)).quantize(D('1'))),
                       mean_ret_pct=round(statistics.mean(p['ret'] for p in vs), 3), win_pct=round(sum(p['pnl'] > 0 for p in vs) / len(vs) * 100, 1))
    sl = [p['ret'] for p in ps if p['exit'] == 'STOP_LOSS']; tp = [p['ret'] for p in ps if p['exit'] == 'TAKE_PROFIT']
    ref = (-statistics.mean(sl)) / (statistics.mean(tp) - statistics.mean(sl)) * 100 if sl and tp else None
    R['expectancy'][e] = dict(n=len(ps), win_pct_all=round(sum(p['pnl'] > 0 for p in ps) / len(ps) * 100, 1),
                              mean_ret_pct_all=round(statistics.mean(p['ret'] for p in ps), 3),
                              ret_on_invested_pct=round(float(sum((p['pnl'] for p in ps), D(0)) / inv * 100), 3),
                              by_exit=rows, ref_breakeven_sl_tp_only_pct=round(ref, 1) if ref else None,
                              ref_population=len(sl) + len(tp))

# ==== 5. 보유 비교: 마감된 봉, 비용 맞춤 ====
FEE, SLIP = 0.0005, 0.001
BH = {}
for r in T4['bh_closed']:
    BH[(r['engine'], r['id'])] = r
bh_rows = []
for key, s in S.items():
    ps = [p for p in P if p['key'] == key]
    b = BH.get(key)
    if not ps or not b or not b['close_start'] or not b['close_end']: continue
    st, en = ts(b['started_at']), ts(b['ended_at'])
    t0 = ts(b['bar_start_open']) + timedelta(hours=1); t1 = ts(b['bar_end_open']) + timedelta(hours=1)
    gap0, gap1 = (st - t0).total_seconds() / 60, (en - t1).total_seconds() / 60
    cost = (1 - FEE) ** 2 * ((1 - SLIP) ** 2 if s['engine'] in ('PAPER', 'DYN_PAPER') else 1)
    hold = (float(b['close_end']) / float(b['close_start']) * cost - 1) * 100
    dur_h = (en - st).total_seconds() / 3600
    inmkt = sum(p['hold_min'] for p in ps) / 60 / dur_h * 100 if dur_h > 0 else None
    sret = float(sum((p['pnl'] for p in ps), D(0)) / s['cap'] * 100) if s['cap'] else None
    bh_rows.append(dict(engine=s['engine'], sid=s['sid'], strategy=s['strategy'], tf=s['tf'], coin=b['coin_pair'], trades=len(ps),
                        dur_h=round(dur_h, 1), price_start_gap_min=round(gap0, 1), price_end_gap_min=round(gap1, 1),
                        session_ret_pct=round(sret, 3), hold_ret_net_pct=round(hold, 3), in_market_pct=round(inmkt, 1) if inmkt is not None else None,
                        usable=gap0 <= 120 and gap1 <= 120 and dur_h >= 24))
with open(out / 'hold_compare_v2.csv', 'w', encoding='utf-8', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(bh_rows[0].keys())); w.writeheader(); w.writerows(bh_rows)
R['hold_compare'] = {}
for e in ENG:
    rs = [r for r in bh_rows if r['engine'] == e and r['usable']]
    if not rs: R['hold_compare'][e] = dict(sessions=0); continue
    R['hold_compare'][e] = dict(sessions=len(rs), excluded=sum(1 for r in bh_rows if r['engine'] == e and not r['usable']),
                                beat_hold=sum(r['session_ret_pct'] > r['hold_ret_net_pct'] for r in rs),
                                positive_abs=sum(r['session_ret_pct'] > 0 for r in rs),
                                session_ret_median=round(statistics.median(r['session_ret_pct'] for r in rs), 3),
                                hold_net_median=round(statistics.median(r['hold_ret_net_pct'] for r in rs), 3),
                                in_market_median=round(statistics.median(r['in_market_pct'] for r in rs if r['in_market_pct'] is not None), 1),
                                gap_max_min=max(max(r['price_start_gap_min'], r['price_end_gap_min']) for r in rs))

# ==== 6. 문구 확인용: 양수 묶음 거래 수 ====
gg = defaultdict(list)
for p in P: gg[(p['eng'], p['strategy'], p['tf'])].append(p)
R['positive_groups'] = [dict(engine=k[0], strategy=k[1], tf=k[2], rows=len(v), sessions=len({p['key'] for p in v}),
                             pnl=str(sum((p['pnl'] for p in v), D(0)).quantize(D('1'))))
                        for k, v in sorted(gg.items()) if sum((p['pnl'] for p in v), D(0)) > 0]

with open(out / 'positions_v2.csv', 'w', encoding='utf-8', newline='') as f:
    w = csv.writer(f); w.writerow(['engine', 'session_id', 'position_id', 'strategy', 'tf', 'coin', 'opened_utc', 'closed_utc', 'hold_min', 'exit', 'ret_pct', 'sl_dist_pct', 'ruleset_hash', 'sl_class'])
    for p in sorted(P, key=lambda p: (p['eng'], int(p['id']))):
        w.writerow([p['eng'], p['key'][1], p['id'], p['strategy'], p['tf'], p['coin'], p['o'].isoformat(sep=' ')[:19], p['c'].isoformat(sep=' ')[:19],
                    round(p['hold_min'], 1), p['exit'], round(p['ret'], 6), round(p['sl_dist'], 6) if p['sl_dist'] is not None else '', p['hash'], p['cls']])
(out / 'scorecard_v2.json').write_text(json.dumps(R, ensure_ascii=False, indent=1), encoding='utf-8', newline='\n')
print(json.dumps(R, ensure_ascii=False, indent=1))
