"""aggregate.py 자체 시험 — 사전 등록 규칙(LIVE_ENTRY_PREREG.md §4·§7-2)과 리뷰 판정(2026-10-19)을 합성 자료로 고정한다.
실행: python -m unittest -v test_aggregate"""
import csv
import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from decimal import Decimal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import aggregate as A  # noqa: E402

START = datetime(2026, 11, 1, tzinfo=timezone.utc)
SIZE = Decimal('20000')
FIELDS = ['position_id', 'session_kind', 'session_id', 'coin_pair', 'entry_allocation_krw', 'opened_at', 'closed_at',
          'order_id', 'side', 'state', 'signal_price', 'filled_quantity', 'funds', 'fill_price_source', 'filled_at']


class Rows:
    """합성 행 — 왕복 하나 = 매수 1 + 매도 1(기본). 진입은 청산 1시간 전."""

    def __init__(self):
        self.rows = []
        self.pid = 0
        self.oid = 0

    def order(self, pid, base, side, signal, qty, funds, state='FILLED', source=''):
        self.oid += 1
        self.rows.append({**base, 'order_id': self.oid, 'side': side, 'state': state, 'signal_price': signal,
                          'filled_quantity': qty, 'funds': funds, 'fill_price_source': source, 'filled_at': base['closed_at']})

    def trip(self, coin, buy_slip='0', sell_slip='0', size='20000', day=1, signal='100', qty='10', source='',
             opened=None, closed=None):
        """buy_slip·sell_slip: (체결가 − 신호가)/신호가 × 100 (부호 있음)."""
        self.pid += 1
        c = closed or (START + timedelta(days=day))
        o = opened or (c - timedelta(hours=1))
        base = {'position_id': self.pid, 'session_kind': 'LIVE', 'session_id': 1, 'coin_pair': 'KRW-' + coin,
                'entry_allocation_krw': size, 'opened_at': o.isoformat(), 'closed_at': c.isoformat()}
        sig, q = Decimal(signal), Decimal(qty)
        for side, slip in (('BUY', buy_slip), ('SELL', sell_slip)):
            fill = sig * (1 + Decimal(slip) / 100)
            self.order(self.pid, base, side, str(sig), str(q), str(fill * q), source=source)
        return self.pid, base

    def run(self, days=30, end=None):
        return A.aggregate(A.load_trips(self.rows), SIZE, START, START + timedelta(days=days), end)


def cost_of(cost_pct):
    """왕복 비용이 cost_pct 가 되게 하는 (매수 불리, 매도 불리) — 수수료 0.10 을 빼고 매수 쪽에 몰아준다."""
    return str(Decimal(cost_pct) - Decimal('0.10')), '0'


class SideCost(unittest.TestCase):
    def test_buy_expensive_and_sell_cheap_are_costs(self):
        self.assertEqual(A.side_cost('BUY', Decimal('1000'), Decimal('1002')), Decimal('0.2'))
        self.assertEqual(A.side_cost('SELL', Decimal('1000'), Decimal('998')), Decimal('0.2'))

    def test_favourable_fill_reduces_cost(self):
        r = Rows()
        r.trip('IOTA', buy_slip='-0.05', sell_slip='0.05')   # 싸게 사고 비싸게 팔았다 — 둘 다 유리
        self.assertEqual(A.load_trips(r.rows)[0].cost(), Decimal('0.10') - Decimal('0.05') - Decimal('0.05'))

    def test_round_trip_adds_fixed_fee(self):
        r = Rows()
        r.trip('IOTA', buy_slip='0.1', sell_slip='-0.05')
        self.assertEqual(A.load_trips(r.rows)[0].cost(), Decimal('0.1') + Decimal('0.05') + Decimal('0.10'))

    def test_low_price_coin_keeps_precision(self):
        r = Rows()
        r.trip('SC', buy_slip='0.3', signal='0.01234567', qty='1000000')
        self.assertEqual(A.load_trips(r.rows)[0].side_cost('BUY').quantize(Decimal('0.000001')), Decimal('0.300000'))

    def test_amount_based_with_different_signals_D2(self):
        """신호가 100 에 10 개(0.2% 비싸게) + 신호가 200 에 10 개(0.5% 비싸게) — 금액 기준 (3012 − 3000)/3000 = 0.4%.
        주문별 슬리피지의 수량 가중 평균이면 (0.2 + 0.5)/2 = 0.35% — 다르다."""
        r = Rows()
        pid, base = r.trip('IOTA')
        r.rows = [x for x in r.rows if x['side'] == 'SELL']   # 매수를 두 주문으로 다시 만든다
        r.order(pid, base, 'BUY', '100', '10', '1002')
        r.order(pid, base, 'BUY', '200', '10', '2010')
        for x in r.rows:
            if x['side'] == 'SELL':
                x['filled_quantity'], x['signal_price'], x['funds'] = '20', '150', '3000'
        t = A.load_trips(r.rows)[0]
        self.assertTrue(t.measurable(), t.problems)
        self.assertEqual(t.side_cost('BUY'), Decimal('0.4'))


