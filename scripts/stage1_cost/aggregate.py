"""1단계 체결 비용 집계 — 선행 조건 2 (LIVE_ENTRY_PREREG.md §4·§7-2, 2026-10-19).

입력: export.sql 로 운영자가 읽기 전용으로 받은 CSV(대상 포지션 × 주문, 주문이 없는 포지션도 한 행). 출력: 판정 보고(텍스트).
판정 규칙은 사전 등록 문서 그대로다.

  왕복 실측 비용(%) = 불리(진입) + 불리(청산) + 0.10        (수수료 편당 0.05% × 2, 고정)
  ① 왕복별 비용 → ② 코인별 단순평균 → ③ 판정 가능 코인(왕복 ≥ 3)의 코인 동일가중 평균   ← 판정은 ③ 으로만
  판정 가능 ≥ 3건 · ⚪ 판정 불가 1~2건 · ⚪ 미관측 0건 — ⚪ 는 ③ 에서 제외(0 으로 채우거나 평균으로 대체하지 않는다)
  정상 종료 = 판정 가능 코인 ≥ 8 / 22 AND 총 왕복 ≥ 30, 기간 상한 60일(미달이면 판정 불가 → 2단계 진입 불가)
  ③ ≤ 0.30 가정 유효 · 0.30 < ③ ≤ 0.40 §4(나) 재계산 · ③ > 0.40 가정 깨짐 · 코인별 ③ > 0.60 → 그 코인 제외
  분포·절대 편차·코인별 표본 수는 보고만 한다.

정의 (리뷰 2026-10-19 판정):
  D1 판정 대상은 사전 고정 22코인뿐(총 왕복 30 도 그 안에서). 밖의 코인은 따로 센다.                         — 동의
  D2 한 방향의 비용은 **금액 기준**이다(보완):                                                                  — 보완 반영
       BUY  = (Σ체결대금 − Σ(신호가×체결수량)) / Σ(신호가×체결수량) × 100
       SELL = (Σ(신호가×체결수량) − Σ체결대금) / Σ(신호가×체결수량) × 100
     신호가가 주문마다 다르면 주문별 슬리피지의 수량 가중 평균과 값이 다르다 — 이것은 계산 정의의 보완이다.
  D3 측정할 수 없는 왕복은 '측정 불가' 로 따로 센다(유효 표본 수와 구분). 대상 포지션은 추출에서 먼저 보존한다 — 주문 없음·한쪽 체결 없음·
     체결량·신호가·대금 누락·미결 상태의 체결·매수/매도 수량 불일치.                                                   — 동의(추출 보완)
  D4 '그 크기에서의 통과'(§4): 등록된 진입 배정액(position.entry_allocation_krw)이 --size 와 같은 왕복만 판정.       — 동의
  D5 페이퍼 가정 체결(fill_price_source 가 있는 주문)이 섞인 왕복은 제외(따로 센다).                            — 동의
  D6 코인별 > 0.60 제외 표시 코인도 ③ 평균에는 남긴다(사후 제거 금지).                                          — 동의

집계 기간(리뷰 2026-10-19): 단계 시작(--start) **이후 진입**하고 기간 끝 **이전 청산**한 왕복만 판정한다.
  기간 끝 = min(기준 시각 --as-of, 단계 종료 시각 --end, 시작 + 60일). 60일 뒤의 거래로 조건을 채워 '정상 종료' 가 되지 않는다.
"""
import argparse
import csv
import sys
from collections import OrderedDict
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP

UNIVERSE = ('IOTA WAVES CRO ONG SC POLYX NEAR WAXP BCH CVC POWR T ANKR DKA GLM HIVE PUNDIX ELF BLAST JUP G INJ').split()
FEE_ROUND_TRIP = Decimal('0.10')
MIN_JUDGEABLE = 3
MIN_COINS = 8
MIN_TRIPS = 30
PERIOD_CAP_DAYS = 60
BAND_VALID = Decimal('0.30')
BAND_RECALC = Decimal('0.40')
COIN_EXCLUDE = Decimal('0.60')
SETTLED_STATES = ('FILLED', 'CANCELLED')
QTY = Decimal('0.00000001')
Q = Decimal('0.0001')


