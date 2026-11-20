"""월 성적표 v4 후보(총괄 v1.203 §2-1·§3-1) — v3(dec-scorecard-v3-20261119/scorecard, 5ca20646…, 원문 보존) 사본에 값 상태만 더했다.
  입력 version scorecard-input-3. 거래의 각 매수/매도에 value_status('확정'|'미확정') 가 필수다. 시각(settled_at)과 값은 따로 전한다.
  - '미확정' 이면 qty/funds/fee 는 null 이어야 한다(최신 포착 숫자로 채워 넘기면 거절 — 임의의 숫자로 호환하지 않는다).
  - 매수 원가가 미확정이면 그 거래의 이 달 매도 실현·생애 손익·승패·왕복 종료는 미확정이다. 원가가 확인된 다른 거래의 부분 합은
    realized_known_part / wins_known_part 로 보존한다. 해당 거래를 조용히 빼거나 0 으로 만들지 않는다.
  - 값이 미확정인 매수·매도의 수수료·대금은 그 달에 속해도 확정 합에 넣지 않고 사유를 남긴다.
v3 설명(아래)은 그대로 유효하다.

월 성적표 v3(총괄 v1.191 §3) — v2(dec-scorecard-v2-20261118, 원문 보존)에 두 반례를 보완했다:
  ① 정산 시각 미상 거래의 수수료·매수 대금은 월 귀속을 모르므로 확정 합 대신 "알려진 부분 합 + 미확정"으로 낸다.
  ② 매도 정산 시각이 그 거래의 매수 정산 시각보다 앞서면 계약 위반(rc2)으로 거절한다. 시각 미상은 추정하지 않는다.
v2 설명(아래)은 그대로 유효하다.

월 성적표 v2(총괄 v1.190 §3). v1(dec-scorecard-20261118, 원문 보존)에서 네 반례 S1~S4 와 영향받은 경계를 보완했다.
파일 입력 → 파일 산출. 네트워크·DB 접속 없음.

사용: python -I scorecard.py --input <입력.json> --out <폴더>   → <폴더>/scorecard.json, scorecard.md
종료값: 0 = 산출(미확정 칸이 있어도 0), 2 = 입력 계약 위반(산출 없음).

v1 에서 유지
  - 월손익 = 기말 평가 − 기초 평가 − 기간 순입금. 하나라도 모르면 0 으로 채우지 않고 '미확정'과 이유.
  - CLOSING·가격 없는 보유는 미확정. 수수료는 손익에 이미 반영 — 표시만.
  - 실현손익은 주문 원천(매수 대금+수수료로 만든 평균가, 매도 대금−수수료)으로 다시 계산하고 저장값과 다르면 고르지 않고 불일치 표시.
  - 손실·거래0·중단 포함. 합계는 묶음 안 모든 세션이 확정일 때만.
v2 에서 바뀐 계약(입력 version scorecard-input-2)
  S1 미실현: 'unrealized_derived'(v1)를 없앴다. 기초/기말 미실현은 스냅숏 보유의 원가(cost_krw)가 있을 때만 계산하고, 없으면 미확인.
     월손익 − 기간 실현 = '기간 중 미실현 증감'으로 따로 이름 붙인다. 셋이 모두 확정이면 항등식(실현+증감=월손익)을 대조한다.
     왕복의 생애 손익(모든 매도)과 해당 월 실현(기간 안 매도)을 다른 칸에 둔다. 이월 왕복은 표시한다.
  S2 입출금: flows 의 각 항목은 kind(ALLOCATE/RECOVER)·양수 금액·시각이 필수이며 기간 [from,to) 밖이거나 세션 생성 전이면 계약 위반(rc2).
     flows 자체가 없으면(None) 미확정 — 0 으로 채우지 않는다.
  S3 계좌: 실자금(LIVE·DYNAMIC REAL)과 모의(PAPER·DYNAMIC PAPER)를 섞은 '전체' 합을 내지 않는다. 계좌 대조는 실자금 합과만 한다.
     묶음은 돈 종류 안에서 종류·모드·전략/주기·코드 버전별로 낸다.
  S4 낙폭: 최대 금액 낙폭과 최대 비율 낙폭을 각각 계산한다(서로 다른 구간일 수 있음). 입출금은 금액 낙폭에서는 누적 순입금을 빼고,
     비율 낙폭에서는 입출금이 직전 표본 바로 뒤에 일어났다고 보는 연쇄 지수로 뺀다(단순 출금이 손실로 세지지 않음).
     표본 시각 역순·중복은 계약 위반(rc2), 25시간 넘는 표본 간격은 결측 구간으로 표시하고 채우지 않는다.
  시각: 매수·매도 시각은 체결/정산 시각(settled_at)만 받는다. 주문 생성 시각(created_at)으로 대신하지 않는다 — v1 이름 'at' 은 거절.
     settled_at 이 null 이면 그 거래의 월 귀속은 미확정이고 세션 실현도 미확정이다.
  예약: 스냅숏의 pending_buys(필수, 빈 목록 가능)는 아직 포지션이 없는 매수 예약이다. 예약 금액은 가용 KRW 에서 이미 빠져 있으므로
     평가에 더한다. 금액이 없거나 일부 체결된 예약은 그 시점 평가를 미확정으로 둔다.
금액은 Decimal 로 계산하고 원 단위 반올림은 표시에서만 한다.
"""
import argparse, json, os, sys
from datetime import datetime, timedelta
from decimal import Decimal as D, InvalidOperation