class Aggregation(unittest.TestCase):
    def test_coin_equal_weight_not_trade_weight(self):
        r = Rows()
        for _ in range(10):
            r.trip('IOTA', *cost_of('0.20'))
        for _ in range(3):
            r.trip('WAVES', *cost_of('0.50'))
        res = r.run()
        self.assertEqual(res['value'], Decimal('0.35'), '코인 동일가중 (0.20 + 0.50) / 2 — 거래 가중이면 0.27')
        self.assertEqual(res['judgeable'], ['IOTA', 'WAVES'])

    def test_white_coins_excluded_not_filled(self):
        r = Rows()
        for _ in range(3):
            r.trip('IOTA', *cost_of('0.20'))
        r.trip('WAVES', *cost_of('5.00'))
        r.trip('WAVES', *cost_of('5.00'))
        res = r.run()
        self.assertEqual(res['value'], Decimal('0.20'))
        self.assertEqual(res['coins']['WAVES']['class'], '⚪ 판정 불가(1~2건)')
        self.assertEqual(res['coins']['CRO']['class'], '⚪ 미관측')

    def test_coin_over_060_flagged_but_kept_in_overall_D6(self):
        r = Rows()
        for _ in range(3):
            r.trip('IOTA', *cost_of('0.20'))
            r.trip('WAVES', *cost_of('0.80'))
        res = r.run()
        self.assertEqual(res['coins']['WAVES']['class'], '판정 가능 — 제외(> 0.60%)')
        self.assertEqual(res['value'], Decimal('0.50'))


