"""총괄 v1.178 R1 국소 시험 — 생성 POST 의 3xx 를 따라가 두 번째 요청(목록 GET 등)이 생기는가.

대상 모듈은 환경 변수 R1_TARGET 으로 고른다:
  R1_TARGET=v41  → ab-create-v41-20261116/create/ab_create.py (보완 전, 원문). 301·302·303 에서 **실패해야 한다**(재현).
  R1_TARGET=v42  → 이 폴더의 ab_create.py (보완 뒤). 모두 통과해야 한다.
외부 연결 없음 — 127.0.0.1 대역 서버 두 개(두 번째는 '다른 호스트' 역할)만 쓴다. 대역 목록 응답의 세션 560 성과 필드는 가짜다.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
TARGET = os.environ.get("R1_TARGET", "v42")
SRC = {"v41": HERE.parents[1] / "ab-create-v41-20261116" / "create" / "ab_create.py", "v42": HERE / "ab_create.py"}[TARGET]
sys.path.insert(0, str(HERE))
_spec = importlib.util.spec_from_file_location(f"ab_create_{TARGET}", SRC)
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)

import fake_meta   # noqa: E402
from fake_meta import FakeMetaDB   # noqa: E402

ENDPOINT = "/api/v1/paper-trading/sessions"
TOKEN = "r1-standin-token-not-a-secret"
CODES = (301, 302, 303, 307, 308)
FAKE_LIST = {"success": True, "error": None, "data": [{"id": 560, "status": "RUNNING", "totalReturnPct": 12.34, "realizedPnl": 5678}]}


class Rec:
    def __init__(self):
        self.reqs: list[tuple[str, str]] = []
        self.lock = threading.Lock()


def server(rec: Rec, code: int | None = None, location=None, redirect_at: int = 1):
    """POST ENDPOINT 의 redirect_at 번째 요청에 code+Location(그 밖 POST 는 정상 생성 200). 모든 요청을 센다. GET 은 가짜 목록."""
    state = {"posts": 0, "next": 900}

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, c, obj, headers=()):
            b = json.dumps(obj).encode("utf-8")
            self.send_response(c)
            for k, v in headers:
                self.send_header(k, v)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)

        def do_GET(self):
            with rec.lock:
                rec.reqs.append(("GET", self.path))
            self._send(200, FAKE_LIST)

        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(n)
            with rec.lock:
                rec.reqs.append(("POST", self.path))
                state["posts"] += 1
                k = state["posts"]
            if code is not None and k == redirect_at:
                loc = location() if callable(location) else location
                return self._send(code, {"success": True, "data": FAKE_LIST["data"]}, [("Location", loc)])
            body = json.loads(raw.decode("utf-8"))
            with rec.lock:
                sid = state["next"]
                state["next"] += 1
            return self._send(200, {"success": True, "error": None, "data": {
                "id": sid, "strategyName": body["strategyType"], "coinPair": body["coinPair"], "timeframe": body["timeframe"],
                "status": "RUNNING", "initialCapital": body["initialCapital"], "strategyParams": body.get("strategyParams"),
                "startedAt": datetime(2026, 11, 17, 3, 7, k, 123456, tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")}})

    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


class Redirect(unittest.TestCase):
    """같은 경로·다른 경로(전체 목록)·다른 호스트 × 301/302/303/307/308 — 두 번째 요청 0, 결과는 응답 불명."""

    def setUp(self):
        self.other_rec = Rec()
        self.other = server(self.other_rec)   # '다른 호스트' 역할(다른 포트·localhost 이름)

    def tearDown(self):
        self.other.shutdown()

    def one(self, code: int, target: str):
        rec = Rec()
        holder = {}
        loc = {"same": lambda: f"http://127.0.0.1:{holder['port']}{ENDPOINT}",
               "list": lambda: f"http://127.0.0.1:{holder['port']}{ENDPOINT}?status=RUNNING",
               "relative_list": lambda: ENDPOINT + "?page=0",
               "other_host": lambda: f"http://localhost:{self.other.server_address[1]}{ENDPOINT}"}[target]
        srv = server(rec, code, loc)
        holder["port"] = srv.server_address[1]
        try:
            c = mod.Client(f"http://127.0.0.1:{holder['port']}", TOKEN, 2)
            got = c.call("POST", ENDPOINT, {"strategyType": "X", "coinPair": "KRW-BTC", "timeframe": "M15", "initialCapital": 1,
                                            "strategyParams": {}})
        finally:
            srv.shutdown()
        return got, rec.reqs + [("OTHER-" + m, p) for m, p in self.other_rec.reqs]

    def test_no_second_request_any_code_any_target(self):
        for code in CODES:
            for target in ("same", "list", "relative_list", "other_host"):
                with self.subTest(code=code, target=target):
                    self.other_rec.reqs.clear()
                    (http, js, err), reqs = self.one(code, target)
                    self.assertEqual(reqs, [("POST", ENDPOINT)], f"두 번째 요청이 생겼다: {reqs}")
                    self.assertIsNone(js, "3xx 응답 본문·따라간 응답을 반환하면 안 된다")
                    cls, detail = mod.classify(http, js, err, {}, set(), 0)
                    self.assertEqual(cls, "UNKNOWN")
                    self.assertEqual(detail.get("http"), code)


@unittest.skipIf(TARGET != "v42", "생성기 전체 실행은 보완 뒤 모듈만")
class CreatorRun(unittest.TestCase):
    """8팔 생성기 전체 실행 — 3번째 POST 가 302(목록으로) → 응답 불명, 남은 5팔 0·GET 0·재전송 0, 종료 3."""

    def test_redirect_mid_run_stops(self):
        for code in CODES:
            with self.subTest(code=code):
                rec = Rec()
                holder = {}
                srv = server(rec, code, lambda: f"http://127.0.0.1:{holder['port']}{ENDPOINT}?size=1000", redirect_at=3)
                holder["port"] = srv.server_address[1]
                tmp = Path(tempfile.mkdtemp(prefix="r1-"))
                tok = tmp / "token.txt"
                tok.write_text(TOKEN, encoding="utf-8")
                FakeMetaDB.reset(lambda: [])
                args = ["--base-url", f"http://127.0.0.1:{holder['port']}", "--token-file", str(tok),
                        "--protocol", str(HERE.parent / "tool" / "protocol.json"), "--out-dir", str(tmp / "out"),
                        "--code-sha", "0" * 40, "--timeout", "2", "--db-cmd", json.dumps(["psql"]), "--db-source", "대역", "--execute"]
                buf = io.StringIO()
                try:
                    with patch.object(mod, "MetaDB", FakeMetaDB), contextlib.redirect_stdout(buf):
                        rc = mod.run(args, now=datetime(2026, 11, 17, 3, 7, 0, tzinfo=timezone.utc))
                finally:
                    srv.shutdown()
                self.assertEqual(rc, 3, buf.getvalue())
                self.assertEqual(rec.reqs, [("POST", ENDPOINT)] * 3, "3번째에서 멈추고 GET·재전송 0")
                j = mod.journal_lines(tmp / "out" / "journal.jsonl")
                res = [(r["pair_arm"], r["class"], r.get("http")) for r in j if r["kind"] == "result"]
                self.assertEqual(res, [("P1A", "OK", 200), ("P1B", "OK", 200), ("P2A", "UNKNOWN", code)])
                self.assertEqual([r["pair_arm"] for r in j if r["kind"] == "intent"], ["P1A", "P1B", "P2A"])
                self.assertEqual(j[0].get("creator_version"), "v4.2")
                self.assertFalse((tmp / "out" / "creation_log.draft.json").exists())
                out = buf.getvalue() + "".join(f.read_text(encoding="utf-8") for f in (tmp / "out").rglob("*") if f.is_file())
                self.assertNotIn(TOKEN, out)
                self.assertNotIn("totalReturnPct", out, "따라간 목록 응답의 성과 필드가 어디에도 남지 않는다")
                # 같은 폴더로 다시 실행해도 보내지 않는다(OK 아닌 결과가 있는 기록부)
                srv2 = server(rec)
                args2 = list(args)
                args2[1] = f"http://127.0.0.1:{srv2.server_address[1]}"
                with patch.object(mod, "MetaDB", FakeMetaDB), contextlib.redirect_stdout(io.StringIO()):
                    rc2 = mod.run(args2, now=datetime(2026, 11, 17, 3, 7, 0, tzinfo=timezone.utc))
                srv2.shutdown()
                self.assertEqual(rc2, 4)
                self.assertEqual(len(rec.reqs), 3)


if __name__ == "__main__":
    print(f"R1_TARGET={TARGET} SRC={SRC.relative_to(HERE.parents[1]).as_posix()}")
    unittest.main(verbosity=2)