UNK = '미확정'
NA = '미확인'
TOL = D('0.01')   # 저장 실현손익과 주문 원천 재계산의 표시 허용 차(원) — 넘으면 불일치로 표시(판정을 통과시키는 유예가 아님)
GAP = timedelta(hours=25)   # 일별 표본 간격이 이보다 길면 결측 구간
KINDS = {'LIVE', 'DYNAMIC', 'PAPER'}
FLOW_SIGN = {'ALLOCATE': 1, 'RECOVER': -1}
REAL, SIM = '실자금', '모의'


class Bad(ValueError):
    pass


def dec(v, path, allow_none=False):
    if v is None:
        if allow_none:
            return None
        raise Bad(f'{path}: 값 없음')
    if isinstance(v, bool) or not isinstance(v, (str, int)):
        raise Bad(f'{path}: 문자열/정수 숫자만(실수형 금지)')
    try:
        d = D(str(v))
    except InvalidOperation:
        raise Bad(f'{path}: 숫자 아님')
    if not d.is_finite():
        raise Bad(f'{path}: 유한수 아님')
    return d


def tstamp(v, path, allow_none=False):
    if v is None:
        if allow_none:
            return None
        raise Bad(f'{path}: 시각 없음')
    try:
        t = datetime.fromisoformat(str(v).replace('Z', '+00:00'))
    except ValueError:
        raise Bad(f'{path}: 시각 아님')
    if t.tzinfo is None:
        raise Bad(f'{path}: 시간대 없음')
    return t


def s(x):
    return None if x is None else str(x)


def won(x):
    return UNK if x is None else f'{x.quantize(D("1")):,}'


def pct(x):
    return None if x is None else s(x.quantize(D('0.01')))


def money_class(x, k):
    kind, mode = x.get('kind'), x.get('mode')
    if kind not in KINDS:
        raise Bad(f'{k}.kind: LIVE/DYNAMIC/PAPER 만(받은 값 {kind!r})')
    if mode not in ('REAL', 'PAPER'):
        raise Bad(f'{k}.mode: REAL/PAPER 필수(받은 값 {mode!r}) — 실자금/모의 구분 없이 합산하지 않는다')
    if kind == 'LIVE' and mode != 'REAL':
        raise Bad(f'{k}: LIVE 는 REAL 만')
    if kind == 'PAPER' and mode != 'PAPER':
        raise Bad(f'{k}: PAPER 는 PAPER 만')
    return REAL if mode == 'REAL' else SIM


# ── 평가 ──────────────────────────────────────────────────────
def snapshot(snap, path, why):
    """세션 시점 평가 = 가용 KRW + 매수 예약 + 보유 평가, 그리고 미실현(원가 있는 보유만). 모르면 None 과 이유."""
    if snap is None:
        why.append(f'{path} 없음')
        return None, None
    total = dec(snap.get('available_krw'), f'{path}.available_krw')
    ok, unreal, unreal_ok = True, D(0), True
    if 'pending_buys' not in snap or not isinstance(snap['pending_buys'], list):
        raise Bad(f'{path}.pending_buys: 목록 필수(없으면 빈 목록) — 매수 예약은 가용 KRW 에서 이미 빠져 있다')
    for i, pb in enumerate(snap['pending_buys']):
        filled = dec(pb.get('filled_qty', '0'), f'{path}.pending_buys[{i}].filled_qty')
        rk = dec(pb.get('reserved_krw'), f'{path}.pending_buys[{i}].reserved_krw', allow_none=True)
        if filled > 0:
            why.append(f"{path} 매수 예약 {pb.get('order_id')} 일부 체결 — 포지션·남은 예약 정산 전 미확정")
            ok = False
        elif rk is None:
            why.append(f"{path} 매수 예약 {pb.get('order_id')} 예약 금액 없음")
            ok = False
        else:
            total += rk
    for i, p in enumerate(snap.get('positions') or []):
        st = p.get('status')
        size = dec(p.get('size'), f'{path}.positions[{i}].size')
        cost = dec(p.get('cost_krw'), f'{path}.positions[{i}].cost_krw', allow_none=True)
        if st == 'CLOSING':
            why.append(f"{path} 포지션 {p.get('id')} CLOSING — 0원 아님, 매도 확정 전 미확정")
            ok = False
            continue
        if st != 'OPEN':
            raise Bad(f'{path}.positions[{i}].status: OPEN/CLOSING 만(받은 값 {st!r})')
        price = dec(p.get('price'), f'{path}.positions[{i}].price', allow_none=True)
        if price is None:
            why.append(f"{path} 포지션 {p.get('id')} {p.get('coin')} 평가 가격 없음")
            ok = False
            continue
        total += size * price
        if cost is None:
            unreal_ok = False
        else:
            unreal += size * price - cost
    if not ok:
        return None, None
    return total, (unreal if unreal_ok else None)


