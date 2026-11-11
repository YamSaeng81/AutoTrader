"""청산 A/B 8세션 생성 호출기 v4 (총괄 v1.151 §3·v1.152 A) — 실제 생성 승인 전이다. 로컬 대역·로컬 PG 연결로만 시험했다.

v3 → v4 (2026-11-11, 생성 세션 시각 대조 — v3 원문은 ab-create-v3-20261111 에 그대로 보존):
  - POST 응답의 startedAt **원문 문자열**을 그대로 남긴다(post_started_at_raw). v3 는 Python datetime 으로 바꾸며 마이크로초 아래를 잃었다.
    초안의 created_at 은 이 원문과 같은 값이며, 출처가 "POST 응답 startedAt" 임을 created_at_source 로 적는다.
  - 마지막 사후 API 목록 대조에서 같은 ID 의 **DB 재조회 startedAt 원문**도 남긴다(api_stored_started_at).
  - 두 값은 저장 정밀도 계약(나노초 → 마이크로초 반올림, 실제 로컬 PG 로 확인)으로 대조한다. 어긋나면 API 대조 실패(종료 3).
    초/분 허용 오차·현재 시각 대체는 쓰지 않는다. DB(psql) 시각과의 정확 대조는 완성기(ab_complete v4)가 한다.
  - 나머지 동작(창·기록부·잠금·분류·재전송 금지)은 v3 와 같다.

--- 이하 v3 머리말 원문 ---
청산 A/B 8세션 생성 호출기 v3 (총괄 v1.149 §4-1) — 실제 생성 승인 전이다. 대역·목 객체로만 시험했다.

v2 → v3 (2026-11-11, 총괄 v1.149 관측 C1·C2 보완):
  C1  마지막 목록 대조가 실패해도 종료 0·"8개 OK"였다 → 사후 목록이 성공이고 **정확한 팔 집합 8개·팔별 ID 1개·예상 밖 ID 0개·잠정 후보 0개**
      일 때만 API 대조 성공(종료 0). 조회 실패·형식 이상·불완전 집합은 빈 성공으로 채우지 않는다(종료 3, 초안에 실패를 적는다).
  C2  결과 없는 의도(intent) 행이 있어도 새로 8개를 보냈다 → 결과 불명 전송이 하나라도 있으면 **아무것도 보내지 않는다**(종료 4).
      사전 RUNNING 목록이 비어 있다는 사실로 과거 전송 미접수를 확정하지 않는다.
  추가  기록부 파손·다른 프로토콜/코드/본문의 기록부 거절, 기록 폴더 잠금(동시 실행 금지·비정상 종료 흔적이면 사람 판단), 기록 쓰기는
      flush+fsync 이며 실패하면 그 자리에서 멈춘다(의도 기록 실패 → 보내지 않음, 결과 기록 실패 → 더 보내지 않음).
      응답·목록의 JSON 구조·필수 키·ID·시각 형식 검사 — 틀리면 원문을 남기지 않는 고정 분류(UNKNOWN/형식 이상).
      창(C+6분~C+12분)을 실행 시작 한 번이 아니라 **요청마다 직전에** 다시 본다. 지났으면 더 보내지 않고 부분 결과로 남긴다.
      응답 불명 뒤 목록의 새 ID 는 **잠정 후보**(provisional)로만 적는다 — 팔 확정은 요청/응답 기록과 생성 뒤 DB 저장값 대조(ab_complete.py)로 한다.
  유지  자동 이어 보내기 없음, 서버 생성 API 우회 없음, 규칙 지문에 팔 표지·통계 오버라이드를 넣지 않음, 토큰은 파일에서만 읽고 어디에도 쓰지 않음.

경로: 기존 POST /api/v1/paper-trading/sessions + Authorization: Bearer (v1.145 결정).
종료 코드: 0 = 8개 OK 이고 API 대조 성공(아직 DB 확인 전 초안), 2 = 보낸 것 없음(계획만·창 밖·사전 확인), 3 = 멈춤/부분/대조 실패,
          4 = 기록부·잠금 상태 때문에 보내지 않음(사람 판단).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

ENDPOINT = "/api/v1/paper-trading/sessions"
ORDER = [("P1", "A"), ("P1", "B"), ("P2", "A"), ("P2", "B"), ("P3", "A"), ("P3", "B"), ("P4", "A"), ("P4", "B")]
ARM_KEYS = [p + a for p, a in ORDER]
WINDOW_FROM = timedelta(minutes=6)
WINDOW_TO = timedelta(minutes=12)
LOCK_NAME = ".ab_create.lock"


class JournalError(Exception):
    """기록부를 읽거나 쓸 수 없다 — 그 자리에서 멈춘다."""


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


def plan_sha(plan: list[dict]) -> str:
    return hashlib.sha256("|".join(p["body_sha256"] for p in plan).encode("utf-8")).hexdigest()


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


# ── 형식 검사 ──────────────────────────────────────────────────────────────────

def _ts(s) -> datetime:
    if not isinstance(s, str) or not s:
        raise ValueError("시각 아님")
    d = datetime.fromisoformat(s.replace("Z", "+00:00"))
    if d.tzinfo is None:
        raise ValueError("시간대 없음")
    return d.astimezone(timezone.utc)


TS_RE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}(?::?\d{2})?)$")
TOOL_VERSION = "v4"


def ts_ns(s) -> int:
    """시각 문자열 → UTC 기준 에포크 나노초(정수, 정밀도 손실 없음). 시간대 없음·형식 이상은 ValueError.
    'Z'·'+00'·'+00:00'·'+09:00' 같은 표기 차이는 같은 순간이면 같은 값이 된다."""
    if not isinstance(s, str):
        raise ValueError("시각 아님")
    m = TS_RE.match(s.strip())
    if not m:
        raise ValueError("시각 형식 이상 또는 시간대 없음")
    y, mo, d, h, mi, se, frac, tz = m.groups()
    base = datetime(int(y), int(mo), int(d), int(h), int(mi), int(se), tzinfo=timezone.utc)
    if tz != "Z":
        sign = 1 if tz[0] == "+" else -1
        digits = tz[1:].replace(":", "")
        off = timedelta(hours=int(digits[:2]), minutes=int(digits[2:4] or 0))
        base = base - sign * off
    nanos = int((frac or "").ljust(9, "0"))
    return (int(base.timestamp()) * 1_000_000_000) + nanos


def round_to_micros(ns: int) -> int:
    """저장 정밀도 계약 — 나노초를 가장 가까운 마이크로초로(0.5 는 올림). 실제 로컬 PG(Hibernate·pgjdbc 경로)로 확인한 계약이다."""
    return ((ns + 500) // 1000) * 1000


def precision_contract_ok(post_raw: str, stored_raw: str) -> bool:
    return round_to_micros(ts_ns(post_raw)) == ts_ns(stored_raw)


def _valid_id(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool) and v > 0


def classify(code, js, err, body: dict, known_ids: set) -> tuple[str, dict]:
    """응답 분류 — 원문(오류 메시지·본문)은 남기지 않는다. 고정 분류와 HTTP 코드·ID·시각만."""
    if err == "JSON 아님" and code in (401, 403):
        return "AUTH", {"http": code}
    if err is not None:
        return "UNKNOWN", {"http": code, "transport": err if err == "JSON 아님" else "전송 오류"}
    if code in (401, 403):
        return "AUTH", {"http": code}
    if not isinstance(js, dict):
        return "UNKNOWN", {"http": code, "why": "형식 이상(봉투 아님)"}
    if code != 200:
        return "HTTP_REJECTED", {"http": code, "error_present": js.get("error") is not None}
    if js.get("success") is not True:
        if js.get("success") is False:
            return "REJECTED", {"http": code, "error_present": js.get("error") is not None}
        return "UNKNOWN", {"http": code, "why": "형식 이상(success 값)"}
    d = js.get("data")
    if not isinstance(d, dict):
        return "UNKNOWN", {"http": code, "why": "형식 이상(data)"}
    sid = d.get("id")
    if not _valid_id(sid) or sid in known_ids:
        return "UNKNOWN", {"http": code, "why": "새 정수 id 아님"}
    try:
        started = _ts(d.get("startedAt")).isoformat()
        ts_ns(d.get("startedAt"))   # v4: 원문을 정밀도 손실 없이 읽을 수 있어야 한다
    except Exception:
        return "UNKNOWN", {"http": code, "id": sid, "why": "형식 이상(startedAt)"}
    diffs = []
    for k_resp, k_body in (("strategyName", "strategyType"), ("coinPair", "coinPair"), ("timeframe", "timeframe")):
        if d.get(k_resp) != body[k_body]:
            diffs.append(k_resp)
    try:
        cap_ok = d.get("initialCapital") is not None and float(d.get("initialCapital")) == float(body["initialCapital"])
    except (TypeError, ValueError):
        cap_ok = False
    if not cap_ok:
        diffs.append("initialCapital")
    if d.get("strategyParams") != body["strategyParams"]:
        diffs.append("strategyParams")
    if d.get("status") != "RUNNING":
        diffs.append("status")
    if diffs:
        return "MISMATCH", {"http": code, "id": sid, "fields": diffs}
    return "OK", {"http": code, "id": sid, "startedAt": started, "startedAt_raw": d.get("startedAt")}


def parse_list(code, js, err) -> tuple[list[dict] | None, str | None]:
    """목록 응답 → (행, None) 또는 (None, 고정 실패 사유). 원문은 돌려주지 않는다."""
    if err is not None:
        return None, "목록 조회 실패(전송 오류)" if err != "JSON 아님" else "목록 조회 실패(JSON 아님)"
    if code != 200:
        return None, f"목록 조회 실패(HTTP {code})"
    if not isinstance(js, dict) or js.get("success") is not True:
        return None, "목록 조회 실패(success 아님)"
    rows = js.get("data")
    if not isinstance(rows, list):
        return None, "목록 형식 이상(data)"
    for r in rows:
        if not isinstance(r, dict) or not _valid_id(r.get("id")) or not isinstance(r.get("status"), str):
            return None, "목록 형식 이상(행)"
        if r.get("startedAt") is not None:
            try:
                _ts(r.get("startedAt"))
            except Exception:
                return None, "목록 형식 이상(startedAt)"
    return rows, None


# ── 기록부 ─────────────────────────────────────────────────────────────────────

def journal_lines(path: Path) -> list[dict]:
    """엄격하게 읽는다 — 한 줄이라도 JSON 객체가 아니거나 kind 가 없으면 JournalError(파손)."""
    if not path.exists():
        return []
    out = []
    try:
        text = path.read_text(encoding="utf-8")
    except Exception as e:
        raise JournalError("기록부를 읽을 수 없다") from e
    for i, line in enumerate(text.splitlines()):
        if not line.strip():
            continue
        try:
            rec = json.loads(line)
        except Exception as e:
            raise JournalError(f"기록부 {i + 1}행 파손") from e
        if not isinstance(rec, dict) or not isinstance(rec.get("kind"), str):
            raise JournalError(f"기록부 {i + 1}행 형식 이상")
        out.append(rec)
    if text and not text.endswith("\n"):
        raise JournalError("기록부 마지막 행이 끝나지 않았다(쓰는 중 종료 흔적)")
    return out


def append(path: Path, rec: dict) -> None:
    """한 줄 쓰고 flush+fsync — 실패하면 JournalError 로 멈춘다."""
    try:
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False, sort_keys=True) + "\n")
            f.flush()
            os.fsync(f.fileno())
    except Exception as e:
        raise JournalError("기록부 쓰기 실패") from e


def journal_problem(prior: list[dict], psha: str, code_sha: str, plan: list[dict]) -> str | None:
    """보내기 전 기록부 검사 — 문제가 있으면 고정 사유."""
    body_shas = {p["pair_id"] + p["arm"]: p["body_sha256"] for p in plan}
    for r in prior:
        if "protocol_sha256" in r and r["protocol_sha256"] != psha:
            return "다른 프로토콜의 기록부다"
        if "code_sha" in r and r["code_sha"] != code_sha:
            return "다른 대상 코드의 기록부다"
        if "plan_sha256" in r and r["plan_sha256"] != plan_sha(plan):
            return "다른 본문 계획의 기록부다"
        if r["kind"] == "intent" and body_shas.get(r.get("pair_arm")) != r.get("body_sha256"):
            return "본문이 다른 전송 의도가 있다"
    intents: dict[str, int] = {}
    results: dict[str, int] = {}
    for r in prior:
        if r["kind"] == "intent":
            intents[r.get("pair_arm")] = intents.get(r.get("pair_arm"), 0) + 1
        elif r["kind"] == "result":
            results[r.get("pair_arm")] = results.get(r.get("pair_arm"), 0) + 1
    unresolved = sorted(k for k, n in intents.items() if n > results.get(k, 0))
    if unresolved:
        return "결과 없는 전송 의도가 있다(" + ",".join(str(k) for k in unresolved) + ") — 결과 불명, 재전송 금지"
    if any(r["kind"] == "result" and r.get("class") != "OK" for r in prior):
        return "기록부에 OK 가 아닌 결과가 있다 — 사람 판단 전에는 보내지 않는다"
    if results:
        return "이미 생성 결과가 있는 기록부다 — 이어 보내기는 이 도구의 범위가 아니다"
    return None


# ── 잠금 ───────────────────────────────────────────────────────────────────────

def acquire_lock(out: Path) -> bool:
    try:
        fd = os.open(str(out / LOCK_NAME), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        return False
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(json.dumps({"pid": os.getpid(), "at": datetime.now(timezone.utc).isoformat()}) + "\n")
    return True


def release_lock(out: Path) -> None:
    try:
        (out / LOCK_NAME).unlink()
    except FileNotFoundError:
        pass


# ── 대조 ───────────────────────────────────────────────────────────────────────

def reconcile(client: Client, plan: list[dict], since: str, ok_ids: dict[str, int], stopped: str | None = None) -> dict:
    """사전 확인 이후 시작된 RUNNING 세션을 쌍 서명으로 찾는다.

    🔴 같은 쌍의 A·B 는 목록 응답으로 구분되지 않는다(stopLossPct 미노출). OK 응답 id 만 팔에 귀속한다(by_arm). 응답 불명인 팔의
    새 id 는 **잠정 후보**(provisional)일 뿐 귀속이 아니다 — 다른 동시 생성일 수 있다. 확정은 생성 뒤 DB 저장값 대조로 한다.
    """
    rows, problem = parse_list(*client.call("GET", ENDPOINT))
    if problem:
        return {"status": problem}
    since_t = _ts(since)
    by_arm, unexpected, provisional = {}, {}, {}
    # v4: OK 응답 ID 의 DB 재조회 startedAt 원문(목록 행 그대로). 목록에 없거나 비면 키를 두지 않는다(대조 실패로 이어짐).
    stored_started = {k: r.get("startedAt") for k, i in ok_ids.items() for r in rows
                      if r.get("id") == i and isinstance(r.get("startedAt"), str)}
    for pid in sorted({p["pair_id"] for p in plan}):
        sig = body_signature(next(p["body"] for p in plan if p["pair_id"] == pid))
        hits = sorted(r["id"] for r in rows if row_signature(r) == sig and r.get("status") == "RUNNING"
                      and r.get("startedAt") and _ts(r["startedAt"]) >= since_t)
        attributed = set()
        for arm in ("A", "B"):
            k = pid + arm
            if k in ok_ids:
                by_arm[k] = [ok_ids[k]] if ok_ids[k] in hits else []
                attributed.add(ok_ids[k])
            else:
                by_arm[k] = []
        rest = [i for i in hits if i not in attributed]
        if rest and stopped is not None and stopped.startswith(pid):
            provisional[stopped] = rest
        elif rest:
            unexpected[pid] = rest
    return {"status": "조회됨", "by_arm": by_arm, "unexpected": unexpected, "provisional": provisional,
            "stored_started_at": stored_started}


def api_reconcile_ok(rec: dict, ok_ids: dict[str, int]) -> list[str]:
    """API 대조 성공 조건 — 빈 목록이면 성공. 조회 실패·형식 이상·불완전 집합을 성공으로 채우지 않는다."""
    if rec.get("status") != "조회됨":
        return [rec.get("status") or "목록 대조 결과 없음"]
    by_arm = rec.get("by_arm")
    if not isinstance(by_arm, dict) or set(by_arm) != set(ARM_KEYS):
        return ["팔 집합이 8개가 아니다"]
    problems = [k for k in ARM_KEYS if by_arm[k] != [ok_ids.get(k)]]
    if rec.get("unexpected"):
        problems.append("예상 밖 " + ",".join(sorted(rec["unexpected"])))
    if rec.get("provisional"):
        problems.append("잠정 후보 " + ",".join(sorted(rec["provisional"])))
    return problems


def run(argv: list[str], now: datetime | None = None, clock: Callable[[], datetime] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--token-file", required=True)
    ap.add_argument("--protocol", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--code-sha", required=True)
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--execute", action="store_true", help="없으면 계획만 쓰고 아무것도 보내지 않는다")
    a = ap.parse_args(argv)
    if clock is None:
        clock = (lambda: now) if now is not None else (lambda: datetime.now(timezone.utc))
    out = Path(a.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    journal = out / "journal.jsonl"
    protocol, psha = load_protocol(Path(a.protocol))
    plan = bodies_from_protocol(protocol)
    if not a.execute:
        (out / "plan.json").write_text(json.dumps({"protocol_sha256": psha, "plan_sha256": plan_sha(plan), "endpoint": "POST " + ENDPOINT,
                                                   "bodies": plan}, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"mode": "계획만", "bodies": len(plan), "protocol_sha256": psha}, ensure_ascii=False))
        return 2

    if not acquire_lock(out):
        print(json.dumps({"stop": "기록 폴더 잠금이 이미 있다 — 다른 실행 중이거나 비정상 종료 흔적. 사람 판단 전에는 보내지 않는다"},
                         ensure_ascii=False))
        return 4
    try:
        return _execute(a, clock, out, journal, protocol, psha, plan)
    except JournalError as e:
        print(json.dumps({"stop": f"기록부 문제 — {e}. 더 보내지 않는다(사람 판단)"}, ensure_ascii=False))
        return 4
    finally:
        release_lock(out)


def _execute(a, clock, out: Path, journal: Path, protocol: dict, psha: str, plan: list[dict]) -> int:
    prior = journal_lines(journal)   # 파손이면 JournalError → 4
    problem = journal_problem(prior, psha, a.code_sha, plan)
    if problem:
        print(json.dumps({"stop": problem}, ensure_ascii=False))
        return 4
    (out / "plan.json").write_text(json.dumps({"protocol_sha256": psha, "plan_sha256": plan_sha(plan), "endpoint": "POST " + ENDPOINT,
                                               "bodies": plan}, ensure_ascii=False, indent=2), encoding="utf-8")

    token = Path(a.token_file).read_text(encoding="utf-8").strip()
    client = Client(a.base_url, token, a.timeout)
    del token

    start = clock()
    c, inside = m15_window(start)
    append(journal, {"kind": "preflight", "at": start.isoformat(), "m15_boundary": c.isoformat(), "inside_window": inside,
                     "protocol_sha256": psha, "code_sha": a.code_sha, "plan_sha256": plan_sha(plan)})
    if not inside:
        print(json.dumps({"stop": f"창 밖 — {c.isoformat()} 기준 [+6분, +12분]이 아니다. 보낸 것 없음"}, ensure_ascii=False))
        return 2
    rows, lp = parse_list(*client.call("GET", ENDPOINT))
    if lp:
        append(journal, {"kind": "preflight_list", "ok": False, "status": lp})
        print(json.dumps({"stop": "사전 목록 조회 실패 — 보낸 것 없음", "status": lp}, ensure_ascii=False))
        return 2
    known_ids = {r.get("id") for r in rows}
    dup = sorted({p["pair_id"] + p["arm"] for p in plan for r in rows
                  if r.get("status") == "RUNNING" and row_signature(r) == body_signature(p["body"])})
    append(journal, {"kind": "preflight_list", "ok": True, "running": sum(1 for r in rows if r.get("status") == "RUNNING"),
                     "duplicates": dup})
    if dup:
        print(json.dumps({"stop": "같은 서명의 RUNNING 세션이 이미 있다 — 보낸 것 없음", "arms": dup}, ensure_ascii=False))
        return 2

    since = start.isoformat()
    created = []
    ok_ids: dict[str, int] = {}
    t0 = c + timedelta(minutes=15)
    for p in plan:
        k = p["pair_id"] + p["arm"]
        now_k = clock()
        c_k, inside_k = m15_window(now_k)
        if c_k != c or not inside_k:
            append(journal, {"kind": "window_closed", "pair_arm": k, "at": now_k.isoformat()})
            rec = reconcile(client, plan, since, ok_ids)
            append(journal, {"kind": "reconcile", **rec})
            print(json.dumps({"stop": f"{k} 직전 창 지남 — 더 보내지 않는다(부분 결과)", "created_ok": [x["pair_arm"] for x in created],
                              "reconcile_status": rec.get("status")}, ensure_ascii=False))
            return 3
        append(journal, {"kind": "intent", "pair_arm": k, "body_sha256": p["body_sha256"], "at": now_k.isoformat()})
        code, js, err = client.call("POST", ENDPOINT, p["body"])
        cls, detail = classify(code, js, err, p["body"], known_ids)
        append(journal, {"kind": "result", "pair_arm": k, "class": cls, **detail})
        if cls != "OK":
            rec = reconcile(client, plan, since, ok_ids, stopped=k)
            append(journal, {"kind": "reconcile", **rec})
            print(json.dumps({"stop": f"{k} {cls} — 다시 보내지 않고 남은 본문도 보내지 않는다",
                              "created_ok": [x["pair_arm"] for x in created], "reconcile_status": rec.get("status"),
                              "provisional": rec.get("provisional")}, ensure_ascii=False))
            return 3
        known_ids.add(detail["id"])
        ok_ids[k] = detail["id"]
        created.append({"pair_arm": k, "pair_id": p["pair_id"], "arm": p["arm"], "session_id": str(detail["id"]),
                        "created_at": detail["startedAt_raw"]})
    rec = reconcile(client, plan, since, ok_ids)
    append(journal, {"kind": "reconcile", **rec})
    problems = api_reconcile_ok(rec, ok_ids)
    late = [x["pair_arm"] for x in created if _ts(x["created_at"]) >= t0]
    stored_map = rec.get("stored_started_at") or {}
    precision_bad = []
    for x in created:
        sr = stored_map.get(x["pair_arm"])
        try:
            if sr is None or not precision_contract_ok(x["created_at"], sr):
                precision_bad.append(x["pair_arm"])
        except ValueError:
            precision_bad.append(x["pair_arm"])
    if precision_bad:
        problems = problems + ["POST startedAt 원문과 저장 후 API startedAt 이 저장 정밀도 계약으로 맞지 않음 " + ",".join(precision_bad)]
    draft = {"status": "API 초안 — DB 확인 전(ab_complete.py v4 로 완성)", "tool_version": TOOL_VERSION,
             "protocol_sha256": psha, "code_sha": a.code_sha,
             "t0": t0.isoformat(), "db_verified": False, "api_reconcile_ok": not problems and not late,
             "created_at_source": "POST 응답 data.startedAt 원문(정밀도 보존)",
             "api_stored_started_at_source": "생성 뒤 GET 목록의 같은 ID startedAt 원문(DB 재조회 값)",
             "precision_contract": "round_half_up(나노초→마이크로초)(post_started_at_raw) == api_stored_started_at",
             "sessions": [{"pair_id": x["pair_id"], "arm": x["arm"], "session_id": x["session_id"], "created_at": x["created_at"],
                           "post_started_at_raw": x["created_at"], "api_stored_started_at": stored_map.get(x["pair_arm"]),
                           "ruleset_hash": None, "stored": None} for x in created],
             "checks": {"api_reconcile_problems": problems, "created_at_not_before_t0": late,
                        "precision_contract_failed": precision_bad}}
    (out / "creation_log.draft.json").write_text(json.dumps(draft, ensure_ascii=False, indent=2), encoding="utf-8")
    if problems or late:
        print(json.dumps({"stop": "8개 응답은 OK 지만 API 대조가 성공이 아니다 — DB 확인 전 판단 금지", "problems": problems, "late": late},
                         ensure_ascii=False))
        return 3
    print(json.dumps({"done": "8개 OK · API 대조 성공(DB 확인 전 초안)", "t0": t0.isoformat()}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(run(sys.argv[1:]))
