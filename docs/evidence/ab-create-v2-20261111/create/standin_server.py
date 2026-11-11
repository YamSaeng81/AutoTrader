"""ab_create.py 시험용 대역 HTTP 서버 — 운영 API 와 같은 경로·봉투({success,data,error})·인증 헤더만 흉내 낸다. 외부 연결 없음.

시나리오(시험이 정한다):
  ok              — 8개 모두 정상
  auth_at:k       — k번째 POST 에 401(생성 안 함)
  http500_at:k    — k번째 POST 에 500(생성 안 함)
  timeout_at:k    — k번째 POST 는 **생성한 뒤** 응답을 늦춰 호출기가 시간 초과로 끊게 함
  fail_at:k       — k번째 POST 에 HTTP 200 + success=false(생성 안 함, 운영 IllegalStateException 경로와 같음)
  mismatch_at:k   — k번째 POST 응답의 strategyParams 에서 실행 모드를 뺌(생성은 함)
  dup_preexisting — 목록에 P2A 와 같은 서명의 RUNNING 세션이 이미 있음
"""
from __future__ import annotations

import json
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = "standin-token-not-a-secret"


class State:
    def __init__(self, scenario: str, now: datetime):
        self.scenario, self.now = scenario, now
        self.sessions: list[dict] = []
        self.posts = 0
        self.auth_headers: list[str] = []
        self.lock = threading.Lock()
        self.next_id = 700
        if scenario == "dup_preexisting":
            self.sessions.append({"id": 650, "strategyName": "COMPOSITE_MEANREV_BB", "coinPair": "KRW-ETH", "timeframe": "M15",
                                  "status": "RUNNING", "initialCapital": 100000,
                                  "startedAt": (now - timedelta(days=1)).isoformat().replace("+00:00", "Z"),
                                  "strategyParams": {"paperMaxInvestment": 20000, "paperExecutionMode": "LIVE_ALIGNED"}})
        self.sessions.append({"id": 600, "strategyName": "COMPOSITE_MTF_MOMENTUM", "coinPair": "KRW-ANKR", "timeframe": "H1",
                              "status": "RUNNING", "initialCapital": 1000000, "startedAt": "2026-10-01T00:00:00Z", "strategyParams": None})

    def at(self, kind: str) -> int | None:
        if self.scenario.startswith(kind + ":"):
            return int(self.scenario.split(":")[1])
        return None


def make_handler(state: State):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, code: int, obj: dict | None, raw: bytes | None = None):
            b = raw if raw is not None else json.dumps(obj).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)

        def _auth(self) -> bool:
            h = self.headers.get("Authorization") or ""
            state.auth_headers.append(h)
            return h == "Bearer " + TOKEN

        def do_GET(self):
            if self.path != "/api/v1/paper-trading/sessions":
                return self._send(404, {"success": False})
            if not self._auth():
                return self._send(401, {"success": False, "error": {"code": "UNAUTHORIZED", "message": "인증이 필요합니다."}})
            with state.lock:
                rows = list(reversed(state.sessions))
            return self._send(200, {"success": True, "data": rows, "error": None})

        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n).decode("utf-8"))
            with state.lock:
                state.posts += 1
                k = state.posts
            if not self._auth() or state.at("auth_at") == k:
                return self._send(401, {"success": False, "error": {"code": "UNAUTHORIZED", "message": "인증이 필요합니다."}})
            if state.at("http500_at") == k:
                return self._send(500, {"success": False, "error": {"code": "INTERNAL", "message": "대역 500"}})
            if state.at("fail_at") == k:
                return self._send(200, {"success": False, "data": None, "error": {"code": "INVALID_REQUEST", "message": "대역 거절"}})
            with state.lock:
                sid = state.next_id
                state.next_id += 1
                row = {"id": sid, "strategyName": body["strategyType"], "coinPair": body["coinPair"], "timeframe": body["timeframe"],
                       "status": "RUNNING", "initialCapital": body["initialCapital"],
                       "startedAt": (state.now + timedelta(seconds=k)).isoformat().replace("+00:00", "Z"),
                       "strategyParams": body.get("strategyParams")}
                state.sessions.append(row)
            resp = dict(row)
            if state.at("mismatch_at") == k:
                resp["strategyParams"] = {"paperMaxInvestment": 20000}
            if state.at("timeout_at") == k:
                time.sleep(3)   # 대역 서버 쪽 지연 — 호출기 시간 초과(1초)를 만든다. 시험의 통과 조건을 위한 대기가 아니다.
            return self._send(200, {"success": True, "data": resp, "error": None})
    return H


def start(scenario: str, now: datetime):
    state = State(scenario, now)
    srv = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(state))
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv, state