def flows_in(x, k, period, created):
    """기간 순입금. None 이면 미확정. 기간 밖·생성 전·종류/부호 오류는 계약 위반."""
    if x.get('flows') is None:
        return None, []
    out, total = [], D(0)
    for i, f in enumerate(x['flows']):
        p = f'{k}.flows[{i}]'
        kind = f.get('kind')
        if kind not in FLOW_SIGN:
            raise Bad(f'{p}.kind: ALLOCATE/RECOVER 만(받은 값 {kind!r})')
        amt = dec(f.get('amount_krw'), f'{p}.amount_krw')
        if amt <= 0:
            raise Bad(f'{p}.amount_krw: 양수만 — 방향은 kind 로 정한다')
        at = tstamp(f.get('at'), f'{p}.at')
        if not (period[0] <= at < period[1]):
            raise Bad(f'{p}.at {f.get("at")}: 기간 [from,to) 밖 입출금 — 이 달 성적표에 넣지 않는다(다른 달 입력으로)')
        if created is not None and at < created:
            raise Bad(f'{p}.at: 세션 생성({created.isoformat()}) 전 입출금')
        v = FLOW_SIGN[kind] * amt
        total += v
        out.append((at, v))
    return total, sorted(out)


def leg_values(x, p):
    """v4 — (qty, funds, fee) 또는 None(값 미확정). 미확정인데 숫자를 주면 거절."""
    st = x.get('value_status')
    if st not in ('확정', UNK):
        raise Bad(f"{p}.value_status: '확정'/'{UNK}' 필수(시각 미상과 값 미상을 따로 전한다)")
    if st == UNK:
        if any(x.get(k) is not None for k in ('qty', 'funds', 'fee')):
            raise Bad(f'{p}: 값 미확정인데 qty/funds/fee 숫자를 줌 — 최신 포착 값을 확정 비용으로 넘기지 않는다')
        return None
    return dec(x.get('qty'), f'{p}.qty'), dec(x.get('funds'), f'{p}.funds'), dec(x.get('fee'), f'{p}.fee')


