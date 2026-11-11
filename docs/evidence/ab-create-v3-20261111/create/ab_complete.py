"""생성 기록부 완성기 (총괄 v1.149 §4-2·§4-1.5) — API 초안 + 읽기 전용 DB 확인 출력 → 완성 기록부(creation_log.json).

입력:
  --draft      ab_create.py 가 쓴 creation_log.draft.json (db_verified=false, api_reconcile_ok=true 여야 한다)
  --db-output  post_creation_check.sql 의 psql --csv 출력(@@ 구획). 이 파일의 sha256 을 완성본에 남긴다.
  --protocol   불변 프로토콜(protocol.json) — 초안의 protocol_sha256 과 같아야 한다.
  --out        완성 기록부를 쓸 경로(모든 검사를 통과할 때만 쓴다).

팔 확정: 같은 쌍의 A·B 는 API 로 구분되지 않는다. 초안의 (쌍, 팔, ID)를 DB 저장값으로 확정한다 — A 는 stop_loss_pct 빈 값, B 는 5.
초안과 DB 가 다르면(ID/팔 뒤바뀜) 거절한다. 응답 불명 뒤 잠정 후보는 초안에 들어오지 않으며(ab_create 가 종료 3), 이 도구가 채우지 않는다.
거절(종료 3, 완성본 없음): DB 출력 구획 누락·형식 이상, ID 집합 불일치, 팔 뒤바뀜, 저장값(전략·코인·주기·자본·비율·상한·보유·하한·파라미터)
불일치, RUNNING 아님, started_at ≥ T0, 지문 없음·복수·형식 이상, T0 이전 주문·포지션, 초안이 API 대조 실패.
🔴 DB 를 읽지 않고 db_verified=true 만 붙인 파일은 확인 근거가 아니다. 완성본은 이 도구가 DB 확인 출력에서 만든 것만 쓴다.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
import sys
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

HASH_RE = re.compile(r"^[0-9a-f]{12}$")
SECTIONS = ("db_now", "stored", "fingerprint", "before_t0")
STORED_KEYS = ("strategy", "coin", "timeframe", "initial_capital", "invest_ratio", "max_investment", "max_hold_hours",
               "stop_loss_pct", "params_json")


class Rejected(Exception):
    pass


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
    """@@구획 → CSV 행. 구획 순서·누락·끝 표지까지 본다."""
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
    out = {}
    for name, lines in parts.items():
        out[name] = list(csv.DictReader(io.StringIO("\n".join(lines))))
    return out


def arm_of(stop_loss) -> str:
    v = _dec(stop_loss)
    if v is None:
        return "A"
    if v == Decimal("5"):
        return "B"
    raise Rejected("stop_loss_pct 가 A(빈 값)·B(5) 어느 쪽도 아니다")


def complete(draft: dict, db_text: str, protocol: dict, protocol_sha: str) -> dict:
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
            "ruleset_hash": fp["ruleset_hash"],
            "stored": {"strategy": r["strategy_name"], "coin": r["coin_pair"], "timeframe": r["timeframe"],
                       "initial_capital": r["initial_capital"], "invest_ratio": r["invest_ratio"],
                       "max_investment": r["max_investment"], "max_hold_hours": r["max_hold_hours"],
                       "stop_loss_pct": r.get("stop_loss_pct") or "", "params_json": params},
        })
    return {
        "status": "완성본 — 읽기 전용 DB 확인 출력에서 만듦(ab_complete.py)",
        "protocol_sha256": protocol_sha, "code_sha": draft["code_sha"], "t0": draft["t0"], "db_verified": True,
        "db_check": {"status": "확인됨", "source_sha256": hashlib.sha256(db_text.encode("utf-8")).hexdigest(),
                     "db_now": db_now.isoformat()},
        "sessions": out_sessions,
    }


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--draft", required=True)
    ap.add_argument("--db-output", required=True)
    ap.add_argument("--protocol", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    raw = Path(a.protocol).read_bytes()
    protocol, psha = json.loads(raw.decode("utf-8")), hashlib.sha256(raw).hexdigest()
    try:
        draft = json.loads(Path(a.draft).read_text(encoding="utf-8"))
        db_text = Path(a.db_output).read_bytes().decode("utf-8")
        log = complete(draft, db_text, protocol, psha)
    except Rejected as e:
        print(json.dumps({"rejected": str(e)}, ensure_ascii=False))
        return 3
    except (OSError, ValueError, KeyError) as e:
        print(json.dumps({"rejected": f"입력 형식 이상({type(e).__name__})"}, ensure_ascii=False))
        return 3
    out = Path(a.out)
    if out.exists():
        print(json.dumps({"rejected": "완성본 경로에 파일이 이미 있다 — 덮어쓰지 않는다"}, ensure_ascii=False))
        return 3
    out.write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"done": "완성본 작성", "sessions": len(log["sessions"]), "db_output_sha256": log["db_check"]["source_sha256"]},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
