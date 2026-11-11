"""생성 기록부 완성기 v4 (총괄 v1.151 §3·v1.152 A) — API 초안(v4) + 읽기 전용 DB 확인 출력 → 완성 기록부(creation_log.json).

v3 → v4 (2026-11-11, v3 원문은 ab-create-v3-20261111 에 그대로 보존):
  v3 는 DB 출력의 started_at 을 초안과 대조하지 않아, 세션 ID 가 겹치는 **다른 DB/다른 생성 회차**의 출력도 받아 완성본을 썼다
  (v1.150 run3-wrongdb 재현, 총괄 v1.151 재확인). v4 는 다음을 더한다.
  - 초안은 v4 여야 한다(tool_version=v4, 세션마다 post_started_at_raw·api_stored_started_at). 필수 근거가 없는 옛 초안에 DB 값을 복사해
    채우지 않는다 — 거절.
  - POST 원문과 저장 후 API 시각이 저장 정밀도 계약(나노초→마이크로초 반올림)으로 맞는지 다시 본다.
  - **저장 후 API 시작 시각과 psql 시작 시각을 UTC 의 같은 순간으로 정확히 대조한다**(나노초 정수 비교, 표기 차이만 허용).
    초/분 허용 오차·현재 시각 대체 없음. 8개 모두 맞아야 하고 한 행이라도 다르면 거절한다.
  - 연결 출처(--db-source, 사람이 적는 서버/DB 이름)·초안/DB 출력/프로토콜 원문 sha256 을 완성본에 남긴다.
    🔴 시각 대조는 같은 생성 회차의 행인지를 보는 검사일 뿐 DB 서버의 신원 인증이 아니다.
  - 거절이면 완성 파일 0개, 이 도구는 어떤 생성 요청도 보내지 않는다(자동 재생성 없음).

입력:
  --draft      ab_create.py v4 가 쓴 creation_log.draft.json (db_verified=false, api_reconcile_ok=true)
  --db-output  post_creation_check.sql 의 psql --csv 출력(@@ 구획)
  --protocol   불변 프로토콜(protocol.json) — 초안의 protocol_sha256 과 같아야 한다
  --db-source  확인 SQL 을 돌린 연결 출처(예: "로컬 격리 v152-pg/ab_chain_v4") — 기록용, 인증 아님
  --out        완성 기록부 경로(모든 검사를 통과할 때만 쓴다, 이미 있으면 거절)

거절(종료 3, 완성본 없음): v3 의 모든 거절 + v4 필수 필드 누락·시간대 없음·정밀도 계약 불일치·DB 시작 시각 불일치.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

HASH_RE = re.compile(r"^[0-9a-f]{12}$")
SECTIONS = ("db_now", "stored", "fingerprint", "before_t0")
STORED_KEYS = ("strategy", "coin", "timeframe", "initial_capital", "invest_ratio", "max_investment", "max_hold_hours",
               "stop_loss_pct", "params_json")
TS_RE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}(?::?\d{2})?)$")
TOOL_VERSION = "v4"


class Rejected(Exception):
    pass


def ts_ns(s) -> int:
    """시각 문자열 → UTC 에포크 나노초(정수). 시간대 없음·형식 이상은 Rejected. 표기 차이(Z, +00, +00:00, +09:00)는 같은 순간이면 같은 값."""
    if not isinstance(s, str):
        raise Rejected("시각 값이 없다")
    m = TS_RE.match(s.strip())
    if not m:
        raise Rejected(f"시각 형식 이상 또는 시간대 없음: {s!r}")
    y, mo, d, h, mi, se, frac, tz = m.groups()
    base = datetime(int(y), int(mo), int(d), int(h), int(mi), int(se), tzinfo=timezone.utc)
    if tz != "Z":
        sign = 1 if tz[0] == "+" else -1
        digits = tz[1:].replace(":", "")
        base = base - sign * timedelta(hours=int(digits[:2]), minutes=int(digits[2:4] or 0))
    return int(base.timestamp()) * 1_000_000_000 + int((frac or "").ljust(9, "0"))


def round_to_micros(ns: int) -> int:
    """저장 정밀도 계약 — 가장 가까운 마이크로초(0.5 는 올림). 실제 로컬 PG(Hibernate·pgjdbc)로 확인."""
    return ((ns + 500) // 1000) * 1000


def _ts(s: str) -> datetime:
    d = datetime.fromisoformat(str(s).strip().replace("Z", "+00:00").replace(" ", "T", 1))
    if d.tzinfo is None:
        raise Rejected("시각에 시간대가 없다")
    return d.astimezone(timezone.utc)


def _dec(s) -> Decimal | None:
    s = "" if s is None else str(s).strip()
    if s == "":
        return None
    try:
        return Decimal(s)
    except InvalidOperation as e:
        raise Rejected("숫자 형식 이상") from e


def parse_db_output(text: str) -> dict[str, list[dict]]:
    """@@구획 → CSV 행. 구획 순서·누락·끝 표지까지 본다(v3 와 같음)."""
    parts: dict[str, list[str]] = {}
    cur = None
    ended = False
    for line in text.splitlines():
        if line.startswith("@@"):
            name = line[2:].strip()
            if name == "end":
                ended = True
                cur = None
                continue
            if name in parts or name not in SECTIONS:
                raise Rejected(f"DB 출력 구획 이상: {name}")
            parts[name] = []
            cur = name
        elif cur is not None and line.strip():
            parts[cur].append(line)
    if not ended or list(parts) != list(SECTIONS):
        raise Rejected("DB 출력 구획 누락 또는 순서 이상(확인 SQL 이 끝까지 돌지 않았다)")
    return {name: list(csv.DictReader(io.StringIO("\n".join(lines)))) for name, lines in parts.items()}


def arm_of(stop_loss) -> str:
    v = _dec(stop_loss)
    if v is None:
        return "A"
    if v == Decimal("5"):
        return "B"
    raise Rejected("stop_loss_pct 가 A(빈 값)·B(5) 어느 쪽도 아니다")


def check_draft_v4(draft: dict) -> None:
    if draft.get("tool_version") != TOOL_VERSION:
        raise Rejected("초안이 v4 가 아니다(옛 초안에는 시작 시각 원문·저장 후 시각이 없다 — DB 값을 복사해 채우지 않는다)")
    for s in draft.get("sessions") or []:
        k = f"{s.get('pair_id')}{s.get('arm')}"
        raw, stored = s.get("post_started_at_raw"), s.get("api_stored_started_at")
        if not isinstance(raw, str) or not raw or not isinstance(stored, str) or not stored:
            raise Rejected(f"{k}: 초안에 POST 시작 시각 원문 또는 저장 후 API 시작 시각이 없다")
        if s.get("created_at") != raw:
            raise Rejected(f"{k}: 초안 created_at 이 POST 시작 시각 원문과 다르다")
        if round_to_micros(ts_ns(raw)) != ts_ns(stored):
            raise Rejected(f"{k}: POST 시작 시각 원문과 저장 후 API 시작 시각이 저장 정밀도 계약으로 맞지 않는다")


def complete(draft: dict, db_text: str, protocol: dict, protocol_sha: str, *, draft_sha: str = "", db_source: str = "") -> dict:
    if draft.get("protocol_sha256") != protocol_sha:
        raise Rejected("초안의 프로토콜 sha256 이 프로토콜 파일과 다르다")
    if draft.get("api_reconcile_ok") is not True:
        raise Rejected("초안의 API 대조가 성공이 아니다 — 완성하지 않는다")
    if draft.get("db_verified") is not False:
        raise Rejected("초안이 아니다(db_verified 가 false 가 아님)")
    sessions = draft.get("sessions") or []
    if len(sessions) != 8 or len({s.get("session_id") for s in sessions}) != 8:
        raise Rejected("초안 세션이 8개 서로 다른 ID 가 아니다")
    if {(s.get("pair_id"), s.get("arm")) for s in sessions} != {(p, a) for p in protocol["pairs"] for a in ("A", "B")}:
        raise Rejected("초안의 팔 집합이 P1~P4 × A·B 가 아니다")
    check_draft_v4(draft)
    t0 = _ts(draft["t0"])
    db = parse_db_output(db_text)

    if len(db["db_now"]) != 1 or not db["db_now"][0].get("db_now"):
        raise Rejected("DB 시각 행이 없다")
    db_now = _ts(db["db_now"][0]["db_now"])

    stored = {r.get("id"): r for r in db["stored"]}
    want_ids = {str(s["session_id"]) for s in sessions}
    if len(db["stored"]) != 8 or set(stored) != want_ids:
        raise Rejected("DB 저장 행의 ID 집합이 초안과 다르다")
    fps = {r.get("session_id"): r for r in db["fingerprint"]}
    if len(db["before_t0"]) != 1:
        raise Rejected("T0 이전 거래 행이 없다")
    bt = db["before_t0"][0]
    if _dec(bt.get("orders_before_t0")) != 0 or _dec(bt.get("positions_before_t0")) != 0:
        raise Rejected("T0 이전 주문·포지션이 있다 — 동시 시작이 성립하지 않는다")

    # v4: 시작 시각 정확 대조를 저장값 대조보다 먼저 8개 모두 본다 — 다른 DB/다른 회차의 같은 ID 를 여기서 거절한다.
    mismatched = []
    for s in sorted(sessions, key=lambda x: (x["pair_id"], x["arm"])):
        r = stored[str(s["session_id"])]
        if ts_ns(r.get("started_at")) != ts_ns(s["api_stored_started_at"]):
            mismatched.append(s["pair_id"] + s["arm"])
    if mismatched:
        raise Rejected("DB started_at 이 저장 후 API 시작 시각과 다르다(다른 DB·다른 생성 회차의 같은 ID 일 수 있다): " + ",".join(mismatched))

    out_sessions = []
    c = protocol["common"]
    for s in sorted(sessions, key=lambda x: (x["pair_id"], x["arm"])):
        sid = str(s["session_id"])
        r = stored[sid]
        k = s["pair_id"] + s["arm"]
        if arm_of(r.get("stop_loss_pct")) != s["arm"]:
            raise Rejected(f"{k}: 초안의 팔과 DB 손절 하한이 다르다(ID/팔 뒤바뀜)")
        spec = protocol["pairs"][s["pair_id"]]
        try:
            params = json.loads(r.get("params_json") or "")
        except Exception as e:
            raise Rejected(f"{k}: strategy_params 형식 이상") from e
        checks = [
            (r.get("strategy_name") == spec["strategy"], "전략"), (r.get("coin_pair") == spec["coin"], "코인"),
            (r.get("timeframe") == protocol["timeframe"], "주기"), (r.get("status") == "RUNNING", "상태"),
            (_dec(r.get("initial_capital")) == Decimal(str(c["initial_capital"])), "자본"),
            (_dec(r.get("invest_ratio")) == Decimal(str(c["invest_ratio"])), "투자 비율"),
            (_dec(r.get("max_investment")) == Decimal(str(c["max_investment"])), "상한"),
            (_dec(r.get("max_hold_hours")) == Decimal(str(c["max_hold_hours"])), "보유 시간"),
            (params == protocol["params"], "파라미터"),
            (r.get("before_t0") in ("t", "true", "True"), "started_at < T0"),
        ]
        bad = [name for ok, name in checks if not ok]
        if bad:
            raise Rejected(f"{k}: DB 저장값 불일치 — {','.join(bad)}")
        if _ts(r["started_at"]) >= t0:
            raise Rejected(f"{k}: started_at 이 T0 이후다")
        fp = fps.get(sid)
        if fp is None or _dec(fp.get("hashes")) != 1 or not HASH_RE.match(fp.get("ruleset_hash") or ""):
            raise Rejected(f"{k}: 규칙 지문이 없거나 복수이거나 형식이 다르다")
        out_sessions.append({
            "pair_id": s["pair_id"], "arm": s["arm"], "session_id": sid, "created_at": s["created_at"],
            "post_started_at_raw": s["post_started_at_raw"], "api_stored_started_at": s["api_stored_started_at"],
            "db_started_at": r["started_at"],
            "ruleset_hash": fp["ruleset_hash"],
            "stored": {"strategy": r["strategy_name"], "coin": r["coin_pair"], "timeframe": r["timeframe"],
                       "initial_capital": r["initial_capital"], "invest_ratio": r["invest_ratio"],
                       "max_investment": r["max_investment"], "max_hold_hours": r["max_hold_hours"],
                       "stop_loss_pct": r.get("stop_loss_pct") or "", "params_json": params},
        })
    return {
        "status": "완성본 — 읽기 전용 DB 확인 출력에서 만듦(ab_complete.py v4)", "tool_version": TOOL_VERSION,
        "protocol_sha256": protocol_sha, "code_sha": draft["code_sha"], "t0": draft["t0"], "db_verified": True,
        "db_check": {"status": "확인됨", "source_sha256": hashlib.sha256(db_text.encode("utf-8")).hexdigest(),
                     "db_now": db_now.isoformat(), "db_source": db_source,
                     "started_at_match": "8/8 — 저장 후 API startedAt == psql started_at (UTC 같은 순간, 나노초 정수 비교)",
                     "note": "시각 대조는 같은 생성 회차의 행인지 확인하는 검사이며 DB 서버 신원 인증이 아니다"},
        "draft_sha256": draft_sha,
        "sessions": out_sessions,
    }


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--draft", required=True)
    ap.add_argument("--db-output", required=True)
    ap.add_argument("--protocol", required=True)
    ap.add_argument("--db-source", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    out = Path(a.out)
    if out.exists():
        print(json.dumps({"rejected": "완성본 경로에 파일이 이미 있다 — 덮어쓰지 않는다"}, ensure_ascii=False))
        return 3
    if not a.db_source.strip():
        print(json.dumps({"rejected": "연결 출처(--db-source)가 비었다"}, ensure_ascii=False))
        return 3
    try:
        raw = Path(a.protocol).read_bytes()
        protocol, psha = json.loads(raw.decode("utf-8")), hashlib.sha256(raw).hexdigest()
        draft_raw = Path(a.draft).read_bytes()
        draft = json.loads(draft_raw.decode("utf-8"))
        db_text = Path(a.db_output).read_bytes().decode("utf-8")
        log = complete(draft, db_text, protocol, psha, draft_sha=hashlib.sha256(draft_raw).hexdigest(), db_source=a.db_source)
    except Rejected as e:
        print(json.dumps({"rejected": str(e)}, ensure_ascii=False))
        return 3
    except (OSError, ValueError, KeyError, TypeError) as e:
        print(json.dumps({"rejected": f"입력 형식 이상({type(e).__name__})"}, ensure_ascii=False))
        return 3
    try:
        with open(out, "x", encoding="utf-8") as f:
            f.write(json.dumps(log, ensure_ascii=False, indent=2))
    except FileExistsError:
        print(json.dumps({"rejected": "완성본 경로에 파일이 이미 있다 — 덮어쓰지 않는다"}, ensure_ascii=False))
        return 3
    print(json.dumps({"done": "완성본 작성", "sessions": len(log["sessions"]), "db_output_sha256": log["db_check"]["source_sha256"],
                      "draft_sha256": log["draft_sha256"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
