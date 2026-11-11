"""v4 시험 — 생성기 시각 원문 보존·완성기 시작 시각 대조·arms.csv 변환 (총괄 v1.151 §3, v1.152 A·C). 2026-11-11.

실행: 이 폴더에서  python -X utf8 -B -m unittest -v test_ab_v4
실제 서버·토큰·POST·DB 없음(생성기는 목 클라이언트, 완성기/변환기는 파일 입력). 실제 로컬 HTTP·PG 연결은 별도(AbChainPgTest + chain_driver_v4).

DB 출력 견본은 v1.150 실제 로컬 PG 연결 run-final-19dbad3a 의 ok.db_check.out 형식(psql --csv)을 따른다. 그 실행의 실제 관측:
초안(v3, 마이크로초 아래 잃음) 07:36:29.080737 / DB 07:36:29.080738 — 4세션이 1마이크로초 다름(총괄 v1.151 재확인).
"""
from __future__ import annotations

import contextlib
import copy
import csv
import hashlib
import io
import json
import sys
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "create"))

import ab_arms        # noqa: E402
import ab_complete as cp   # noqa: E402
import ab_create      # noqa: E402
import test_ab_create as v3t   # noqa: E402  (Fake 목 클라이언트·창 안 시각 재사용)

PROTOCOL = HERE / "protocol.json"
PSHA = hashlib.sha256(PROTOCOL.read_bytes()).hexdigest()
PROTO = json.loads(PROTOCOL.read_text(encoding="utf-8"))
CODE = "c" * 40
T0 = "2026-11-11T07:45:00+00:00"
ARMS = [(p, a) for p in ("P1", "P2", "P3", "P4") for a in ("A", "B")]
STRAT = {"P1": ("COMPOSITE_MEANREV_BB", "KRW-BTC"), "P2": ("COMPOSITE_MEANREV_BB", "KRW-ETH"),
         "P3": ("COMPOSITE_MTF_CONFIRMED", "KRW-BTC"), "P4": ("COMPOSITE_MTF_CONFIRMED", "KRW-ETH")}
# POST 원문(7자리, Java Instant) → 저장(마이크로초 반올림). 0.5 경계·올림·내림이 섞이게 고른다.
POST_RAW = ["2026-11-11T07:36:29.0252294Z", "2026-11-11T07:36:29.0622850Z", "2026-11-11T07:36:29.0807375Z",
            "2026-11-11T07:36:29.0990068Z", "2026-11-11T07:36:29.135019Z", "2026-11-11T07:36:29.1653576Z",
            "2026-11-11T07:36:29.1935215Z", "2026-11-11T07:36:29.9999995Z"]
API_STORED = ["2026-11-11T07:36:29.025229Z", "2026-11-11T07:36:29.062285Z", "2026-11-11T07:36:29.080738Z",
              "2026-11-11T07:36:29.099007Z", "2026-11-11T07:36:29.135019Z", "2026-11-11T07:36:29.165358Z",
              "2026-11-11T07:36:29.193522Z", "2026-11-11T07:36:30Z"]
DB_STARTED = ["2026-11-11 07:36:29.025229+00", "2026-11-11 07:36:29.062285+00", "2026-11-11 07:36:29.080738+00",
              "2026-11-11 07:36:29.099007+00", "2026-11-11 07:36:29.135019+00", "2026-11-11 07:36:29.165358+00",
              "2026-11-11 07:36:29.193522+00", "2026-11-11 07:36:30+00"]


def draft(**over):
    d = {"status": "API 초안", "tool_version": "v4", "protocol_sha256": PSHA, "code_sha": CODE, "t0": T0, "db_verified": False,
         "api_reconcile_ok": True,
         "sessions": [{"pair_id": p, "arm": a, "session_id": str(2 + i), "created_at": POST_RAW[i], "post_started_at_raw": POST_RAW[i],
                       "api_stored_started_at": API_STORED[i], "ruleset_hash": None, "stored": None}
                      for i, (p, a) in enumerate(ARMS)],
         "checks": {}}
    d.update(over)
    return d


