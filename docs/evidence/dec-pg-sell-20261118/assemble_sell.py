"""P7 격리 PG 매도 현금흐름 — 스냅샷(P7SellCashflowPgTest 출력, v5 시계 메타데이터) → 대조기 v5 입력 조립·판정 (총괄 v1.189 §3-3).
네트워크·DB 접속 없음. 스냅샷은 읽기만 하고 입력·결과는 --out 에 새로 쓴다.

사용: python -I assemble_sell.py --snaps <run/snaps> --impl <p7v5/reconcile.py> --out <run/pairs>
종료값: 모든 기대 일치 0, 하나라도 다르면 1.
"""
import argparse, copy, hashlib, importlib.util, json, os, sys
from decimal import Decimal as D

SRC = {'environment': 'local-isolated', 'db': 'cat-rt-pg/p7_sell(Flyway 새 DB)', 'exchange_account': 'stand-in-exchange-book(P7SellCashflowPgTest)'}
APP = '0e1d2a02'
APP_REF = '로컬 작업 사본 D:/tmp/p7pg-0e1d2a02 = 운영 반영 커밋 0e1d2a02 + 시험 클래스(제품 변경 없음). 운영 관측 아님'
SAME = [{'axes': ['app', 'collector'], 'basis': '격리 PG 시험: 엔진(세션·주문 created_at, Instant.now())과 수집기가 같은 JVM — 측정 아님, 같은 프로세스 시계'},
        {'axes': ['exchange', 'collector'], 'basis': '대역 거래소(같은 JVM): done_query 기간은 조립기가 단계 시작 시각으로 기록 — 실제 거래소 시계 아님'}]


def load(snaps, label):
    raw = open(os.path.join(snaps, label + '.json'), 'rb').read()
    return json.loads(raw.decode('utf-8')), hashlib.sha256(raw).hexdigest()


def obs(sn):
    """스냅샷의 단계 구간·표본을 그대로 v5 meta 로 옮긴다(값을 고치지 않음)."""
    steps = [{'step': s['step'], 'start': s['start'], 'end': s['end']} for s in sn['steps']]
    start = {s['step']: s['start'] for s in steps}
    ob = {'meta': {'source': dict(SRC), 'app_code_sha': APP, 'app_code_sha_ref': APP_REF, 'steps': steps,
                   'clock': {'version': 'p7-clock-1', 'samples': copy.deepcopy(sn['clock_samples']), 'same_source': copy.deepcopy(SAME)}},
          'db_fp_pre': sn['db_fp_pre'], 'db': copy.deepcopy(sn['db']), 'db_fp_post': sn['db_fp_post'],
          'external_flows': [], 'query_errors': []}
    for side in ('exchange_before', 'exchange_after'):
        ex = copy.deepcopy(sn[side])
        # 대역 거래소는 모든 응답을 기억하므로 완료 목록이 시작부터 그 단계 시작까지 완전하다(실제 거래소 조회 증거 아님)
        ex['done_query'] = {'from': '2026-01-01T00:00:00Z', 'to': start[side], 'complete': True, 'pages': 1}
        ob[side] = ex
    return ob


def registry(base, now, drop=()):
    """기준에 이미 있는 세션은 적용 시작 = 세션 created_at(앱 축). 이 시험의 구간은 모두 기존 세션이다."""
    b = {(s['kind'], s['id']) for s in base['db']['sessions']}
    out = []
    for s in now['db']['sessions']:
        if s['mode'] != 'REAL' or (s['kind'], s['id']) in drop:
            continue
        old = (s['kind'], s['id']) in b
        out.append({'kind': s['kind'], 'id': s['id'], 'mode': 'REAL', 'strategy_type': s['strategy_type'], 'timeframe': s['timeframe'],
                    'initial_capital': s['initial_capital'], 'coin_pair': s['coin_pair'] if s['kind'] == 'LIVE' else 'DYNAMIC',
                    'code_sha': APP, 'effective_from': s['created_at'] if old else base['db']['observed_at'],
                    'effective_axis': 'app' if old else 'db', 'effective_to': None,
                    'session_created_at': s['created_at'] if old else None,
                    'rules': {'max_open_positions': 1, 'max_buy_request_krw': '20000'}})
    return {'registry_version': 'p7pg-sell-local-1', 'observation_source': dict(SRC), 'sessions': out}


def codes(r):
    return sorted({x['code'] for x in r.get('anomalies', [])} | {x['code'] for x in r.get('holds', [])})


def tamper_krw(d):
    for side in ('exchange_before', 'exchange_after'):
        a = d['now'][side]['accounts']['KRW']
        a['balance'] = str(D(a['balance']) - 1)