class Termination(unittest.TestCase):
    def fill(self, coins, per_coin, cost='0.20', day=1):
        r = Rows()
        for c in A.UNIVERSE[:coins]:
            for _ in range(per_coin):
                r.trip(c, *cost_of(cost), day=day)
        return r

    def test_eight_coins_and_thirty_trips_met(self):
        res = self.fill(8, 4).run()
        self.assertTrue(res['met'])
        self.assertTrue(res['band'].startswith('≤ 0.30%'))

    def test_thirty_trips_but_seven_coins_not_met(self):
        res = self.fill(7, 5).run()
        self.assertEqual(res['total'], 35)
        self.assertFalse(res['met'])
        self.assertTrue(res['band'].startswith('(판정 아님)'))

    def test_eight_coins_but_twenty_four_trips_not_met(self):
        self.assertFalse(self.fill(8, 3).run()['met'])

    def test_sixty_day_cap_without_conditions_is_unjudgeable(self):
        res = self.fill(7, 5).run(days=60)
        self.assertTrue(res['capped'])
        self.assertIn('판정 불가 → 2단계 진입 불가', res['status'])

    def test_trades_after_day_60_cannot_complete_the_sample(self):
        """🔴 리뷰: 60일까지 7코인이었는데 61일째 8번째 코인 거래로 조건을 채우면 '정상 종료' 가 되던 결함."""
        r = self.fill(7, 5)
        for _ in range(3):
            r.trip(A.UNIVERSE[7], *cost_of('0.20'), day=61)
        res = r.run(days=70)
        self.assertFalse(res['met'])
        self.assertEqual(res['closed_after_window'], 3)
        self.assertIn('판정 불가 → 2단계 진입 불가', res['status'])

    def test_day_60_boundary_is_inside(self):
        r = self.fill(7, 5)
        for _ in range(3):
            r.trip(A.UNIVERSE[7], *cost_of('0.20'), closed=START + timedelta(days=60))
        self.assertTrue(r.run(days=70)['met'], '시작 + 60일 정각 청산은 기간 안')

    def test_stage_end_limits_window(self):
        r = self.fill(8, 4, day=1)
        for _ in range(3):
            r.trip('IOTA', *cost_of('5.00'), day=20)
        res = r.run(days=30, end=START + timedelta(days=10))
        self.assertEqual(res['closed_after_window'], 3, '단계 종료 뒤 청산은 넣지 않는다')
        self.assertEqual(res['value'], Decimal('0.20'))

    def test_entered_before_start_excluded(self):
        r = Rows()
        for _ in range(3):
            r.trip('IOTA', *cost_of('0.20'))
        r.trip('IOTA', *cost_of('5.00'), opened=START - timedelta(hours=1), closed=START + timedelta(days=1))
        res = r.run()
        self.assertEqual(res['opened_before_start'], 1)
        self.assertEqual(res['value'], Decimal('0.20'))


class Bands(unittest.TestCase):
    def band(self, cost):
        r = Rows()
        for c in A.UNIVERSE[:8]:
            for _ in range(4):
                r.trip(c, *cost_of(cost))
        return r.run()['band']

    def test_boundaries(self):
        self.assertTrue(self.band('0.30').startswith('≤ 0.30%'), '0.30 은 유효 구간(≤)')
        self.assertTrue(self.band('0.3001').startswith('0.30% ~ 0.40%'))
        self.assertTrue(self.band('0.40').startswith('0.30% ~ 0.40%'), '0.40 은 재계산 구간(≤)')
        self.assertTrue(self.band('0.4001').startswith('🔴 > 0.40%'))