def trade_metrics(trades, period, path):
    """기간 실현(주문 원천)·수수료·왕복(생애 손익)·저장값 대조. 정산 시각 없는 거래·값 미확정 거래가 있으면 실현 None."""
    realized, fees, buy_funds = D(0), D(0), D(0)
    realized_ok = True
    fees_why, buys_why, value_why = [], [], []   # v3 — 월 귀속 모름 / v4 — 값(원가·대금·수수료) 미확정
    rounds, open_rounds, mism, missing = [], [], [], []
    for i, t in enumerate(trades):
        b = t['buy']
        if 'at' in b or any('at' in sl for sl in t.get('sells') or []):
            raise Bad(f'{path}[{i}]: v1 이름 at — 체결/정산 시각 settled_at 으로 명시(주문 생성 시각으로 대신하지 않음)')
        bv = leg_values(b, f'{path}[{i}].buy')
        bat = tstamp(b.get('settled_at'), f'{path}[{i}].buy.settled_at', allow_none=True)
        avg = None
        if bv is not None:
            bq, bf, bfee = bv
            if bq <= 0:
                raise Bad(f'{path}[{i}].buy.qty ≤ 0')
            avg = (bf + bfee) / bq   # 평균가에 매수 수수료 포함(공통 경제 손익)
        else:
            value_why.append(f'거래{i} 매수 원가(수량·대금·수수료) 미확정 — 이 거래의 실현·승패·왕복 미확정')
        if bat is None:
            missing.append(f'거래{i} 매수 정산 시각 없음 — 월 귀속 미확정')
            fees_why.append(f'거래{i} 매수 수수료 월 귀속 모름(매수 정산 시각 없음)')
            buys_why.append(f'거래{i} 매수 대금 월 귀속 모름(매수 정산 시각 없음)')
        elif period[0] <= bat < period[1]:
            if bv is None:
                fees_why.append(f'거래{i} 이 달 매수 수수료 값 미확정')
                buys_why.append(f'거래{i} 이 달 매수 대금 값 미확정')
            else:
                fees += bfee
                buy_funds += bf
        sold, life, month_part, last_sell, sells_known = D(0), D(0), D(0), None, True
        qty_known, life_known = bv is not None, bv is not None
        for j, sl in enumerate(t.get('sells') or []):
            sat = tstamp(sl.get('settled_at'), f'{path}[{i}].sells[{j}].settled_at', allow_none=True)
            sv = leg_values(sl, f'{path}[{i}].sells[{j}]')
            r = None
            if sv is None:
                qty_known = life_known = False
                value_why.append(f'거래{i} 매도{j} 값 미확정')
            else:
                q, f, fee = sv
                sold += q
                if avg is not None:
                    r = (f - fee) - q * avg
                    life += r
                else:
                    life_known = False
            if sat is None:
                sells_known = False
                realized_ok = False
                missing.append(f'거래{i} 매도{j} 정산 시각 없음 — 이 달 실현 미확정')
                fees_why.append(f'거래{i} 매도{j} 수수료 월 귀속 모름(매도 정산 시각 없음)')
                continue
            if bat is not None and sat < bat:
                # v3 ② — 같은 거래의 매도 정산이 매수 정산보다 앞선다: 시각 모순. 왕복·보유시간을 세지 않고 거절한다.
                raise Bad(f'{path}[{i}].sells[{j}].settled_at {sat.isoformat()} < buy.settled_at {bat.isoformat()} — 매도 정산이 매수 정산보다 앞섬(시각 모순)')
            last_sell = sat if last_sell is None or sat > last_sell else last_sell
            if not (period[0] <= sat < period[1]):
                continue
            if sv is None:
                fees_why.append(f'거래{i} 매도{j} 이 달 수수료 값 미확정')
            else:
                fees += fee
            if r is None:
                realized_ok = False
                missing.append(f'거래{i} 매도{j} 이 달 실현 미확정(' + ('매도 값' if sv is None else '매수 원가') + ' 미확정)')
                continue
            realized += r
            month_part += r
            stored = dec(sl.get('stored_realized_pnl'), f'{path}[{i}].sells[{j}].stored_realized_pnl', allow_none=True)
            if stored is not None and abs(stored - r) > TOL:
                mism.append({'trade': i, 'sell': j, 'stored': s(stored), 'from_orders': s(r), 'diff': s(stored - r)})
        if qty_known and sold > bq:
            raise Bad(f'{path}[{i}]: 매도 수량 합 > 매수 수량')
        if sells_known and last_sell is not None and period[0] <= last_sell < period[1]:
            if qty_known and life_known and sold == bq:
                carry = bat is None or bat < period[0]
                rounds.append({'trade': i, 'lifetime_pnl': life, 'this_month_part': month_part, 'carry_in': carry,
                               'hours': None if bat is None else (last_sell - bat).total_seconds() / 3600})
            elif not (qty_known and sold < bq):
                open_rounds.append({'trade': i, 'why': '값 미확정 — 왕복 종료 여부·생애 손익·승패를 확정하지 않음'})
    holds = sorted(x['hours'] for x in rounds if x['hours'] is not None)
    med = None if not holds else (holds[len(holds) // 2] if len(holds) % 2 else (holds[len(holds) // 2 - 1] + holds[len(holds) // 2]) / 2)
    wins, losses = sum(x['lifetime_pnl'] > 0 for x in rounds), sum(x['lifetime_pnl'] < 0 for x in rounds)
    return {'realized_in_period': realized if realized_ok else None, 'realized_known_part': realized,
            'fees_paid': fees if not fees_why else None, 'fees_known_part': fees, 'fees_why': fees_why,
            'buy_funds': buy_funds if not buys_why else None, 'buy_funds_known_part': buy_funds, 'buys_why': buys_why,
            'missing_time': missing, 'value_unconfirmed': value_why,
            'round_trips_closed': None if open_rounds else len(rounds), 'round_trips_closed_known_part': len(rounds),
            'round_trips_unconfirmed': open_rounds,
            'wins': None if open_rounds else wins, 'losses': None if open_rounds else losses,
            'wins_known_part': wins, 'losses_known_part': losses,
            'holding_hours_median': None if med is None else round(med, 2),
            'round_trips': [{'trade': x['trade'], 'lifetime_pnl': s(x['lifetime_pnl']), 'this_month_part': s(x['this_month_part']),
                             'carry_in': x['carry_in']} for x in rounds],
            'stored_realized_mismatch': mism}


def drawdown(daily, flows, period, path):
    if not daily or len(daily) < 2:
        return {'max_amount_krw': None, 'max_pct': None, 'label': f'{NA}(일별 평가 표본 없음 — 복원하지 않음)'}
    pts = []
    for i, d in enumerate(daily):
        if 'date' in d:
            raise Bad(f'{path}[{i}]: v1 이름 date — 표본 시각 at(시간대 포함) 필수')
        at = tstamp(d.get('at'), f'{path}[{i}].at')
        if not (period[0] <= at <= period[1]):
            raise Bad(f'{path}[{i}].at: 기간 밖 표본')
        if pts and at <= pts[-1][0]:
            raise Bad(f'{path}[{i}].at: 시각 역순·중복 표본 — 정렬/중복 제거는 입력 쪽에서(도구가 고르지 않음)')
        pts.append((at, dec(d.get('equity'), f'{path}[{i}].equity')))
    gaps = [f'{a.isoformat()}→{b.isoformat()}' for (a, _), (b, _) in zip(pts, pts[1:]) if b - a > GAP]
    if flows is None:
        return {'max_amount_krw': None, 'max_pct': None, 'samples': len(pts), 'missing_intervals': gaps,
                'label': f'{UNK}(입출금 이력 없음 — 입출금과 손실을 구분할 수 없음)'}
    # 금액: 첫 표본 뒤 누적 순입금을 뺀 조정 평가. 비율: I_t = I_{t-1} × E_t / (E_{t-1} + F_t), F_t = (t-1, t] 입출금.
    adj_peak, max_amt, idx, idx_peak, max_pct, pct_ok = None, D(0), D(1), D(1), D(0), True
    cum = D(0)
    amt_window = pct_window = None
    peak_at = pk2_at = pts[0][0]
    for n, (at, e) in enumerate(pts):
        f_t = sum((v for fa, v in flows if (pts[n - 1][0] if n else period[0]) < fa <= at), D(0)) if n else D(0)
        cum += f_t
        a = e - cum
        if adj_peak is None or a > adj_peak:
            adj_peak, peak_at = a, at
        if a - adj_peak < max_amt:
            max_amt, amt_window = a - adj_peak, (peak_at, at)
        if n:
            base = pts[n - 1][1] + f_t
            if base <= 0:
                pct_ok = False
            else:
                idx = idx * e / base
        if idx > idx_peak:
            idx_peak, pk2_at = idx, at
        dd = (idx / idx_peak - 1) * 100
        if dd < max_pct:
            max_pct, pct_window = dd, (pk2_at, at)
    w = lambda x: None if x is None else [x[0].isoformat(), x[1].isoformat()]
    return {'max_amount_krw': s(max_amt), 'max_amount_window': w(amt_window),
            'max_pct': pct(max_pct) if pct_ok else None, 'max_pct_window': w(pct_window) if pct_ok else None,
            'pct_note': None if pct_ok else f'{NA}(직전 평가+입출금 ≤ 0 구간)',
            'samples': len(pts), 'missing_intervals': gaps,
            'flow_policy': '금액=누적 순입금 차감, 비율=입출금이 직전 표본 직후라고 본 연쇄 지수 — 단순 출금은 손실 아님',
            'label': '일별 표본 낙폭(장중 최대 낙폭 아님)' + (f' · 결측 구간 {len(gaps)}(채우지 않음)' if gaps else '')}


def session_card(x, period, bench):
    k = f"{x.get('kind')}#{x.get('id')}"
    mc = money_class(x, k)
    why = []
    created = tstamp(x.get('created_at'), f'{k}.created_at', allow_none=True)
    if x.get('started_in_period'):
        if created is None or not (period[0] <= created < period[1]):
            raise Bad(f'{k}: started_in_period 인데 created_at 이 없거나 기간 밖')
        if x.get('start') is not None:
            raise Bad(f'{k}: 기간 안 새 세션에 기초 스냅숏을 함께 줌 — 기초는 0, 배정은 순입금')
    elif created is not None and created >= period[0]:
        raise Bad(f'{k}: created_at 이 기간 안인데 started_in_period 아님')
    if x.get('started_in_period'):
        start, u_start = D(0), D(0)
    else:
        start, u_start = snapshot(x.get('start'), f'{k} 기초', why)
        if x.get('start') is None:
            why[-1] = f'{k} 기초 평가 없음(기간 전부터 운용)'
    end, u_end = snapshot(x.get('end'), f'{k} 기말', why)
    flows, flow_list = flows_in(x, k, period, created)
    if flows is None:
        why.append(f'{k} 순입금(배정·회수) 이력 없음')
    pnl = end - start - flows if None not in (start, end, flows) else None
    tm = trade_metrics(x.get('trades') or [], period, f'{k}.trades')
    why += tm['missing_time'] + tm['value_unconfirmed']
    realized = tm['realized_in_period']
    change = None if None in (pnl, realized) else pnl - realized
    flags = []
    identity = NA
    if None not in (change, u_start, u_end):
        gap = change - (u_end - u_start)
        identity = '일치' if abs(gap) <= TOL else f'불일치 {s(gap)}'
        if identity != '일치':
            flags.append('실현+미실현 증감 ≠ 월손익 — 입력 원가/평가/거래 누락 확인')
    prov = x.get('provenance') or {}
    hashes = prov.get('ruleset_hashes_seen') or ([prov['ruleset_hash']] if prov.get('ruleset_hash') else [])
    if len(set(hashes)) > 1:
        flags.append('구간 중 규칙 지문 변경 — 지문별 새 구간으로 분리해야 함(합산 금지)')
    if tm['stored_realized_mismatch']:
        flags.append('저장 실현손익 ≠ 주문 원천 재계산 — 어느 쪽도 고르지 않음')
    if any(r['carry_in'] for r in tm['round_trips']):
        flags.append('이월 왕복 포함 — 생애 손익과 이 달 실현을 따로 봄')
    regimes = x.get('market_regime')
    base_cap = start if start not in (None, D(0)) else (flows if flows is not None and flows > 0 else None)
    btc = None
    if bench.get('btc_start') is not None and bench.get('btc_end') is not None and base_cap is not None:
        btc = base_cap * (bench['btc_end'] / bench['btc_start'] - 1)
    return {
        'session': k, 'money': mc, 'kind': x['kind'], 'mode': x['mode'], 'strategy': x.get('strategy_type') or NA,
        'timeframe': x.get('timeframe') or NA, 'status_end': x.get('status_end') or NA,
        'trade_state': '거래0' if not x.get('trades') else (f"이 달 종료 왕복 {tm['round_trips_closed']}" if tm['round_trips_closed'] is not None
                                                            else f"이 달 종료 왕복 {UNK}(확정 {tm['round_trips_closed_known_part']})"),
        'start_equity': s(start), 'end_equity': s(end), 'net_flows': s(flows),
        'month_pnl': s(pnl), 'month_pnl_status': '확정' if pnl is not None else UNK, 'unconfirmed_reasons': why,
        'month_return_pct': None if (pnl is None or base_cap in (None, D(0))) else pct(pnl / base_cap * 100),
        'realized_in_period': s(realized), 'realized_known_part': s(tm['realized_known_part']),
        'unrealized_start': s(u_start), 'unrealized_end': s(u_end),
        'unrealized_change': s(change), 'unrealized_change_note': '월손익 − 기간 실현 = 기간 중 미실현 증감(기말 미실현 아님)',
        'identity_check': identity,
        'fees_paid': s(tm['fees_paid']), 'fees_known_part': s(tm['fees_known_part']),
        'fees_status': '확정' if tm['fees_paid'] is not None else UNK, 'fees_unconfirmed_reasons': tm['fees_why'],
        'fee_note': '손익에 이미 반영(매도 수수료는 대금에서, 매수 수수료는 평균가에) — 다시 빼지 않음',
        'buy_funds': s(tm['buy_funds']), 'buy_funds_known_part': s(tm['buy_funds_known_part']),
        'buy_funds_unconfirmed_reasons': tm['buys_why'], 'round_trips_closed': tm['round_trips_closed'], 'wins': tm['wins'], 'losses': tm['losses'],
        'round_trips_closed_known_part': tm['round_trips_closed_known_part'], 'wins_known_part': tm['wins_known_part'],
        'losses_known_part': tm['losses_known_part'], 'round_trips_unconfirmed': tm['round_trips_unconfirmed'],
        'value_unconfirmed': tm['value_unconfirmed'],
        'win_loss_basis': '이 달 종료 왕복의 생애 손익',
        'holding_hours_median': tm['holding_hours_median'], 'round_trips': tm['round_trips'],
        'stored_realized_mismatch': tm['stored_realized_mismatch'],
        'drawdown': drawdown(x.get('daily_equity'), flow_list if flows is not None else None, period, f'{k}.daily_equity'),
        'provenance': {'code_sha': prov.get('code_sha') or NA, 'code_sha_source': prov.get('code_sha_source') or NA,
                       'ruleset_hashes': hashes or [NA], 'registry_ref': prov.get('registry_ref') or NA},
        'market_regime': regimes if regimes else NA,
        'benchmark': {'cash_pnl': '0', 'btc_hold_pnl': s(btc) if btc is not None else NA},
        'flags': flags,
    }


def account_card(a, period):
    if a is None:
        return {'month_pnl': None, 'status': f'{NA}(계좌 기초/기말 없음)'}
    why = []

    def value(snap, name):
        if snap is None:
            why.append(f'계좌 {name} 없음')
            return None
        t = dec(snap.get('krw'), f'account.{name}.krw')
        for c, q in (snap.get('coins') or {}).items():
            p = (snap.get('prices') or {}).get(c)
            if p is None:
                why.append(f'계좌 {name} {c} 가격 없음')
                return None
            t += dec(q, f'account.{name}.coins.{c}') * dec(p, f'account.{name}.prices.{c}')
        return t
    st, en = value(a.get('start'), '기초'), value(a.get('end'), '기말')
    nd = None
    if a.get('deposits') is not None:
        nd = D(0)
        for i, d in enumerate(a['deposits']):
            at = tstamp(d.get('at'), f'account.deposits[{i}].at')
            if not (period[0] <= at < period[1]):
                raise Bad(f'account.deposits[{i}].at: 기간 밖 입출금')
            nd += dec(d.get('amount_krw'), f'account.deposits[{i}].amount_krw')   # 입금 +, 출금 −(거래소 기록 부호)
    elif 'net_deposits_krw' in a:
        raise Bad('account.net_deposits_krw: v2 는 시각이 있는 deposits 목록만(월 밖 입출금 검사)')
    if nd is None:
        why.append('계좌 입출금 이력 없음')
    pnl = en - st - nd if None not in (st, en, nd) else None
    return {'scope': '실물 계좌 — 실자금 세션하고만 대조', 'start': s(st), 'end': s(en), 'net_deposits': s(nd), 'month_pnl': s(pnl),
            'status': '확정' if pnl is not None else UNK, 'reasons': why}


def build(inp):
    if not isinstance(inp, dict) or inp.get('version') != 'scorecard-input-3':
        raise Bad('version scorecard-input-3 아님')
    per = inp['period']
    period = (tstamp(per['from'], 'period.from'), tstamp(per['to'], 'period.to'))
    if not period[0] < period[1]:
        raise Bad('period.from ≥ period.to')
    b = inp.get('benchmarks') or {}
    bench = {'btc_start': dec(b.get('btc_price_start'), 'benchmarks.btc_price_start', True),
             'btc_end': dec(b.get('btc_price_end'), 'benchmarks.btc_price_end', True)}
    seen = set()
    cards = []
    for x in inp['sessions']:
        key = (x.get('kind'), x.get('id'))
        if key in seen:
            raise Bad(f'세션 중복 {key}')
        seen.add(key)
        cards.append(session_card(x, period, bench))
    groups = {}
    for c in cards:
        m = c['money']
        for gk in (('money', m), ('kind', f"{m}|{c['kind']}"), ('mode', f"{m}|{c['mode']}"),
                   ('strategy_timeframe', f"{m}|{c['strategy']}/{c['timeframe']}"),
                   ('code_version', f"{m}|{c['provenance']['code_sha']}")):
            g = groups.setdefault(gk, {'sessions': [], 'known_sum': D(0), 'unconfirmed': []})
            g['sessions'].append(c['session'])
            if c['month_pnl'] is None:
                g['unconfirmed'].append(c['session'])
            else:
                g['known_sum'] += D(c['month_pnl'])
    order = {'money': 0, 'kind': 1, 'mode': 2, 'strategy_timeframe': 3, 'code_version': 4}
    agg = []
    for (gt, name), g in sorted(groups.items(), key=lambda kv: (order[kv[0][0]], kv[0][1])):
        agg.append({'group': gt, 'name': name, 'sessions': g['sessions'],
                    'month_pnl': s(g['known_sum']) if not g['unconfirmed'] else None,
                    'status': '확정' if not g['unconfirmed'] else UNK,
                    'known_part_sum': s(g['known_sum']), 'unconfirmed_sessions': g['unconfirmed'],
                    'note': None if not g['unconfirmed'] else '확정 부분 합은 전체 손익이 아님'})
    acc = account_card(inp.get('account'), period)
    real = next((a for a in agg if a['group'] == 'money' and a['name'] == REAL), None)
    gap = None
    if acc.get('month_pnl') is not None and real is not None and real['month_pnl'] is not None:
        gap = s(D(acc['month_pnl']) - D(real['month_pnl']))
    unconf = [c['session'] for c in cards if c['month_pnl'] is None]
    return {'version': 'scorecard-4', 'period': {'from': per['from'], 'to': per['to']},
            'sessions': cards, 'aggregates': agg, 'account': acc,
            'account_minus_real_sessions': gap if gap is not None else f'{NA}(계좌 또는 실자금 세션 합계 미확정·없음)',
            'included': {'sessions': len(cards), 'real': sum(c['money'] == REAL for c in cards), 'paper': sum(c['money'] == SIM for c in cards),
                         'zero_trade': sum(c['trade_state'] == '거래0' for c in cards),
                         'loss': sum(1 for c in cards if c['month_pnl'] is not None and D(c['month_pnl']) < 0),
                         'stopped': sum(c['status_end'] == 'STOPPED' for c in cards), 'unconfirmed': len(unconf)},
            'rules': ['월손익=기말−기초−기간 순입금', '실자금·모의 합산 금지, 계좌 대조는 실자금만', '월 밖 입출금은 계약 위반',
                      'CLOSING·가격 없음·예약 금액 없음은 미확정', '체결/정산 시각만 월 귀속(생성 시각 아님)',
                      '수수료는 손익에 이미 반영 — 표시만', '미실현 증감≠기말 미실현', '최대 금액·비율 낙폭 각각',
                      '손실·거래0·중단 포함', '없는 출처는 미확인', '값 미확정 거래는 실현·승패·왕복 미확정(알려진 부분 합 별도)']}


def render_md(r):
    i = r['included']
    L = [f"# 월 성적표 v4 {r['period']['from']} ~ {r['period']['to']}", '',
         f"포함 세션 {i['sessions']}(실자금 {i['real']}·모의 {i['paper']} / 거래0 {i['zero_trade']}·손실 {i['loss']}·중단 {i['stopped']}·미확정 {i['unconfirmed']})", '',
         '| 세션 | 돈 | 전략/주기 | 거래 | 월손익 | 이 달 실현 | 미실현 기초→기말 | 미실현 증감 | 수수료(반영됨) | 최대 낙폭 금액/비율 | 코드 | 표시 |',
         '|---|---|---|---|---|---|---|---|---|---|---|---|']
    for c in r['sessions']:
        dd = c['drawdown']
        ddt = dd['label'] if dd['max_amount_krw'] is None else f"{won(D(dd['max_amount_krw']))} / {dd['max_pct'] or NA}% {dd['label']}"
        dv = lambda v: NA if v is None else won(D(v))
        L.append(f"| {c['session']} | {c['money']} | {c['strategy']}/{c['timeframe']} | {c['trade_state']} | "
                 f"{won(None if c['month_pnl'] is None else D(c['month_pnl']))} | {won(None if c['realized_in_period'] is None else D(c['realized_in_period']))} | "
                 f"{dv(c['unrealized_start'])}→{dv(c['unrealized_end'])} | {won(None if c['unrealized_change'] is None else D(c['unrealized_change']))} | "
                 f"{won(D(c['fees_paid'])) if c['fees_paid'] is not None else UNK + '(알려진 ' + won(D(c['fees_known_part'])) + ')'} | {ddt} | {c['provenance']['code_sha']} | {'; '.join(c['flags'] + c['unconfirmed_reasons']) or '-'} |")
    L += ['', '| 묶음 | 이름 | 월손익 | 확정 부분 합 | 미확정 세션 |', '|---|---|---|---|---|']
    for a in r['aggregates']:
        L.append(f"| {a['group']} | {a['name']} | {won(None if a['month_pnl'] is None else D(a['month_pnl']))} | {won(D(a['known_part_sum']))} | "
                 f"{','.join(a['unconfirmed_sessions']) or '-'} |")
    acc = r['account']
    L += ['', f"실물 계좌: 월손익 {won(None if acc.get('month_pnl') is None else D(acc['month_pnl']))} ({acc['status']}) · 계좌−실자금 세션 합 {r['account_minus_real_sessions']}",
          '', '규칙: ' + ' / '.join(r['rules'])]
    return '\n'.join(L) + '\n'


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--input', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', newline='\n')
    try:
        inp = json.loads(open(a.input, 'rb').read().decode('utf-8'), parse_float=lambda x: (_ for _ in ()).throw(Bad(f'실수형 {x}')))
        r = build(inp)
    except (Bad, KeyError, TypeError, ValueError) as e:
        print(json.dumps({'error': type(e).__name__, 'message': str(e)}, ensure_ascii=False))
        return 2
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, 'scorecard.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(r, f, ensure_ascii=False, indent=1)
    with open(os.path.join(a.out, 'scorecard.md'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(render_md(r))
    print(json.dumps({'ok': True, 'sessions': r['included']}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
