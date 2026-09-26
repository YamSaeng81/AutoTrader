# -*- coding: utf-8 -*-
"""
전향 검증 66세션 생성·검증 — docs/SUPERTREND_VALIDATION_PREREG.md §5

  OFF·B·A × 22코인 = 66 페이퍼 세션, H1, 세션당 100만원

🔴 공통 T0 을 지키는 유일한 수단은 **생성 전체를 한 평가 틱 간격(60초) 안에 넣는 것**이다.
   엔진에는 "준비됐지만 거래하지 않는" 상태가 없다 (PaperTradingService:189 → RUNNING 즉시,
   :570 runStrategy fixedDelay=60s 가 그 시점의 RUNNING 전량을 평가).

사용 (생성은 단건 66회 · 코인마다 팔 순서를 한 칸씩 돌린다 / API_BASE 기본값 http://localhost:8080 · 토큰은 .env 의 API_AUTH_TOKEN 폴백)
    python start_66.py check                     배포·정원·세 팔 활성 여부 확인 (부작용 없음)
    python start_66.py diagnose                  코인별 원인 진단 — 캐시·동기화·평가 세 층을 이어 본다
    python start_66.py scheduler                 스케줄러 포화·작업 오류 확인 (①의 절반)
    python start_66.py probe                     세 팔 생성 가능 여부 + 틱 위상 (22코인 밖, 끝나면 삭제)
    python start_66.py create --after <epoch초>   다음 틱 직후에 66개 일괄 생성
    python start_66.py verify [--wait 초]         T0 일치·T0 이전 주문 0건 검증 (기본 300초까지 기다린다)
    python start_66.py abort                      생성된 세션 전량 정지·삭제 (부분 보정 금지 규칙의 집행)
    python start_66.py arm-gate enable        팔 B 를 검증 기간 한정 재활성화 (토글 주의 — 먼저 읽은 뒤에 바꾼다)
    python start_66.py arm-gate restore       검증 종료 후 원래 상태로 되돌린다
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

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
            if not raw:
                return r.status, None
            try:
                return r.status, json.loads(raw)
            except ValueError:
                # /actuator/prometheus 처럼 JSON 이 아닌 응답도 있다 — 본문을 그대로 준다.
                return r.status, raw.decode("utf-8", errors="replace")
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


LOOKBACK = 500      # TradingConstants.CANDLE_LOOKBACK — 엔진이 보는 창의 길이(봉)

# 🔴 요구 봉 수를 **유도하지 않고 엔진에서 읽는다** (2026-09-26).
#    나는 `max(core, SENKOU_B 52 + CHIKOU 26) = 78` 로 계산했는데 **틀렸다** — 운영 로그는
#    세 팔 모두 "< 100건 필요"였다. 100 은 momentumV2Core 안의 GRID 가 요구하는 값이고
#    (`GridStrategy:154-156`), 그게 Ichimoku 의 78 을 덮는다. 유도는 이렇게 조용히 틀리므로
#    `GET /strategies/{name}` 의 minimumCandleCount 를 그대로 쓴다.
def arm_min_candles():
    """팔별 요구 봉 수 — 엔진이 보고하는 값. 세 팔이 다르면 그것 자체가 문제다."""
    out = {}
    for arm, name in ARMS.items():
        st, body = call("GET", "/api/v1/strategies/%s" % name)
        out[arm] = (body or {}).get("data", {}).get("minimumCandleCount") if st == 200 else None
    return out

# 엔진이 남기는 세 가지 흔적. 평가 시점에 **실제로 조회된 봉 수**가 여기 찍힌다.
RE_TOO_FEW = re.compile(r"모의투자 캔들 부족: (\S+) (\d+)건 \(sessionId=(\d+)\)")
RE_SHORT = re.compile(r"요구 캔들 미달[^:]*: (\S+) (\d+)건 < (\d+)건 필요 \(([^,]+), sessionId=(\d+)\)")
RE_SYNC_FAIL = re.compile(r"시장 데이터 동기화 실패: (\S+) (\S+) - (.*)")
# 🔴 2026-09-26 — 나는 동기화 실패 채널을 **하나만** 보고 있었다. `syncPair` 는 수집 결과가
#    비면 예외 없이 "캔들 수신 없음" 만 남기고 돌아간다(MarketDataSyncService). 그 경고를
#    안 보면 "실패 기록이 없다"는 오독이 나온다 — 두 채널을 모두 센다.
RE_NO_CANDLE = re.compile(r"캔들 수신 없음: (\S+) (\S+)")
# UpbitCandleCollector 의 INFO 로그 — 코인별 응답 건수(②층)
RE_COLLECT = re.compile(r"캔들 수집 완료: (\S+) (\S+) (\d+) 건")


def server_logs(keyword, lines=2000):
    st, body = call("GET", "/api/v1/settings/server-logs?level=ALL&lines=%d&keyword=%s"
                    % (lines, urllib.parse.quote(keyword)))
    if st != 200:
        return None
    return (body or {}).get("data", {}).get("entries", [])


RE_METRIC = re.compile(r'^(executor_\w+)\{[^}]*name="taskScheduler"[^}]*\}\s+([0-9.eE+-]+)',
                       re.MULTILINE)


def cmd_scheduler():
    """①층의 절반 — 스케줄러가 돌 수 있는 상태인가 (2026-09-26).

    `diagnose` 의 ②층이 21코인 전부 흔적 없음이고, 컨테이너 로그 60분에 `캔들 수집 완료` 가
    한 줄도 없었다. 그래서 다음 확인 대상은 **스케줄러 자체**다.

    🔴 두 가지를 읽는다. 추정하지 않는다.
      · 풀 포화   `executor_active_threads{name="taskScheduler"}` 가 풀 크기에 붙어 있는가.
                  풀 크기는 8 인데 `@Scheduled` 는 34개다 — `SchedulerConfig` javadoc 자신이
                  "60초 tick 9개가 겹치면 스레드 8개를 모두 점유한다"고 경고해 두었고,
                  이번에 페이퍼 세션이 40 → 106 으로 늘어 `runStrategy` 가 길어졌다.
      · 작업 오류  `setErrorHandler` 가 남기는 `스케줄러 작업 오류` — 예외로 죽었는가.

    ⚠️ `executor_queued_tasks` 를 적체로 읽지 않는다. `ScheduledThreadPoolExecutor` 의
       DelayedWorkQueue 에는 **실행 시각이 아직 안 된 예약 작업이 전부** 들어앉는다
       (`SchedulerConfig` javadoc 의 2026-09-15 오독 기록).

    🔴 포화가 확인돼도 그것이 곧 "적재 정지의 원인"은 아니다. 스케줄러가 밀리는 것과
       특정 코인이 동기화 대상 목록에 들어갔는지는 **다른 질문**이다 — 후자는
       `시장 데이터 동기화 시작: N 종목`(DEBUG)을 켜야 보인다.
    """
    need_env()
    st, body = call("GET", "/actuator/prometheus")
    if st == 200 and isinstance(body, str):
        found = RE_METRIC.findall(body)
        if found:
            print("taskScheduler 지표")
            vals = {}
            for k, v in found:
                vals[k] = float(v)
                print("   %-28s %s" % (k, v))
            act, pool = vals.get("executor_active_threads"), vals.get("executor_pool_size")
            if act is not None and pool:
                print("   → 활성 %g / 풀 %g  %s"
                      % (act, pool, "🔴 포화" if act >= pool else "여유 있음"))
                if act >= pool:
                    print("     이 시점 스냅샷 하나다. 지속 포화인지는 몇 번 더 재야 한다.")
        else:
            print("⚠️ taskScheduler 지표를 찾지 못했다 — 지표 이름이 다를 수 있다.")
            print("   서버에서: curl -s %s/actuator/prometheus | grep executor_" % BASE)
    else:
        print("⚠️ /actuator/prometheus 응답을 읽지 못했다 (HTTP %s)." % st)

    print("\n스케줄러 작업 오류 기록:")
    rows = server_logs("스케줄러 작업 오류") or []
    if not rows:
        print("   (버퍼에 없음) 🔴 경고 부재는 '오류가 없었다'의 증거가 아니다 —")
        print("      컨테이너 로그로 같은 문구를 확인한다.")
    for e in rows[-10:]:
        print("   %s  %s" % (e.get("timestamp"), e.get("message", "")[:160]))
    return 0


def report_call_path(cache, no_candle, sync_fail):
    """호출 경로 3단계 — 호출 누락 · 수집 문제 · 저장 문제를 가른다 (2026-09-26).

    🔴 빈 응답은 아직 가설이다. 추정하지 않고 경로를 따라간다.

      ① 스케줄러 실행 + 대상 목록에 22코인이 들어갔는가
         `MarketDataSyncService` 의 "시장 데이터 동기화 시작: N 종목" 은 **DEBUG** 라
         현재 로그 레벨에서는 안 보인다. ②의 흔적으로 간접 확인하고, 그래도
         안 갈리면 그 클래스만 DEBUG 로 올린다(별도 작업).
      ② 코인별 요청 범위와 응답 건수
         `UpbitCandleCollector` 의 "캔들 수집 완료: {코인} {봉} {N} 건" 은 **INFO** 다 —
         🔴 이것이 지금 바로 읽을 수 있는 유일한 직접 증거다.
      ③ 응답이 있었다면 변환·저장 후 캐시 최종 시각이 전진했는가
         "시장 데이터 동기화 완료" 는 DEBUG 지만, 전진 여부는 `upbit/status` 의
         `to` 로 직접 본다.

    세 가지를 이으면 분류가 나온다:

      ②의 흔적 없음            → **호출 누락** (스케줄러 미실행 또는 대상 목록 누락)
      ② N=0                    → **수집 문제** (요청 범위·API 응답)
      ② N>0 인데 ③ to 정체     → **저장 문제** (변환·upsert)
    """
    collect = {}
    for e in server_logs("캔들 수집 완료") or []:
        m = RE_COLLECT.search(e.get("message", ""))
        if m and m.group(2) == TIMEFRAME:
            # 같은 코인의 여러 줄 중 가장 최근 것을 쓴다 (버퍼는 시간순)
            collect[m.group(1)] = (int(m.group(3)), e.get("timestamp"))

    print("\n── 호출 경로 3단계 — 호출 누락 / 수집 문제 / 저장 문제 ──")
    if not collect:
        print("②의 흔적이 **하나도** 없다. 두 가지가 남는다:")
        print("  · 스케줄러가 이 창(로그 버퍼) 안에서 돌지 않았다")
        print("  · 로그가 버퍼에서 밀려났다")
        print("🔴 컨테이너 로그로 `캔들 수집 완료` 를 직접 확인한다 — 버퍼보다 길게 남는다.")
        print("   그래도 없으면 ①을 보려고 MarketDataSyncService 를 DEBUG 로 올린다.")
    print("%-11s %9s %-17s %s" % ("코인", "②응답건수", "③캐시 to", "분류"))
    counts = {"호출 누락": 0, "수집 문제": 0, "저장 문제": 0, "정상 전진": 0, "판정 불가": 0}
    for coin in COINS:
        pair = "KRW-" + coin
        to = (cache.get((pair, TIMEFRAME)) or {}).get("to") or "-"
        got = collect.get(pair)
        if got is None:
            k = "판정 불가" if not collect else "호출 누락"
            note = "②흔적 없음" + ("" if collect else " (버퍼 자체가 비었다)")
        elif got[0] == 0:
            k, note = "수집 문제", "응답 0건 — 요청 범위·API 응답을 본다"
        else:
            # 응답이 있었는데 to 가 최근이 아니면 저장 쪽을 본다.
            stale = to == "-" or to[:10] < got[1][:10]
            k = "저장 문제" if stale else "정상 전진"
            note = ("응답 %d건인데 to 가 전진하지 않았다" % got[0]) if stale \
                else "응답 %d건 · to 전진" % got[0]
        counts[k] += 1
        extra = []
        if pair in no_candle:
            extra.append("캔들 수신 없음")
        if pair in sync_fail:
            extra.append("동기화 실패")
        print("%-11s %9s %-17s %s%s"
              % (pair, "-" if got is None else got[0], to[:16],
                 note, (" · " + "·".join(extra)) if extra else ""))
    print("\n분류 집계: " + " · ".join("%s %d" % (k, v) for k, v in counts.items() if v))
    print("🔴 이 표는 원인을 **가르기만** 한다. 어느 칸이든 다음 확인 대상이 정해질 뿐이고,")
    print("   빈 응답·저장 실패 중 무엇이라고 지금 단정하지 않는다.")


def cmd_diagnose():
    """7코인이 평가되지 않는 원인을 **세 층을 이어서** 가른다 (2026-09-26).

    🔴 왜 세 층인가: 앞서 나는 원인을 "적재 지연 아니면 동기화 실패" 둘로 좁혔는데
    **그건 이르다.** 캐시에 봉이 있어도 조회 키·시간 범위·봉 유효성·워밍업 조건 때문에
    평가에서 빠질 수 있다. 특히 `fetchRecentCandles` 는 `[now − 500×봉주기, now]` **창**만
    조회하므로(`PaperTradingService:1072-1083`), **전체 건수가 많아도 창 안이 비어 있으면
    0봉**이다. 그러니 "총 건수 ≥ 요구량" 같은 검사는 원인을 가리지 못한다.

      ① 캐시     `market_data_cache` 의 코인별 건수·최초·최종 시각
                 (`GET /settings/upbit/status` — 🔴 이 건수는 **창 안이 아니라 전체**다)
      ② 동기화    "시장 데이터 동기화 실패"(예외) **와 "캔들 수신 없음"(빈 결과) 두 채널**
                 🔴 경고가 없는 것은 동기화 시도나 성공의 증거가 아니다. 이 채널은
                 **실패만** 보여준다
      ③ 평가      "모의투자 캔들 부족 N건" / "요구 캔들 미달 N건 < M건 필요"
                 🔴 **엔진이 평가 시점에 실제로 조회한 봉 수가 여기 찍힌다**

    ①과 ③을 나란히 놓는 것이 이 명령의 요점이다. 단, 어긋남이 말해주는 것은
    "전체 건수로는 준비 여부를 판단할 수 없었다" 뿐이다 —
    🔴 **최근 구간 적재 부족도 여전히 후보다.** 원인을 창·키·유효성으로 좁혀
    적재 문제를 제외하지 않는다.
    """
    need_env()
    st, body = call("GET", "/api/v1/settings/upbit/status")
    data = (body or {}).get("data", {}) if st == 200 else {}
    if not data.get("candleQueryOk"):
        print("✗ 캐시 현황 조회 실패 (HTTP %s): %s" % (st, data.get("candleError")))
        return 1
    cache = {(r["coinPair"], r["timeframe"]): r for r in data.get("candleSummary", [])}

    # ── ③ 평가 시점 관측치 ───────────────────────────────────────────────
    seen, short = {}, {}
    rows = server_logs("모의투자 캔들 부족")
    if rows is None:
        print("⚠️ 서버 로그를 읽을 수 없다 — ③층을 못 본다. 컨테이너 로그로 대체한다.")
        rows = []
    for e in rows:
        m = RE_TOO_FEW.search(e.get("message", ""))
        if m:
            seen[m.group(1)] = (int(m.group(2)), e.get("timestamp"))
    for e in server_logs("요구 캔들 미달") or []:
        m = RE_SHORT.search(e.get("message", ""))
        if m:
            short[m.group(1)] = (int(m.group(2)), int(m.group(3)), e.get("timestamp"))

    # ── ② 동기화 — 실패 채널이 둘이다 ────────────────────────────────────
    sync_fail = {}
    for e in server_logs("시장 데이터 동기화 실패") or []:
        m = RE_SYNC_FAIL.search(e.get("message", ""))
        if m and m.group(2) == TIMEFRAME:
            sync_fail[m.group(1)] = (m.group(3)[:60], e.get("timestamp"))
    no_candle = {}
    for e in server_logs("캔들 수신 없음") or []:
        m = RE_NO_CANDLE.search(e.get("message", ""))
        if m and m.group(2) == TIMEFRAME:
            no_candle[m.group(1)] = e.get("timestamp")
    for e in server_logs("UpbitRestClient Bean 미등록") or []:
        print("🔴 UpbitRestClient Bean 미등록 — 동기화가 아예 돌지 않는다: %s"
              % e.get("timestamp"))
        break

    # ── 요구 봉 수는 엔진에서 읽는다 ─────────────────────────────────────
    mins = arm_min_candles()
    vals = {v for v in mins.values() if v}
    MIN_CANDLES = max(vals) if vals else 100
    print("팔별 요구 봉 수 (엔진 보고): %s" % "  ".join(
        "%s=%s" % (a, mins[a]) for a in ("OFF", "B", "A")))
    if len(vals) != 1:
        print("🔴 세 팔의 요구 봉 수가 다르다 — 캔들 부족이 **팔 비대칭**을 만든다. 착수 불가.")

    # 0봉 코인은 종목이 아직 거래되는지부터 확인한다 — 동기화 문제와 상장·거래 문제는 다르다.
    zero = [c for c in COINS
            if (seen.get("KRW-" + c, (None,))[0] == 0
                or int((cache.get(("KRW-" + c, TIMEFRAME)) or {}).get("count", 0) or 0) == 0)]
    ticker_ok = {}
    if zero:
        st, body = call("GET", "/api/v1/settings/upbit/ticker?markets=%s"
                        % ",".join("KRW-" + c for c in zero))
        for t in ((body or {}).get("data") or []) if st == 200 else []:
            ticker_ok[t.get("market")] = t.get("trade_price")

    window_h = LOOKBACK   # H1 이므로 500봉 = 500시간
    print("\n엔진이 보는 창 = 최근 %d봉 (H1 이면 %d시간). 최소 요구 %d봉.\n"
          % (LOOKBACK, window_h, MIN_CANDLES))
    # 🔴 창 안 봉 수 추정 — `to` 가 창 시작보다 앞서면 창 안은 비어 있다. 캐시가 멈춘 코인은
    #    창이 흐르면서 **봉 수가 시간당 1개씩 줄어들어 결국 요구량 아래로 떨어진다.**
    #    (추정이다. 창 시작~to 사이가 시간당 연속이라고 가정한다 — 엔진 실측이 있으면 그게 우선.)
    period_min = 60          # H1
    now_utc = datetime.now(timezone.utc)
    win_start = now_utc - timedelta(minutes=LOOKBACK * period_min)

    print("%-11s %8s %-17s %7s %7s %7s %s"
          % ("코인", "캐시건수", "캐시 최종(to)", "엔진관측", "창안추정", "잔여일", "판정"))

    verdicts, doomed = {}, []
    for coin in COINS:
        pair = "KRW-" + coin
        r = cache.get((pair, TIMEFRAME))
        n_cache = int(r["count"]) if r else 0
        to = (r or {}).get("to") or "-"
        obs = seen.get(pair, (None, None))[0]
        if obs is None and pair in short:
            obs = short[pair][0]

        est, left = None, None
        if to != "-":
            try:
                t_last = datetime.fromisoformat(to.replace("Z", "")).replace(tzinfo=timezone.utc)
                est = max(0, min(LOOKBACK,
                                 int((t_last - win_start).total_seconds() // (period_min * 60))))
                left = (est - MIN_CANDLES) * period_min / 60.0 / 24.0
                if est >= MIN_CANDLES and left < 180:
                    doomed.append((pair, est, left))
            except ValueError:
                pass

        if pair in sync_fail:
            v = "🔴 동기화 실패: " + sync_fail[pair][0]
        elif obs is None:
            # 🔴 미달 경고가 없다는 것은 **평가됐다는 증거가 아니다.** 로그가 버퍼에서
            #    밀려났을 수도 있다. 평가 여부는 verify 의 strategy_log 로만 말한다.
            v = "? 미달 경고 없음 — 평가 여부는 verify 로 확인한다"
        elif obs == 0:
            # 0봉은 "적다"와 질이 다르다 — 창 안에 아무것도 없다.
            # 종목이 아직 거래되는지부터 가른다: 시세가 오면 종목은 살아 있고 적재가 안 된
            # 것이며, 시세도 안 오면 상장·거래 자체를 확인해야 한다(동기화 문제가 아니다).
            px = ticker_ok.get(pair)
            nc = " · 수집 시도가 빈 결과였다(캔들 수신 없음)" if pair in no_candle else ""
            v = ("🔴 창 안 0봉 — 시세는 %s 로 조회됨(종목 생존). 적재가 안 되고 있다" % px
                 + nc if px is not None else
                 "🔴 창 안 0봉 + 시세 조회 실패 — 상장·거래 여부를 먼저 확인한다")
        elif n_cache >= MIN_CANDLES and obs < MIN_CANDLES:
            # 🔴 이 어긋남이 말해주는 것은 "전체 건수로는 준비 여부를 판단할 수 없었다"는
            #    것뿐이다. **최근 구간 적재 부족도 여전히 후보다** — 전체가 많아도 창 안이
            #    비어 있을 수 있다. 원인을 창·키·유효성으로 좁히지 않는다.
            v = "🔴 전체 %d봉인데 엔진은 %d봉 — 전체 건수로는 판단 불가 (최근 구간 적재 부족 포함)" \
                % (n_cache, obs)
        elif obs < MIN_CANDLES:
            v = "🔴 엔진 %d봉 < %d봉 — 최근 구간이 부족하다" % (obs, MIN_CANDLES)
        else:
            v = "· 엔진 %d봉" % obs
        verdicts[pair] = v
        print("%-11s %8d %-17s %7s %7s %7s %s"
              % (pair, n_cache, to[:16], "-" if obs is None else obs,
                 "-" if est is None else est,
                 "-" if left is None else ("미달" if left < 0 else "%.1f" % left), v))

    bad = [p for p, v in verdicts.items() if v.startswith("🔴")]
    unknown = [p for p, v in verdicts.items() if v.startswith("?")]
    print("문제 %d종 · 판정 불가 %d종 / 22" % (len(bad), len(unknown)))
    if doomed:
        print("\n🔴 캐시가 멈춘 채로는 지금 통과하는 코인도 차례로 탈락한다 —")
        print("   창이 흐르면 창 안 봉 수가 시간당 1개씩 줄어든다. 잔여일이 짧은 순:")
        for pair, est, left in sorted(doomed, key=lambda x: x[2])[:8]:
            print("     %-11s 창 안 %3d봉 → %.1f일 뒤 %d봉 미달" % (pair, est, left, MIN_CANDLES))
        print("   🔴 §5 의 종료 조건은 **최대 6개월**이다. 적재가 계속되지 않으면 검증 자체가")
        print("      성립하지 않는다 — 8코인을 고치는 문제가 아니다.")
    if unknown:
        print("   ? 는 미달 경고가 없는 코인이다 — 정상 평가일 수도, 로그가 버퍼에서 밀려난")
        print("     것일 수도 있다. **경고 부재는 평가의 증거가 아니다.**")
    if bad:
        print("🔴 22코인을 15코인으로 줄이지 않는다 — §4 (가) 의 분모는 22 로 고정돼 있고,")
        print("   준비된 코인만 남기면 **코인 구성에 따른 선택 편향**이 생긴다(팔 대칭과 별개다).")
        print("   원인을 고친 뒤 22코인 전부가 준비됐음을 확인하고 **새 T0 로** 다시 시작한다.")
    print("\n🔴 이 명령이 문제 0종을 내도 그것은 '준비됐다'는 확인이 아니다.")
    print("   이 표의 ① 은 전체 건수이고 ②·③ 은 경고가 남았을 때만 보인다 —")
    print("   준비 확인은 **엔진과 같은 조회·워밍업 조건으로** 통과시켜야 한다.")
    print("   (재시작 전 그 확인 절차를 먼저 정하고, 그 다음에 create 한다.)")
    report_call_path(cache, no_candle, sync_fail)
    return 0 if not bad else 1


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


def cmd_verify(wait_sec=300):
    """T0 일치 — 66개의 첫 평가가 같은 틱인가, T0 이전 주문이 0건인가.

    🔴 기다린다 (2026-09-26 추가). `strategy_log` 는 **새 닫힌 캔들을 평가할 때만** 기록된다
    (`PaperTradingService:734-757` — 로그 저장이 그 `else` 분기 안에 있다). 그래서 생성 직후
    돌리면 전량 "로그 없음"이 나온다 — 첫 create 를 그렇게 읽어 0/66 을 봤다.
    스케줄러는 initialDelay 35초 · fixedDelay 60초이고 106세션 루프 자체가 1분 가까이 걸린 적이
    있다(2026-09-15 p95 57~71초). 전부 모일 때까지 폴링한다.

    🔴 "로그 없음"의 원인은 셋이고, 셋이 서로 다른 처분을 요구한다:
      ① 아직 첫 틱이 오지 않았다            → 기다리면 된다 (이 함수가 한다)
      ② 전략 요구 캔들 미달                  → 그 세션은 **조용히 아무것도 하지 않는다**.
         `PaperTradingService:679-682` 가 평가와 로그를 함께 건너뛴다.
         📌 세 팔의 최소 캔들 요구는 **같다 — 100봉이다.** 운영 로그가 세 팔 모두
         "< 100건 필요"로 찍었다(2026-09-26). 🔴 내가 앞서 유도한 78 은 틀렸다 —
         momentumV2Core 안의 GRID 가 100 을 요구해(`GridStrategy:154-156`) Ichimoku 의 78 을
         덮는다. 그래서 이 값은 유도하지 않고 `GET /strategies/{name}` 에서 읽는다.
         캔들이 부족하면 세 팔이 함께 빠지므로 **팔 사이 비대칭은 아니지만**, 준비된
         코인만 남으면 **코인 구성에 따른 선택 편향**이 생긴다 — 아래에서 코인 단위로 묶어
         보여준다. 0거래를 성과로 읽으면 안 된다.
      ③ 로그 저장 자체가 실패했다            → 서버 로그의 "전략 로그 저장 실패" 를 본다
    """
    need_env()
    state = json.load(open(STATE, encoding="utf-8"))
    sessions = state["sessions"]

    deadline = time.time() + max(0, wait_sec)
    times = {}
    while True:
        for s in sessions:
            if s["id"] not in times:
                t = first_log_time(s["id"])
                if t:
                    times[s["id"]] = t
        left = len(sessions) - len(times)
        if left == 0 or time.time() >= deadline:
            break
        print("   첫 평가 대기 — %d/%d (남은 시간 %.0f초)"
              % (len(times), len(sessions), deadline - time.time()))
        time.sleep(10)

    rows = []
    for s in sessions:
        st, body = call("GET", "/api/v1/paper-trading/sessions/%s/orders" % s["id"])
        orders = (body or {}).get("data", {}).get("orders", []) if st == 200 else []
        rows.append((s, times.get(s["id"]), orders))

    missing = [s for s, t, _ in rows if not t]
    stamps = sorted({datetime.fromisoformat(t.replace("Z", "+00:00")).timestamp()
                     for _, t, _ in rows if t})
    print("\n① 첫 평가 로그 — %d/%d" % (len(rows) - len(missing), len(rows)))
    if missing:
        by_coin = {}
        for s in missing:
            by_coin.setdefault(s["coin"], []).append(s["arm"])
        whole = {c: a for c, a in by_coin.items() if len(a) == 3}
        part = {c: a for c, a in by_coin.items() if len(a) < 3}
        print("   🔴 로그 없는 세션 %d개" % len(missing))
        if whole:
            print("   · 세 팔 전부 빠진 코인 %d종 — 요구 캔들 수는 세 팔이 같다(100봉)."
                  % len(whole))
            print("     %s" % " ".join(sorted(whole)))
            print("     🔴 이것은 '팔 사이 비대칭이 없다'는 뜻일 뿐 **편향이 없다는 뜻이 아니다.**")
            print("        준비된 코인만 남으면 **코인 구성에 따른 선택 편향**이 생긴다 —")
            print("        자료가 준비되는 조건(상장 시기·유동성·수집 대상 여부)이 성과와")
            print("        무관하다고 볼 근거가 없다. 표본을 줄여 진행하지 않는다.")
            print("     🔴 원인은 `diagnose` 로 가른다 — 적재량·동기화 말고도 조회 창·키·")
            print("        봉 유효성·워밍업 조건이 모두 후보다.")
        if part:
            print("   · 일부 팔만 빠진 코인 %d종 — **팔 사이 비대칭이다. 원인을 밝히기 전에**"
                  % len(part))
            print("     **집계하지 않는다.**")
            for c in sorted(part):
                print("     %-10s %s" % (c, " ".join(sorted(part[c]))))
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
                   if ok else "🔴 성립하지 않는다 — 원인을 위에서 확인한다."))
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
        w = int(sys.argv[sys.argv.index("--wait") + 1]) if "--wait" in sys.argv else 300
        return cmd_verify(w)
    if c == "diagnose":
        return cmd_diagnose()
    if c == "scheduler":
        return cmd_scheduler()
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
