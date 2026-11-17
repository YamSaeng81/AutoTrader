"""preflight_v2.sql 출력 판정(총괄 v1.178 §4-3·§4-4) — 생성 전 1회. 하나라도 어기면 종료 2(보내지 않음), 형식 이상 3.
조건: 구획·끝 표지, 봉 3조 모두 normal, 세 (전략·주기) 모두 enabled, cap_ok, 중복 후보 중 9본문과 같은 서명 또는 forwardTag=SMA20-FWD-1 행 0.
출력은 고정 사유와 개수·ID 만(522~587 은 SQL 이 행으로 주지 않는다).
"""
from __future__ import annotations

import csv
import io
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "create"))
import ab_create as ab   # noqa: E402

SECTIONS = ("db_now", "enablement", "candles", "sessions", "dup_candidates")


def parse(text: str) -> dict[str, list[dict]]:
    parts, cur, ended = {}, None, False
    for line in text.splitlines():
        if line.startswith("@@"):
            name = line[2:].strip()
            if name == "end":
                ended, cur = True, None
                continue
            if name in parts or name not in SECTIONS:
                raise ValueError("구획 이상")
            parts[name], cur = [], name
        elif cur and line.strip():
            parts[cur].append(line)
    if not ended or list(parts) != list(SECTIONS):
        raise ValueError("구획 누락·끝 표지 없음")
    return {k: list(csv.DictReader(io.StringIO("\n".join(v)))) for k, v in parts.items()}


def judge(text: str, protocol: Path, sma20_body: Path) -> tuple[int, dict]:
    try:
        t = parse(text)
    except ValueError as e:
        return 3, {"stop": f"형식 이상 — {e}"}
    probs = []
    st = {f"{r['coin_pair']}/{r['timeframe']}": r["status"] for r in t["candles"]}
    if len(st) != 3 or any(v != "normal" for v in st.values()):
        probs.append(f"봉 상태 {st}")
    en = {f"{r['strategy_name']}@{r['timeframe']}": r["enabled"] for r in t["enablement"]}
    if len(en) != 3 or any(v != "t" for v in en.values()):
        probs.append(f"전략 활성 {en}")
    s = t["sessions"][0] if len(t["sessions"]) == 1 else {}
    if s.get("cap_ok") != "t":
        probs.append(f"동시 세션 상한 running={s.get('running_total')} + {s.get('n_new')} > {s.get('cap')}")
    proto, _ = ab.load_protocol(protocol)
    bodies = [p["body"] for p in ab.bodies_from_protocol(proto)] + [json.loads(sma20_body.read_text(encoding="utf-8"))]
    sigs = {ab.body_signature(b) for b in bodies}
    dup = []
    for r in t["dup_candidates"]:
        row = {"strategyName": r["strategy_name"], "coinPair": r["coin_pair"], "timeframe": r["timeframe"],
               "initialCapital": r["initial_capital"], "strategyParams": json.loads(r["params_json"]) if r["params_json"] else None}
        tag = isinstance(row["strategyParams"], dict) and row["strategyParams"].get("forwardTag") == "SMA20-FWD-1"
        if tag or (r["status"] == "RUNNING" and ab.row_signature(row) in sigs):
            dup.append(int(r["id"]))
    if dup:
        probs.append(f"같은 서명·표지 세션 {sorted(dup)}")
    info = {"db_now": t["db_now"][0].get("db_now") if t["db_now"] else None, "candles": st, "enablement": en,
            "running_total": s.get("running_total"), "running_prereg": s.get("running_prereg"), "max_id": s.get("max_id"), "dup_ids": sorted(dup)}
    if probs:
        return 2, {"stop": "사전 확인 실패 — 보내지 않는다", "problems": probs, **info}
    return 0, {"ok": "사전 확인 통과", **info}


def main(argv: list[str]) -> int:
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--preflight-output", required=True)
    ap.add_argument("--protocol", default=str(HERE.parent / "tool" / "protocol.json"))
    ap.add_argument("--sma20-body", default=str(HERE.parent / "sma20" / "body.json"))
    a = ap.parse_args(argv)
    rc, info = judge(Path(a.preflight_output).read_text(encoding="utf-8"), Path(a.protocol), Path(a.sma20_body))
    print(json.dumps(info, ensure_ascii=False))
    return rc


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
