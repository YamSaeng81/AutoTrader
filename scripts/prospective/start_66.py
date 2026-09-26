# -*- coding: utf-8 -*-
"""
전향 검증 66세션 생성·검증 — docs/SUPERTREND_VALIDATION_PREREG.md §5

  OFF·B·A × 22코인 = 66 페이퍼 세션, H1, 세션당 100만원

🔴 공통 T0 을 지키는 유일한 수단은 **생성 전체를 한 평가 틱 간격(60초) 안에 넣는 것**이다.
   엔진에는 "준비됐지만 거래하지 않는" 상태가 없다 (PaperTradingService:189 → RUNNING 즉시,
   :570 runStrategy fixedDelay=60s 가 그 시점의 RUNNING 전량을 평가).

사용 (생성은 단건 66회 · 코인마다 팔 순서를 한 칸씩 돌린다 / API_BASE 기본값 http://localhost:8080 · 토큰은 .env 의 API_AUTH_TOKEN 폴백)
    python start_66.py check                     배포·정원·세 팔 활성 여부 확인 (부작용 없음)
    python start_66.py probe                     세 팔 생성 가능 여부 + 틱 위상 (22코인 밖, 끝나면 삭제)
    python start_66.py create --after <epoch초>   다음 틱 직후에 66개 일괄 생성
    python start_66.py verify                     T0 일치·T0 이전 주문 0건 검증
    python start_66.py abort                      생성된 세션 전량 정지·삭제 (부분 보정 금지 규칙의 집행)
    python start_66.py arm-gate enable        팔 B 를 검증 기간 한정 재활성화 (토글 주의 — 먼저 읽은 뒤에 바꾼다)
    python start_66.py arm-gate restore       검증 종료 후 원래 상태로 되돌린다
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
    # 🔴 "운영 기본값"이 아니다 — 배포된 것은 **형성 중 H4 봉을 포함하는 다운샘플러 경로**이고,
    #    이 프리셋 자신은 `strategy_type_enabled` 에서 is_active=false 여서 생성이 막힌 상태였다
    #    (2026-09-26 첫 create 에서 22건 400). 상세는 사전 등록 문서 §5 참조.
    "B":   "COMPOSITE_MTF_MOMENTUM",             # 형성 중 H4 봉 포함 (= 배포된 다운샘플러 거동)
    "A":   "COMPOSITE_MTF_MOMENTUM_CLOSED",      # 변경안 (완결 H4 봉만)
}
TIMEFRAME = "H1"
CAPITAL = 1_000_000
PROBE_COIN = "KRW-BTC"          # 22코인 밖 — 검증 기록을 오염시키지 않는다
TICK = 60                       # PaperTradingService:570 fixedDelay
SESSION_CAP = 120               # MAX_CONCURRENT_SESSIONS
_HERE = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(_HERE, "sessions_66.json")
BASELINE = os.path.join(_HERE, "preexisting_excluded.json")


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

    # 🔴 2026-09-26 추가 — 등재됐다고 만들 수 있는 것이 아니다.
    #    `strategy_type_enabled` 은 **차단 목록**이고(부재=활성) 세 생성 경로 전부가
    #    StrategyEnablementGate 를 지난다. 첫 create 는 팔 B(COMPOSITE_MTF_MOMENTUM)가
    #    이 표에서 is_active=false 여서 22건 전부 400 으로 떨어졌다 — 프리셋 존재 확인만으로는
    #    잡히지 않는 실패였다. 여기서 먼저 센다.
    print("\n②' 활성 여부 — 세션을 만들 수 있는 상태인가 (strategy_type_enabled)")
    for arm, name in ARMS.items():
        st, body2 = call("GET", "/api/v1/strategies/%s" % name)
        active = (body2 or {}).get("data", {}).get("isActive") if st == 200 else None
        if active is True:
            print("   ✔ %-3s %s" % (arm, name))
        else:
            ok = False
            print("   ✗ %-3s %s  isActive=%s" % (arm, name, active))
            print("      🔴 이 팔은 생성이 거부된다. 사전 등록한 세 팔 중 하나가 빠지면")
            print("         주 비교(A−B)가 성립하지 않는다 — 임의로 팔을 줄이지 않는다.")

    st, body = call("GET", "/api/v1/paper-trading/sessions")
    running = [s for s in body["data"] if s.get("status") == "RUNNING"]
    print("\n② 정원 — 현재 RUNNING %d + 66 = %d / %d   %s"
          % (len(running), len(running) + 66, SESSION_CAP,
             "✔" if len(running) + 66 <= SESSION_CAP else "✗"))
    dup = [s for s in running if s.get("strategyName") in ARMS.values()]
    if dup:
        # 🔴 정정: 이 세션들을 정지할 필요는 없다. 페이퍼는 세션별로 자본·전략 인스턴스가
        #    독립이고, LIVE 의 cross-session 잔고 가드를 **의도적으로 적용하지 않는다**
        #    (PaperTradingService:611-613). 섞이는 것은 집계뿐이므로 ID 를 기록해 제외한다.
        print("\n③ 세 팔과 같은 전략으로 이미 도는 기존 세션 %d개 — **정지하지 않는다**" % len(dup))
        print("   페이퍼는 세션별 자본·전략 인스턴스가 독립이고 cross-session 잔고 가드가 없다.")
        print("   집계에서만 제외하면 된다 (verify 가 세션 ID 목록으로 집계하므로 자동 제외).")
        with open(BASELINE, "w", encoding="utf-8") as fh:
            json.dump([{"id": sid_of(s), "coin": s.get("coinPair"),
                        "strategy": s.get("strategyName"), "timeframe": s.get("timeframe"),
                        "startedAt": s.get("startedAt")} for s in dup],
                      fh, ensure_ascii=False, indent=1)
        print("   제외 목록 저장: %s" % BASELINE)
        for s in dup:
            print("     - %s  %-10s %-32s %s" % (sid_of(s), s.get("coinPair"),
                                                 s.get("strategyName"), s.get("timeframe")))
    return 0 if ok else 1


def first_log_time(session_id):
    st, body = call("GET", "/api/v1/logs/strategy?sessionId=%s&size=1" % session_id)
    if st != 200 or not (body or {}).get("data", {}).get("content"):
        return None
    return body["data"]["content"][0]["createdAt"]


def cmd_probe():
    """세 팔 생성 가능 여부 + 틱 위상 — 22코인 밖 프로브. 끝나면 전부 정지·삭제.

    🔴 왜 세 팔 모두 만들어 보는가 (2026-09-26): (전략 × 타임프레임) 폐기 표
    `strategy_timeframe_enabled` 에는 **조회 엔드포인트가 없다** — 유효한 유일한 사전 검사는
    같은 (전략, 타임프레임) 조합으로 실제 생성을 한 번 시도하는 것이다. 첫 create 가
    22건 실패한 뒤 넣었다. 프로브는 22코인 밖이라 검증 기록을 오염시키지 않는다.
    """
    need_env()
    probes = {}
    for arm in ("OFF", "B", "A"):
        st, body = call("POST", "/api/v1/paper-trading/sessions", {
            "strategyType": ARMS[arm], "coinPair": PROBE_COIN,
            "timeframe": TIMEFRAME, "initialCapital": CAPITAL})
        if st != 200:
            print("✗ 팔 %s 프로브 생성 거부 %s: %s" % (arm, st, body))
        else:
            probes[arm] = sid_of(body["data"])
            print("✔ 팔 %-3s 생성 가능 — 프로브 세션 %s" % (arm, probes[arm]))
    if len(probes) != 3:
        for sid in probes.values():
            call("POST", "/api/v1/paper-trading/sessions/%s/stop" % sid)
            call("DELETE", "/api/v1/paper-trading/history/%s" % sid)
        print("\n🔴 세 팔이 모두 만들어지지 않는다 — create 로 넘어가지 않는다.")
        print("   프로브는 정리했다. 막힌 팔을 먼저 해결한다 (사전 등록 문서에 기록할 일이다).")
        return 1
    sid = probes["A"]
    print("\n틱 위상 측정 — 팔 A 프로브 %s 의 첫 평가를 기다린다 (최대 %d초)"
          % (sid, TICK + 20))
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
    for arm, pid in probes.items():
        call("POST", "/api/v1/paper-trading/sessions/%s/stop" % pid)
        st, _ = call("DELETE", "/api/v1/paper-trading/history/%s" % pid)
        print("프로브 정리 팔 %-3s %s: stop + delete (HTTP %s)" % (arm, pid, st))
    return 0 if t_first else 1


ARM_GATE = os.path.join(_HERE, "arm_gate_changes.json")


def _arm_active(name):
    st, body = call("GET", "/api/v1/strategies/%s" % name)
    if st != 200:
        return None
    return (body or {}).get("data", {}).get("isActive")


def cmd_arm_gate(target):
    """팔 B 의 `strategy_type_enabled` 상태를 검증 기간 동안만 바꾼다.

    🔴 `PATCH /strategies/{name}/active` 는 **set 이 아니라 toggle** 이다
    (StrategyController:73-89). 반드시 먼저 읽고, 이미 원하는 상태면 건드리지 않는다 —
    무조건 PATCH 하면 두 번 실행했을 때 되돌아간다.

    🔴 되돌리기 위해 바꾼 내역을 `arm_gate_changes.json` 에 남긴다. 검증 종료 후
    `arm-gate restore` 가 그 파일을 보고 원래 상태로 되돌린다 — 기본값은 복귀다.
    """
    need_env()
    name = ARMS["B"]
    before = _arm_active(name)
    if before is None:
        print("✗ %s 조회 실패 — 이름과 등재 여부를 확인한다." % name)
        return 1
    print("현재 %s isActive=%s" % (name, before))

    if target == "restore":
        if not os.path.exists(ARM_GATE):
            print("변경 기록(%s)이 없다 — 되돌릴 것이 없다." % os.path.basename(ARM_GATE))
            return 0
        rec = json.load(open(ARM_GATE, encoding="utf-8"))
        want = rec.get("before")
        print("기록된 원래 상태 isActive=%s (%s 에 변경)" % (want, rec.get("at")))
    else:
        want = True

    if before == want:
        print("이미 isActive=%s — 토글하지 않는다." % want)
        if target == "restore":
            os.remove(ARM_GATE)
        return 0

    # 모든 프록시가 Content-Length 없는 PATCH 를 받지는 않는다 — 빈 본문을 붙인다.
    st, body = call("PATCH", "/api/v1/strategies/%s/active" % name, {})
    after = (body or {}).get("data", {}).get("isActive") if st == 200 else None
    if after != want:
        print("✗ 토글 실패 — HTTP %s, isActive=%s. 응답: %s" % (st, after, str(body)[:200]))
        return 1
    print("✔ %s isActive %s → %s" % (name, before, after))

    if target == "restore":
        os.remove(ARM_GATE)
        print("변경 기록 삭제 — 원래 상태로 돌아갔다.")
    else:
        with open(ARM_GATE, "w", encoding="utf-8") as fh:
            json.dump({"strategy": name, "before": before, "after": after,
                       "at": datetime.now(timezone.utc).isoformat(),
                       "why": "전향 검증 기간 한정 재활성화 — 사전 등록 문서 §5 참조. "
                              "종료 후 `arm-gate restore` 로 되돌린다."},
                      fh, ensure_ascii=False, indent=1)
        print("변경 기록 저장: %s" % ARM_GATE)
        print("🔴 이 시각을 사전 등록 문서의 '재활성화 시각' 칸에 옮겨 적는다.")
        print("🔴 검증 종료 후: python start_66.py arm-gate restore")
    return 0


def cmd_abort():
    """생성된 세션을 전량 정지·삭제한다 — §5 "부분 보정하지 않는다"의 집행.

    🔴 `preexisting_excluded.json` 의 기존 세션은 건드리지 않는다. 남의 표본이다.
    """
    need_env()
    if not os.path.exists(STATE):
        print("%s 가 없다 — 지울 세션 목록이 없다." % STATE)
        return 0
    state = json.load(open(STATE, encoding="utf-8"))
    sessions = state.get("sessions", [])
    keep = {str(x.get("id")) for x in
            (json.load(open(BASELINE, encoding="utf-8")) if os.path.exists(BASELINE) else [])}
    print("정지·삭제 대상 %d개 (기존 세션 %d개는 제외)" % (len(sessions), len(keep)))
    ok = bad = 0
    for s in sessions:
        sid = str(s.get("id"))
        if sid in keep:
            print("   · %s 기존 세션 — 건드리지 않는다" % sid)
            continue
        call("POST", "/api/v1/paper-trading/sessions/%s/stop" % sid)
        st, body = call("DELETE", "/api/v1/paper-trading/history/%s" % sid)
        if st == 200:
            ok += 1
        else:
            bad += 1
            print("   ✗ %s 삭제 실패 %s: %s" % (sid, st, body))
    print("\n삭제 %d개 · 실패 %d개" % (ok, bad))
    if bad == 0:
        os.remove(STATE)
        print("%s 삭제 — probe 부터 다시 시작한다." % os.path.basename(STATE))
    else:
        print("🔴 남은 세션이 있다. 목록 파일을 지우지 않았다 — 해결 후 abort 를 다시 돌린다.")
    return 0 if bad == 0 else 1


def creation_order():
    """(코인, 팔) 생성 순서 — **코인마다 팔 순서를 한 칸씩 돌린다.**

    🔴 왜: 엔진은 `findByStatusOrderByStartedAtAsc` 로 돌므로 **생성 순서가 평가 순서로
    고정된다**. 매 코인 OFF→B→A 로 만들면 A 는 항상 마지막에 평가된다. 한 틱의 캔들은
    `TickCandleCache` 로 공유되므로 같은 자료를 보지만, 닫힌 캔들 판정은
    `Instant.now()` 를 쓴다(`PaperTradingService:667`) — 루프가 캔들 경계를 가로지르면
    먼저·나중 평가된 세션의 `lastCandleClosed` 가 갈릴 수 있다. 팔 순서를 돌려
    그 영향이 특정 팔에 쏠리지 않게 한다.
    """
    names = ["OFF", "B", "A"]
    for i, coin in enumerate(COINS):
        for j in range(3):
            yield coin, names[(i + j) % 3]


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
    for coin, arm in creation_order():
        st, body = call("POST", "/api/v1/paper-trading/sessions", {
            "strategyType": ARMS[arm], "coinPair": "KRW-" + coin,
            "timeframe": TIMEFRAME, "initialCapital": CAPITAL})
        if st != 200:
            failed.append((coin + "/" + arm, st, body))
            continue
        created.append({"coin": "KRW-" + coin, "arm": arm,
                        "strategy": ARMS[arm], "id": sid_of(body["data"])})
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
    if c == "abort":
        return cmd_abort()
    if c == "arm-gate":
        t = sys.argv[2] if len(sys.argv) > 2 else "enable"
        if t not in ("enable", "restore"):
            print("arm-gate enable | arm-gate restore")
            return 2
        return cmd_arm_gate(t)
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
