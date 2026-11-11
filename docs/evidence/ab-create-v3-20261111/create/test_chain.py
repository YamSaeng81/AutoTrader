"""국소 연결 시험 (총괄 v1.149 §4-2.3) — 생성 호출기 API 초안 → 읽기 전용 DB 확인 출력(합성) → 완성기 → 집계기.

실제 서버·DB·토큰 없음. DB 확인 출력은 post_creation_check.sql 의 psql --csv 형식(@@ 구획)을 시험이 합성한다 — 실제 DB 결과가 아니다.
보는 것: 정상 연결이면 주지표 계산, 그리고 ID/팔 뒤바뀜·한 팔 저장 하한 불일치·지문 누락/복수·T0 이전 거래·DB 확인 실패(출력 잘림)·
DB 를 읽지 않고 db_verified=true 만 붙인 초안을 각각 거절.
실행: 이 폴더에서 python -X utf8 -m unittest -v test_chain
"""
import contextlib
import copy
import io
import json
import sys
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tool"))

import ab_aggregate as ag   # noqa: E402
import ab_complete as cp    # noqa: E402
import ab_create            # noqa: E402
from test_ab_create import Fake, IN_WINDOW, PROTOCOL   # noqa: E402

CODE = "a1" * 20


def make_draft(tmp: Path) -> dict:
    out = tmp / "out"
    out.mkdir()
    token = tmp / "t.txt"
    token.write_text("LOCAL_FAKE", encoding="utf-8")
    Fake.cfg = {}
    args = ["--base-url", "http://invalid.invalid", "--token-file", str(token), "--protocol", str(PROTOCOL), "--out-dir", str(out),
            "--code-sha", CODE, "--execute"]
    with patch.object(ab_create, "Client", Fake), contextlib.redirect_stdout(io.StringIO()):
        rc = ab_create.run(args, now=IN_WINDOW)
    assert rc == 0
    return json.loads((out / "creation_log.draft.json").read_text(encoding="utf-8"))


def db_rows(draft: dict, protocol: dict) -> dict:
    """완성기가 읽을 DB 확인 결과(합성) — 구획별 행."""
    c = protocol["common"]
    stored, fps = [], []
    for i, s in enumerate(sorted(draft["sessions"], key=lambda x: int(x["session_id"]))):
        spec = protocol["pairs"][s["pair_id"]]
        stored.append({"id": s["session_id"], "strategy_name": spec["strategy"], "coin_pair": spec["coin"], "timeframe": protocol["timeframe"],
                       "status": "RUNNING", "initial_capital": "100000.00", "invest_ratio": "0.8000",
                       "stop_loss_pct": "" if s["arm"] == "A" else "5.00", "max_hold_hours": "24",
                       "max_investment": str(c["max_investment"]), "params_json": json.dumps(protocol["params"]),
                       "started_at": s["created_at"], "before_t0": "t"})
        fps.append({"session_id": s["session_id"], "hashes": "1", "ruleset_hash": f"{0xabc000 + i:012x}", "first_log": s["created_at"]})
    return {"db_now": [{"db_now": "2026-12-07 00:09:30+00"}], "stored": stored, "fingerprint": fps,
            "before_t0": [{"orders_before_t0": "0", "positions_before_t0": "0"}]}


def render(sections: dict, truncate_after: str | None = None) -> str:
    lines = []
    for name in ("db_now", "stored", "fingerprint", "before_t0"):
        lines.append("@@" + name)
        rows = sections[name]
        if rows:
            keys = list(rows[0].keys())
            lines.append(",".join(keys))
            for r in rows:
                lines.append(",".join('"' + str(r[k]).replace('"', '""') + '"' for k in keys))
        if truncate_after == name:
            return "\n".join(lines) + "\n"
    lines.append("@@end")
    return "\n".join(lines) + "\n"


def arms_from_log(log: dict, protocol: dict) -> list[dict]:
    t0 = ag.ts(log["t0"])
    rows = []
    for e in log["sessions"]:
        st = e["stored"]
        rows.append({"pair_id": e["pair_id"], "arm": e["arm"], "session_id": e["session_id"], "strategy": st["strategy"],
                     "coin": st["coin"], "timeframe": st["timeframe"], "initial_capital": st["initial_capital"],
                     "invest_ratio": st["invest_ratio"], "max_investment": st["max_investment"], "max_hold_hours": st["max_hold_hours"],
                     "stop_loss_pct": st["stop_loss_pct"], "params_json": json.dumps(st["params_json"]), "ruleset_hash": e["ruleset_hash"],
                     "code_sha": log["code_sha"], "start_ts": t0.isoformat(), "end_ts": (t0 + timedelta(days=int(protocol["days"]))).isoformat()})
    return rows


def marks_for(t0) -> list[dict]:
    out = []
    for coin in ("KRW-BTC", "KRW-ETH"):
        for i in range(43):
            out.append({"coin": coin, "ts": (t0 + timedelta(days=i)).isoformat(), "price": "1000"})
    return out


