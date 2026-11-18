"""P7 v2 병행 대조기 — 로컬 초안(총괄 v1.186 §2·§3-2·§3-3). 운영 설치본·RUNBOOK·F 원문과 무관하다.

입력은 모두 파일이다(네트워크·DB 접속 없음).
  --registry  등록표(JSON)
  --base      기준 관측 묶음(JSON)   --now  현재 관측 묶음(JSON)
관측 묶음 = {db_fp_pre, exchange_before, db(extract.sql 출력), exchange_after, db_fp_post, external_flows, query_errors}

판정 원칙
  1. 계좌 대조는 '현재 보유량'이 아니라 '공통 기준 시점 대비 증감'으로 한다.
     Δ거래소 수량(잔고+잠금) = 구간 순체결량(등록 주문) + 외부 입출금 = Δ내부 열린 포지션 수량.
  2. 새 세션의 초기 배정은 거래소 현금 유입이 아니다 — 주문 단위로 대조하므로 배정/회수는 계좌식에 들어가지 않고 내부 이전 목록으로만 낸다.
     정지한 등록 세션의 주문도 그대로 센다.
  3. 원 단위 허용 오차(세션 수×1원)를 두지 않는다. 주문별 반올림 규칙(BalanceAmounts: 매수 환급·매도 입금을 둘째 자리 HALF_UP)으로
     설명 가능한 차이를 계산하고 원시 잔차·설명분·조정 잔차를 모두 낸다. 조정 잔차는 --krw-quantum(기본 0) 안이어야 한다.
  4. 이상(🔴)과 보류(❔)는 따로 낸다. 보류가 있어도 이미 확인된 이상은 그대로 이상이다. 새 대기 시간 기준은 두지 않는다.
  5. 등록 키는 (종류, ID)이고 모드·전략·주기·자본·코인·적용 시각이 모두 맞아야 등록 세션으로 인정한다.
성과 평가(손익률 등)는 하지 않는다 — 현금·수량 대조만 한다.
"""
import argparse, json, sys
from decimal import Decimal as D, ROUND_HALF_UP
from datetime import datetime

ACTIVE = {'PENDING', 'INTENT_READY', 'SUBMITTING', 'SUBMITTED', 'PARTIAL_FILLED'}
TERMINAL_NOFILL = {'CANCELLED', 'FAILED', 'INTENT_REJECTED'}
RULES = {'max_filled_buys_in_window', 'max_open_positions', 'excluded_coins', 'max_buy_request_krw'}
REQUIRED = ('kind', 'id', 'mode', 'strategy_type', 'timeframe', 'initial_capital', 'coin_pair', 'code_sha', 'effective_from', 'effective_to', 'rules')
CENT = D('0.01')


def dec(v):
    return None if v is None else D(str(v))


def r2(x):
    return x.quantize(CENT, rounding=ROUND_HALF_UP)


def ts(v):
    if v is None:
        return None
    return datetime.fromisoformat(str(v).replace('Z', '+00:00'))


class Result:
    def __init__(self):
        self.anomalies, self.holds, self.notes = [], [], []

    def anomaly(self, code, **kw):
        self.anomalies.append({'code': code, **kw})

    def hold(self, code, **kw):
        self.holds.append({'code': code, **kw})


# ── 등록표 ─────────────────────────────────────────────────────
def load_registry(reg, res):
    entries, ok = {}, True
    if not isinstance(reg, dict) or not isinstance(reg.get('sessions'), list):
        res.anomaly('REGISTRY_INVALID', detail='sessions 목록 없음')
        return {}, False
    for i, e in enumerate(reg['sessions']):
        miss = [k for k in REQUIRED if k not in e]
        bad = []
        if miss:
            bad.append(f'필드 누락 {miss}')
        else:
            if e['kind'] not in ('LIVE', 'DYNAMIC'):
                bad.append('kind')
            if e['mode'] != 'REAL':
                bad.append('mode≠REAL')
            if not isinstance(e['id'], int):
                bad.append('id')
            if e['kind'] == 'LIVE' and not str(e['coin_pair']).startswith('KRW-'):
                bad.append('LIVE coin_pair')
            if e['kind'] == 'DYNAMIC' and e['coin_pair'] != 'DYNAMIC':
                bad.append('DYNAMIC coin_pair≠DYNAMIC')
            try:
                dec(e['initial_capital']); ts(e['effective_from']); ts(e['effective_to'])
            except Exception:
                bad.append('수치/시각 형식')
            unknown = set(e['rules']) - RULES if isinstance(e['rules'], dict) else {'rules'}
            if unknown:
                bad.append(f'알 수 없는 규칙 {sorted(unknown)}')
            key = (e['kind'], e['id'])
            if key in entries:
                bad.append('중복 키')
        if bad:
            ok = False
            res.anomaly('REGISTRY_INVALID', index=i, detail='; '.join(bad))
        else:
            entries[(e['kind'], e['id'])] = e
    if not ok:
        # 🔴 등록표가 하나라도 잘못되면 전체를 믿지 않는다 — 모든 실자금 주문이 등록 밖으로 잡힌다.
        return {}, False
    return entries, True


