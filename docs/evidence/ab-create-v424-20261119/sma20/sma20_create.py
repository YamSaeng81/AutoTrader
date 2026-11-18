"""SMA20 전향 모의 단건 생성 호출기 v1 (총괄 v1.177 D3·D4·D5, v1.178 R1·§4-2) — 실제 생성 승인 전이다. 로컬 대역·격리 로컬 PG 로만 시험한다.

8팔 계약(ab_create)을 1팔로 억지로 맞추지 않고, 그 함수만 다시 쓴다(같은 폴더 create/ 의 v4.2):
  - 전송: http_post(v4.2) — 생성 POST 한 번, 어떤 3xx 도 따라가지 않음, 목록 GET 없음, 재시도 없음.
  - 응답 분류(ab_create.classify), 기록부(flush+fsync, 파손·다른 본문/코드/T0 거절), 기록 폴더 잠금, DB 메타 조회(열 제한·522~587 제외),
    저장 정밀도 계약(나노초 → 마이크로초 반올림), --db-cmd 비밀번호 거절.
SMA20 만의 규칙:
  - 본문은 고정 파일(body.json) 하나. 전략 DAILY_SMA20·KRW-BTC·H1·LIVE_ALIGNED·forwardTag=SMA20-FWD-1(D5)이 아니면 보내지 않는다.
  - **생성 창과 T0 를 POST 전에 인자로 고정한다(D4).** T0 = 창 시작 뒤 첫 09:00 KST(00:00 UTC). 창은 그 전날 10:00 KST(01:00 UTC) 이후에
    시작하고 T0 - 확인 여유(기본 30분) 안에 끝나야 한다 — 09:00~09:59 KST 생성 금지(첫 평가가 23:00 UTC 일봉 마감을 볼 수 있음), 생성 뒤 DB 확인을
    T0 전에 끝낼 시간. 인자 --t0 가 계산값과 다르면 보내지 않는다(T0 를 옮겨 통과시키지 않는다).
  - 생성 반복 금지: 상태와 무관하게 forwardTag=SMA20-FWD-1 세션이 이미 있거나, 기록부에 전송 의도가 하나라도 있으면 보내지 않는다.
  - 동시 세션 상한: RUNNING + 1 > --session-cap 이면 보내지 않는다.
  - DB 시각과 도구 시각이 60초 넘게 다르면 보내지 않는다(창·T0 판단의 시계 전제).
  - 토큰 파일(D2: 운영자가 준비한 권한 600 파일)은 POSIX 에서 그룹·기타 권한이 있으면 보내지 않는다. 경로만 기록하고 내용은 어디에도 쓰지 않는다.
종료 코드: 0 = OK·DB 메타 대조 성공(확인 SQL 전 초안), 2 = 보낸 것 없음, 3 = 멈춤/불명/대조 실패, 4 = 기록부·잠금 때문에 보내지 않음(사람 판단).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "create"))
import ab_create as ab   # noqa: E402  (v4.2 — 분류·기록부·잠금·메타 조회·정밀도)
import http_post   # noqa: E402

TOOL = "sma20_create v1"
KEY = "SMA20"
FORWARD_TAG = "SMA20-FWD-1"
PREFLIGHT_SQL = HERE / "sma20_meta_preflight.sql"
CLOCK_SKEW_MAX = timedelta(seconds=60)
JOURNAL = "journal.jsonl"


def first_t0_after(t: datetime) -> datetime:
    """t 뒤 첫 00:00 UTC(= 09:00 KST). t 가 정확히 00:00 이면 다음 날."""
    d = t.astimezone(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    return d + timedelta(days=1)


def window_problem(ws: datetime, we: datetime, t0: datetime, margin: timedelta) -> str | None:
    if ws.tzinfo is None or we.tzinfo is None or t0.tzinfo is None:
        return "창·T0 에 시간대가 없다"
    if not ws < we:
        return "창 시작이 끝보다 늦다"
    want = first_t0_after(ws)
    if t0 != want:
        return f"--t0 가 창 시작 뒤 첫 09:00 KST({want.isoformat()})가 아니다 — T0 를 옮기지 않는다"
    if ws < want - timedelta(hours=23):
        return "창이 09:00~09:59 KST(00:00~00:59 UTC)에 걸린다 — 첫 평가가 일봉 마감을 볼 수 있다"
    if we > t0 - margin:
        return f"창 끝이 T0 - {int(margin.total_seconds() // 60)}분보다 늦다 — 생성 뒤 DB 확인을 T0 전에 끝낼 수 없다"
    return None


def load_body(path: Path) -> tuple[dict, str, str]:
    raw = path.read_bytes()
    body = json.loads(raw.decode("utf-8"))
    canon = hashlib.sha256(json.dumps(body, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()
    return body, hashlib.sha256(raw).hexdigest(), canon


def body_problem(b: dict) -> str | None:
    sp = b.get("strategyParams") if isinstance(b, dict) else None
    if not isinstance(sp, dict):
        return "본문 strategyParams 없음"
    want = {"strategyType": "DAILY_SMA20", "coinPair": "KRW-BTC", "timeframe": "H1"}
    for k, v in want.items():
        if b.get(k) != v:
            return f"본문 {k} 가 {v} 가 아니다"
    if sp.get("forwardTag") != FORWARD_TAG:
        return f"본문 forwardTag 가 {FORWARD_TAG} 가 아니다(D5)"
    if sp.get("paperExecutionMode") != "LIVE_ALIGNED":
        return "본문 paperExecutionMode 가 LIVE_ALIGNED 가 아니다"
    return None


def token_perm(path: Path) -> tuple[str, bool]:
    """(기록용 상태, 허용). POSIX 는 그룹·기타 권한 0 이어야 한다(600/400). Windows 는 모드로 판단할 수 없어 '확인 불가'로 적고 허용한다."""
    st = path.stat()
    if os.name != "posix":
        return "확인 불가(POSIX 아님)", True
    mode = stat.S_IMODE(st.st_mode)
    return f"{mode:03o}", (mode & 0o077) == 0


def tagged(r: dict) -> bool:
    sp = r.get("strategyParams")
    return isinstance(sp, dict) and sp.get("forwardTag") == FORWARD_TAG


class SMA20Meta(ab.MetaDB):
    def preflight(self):
        return ab.parse_meta(*ab.run_psql(self._cmd, PREFLIGHT_SQL, {}))


def journal_problem(prior: list[dict], body_sha: str, code_sha: str, t0: str) -> str | None:
    for r in prior:
        if "body_sha256" in r and r["body_sha256"] != body_sha:
            return "본문이 다른 기록부다"
        if "code_sha" in r and r["code_sha"] != code_sha:
            return "다른 대상 코드의 기록부다"
        if "t0" in r and r["t0"] != t0:
            return "T0 가 다른 기록부다 — T0 를 옮기지 않는다"
    if any(r["kind"] == "intent" for r in prior):
        return "이미 전송 의도가 있는 기록부다 — 생성 반복 금지(결과와 무관)"
    if any(r["kind"] == "result" for r in prior):
        return "이미 결과가 있는 기록부다"
    return None


def after_check(meta: ab.MetaDB, body: dict, max_id: int, ok_id: int | None) -> dict:
    m, problem = meta.after(max_id)
    if problem:
        return {"status": problem, "source": "DB 메타 조회"}
    sig = ab.body_signature(body)
    hits = sorted(r["id"] for r in m["rows"] if ab.row_signature(r) == sig or tagged(r))
    rec = {"status": "조회됨", "source": "DB 메타 조회", "meta_sha256": m["sha256"], "db_now": m["db_now"], "hits": hits,
           "other_new_ids": sorted(r["id"] for r in m["rows"] if r["id"] not in hits)}
    if ok_id is not None:
        row = next((r for r in m["rows"] if r["id"] == ok_id), None)
        rec["stored_started_at"] = row.get("startedAt") if row else None
    else:
        rec["provisional"] = hits   # 응답 불명 뒤 새 후보 — 귀속 아님
    return rec


def run(argv: list[str], now: datetime | None = None, clock: Callable[[], datetime] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True, help="검토한 backend 직접 경로(프록시·로그인 경유 금지)")
    ap.add_argument("--token-file", required=True, help="운영자가 준비한 권한 600 토큰 파일 경로(D2). 내용은 기록하지 않는다")
    ap.add_argument("--body", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--code-sha", required=True)
    ap.add_argument("--window-start", required=True, help="생성 창 시작(ISO, 시간대 필수)")
    ap.add_argument("--window-end", required=True)
    ap.add_argument("--t0", required=True, help="창 시작 뒤 첫 09:00 KST(ISO). 계산값과 같아야 한다")
    ap.add_argument("--check-margin-min", type=int, default=30)
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--db-cmd")
    ap.add_argument("--db-source")
    ap.add_argument("--session-cap", type=int, default=ab.DEFAULT_SESSION_CAP)
    ap.add_argument("--execute", action="store_true")
    a = ap.parse_args(argv)
    if clock is None:
        clock = (lambda: now) if now is not None else (lambda: datetime.now(timezone.utc))
    out = Path(a.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    body, raw_sha, body_sha = load_body(Path(a.body))
    try:
        ws, we, t0 = ab._ts(a.window_start), ab._ts(a.window_end), ab._ts(a.t0)
    except ValueError:
        print(json.dumps({"stop": "창·T0 형식 이상 — 보낸 것 없음"}, ensure_ascii=False))
        return 2
    margin = timedelta(minutes=a.check_margin_min)
    problem = body_problem(body) or (None if a.check_margin_min >= 10 else "확인 여유가 10분보다 짧다") or window_problem(ws, we, t0, margin)
    plan = {"tool": TOOL, "creator_version": ab.CREATOR_VERSION, "endpoint": "POST " + ab.ENDPOINT, "body": body,
            "body_sha256": body_sha, "body_file_sha256": raw_sha, "window_start": ws.isoformat(), "window_end": we.isoformat(),
            "t0": t0.isoformat(), "check_margin_min": a.check_margin_min, "plan_problem": problem}
    (out / "plan.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8")
    if problem:
        print(json.dumps({"stop": f"{problem} — 보낸 것 없음"}, ensure_ascii=False))
        return 2
    if not a.execute:
        print(json.dumps({"mode": "계획만", "t0": t0.isoformat(), "body_sha256": body_sha}, ensure_ascii=False))
        return 2
    try:
        db_cmd = ab.parse_db_cmd(a.db_cmd or "")
        if not (a.db_source or "").strip():
            raise ValueError("--db-source 가 비었다")
        perm, perm_ok = token_perm(Path(a.token_file))
    except (ValueError, OSError) as e:
        print(json.dumps({"stop": f"설정 이상 — {type(e).__name__}: {e if isinstance(e, ValueError) else '토큰 파일을 볼 수 없다'}. 보낸 것 없음"},
                         ensure_ascii=False))
        return 2
    if not perm_ok:
        print(json.dumps({"stop": f"토큰 파일 권한 {perm} — 그룹·기타 권한이 있다(600 필요). 보낸 것 없음"}, ensure_ascii=False))
        return 2
    if not ab.acquire_lock(out):
        print(json.dumps({"stop": "기록 폴더 잠금이 이미 있다 — 사람 판단 전에는 보내지 않는다"}, ensure_ascii=False))
        return 4
    try:
        return _execute(a, clock, out, body, body_sha, raw_sha, ws, we, t0, perm, SMA20Meta(db_cmd))
    except ab.JournalError as e:
        print(json.dumps({"stop": f"기록부 문제 — {e}. 더 보내지 않는다(사람 판단)"}, ensure_ascii=False))
        return 4
    finally:
        ab.release_lock(out)


def _execute(a, clock, out: Path, body: dict, body_sha: str, raw_sha: str, ws: datetime, we: datetime, t0: datetime,
             perm: str, meta: ab.MetaDB) -> int:
    journal = out / JOURNAL
    prior = ab.journal_lines(journal)
    jp = journal_problem(prior, body_sha, a.code_sha, t0.isoformat())
    if jp:
        print(json.dumps({"stop": jp}, ensure_ascii=False))
        return 4
    token = Path(a.token_file).read_text(encoding="utf-8").strip()
    client = ab.Client(a.base_url, token, a.timeout)
    del token

    start = clock()
    inside = ws <= start <= we
    ab.append(journal, {"kind": "preflight", "at": start.isoformat(), "tool": TOOL, "creator_version": ab.CREATOR_VERSION,
                        "body_sha256": body_sha, "body_file_sha256": raw_sha, "code_sha": a.code_sha, "t0": t0.isoformat(),
                        "window_start": ws.isoformat(), "window_end": we.isoformat(), "inside_window": inside,
                        "db_source": a.db_source, "token_file": str(a.token_file), "token_file_mode": perm, "base_url": a.base_url})
    if not inside:
        print(json.dumps({"stop": f"창 밖({start.isoformat()}) — 보낸 것 없음"}, ensure_ascii=False))
        return 2
    m, lp = meta.preflight()
    if lp:
        ab.append(journal, {"kind": "preflight_meta", "ok": False, "status": lp, "source": "DB 메타 조회"})
        print(json.dumps({"stop": "사전 DB 메타 조회 실패 — 보낸 것 없음", "status": lp}, ensure_ascii=False))
        return 2
    rows, max_id = m["rows"], m["max_id"]
    sig = ab.body_signature(body)
    dup = sorted(r["id"] for r in rows if r.get("status") == "RUNNING" and ab.row_signature(r) == sig)
    prior_tag = sorted(r["id"] for r in rows if tagged(r))
    over_cap = m["running_total"] + 1 > a.session_cap
    skew = abs(ab._ts(m["db_now"]) - start)
    ab.append(journal, {"kind": "preflight_meta", "ok": True, "source": "DB 메타 조회", "meta_sha256": m["sha256"], "db_now": m["db_now"],
                        "running_total": m["running_total"], "max_id": max_id, "candidate_rows": len(rows), "duplicates": dup,
                        "forward_tag_rows": prior_tag, "session_cap": a.session_cap, "over_cap": over_cap,
                        "clock_skew_s": skew.total_seconds()})
    stop = None
    if prior_tag:
        stop = f"forwardTag={FORWARD_TAG} 세션이 이미 있다({prior_tag}) — 생성 반복 금지"
    elif dup:
        stop = f"같은 서명의 RUNNING 세션이 이미 있다({dup})"
    elif over_cap:
        stop = f"RUNNING {m['running_total']} + 1 > 상한 {a.session_cap}"
    elif skew > CLOCK_SKEW_MAX:
        stop = f"DB 시각과 도구 시각 차이 {skew.total_seconds():.1f}초 > 60초"
    if stop:
        print(json.dumps({"stop": stop + " — 보낸 것 없음"}, ensure_ascii=False))
        return 2

    now_k = clock()
    if not ws <= now_k <= we:
        ab.append(journal, {"kind": "window_closed", "pair_arm": KEY, "at": now_k.isoformat()})
        print(json.dumps({"stop": "전송 직전 창 지남 — 보낸 것 없음"}, ensure_ascii=False))
        return 2
    ab.append(journal, {"kind": "intent", "pair_arm": KEY, "body_sha256": body_sha, "at": now_k.isoformat(), "t0": t0.isoformat()})
    code, js, err = client.call("POST", ab.ENDPOINT, body)
    cls, detail = ab.classify(code, js, err, body, {r["id"] for r in rows}, max_id)
    ab.append(journal, {"kind": "result", "pair_arm": KEY, "class": cls, **detail})
    if cls != "OK":
        rec = after_check(meta, body, max_id, None)
        ab.append(journal, {"kind": "reconcile", **rec})
        print(json.dumps({"stop": f"{KEY} {cls} — 다시 보내지 않는다", "reconcile_status": rec.get("status"),
                          "provisional": rec.get("provisional")}, ensure_ascii=False))
        return 3
    sid = detail["id"]
    rec = after_check(meta, body, max_id, sid)
    ab.append(journal, {"kind": "reconcile", **rec})
    problems = []
    if rec.get("status") != "조회됨":
        problems.append(rec.get("status"))
    else:
        if rec["hits"] != [sid]:
            problems.append(f"사후 같은 서명·표지 세션이 응답 ID 하나가 아니다: {rec['hits']}")
        sr = rec.get("stored_started_at")
        try:
            if sr is None or not ab.precision_contract_ok(detail["startedAt_raw"], sr):
                problems.append("POST startedAt 원문과 DB 메타 조회 started_at 이 저장 정밀도 계약으로 맞지 않음")
        except ValueError:
            problems.append("저장 시각 형식 이상")
    created = ab._ts(detail["startedAt_raw"])
    timing = []
    if not created < t0:
        timing.append("생성 시각이 T0 이후")
    if created < t0 - timedelta(hours=23):
        timing.append("생성 시각이 09:00~09:59 KST 또는 그 전")
    if not (ws - CLOCK_SKEW_MAX) <= created <= (we + CLOCK_SKEW_MAX):
        timing.append("생성 시각이 고정 창(±60초) 밖")
    draft = {"status": "SMA20 생성 초안 — 확인 SQL 전(sma20_complete.py 로 완성)", "tool": TOOL, "creator_version": ab.CREATOR_VERSION,
             "code_sha": a.code_sha, "body_sha256": body_sha, "body_file_sha256": raw_sha, "forward_tag": FORWARD_TAG,
             "window_start": ws.isoformat(), "window_end": we.isoformat(), "t0": t0.isoformat(),
             "t0_rule": "창 시작 뒤 첫 09:00 KST(00:00 UTC), POST 전 고정", "db_verified": False,
             "reconcile_ok": not problems and not timing, "reconcile_source": "DB 메타 조회(meta_after.sql, 열 제한·522~587 제외) — API 목록 아님",
             "db_source": a.db_source, "preflight_max_id": max_id, "meta_after_sha256": rec.get("meta_sha256"),
             "session_id": str(sid), "post_started_at_raw": detail["startedAt_raw"], "db_meta_started_at": rec.get("stored_started_at"),
             "precision_contract": "round_half_up(나노초→마이크로초)(post_started_at_raw) == db_meta_started_at",
             "checks": {"reconcile_problems": problems, "timing_problems": timing, "other_new_ids": rec.get("other_new_ids", [])}}
    (out / "sma20_creation.draft.json").write_text(json.dumps(draft, ensure_ascii=False, indent=2), encoding="utf-8")
    if problems or timing:
        print(json.dumps({"stop": "응답은 OK 지만 DB 메타 대조·시각 조건이 성공이 아니다", "problems": problems, "timing": timing},
                         ensure_ascii=False))
        return 3
    print(json.dumps({"done": "SMA20 1개 OK · DB 메타 대조 성공(확인 SQL 전 초안)", "session_id": sid, "t0": t0.isoformat()},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(run(sys.argv[1:]))