class Chain(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.protocol, cls.psha = cp.json.loads(PROTOCOL.read_bytes().decode("utf-8")), cp.hashlib.sha256(PROTOCOL.read_bytes()).hexdigest()
        cls.draft = make_draft(Path(tempfile.mkdtemp(prefix="chain-")))

    def complete(self, sections=None, draft=None, truncate_after=None):
        sections = sections or db_rows(self.draft, self.protocol)
        return cp.complete(copy.deepcopy(draft or self.draft), render(sections, truncate_after), self.protocol, self.psha)

    def aggregate(self, log, positions=None):
        rows = arms_from_log(log, self.protocol)
        return ag.aggregate(rows, positions or [], marks_for(ag.ts(log["t0"])), [], creation_log=log, protocol_path=PROTOCOL)

    def test_control_chain_computes(self):
        log = self.complete()
        self.assertTrue(log["db_verified"])
        self.assertEqual(log["db_check"]["status"], "확인됨")
        out = self.aggregate(log)
        print("[연결 대조] 주지표=" + str(out["primary_mean_B_minus_A"]) + " 상태=" + out["primary_status"])
        self.assertEqual(out["primary_mean_B_minus_A"], "0.000000")

    def test_rejections(self):
        base = db_rows(self.draft, self.protocol)
        cases = {}
        s = copy.deepcopy(base)   # 같은 쌍의 A·B ID 뒤바뀜: DB 하한이 초안 팔과 반대
        a = next(r for r in s["stored"] if r["stop_loss_pct"] == "")
        b = next(r for r in s["stored"] if r["stop_loss_pct"] == "5.00" and r["coin_pair"] == a["coin_pair"] and r["strategy_name"] == a["strategy_name"])
        a["stop_loss_pct"], b["stop_loss_pct"] = "5.00", ""
        cases["ID/팔 뒤바뀜"] = s
        s = copy.deepcopy(base); next(r for r in s["stored"] if r["stop_loss_pct"] == "5.00")["stop_loss_pct"] = "4.00"
        cases["한 팔 저장 하한 4"] = s
        s = copy.deepcopy(base); s["fingerprint"] = s["fingerprint"][:7]
        cases["지문 누락(한 세션 로그 없음)"] = s
        s = copy.deepcopy(base); s["fingerprint"][2]["hashes"] = "2"
        cases["지문 복수"] = s
        s = copy.deepcopy(base); s["fingerprint"][4]["ruleset_hash"] = ""
        cases["빈 지문"] = s
        s = copy.deepcopy(base); s["before_t0"][0]["positions_before_t0"] = "1"
        cases["T0 이전 포지션"] = s
        s = copy.deepcopy(base); s["stored"][0]["invest_ratio"] = "0.9000"
        cases["저장 투자 비율 다름"] = s
        s = copy.deepcopy(base); s["stored"] = s["stored"][:7]
        cases["DB 행 7개"] = s
        for name, sections in cases.items():
            with self.subTest(name):
                with self.assertRaises(cp.Rejected):
                    self.complete(sections)

    def test_db_check_failure_truncated_output(self):
        for cut in ("db_now", "stored", "fingerprint", "before_t0"):
            with self.subTest(cut):
                with self.assertRaises(cp.Rejected):
                    self.complete(truncate_after=cut)

    def test_draft_with_failed_api_reconcile_not_completed(self):
        d = copy.deepcopy(self.draft)
        d["api_reconcile_ok"] = False
        with self.assertRaises(cp.Rejected):
            self.complete(draft=d)

    def test_manual_flag_without_db_reading_rejected_by_aggregator(self):
        """DB 를 읽지 않고 초안에 db_verified=true 만 붙이고 지문을 손으로 채운 파일 — 집계기가 거절(db_check 없음)."""
        forged = copy.deepcopy(self.draft)
        forged["db_verified"] = True
        for i, e in enumerate(forged["sessions"]):
            e["ruleset_hash"] = f"{i:012x}"
            spec = self.protocol["pairs"][e["pair_id"]]
            e["stored"] = {"strategy": spec["strategy"], "coin": spec["coin"], "timeframe": "M15", "initial_capital": "100000",
                           "invest_ratio": "0.8", "max_investment": "20000", "max_hold_hours": "24",
                           "stop_loss_pct": "" if e["arm"] == "A" else "5", "params_json": self.protocol["params"]}
        with self.assertRaises(ag.ConfigRejected):
            self.aggregate(forged)

    def test_trade_before_t0_rejected_by_aggregator_after_completion(self):
        log = self.complete()
        t0 = ag.ts(log["t0"])
        pos = [{"session_id": log["sessions"][0]["session_id"], "position_id": "1", "opened_at": (t0 - timedelta(seconds=1)).isoformat(),
                "closed_at": "", "size": "1", "buy_cash_out": "1000", "sell_cash_in": "", "status": "OPEN"}]
        with self.assertRaises(ag.ConfigRejected):
            self.aggregate(log, pos)

    def test_closing_holding_flows_through(self):
        log = self.complete()
        t0 = ag.ts(log["t0"])
        sid = next(e["session_id"] for e in log["sessions"] if e["pair_id"] == "P1" and e["arm"] == "B")
        pos = [{"session_id": sid, "position_id": "1", "opened_at": (t0 + timedelta(days=1)).isoformat(), "closed_at": "", "size": "10",
                "buy_cash_out": "10010", "sell_cash_in": "", "status": "CLOSING"}]
        out = self.aggregate(log, pos)
        self.assertEqual(out["pairs"]["P1"]["B"]["open_at_end"], 1)
        self.assertIsNotNone(out["primary_mean_B_minus_A"])


if __name__ == "__main__":
    unittest.main()
