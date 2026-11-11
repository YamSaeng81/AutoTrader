"""ab_aggregate v2 합성 자료 시험 — v1 의 비용·미실현·거래 없는 날·한쪽만 보유·끝 가격 미확인·설정 거절·구간 표시·결정성에
더해, 불변 프로토콜·생성 기록부 대조와 총괄 음성 입력 4개(v1.145 §3-4, review.py 와 같은 변형식)의 거절을 본다.
v1 fixture 에서 바꾼 것은 확정 실행 모드(paperExecutionMode=LIVE_ALIGNED)를 8세션 파라미터에 넣은 것과 생성 기록부를 만든 것뿐이다.

실행: python -I -X utf8 -m unittest test_ab_aggregate  (이 폴더에서). 실제 성적이 아니라 계산 계약 확인이다.
"""
import copy
import json
import unittest
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import ab_aggregate as ag

PARAMS = {"paperMaxInvestment": 20000, "paperExecutionMode": "LIVE_ALIGNED"}
CODE = "c0ffee"

START = datetime(2026, 12, 7, 0, 0, tzinfo=timezone.utc)   # 가상 날짜 — 실제 시작일이 아니다
END = START + timedelta(days=42)
COINS = {"P1": "KRW-BTC", "P2": "KRW-ETH", "P3": "KRW-BTC", "P4": "KRW-ETH"}
STRATS = {"P1": "COMPOSITE_MEANREV_BB", "P2": "COMPOSITE_MEANREV_BB", "P3": "COMPOSITE_MTF_CONFIRMED", "P4": "COMPOSITE_MTF_CONFIRMED"}


def iso(d):
    return d.isoformat()


def arms():
    rows, sid = [], 900
    for pid in ("P1", "P2", "P3", "P4"):
        for arm in ("A", "B"):
            sid += 1
            rows.append({
                "pair_id": pid, "arm": arm, "session_id": str(sid), "strategy": STRATS[pid], "coin": COINS[pid],
                "timeframe": "M15", "initial_capital": "100000", "invest_ratio": "0.8", "max_investment": "20000",
                "max_hold_hours": "24", "stop_loss_pct": "" if arm == "A" else "5",
                "params_json": json.dumps(PARAMS), "ruleset_hash": f"h{sid}",
                "code_sha": CODE, "start_ts": iso(START), "end_ts": iso(END),
            })
    return rows


def creation_log(rows=None):
    """생성 기록부 — 생성 직후 저장값을 그대로 적은 것(시험에서는 fixture 행에서 만든다)."""
    rows = rows if rows is not None else arms()
    _, sha = ag.load_protocol()
    return {
        "protocol_sha256": sha, "code_sha": rows[0]["code_sha"], "t0": iso(START),
        "sessions": [{"pair_id": r["pair_id"], "arm": r["arm"], "session_id": r["session_id"], "ruleset_hash": r["ruleset_hash"],
                      "created_at": iso(START - timedelta(minutes=8) + timedelta(seconds=i)),
                      "stored": {k: r[k] for k in ag.STORED_KEYS}} for i, r in enumerate(rows)],
    }


def marks(skip=None):
    out = []
    for coin, base in (("KRW-BTC", 20_000_000), ("KRW-ETH", 4_000_000)):
        for i in range(43):
            t = START + timedelta(days=i)
            if skip and (coin, t) in skip:
                continue
            price = 21_000_000 if (coin == "KRW-BTC" and i == 42) else base
            out.append({"coin": coin, "ts": iso(t), "price": str(price)})
    return out


def positions():
    # P1-A(901): 닫힌 거래 1건 +200. P1-B(902): 끝까지 보유 — 0.001 BTC 를 20,010(수수료 포함)에 삼.
    return [
        {"session_id": "901", "position_id": "1", "opened_at": iso(START + timedelta(days=1, hours=3)),
         "closed_at": iso(START + timedelta(days=2, hours=5)), "size": "0.001", "buy_cash_out": "20000",
         "sell_cash_in": "20200", "status": "CLOSED"},
        {"session_id": "902", "position_id": "2", "opened_at": iso(START + timedelta(days=40, hours=1)),
         "closed_at": "", "size": "0.001", "buy_cash_out": "20010", "sell_cash_in": "", "status": "OPEN"},
    ]


