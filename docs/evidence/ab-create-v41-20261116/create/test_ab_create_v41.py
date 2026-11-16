"""ab_create.py v4.1 시험 — v3/v4 생성기 시험 22건을 v4.1 에 옮기고(목록 GET → DB 메타 조회), v4.1 경계 시험을 더한다.
실행: 이 폴더에서 python -X utf8 -B -m unittest -v test_ab_create_v41
실제 서버·DB·토큰·POST 없음. 메타 조회는 fake_meta.FakeMetaDB(출력 형식은 실제 SQL 과 같은 psql --csv + @@ 구획, parse_meta 를 그대로 통과).

옮긴 규칙: v3 의 Fake/대역이 GET 목록으로 주던 상태를 FakeMetaDB 가 같은 상태에서 SQL 의미대로 만든다. v3 의 "n 번째 GET 실패" 훅은
"n 번째 메타 조회 실패" 훅으로 바꾼다. 모든 시험에서 목록 GET 은 0 이어야 한다(대역 list_gets, 목 Fake.gets).
"""
import contextlib
import copy
import io
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

import ab_create
import fake_meta
import standin_server
from fake_meta import FakeMetaDB

HERE = Path(__file__).resolve().parent
PROTOCOL = HERE.parent / "tool" / "protocol.json"
BOUNDARY = datetime(2026, 12, 7, 0, 0, tzinfo=timezone.utc)   # 가상 시각 — 실제 생성일이 아니다
IN_WINDOW = BOUNDARY + timedelta(minutes=8)
SECRETISH = "서버 오류 원문 — 기록부에 남으면 안 됨"
DB_ARGS = ["--db-cmd", '["psql-대역"]', "--db-source", "시험 대역 메타"]
PREREG_SAME_SIG = {"id": 531, "strategyName": "COMPOSITE_MEANREV_BB", "coinPair": "KRW-ETH", "timeframe": "M15", "status": "RUNNING",
                   "initialCapital": 100000, "startedAt": "2026-11-10T00:00:00Z",
                   "strategyParams": {"paperMaxInvestment": 20000, "paperExecutionMode": "LIVE_ALIGNED"}}


def _strip_last_tz(t: str) -> str:
    i = t.rfind("+00\n")
    return t[:i] + "\n" + t[i + 4:]