def db_text(started=None, ids=None):
    started = started or DB_STARTED
    ids = ids or [str(2 + i) for i in range(8)]
    lines = ["@@db_now", "db_now", "2026-11-11 07:36:30.596247+00", "@@stored",
             "id,strategy_name,coin_pair,timeframe,status,initial_capital,invest_ratio,stop_loss_pct,max_hold_hours,max_investment,"
             "params_json,started_at,before_t0"]
    for i, (p, a) in enumerate(ARMS):
        s, c = STRAT[p]
        sl = "" if a == "A" else "5.00"
        lines.append(f'{ids[i]},{s},{c},M15,RUNNING,100000.00,0.8000,{sl},24,20000,'
                     f'"{{""paperExecutionMode"": ""LIVE_ALIGNED"", ""paperMaxInvestment"": 20000}}",{started[i]},t')
    lines += ["@@fingerprint", "session_id,hashes,ruleset_hash,first_log"]
    for i, (p, a) in enumerate(ARMS):
        lines.append(f"{ids[i]},1,{'141c4dfd6822' if a == 'A' else '282d280b8014'},2026-11-11 07:36:31+00")
    lines += ["@@before_t0", "orders_before_t0,positions_before_t0", "0,0", "@@end"]
    return "\n".join(lines) + "\n"


class Tmp(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="abv4-"))

    def complete_cli(self, d, text, out_name="creation_log.json", db_source="로컬 시험 견본"):
        dp, op, out = self.tmp / "draft.json", self.tmp / "db.out", self.tmp / out_name
        dp.write_text(json.dumps(d, ensure_ascii=False), encoding="utf-8")
        op.write_bytes(text.encode("utf-8"))
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = cp.main(["--draft", str(dp), "--db-output", str(op), "--protocol", str(PROTOCOL), "--db-source", db_source,
                          "--out", str(out)])
        return rc, buf.getvalue(), out


class Precision(unittest.TestCase):
    def test_parse_notation_equivalence(self):
        self.assertEqual(cp.ts_ns("2026-11-11T07:36:29.080738Z"), cp.ts_ns("2026-11-11 07:36:29.080738+00"))
        self.assertEqual(cp.ts_ns("2026-11-11T07:36:29.080738Z"), cp.ts_ns("2026-11-11T16:36:29.080738+09:00"))
        self.assertEqual(cp.ts_ns("2026-11-11T07:36:29.08Z"), cp.ts_ns("2026-11-11T07:36:29.080000000Z"))

    def test_parse_rejects_no_timezone_and_garbage(self):
        for bad in ("2026-11-11T07:36:29.080738", "2026-11-11 07:36:29", "어제", "", None, "2026-11-11T07:36:29.0807381234Z"):
            with self.assertRaises(cp.Rejected, msg=repr(bad)):
                cp.ts_ns(bad)

    def test_round_half_up_boundaries(self):
        for raw, stored in zip(POST_RAW, API_STORED):
            self.assertEqual(cp.round_to_micros(cp.ts_ns(raw)), cp.ts_ns(stored), raw)
        self.assertNotEqual(cp.round_to_micros(cp.ts_ns("2026-11-11T07:36:29.0807374Z")), cp.ts_ns("2026-11-11T07:36:29.080738Z"))
        # 생성기와 완성기의 계약 함수가 같은 결과
        for raw in POST_RAW:
            self.assertEqual(ab_create.round_to_micros(ab_create.ts_ns(raw)), cp.round_to_micros(cp.ts_ns(raw)))