class Aggregate(unittest.TestCase):

    def run_ag(self, a=None, p=None, m=None, i=None, log="default"):
        return ag.aggregate(a if a is not None else arms(), p if p is not None else positions(),
                            m if m is not None else marks(), i or [],
                            creation_log=creation_log() if log == "default" else log)

    def test_hand_computed_costs_and_unrealized(self):
        out = self.run_ag()
        p1 = out["pairs"]["P1"]
        self.assertEqual(p1["A"]["return"], "0.002000")
        # B: 현금 100000−20010 = 79990, 평가 0.001×21,000,000×(1−0.0015) = 20968.5 → 100958.5
        self.assertEqual(p1["B"]["end_nav"], "100958.50")
        self.assertEqual(p1["B"]["return"], "0.009585")
        self.assertEqual(p1["B"]["open_at_end"], 1)
        self.assertEqual(p1["B_minus_A"], "0.007585")
        # 나머지 3쌍은 거래 없음 → 0. 4쌍 동일 가중
        self.assertEqual(out["primary_mean_B_minus_A"], "0.001896")
        self.assertIsNotNone(out["diagnostic_week_block_90ci"])

    def test_no_trade_days_are_capital(self):
        out = self.run_ag(p=[])
        for pid in ("P1", "P2", "P3", "P4"):
            self.assertEqual(out["pairs"][pid]["A"]["end_nav"], "100000.00")
            self.assertEqual(out["pairs"][pid]["B"]["days_unvalued"], 0)
        self.assertEqual(out["primary_mean_B_minus_A"], "0.000000")

    def test_end_price_missing_makes_primary_undecidable_not_dropped(self):
        out = self.run_ag(m=marks(skip={("KRW-BTC", END)}))
        self.assertIsNone(out["pairs"]["P1"]["B"]["return"])
        self.assertIsNone(out["pairs"]["P1"]["B_minus_A"])
        self.assertIsNone(out["primary_mean_B_minus_A"])
        self.assertIn("판정 불가", out["primary_status"])
        self.assertIn("P1", out["primary_status"])
        # P3 는 KRW-BTC 지만 보유가 없어 평가가가 필요 없다 — 계산된다
        self.assertEqual(out["pairs"]["P3"]["B_minus_A"], "0.000000")

    def test_mid_period_mark_missing_while_holding(self):
        gap = START + timedelta(days=41)
        out = self.run_ag(m=marks(skip={("KRW-BTC", gap)}))
        self.assertEqual(out["pairs"]["P1"]["B"]["days_unvalued"], 1)
        self.assertEqual(out["pairs"]["P1"]["A"]["days_unvalued"], 0)
        self.assertIsNotNone(out["primary_mean_B_minus_A"])
        self.assertIsNone(out["diagnostic_week_block_90ci"], "일별 값이 빠지면 진단 구간을 만들지 않는다")

    def test_interval_flags(self):
        inside = [{"start_ts": iso(START + timedelta(days=10)), "end_ts": iso(START + timedelta(days=10, hours=3)),
                   "kind": "CODE_CHANGE", "note": "배포"}]
        outside = [{"start_ts": iso(END + timedelta(days=1)), "end_ts": iso(END + timedelta(days=2)),
                    "kind": "OUTAGE", "note": "구간 밖"}]
        self.assertEqual(self.run_ag(i=inside + outside)["interval_flags"], ["CODE_CHANGE"])
        self.assertEqual(self.run_ag(i=outside)["interval_flags"], [])

    def test_deterministic(self):
        self.assertEqual(json.dumps(self.run_ag(), sort_keys=True), json.dumps(self.run_ag(), sort_keys=True))

    def test_config_rejections(self):
        cases = {}
        a = arms(); cases["7세션"] = a[:7]
        a = arms(); a[0]["stop_loss_pct"] = "5"; cases["A 에 손절 하한"] = a
        a = arms(); a[1]["stop_loss_pct"] = "4"; cases["B 손절 하한 4"] = a
        a = arms(); a[1]["invest_ratio"] = "0.9"; cases["투자 비율 다름"] = a
        a = arms(); a[1]["max_hold_hours"] = "0"; cases["시간 청산 다름"] = a
        a = arms(); a[1]["params_json"] = json.dumps({"paperMaxInvestment": 20000}); cases["모드 한쪽만"] = a
        a = arms(); a[3]["start_ts"] = iso(START + timedelta(minutes=1)); cases["시작 시각 다름"] = a
        a = arms()
        for r in a:
            r["end_ts"] = iso(START + timedelta(days=41))
        cases["41일"] = a
        a = arms(); a[1]["arm"] = "A"; cases["A 팔 둘"] = a
        a = arms(); a[1]["session_id"] = a[0]["session_id"]; cases["세션 ID 중복"] = a
        a = arms(); a[1]["code_sha"] = "other"; cases["코드 다름"] = a
        for name, rows in cases.items():
            with self.subTest(name):
                with self.assertRaises(ag.ConfigRejected):
                    self.run_ag(a=copy.deepcopy(rows))

    # ── v2: 불변 프로토콜·생성 기록부 ─────────────────────────────────────────

    OVERSEER_NEGATIVES = [
        # 총괄 review.py 와 같은 변형식(v1.145 §3-4)
        ("all_four_pairs_same_strategy_coin", lambda rows: [r.update(strategy="COMPOSITE_MEANREV_BB", coin="KRW-BTC") for r in rows]),
        ("all_capital_200000", lambda rows: [r.update(initial_capital="200000") for r in rows]),
        ("all_timeframe_H1", lambda rows: [r.update(timeframe="H1") for r in rows]),
        ("different_sha_between_pairs", lambda rows: [r.update(code_sha="different") for r in rows if r["pair_id"] == "P4"]),
    ]

    def test_overseer_negatives_rejected_without_log(self):
        """총괄 하네스처럼 check_config(rows) 만 불러도 거절된다(기록부 없이 강제하는 고정 설계)."""
        ag.check_config(arms())   # 대조군은 통과
        for name, mutate in self.OVERSEER_NEGATIVES:
            with self.subTest(name):
                rows = arms()
                mutate(rows)
                with self.assertRaises(ag.ConfigRejected):
                    ag.check_config(rows)

    def test_overseer_negatives_rejected_even_if_log_forged_to_match(self):
        """기록부를 변형된 행에 맞춰 만들어도 프로토콜이 거절한다."""
        for name, mutate in self.OVERSEER_NEGATIVES:
            with self.subTest(name):
                rows = arms()
                mutate(rows)
                with self.assertRaises(ag.ConfigRejected):
                    self.run_ag(a=rows, log=creation_log(rows))

    def test_overseer_negatives_rejected_by_aggregate_with_true_log(self):
        for name, mutate in self.OVERSEER_NEGATIVES:
            with self.subTest(name):
                rows = arms()
                mutate(rows)
                with self.assertRaises(ag.ConfigRejected):
                    self.run_ag(a=rows, log=creation_log())

    def test_log_reconciliation_rejections(self):
        cases = {}
        cases["기록부 없음"] = (arms(), None)
        lg = creation_log(); lg["protocol_sha256"] = "0" * 64; cases["프로토콜 sha 다름"] = (arms(), lg)
        lg = creation_log(); lg["code_sha"] = "other"; cases["기록부 코드 SHA 다름(8세션은 서로 같음)"] = (arms(), lg)
        lg = creation_log(); lg["sessions"][2]["ruleset_hash"] = "hX"; cases["지문이 기록부와 다름"] = (arms(), lg)
        lg = creation_log(); lg["sessions"][2]["session_id"] = "999"; cases["세션 ID 가 기록부와 다름"] = (arms(), lg)
        lg = creation_log(); lg["sessions"] = lg["sessions"][:7]; cases["기록부 7항목"] = (arms(), lg)
        lg = creation_log(); lg["sessions"][5]["stored"]["invest_ratio"] = "0.9"; cases["저장값이 기록부와 다름"] = (arms(), lg)
        lg = creation_log(); lg["sessions"][4]["created_at"] = iso(START + timedelta(seconds=1)); cases["T0 이후 생성"] = (arms(), lg)
        lg = creation_log(); lg["t0"] = iso(START + timedelta(minutes=15)); cases["시작이 T0 와 다름"] = (arms(), lg)
        for name, (rows, lg) in cases.items():
            with self.subTest(name):
                with self.assertRaises(ag.ConfigRejected):
                    self.run_ag(a=rows, log=lg)

    def test_different_fingerprints_per_arm_are_fine_when_logged(self):
        rows = arms()
        for r in rows:
            r["ruleset_hash"] = ("4db7e2626f9a" if r["arm"] == "A" else "897b9ed5f056")
        self.assertIsNotNone(self.run_ag(a=rows, log=creation_log(rows))["primary_mean_B_minus_A"])

    def test_trade_before_t0_rejected(self):
        p = positions()
        p.append({"session_id": "905", "position_id": "9", "opened_at": iso(START - timedelta(minutes=1)),
                  "closed_at": iso(START + timedelta(hours=1)), "size": "0.001", "buy_cash_out": "4002", "sell_cash_in": "4000",
                  "status": "CLOSED"})
        with self.assertRaises(ag.ConfigRejected):
            self.run_ag(p=p)

    def test_one_side_holding_only_b(self):
        out = self.run_ag()
        self.assertEqual(out["pairs"]["P1"]["A"]["open_at_end"], 0)
        self.assertEqual(out["pairs"]["P1"]["B"]["open_at_end"], 1)


if __name__ == "__main__":
    unittest.main()
