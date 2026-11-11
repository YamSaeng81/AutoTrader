"""ab_create.py v3 시험 — 실제 서버·토큰·POST 없음. 실행: 이 폴더에서 python -X utf8 -m unittest -v test_ab_create

A. 대역 HTTP 서버(standin_server.py, v2 와 같은 시나리오): 계획만, 정상 8개, 창 밖, 401·500·success=false·응답 불명·저장값 불일치에서
   즉시 멈춤과 재전송 0, 재실행 거부, 같은 서명 선존재. v3 에서 바뀐 기대: 응답 불명 뒤 새 ID 는 by_arm 이 아니라 **잠정 후보**다.
B. 목 클라이언트(총괄 review.py 와 같은 방식 — Client 만 바꿈): 총괄 관측 C1(사후 목록 실패에도 종료 0)·C2(결과 없는 의도가 있어도 8개 전송)
   와 v1.149 §4-1 의 기록 실패·잠금·파손 기록부·다른 프로토콜/코드·형식 이상 응답·요청 직전 창 재확인을 실패 시험으로 고정한다.
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
import standin_server

HERE = Path(__file__).resolve().parent
PROTOCOL = HERE.parent / "tool" / "protocol.json"
BOUNDARY = datetime(2026, 12, 7, 0, 0, tzinfo=timezone.utc)   # 가상 시각 — 실제 생성일이 아니다
IN_WINDOW = BOUNDARY + timedelta(minutes=8)
SECRETISH = "서버 오류 원문 — 기록부에 남으면 안 됨"


class Create(unittest.TestCase):
    """A. 대역 HTTP 서버."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="abcreate-"))
        self.token_file = self.tmp / "token.txt"
        self.token_file.write_text(standin_server.TOKEN, encoding="utf-8")
        self.out = self.tmp / "out"

    def go(self, scenario, now=IN_WINDOW, execute=True, state=None):
        srv, st = (None, state) if state else standin_server.start(scenario, now)
        try:
            base = f"http://127.0.0.1:{(srv or self._srv).server_address[1]}"
            args = ["--base-url", base, "--token-file", str(self.token_file), "--protocol", str(PROTOCOL),
                    "--out-dir", str(self.out), "--code-sha", "0" * 40, "--timeout", "1"]
            if execute:
                args.append("--execute")
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = ab_create.run(args, now=now)
            self.stdout = buf.getvalue()
            return rc, st
        finally:
            if srv and not state:
                self._srv = srv

    def tearDown(self):
        if getattr(self, "_srv", None):
            self._srv.shutdown()

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
        self.assertEqual(rc, 2)
        self.assertEqual(st.posts, 0)
        self.assertEqual(st.auth_headers, [])
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
        self.assertEqual(rc, 0, self.stdout)
        self.assertEqual(st.posts, 8)
        self.assertEqual([c for _, c in self.results()], ["OK"] * 8)
        rec = self.last_reconcile()
        self.assertTrue(all(len(v) == 1 for v in rec["by_arm"].values()))
        self.assertEqual((rec["unexpected"], rec["provisional"]), ({}, {}))
        draft = json.loads((self.out / "creation_log.draft.json").read_text(encoding="utf-8"))
        self.assertEqual(draft["t0"], (BOUNDARY + timedelta(minutes=15)).isoformat())
        self.assertFalse(draft["db_verified"])
        self.assertTrue(draft["api_reconcile_ok"])
        self.assertEqual(draft["checks"], {"api_reconcile_problems": [], "created_at_not_before_t0": [], "precision_contract_failed": []})
        self.assertFalse((self.out / ab_create.LOCK_NAME).exists(), "정상 종료면 잠금을 푼다")
        self.assert_no_token_anywhere(st)

    def test_outside_window_sends_nothing(self):
        for now in (BOUNDARY + timedelta(minutes=5, seconds=59), BOUNDARY + timedelta(minutes=12, seconds=1)):
            with self.subTest(str(now)):
                self.out = self.tmp / ("out-" + now.strftime("%H%M%S"))
                rc, st = self.go("ok", now=now)
                self.assertEqual(rc, 2)
                self.assertEqual(st.posts, 0)

    def _stop_case(self, scenario, k, cls, created_at_k):
        rc, st = self.go(scenario)
        self.assertEqual(rc, 3, self.stdout)
        self.assertEqual(st.posts, k, "멈춘 뒤 다시 보내거나 남은 본문을 보내지 않는다")
        res = self.results()
        self.assertEqual([c for _, c in res], ["OK"] * (k - 1) + [cls])
        rec = self.last_reconcile()
        stopped = res[-1][0]
        if cls == "MISMATCH":
            # MISMATCH 는 응답 id 가 있으나 OK 가 아니다 — 귀속하지 않고 잠정 후보로만 남긴다.
            pass
        self.assertEqual(rec["by_arm"][stopped], [], "멈춘 팔은 귀속하지 않는다")
        self.assertEqual(len(rec["provisional"].get(stopped, [])), 1 if created_at_k else 0, rec)
        for arm, _ in res[:-1]:
            self.assertEqual(len(rec["by_arm"][arm]), 1)
        self.assert_no_token_anywhere(st)
        self.assertNotIn("대역", (self.out / "journal.jsonl").read_text(encoding="utf-8"), "서버 오류 원문을 남기지 않는다")
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
        self.assertEqual(rc, 2, self.stdout)
        self.assertEqual(st.posts, 0)
        self.assertIn("P2A", self.stdout)