def is_paper(s):
    # 제품 규칙: DYNAMIC 은 정확히 'PAPER' 가 아니면 REAL 분기. LIVE 도 같은 보수 규칙을 쓴다.
    return s is not None and s.get('mode') == 'PAPER'


def contract_diff(e, s):
    diffs = []
    if s.get('mode') != e['mode']:
        diffs.append(f"mode {s.get('mode')}≠{e['mode']}")
    for k in ('strategy_type', 'timeframe'):
        if s.get(k) != e[k]:
            diffs.append(f'{k} {s.get(k)}≠{e[k]}')
    if dec(s.get('initial_capital')) != dec(e['initial_capital']):
        diffs.append(f"initial_capital {s.get('initial_capital')}≠{e['initial_capital']}")
    if e['kind'] == 'LIVE' and s.get('coin_pair') != e['coin_pair']:
        diffs.append(f"coin_pair {s.get('coin_pair')}≠{e['coin_pair']}")
    created = ts(s.get('created_at'))
    if created and created < ts(e['effective_from']):
        diffs.append('세션 생성이 등록 적용 시각보다 앞섬')
    return diffs


# ── 주문별 금액 ────────────────────────────────────────────────
def deducted(o):
    """매수 제출 때 세션에서 뺀 금액(DynamicTradingService/LiveTradingService restorableKrw 와 같은 규칙)."""
    if o.get('reserved_krw') is not None:
        return dec(o['reserved_krw'])
    if o.get('client_order_id') is not None:
        return D(0)
    return dec(o['quantity'])


def order_effects(o):
    """(거래소 KRW 증감, 세션 장부 증감, 코인 수량 증감, 설명 가능한 반올림, 확정 여부)"""
    side, st = o['side'], o['state']
    fq = dec(o.get('filled_quantity')) or D(0)
    af, fee = dec(o.get('applied_executed_funds')), dec(o.get('applied_paid_fee'))
    applied = af is not None and fee is not None
    if side == 'BUY':
        if fq > 0 and applied:
            x = dec(o['quantity']) - af - fee          # 미사용 환급(8자리 원시)
            ex, led = -(af + fee), -deducted(o) + r2(x)
            return ex, led, fq, ex - led, True
        if fq == 0 and st in TERMINAL_NOFILL:
            return D(0), D(0), D(0), D(0), True     # 예약 복원 → 세션 증감 0
        return D(0), -deducted(o), D(0), D(0), False  # 진행 중·반영 대기
    # SELL
    if fq > 0 and applied:
        y = af - fee
        ex, led = y, r2(y)
        return ex, led, -fq, ex - led, True
    if fq == 0 and st in TERMINAL_NOFILL:
        return D(0), D(0), D(0), D(0), True
    return D(0), D(0), D(0), D(0), False


# ── 관측 안정성 ────────────────────────────────────────────────
def check_observation(name, ob, res):
    stable = True
    if ob.get('query_errors'):
        res.hold('QUERY_FAILED', at=name, detail=ob['query_errors'])
        stable = False
    if ob.get('db') is None or ob.get('exchange_before') is None or ob.get('exchange_after') is None:
        res.hold('OBSERVATION_MISSING', at=name)
        return False
    fp = ob['db'].get('fingerprint')
    if not (ob.get('db_fp_pre') == fp == ob.get('db_fp_post')):
        res.hold('UNSTABLE_DB', at=name, detail='추출 전·중·후 DB 지문이 다름 — 읽는 사이 주문/반영 변화')
        stable = False
    eb, ea = ob['exchange_before'], ob['exchange_after']
    for k in ('accounts', 'open_orders', 'done_order_ids'):
        if eb.get(k) != ea.get(k):
            res.hold('UNSTABLE_EXCHANGE', at=name, detail=f'거래소 전후 {k} 다름')
            stable = False
    if ea.get('open_orders'):
        res.hold('EXCHANGE_OPEN_ORDERS', at=name, ids=ea['open_orders'])
        stable = False
    return stable