class Complete(Tmp):
    def test_normal_with_notation_difference_completes(self):
        rc, out, path = self.complete_cli(draft(), db_text())
        self.assertEqual(rc, 0, out)
        log = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(log["tool_version"], "v4")
        self.assertTrue(log["db_check"]["started_at_match"].startswith("8/8"))
        self.assertEqual(log["db_check"]["db_source"], "로컬 시험 견본")
        self.assertRegex(log["draft_sha256"], "^[0-9a-f]{64}$")
        s = {x["pair_id"] + x["arm"]: x for x in log["sessions"]}
        self.assertEqual(s["P2A"]["post_started_at_raw"], "2026-11-11T07:36:29.0807375Z")
        self.assertEqual(s["P2A"]["db_started_at"], "2026-11-11 07:36:29.080738+00")

    def test_other_db_same_ids_rejected_no_file(self):
        other = [x.replace("07:36:", "07:21:") for x in DB_STARTED]   # 다른 DB/다른 회차: 같은 ID, 다른 시작 시각(v1.150 run3 모양)
        rc, out, path = self.complete_cli(draft(), db_text(started=other))
        self.assertEqual(rc, 3)
        self.assertIn("DB started_at 이 저장 후 API 시작 시각과 다르다", out)
        self.assertIn("P1A", out)
        self.assertFalse(path.exists())

    def test_one_row_off_by_one_micro_rejected(self):
        one = list(DB_STARTED)
        one[5] = "2026-11-11 07:36:29.165357+00"
        rc, out, path = self.complete_cli(draft(), db_text(started=one))
        self.assertEqual(rc, 3)
        self.assertIn("P3B", out)
        self.assertFalse(path.exists())

    def test_db_without_timezone_rejected(self):
        nz = list(DB_STARTED)
        nz[0] = "2026-11-11 07:36:29.025229"
        rc, out, path = self.complete_cli(draft(), db_text(started=nz))
        self.assertEqual(rc, 3)
        self.assertIn("시간대", out)
        self.assertFalse(path.exists())

    def test_old_v3_draft_rejected_not_filled(self):
        d = draft()
        del d["tool_version"]
        for s in d["sessions"]:
            s.pop("post_started_at_raw")
            s.pop("api_stored_started_at")
        rc, out, path = self.complete_cli(d, db_text())
        self.assertEqual(rc, 3)
        self.assertIn("v4 가 아니다", out)
        self.assertFalse(path.exists())

    def test_missing_field_rejected(self):
        for field in ("post_started_at_raw", "api_stored_started_at"):
            d = draft()
            d["sessions"][3][field] = None
            rc, out, path = self.complete_cli(d, db_text(), out_name=f"x-{field}.json")
            self.assertEqual(rc, 3, field)
            self.assertFalse(path.exists())

    def test_draft_precision_contract_broken_rejected(self):
        d = draft()
        d["sessions"][2]["api_stored_started_at"] = "2026-11-11T07:36:29.080737Z"   # 반올림이면 080738 이어야 함
        rc, out, path = self.complete_cli(d, db_text())
        self.assertEqual(rc, 3)
        self.assertIn("저장 정밀도 계약", out)
        self.assertFalse(path.exists())

    def test_created_at_must_equal_post_raw(self):
        d = draft()
        d["sessions"][0]["created_at"] = "2026-11-11T07:36:29.025229+00:00"   # v3 식 잘린 값
        rc, out, path = self.complete_cli(d, db_text())
        self.assertEqual(rc, 3)
        self.assertFalse(path.exists())

    def test_existing_output_not_overwritten(self):
        p = self.tmp / "creation_log.json"
        p.write_text("기존", encoding="utf-8")
        rc, out, path = self.complete_cli(draft(), db_text())
        self.assertEqual(rc, 3)
        self.assertEqual(p.read_text(encoding="utf-8"), "기존")

    def test_empty_db_source_rejected(self):
        rc, out, path = self.complete_cli(draft(), db_text(), db_source="  ")
        self.assertEqual(rc, 3)
        self.assertFalse(path.exists())

    def test_v3_rejections_kept(self):
        swapped = draft()
        swapped["sessions"][0]["session_id"], swapped["sessions"][1]["session_id"] = "3", "2"
        rc, out, _ = self.complete_cli(swapped, db_text(started=[DB_STARTED[1], DB_STARTED[0]] + DB_STARTED[2:]),
                                       out_name="swap.json")
        self.assertEqual(rc, 3)
        self.assertIn("뒤바뀜", out)


