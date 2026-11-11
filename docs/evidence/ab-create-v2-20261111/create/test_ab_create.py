"""ab_create.py 대역 시험 — 실제 서버·토큰·POST 없음. 실행: 이 폴더에서 python -X utf8 -m unittest -v test_ab_create

보는 것: 계획만 모드, 정상 8개, 창 밖, 401·500·success=false·응답 불명(생성됐는데 시간 초과)·저장값 불일치에서 즉시 멈춤과 재전송 0,
재실행 거부, 응답 불명 뒤 목록 대조로 실제 생성 확인, 이미 있는 같은 서명 세션이면 보내지 않음, 토큰이 어떤 출력·파일에도 없음.
"""
import contextlib
import io
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import ab_create
import standin_server

HERE = Path(__file__).resolve().parent
PROTOCOL = HERE.parent / "tool" / "protocol.json"
BOUNDARY = datetime(2026, 12, 7, 0, 0, tzinfo=timezone.utc)   # 가상 시각 — 실제 생성일이 아니다
IN_WINDOW = BOUNDARY + timedelta(minutes=8)


class Create(unittest.TestCase):

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

    def reconcile(self):
        return [r for r in self.journal() if r["kind"] == "reconcile"][-1]["by_arm"]

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
        self.assertTrue(all(len(v) == 1 for v in self.reconcile().values()))
        draft = json.loads((self.out / "creation_log.draft.json").read_text(encoding="utf-8"))
        self.assertEqual(draft["t0"], (BOUNDARY + timedelta(minutes=15)).isoformat())
        self.assertFalse(draft["db_verified"])
        self.assertEqual(draft["checks"], {"reconcile_not_exactly_one": [], "created_at_not_before_t0": []})
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
        rec = self.reconcile()
        stopped = res[-1][0]
        self.assertEqual(len(rec[stopped]), 1 if created_at_k else 0, rec)
        for arm, _ in res[:-1]:
            self.assertEqual(len(rec[arm]), 1)
        self.assertEqual(sum(len(v) for v in rec.values()), (k - 1) + (1 if created_at_k else 0))
        self.assert_no_token_anywhere(st)
        # 재실행 — 기록부에 OK 가 아닌 결과가 있으면 아무것도 보내지 않는다
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

    def test_unknown_response_but_created_is_found_by_reconcile_and_not_resent(self):
        self._stop_case("timeout_at:5", 5, "UNKNOWN", created_at_k=True)

    def test_stored_value_mismatch_stops(self):
        self._stop_case("mismatch_at:6", 6, "MISMATCH", created_at_k=True)

    def test_preexisting_same_signature_sends_nothing(self):
        rc, st = self.go("dup_preexisting")
        self.assertEqual(rc, 2, self.stdout)
        self.assertEqual(st.posts, 0)
        self.assertIn("P2A", self.stdout)


if __name__ == "__main__":
    unittest.main()
