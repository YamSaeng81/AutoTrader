"""완성 기록부 → arms.csv 변환 도구 (총괄 v1.151 §3 "arms.csv 변환은 도구로 고정"·v1.152 C, 2026-11-11).

시험 헬퍼를 손으로 부르거나 CSV 를 손으로 고치지 않도록, 완성본(ab_complete.py v4 출력)과 불변 프로토콜에서만 arms.csv 를 만든다.
평가 기준·비용·A/B 설정을 바꾸는 도구가 아니다. 집계기(ab-create-v3 tool/ab_aggregate.py, 원문 그대로)의 설정 검사를 쓰기 전에 통과해야 한다.

입력:  --complete  creation_log.json (v4 완성본: db_verified=true, tool_version=v4, 시작 시각 대조 근거 포함)
       --protocol  protocol.json (완성본의 protocol_sha256 과 같아야 한다)
       --db-output 완성기가 읽은 확인 SQL 출력 원문. sha256 이 완성본 db_check.source_sha256 과 같아야 하고, ID 마다 저장값·시작 시각·
                   규칙 지문이 완성본 항목과 같아야 한다(완성본만으로는 ID↔팔 대응을 확인할 수 없음 — 1차 시험에서 ID 맞바꿈 통과).
       --out       arms.csv 경로. 같은 이름 + ".meta.json" 에 입력/출력 해시를 남긴다. 둘 중 하나라도 이미 있으면 거절(덮어쓰기 금지).
출력 열: pair_id,arm,session_id,strategy,coin,timeframe,initial_capital,invest_ratio,max_investment,max_hold_hours,stop_loss_pct,
        params_json,ruleset_hash,code_sha,start_ts,end_ts   (집계기 입력 형식 그대로)
변환 규칙(고정):
  - 정확히 8팔(P1~P4 × A·B), 팔마다 ID 1개, ID 8개 서로 다름. 행 순서는 P1A,P1B,…,P4B.
  - params_json: 완성본의 객체 → JSON 문자열 json.dumps(obj, sort_keys=True, ensure_ascii=False, separators=(",", ":")). 이미 문자열이면 거절
    (완성본 v4 는 항상 객체다 — 두 형식이 섞인 입력을 받아 주지 않는다).
  - 저장값(전략·코인·주기·자본·비율·상한·보유·하한)은 완성본의 DB 값 문자열 그대로. code_sha 는 완성본 값(40자 16진).
  - start_ts = 완성본 t0 (UTC ISO), end_ts = t0 + protocol.days(42)일.
거절(종료 3, 출력 0개): 완성본 아님(초안·db_verified≠true·v4 아님·시작 시각 근거 없음), 프로토콜 sha 불일치, 팔·ID 누락/중복/뒤바뀜
  (A 인데 하한 5 등), 필수값 누락, code_sha 형식, 집계기 설정 검사 실패, 출력 경로 존재.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
import sys
from datetime import timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
V3_TOOL = HERE.parents[1] / "ab-create-v3-20261111" / "tool"
V4_CREATE = HERE.parent / "create"
sys.path.insert(0, str(V3_TOOL))
sys.path.insert(0, str(V4_CREATE))

import ab_aggregate as ag   # noqa: E402  (v3 원문 그대로)
import ab_complete as cp    # noqa: E402  (v4 — 시각 파서)

FIELDS = ["pair_id", "arm", "session_id", "strategy", "coin", "timeframe", "initial_capital", "invest_ratio", "max_investment",
          "max_hold_hours", "stop_loss_pct", "params_json", "ruleset_hash", "code_sha", "start_ts", "end_ts"]
ORDER = [(p, a) for p in ("P1", "P2", "P3", "P4") for a in ("A", "B")]
SHA40_RE = re.compile(r"^[0-9a-f]{40}$")
ID_RE = re.compile(r"^[1-9][0-9]*$")


class Rejected(Exception):
    pass


def canonical_params(obj) -> str:
    if not isinstance(obj, dict):
        raise Rejected("params_json 이 객체가 아니다(완성본 v4 는 객체여야 한다)")
    return json.dumps(obj, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def check_against_db_output(log: dict, db_text: str) -> None:
    """완성본만으로는 ID↔팔 대응을 확인할 수 없다(1차 시험: 같은 A 팔 둘의 ID 를 맞바꾼 완성본이 통과). 완성기가 읽은 DB 출력 원문을
    다시 받아 sha256 이 완성본 기록과 같은지 보고, ID 마다 저장값·시작 시각·규칙 지문이 완성본 항목과 같은지 대조한다."""
    if hashlib.sha256(db_text.encode("utf-8")).hexdigest() != (log.get("db_check") or {}).get("source_sha256"):
        raise Rejected("DB 출력 원문의 sha256 이 완성본의 db_check.source_sha256 과 다르다")
    db = cp.parse_db_output(db_text)
    stored = {r.get("id"): r for r in db["stored"]}
    fps = {r.get("session_id"): r for r in db["fingerprint"]}
    for s in log.get("sessions") or []:
        k = f"{s.get('pair_id')}{s.get('arm')}"
        r = stored.get(str(s.get("session_id")))
        if r is None:
            raise Rejected(f"{k}: DB 출력에 이 ID 가 없다")
        st = s.get("stored") or {}
        pairs = [("strategy", "strategy_name"), ("coin", "coin_pair"), ("timeframe", "timeframe"), ("initial_capital", "initial_capital"),
                 ("invest_ratio", "invest_ratio"), ("max_investment", "max_investment"), ("max_hold_hours", "max_hold_hours")]
        bad = [a for a, b in pairs if str(st.get(a)) != str(r.get(b))]
        if (st.get("stop_loss_pct") or "") != (r.get("stop_loss_pct") or ""):
            bad.append("stop_loss_pct")
        try:
            if json.loads(r.get("params_json") or "") != st.get("params_json"):
                bad.append("params_json")
        except ValueError:
            bad.append("params_json")
        if cp.ts_ns(r.get("started_at")) != cp.ts_ns(s.get("db_started_at")):
            bad.append("started_at")
        fp = fps.get(str(s.get("session_id")))
        if fp is None or fp.get("ruleset_hash") != s.get("ruleset_hash"):
            bad.append("ruleset_hash")
        if bad:
            raise Rejected(f"{k}: 완성본 항목이 DB 출력의 같은 ID 행과 다르다 — {','.join(bad)}")


def build_rows(log: dict, protocol: dict, protocol_sha: str) -> list[dict]:
    if log.get("db_verified") is not True or log.get("tool_version") != "v4":
        raise Rejected("완성본 v4 가 아니다(db_verified=true·tool_version=v4 필요 — 초안·옛 완성본은 변환하지 않는다)")
    if log.get("protocol_sha256") != protocol_sha:
        raise Rejected("완성본의 protocol_sha256 이 프로토콜 파일과 다르다")
    chk = log.get("db_check") or {}
    if chk.get("status") != "확인됨" or not str(chk.get("started_at_match") or "").startswith("8/8"):
        raise Rejected("완성본에 DB 확인·시작 시각 대조 근거가 없다")
    code = str(log.get("code_sha") or "")
    if not SHA40_RE.match(code):
        raise Rejected("code_sha 가 40자 16진이 아니다")
    t0 = cp._ts(log.get("t0") or "")
    end = t0 + timedelta(days=int(protocol["days"]))
    sessions = log.get("sessions")
    if not isinstance(sessions, list) or len(sessions) != 8:
        raise Rejected("완성본 세션이 정확히 8개가 아니다")
    by_key = {}
    for s in sessions:
        k = (s.get("pair_id"), s.get("arm"))
        if k in by_key:
            raise Rejected(f"팔 {k} 가 둘")
        by_key[k] = s
    if set(by_key) != set(ORDER):
        raise Rejected("팔 집합이 P1~P4 × A·B 가 아니다")
    ids = [str(s.get("session_id") or "") for s in sessions]
    if len(set(ids)) != 8 or not all(ID_RE.match(i) for i in ids):
        raise Rejected("세션 ID 가 8개 서로 다른 양의 정수가 아니다")
    rows = []
    for pid, arm in ORDER:
        s = by_key[(pid, arm)]
        st = s.get("stored")
        if not isinstance(st, dict) or any(k not in st for k in ag.STORED_KEYS):
            raise Rejected(f"{pid}{arm} 저장값 키가 모자란다")
        for k in ("post_started_at_raw", "api_stored_started_at", "db_started_at"):
            if not s.get(k):
                raise Rejected(f"{pid}{arm} 시작 시각 근거({k})가 없다")
        if cp.ts_ns(s["db_started_at"]) != cp.ts_ns(s["api_stored_started_at"]):
            raise Rejected(f"{pid}{arm} 완성본 안의 DB 시작 시각과 저장 후 API 시작 시각이 다르다")
        try:
            if cp.arm_of(st.get("stop_loss_pct")) != arm:
                raise Rejected(f"{pid}{arm} 팔과 저장 하한이 다르다(뒤바뀜)")
        except cp.Rejected as e:
            raise Rejected(f"{pid}{arm} 하한 형식 이상") from e
        if not cp.HASH_RE.match(str(s.get("ruleset_hash") or "")):
            raise Rejected(f"{pid}{arm} 규칙 지문 형식 이상")
        row = {"pair_id": pid, "arm": arm, "session_id": str(s["session_id"])}
        for k in ag.STORED_KEYS:
            v = st.get(k)
            if k == "params_json":
                row[k] = canonical_params(v)
            else:
                if v is None or (k != "stop_loss_pct" and str(v).strip() == ""):
                    raise Rejected(f"{pid}{arm} 저장값 {k} 가 비었다")
                row[k] = str(v)
        row.update({"ruleset_hash": s["ruleset_hash"], "code_sha": code, "start_ts": t0.isoformat(), "end_ts": end.isoformat()})
        rows.append(row)
    return rows


def to_csv_bytes(rows: list[dict]) -> bytes:
    buf = io.StringIO()
    w = csv.DictWriter(buf, fieldnames=FIELDS, lineterminator="\n")
    w.writeheader()
    w.writerows(rows)
    return buf.getvalue().encode("utf-8")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--complete", required=True)
    ap.add_argument("--protocol", required=True)
    ap.add_argument("--db-output", required=True, help="완성기가 읽은 확인 SQL 출력 원문(sha256 이 완성본 기록과 같아야 한다)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    out = Path(a.out)
    meta = out.with_name(out.name + ".meta.json")
    if out.exists() or meta.exists():
        print(json.dumps({"rejected": "출력 경로(arms.csv 또는 .meta.json)에 파일이 이미 있다 — 덮어쓰지 않는다"}, ensure_ascii=False))
        return 3
    try:
        p_raw = Path(a.protocol).read_bytes()
        protocol, psha = json.loads(p_raw.decode("utf-8")), hashlib.sha256(p_raw).hexdigest()
        c_raw = Path(a.complete).read_bytes()
        log = json.loads(c_raw.decode("utf-8"))
        rows = build_rows(log, protocol, psha)
        db_raw = Path(a.db_output).read_bytes()
        check_against_db_output(log, db_raw.decode("utf-8"))
        # 집계기 설정 검사(프로토콜 + 완성본 대조)를 쓰기 전에 통과해야 한다 — CSV 왕복 뒤 값으로 본다.
        data = to_csv_bytes(rows)
        back = list(csv.DictReader(io.StringIO(data.decode("utf-8"))))
        ag.check_config(back, log, Path(a.protocol))
    except (Rejected, cp.Rejected, ag.ConfigRejected) as e:
        print(json.dumps({"rejected": str(e)}, ensure_ascii=False))
        return 3
    except (OSError, ValueError, KeyError, TypeError) as e:
        print(json.dumps({"rejected": f"입력 형식 이상({type(e).__name__})"}, ensure_ascii=False))
        return 3
    info = {"tool": "ab_arms.py (ab-create-v4-20261111)", "complete_sha256": hashlib.sha256(c_raw).hexdigest(), "protocol_sha256": psha,
            "db_output_sha256": hashlib.sha256(db_raw).hexdigest(),
            "arms_csv_sha256": hashlib.sha256(data).hexdigest(), "rows": len(rows), "t0": rows[0]["start_ts"], "end": rows[0]["end_ts"],
            "code_sha": rows[0]["code_sha"]}
    try:
        with open(meta, "x", encoding="utf-8") as f:
            f.write(json.dumps(info, ensure_ascii=False, indent=2) + "\n")
    except FileExistsError:
        print(json.dumps({"rejected": "출력 경로에 파일이 생겼다 — 덮어쓰지 않는다"}, ensure_ascii=False))
        return 3
    try:
        with open(out, "xb") as f:
            f.write(data)
    except OSError:
        meta.unlink()   # 이 실행이 방금 만든 meta 만 지운다 — 출력은 둘 다 있거나 둘 다 없다
        print(json.dumps({"rejected": "arms.csv 를 쓰지 못했다(이미 있거나 쓰기 실패) — 출력 없음"}, ensure_ascii=False))
        return 3
    print(json.dumps({"done": "arms.csv 작성", **info}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