class Arms(Tmp):
    def complete_ok(self):
        if (self.tmp / "creation_log.json").exists():   # 1회차 결함: 같은 임시 폴더에서 반복 생성 → 완성기의 덮어쓰기 거절
            return self.tmp / "creation_log.json"
        rc, out, path = self.complete_cli(draft(), db_text())
        self.assertEqual(rc, 0, out)
        return path

    def arms_cli(self, complete_path, out_name="arms.csv", protocol=PROTOCOL, db_output=None):
        out = self.tmp / out_name
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = ab_arms.main(["--complete", str(complete_path), "--protocol", str(protocol),
                               "--db-output", str(db_output or self.tmp / "db.out"), "--out", str(out)])
        return rc, buf.getvalue(), out

    def test_normal_eight_arms_and_aggregator_accepts(self):
        rc, out, path = self.arms_cli(self.complete_ok())
        self.assertEqual(rc, 0, out)
        rows = list(csv.DictReader(io.StringIO(path.read_text(encoding="utf-8"))))
        self.assertEqual([(r["pair_id"], r["arm"]) for r in rows], ARMS)
        self.assertEqual(rows[0]["params_json"], '{"paperExecutionMode":"LIVE_ALIGNED","paperMaxInvestment":20000}')
        self.assertEqual({r["start_ts"] for r in rows}, {T0})
        self.assertEqual({r["end_ts"] for r in rows}, {"2026-12-23T07:45:00+00:00"})
        self.assertEqual(rows[0]["stop_loss_pct"], "")
        self.assertEqual(rows[1]["stop_loss_pct"], "5.00")
        meta = json.loads(path.with_name("arms.csv.meta.json").read_text(encoding="utf-8"))
        self.assertEqual(meta["arms_csv_sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertEqual(meta["protocol_sha256"], PSHA)
        log = json.loads((self.tmp / "creation_log.json").read_text(encoding="utf-8"))
        ab_arms.ag.check_config(rows, log, PROTOCOL)   # 집계기 설정 검사 통과

    def test_same_input_same_bytes(self):
        c = self.complete_ok()
        rc1, _, p1 = self.arms_cli(c, "a1.csv")
        rc2, _, p2 = self.arms_cli(c, "a2.csv")
        self.assertEqual((rc1, rc2), (0, 0))
        self.assertEqual(p1.read_bytes(), p2.read_bytes())

    def test_existing_output_rejected_and_untouched(self):
        c = self.complete_ok()
        p = self.tmp / "arms.csv"
        p.write_text("기존", encoding="utf-8")
        rc, out, _ = self.arms_cli(c)
        self.assertEqual(rc, 3)
        self.assertEqual(p.read_text(encoding="utf-8"), "기존")
        self.assertFalse((self.tmp / "arms.csv.meta.json").exists())

    def test_other_db_output_rejected(self):
        c = self.complete_ok()
        other = self.tmp / "other.db.out"
        other.write_bytes(db_text(started=[x.replace("07:36:", "07:21:") for x in DB_STARTED]).encode("utf-8"))
        rc, out, path = self.arms_cli(c, db_output=other)
        self.assertEqual(rc, 3)
        self.assertIn("sha256", out)
        self.assertFalse(path.exists())

    def _mutated(self, fn, name):
        log = json.loads(self.complete_ok().read_text(encoding="utf-8"))
        fn(log)
        p = self.tmp / f"{name}.json"
        p.write_text(json.dumps(log, ensure_ascii=False), encoding="utf-8")
        rc, out, path = self.arms_cli(p, f"{name}.csv")
        self.assertEqual(rc, 3, name + out)
        self.assertFalse(path.exists(), name)
        self.assertFalse(path.with_name(path.name + ".meta.json").exists(), name)
        return out

    def test_rejections_write_nothing(self):
        def draft_like(l): l["db_verified"] = False
        def old_v3(l): l.pop("tool_version")
        def no_match(l): l["db_check"].pop("started_at_match")
        def seven(l): l["sessions"].pop()
        def dup_arm(l): l["sessions"][1]["arm"] = "A"
        def swap_ids(l): l["sessions"][0]["session_id"], l["sessions"][2]["session_id"] = l["sessions"][2]["session_id"], l["sessions"][0]["session_id"]
        def swap_arm(l): l["sessions"][0]["stored"]["stop_loss_pct"] = "5.00"
        def missing_value(l): l["sessions"][4]["stored"]["invest_ratio"] = ""
        def missing_key(l): l["sessions"][4]["stored"].pop("max_hold_hours")
        def params_string(l): l["sessions"][0]["stored"]["params_json"] = json.dumps(l["sessions"][0]["stored"]["params_json"])
        def bad_code(l): l["code_sha"] = "abc"
        def t0_naive(l): l["t0"] = "2026-11-11T07:45:00"
        def start_evidence(l): l["sessions"][3]["db_started_at"] = "2026-11-11 07:21:00+00"
        def protocol_sha(l): l["protocol_sha256"] = "0" * 64
        def params_value(l): l["sessions"][6]["stored"]["params_json"]["paperMaxInvestment"] = 30000
        for fn in (draft_like, old_v3, no_match, seven, dup_arm, swap_ids, swap_arm, missing_value, missing_key, params_string,
                   bad_code, t0_naive, start_evidence, protocol_sha, params_value):
            with self.subTest(fn.__name__):
                self._mutated(fn, fn.__name__)


class CreatorV4(unittest.TestCase):
    """생성기 v4 — 목 클라이언트(v3 시험의 Fake). 실제 HTTP 없음."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="abv4-create-"))
        self.token = self.tmp / "token.txt"
        self.token.write_text("LOCAL_FAKE", encoding="utf-8")
        self.out = self.tmp / "out"
        self.out.mkdir()
        v3t.Fake.cfg = {}

    def run_tool(self):
        args = ["--base-url", "http://invalid.invalid", "--token-file", str(self.token), "--protocol", str(PROTOCOL),
                "--out-dir", str(self.out), "--code-sha", "b" * 40, "--execute"]
        buf = io.StringIO()
        with patch.object(ab_create, "Client", v3t.Fake), contextlib.redirect_stdout(buf):
            rc = ab_create.run(args, now=v3t.IN_WINDOW)
        return rc, v3t.Fake.last, json.loads((self.out / "creation_log.draft.json").read_text(encoding="utf-8"))

    def _post_with_raw(self, client, n, body):
        raw = (v3t.IN_WINDOW + timedelta(seconds=n)).strftime("%Y-%m-%dT%H:%M:%S") + ".1234565Z"
        stored = (v3t.IN_WINDOW + timedelta(seconds=n)).strftime("%Y-%m-%dT%H:%M:%S") + ".123457Z"
        d = {"id": 1000 + n, "strategyName": body["strategyType"], "coinPair": body["coinPair"], "timeframe": body["timeframe"],
             "initialCapital": body["initialCapital"], "strategyParams": body["strategyParams"], "status": "RUNNING", "startedAt": raw}
        row = dict(d, startedAt=stored)
        if n == 3 and v3t.Fake.cfg.get("break_one"):
            row["startedAt"] = (v3t.IN_WINDOW + timedelta(seconds=n)).strftime("%Y-%m-%dT%H:%M:%S") + ".123456Z"
        client.rows.append(row)
        return 200, {"success": True, "data": d}, None

    def test_raw_post_and_stored_list_preserved(self):
        v3t.Fake.cfg = {"post": self._post_with_raw}
        rc, c, d = self.run_tool()
        self.assertEqual(rc, 0)
        self.assertEqual(c.posts, 8)
        self.assertEqual(d["tool_version"], "v4")
        s0 = d["sessions"][0]
        self.assertTrue(s0["post_started_at_raw"].endswith(".1234565Z"))
        self.assertEqual(s0["created_at"], s0["post_started_at_raw"])
        self.assertTrue(s0["api_stored_started_at"].endswith(".123457Z"))
        self.assertEqual(d["checks"]["precision_contract_failed"], [])

    def test_one_stored_time_off_fails_reconcile(self):
        v3t.Fake.cfg = {"post": self._post_with_raw, "break_one": True}
        rc, c, d = self.run_tool()
        self.assertEqual(rc, 3)
        self.assertEqual(c.posts, 8)   # 추가 POST 없음
        self.assertFalse(d["api_reconcile_ok"])
        self.assertEqual(d["checks"]["precision_contract_failed"], ["P2A"])


if __name__ == "__main__":
    unittest.main()
