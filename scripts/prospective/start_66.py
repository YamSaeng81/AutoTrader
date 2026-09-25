# -*- coding: utf-8 -*-
"""
전향 검증 66세션 생성·검증 — docs/SUPERTREND_VALIDATION_PREREG.md §5

  OFF·B·A × 22코인 = 66 페이퍼 세션, H1, 세션당 100만원

🔴 공통 T0 을 지키는 유일한 수단은 **생성 전체를 한 평가 틱 간격(60초) 안에 넣는 것**이다.
   엔진에는 "준비됐지만 거래하지 않는" 상태가 없다 (PaperTradingService:189 → RUNNING 즉시,
   :570 runStrategy fixedDelay=60s 가 그 시점의 RUNNING 전량을 평가).

사용 (API_BASE 기본값 http://localhost:8080 · 토큰은 .env 의 API_AUTH_TOKEN 폴백)
    python start_66.py check                     배포·정원 확인 (부작용 없음)
    python start_66.py probe                     틱 위상 측정 (22코인 밖 프로브, 끝나면 삭제)
    python start_66.py create --after <epoch초>   다음 틱 직후에 66개 일괄 생성
    python start_66.py verify                     T0 일치·T0 이전 주문 0건 검증
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")   # sys.exit 문구도 cp949 로 깨지지 않게
except Exception:
    pass

def from_dotenv(key):
    """저장소 루트 .env 에서 값을 읽는다 — 기존 운영 스크립트와 같은 방식
    (scripts/backfill_candles_0920.sh:77 등). 🔴 값을 출력하거나 기록하지 않는다."""
    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    path = os.path.join(root, ".env")
    if not os.path.exists(path):
        return ""
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.strip().startswith(key + "="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


BASE = (os.environ.get("API_BASE") or from_dotenv("API_BASE")
        or "http://localhost:8080").rstrip("/")
TOKEN = (os.environ.get("API_TOKEN") or os.environ.get("API_AUTH_TOKEN")
         or from_dotenv("API_AUTH_TOKEN") or from_dotenv("API_TOKEN"))

COINS = ["IOTA", "WAVES", "CRO", "ONG", "SC", "POLYX", "NEAR", "WAXP", "BCH", "CVC",
         "POWR", "T", "ANKR", "DKA", "GLM", "HIVE", "PUNDIX", "ELF", "BLAST", "JUP",
         "G", "INJ"]

ARMS = {
    "OFF": "COMPOSITE_MOMENTUM_ICHIMOKU_V2",     # 확인 필터 없음
    "B":   "COMPOSITE_MTF_MOMENTUM",             # 운영 기본값 (형성 중 H4 봉 포함)
    "A":   "COMPOSITE_MTF_MOMENTUM_CLOSED",      # 변경안 (완결 H4 봉만)
}
TIMEFRAME = "H1"
CAPITAL = 1_000_000
PROBE_COIN = "KRW-BTC"          # 22코인 밖 — 검증 기록을 오염시키지 않는다
TICK = 60                       # PaperTradingService:570 fixedDelay
SESSION_CAP = 120               # MAX_CONCURRENT_SESSIONS
STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sessions_66.json")


def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


def need_env():
    if not TOKEN:
        sys.exit("API 토큰이 없다 — 환경변수 API_TOKEN/API_AUTH_TOKEN 또는 .env 의 API_AUTH_TOKEN.")
    st, _ = call("GET", "/api/v1/health")
    if st != 200:
        sys.exit("%s 에 닿지 않는다 (HTTP %s). API_BASE 를 확인한다." % (BASE, st))


def sid_of(d):
    return d.get("sessionId") or d.get("id")


def cmd_check():
    need_env()
    st, body = call("GET", "/api/v1/strategies/types")
    if st != 200:
        print("✗ 전략 목록 조회 실패 %s: %s" % (st, body))
        return 1
    have = {x["type"] for x in body["data"]}
    print("① 배포 확인 — 세 팔 프리셋이 구동 중인 이미지에 있는가")
    ok = True
    for arm, name in ARMS.items():
        if name not in have:
            ok = False
        print("   %s %-3s %s" % ("✔" if name in have else "✗", arm, name))
    if not ok:
        print("   🔴 구 이미지다. 팔 A 는 평가 시점에 예외로 죽는다 — 재배포 후 다시 확인한다.")

    st, body = call("GET", "/api/v1/paper-trading/sessions")
    running = [s for s in body["data"] if s.get("status") == "RUNNING"]
    print("\n② 정원 — 현재 RUNNING %d + 66 = %d / %d   %s"
          % (len(running), len(running) + 66, SESSION_CAP,
             "✔" if len(running) + 66 <= SESSION_CAP else "✗"))
    dup = [s for s in running if s.get("strategyName") in ARMS.values()]
    if dup:
        print("   ⚠️ 세 팔 전략으로 이미 도는 세션 %d개 — 표본이 섞인다. 먼저 정리한다." % len(dup))
    return 0 if ok else 1


def first_log_time(session_id):
    st, body = call("GET", "/api/v1/logs/strategy?sessionId=%s&size=1" % session_id)
    if st != 200 or not (body or {}).get("data", {}).get("content"):
        return None
    return body["data"]["content"][0]["createdAt"]


def cmd_probe():
    """틱 위상 측정 — 프로브 세션 하나의 첫 평가 시각을 읽는다. 끝나면 정지·삭제."""
    need_env()
    st, body = call("POST", "/api/v1/paper-trading/sessions", {
        "strategyType": ARMS["A"], "coinPair": PROBE_COIN,
        "timeframe": TIMEFRAME, "initialCapital": CAPITAL})
    if st != 200:
        print("✗ 프로브 생성 실패 %s: %s" % (st, body))
        return 1
    sid = sid_of(body["data"])
    print("프로브 세션 %s (%s, 팔 A) — 첫 평가를 기다린다 (최대 %d초)"
          % (sid, PROBE_COIN, TICK + 20))
    t_first = None
    for _ in range((TICK + 20) // 2):
        time.sleep(2)
        t_first = first_log_time(sid)
        if t_first:
            break
    if t_first:
        ts = datetime.fromisoformat(t_first.replace("Z", "+00:00"))
        print("\n✔ 첫 평가 %s  (epoch %d)" % (ts.isoformat(), int(ts.timestamp())))
        print("  틱 위상 = epoch mod %d = %d" % (TICK, int(ts.timestamp()) % TICK))
        print("\n  다음: python start_66.py create --after %d" % int(ts.timestamp()))
        print("  🔴 팔 A 가 평가됐다 = 새 이미지가 돌고 있다는 실행 증거다.")
    else:
        print("\n✗ 첫 평가 로그가 안 보인다 — 구 이미지에서 팔 A 가 예외로 죽었을 수 있다.")
    call("POST", "/api/v1/paper-trading/sessions/%s/stop" % sid)
    st, _ = call("DELETE", "/api/v1/paper-trading/history/%s" % sid)
    print("프로브 정리: stop + delete (HTTP %s)" % st)
    return 0 if t_first else 1


def cmd_create(after):
    """관측된 틱(after) 기준 **다음 틱 직후**에 22회 호출을 쉬지 않고 실행한다."""
    need_env()
    now = time.time()
    k = int((now - after) // TICK) + 1
    target = after + k * TICK + 2          # 틱이 지난 2초 뒤 = 남은 여유 ≈ 55초
    wait = target - now
    if wait > TICK + 5:
        print("--after 값이 너무 오래됐다 (대기 %.0f초). probe 를 다시 돌린다." % wait)
        return 2
    print("다음 틱 직후까지 %.1f초 대기 (목표 epoch %d)" % (wait, target))
    if wait > 0:
        time.sleep(wait)

    t_start = time.time()
    created, failed = [], []
    for coin in COINS:
        st, body = call("POST", "/api/v1/paper-trading/sessions/multi", {
            "strategyTypes": [ARMS["OFF"], ARMS["B"], ARMS["A"]],
            "coinPair": "KRW-" + coin, "timeframe": TIMEFRAME,
            "initialCapital": CAPITAL})
        if st != 200:
            failed.append((coin, st, body))
            continue
        for s in body["data"]:
            created.append({"coin": "KRW-" + coin,
                            "strategy": s.get("strategyName"), "id": sid_of(s)})
    elapsed = time.time() - t_start

    print("\n생성 %d개 · 실패 %d개 · 소요 %.2f초 (한 틱 %d초 안 %s)"
          % (len(created), len(failed), elapsed, TICK,
             "✔" if elapsed < TICK - 5 else "🔴 초과 — 폐기 후 재시도"))
    for f in failed:
        print("   ✗ %s: %s %s" % f)
    with open(STATE, "w", encoding="utf-8") as fh:
        json.dump({"createdAtEpoch": t_start, "elapsed": elapsed,
                   "sessions": created}, fh, ensure_ascii=False, indent=1)
    print("세션 목록 저장: %s" % STATE)
    if len(created) != 66 or elapsed >= TICK - 5:
        print("\n🔴 부분 보정하지 않는다 — stop-all 후 66세션을 삭제하고 probe 부터 다시 한다.")
        return 1
    return 0


def cmd_verify():
    """T0 일치 — 66개의 첫 평가가 같은 틱인가, T0 이전 주문이 0건인가."""
    need_env()
    state = json.load(open(STATE, encoding="utf-8"))
    rows = []
    for s in state["sessions"]:
        t = first_log_time(s["id"])
        st, body = call("GET", "/api/v1/paper-trading/sessions/%s/orders" % s["id"])
        orders = (body or {}).get("data", {}).get("orders", []) if st == 200 else []
        rows.append((s, t, orders))

    missing = [s["id"] for s, t, _ in rows if not t]
    stamps = sorted({datetime.fromisoformat(t.replace("Z", "+00:00")).timestamp()
                     for _, t, _ in rows if t})
    print("① 첫 평가 로그 — %d/%d" % (len(rows) - len(missing), len(rows)))
    if missing:
        print("   🔴 로그 없는 세션 %d개: %s" % (len(missing), missing[:10]))
        print("      캔들 부족이면 그 팔은 조용히 아무것도 하지 않는다 — 0거래를 성과로 읽으면 안 된다.")
    ok = False
    if stamps:
        spread = stamps[-1] - stamps[0]
        print("② 첫 평가 시각 폭 %.1f초  %s"
              % (spread, "✔ 같은 틱" if spread < TICK else "🔴 틱이 갈렸다"))
        print("   T0 = %s" % datetime.fromtimestamp(stamps[0], timezone.utc).isoformat())
        early = [s["id"] for s, _, os_ in rows for o in os_
                 if o.get("createdAt") and datetime.fromisoformat(
                     o["createdAt"].replace("Z", "+00:00")).timestamp() < stamps[0]]
        print("③ T0 이전 주문 %d건  %s" % (len(early), "✔" if not early else "🔴 표본 폐기 대상"))
        ok = not missing and spread < TICK and not early
    print("\n%s" % ("✔ 공통 T0 성립 — 이 T0 을 사전 등록 문서에 기록한다."
                   if ok else "🔴 성립하지 않는다 — 전량 폐기 후 재시도."))
    return 0 if ok else 1


def main():
    c = sys.argv[1] if len(sys.argv) > 1 else ""
    if c == "check":
        return cmd_check()
    if c == "probe":
        return cmd_probe()
    if c == "create":
        if "--after" not in sys.argv:
            print("--after <epoch초> 가 필요하다 (probe 출력).")
            return 2
        return cmd_create(int(sys.argv[sys.argv.index("--after") + 1]))
    if c == "verify":
        return cmd_verify()
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