def tamper_coin(d):
    for side in ('exchange_before', 'exchange_after'):
        a = d['now'][side]['accounts']
        a['RND'] = {'balance': str(D(a.get('RND', {'balance': '0'})['balance']) + D('0.1')), 'locked': '0'}


def stale_exchange(base_snap):
    def f(d):   # DB 는 매도 정정 뒤, 거래소는 정정 전 응답 그대로 — 매도 정정이 거래소와 대조되는지
        for side in ('exchange_before', 'exchange_after'):
            keep = d['now'][side]['done_query']
            d['now'][side] = copy.deepcopy(base_snap[side])
            d['now'][side]['done_query'] = keep
    return f


def tamper_ledger(kind):
    def f(d):
        for s in d['now']['db']['sessions']:
            if s['kind'] == kind:
                s['available_krw'] = str(D(s['available_krw']) + D('0.01'))
    return f


def tamper_position(kind):
    def f(d):   # DB 포지션 잔량만 +1 (거래소·주문은 그대로) — 잔량 유지 대조가 수량을 보는지
        for p in d['now']['db']['positions']:
            if p['session_kind'] == kind:
                p['size'] = str(D(p['size']) + 1)
    return f


PAIRS = []
for p, kind in (('L', 'LIVE'), ('D', 'DYNAMIC')):
    PAIRS += [
        (f'{p}12_sell_submitted', f'{p}1-holding', f'{p}2-sell-submitted', 'HOLD', ['ORDER_IN_FLIGHT', 'POSITION_CLOSING'], [], None,
         f'{kind} 실제 매도 제출(CLOSING·진행 중) — 그 코인·KRW 보류'),
        (f'{p}23_sell_partial_active', f'{p}2-sell-submitted', f'{p}3-sell-partial', 'HOLD', ['ORDER_IN_FLIGHT'], [], None,
         '매도 부분 체결(활성) — 보류'),
        (f'{p}14_partial_cancel_remaining_kept', f'{p}1-holding', f'{p}4-sell-cancel-partial', 'OK', [], [], None,
         '매도 10 체결 뒤 취소 → 판 수량만 반영, 잔량 15 OPEN 유지'),
        (f'{p}45_reliquidated', f'{p}4-sell-cancel-partial', f'{p}5-reliquidated', 'OK', [], [], None, '같은 실제 매도 경로로 잔량 15 재청산'),
        (f'{p}56_sell_fee_corrections', f'{p}5-reliquidated', f'{p}6-sell-corrected', 'OK', [], [], None, '늦은 매도 수수료 정정 2회(settle APPLIED)'),
        (f'{p}67_same_response_repeated', f'{p}6-sell-corrected', f'{p}7-repeated', 'OK', [], [], None, '같은 응답 반복(NO_CHANGE)'),
        (f'{p}17_full_sell_window', f'{p}1-holding', f'{p}7-repeated', 'OK', [], [], None, '보유부터 재청산·정정·반복까지 한 구간'),
        (f'{p}N1_exchange_krw_minus_1', f'{p}1-holding', f'{p}4-sell-cancel-partial', 'ANOMALY', ['ACCOUNT_KRW_MISMATCH'], [], tamper_krw,
         '음성 대조: 거래소 KRW −1'),
        (f'{p}N2_exchange_coin_plus', f'{p}1-holding', f'{p}4-sell-cancel-partial', 'ANOMALY', ['ACCOUNT_COIN_MISMATCH'], [], tamper_coin,
         '음성 대조: 거래소 RND +0.1'),
        (f'{p}N3_sell_correction_not_on_exchange', f'{p}5-reliquidated', f'{p}6-sell-corrected', 'ANOMALY', ['ACCOUNT_KRW_MISMATCH'], [], 'STALE',
         '음성 대조: DB 매도 정정, 거래소는 정정 전'),
        (f'{p}N4_session_ledger_plus_1c', f'{p}6-sell-corrected', f'{p}7-repeated', 'ANOMALY', ['SESSION_LEDGER_MISMATCH'], [], tamper_ledger(kind),
         '음성 대조: 세션 잔액 +0.01'),
        (f'{p}N5_position_size_plus_1', f'{p}1-holding', f'{p}4-sell-cancel-partial', 'ANOMALY', ['ACCOUNT_COIN_MISMATCH'], [], tamper_position(kind),
         '음성 대조: DB 잔량 포지션만 +1'),
    ]