class Create(unittest.TestCase):
    """A. 대역 HTTP 서버 + 대역 메타(같은 상태)."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="abcreate41-"))
        self.token_file = self.tmp / "token.txt"
        self.token_file.write_text(standin_server.TOKEN, encoding="utf-8")
        self.out = self.tmp / "out"

    def go(self, scenario, now=IN_WINDOW, execute=True, state=None, extra=()):
        srv, st = (None, state) if state else standin_server.start(scenario, now)
        if not state:
            FakeMetaDB.reset(lambda: [dict(r) for r in st.sessions])
        try:
            base = f"http://127.0.0.1:{(srv or self._srv).server_address[1]}"
            args = ["--base-url", base, "--token-file", str(self.token_file), "--protocol", str(PROTOCOL),
                    "--out-dir", str(self.out), "--code-sha", "0" * 40, "--timeout", "1"] + DB_ARGS + list(extra)
            if execute:
                args.append("--execute")
            buf = io.StringIO()
            with patch.object(ab_create, "MetaDB", FakeMetaDB), contextlib.redirect_stdout(buf):
                rc = ab_create.run(args, now=now)
            self.stdout = buf.getvalue()
            return rc, st
        finally:
            if srv and not state:
                self._srv = srv

    def tearDown(self):
        if getattr(self, "_srv", None):
            self.assertEqual(self._st_gets(), 0, "전체 목록 GET 은 0 이어야 한다")
            self._srv.shutdown()

    def _st_gets(self):
        return getattr(self, "_last_state", None).list_gets if getattr(self, "_last_state", None) else 0

    def journal(self):
        return ab_create.journal_lines(self.out / "journal.jsonl")

    def results(self):
        return [(r["pair_arm"], r["class"]) for r in self.journal() if r["kind"] == "result"]

    def last_reconcile(self):
        return [r for r in self.journal() if r["kind"] == "reconcile"][-1]

    def assert_no_token_anywhere(self, st):
        self.assertNotIn(standin_server.TOKEN, self.stdout)
        for f in self.out.rglob("*"):
            if f.is_file():
                self.assertNotIn(standin_server.TOKEN, f.read_text(encoding="utf-8"), str(f))
        self.assertTrue(st.auth_headers)
        self.assertTrue(all(h == "Bearer " + standin_server.TOKEN for h in st.auth_headers))

    def test_plan_only_sends_nothing(self):
        rc, st = self.go("ok", execute=False)
        self._last_state = st
        self.assertEqual(rc, 2)
        self.assertEqual(st.posts, 0)
        self.assertEqual(st.auth_headers, [])
        self.assertEqual(FakeMetaDB.calls, [], "계획만이면 DB 도 읽지 않는다")
        plan = json.loads((self.out / "plan.json").read_text(encoding="utf-8"))["bodies"]
        self.assertEqual(len(plan), 8)
        for p in plan:
            b = p["body"]
            self.assertEqual(b["strategyParams"], {"paperMaxInvestment": 20000, "paperExecutionMode": "LIVE_ALIGNED"})
            self.assertEqual((b["initialCapital"], b["investRatio"], b["maxHoldHours"], b["timeframe"]), (100000, 0.8, 24, "M15"))
            if p["arm"] == "A":
                self.assertNotIn("stopLossPct", b)
            else:
                self.assertEqual(b["stopLossPct"], 5)

    def test_all_ok(self):
        rc, st = self.go("ok")
        self._last_state = st
        self.assertEqual(rc, 0, self.stdout)
        self.assertEqual(st.posts, 8)
        self.assertEqual(st.list_gets, 0)
        self.assertEqual([c for _, c in self.results()], ["OK"] * 8)
        rec = self.last_reconcile()
        self.assertEqual(rec["source"], "DB 메타 조회")
        self.assertTrue(all(len(v) == 1 for v in rec["by_arm"].values()))
        self.assertEqual((rec["unexpected"], rec["provisional"]), ({}, {}))
        pre = [r for r in self.journal() if r["kind"] == "preflight_meta"][0]
        self.assertEqual(pre["duplicates"], [], "522~587 의 같은 서명 RUNNING(530)은 중복 후보로 읽지 않는다")
        self.assertEqual(pre["running_total"], 2, "전체 RUNNING 수에는 사전 등록 행도 개수로 들어간다(530·600)")
        self.assertEqual(pre["max_id"], 600)
        draft = json.loads((self.out / "creation_log.draft.json").read_text(encoding="utf-8"))
        self.assertEqual(draft["t0"], (BOUNDARY + timedelta(minutes=15)).isoformat())
        self.assertEqual(draft["tool_version"], "v4.1")
        self.assertFalse(draft["db_verified"])
        self.assertTrue(draft["reconcile_ok"])
        self.assertTrue(draft["reconcile_source"].startswith("DB 메타 조회"))
        self.assertEqual(draft["db_source"], "시험 대역 메타")
        self.assertNotIn("api_reconcile_ok", draft)
        for s in draft["sessions"]:
            self.assertNotIn("api_stored_started_at", s)
            self.assertTrue(s["db_meta_started_at"].endswith("+00"))
        self.assertEqual(draft["checks"], {"reconcile_problems": [], "created_at_not_before_t0": [], "precision_contract_failed": [],
                                           "other_new_ids": []})
        self.assertFalse((self.out / ab_create.LOCK_NAME).exists(), "정상 종료면 잠금을 푼다")
        self.assert_no_token_anywhere(st)
        self.assertEqual([w for w, _ in FakeMetaDB.calls], ["preflight", "after"])
        self.assertEqual(FakeMetaDB.calls[1][1], 600)

    def test_outside_window_sends_nothing(self):
        for now in (BOUNDARY + timedelta(minutes=5, seconds=59), BOUNDARY + timedelta(minutes=12, seconds=1)):
            with self.subTest(str(now)):
                self.out = self.tmp / ("out-" + now.strftime("%H%M%S"))
                rc, st = self.go("ok", now=now)
                self._last_state = st
                self.assertEqual(rc, 2)
                self.assertEqual(st.posts, 0)
                self.assertEqual(FakeMetaDB.calls, [], "창 밖이면 DB 도 읽지 않는다")

    def _stop_case(self, scenario, k, cls, created_at_k):
        rc, st = self.go(scenario)
        self._last_state = st
        self.assertEqual(rc, 3, self.stdout)
        self.assertEqual(st.posts, k, "멈춘 뒤 다시 보내거나 남은 본문을 보내지 않는다")
        self.assertEqual(st.list_gets, 0, "실패·불명 경로에서도 목록 GET 0")
        res = self.results()
        self.assertEqual([c for _, c in res], ["OK"] * (k - 1) + [cls])
        rec = self.last_reconcile()
        stopped = res[-1][0]
        self.assertEqual(rec["by_arm"][stopped], [], "멈춘 팔은 귀속하지 않는다")
        self.assertEqual(len(rec["provisional"].get(stopped, [])), 1 if created_at_k else 0, rec)
        for arm, _ in res[:-1]:
            self.assertEqual(len(rec["by_arm"][arm]), 1)
        self.assert_no_token_anywhere(st)
        self.assertNotIn("대역", (self.out / "journal.jsonl").read_text(encoding="utf-8").replace("시험 대역 메타", ""),
                         "서버 오류 원문을 남기지 않는다")
        before = st.posts
        srv = self._srv
        rc2, _ = self.go(scenario, state=st)
        self.assertEqual(rc2, 4, self.stdout)
        self.assertEqual(st.posts, before)
        self._srv = srv

    def test_401_stops(self):
        self._stop_case("auth_at:3", 3, "AUTH", created_at_k=False)

    def test_500_stops(self):
        self._stop_case("http500_at:2", 2, "HTTP_REJECTED", created_at_k=False)

    def test_success_false_with_http_200_stops(self):
        self._stop_case("fail_at:4", 4, "REJECTED", created_at_k=False)

    def test_unknown_response_but_created_is_provisional_only_and_not_resent(self):
        self._stop_case("timeout_at:5", 5, "UNKNOWN", created_at_k=True)

    def test_stored_value_mismatch_stops(self):
        self._stop_case("mismatch_at:6", 6, "MISMATCH", created_at_k=True)

    def test_preexisting_same_signature_sends_nothing(self):
        rc, st = self.go("dup_preexisting")
        self._last_state = st
        self.assertEqual(rc, 2, self.stdout)
        self.assertEqual(st.posts, 0)
        self.assertIn("P2A", self.stdout)

    # v4.1
    def test_v41_session_cap(self):
        """RUNNING(2, 사전 등록 포함) + 8 > 상한이면 보내지 않는다. 같으면 보낸다."""
        rc, st = self.go("ok", extra=["--session-cap", "9"])
        self._last_state = st
        self.assertEqual(rc, 2, self.stdout)
        self.assertEqual(st.posts, 0)
        self.out = self.tmp / "out-cap10"
        rc, st = self.go("ok", extra=["--session-cap", "10"])
        self._last_state = st
        self.assertEqual(rc, 0, self.stdout)
        self.assertEqual(st.posts, 8)


# ── B. 목 클라이언트 ────────────────────────────────────────────────────────────

class Fake:
    """Client 대역 — POST 만 받는다(GET 이 오면 센다·실패). 실제 HTTP·서버·DB·토큰 사용 0."""
    last = None
    cfg: dict = {}

    def __init__(self, *a, **kw):
        self.posts = 0
        self.gets = 0
        self.rows = [dict(PREREG_SAME_SIG)]
        Fake.last = self

    def call(self, method, path, body=None):
        cfg = Fake.cfg
        if method == "GET":
            self.gets += 1
            raise AssertionError("v4.1 에서 GET 은 오면 안 된다")
        self.posts += 1
        hook = cfg.get("post")
        if hook:
            r = hook(self, self.posts, body)
            if r is not None:
                return r
        d = {"id": 1000 + self.posts, "strategyName": body["strategyType"], "coinPair": body["coinPair"], "timeframe": body["timeframe"],
             "initialCapital": body["initialCapital"], "strategyParams": body["strategyParams"], "status": "RUNNING",
             "startedAt": (IN_WINDOW + timedelta(seconds=self.posts)).isoformat()}
        self.rows.append(d)
        return 200, {"success": True, "data": d}, None


class CreateMock(unittest.TestCase):
    """B. 목 클라이언트 + 대역 메타 — 총괄 C1·C2 와 v1.149 §4-1, v4.1 메타 조회 실패 경로."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="abcreate41-mock-"))
        self.token = self.tmp / "token.txt"
        self.token.write_text("LOCAL_FAKE", encoding="utf-8")
        self.out = self.tmp / "out"
        self.out.mkdir()
        Fake.cfg = {}
        self.meta_hook = None

    def run_tool(self, clock=None, now=IN_WINDOW, code_sha="b" * 40, protocol=PROTOCOL, db_args=DB_ARGS):
        args = ["--base-url", "http://invalid.invalid", "--token-file", str(self.token), "--protocol", str(protocol),
                "--out-dir", str(self.out), "--code-sha", code_sha, "--execute"] + list(db_args)
        buf = io.StringIO()
        Fake.last = None
        FakeMetaDB.reset(lambda: [dict(r) for r in (Fake.last.rows if Fake.last else [PREREG_SAME_SIG])])
        FakeMetaDB.hook = self.meta_hook
        with patch.object(ab_create, "Client", Fake), patch.object(ab_create, "MetaDB", FakeMetaDB), contextlib.redirect_stdout(buf):
            rc = ab_create.run(args, now=now, clock=clock)
        self.stdout = buf.getvalue()
        if Fake.last is not None:
            self.assertEqual(Fake.last.gets, 0, "목록 GET 0")
        return rc, Fake.last

    def journal(self):
        return ab_create.journal_lines(self.out / "journal.jsonl")

    def draft(self):
        return json.loads((self.out / "creation_log.draft.json").read_text(encoding="utf-8"))

    def posts(self, client):
        return 0 if client is None else client.posts

    # C1 (v4.1: 사후 메타 조회 실패)
    def test_c1_reconcile_failure_after_8_ok_is_not_success(self):
        self.meta_hook = lambda n, which, mx: (2, SECRETISH) if n == 2 else None
        rc, c = self.run_tool()
        self.assertEqual(rc, 3, self.stdout)
        self.assertEqual(c.posts, 8)
        d = self.draft()
        self.assertFalse(d["reconcile_ok"])
        self.assertFalse(d["db_verified"])
        self.assertIn("DB 메타 조회 실패(종료 2)", d["checks"]["reconcile_problems"])
        self.assertNotIn(SECRETISH, (self.out / "journal.jsonl").read_text(encoding="utf-8") + self.stdout)

    def test_c1_malformed_or_incomplete_reconcile_meta(self):
        def rows_now():
            return [dict(r) for r in Fake.last.rows]
        cases = {
            "실행 불가(시간 초과·psql 없음)": lambda n, w, mx: (None, "") if n == 2 else None,
            "빈 출력": lambda n, w, mx: (0, "") if n == 2 else None,
            "끝 표지 없음": lambda n, w, mx: (0, fake_meta.meta_text(rows_now(), w, mx).replace("@@end\n", "")) if n == 2 else None,
            "구획 순서 이상": lambda n, w, mx: (0, fake_meta.meta_text(rows_now(), w, mx).replace("@@counts", "@@x")) if n == 2 else None,
            "행 열 이름 다름": lambda n, w, mx: (0, fake_meta.meta_text(rows_now(), w, mx).replace("params_json", "pnl")) if n == 2 else None,
            "startedAt 형식 이상(시간대 없음)": lambda n, w, mx: (0, _strip_last_tz(fake_meta.meta_text(rows_now(), w, mx))) if n == 2 else None,
            "db_now 시간대 없음": lambda n, w, mx: (0, fake_meta.meta_text(rows_now(), w, mx).replace("+00\n", "\n", 1)) if n == 2 else None,
            "한 팔이 조회에 없음": lambda n, w, mx: (0, fake_meta.meta_text(rows_now()[:-1], w, mx)) if n == 2 else None,
            "예상 밖 같은 서명 세션": lambda n, w, mx: (0, fake_meta.meta_text(rows_now() + [dict(rows_now()[1], id=5000)], w, mx)) if n == 2 else None,
            "522~587 행이 섞임": lambda n, w, mx: (0, fake_meta.meta_text(rows_now(), w, mx).replace("\n1001,", "\n540,", 1)) if n == 2 else None,
        }
        for name, hook in cases.items():
            with self.subTest(name):
                self.out = self.tmp / ("out-" + str(abs(hash(name))))
                self.out.mkdir()
                self.meta_hook = hook
                rc, c = self.run_tool()
                self.assertEqual(rc, 3, name + " " + self.stdout)
                self.assertEqual(c.posts, 8)
                self.assertFalse(self.draft()["reconcile_ok"])

    def test_v41_preflight_meta_failure_sends_nothing(self):
        cases = {
            "종료 코드": lambda n, w, mx: (3, "") if n == 1 else None,
            "실행 불가": lambda n, w, mx: (None, "") if n == 1 else None,
            "빈 결과(구획 없음)": lambda n, w, mx: (0, "") if n == 1 else None,
            "집계 행 없음": lambda n, w, mx: (0, "@@db_now\ndb_now\n2026-12-07 00:08:00+00\n@@counts\nrunning_total,max_id\n@@rows\n"
                                                 + ",".join(ab_create.META_ROW_KEYS) + "\n@@end\n") if n == 1 else None,
        }
        for name, hook in cases.items():
            with self.subTest(name):
                self.out = self.tmp / ("pre-" + str(abs(hash(name))))
                self.out.mkdir()
                self.meta_hook = hook
                rc, c = self.run_tool()
                self.assertEqual(rc, 2, name + " " + self.stdout)
                self.assertEqual(self.posts(c), 0)
                self.assertFalse((self.out / "creation_log.draft.json").exists())

    def test_v41_bad_db_args_send_nothing(self):
        for name, args in {"db-cmd 없음": ["--db-source", "x"], "db-source 없음": ["--db-cmd", '["psql"]'],
                           "비밀번호 인자": ["--db-cmd", '["psql","password=abc"]', "--db-source", "x"],
                           "PGPASSWORD": ["--db-cmd", '["env","PGPASSWORD=abc","psql"]', "--db-source", "x"],
                           "JSON 아님": ["--db-cmd", "psql -d x", "--db-source", "x"]}.items():
            with self.subTest(name):
                self.out = self.tmp / ("arg-" + str(abs(hash(name))))
                self.out.mkdir()
                try:
                    rc, c = self.run_tool(db_args=args)
                except json.JSONDecodeError:
                    self.fail("JSON 아님은 고정 거절이어야 한다")
                self.assertEqual(rc, 2, name)
                self.assertIsNone(c)
                self.assertEqual(FakeMetaDB.calls, [])
                self.assertNotIn("abc", self.stdout)

    def test_v41_new_id_must_exceed_preflight_max(self):
        """POST 응답 id 가 사전 max_id 이하(예: 다른 DB 의 ID 체계)면 응답 불명 — 더 보내지 않는다."""
        def post(c, n, body):
            if n == 2:
                d = {"id": 900, "strategyName": body["strategyType"], "coinPair": body["coinPair"], "timeframe": body["timeframe"],
                     "initialCapital": body["initialCapital"], "strategyParams": body["strategyParams"], "status": "RUNNING",
                     "startedAt": (IN_WINDOW + timedelta(seconds=n)).isoformat()}
                return 200, {"success": True, "data": d}, None
            return None
        Fake.cfg = {"post": post}
        # 1회차 시험 결함: 950 행을 P2 와 같은 서명으로 만들어 사전 중복으로 먼저 멈췄다(rc2). 서명이 다른 행으로 고쳤다.
        self.meta_hook = lambda n, w, mx: (0, fake_meta.meta_text([dict(PREREG_SAME_SIG), dict(PREREG_SAME_SIG, id=950, coinPair="KRW-XRP")], w, mx)) if n == 1 else None
        rc, c = self.run_tool()
        self.assertEqual(rc, 3, self.stdout)
        self.assertEqual(c.posts, 2)
        res = [r for r in self.journal() if r["kind"] == "result"]
        self.assertEqual(res[-1]["class"], "UNKNOWN")

    def test_v41_prereg_id_response_is_unknown(self):
        def post(c, n, body):
            if n == 1:
                d = {"id": 560, "strategyName": body["strategyType"], "coinPair": body["coinPair"], "timeframe": body["timeframe"],
                     "initialCapital": body["initialCapital"], "strategyParams": body["strategyParams"], "status": "RUNNING",
                     "startedAt": IN_WINDOW.isoformat()}
                return 200, {"success": True, "data": d}, None
            return None
        Fake.cfg = {"post": post}
        self.meta_hook = lambda n, w, mx: (0, fake_meta.meta_text([], w, mx)) if n == 1 else None
        rc, c = self.run_tool()
        self.assertEqual(rc, 3)
        self.assertEqual(c.posts, 1)

    def test_v41_other_db_meta_fails_reconcile(self):
        """생성기의 메타 DB 가 API 서버의 DB 와 다르다(같은 ID 없음/다른 시각) → 대조 실패, 추가 POST 0."""
        other = [dict(PREREG_SAME_SIG)] + [{"id": 1000 + i, "strategyName": "X", "coinPair": "KRW-XRP", "timeframe": "H1", "status": "RUNNING",
                                             "initialCapital": 5, "strategyParams": None, "startedAt": "2026-10-01T00:00:00Z"} for i in range(1, 9)]
        self.meta_hook = lambda n, w, mx: (0, fake_meta.meta_text(other, w, mx)) if n == 2 else None
        rc, c = self.run_tool()
        self.assertEqual(rc, 3, self.stdout)
        self.assertEqual(c.posts, 8)
        d = self.draft()
        self.assertFalse(d["reconcile_ok"])
        self.assertEqual(sorted(d["checks"]["precision_contract_failed"]), sorted(ab_create.ARM_KEYS))

    def test_v41_concurrent_other_creation_recorded_not_attributed(self):
        """같은 때 다른 생성(서명 다름)은 other_new_ids 로 남고 대조 성공을 막지 않는다. 같은 서명이면 예상 밖으로 실패."""
        def post(c, n, body):
            if n == 4:
                c.rows.append({"id": 1500, "strategyName": "COMPOSITE_MTF_MOMENTUM", "coinPair": "KRW-XRP", "timeframe": "H1",
                               "status": "RUNNING", "initialCapital": 100000, "strategyParams": {"paperExecutionMode": "LIVE_ALIGNED"},
                               "startedAt": IN_WINDOW.isoformat()})
            return None
        Fake.cfg = {"post": post}
        rc, c = self.run_tool()
        self.assertEqual(rc, 0, self.stdout)
        self.assertEqual(self.draft()["checks"]["other_new_ids"], [1500])

    # C2
    def test_c2_unresolved_intent_blocks_all_sends(self):
        ab_create.append(self.out / "journal.jsonl", {"kind": "intent", "pair_arm": "P1A",
                                                       "body_sha256": ab_create.bodies_from_protocol(ab_create.load_protocol(PROTOCOL)[0])[0]["body_sha256"]})
        rc, c = self.run_tool()
        self.assertEqual(rc, 4, self.stdout)
        self.assertIsNone(c, "토큰을 읽고 클라이언트를 만들기 전에 멈춘다")
        self.assertIn("결과 없는 전송 의도", self.stdout)
        self.assertEqual(FakeMetaDB.calls, [])

    def test_c2_overseer_form_unknown_body_sha_also_blocks(self):
        ab_create.append(self.out / "journal.jsonl", {"kind": "intent", "pair_arm": "P1A", "body_sha256": "unresolved-prior-send"})
        rc, c = self.run_tool()
        self.assertEqual(rc, 4, self.stdout)
        self.assertIsNone(c)

    def test_crash_after_post_before_result_write_then_rerun_sends_nothing(self):
        real = ab_create.append

        def failing(path, rec):
            if rec.get("kind") == "result" and rec.get("pair_arm") == "P2A":
                raise ab_create.JournalError("주입 — 결과 기록 실패")
            return real(path, rec)
        with patch.object(ab_create, "append", failing):
            rc, c = self.run_tool()
        self.assertEqual(rc, 4, self.stdout)
        self.assertEqual(c.posts, 3, "결과 기록 실패 뒤 더 보내지 않는다")
        self.assertFalse((self.out / ab_create.LOCK_NAME).exists())
        rc2, c2 = self.run_tool()
        self.assertEqual(rc2, 4, self.stdout)
        self.assertIsNone(c2)
        self.assertIn("P2A", self.stdout)

    def test_intent_write_failure_sends_nothing_for_that_body(self):
        real = ab_create.append

        def failing(path, rec):
            if rec.get("kind") == "intent" and rec.get("pair_arm") == "P1B":
                raise ab_create.JournalError("주입 — 의도 기록 실패")
            return real(path, rec)
        with patch.object(ab_create, "append", failing):
            rc, c = self.run_tool()
        self.assertEqual(rc, 4)
        self.assertEqual(c.posts, 1, "의도를 못 적은 본문은 보내지 않는다")

    def test_killed_process_leaves_lock_and_intent(self):
        plan = ab_create.bodies_from_protocol(ab_create.load_protocol(PROTOCOL)[0])
        ab_create.append(self.out / "journal.jsonl", {"kind": "intent", "pair_arm": "P1A", "body_sha256": plan[0]["body_sha256"]})
        (self.out / ab_create.LOCK_NAME).write_text("{}", encoding="utf-8")
        rc, c = self.run_tool()
        self.assertEqual(rc, 4)
        self.assertIsNone(c)
        self.assertIn("잠금", self.stdout)
        (self.out / ab_create.LOCK_NAME).unlink()
        rc2, c2 = self.run_tool()
        self.assertEqual(rc2, 4)
        self.assertIsNone(c2)

    def test_concurrent_run_lock(self):
        self.assertTrue(ab_create.acquire_lock(self.out))
        rc, c = self.run_tool()
        self.assertEqual(rc, 4)
        self.assertIsNone(c)
        ab_create.release_lock(self.out)

    def test_corrupt_or_foreign_journal_rejected_before_send(self):
        cases = {
            "깨진 줄": "{\"kind\": \"preflight\"\nnot json\n",
            "마지막 줄 미완(쓰는 중 종료)": json.dumps({"kind": "preflight"}),
            "kind 없음": json.dumps({"x": 1}) + "\n",
            "다른 프로토콜": json.dumps({"kind": "preflight", "protocol_sha256": "0" * 64}) + "\n",
            "다른 대상 코드": json.dumps({"kind": "preflight", "code_sha": "c" * 40}) + "\n",
            "다른 본문 계획": json.dumps({"kind": "preflight", "plan_sha256": "d" * 64}) + "\n",
            "본문이 다른 의도+결과": json.dumps({"kind": "intent", "pair_arm": "P1A", "body_sha256": "e" * 64}) + "\n"
                                 + json.dumps({"kind": "result", "pair_arm": "P1A", "class": "OK", "id": 1}) + "\n",
        }
        for name, text in cases.items():
            with self.subTest(name):
                self.out = self.tmp / ("j-" + str(abs(hash(name))))
                self.out.mkdir()
                (self.out / "journal.jsonl").write_text(text, encoding="utf-8")
                rc, c = self.run_tool()
                self.assertEqual(rc, 4, name + " " + self.stdout)
                self.assertIsNone(c)

    def test_window_only_preflight_journal_can_rerun(self):
        rc, c = self.run_tool(now=BOUNDARY + timedelta(minutes=3))
        self.assertEqual(rc, 2)
        self.assertEqual(self.posts(c), 0)
        rc2, c2 = self.run_tool(now=IN_WINDOW)
        self.assertEqual(rc2, 0, self.stdout)
        self.assertEqual(c2.posts, 8)

    def test_malformed_post_responses_are_fixed_unknown(self):
        cases = {
            "data 가 객체 아님": (200, {"success": True, "data": "ok"}, None),
            "success 값 이상": (200, {"success": "yes", "data": {}}, None),
            "봉투 아님": (200, ["x"], None),
            "id 가 bool": (200, {"success": True, "data": {"id": True}}, None),
            "startedAt 없음": None,
        }
        for name, resp in cases.items():
            with self.subTest(name):
                self.out = self.tmp / ("p-" + str(abs(hash(name))))
                self.out.mkdir()

                def post(c, n, body, resp=resp, name=name):
                    if n != 2:
                        return None
                    if name == "startedAt 없음":
                        d = {"id": 1000 + n, "strategyName": body["strategyType"], "coinPair": body["coinPair"],
                             "timeframe": body["timeframe"], "initialCapital": body["initialCapital"],
                             "strategyParams": body["strategyParams"], "status": "RUNNING", "error": SECRETISH}
                        return 200, {"success": True, "data": d}, None
                    return resp
                Fake.cfg = {"post": post}
                rc, c = self.run_tool()
                self.assertEqual(rc, 3, name)
                self.assertEqual(c.posts, 2)
                res = [r for r in self.journal() if r["kind"] == "result"]
                self.assertEqual(res[-1]["class"], "UNKNOWN")
                self.assertNotIn(SECRETISH, (self.out / "journal.jsonl").read_text(encoding="utf-8") + self.stdout)

    def test_window_rechecked_before_each_request(self):
        ticks = iter([BOUNDARY + timedelta(minutes=11, seconds=58)] * 4 + [BOUNDARY + timedelta(minutes=12, seconds=1)] * 20)
        rc, c = self.run_tool(clock=lambda: next(ticks))
        self.assertEqual(rc, 3, self.stdout)
        self.assertEqual(c.posts, 3)
        kinds = [r["kind"] for r in self.journal()]
        self.assertIn("window_closed", kinds)
        self.assertFalse((self.out / "creation_log.draft.json").exists())

    def test_unknown_then_list_candidate_is_provisional_not_attributed(self):
        def post(c, n, body):
            if n == 3:
                d = {"id": 1000 + n, "strategyName": body["strategyType"], "coinPair": body["coinPair"], "timeframe": body["timeframe"],
                     "initialCapital": body["initialCapital"], "strategyParams": body["strategyParams"], "status": "RUNNING",
                     "startedAt": (IN_WINDOW + timedelta(seconds=n)).isoformat()}
                c.rows.append(d)
                return None, None, "TimeoutError"
            return None
        Fake.cfg = {"post": post}
        rc, c = self.run_tool()
        self.assertEqual(rc, 3)
        rec = [r for r in self.journal() if r["kind"] == "reconcile"][-1]
        self.assertEqual(rec["by_arm"]["P2A"], [])
        self.assertEqual(rec["provisional"], {"P2A": [1003]})


class Structural(unittest.TestCase):
    def test_client_refuses_get(self):
        c = ab_create.Client("http://invalid.invalid", "x", 0.1)
        with self.assertRaises(RuntimeError):
            c.call("GET", ab_create.ENDPOINT)
        with self.assertRaises(RuntimeError):
            c.call("POST", "/api/v1/paper-trading/sessions/1/stop")

    def test_no_list_request_in_source(self):
        src = (HERE / "ab_create.py").read_text(encoding="utf-8")
        self.assertNotIn('call("GET"', src)
        self.assertNotIn("parse_list(", src)


if __name__ == "__main__":
    unittest.main()
