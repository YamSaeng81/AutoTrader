"""SMA20 단건 생성 기록부 완성기 v1 (총괄 v1.177 D3·D4) — sma20_create 초안 + 읽기 전용 sma20_check.sql 출력 → sma20_creation_log.json.

완성 조건(하나라도 아니면 종료 3, 완성본을 쓰지 않는다):
  - 초안이 sma20_create v1·reconcile_ok=true, DB 메타 출처(reconcile_source)·db_source 가 있다.
  - 확인 출력의 구획 db_now·stored·trades·끝 표지, stored 정확히 1행·같은 ID.
  - 저장 설정이 본문과 같다(전략·코인·주기·상태 RUNNING·초기자본·투자 비율·손절·최대 보유·strategy_params 전체).
  - psql started_at == 초안 db_meta_started_at (나노초 정확 — 다른 DB 의 같은 ID·1µs 차이를 거절), started_at < T0.
  - **확인 시각 db_now < T0**(T0 전에 확인을 끝냈다), T0 전 주문·포지션 0, 전체 주문·포지션 0.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import sys
from decimal import Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "create"))
import ab_create as ab   # noqa: E402

SECTIONS = ("db_now", "stored", "trades")


class Rejected(Exception):
    pass


def sections(text: str) -> dict[str, list[dict]]:
    parts: dict[str, list[str]] = {}
    cur, ended = None, False
    for line in text.splitlines():
        if line.startswith("@@"):
            name = line[2:].strip()
            if name == "end":
                ended, cur = True, None
                continue
            if name in parts or name not in SECTIONS:
                raise Rejected("확인 출력 형식 이상(구획)")
            parts[name], cur = [], name
        elif cur is not None and line.strip():
            parts[cur].append(line)
    if not ended or list(parts) != list(SECTIONS):
        raise Rejected("확인 출력 형식 이상(구획 누락·끝 표지 없음)")
    return {n: list(csv.DictReader(io.StringIO("\n".join(ls)))) for n, ls in parts.items()}


def _num(v) -> Decimal | None:
    return None if v in (None, "") else Decimal(str(v))


def complete(draft: dict, body: dict, text: str) -> dict:
    if draft.get("tool") != "sma20_create v1":
        raise Rejected("sma20_create v1 초안이 아니다")
    if draft.get("reconcile_ok") is not True:
        raise Rejected("초안의 DB 메타 대조가 성공이 아니다")
    if not str(draft.get("reconcile_source", "")).startswith("DB 메타 조회") or not draft.get("db_source"):
        raise Rejected("초안에 DB 메타 조회 출처가 없다")
    bsha = hashlib.sha256(json.dumps(body, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()
    if bsha != draft.get("body_sha256"):
        raise Rejected("본문 해시가 초안과 다르다")
    t = sections(text)
    if len(t["db_now"]) != 1 or len(t["trades"]) != 1:
        raise Rejected("확인 출력 집계 행 수 이상")
    if len(t["stored"]) != 1:
        raise Rejected(f"세션 행이 1개가 아니다({len(t['stored'])}) — 다른 DB·522~587·없는 ID")
    s, n, tr = t["stored"][0], t["db_now"][0], t["trades"][0]
    t0_ns = ab.ts_ns(draft["t0"])
    probs = []
    if s["id"] != draft.get("session_id"):
        probs.append("ID 다름")
    want = {"strategy_name": body["strategyType"], "coin_pair": body["coinPair"], "timeframe": body["timeframe"], "status": "RUNNING"}
    probs += [f"{k} 다름" for k, v in want.items() if s.get(k) != v]
    for col, key in (("initial_capital", "initialCapital"), ("invest_ratio", "investRatio"), ("stop_loss_pct", "stopLossPct"),
                     ("max_hold_hours", "maxHoldHours")):
        if _num(s.get(col)) != _num(body.get(key)):
            probs.append(f"{col} 다름")
    try:
        if json.loads(s.get("params_json") or "null") != body["strategyParams"]:
            probs.append("strategy_params 다름")
    except ValueError:
        probs.append("strategy_params 형식 이상")
    try:
        if ab.ts_ns(s["started_at"]) != ab.ts_ns(draft.get("db_meta_started_at")):
            probs.append("started_at 이 초안 DB 메타 값과 나노초까지 같지 않다(다른 DB·저장값 다름)")
        if not ab.ts_ns(s["started_at"]) < t0_ns or s.get("before_t0") != "t":
            probs.append("started_at 이 T0 전이 아니다")
        if not ab.ts_ns(n["db_now"]) < t0_ns or n.get("before_t0_now") != "t":
            probs.append("확인 시각이 T0 전이 아니다")
    except (ValueError, KeyError):
        probs.append("시각 형식 이상")
    for k in ("orders_all", "orders_before_t0", "positions_all", "positions_before_t0"):
        if tr.get(k) != "0":
            probs.append(f"{k}={tr.get(k)} (0 이어야 한다)")
    if probs:
        raise Rejected("; ".join(probs))
    return {"status": "완성본 — 읽기 전용 DB 확인 출력에서 만듦(sma20_complete.py v1)", "db_verified": True,
            **{k: draft[k] for k in ("tool", "creator_version", "code_sha", "body_sha256", "forward_tag", "window_start", "window_end",
                                     "t0", "t0_rule", "session_id", "post_started_at_raw", "db_meta_started_at", "db_source", "reconcile_source")},
            "db_started_at": s["started_at"], "db_check_now": n["db_now"],
            "db_check_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
            "pair_compare_note": "쌍 비교 구간은 LIVE 203 실제 started_at 부터(그 전은 선행 구간) — 이 기록부는 SMA20 쪽 T0 만 고정한다"}


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--draft", required=True)
    ap.add_argument("--body", required=True)
    ap.add_argument("--db-output", required=True)
    ap.add_argument("--check-db-source", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    try:
        draft = json.loads(Path(a.draft).read_text(encoding="utf-8"))
        body = json.loads(Path(a.body).read_text(encoding="utf-8"))
        log = complete(draft, body, Path(a.db_output).read_text(encoding="utf-8"))
    except Rejected as e:
        print(json.dumps({"rejected": str(e)}, ensure_ascii=False))
        return 3
    log["check_db_source"] = a.check_db_source
    Path(a.out).write_text(json.dumps(log, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps({"done": "SMA20 완성본", "session_id": log["session_id"], "t0": log["t0"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