def d(v):
    if v is None:
        return None
    v = str(v).strip()
    return Decimal(v) if v not in ('', 'NULL', 'null', 'None') else None


def parse_time(v):
    v = (v or '').strip()
    if not v:
        return None
    v = v.replace(' ', 'T', 1)
    if 'T' in v and v[-3] in '+-' and v[-2:].isdigit() and ':' not in v[-6:]:
        v += ':00'   # PostgreSQL 의 '+00' · '+09'
    t = datetime.fromisoformat(v)
    return t if t.tzinfo else t.replace(tzinfo=timezone.utc)


def side_cost(side, signal_notional, funds):
    """금액 기준 불리 비용(%) — D2. 유리한 체결은 음수(비용을 깎는다)."""
    if side == 'BUY':
        return (funds - signal_notional) / signal_notional * 100
    return (signal_notional - funds) / signal_notional * 100


class Trip:
    def __init__(self, position_id, coin, size, opened_at, closed_at):
        self.position_id = position_id
        self.coin = coin
        self.size = size
        self.opened_at = opened_at
        self.closed_at = closed_at
        self.fills = {'BUY': [], 'SELL': []}   # (신호가, 체결 수량, 체결 대금)
        self.order_count = 0
        self.problems = []
        self.paper = []

    def add(self, order_id, side, state, signal, qty, funds, paper_source):
        if not order_id:
            return   # 주문 없는 포지션의 빈 행
        self.order_count += 1
        state = (state or '').upper()
        if paper_source:
            self.paper.append(paper_source)
            return
        if side not in ('BUY', 'SELL'):
            self.problems.append(f'주문 {order_id} 방향 불명({side})')
            return
        if qty is None and state in SETTLED_STATES + ('PARTIAL_FILLED',):
            self.problems.append(f'주문 {order_id} {side} {state} 체결량 누락')
            return
        if qty is None or qty <= 0:
            return   # 체결 없는 주문(실패·철회·체결 없는 취소) — 비용과 무관
        if state not in SETTLED_STATES:
            self.problems.append(f'주문 {order_id} {side} 미결 상태({state})의 체결 {qty}')
            return
        if signal is None or signal <= 0:
            self.problems.append(f'주문 {order_id} {side} 신호가 없음')
            return
        if funds is None or funds <= 0:
            self.problems.append(f'주문 {order_id} {side} 체결 대금 없음')
            return
        self.fills[side].append((signal, qty, funds))

    def finish(self):
        """주문을 다 읽은 뒤 왕복 수준의 측정 불가 사유."""
        if self.order_count == 0:
            self.problems.append('주문 없음')
            return
        for side in ('BUY', 'SELL'):
            if not self.fills[side] and not any(p.startswith('주문') and f' {side} ' in p for p in self.problems):
                self.problems.append(f'{side} 체결 없음')
        if not self.problems and self.fills['BUY'] and self.fills['SELL']:
            bq = sum(q for _, q, _ in self.fills['BUY']).quantize(QTY)
            sq = sum(q for _, q, _ in self.fills['SELL']).quantize(QTY)
            if bq != sq:
                self.problems.append(f'매수 체결 {bq} ≠ 매도 체결 {sq}')

    def measurable(self):
        return not self.paper and not self.problems

    def side_cost(self, side):
        fills = self.fills[side]
        notional = sum(s * q for s, q, _ in fills)
        funds = sum(f for _, _, f in fills)
        return side_cost(side, notional, funds)

    def cost(self):
        return self.side_cost('BUY') + self.side_cost('SELL') + FEE_ROUND_TRIP

    def abs_dev(self):
        devs = [abs((f / q - s) / s * 100) for side in ('BUY', 'SELL') for s, q, f in self.fills[side]]
        return sum(devs) / len(devs)