# ── B. 목 클라이언트 ────────────────────────────────────────────────────────────

class Fake:
    """Client 대역 — 호출 수만 센다. 실제 HTTP·서버·DB·토큰 사용 0."""
    last = None
    cfg: dict = {}

    def __init__(self, *a, **kw):
        self.posts = 0
        self.gets = 0
        self.rows = []
        Fake.last = self

    def call(self, method, path, body=None):
        cfg = Fake.cfg
        if method == "GET":
            self.gets += 1
            hook = cfg.get("get")
            if hook:
                r = hook(self, self.gets)
                if r is not None:
                    return r
            return 200, {"success": True, "data": copy.deepcopy(self.rows)}, None
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
    """B. 목 클라이언트 — 총괄 C1·C2 와 v1.149 §4-1."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="abcreate-mock-"))
        self.token = self.tmp / "token.txt"
        self.token.write_text("LOCAL_FAKE", encoding="utf-8")
        self.out = self.tmp / "out"
        self.out.mkdir()
        Fake.cfg = {}

    def run_tool(self, clock=None, now=IN_WINDOW, code_sha="b" * 40, protocol=PROTOCOL):
        args = ["--base-url", "http://invalid.invalid", "--token-file", str(self.token), "--protocol", str(protocol),
                "--out-dir", str(self.out), "--code-sha", code_sha, "--execute"]
        buf = io.StringIO()
        Fake.last = None
        with patch.object(ab_create, "Client", Fake), contextlib.redirect_stdout(buf):
            rc = ab_create.run(args, now=now, clock=clock)
        self.stdout = buf.getvalue()
        return rc, Fake.last

    def journal(self):
        return ab_create.journal_lines(self.out / "journal.jsonl")

    def draft(self):
        return json.loads((self.out / "creation_log.draft.json").read_text(encoding="utf-8"))

    def posts(self, client):
        return 0 if client is None else client.posts

    # C1
    def test_c1_reconcile_failure_after_8_ok_is_not_success(self):
        Fake.cfg = {"get": lambda c, n: (503, {"success": False, "error": {"message": SECRETISH}}, None) if n == 2 else None}
        rc, c = self.run_tool()
        self.assertEqual(rc, 3, self.stdout)
        self.assertEqual(c.posts, 8)
        d = self.draft()
        self.assertFalse(d["api_reconcile_ok"])
        self.assertFalse(d["db_verified"])
        self.assertIn("목록 조회 실패(HTTP 503)", d["checks"]["api_reconcile_problems"])
        self.assertNotIn(SECRETISH, (self.out / "journal.jsonl").read_text(encoding="utf-8") + self.stdout)

    def test_c1_malformed_or_incomplete_reconcile_list(self):
        cases = {
            "data 가 목록 아님": lambda c, n: (200, {"success": True, "data": {"rows": []}}, None) if n == 2 else None,
            "행에 id 없음": lambda c, n: (200, {"success": True, "data": [{"status": "RUNNING"}]}, None) if n == 2 else None,
            "startedAt 형식 이상": lambda c, n: (200, {"success": True, "data": [dict(c.rows[0], startedAt="어제")]}, None) if n == 2 else None,
            "한 팔이 목록에 없음": lambda c, n: (200, {"success": True, "data": c.rows[:7]}, None) if n == 2 else None,
            "예상 밖 같은 서명 세션": lambda c, n: (200, {"success": True, "data": c.rows + [dict(c.rows[0], id=5000)]}, None) if n == 2 else None,
            "JSON 아님": lambda c, n: (200, None, "JSON 아님") if n == 2 else None,
        }
        for name, hook in cases.items():
            with self.subTest(name):
                self.out = self.tmp / ("out-" + str(abs(hash(name))))
                self.out.mkdir()
                Fake.cfg = {"get": hook}
                rc, c = self.run_tool()
                self.assertEqual(rc, 3, name + " " + self.stdout)
                self.assertFalse(self.draft()["api_reconcile_ok"])

    # C2
    def test_c2_unresolved_intent_blocks_all_sends(self):
        ab_create.append(self.out / "journal.jsonl", {"kind": "intent", "pair_arm": "P1A",
                                                       "body_sha256": ab_create.bodies_from_protocol(ab_create.load_protocol(PROTOCOL)[0])[0]["body_sha256"]})
        rc, c = self.run_tool()
        self.assertEqual(rc, 4, self.stdout)
        self.assertIsNone(c, "토큰을 읽고 클라이언트를 만들기 전에 멈춘다")
        self.assertIn("결과 없는 전송 의도", self.stdout)

    def test_c2_overseer_form_unknown_body_sha_also_blocks(self):
        """총괄 review.py 의 입력(본문 sha 가 'unresolved-prior-send')도 보내기 전에 거절."""
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
        """프로세스가 POST 뒤 응답 전에 죽은 흔적: 잠금 + 결과 없는 의도. 잠금이 있으면 거절, 사람이 잠금을 지워도 의도 때문에 거절."""
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
        plan = ab_create.bodies_from_protocol(ab_create.load_protocol(PROTOCOL)[0])
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
        self.assertTrue(plan)

    def test_window_only_preflight_journal_can_rerun(self):
        """창 밖으로 보낸 것 없이 끝난 기록부(같은 프로토콜·코드)는 다음 창에 다시 실행할 수 있다."""
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
            "startedAt 없음": None,   # 아래에서 만든다
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
        """시작은 창 안(C+11:58)이지만 4번째 요청 직전 C+12:01 — 더 보내지 않고 부분 결과(종료 3)."""
        ticks = iter([BOUNDARY + timedelta(minutes=11, seconds=58)] * 4 + [BOUNDARY + timedelta(minutes=12, seconds=1)] * 20)
        rc, c = self.run_tool(clock=lambda: next(ticks))
        self.assertEqual(rc, 3, self.stdout)
        self.assertEqual(c.posts, 3)
        kinds = [r["kind"] for r in self.journal()]
        self.assertIn("window_closed", kinds)
        self.assertFalse((self.out / "creation_log.draft.json").exists())

    def test_unknown_then_list_candidate_is_provisional_not_attributed(self):
        def post(c, n, body):
            if n == 3:   # 생성은 되고 응답 불명
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


if __name__ == "__main__":
    unittest.main()
