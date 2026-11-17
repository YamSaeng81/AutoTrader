"""SMA20 단건 도구(sma20_create·sma20_complete) 시험 — 대역 HTTP 서버(127.0.0.1) + 대역 DB 메타. 외부 연결·운영 DB 없음.
실행: 이 폴더에서 python -X utf8 -B -W ignore::ResourceWarning -m unittest -v test_sma20
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import tempfile
import threading
import time
import unittest
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
import sma20_create as sc   # noqa: E402
import sma20_complete as cp   # noqa: E402
ab = sc.ab
import fake_meta   # noqa: E402  (create/ — sma20_create 가 경로를 넣는다)

BODY = HERE / "body.json"
TOKEN = "sma20-standin-token-not-a-secret"
WS = datetime(2026, 11, 18, 5, 0, tzinfo=timezone.utc)     # 14:00 KST
WE = datetime(2026, 11, 18, 5, 10, tzinfo=timezone.utc)
T0 = datetime(2026, 11, 19, 0, 0, tzinfo=timezone.utc)     # 다음 날 09:00 KST
NOW = datetime(2026, 11, 18, 5, 5, 7, tzinfo=timezone.utc)
ENDPOINT = ab.ENDPOINT


class Srv:
    """scenario: ok · auth · http500 · fail · timeout(생성 뒤 지연) · redirect302 · mismatch."""

    def __init__(self, scenario="ok", rows=None):
        self.scenario, self.rows, self.reqs = scenario, list(rows or []), []
        self.lock, self.next_id = threading.Lock(), 900
        me = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _send(self, c, obj, hdr=()):
                b = json.dumps(obj).encode("utf-8")
                self.send_response(c)
                for k, v in hdr:
                    self.send_header(k, v)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

            def do_GET(self):
                me.reqs.append(("GET", self.path))
                self._send(200, {"success": True, "data": [{"id": 560, "totalReturnPct": 1.0}]})

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode("utf-8"))
                me.reqs.append(("POST", self.path, self.headers.get("Authorization") == "Bearer " + TOKEN))
                s = me.scenario
                if s == "auth":
                    return self._send(401, {"success": False})
                if s == "http500":
                    return self._send(500, {"success": False, "error": {"code": "X"}})
                if s == "fail":
                    return self._send(200, {"success": False, "data": None, "error": {"code": "INVALID_REQUEST"}})
                if s == "redirect302":
                    return self._send(302, {}, [("Location", ENDPOINT + "?size=500")])
                with me.lock:
                    sid = me.next_id
                    me.next_id += 1
                    row = {"id": sid, "status": "RUNNING", "strategyName": body["strategyType"], "coinPair": body["coinPair"],
                           "timeframe": body["timeframe"], "initialCapital": body["initialCapital"],
                           "strategyParams": body["strategyParams"], "startedAt": "2026-11-18T05:05:07.123456789Z"}
                    me.rows.append(row)
                resp = dict(row)
                if s == "mismatch":
                    resp["strategyParams"] = {"paperMaxInvestment": 20000}
                if s == "timeout":
                    time.sleep(2.5)
                return self._send(200, {"success": True, "data": resp, "error": None})

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"

    def posts(self):
        return [r for r in self.reqs if r[0] == "POST"]


class FakeSMA:
    """sma20_meta_preflight.sql·meta_after.sql 의 의미를 흉내 낸 대역(행 조건: RUNNING+실행 모드 또는 forwardTag, 522~587 제외)."""
    srv: Srv = None
    db_now = "2026-11-18 05:05:07.5+00"
    hook = None
    calls: list = []

    def __init__(self, db_cmd):
        pass

    def _text(self, which, max_id=None):
        rows = FakeSMA.srv.rows
        txt = fake_meta.meta_text(rows, which, max_id, db_now=FakeSMA.db_now)
        if which == "preflight":   # forwardTag 행(상태 무관)도 더한다
            extra = [r for r in rows if r["id"] not in fake_meta.PREREG and r.get("status") != "RUNNING" and sc.tagged(r)]
            if extra:
                lines = txt.split("\n")
                i = lines.index("@@end")
                for r in extra:
                    lines.insert(i, f'{r["id"]},{r["status"]},{r["strategyName"]},{r["coinPair"]},{r["timeframe"]},'
                                    f'{float(r["initialCapital"]):.8f},"{json.dumps(r["strategyParams"]).replace(chr(34), chr(34) * 2)}",'
                                    f'{fake_meta._db_ts(r["startedAt"])}')
                txt = "\n".join(lines)
        return txt

    def preflight(self):
        FakeSMA.calls.append("preflight")
        if FakeSMA.hook:
            r = FakeSMA.hook("preflight")
            if r:
                return ab.parse_meta(*r)
        return ab.parse_meta(0, self._text("preflight"))

    def after(self, max_id):
        FakeSMA.calls.append("after")
        if FakeSMA.hook:
            r = FakeSMA.hook("after")
            if r:
                return ab.parse_meta(*r)
        return ab.parse_meta(0, self._text("after", max_id))


def tag_row(i, status):
    return {"id": i, "status": status, "strategyName": "DAILY_SMA20", "coinPair": "KRW-BTC", "timeframe": "H1", "initialCapital": 20200,
            "strategyParams": {"paperMaxInvestment": 20000, "paperExecutionMode": "LIVE_ALIGNED", "forwardTag": "SMA20-FWD-1"},
            "startedAt": "2026-11-10T00:00:00Z"}


def check_out(sid="900", started="2026-11-18 05:05:07.123457+00", db_now="2026-11-18 05:20:00+00", before_now="t", before="t",
              params=None, trades=("0", "0", "0", "0"), rows=1, sl="5.00"):
    p = params or {"paperMaxInvestment": 20000, "paperExecutionMode": "LIVE_ALIGNED", "forwardTag": "SMA20-FWD-1"}
    pj = '"' + json.dumps(p).replace('"', '""') + '"'
    stored = "".join(f"{sid},DAILY_SMA20,KRW-BTC,H1,RUNNING,20200.00000000,0.9950,{sl},0,{pj},{started},{before}\n" for _ in range(rows))
    return ("@@db_now\ndb_now,before_t0_now\n" + f"{db_now},{before_now}\n"
            "@@stored\nid,strategy_name,coin_pair,timeframe,status,initial_capital,invest_ratio,stop_loss_pct,max_hold_hours,params_json,started_at,before_t0\n"
            + stored + "@@trades\norders_all,orders_before_t0,positions_all,positions_before_t0\n" + ",".join(trades) + "\n@@end\n")


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="sma20-"))
        self.tok = self.tmp / "token.txt"
        self.tok.write_text(TOKEN, encoding="utf-8")
        self.out = self.tmp / "out"
        FakeSMA.hook, FakeSMA.calls, FakeSMA.db_now = None, [], "2026-11-18 05:05:07.5+00"
        self.srvs = []

    def tearDown(self):
        for s in self.srvs:
            s.srv.shutdown()
            self.assertEqual([r for r in s.reqs if r[0] != "POST"], [], "POST 외 요청 0")

    def go(self, scenario="ok", rows=None, now=NOW, execute=True, ws=WS, we=WE, t0=T0, extra=(), srv=None, body=BODY):
        if srv is None:
            srv = Srv(scenario, rows)
            self.srvs.append(srv)
        FakeSMA.srv = srv
        args = ["--base-url", srv.base, "--token-file", str(self.tok), "--body", str(body), "--out-dir", str(self.out),
                "--code-sha", "0" * 40, "--window-start", ws.isoformat(), "--window-end", we.isoformat(), "--t0", t0.isoformat(),
                "--timeout", "1", "--db-cmd", json.dumps(["psql"]), "--db-source", "대역"] + list(extra)
        if execute:
            args.append("--execute")
        buf = io.StringIO()
        with patch.object(sc, "SMA20Meta", FakeSMA), contextlib.redirect_stdout(buf):
            rc = sc.run(args, now=now)
        self.stdout = buf.getvalue()
        return rc, srv

    def journal(self):
        return ab.journal_lines(self.out / sc.JOURNAL)

    def no_token(self):
        self.assertNotIn(TOKEN, self.stdout)
        for f in self.out.rglob("*"):
            if f.is_file():
                self.assertNotIn(TOKEN, f.read_text(encoding="utf-8"))


class Create(Base):
    def test_plan_only(self):
        rc, s = self.go(execute=False)
        self.assertEqual((rc, s.posts(), FakeSMA.calls), (2, [], []))
        plan = json.loads((self.out / "plan.json").read_text(encoding="utf-8"))
        self.assertEqual((plan["t0"], plan["plan_problem"]), (T0.isoformat(), None))

    def test_ok_then_complete_and_no_repeat(self):
        rc, s = self.go()
        self.assertEqual(rc, 0, self.stdout)
        self.assertEqual(len(s.posts()), 1)
        self.assertTrue(s.posts()[0][2], "인증 헤더")
        d = json.loads((self.out / "sma20_creation.draft.json").read_text(encoding="utf-8"))
        self.assertEqual((d["session_id"], d["t0"], d["reconcile_ok"]), ("900", T0.isoformat(), True))
        self.assertEqual(d["post_started_at_raw"], "2026-11-18T05:05:07.123456789Z")
        self.assertEqual(d["db_meta_started_at"], "2026-11-18 05:05:07.123457+00")
        kinds = [r["kind"] for r in self.journal()]
        self.assertEqual(kinds, ["preflight", "preflight_meta", "intent", "result", "reconcile"])
        self.no_token()
        body = json.loads(BODY.read_text(encoding="utf-8"))
        log = cp.complete(d, body, check_out())
        self.assertEqual((log["session_id"], log["db_verified"]), ("900", True))
        # 같은 폴더 재실행 — 생성 반복 금지(기록부), 새 폴더 재실행 — forwardTag 행으로 금지
        rc2, _ = self.go(srv=s)
        self.assertEqual(rc2, 4)
        self.out = self.tmp / "out2"
        rc3, _ = self.go(srv=s)
        self.assertEqual(rc3, 2)
        self.assertIn("생성 반복 금지", self.stdout)
        self.assertEqual(len(s.posts()), 1)

    def test_complete_negatives(self):
        rc, s = self.go()
        d = json.loads((self.out / "sma20_creation.draft.json").read_text(encoding="utf-8"))
        body = json.loads(BODY.read_text(encoding="utf-8"))
        cases = {
            "1µs": check_out(started="2026-11-18 05:05:07.123458+00"),
            "other_db_no_row": check_out(rows=0),
            "check_after_t0": check_out(db_now="2026-11-19 00:00:00+00", before_now="f"),
            "order_before_t0": check_out(trades=("1", "1", "0", "0")),
            "position_any": check_out(trades=("0", "0", "1", "0")),
            "params": check_out(params={"paperMaxInvestment": 20000, "paperExecutionMode": "LIVE_ALIGNED"}),
            "stop_loss": check_out(sl=""),
            "two_rows": check_out(rows=2),
            "no_end": check_out().replace("@@end\n", ""),
        }
        for name, text in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(cp.Rejected):
                    cp.complete(d, body, text)
        bad = dict(d, reconcile_ok=False)
        with self.assertRaises(cp.Rejected):
            cp.complete(bad, body, check_out())
        with self.assertRaises(cp.Rejected):
            cp.complete(d, dict(body, initialCapital=20000), check_out())

    def test_window_and_t0_rules_send_nothing(self):
        cases = {
            "t0_shifted": dict(t0=T0 + timedelta(days=1)),
            "t0_hour": dict(t0=T0 + timedelta(hours=1)),
            "kst_0900_hour": dict(ws=datetime(2026, 11, 18, 0, 20, tzinfo=timezone.utc), we=datetime(2026, 11, 18, 0, 30, tzinfo=timezone.utc),
                                  now=datetime(2026, 11, 18, 0, 25, tzinfo=timezone.utc)),
            "too_close_to_t0": dict(ws=datetime(2026, 11, 18, 23, 40, tzinfo=timezone.utc), we=datetime(2026, 11, 18, 23, 50, tzinfo=timezone.utc),
                                    now=datetime(2026, 11, 18, 23, 45, tzinfo=timezone.utc)),
            "now_before_window": dict(now=WS - timedelta(seconds=1)),
            "now_after_window": dict(now=WE + timedelta(seconds=1)),
            "inverted": dict(ws=WE, we=WS),
        }
        for name, kw in cases.items():
            with self.subTest(name=name):
                self.out = self.tmp / ("w-" + name)
                rc, s = self.go(**kw)
                self.assertEqual((rc, s.posts()), (2, []), self.stdout)

    def test_window_boundary_0100_utc_ok(self):
        ws = datetime(2026, 11, 18, 1, 0, tzinfo=timezone.utc)
        self.assertIsNone(sc.window_problem(ws, ws + timedelta(minutes=10), T0, timedelta(minutes=30)))
        self.assertIsNotNone(sc.window_problem(ws - timedelta(seconds=1), ws + timedelta(minutes=10), T0, timedelta(minutes=30)))
        self.assertIsNone(sc.window_problem(WS, T0 - timedelta(minutes=30), T0, timedelta(minutes=30)))

    def test_body_rules(self):
        b = json.loads(BODY.read_text(encoding="utf-8"))
        for k, v in (("forwardTag", "SMA20-FWD-2"), ("paperExecutionMode", "PAPER")):
            with self.subTest(k=k):
                bb = json.loads(json.dumps(b))
                bb["strategyParams"][k] = v
                p = self.tmp / f"b-{k}.json"
                p.write_text(json.dumps(bb), encoding="utf-8")
                self.out = self.tmp / ("b-" + k)
                rc, s = self.go(body=p)
                self.assertEqual((rc, s.posts()), (2, []))

    def test_preflight_stops(self):
        running = [{"id": i, "status": "RUNNING", "strategyName": "X", "coinPair": "KRW-ETH", "timeframe": "M15", "initialCapital": 1,
                    "strategyParams": None, "startedAt": "2026-11-01T00:00:00Z"} for i in range(1, 121)]
        cases = {
            "tag_stopped": [tag_row(800, "STOPPED")],
            "tag_running": [tag_row(800, "RUNNING")],
            "tag_in_prereg_is_not_read": None,
            "cap": running,
        }
        for name, rows in cases.items():
            with self.subTest(name=name):
                self.out = self.tmp / ("p-" + name)
                if rows is None:
                    rc, s = self.go(rows=[tag_row(560, "RUNNING")])   # 522~587 행은 읽지 않으므로 중복으로 안 잡힌다 → 정상 생성
                    self.assertEqual((rc, len(s.posts())), (0, 1), self.stdout)
                    continue
                rc, s = self.go(rows=rows)
                self.assertEqual((rc, s.posts()), (2, []), self.stdout)
        self.out = self.tmp / "p-skew"
        FakeSMA.db_now = "2026-11-18 05:07:00+00"
        rc, s = self.go()
        self.assertEqual((rc, s.posts()), (2, []))
        self.assertIn("시각 차이", self.stdout)
        FakeSMA.db_now = "2026-11-18 05:05:07.5+00"
        self.out = self.tmp / "p-metafail"
        FakeSMA.hook = lambda w: (1, "") if w == "preflight" else None
        rc, s = self.go()
        self.assertEqual((rc, s.posts()), (2, []))

    def test_non_ok_responses_stop_without_resend(self):
        for scen, cls in (("auth", "AUTH"), ("http500", "HTTP_REJECTED"), ("fail", "REJECTED"), ("timeout", "UNKNOWN"),
                          ("redirect302", "UNKNOWN"), ("mismatch", "MISMATCH")):
            with self.subTest(scen=scen):
                self.out = self.tmp / ("n-" + scen)
                rc, s = self.go(scen)
                self.assertEqual(rc, 3, self.stdout)
                self.assertEqual(len(s.posts()), 1)
                j = self.journal()
                self.assertEqual([r["class"] for r in j if r["kind"] == "result"], [cls])
                rec = [r for r in j if r["kind"] == "reconcile"][-1]
                if scen in ("timeout", "mismatch"):
                    self.assertEqual(rec["provisional"], [900], "생성된 새 행은 잠정 후보로만")
                self.assertFalse((self.out / "sma20_creation.draft.json").exists())
                rc2, _ = self.go(srv=s)
                self.assertEqual((rc2, len(s.posts())), (4, 1), "재실행해도 추가 POST 0")
                self.no_token()

    def test_after_meta_failure_and_other_db(self):
        self.out = self.tmp / "a-fail"
        FakeSMA.hook = lambda w: (2, "") if w == "after" else None
        rc, s = self.go()
        self.assertEqual((rc, len(s.posts())), (3, 1))
        d = json.loads((self.out / "sma20_creation.draft.json").read_text(encoding="utf-8"))
        self.assertFalse(d["reconcile_ok"])
        self.out = self.tmp / "a-otherdb"
        FakeSMA.hook = lambda w: (0, fake_meta.meta_text([], "after", 0, db_now=FakeSMA.db_now)) if w == "after" else None
        rc, s = self.go()
        self.assertEqual(rc, 3)
        d = json.loads((self.out / "sma20_creation.draft.json").read_text(encoding="utf-8"))
        self.assertFalse(d["reconcile_ok"])

    def test_journal_lock_failures(self):
        real = ab.append

        def fail_intent(path, rec):
            if rec.get("kind") == "intent":
                raise ab.JournalError("기록부 쓰기 실패")
            return real(path, rec)
        self.out = self.tmp / "j-intent"
        with patch.object(ab, "append", fail_intent):
            rc, s = self.go()
        self.assertEqual((rc, s.posts()), (4, []))
        self.out = self.tmp / "j-lock"
        self.out.mkdir()
        (self.out / ab.LOCK_NAME).write_text("x", encoding="utf-8")
        rc, s = self.go()
        self.assertEqual((rc, s.posts()), (4, []))
        self.out = self.tmp / "j-corrupt"
        self.out.mkdir()
        (self.out / sc.JOURNAL).write_text("{broken\n", encoding="utf-8")
        rc, s = self.go()
        self.assertEqual((rc, s.posts()), (4, []))

    def test_t0_change_in_same_journal_rejected(self):
        self.out = self.tmp / "t0"
        rc, s = self.go(now=WS - timedelta(minutes=1))
        self.assertEqual(rc, 2)
        ws2, we2 = WS + timedelta(days=1), WE + timedelta(days=1)
        rc, _ = self.go(ws=ws2, we=we2, t0=T0 + timedelta(days=1), now=NOW + timedelta(days=1), srv=s)
        self.assertEqual((rc, s.posts()), (4, []))
        self.assertIn("T0", self.stdout)

    def test_db_cmd_password_rejected(self):
        self.out = self.tmp / "pw"
        srv = Srv()
        self.srvs.append(srv)
        FakeSMA.srv = srv
        args = ["--base-url", srv.base, "--token-file", str(self.tok), "--body", str(BODY), "--out-dir", str(self.out), "--code-sha", "0" * 40,
                "--window-start", WS.isoformat(), "--window-end", WE.isoformat(), "--t0", T0.isoformat(),
                "--db-cmd", json.dumps(["env", "PGPASSWORD=x", "psql"]), "--db-source", "대역", "--execute"]
        with contextlib.redirect_stdout(io.StringIO()):
            rc = sc.run(args, now=NOW)
        self.assertEqual((rc, srv.posts()), (2, []))


class Structural(unittest.TestCase):
    def test_transport_and_sources(self):
        src = (HERE / "sma20_create.py").read_text(encoding="utf-8")
        self.assertNotIn("urlopen", src)
        self.assertNotIn('"GET"', src)
        self.assertIn("ab.Client(", src)
        self.assertEqual(sc.ab.CREATOR_VERSION, "v4.2")
        for f in ("sma20_meta_preflight.sql", "sma20_check.sql"):
            sql = (HERE / f).read_text(encoding="utf-8")
            self.assertIn("NOT BETWEEN 522 AND 587", sql)
            self.assertIn("READ ONLY", sql)
            self.assertTrue(sql.rstrip().endswith("ROLLBACK;"))

    def test_token_perm_platform(self):
        p = Path(tempfile.mkdtemp()) / "t"
        p.write_text("x", encoding="utf-8")
        state, ok = sc.token_perm(p)
        if os.name == "posix":
            os.chmod(p, 0o644)
            self.assertFalse(sc.token_perm(p)[1])
            os.chmod(p, 0o600)
            self.assertTrue(sc.token_perm(p)[1])
        else:
            self.assertEqual((state, ok), ("확인 불가(POSIX 아님)", True))


if __name__ == "__main__":
    unittest.main()