def load_trips(rows):
    trips = OrderedDict()
    for r in rows:
        pid = str(r['position_id']).strip()
        t = trips.get(pid)
        if t is None:
            t = trips[pid] = Trip(pid, r['coin_pair'].replace('KRW-', ''), d(r.get('entry_allocation_krw')),
                                  parse_time(r.get('opened_at')), parse_time(r.get('closed_at')))
        t.add(str(r.get('order_id') or '').strip(), (r.get('side') or '').strip().upper(), r.get('state'), d(r.get('signal_price')),
              d(r.get('filled_quantity')), d(r.get('funds')), (r.get('fill_price_source') or '').strip())
    for t in trips.values():
        t.finish()
    return list(trips.values())


def percentile(sorted_vals, p):
    if not sorted_vals:
        return None
    k = (len(sorted_vals) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * Decimal(str(k - lo))


def aggregate(trips, size, start, as_of, end=None):
    """판정 — 사전 등록 규칙. 반환: dict(보고에 쓰는 모든 값)."""
    cap = start + timedelta(days=PERIOD_CAP_DAYS)
    window_end = min(x for x in (as_of, end, cap) if x is not None)
    out = {'size': size, 'start': start, 'as_of': as_of, 'end': end, 'window_end': window_end, 'opened_before_start': 0,
           'closed_after_window': 0, 'not_closed': 0, 'outside_universe': 0, 'other_size': 0, 'paper': 0, 'unmeasurable': []}
    judged = []
    for t in trips:
        if t.closed_at is None:
            out['not_closed'] += 1
            continue
        if t.opened_at is None or t.opened_at < start:
            out['opened_before_start'] += 1   # 단계 시작 전 진입 — 1단계 왕복이 아니다
            continue
        if t.closed_at > window_end:
            out['closed_after_window'] += 1   # 기간 끝(60일·단계 종료·기준 시각) 뒤 청산 — 이 판정에 넣지 않는다
            continue
        if t.coin not in UNIVERSE:
            out['outside_universe'] += 1
            continue
        if t.size is None or t.size != size:
            out['other_size'] += 1
            continue
        if t.paper:
            out['paper'] += 1
            continue
        if not t.measurable():
            out['unmeasurable'].append((t.position_id, t.coin, t.problems))
            continue
        judged.append(t)

    per_coin = OrderedDict((c, []) for c in UNIVERSE)
    for t in judged:
        per_coin[t.coin].append(t.cost())
    coins = OrderedDict()
    for c, costs in per_coin.items():
        n = len(costs)
        mean = sum(costs) / n if n else None
        if n >= MIN_JUDGEABLE:
            cls = '판정 가능 — 제외(> 0.60%)' if mean > COIN_EXCLUDE else '판정 가능 — 통과'
        elif n >= 1:
            cls = '⚪ 판정 불가(1~2건)'
        else:
            cls = '⚪ 미관측'
        coins[c] = {'n': n, 'mean': mean, 'class': cls}

    judgeable = [c for c, v in coins.items() if v['n'] >= MIN_JUDGEABLE]
    total = len(judged)
    value = sum(coins[c]['mean'] for c in judgeable) / len(judgeable) if judgeable else None

    capped = as_of >= cap
    met = len(judgeable) >= MIN_COINS and total >= MIN_TRIPS
    if met:
        status = '정상 종료 조건 충족 — 판정'
    elif capped:
        status = '🔴 60일 상한 도달 · 조건 미달 — 1단계 판정 불가 → 2단계 진입 불가'
    elif end is not None and as_of >= end:
        status = '🔴 단계 종료 시각 도달 · 조건 미달 — 판정 불가'
    else:
        status = '진행 중 — 판정 아님(조건 미달, 60일 상한 전)'

    if value is None:
        band = '판정값 없음(판정 가능 코인 0)'
    elif value <= BAND_VALID:
        band = '≤ 0.30% — 가정 유효(검증 §4(나) 결과를 그대로 쓴다)'
    elif value <= BAND_RECALC:
        band = '0.30% ~ 0.40% — 그 값으로 검증 §4(나) 거래당 순수익률을 재계산해 ≥ 0.10% 를 다시 요구(이 스크립트 범위 밖)'
    else:
        band = '🔴 > 0.40% — 가정이 깨졌다: 운용 중단, 검증 §4(나) 전면 재판정'

    costs_sorted = sorted(t.cost() for t in judged)
    out.update({
        'judged': judged, 'coins': coins, 'judgeable': judgeable, 'total': total, 'value': value,
        'status': status, 'band': band if met else '(판정 아님) ' + band, 'met': met, 'capped': capped,
        'median': percentile(costs_sorted, 0.5), 'p90': percentile(costs_sorted, 0.9), 'max': costs_sorted[-1] if costs_sorted else None,
        'abs_dev': (sum(t.abs_dev() for t in judged) / len(judged)) if judged else None,
    })
    return out


def fmt(v):
    return '—' if v is None else str(v.quantize(Q, rounding=ROUND_HALF_UP)) + '%'


def report(r):
    lines = [
        '1단계 체결 비용 집계 (LIVE_ENTRY_PREREG.md §4) — 판정 규칙은 사전 등록 그대로',
        f'단계 시작 {r["start"].isoformat()} · 기준 시각 {r["as_of"].isoformat()}'
        + (f' · 단계 종료 {r["end"].isoformat()}' if r['end'] else '')
        + f' · 집계 기간 끝 {r["window_end"].isoformat()} (60일 상한·단계 종료·기준 시각 중 이른 것) · 판정 크기 {r["size"]} KRW',
        '',
        f'상태: {r["status"]}',
        f'③ 판정 가능 {len(r["judgeable"])}코인의 동일가중 평균: {fmt(r["value"])}   (22코인 전체의 평균이 아니다)',
        f'판정 구간: {r["band"]}',
        f'유효 비용 표본: 22코인 안 왕복 {r["total"]}건(요구 ≥ {MIN_TRIPS}) · 판정 가능 코인 {len(r["judgeable"])} / 22(요구 ≥ {MIN_COINS})',
        f'🔴 측정 불가: {len(r["unmeasurable"])}건 (유효 표본에 넣지 않는다 — 아래 목록)',
        f'따로 센 것: 시작 전 진입 {r["opened_before_start"]} · 기간 끝 뒤 청산 {r["closed_after_window"]} · 미청산 {r["not_closed"]} · '
        f'22코인 밖 {r["outside_universe"]} · 다른 진입 배정액·불명 {r["other_size"]} · 페이퍼 가정 체결 {r["paper"]}',
        '',
        '코인별 (사전 고정 순서) — n · 평균 왕복 비용 · 분류',
    ]
    for c, v in r['coins'].items():
        lines.append(f'  {c:7s} n={v["n"]:<3d} {fmt(v["mean"]):>10s}  {v["class"]}')
    lines += [
        '',
        f'보고만(판정에 쓰지 않음): 거래별 중앙값 {fmt(r["median"])} · 상위 10% 경계 {fmt(r["p90"])} · 최댓값 {fmt(r["max"])} · 평균 절대 편차 {fmt(r["abs_dev"])}',
    ]
    if r['unmeasurable']:
        lines.append('측정 불가 왕복:')
        for pid, coin, probs in r['unmeasurable']:
            lines.append(f'  포지션 {pid} {coin}: ' + '; '.join(probs))
    return '\n'.join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('csv', help='export.sql 로 받은 CSV')
    ap.add_argument('--start', required=True, help='1단계 시작 시각(ISO, 예: 2026-11-01T00:00:00+09:00)')
    ap.add_argument('--as-of', help='판정 기준 시각(ISO, 기본: 지금)')
    ap.add_argument('--end', help='단계 종료 시각(ISO, 있으면 집계 기간 끝이 된다)')
    ap.add_argument('--size', default='20000', help='판정 크기 = 등록된 진입 배정액 KRW(기본 20000 — 1단계)')
    a = ap.parse_args(argv)
    start = parse_time(a.start)
    as_of = parse_time(a.as_of) if a.as_of else datetime.now(timezone.utc)
    end = parse_time(a.end) if a.end else None
    with open(a.csv, encoding='utf-8-sig', newline='') as f:
        rows = list(csv.DictReader(f))
    print(report(aggregate(load_trips(rows), Decimal(a.size), start, as_of, end)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