class Unmeasurable(unittest.TestCase):
    """D3 — 측정 불가는 유효 표본과 따로 센다. 대상 포지션은 추출에서 보존된다(주문 없는 포지션도 빈 행)."""

    def base_with_three_good(self):
        r = Rows()
        for _ in range(3):
            r.trip('IOTA', *cost_of('0.20'))
        return r

    def assert_unmeasurable(self, r, text):
        res = r.run()
        self.assertEqual(res['total'], 3, '유효 표본은 그대로')
        self.assertEqual(res['value'], Decimal('0.20'))
        self.assertEqual(len(res['unmeasurable']), 1)
        self.assertIn(text, '; '.join(res['unmeasurable'][0][2]))

    def test_position_without_orders(self):
        r = self.base_with_three_good()
        pid, base = r.trip('IOTA')
        r.rows = [x for x in r.rows if x['position_id'] != pid] + [{**base, 'order_id': '', 'side': '', 'state': '', 'signal_price': '',
                                                                      'filled_quantity': '', 'funds': '', 'fill_price_source': '', 'filled_at': ''}]
        self.assert_unmeasurable(r, '주문 없음')

    def test_missing_sell_side(self):
        r = self.base_with_three_good()
        pid, _ = r.trip('IOTA')
        r.rows = [x for x in r.rows if not (x['position_id'] == pid and x['side'] == 'SELL')]
        self.assert_unmeasurable(r, 'SELL 체결 없음')

    def test_missing_filled_quantity(self):
        r = self.base_with_three_good()
        pid, _ = r.trip('IOTA')
        for x in r.rows:
            if x['position_id'] == pid and x['side'] == 'SELL':
                x['filled_quantity'] = ''
        self.assert_unmeasurable(r, '체결량 누락')

    def test_missing_signal_and_funds(self):
        r = self.base_with_three_good()
        pid, _ = r.trip('IOTA')
        for x in r.rows:
            if x['position_id'] == pid and x['side'] == 'BUY':
                x['signal_price'] = ''
        self.assert_unmeasurable(r, '신호가 없음')
        r2 = self.base_with_three_good()
        pid, _ = r2.trip('IOTA')
        for x in r2.rows:
            if x['position_id'] == pid and x['side'] == 'SELL':
                x['funds'] = ''
        self.assert_unmeasurable(r2, '체결 대금 없음')

    def test_unresolved_fill(self):
        r = self.base_with_three_good()
        pid, _ = r.trip('IOTA')
        for x in r.rows:
            if x['position_id'] == pid and x['side'] == 'SELL':
                x['state'] = 'UNKNOWN'
        self.assert_unmeasurable(r, '미결 상태(UNKNOWN)')

    def test_buy_sell_quantity_mismatch(self):
        r = self.base_with_three_good()
        pid, _ = r.trip('IOTA')
        for x in r.rows:
            if x['position_id'] == pid and x['side'] == 'SELL':
                x['filled_quantity'] = '9.5'
                x['funds'] = '950'
        self.assert_unmeasurable(r, '매수 체결 10.00000000 ≠ 매도 체결 9.50000000')

    def test_failed_order_without_fill_is_ignored(self):
        r = self.base_with_three_good()
        pid, base = r.trip('IOTA', *cost_of('0.20'))
        r.order(pid, base, 'SELL', '100', '0', '', state='FAILED')   # 체결 없는 실패 매도 — 비용과 무관
        res = r.run()
        self.assertEqual((res['total'], len(res['unmeasurable'])), (4, 0))


class Exclusions(unittest.TestCase):
    def test_outside_universe_other_size_paper(self):
        r = Rows()
        for _ in range(3):
            r.trip('IOTA', *cost_of('0.20'))
        r.trip('BTC', *cost_of('9'))                           # D1
        r.trip('IOTA', *cost_of('9'), size='50000')            # D4 다른 진입 배정액
        r.trip('IOTA', *cost_of('9'), size='')                 # D4 불명
        r.trip('IOTA', *cost_of('9'), source='PAPER_SIGNAL')   # D5
        res = r.run()
        self.assertEqual(res['value'], Decimal('0.20'))
        self.assertEqual((res['outside_universe'], res['other_size'], res['paper'], len(res['unmeasurable'])), (1, 2, 1, 0))


class EndToEnd(unittest.TestCase):
    def test_csv_and_report(self):
        r = Rows()
        for c in A.UNIVERSE[:8]:
            for _ in range(4):
                r.trip(c, *cost_of('0.25'))
        with tempfile.NamedTemporaryFile('w', suffix='.csv', delete=False, encoding='utf-8', newline='') as f:
            w = csv.DictWriter(f, fieldnames=FIELDS)
            w.writeheader()
            w.writerows(r.rows)
            path = f.name
        try:
            buf = io.StringIO()
            with redirect_stdout(buf):
                A.main([path, '--start', START.isoformat(), '--as-of', (START + timedelta(days=30)).isoformat()])
            out = buf.getvalue()
            self.assertIn('③ 판정 가능 8코인의 동일가중 평균: 0.2500%', out)
            self.assertIn('정상 종료 조건 충족', out)
            self.assertIn('측정 불가: 0건', out)
        finally:
            os.remove(path)

    def test_postgres_time_format(self):
        self.assertEqual(A.parse_time('2026-11-02 01:00:00+00'), datetime(2026, 11, 2, 1, tzinfo=timezone.utc))
        self.assertEqual(A.parse_time('2026-11-02 10:00:00+09'), datetime(2026, 11, 2, 1, tzinfo=timezone.utc))


if __name__ == '__main__':
    unittest.main()