def realized_check(sn, kind):
    """보충 기록: 저장 실현손익과 주문 원천(매수 대금+수수료 평균가) 재계산 비교 — 판정 아님."""
    sup = sn['supplement']
    pos = [p for p in sup['positions'] if p['session_kind'] == kind]
    ords = [o for o in sup['orders'] if o['session_kind'] == kind]
    buys = [o for o in ords if o['side'] == 'BUY']
    sells = [o for o in ords if o['side'] == 'SELL' and o['state'] == 'FILLED']
    bq = sum(D(o['filled_quantity']) for o in buys)
    cost = sum(D(o['applied_executed_funds']) + D(o['applied_paid_fee']) for o in buys)
    sold = sum(D(o['filled_quantity']) for o in sells)
    net = sum(D(o['applied_executed_funds']) - D(o['applied_paid_fee']) for o in sells)
    from_orders = net - sold * (cost / bq)
    out = {'kind': kind, 'position': pos, 'buy_fee': str(sum(D(o['applied_paid_fee']) for o in buys)),
           'realized_from_orders': str(from_orders.normalize()), 'stored_realized': pos[0]['realized_pnl'] if pos else None,
           'stored_minus_orders': str((D(pos[0]['realized_pnl']) - from_orders).normalize()) if pos else None}
    if kind == 'DYNAMIC':
        out['dynamic_sell_settlement'] = sup['dynamic_sell_settlement']
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--snaps', required=True)
    ap.add_argument('--impl', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    spec = importlib.util.spec_from_file_location('reconcile_v5', a.impl)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    os.makedirs(a.out, exist_ok=True)
    lines = [f"impl {os.path.basename(a.impl)} sha256={hashlib.sha256(open(a.impl, 'rb').read()).hexdigest()}"]
    fails = 0
    for name, b, n, want, need, forbid, edit, why in PAIRS:
        bs, bh = load(a.snaps, b)
        ns, nh = load(a.snaps, n)
        kind = 'LIVE' if name.startswith('L') else 'DYNAMIC'
        d = {'registry': registry(bs, ns), 'base': obs(bs), 'now': obs(ns)}
        if edit == 'STALE':
            stale_exchange(bs)(d)
        elif callable(edit):
            edit(d)
        r = m.evaluate(d['registry'], d['base'], d['now'])
        cs = codes(r)
        bad = []
        if r.get('overall') != want:
            bad.append(f"overall {r.get('overall')}≠{want}")
        bad += [f'코드 없음 {c}' for c in need if c not in cs]
        bad += [f'금지 코드 {c}' for c in forbid if c in cs]
        if want == 'OK' and (r.get('anomalies') or r.get('holds')):
            bad.append('이상/보류 있음')
        acc = r.get('account') or {}
        k = acc.get('krw', {})
        coins = acc.get('coins', {}).get('RND', {})
        fails += bool(bad)
        lines.append(f"{'PASS' if not bad else 'FAIL'} | {name} | {r.get('overall')} | {','.join(cs)} | "
                     f"KRW Δ거래소={k.get('exchange_delta')} Δ장부={k.get('session_ledger_delta')} 원시={k.get('raw_residual')} "
                     f"설명={k.get('explained_rounding')} 조정={k.get('adjusted_residual')} [{k.get('status')}] "
                     f"RND Δ거래소={coins.get('exchange_delta_minus_external')} 체결={coins.get('net_filled')} 포지션={coins.get('open_position_delta')}"
                     + (f" | {'; '.join(bad)}" if bad else ''))
        with open(os.path.join(a.out, name + '.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump({'why': why, 'base_snapshot': {'label': b, 'sha256': bh}, 'now_snapshot': {'label': n, 'sha256': nh},
                       'expect': {'overall': want, 'codes': need, 'forbid': forbid}, 'input': d, 'result': r}, f,
                      ensure_ascii=False, indent=1, default=str)
    lines.append(f'합계 {len(PAIRS)} 통과 {len(PAIRS) - fails} 실패 {fails}')
    rc = {}
    for p, kind in (('L', 'LIVE'), ('D', 'DYNAMIC')):
        for lab in (f'{p}4-sell-cancel-partial', f'{p}5-reliquidated', f'{p}6-sell-corrected'):
            rc[lab] = realized_check(load(a.snaps, lab)[0], kind)
    with open(os.path.join(a.out, 'realized_check.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(rc, f, ensure_ascii=False, indent=1)
    for lab, v in rc.items():
        lines.append(f"보충 {lab}: 저장 실현={v['stored_realized']} 주문 원천={v['realized_from_orders']} 차={v['stored_minus_orders']} 매수 수수료={v['buy_fee']}")
    with open(os.path.join(a.out, 'pairs.out.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
