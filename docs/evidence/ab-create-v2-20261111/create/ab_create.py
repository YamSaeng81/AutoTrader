"""청산 A/B 8세션 생성 호출기 (총괄 v1.145 §3-4) — 실제 생성 승인 전이다. 대역(standin_server.py)으로만 시험했다.

경로: 기존 POST /api/v1/paper-trading/sessions + Authorization: Bearer (v1.145 결정). /sessions/multi 는 쓰지 않는다 — 그 본문은
전략 목록·코인·주기·자본·텔레그램만 받아 investRatio·stopLossPct·maxHoldHours·strategyParams 를 전달하지 못한다(소스 확인).

비밀값: 토큰은 --token-file 경로의 파일에서만 읽는다. 명령줄·표준 출력·기록부·예외 메시지에 넣지 않는다.

규칙(결과 전에 고정):
  - 사전 확인: 목록 조회 success, 8개 서명(전략·코인·주기·자본·파라미터·손절 하한 키 유무) 중 하나라도 RUNNING 세션이 이미 있으면 중단(중복 방지).
  - 시각 창: 가장 가까운 지난 M15 경계 C 에 대해 지금이 [C+6분, C+12분] 안이어야 한다. 그 회차의 후보 수집 마감 D=C+5분이 이미 지나
    새 세션의 첫 BUY 접수가 거절되고, 다음 회차 봉은 C+15분(=T0)에 닫힌다. 창 밖이면 아무것도 보내지 않고 끝낸다(기다리지 않는다).
  - 본문은 프로토콜에서 만든 8개, 고정 순서로 1회씩. 응답 판정:
      전송 오류·시간 초과·JSON 아님 → UNKNOWN / 401·403 → AUTH / 그 밖 HTTP≠200 → HTTP_REJECTED /
      success≠true → REJECTED / id 없음·정수 아님·이미 있던 id → UNKNOWN / 응답 저장값 ≠ 본문 → MISMATCH / 그 밖 → OK.
    OK 가 아니면 **즉시 멈추고 다시 보내지 않는다.** 남은 본문도 보내지 않는다.
  - 기록부(journal.jsonl)에 OK 가 아닌 줄이 하나라도 있으면 다음 실행은 아무것도 보내지 않는다(사람 판단 대기). OK 로 기록된 쌍은
    다시 보내지 않는다.
  - 끝나거나 멈추면 목록을 다시 읽어 쌍 서명마다 이번 사전 확인 이후 시작된 세션을 찾는다. 🔴 같은 쌍의 A·B 는 목록 응답으로 구분되지
    않는다(stopLossPct 미노출) — OK 응답 id 로 팔을 귀속하고, 귀속되지 않은 새 id 는 멈춘 팔에 붙인다(응답 불명이어도 실제 생성 여부를
    여기서 확인). 그 밖의 새 id 는 "예상 밖"으로 적는다.
  - 8개 모두 OK 면 T0 = C+15분, 생성 기록부 초안(creation_log.draft.json)을 쓴다. API 응답에 없는 저장값(investRatio·stopLossPct·
    maxHoldHours)과 규칙 지문은 생성 뒤 읽기 전용 질의(post_creation_check.sql)로 채운다 — 그 전에는 db_verified=false.
종료 코드: 0 = 8개 OK, 2 = 사전 확인·창 밖으로 보낸 것 없음, 3 = 중간에 멈춤, 4 = 기록부 상태 때문에 보내지 않음.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ENDPOINT = "/api/v1/paper-trading/sessions"
ORDER = [("P1", "A"), ("P1", "B"), ("P2", "A"), ("P2", "B"), ("P3", "A"), ("P3", "B"), ("P4", "A"), ("P4", "B")]
WINDOW_FROM = timedelta(minutes=6)
WINDOW_TO = timedelta(minutes=12)


def load_protocol(path: Path) -> tuple[dict, str]:
    raw = path.read_bytes()
    return json.loads(raw.decode("utf-8")), hashlib.sha256(raw).hexdigest()


def bodies_from_protocol(protocol: dict) -> list[dict]:
    out = []
    c = protocol["common"]
    for pid, arm in ORDER:
        spec = protocol["pairs"][pid]
        body = {
            "strategyType": spec["strategy"], "coinPair": spec["coin"], "timeframe": protocol["timeframe"],
            "initialCapital": int(c["initial_capital"]), "investRatio": float(c["invest_ratio"]),
            "maxHoldHours": int(c["max_hold_hours"]), "strategyParams": dict(protocol["params"]),
        }
        sl = protocol["arms"][arm]["stop_loss_pct"]
        if sl is not None:
            body["stopLossPct"] = int(sl) if str(sl).isdigit() else float(sl)
        out.append({"pair_id": pid, "arm": arm, "body": body,
                    "body_sha256": hashlib.sha256(json.dumps(body, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()})
    return out


def m15_window(now: datetime) -> tuple[datetime, bool]:
    c = now.replace(minute=(now.minute // 15) * 15, second=0, microsecond=0)
    return c, (c + WINDOW_FROM) <= now <= (c + WINDOW_TO)


def signature(strategy, coin, tf, capital, params) -> str:
    return json.dumps([strategy, coin, tf, str(int(float(capital))), params], sort_keys=True, ensure_ascii=False)


def body_signature(b: dict) -> str:
    return signature(b["strategyType"], b["coinPair"], b["timeframe"], b["initialCapital"], b["strategyParams"])


def row_signature(r: dict) -> str:
    return signature(r.get("strategyName"), r.get("coinPair"), r.get("timeframe"), r.get("initialCapital") or 0, r.get("strategyParams"))


class Client:
    def __init__(self, base: str, token: str, timeout: float):
        self.base, self._token, self.timeout = base.rstrip("/"), token, timeout

    def call(self, method: str, path: str, body: dict | None = None) -> tuple[int | None, dict | None, str | None]:
        """(HTTP 코드, JSON, 전송 오류 분류). 토큰은 헤더에만 싣고 어떤 반환값에도 넣지 않는다."""
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method=method,
                                     headers={"Authorization": "Bearer " + self._token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                raw = r.read()
                code = r.status
        except urllib.error.HTTPError as e:
            code, raw = e.code, e.read()
        except Exception as e:   # 시간 초과·연결 오류 — 접수됐을 수 있다
            return None, None, type(e).__name__
        try:
            return code, json.loads(raw.decode("utf-8")), None
        except Exception:
            return code, None, "JSON 아님"


def classify(code, js, err, body: dict, known_ids: set) -> tuple[str, dict]:
    if err == "JSON 아님" and code in (401, 403):
        return "AUTH", {"http": code}
    if err is not None:
        return "UNKNOWN", {"http": code, "transport": err}
    if code in (401, 403):
        return "AUTH", {"http": code}
    if code != 200:
        return "HTTP_REJECTED", {"http": code, "error": (js or {}).get("error")}
    if js.get("success") is not True:
        return "REJECTED", {"http": code, "error": js.get("error")}
    d = js.get("data") or {}
    sid = d.get("id")
    if not isinstance(sid, int) or sid in known_ids:
        return "UNKNOWN", {"http": code, "id": sid, "why": "새 정수 id 아님"}
    diffs = []
    for k_resp, k_body in (("strategyName", "strategyType"), ("coinPair", "coinPair"), ("timeframe", "timeframe")):
        if d.get(k_resp) != body[k_body]:
            diffs.append(k_resp)
    if d.get("initialCapital") is None or float(d.get("initialCapital")) != float(body["initialCapital"]):
        diffs.append("initialCapital")
    if d.get("strategyParams") != body["strategyParams"]:
        diffs.append("strategyParams")
    if d.get("status") != "RUNNING" or not d.get("startedAt"):
        diffs.append("status/startedAt")
    if diffs:
        return "MISMATCH", {"http": code, "id": sid, "fields": diffs}
    return "OK", {"http": code, "id": sid, "startedAt": d.get("startedAt")}


def journal_lines(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def append(path: Path, rec: dict) -> None:
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False, sort_keys=True) + "\n")


def _ts(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(timezone.utc)


def reconcile(client: Client, plan: list[dict], since: str, ok_ids: dict[str, int], stopped: str | None = None) -> dict:
    """사전 확인 이후 시작된 RUNNING 세션을 쌍 서명으로 찾고 팔에 귀속한다.

    🔴 같은 쌍의 A·B 는 목록 응답으로 구분되지 않는다 — 응답에 stopLossPct 가 없다(서명이 같다). 그래서 OK 응답의 id 로 팔을 귀속하고,
    귀속되지 않은 새 id 는 멈춘 팔(응답 불명이면 실제로 생성된 것)에 붙인다. 팔별 손절 하한은 생성 뒤 읽기 전용 DB 대조로만 확인한다.
    """
    code, js, err = client.call("GET", ENDPOINT)
    if err or code != 200 or not js or js.get("success") is not True:
        return {"status": "목록 조회 실패", "http": code, "transport": err}
    rows = js.get("data") or []
    by_arm, unexpected = {}, {}
    for pid in sorted({p["pair_id"] for p in plan}):
        sig = body_signature(next(p["body"] for p in plan if p["pair_id"] == pid))
        hits = sorted(r["id"] for r in rows if row_signature(r) == sig and r.get("status") == "RUNNING"
                      and r.get("startedAt") and _ts(r["startedAt"]) >= _ts(since))
        attributed = set()
        for arm in ("A", "B"):
            k = pid + arm
            if k in ok_ids:
                by_arm[k] = [ok_ids[k]] if ok_ids[k] in hits else []
                attributed.add(ok_ids[k])
        rest = [i for i in hits if i not in attributed]
        for arm in ("A", "B"):
            k = pid + arm
            if k not in ok_ids:
                by_arm[k] = rest if k == stopped else []
        if rest and (stopped is None or not stopped.startswith(pid)):
            unexpected[pid] = rest
    return {"status": "조회됨", "by_arm": by_arm, "unexpected": unexpected}


def run(argv: list[str], now: datetime | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--token-file", required=True)
    ap.add_argument("--protocol", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--code-sha", required=True)
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--execute", action="store_true", help="없으면 계획만 쓰고 아무것도 보내지 않는다")
    a = ap.parse_args(argv)
    now = now or datetime.now(timezone.utc)
    out = Path(a.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    journal = out / "journal.jsonl"
    protocol, psha = load_protocol(Path(a.protocol))
    plan = bodies_from_protocol(protocol)
    (out / "plan.json").write_text(json.dumps({"protocol_sha256": psha, "endpoint": "POST " + ENDPOINT, "bodies": plan},
                                              ensure_ascii=False, indent=2), encoding="utf-8")
    if not a.execute:
        print(json.dumps({"mode": "계획만", "bodies": len(plan), "protocol_sha256": psha}, ensure_ascii=False))
        return 2

    prior = journal_lines(journal)
    bad = [r for r in prior if r.get("kind") == "result" and r.get("class") != "OK"]
    if bad:
        print(json.dumps({"stop": "기록부에 OK 가 아닌 결과가 있다 — 사람 판단 전에는 보내지 않는다",
                          "results": [(r["pair_arm"], r["class"]) for r in bad]}, ensure_ascii=False))
        return 4
    if any(r.get("kind") == "result" for r in prior):
        print(json.dumps({"stop": "이미 생성 결과가 있는 기록부다 — 이어 보내기는 이 도구의 범위가 아니다(총괄 결정)"}, ensure_ascii=False))
        return 4

    token = Path(a.token_file).read_text(encoding="utf-8").strip()
    client = Client(a.base_url, token, a.timeout)
    del token

    c, inside = m15_window(now)
    append(journal, {"kind": "preflight", "at": now.isoformat(), "m15_boundary": c.isoformat(), "inside_window": inside,
                     "protocol_sha256": psha, "code_sha": a.code_sha})
    if not inside:
        print(json.dumps({"stop": f"창 밖 — {c.isoformat()} 기준 [+6분, +12분]이 아니다. 보낸 것 없음"}, ensure_ascii=False))
        return 2
    code, js, err = client.call("GET", ENDPOINT)
    if err or code != 200 or not js or js.get("success") is not True:
        append(journal, {"kind": "preflight_list", "http": code, "transport": err, "ok": False})
        print(json.dumps({"stop": "사전 목록 조회 실패 — 보낸 것 없음", "http": code, "transport": err}, ensure_ascii=False))
        return 2
    rows = js.get("data") or []
    known_ids = {r.get("id") for r in rows}
    dup = sorted({p["pair_id"] + p["arm"] for p in plan for r in rows
                  if r.get("status") == "RUNNING" and row_signature(r) == body_signature(p["body"])})
    append(journal, {"kind": "preflight_list", "ok": True, "running": sum(1 for r in rows if r.get("status") == "RUNNING"),
                     "duplicates": dup})
    if dup:
        print(json.dumps({"stop": "같은 서명의 RUNNING 세션이 이미 있다 — 보낸 것 없음", "arms": dup}, ensure_ascii=False))
        return 2

    since = now.isoformat()
    created = []
    ok_ids: dict[str, int] = {}
    for p in plan:
        append(journal, {"kind": "intent", "pair_arm": p["pair_id"] + p["arm"], "body_sha256": p["body_sha256"]})
        code, js, err = client.call("POST", ENDPOINT, p["body"])
        cls, detail = classify(code, js, err, p["body"], known_ids)
        append(journal, {"kind": "result", "pair_arm": p["pair_id"] + p["arm"], "class": cls, **detail})
        if cls != "OK":
            rec = reconcile(client, plan, since, ok_ids, stopped=p["pair_id"] + p["arm"])
            append(journal, {"kind": "reconcile", **rec})
            print(json.dumps({"stop": f"{p['pair_id']}{p['arm']} {cls} — 다시 보내지 않고 남은 본문도 보내지 않는다",
                              "created_ok": [x["pair_arm"] for x in created], "reconcile": rec}, ensure_ascii=False))
            return 3
        known_ids.add(detail["id"])
        ok_ids[p["pair_id"] + p["arm"]] = detail["id"]
        created.append({"pair_arm": p["pair_id"] + p["arm"], "pair_id": p["pair_id"], "arm": p["arm"], "session_id": str(detail["id"]),
                        "created_at": detail["startedAt"]})
    rec = reconcile(client, plan, since, ok_ids)
    append(journal, {"kind": "reconcile", **rec})
    multi = [k for k, v in (rec.get("by_arm") or {}).items() if len(v) != 1] + [f"예상 밖 {k}" for k in (rec.get("unexpected") or {})]
    t0 = c + timedelta(minutes=15)
    late = [x["pair_arm"] for x in created if _ts(x["created_at"]) >= t0]
    draft = {"protocol_sha256": psha, "code_sha": a.code_sha, "t0": t0.isoformat(), "db_verified": False,
             "sessions": [{"pair_id": x["pair_id"], "arm": x["arm"], "session_id": x["session_id"], "created_at": x["created_at"],
                           "ruleset_hash": None, "stored": None} for x in created],
             "checks": {"reconcile_not_exactly_one": multi, "created_at_not_before_t0": late}}
    (out / "creation_log.draft.json").write_text(json.dumps(draft, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"done": "8개 OK", "t0": t0.isoformat(), "reconcile_not_exactly_one": multi, "late": late}, ensure_ascii=False))
    return 0 if not multi and not late else 3


if __name__ == "__main__":
    raise SystemExit(run(sys.argv[1:]))