def total(acc, cur):
    a = acc.get(cur)
    return D(0) if a is None else dec(a['balance']) + dec(a['locked'])


# ── 본 대조 ────────────────────────────────────────────────────
def reconcile(reg, base, now, krw_quantum=D(0)):
    res = Result()
    entries, reg_ok = load_registry(reg, res)
    stable = check_observation('base', base, res) & check_observation('now', now, res)
    db0, db1 = base.get('db') or {}, now.get('db') or {}
    t0 = ts(db0.get('observed_at'))
    s0 = {(s['kind'], s['id']): s for s in db0.get('sessions', [])}
    s1 = {(s['kind'], s['id']): s for s in db1.get('sessions', [])}
    o0 = {o['id']: o for o in db0.get('orders', [])}
    o1 = {o['id']: o for o in db1.get('orders', [])}

    # 세션 등록 대조
    registered = {}
    for key, e in entries.items():
        s = s1.get(key)
        if s is None:
            res.anomaly('REGISTERED_SESSION_ROW_MISSING', session=list(key))
            registered[key] = e   # 🔴 행이 사라져도 그 주문은 계속 센다
            continue
        d = contract_diff(e, s)
        if d:
            res.anomaly('REGISTRY_CONTRACT_MISMATCH', session=list(key), detail=d)
        else:
            registered[key] = e
    for key, s in s1.items():
        if is_paper(s) or key in registered:
            continue
        if s.get('status') != 'STOPPED':
            res.anomaly('UNREGISTERED_REAL_SESSION_ACTIVE', session=list(key), status=s.get('status'), mode=s.get('mode'))

    # 주문 분류
    win = {}   # 구간에 변한 실자금 주문
    for oid, o in o1.items():
        key = (o['session_kind'], o['session_id'])
        s = s1.get(key)
        if is_paper(s):
            continue
        prev = o0.get(oid)
        changed = prev is None or any(prev.get(k) != o.get(k) for k in
                                      ('state', 'filled_quantity', 'applied_executed_funds', 'applied_paid_fee'))
        e = registered.get(key)
        in_window = e is not None and ts(o['created_at']) >= ts(e['effective_from']) and \
            (e['effective_to'] is None or ts(o['created_at']) <= ts(e['effective_to']))
        if (changed or o['state'] in ACTIVE or o['state'] == 'UNKNOWN') and not in_window:
            res.anomaly('UNREGISTERED_REAL_ORDER', order=oid, session=list(key), state=o['state'],
                        coin=o['coin_pair'], side=o['side'], session_row='없음' if s is None else s.get('status'))
        if o['state'] == 'UNKNOWN':
            res.anomaly('ORDER_UNKNOWN', order=oid, session=list(key))
        elif o['state'] in ACTIVE:
            res.hold('ORDER_IN_FLIGHT', order=oid, session=list(key), state=o['state'], created_at=o['created_at'])
        fq = dec(o.get('filled_quantity')) or D(0)
        if fq > 0:
            if o.get('fill_accounting_pending'):
                res.hold('ACCOUNTING_PENDING', order=oid, session=list(key))
            elif None in (o.get('reported_executed_funds'), o.get('reported_paid_fee'),
                          o.get('applied_executed_funds'), o.get('applied_paid_fee')):
                res.hold('SETTLEMENT_UNCONFIRMED', order=oid, session=list(key))
            elif dec(o['reported_executed_funds']) != dec(o['applied_executed_funds']) or \
                    dec(o['reported_paid_fee']) != dec(o['applied_paid_fee']):
                res.anomaly('SETTLEMENT_MISMATCH', order=oid, session=list(key))
        if changed:
            win[oid] = (o, prev, key, in_window)
    for d in db1.get('unresolved_discrepancies', []):
        res.anomaly('SETTLEMENT_DISCREPANCY_UNRESOLVED', id=d['id'], order=d['order_id'], kind=d['kind'])

    # 포지션
    p1 = db1.get('positions', [])
    for p in p1:
        key = (p['session_kind'], p['session_id'])
        if is_paper(s1.get(key)):
            continue
        if key not in registered:
            res.anomaly('UNREGISTERED_REAL_POSITION', position=p['id'], session=list(key), coin=p['coin_pair'])
        if p['status'] == 'CLOSING':
            res.hold('POSITION_CLOSING', position=p['id'], session=list(key))

    # 거래소 주문 ↔ DB
    db_uuids = {o.get('exchange_order_id') for o in o1.values() if o.get('exchange_order_id')}
    declared_manual = {f.get('ref') for f in (now.get('external_flows') or []) if f.get('kind') == 'MANUAL_TRADE'}
    ea = now.get('exchange_after') or {}
    for u in list(ea.get('done_order_ids') or []) + list(ea.get('open_orders') or []):
        if u not in db_uuids and u not in declared_manual:
            res.anomaly('EXCHANGE_ORDER_NOT_IN_DB', uuid=u)

    # 세션별 규칙(전략·엔진별로 등록표가 정한다 — 단독 규칙을 그대로 복사하지 않는다)
    for key, e in registered.items():
        r = e['rules']
        mine = [o for o in o1.values() if (o['session_kind'], o['session_id']) == key]
        win_mine = [o for o in mine if t0 is None or ts(o['created_at']) >= t0]
        if e['kind'] == 'LIVE':
            for o in win_mine:
                if o['coin_pair'] != e['coin_pair']:
                    res.anomaly('RULE_COIN_OUT_OF_CONTRACT', session=list(key), order=o['id'], coin=o['coin_pair'])
        if 'max_filled_buys_in_window' in r:
            n = sum(1 for o in win_mine if o['side'] == 'BUY' and (dec(o.get('filled_quantity')) or 0) > 0)
            if n > r['max_filled_buys_in_window']:
                res.anomaly('RULE_FILLED_BUYS', session=list(key), count=n, limit=r['max_filled_buys_in_window'])
        if 'max_open_positions' in r:
            n = sum(1 for p in p1 if (p['session_kind'], p['session_id']) == key)
            if n > r['max_open_positions']:
                res.anomaly('RULE_OPEN_POSITIONS', session=list(key), count=n, limit=r['max_open_positions'])
        for o in win_mine:
            if o['side'] == 'BUY' and o['coin_pair'] in r.get('excluded_coins', []):
                res.anomaly('RULE_EXCLUDED_COIN_BUY', session=list(key), order=o['id'], coin=o['coin_pair'])
            if 'max_buy_request_krw' in r and o['side'] == 'BUY' and o['order_type'] == 'MARKET' and \
                    dec(o['quantity']) > dec(r['max_buy_request_krw']):
                res.anomaly('RULE_BUY_REQUEST_CAP', session=list(key), order=o['id'], quantity=o['quantity'])
        per_pos = {}
        for o in mine:
            if o['side'] == 'BUY' and (dec(o.get('filled_quantity')) or 0) > 0 and o.get('position_id') is not None:
                per_pos.setdefault(o['position_id'], []).append(o['id'])
        for pid, ids in per_pos.items():
            if len(ids) > 1:
                res.anomaly('RULE_DUP_BUY_POSITION', session=list(key), position=pid, orders=ids)

    # 계좌·세션 장부 대조 — 보류가 있어도 계산은 내되 판정은 보류로 둔다
    flows = now.get('external_flows')
    if flows is None:
        res.hold('EXTERNAL_FLOWS_UNCONFIRMED', detail='구간 외부 입출금 이력이 입력되지 않음')
        flows = []
    ext = {}
    for f in flows:
        if f.get('kind') in ('DEPOSIT', 'WITHDRAW', 'MANUAL_TRADE'):
            ext[f['currency']] = ext.get(f['currency'], D(0)) + dec(f['amount'])
    ex_sum, led_sum, explained = D(0), D(0), D(0)
    coin_net, all_final = {}, True
    per_session = {}
    for oid, (o, prev, key, in_window) in win.items():
        if not in_window:
            continue
        ex1, led1, q1, rnd1, fin1 = order_effects(o)
        ex0, led0, q0, rnd0, fin0 = order_effects(prev) if prev else (D(0),) * 4 + (True,)
        all_final &= fin1
        ex_sum += ex1 - ex0
        led_sum += led1 - led0
        explained += rnd1 - rnd0
        cur = o['coin_pair'].split('-', 1)[1]
        coin_net[cur] = coin_net.get(cur, D(0)) + (q1 - q0)
        per_session[key] = per_session.get(key, D(0)) + (led1 - led0)
    acc0 = (base.get('exchange_after') or {}).get('accounts', {})
    acc1 = (now.get('exchange_after') or {}).get('accounts', {})
    dk = total(acc1, 'KRW') - total(acc0, 'KRW')
    raw = dk - ext.get('KRW', D(0)) - led_sum
    adjusted = raw - explained
    account = {
        'krw': {'exchange_delta': str(dk), 'external': str(ext.get('KRW', D(0))), 'session_ledger_delta': str(led_sum),
                'raw_residual': str(raw), 'explained_rounding': str(explained), 'adjusted_residual': str(adjusted),
                'quantum': str(krw_quantum)},
        'coins': {}, 'base_holdings': {}, 'internal_allocations': [],
    }
    for cur in sorted(set(acc0) | set(acc1)):
        if cur != 'KRW':
            account['base_holdings'][cur] = {'amount': str(total(acc0, cur)), 'source': 'base.exchange_after'}
    pos_delta = {}
    for snap, sign in ((db0, -1), (db1, 1)):
        for p in snap.get('positions', []):
            if (p['session_kind'], p['session_id']) in registered:
                cur = p['coin_pair'].split('-', 1)[1]
                pos_delta[cur] = pos_delta.get(cur, D(0)) + sign * dec(p['size'])
    coin_ok = True
    for cur in sorted((set(acc0) | set(acc1) | set(coin_net) | set(pos_delta)) - {'KRW'}):
        de = total(acc1, cur) - total(acc0, cur) - ext.get(cur, D(0))
        cn, pd = coin_net.get(cur, D(0)), pos_delta.get(cur, D(0))
        ok = de == cn == pd
        coin_ok &= ok
        account['coins'][cur] = {'exchange_delta_minus_external': str(de), 'net_filled': str(cn),
                                 'open_position_delta': str(pd), 'match': ok}
    # 세션 장부: Δavailable = Σ주문 장부 증감 (새 세션의 기준 = 초기 배정, 내부 이전)
    led_ok = True
    for key in registered:
        s_now, s_base = s1.get(key), s0.get(key)
        if s_now is None:
            continue
        if s_base is None:
            start = dec(s_now['initial_capital'])
            account['internal_allocations'].append({'session': list(key), 'allocated': str(start), 'note': '구간 안 새 배정 — 거래소 유입 아님'})
        else:
            start = dec(s_base['available_krw'])
        got = dec(s_now['available_krw']) - start
        want = per_session.get(key, D(0))
        if got != want:
            led_ok = False
            res.anomaly('SESSION_LEDGER_MISMATCH', session=list(key), available_delta=str(got), order_ledger_delta=str(want))
    # 보유 뒷받침: 증감이 맞아도 거래소 실보유가 등록 세션의 내부 보유(열린 포지션 수량·세션 현금)를 덮지 못하면 이상이다.
    backing = {}
    reg_open = {}
    for p in p1:
        if (p['session_kind'], p['session_id']) in registered:
            cur = p['coin_pair'].split('-', 1)[1]
            reg_open[cur] = reg_open.get(cur, D(0)) + dec(p['size'])
    for cur, need in sorted(reg_open.items()):
        backing[cur] = {'exchange_total': str(total(acc1, cur)), 'registered_open': str(need), 'ok': total(acc1, cur) >= need}
    need_krw = sum((dec(s1[k]['available_krw']) for k in registered if k in s1), D(0))
    backing['KRW'] = {'exchange_total': str(total(acc1, 'KRW')), 'registered_available': str(need_krw), 'ok': total(acc1, 'KRW') >= need_krw}
    account['backing'] = backing
    if not all_final:
        res.hold('ORDER_EFFECT_PENDING', detail='구간 주문 중 반영 미확정 있음')
    if not res.holds:
        if abs(adjusted) > krw_quantum:
            res.anomaly('ACCOUNT_KRW_MISMATCH', adjusted_residual=str(adjusted), raw_residual=str(raw))
        if not coin_ok:
            res.anomaly('ACCOUNT_COIN_MISMATCH', detail={k: v for k, v in account['coins'].items() if not v['match']})
        for cur, b in backing.items():
            if not b['ok']:
                res.anomaly('ACCOUNT_KRW_UNBACKED' if cur == 'KRW' else 'ACCOUNT_COIN_UNBACKED', currency=cur, detail=b)
        account['verdict'] = '일치' if abs(adjusted) <= krw_quantum and coin_ok else '불일치'
    else:
        account['verdict'] = '보류(계산값은 참고)'
    overall = 'ANOMALY' if res.anomalies else ('HOLD' if res.holds else 'OK')
    return {'overall': overall, 'registry_valid': reg_ok, 'observation_stable': stable,
            'anomalies': res.anomalies, 'holds': res.holds, 'account': account}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--registry', required=True)
    ap.add_argument('--base', required=True)
    ap.add_argument('--now', required=True)
    ap.add_argument('--krw-quantum', default='0')
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    load = lambda p: json.load(open(p, encoding='utf-8'))
    out = reconcile(load(a.registry), load(a.base), load(a.now), dec(a.krw_quantum))
    print(json.dumps(out, ensure_ascii=False, indent=1, default=str))


if __name__ == '__main__':
    main()
