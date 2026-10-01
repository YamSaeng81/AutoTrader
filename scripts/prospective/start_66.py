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
    python start_66.py abort                     66세션 **정지만** 한다 (🔴 삭제하지 않는다 — DB 기록 보존)
    python start_66.py lockprobe start|stop      잠금 제한 시험용 통제 경로 (실거래·22코인 미사용 코인)
    python start_66.py prep start|stop           준비 점검용 세션으로 22코인 동기화 유지 (🔴 검증 집계 제외)
    python start_66.py freshness [--save]        코인별 최신 봉·지연·이동 확인 + 72시간 연속 관찰 누적
    python start_66.py watch [--notify] [--only-alerts]
                                                 하루 한 번 자동 감시 — 적재+세션+운영 로그를
                                                 한 번에 보고. --notify 로 텔레그램 전송
    python start_66.py alerts [--days N|--since ISO] [--no-mark]
                                                 🔴 **지난 확인 이후 전체**의 경보·실행 누락 (일일 감시용 — tail 로는 중간 🔴 이 가려진다)
                                                 🔴 매시간 실행한다 — 시작·종료만 보면 중간 정지를 놓친다
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
            # 🔴 실제 지표 이름은 `executor_pool_size_threads` 다 (2026-09-26 실측).
            #    `executor_pool_size` 로 찾다가 조용히 비교를 건너뛰었다.
            #    `executor_pool_max_threads` 는 Integer.MAX_VALUE 라 포화 기준이 아니다.
            act = vals.get("executor_active_threads")
            pool = vals.get("executor_pool_size_threads") or vals.get("executor_pool_core_threads")
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

    # -- 실행 빈도 — 포화가 아니어도 "돌고 있는가"는 별개 질문이다 --------
    #    executor_completed_tasks_total 을 60초 간격으로 두 번 재면 분당 완료 수가 나온다.
    #    @Scheduled 34개 중 5초 주기 4개만 해도 분당 48회가 기대치다.
    #    🔴 기대치는 주기 분포에서 나온 계산값이고 작업마다 실행 시간이 다르므로
    #       미달 자체가 곧 결함은 아니다 — 크게 어긋나면 어느 작업이 긴지 본다.
    def _completed():
        st2, b2 = call("GET", "/actuator/prometheus")
        if st2 != 200 or not isinstance(b2, str):
            return None
        for k, v in RE_METRIC.findall(b2):
            if k == "executor_completed_tasks_total":
                return float(v)
        return None

    if st == 200 and isinstance(body, str):
        m = re.search(r"^process_uptime_seconds\s+([0-9.eE+-]+)", body, re.MULTILINE)
        up = float(m.group(1)) if m else None
        if up:
            print("")
            print("프로세스 가동 %.1f시간" % (up / 3600))
        c0 = _completed()
        if c0 is not None:
            if up:
                print("   누적 완료 %g → 평균 분당 %.1f회" % (c0, c0 / (up / 60)))
            print("   60초 뒤 다시 재서 현재 분당 완료 수를 본다...")
            time.sleep(60)
            c1 = _completed()
            if c1 is not None:
                print("   이번 60초 완료 %g회  (5초 주기 4개만으로도 기대 48회)" % (c1 - c0))
                if c1 - c0 < 20:
                    print("   🔴 기대보다 크게 낮다 — 어느 작업이 스레드를 오래 잡는지 본다.")
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
        obs, obs_at = seen.get(pair, (None, None))
        if obs is None and pair in short:
            obs, obs_at = short[pair][0], short[pair][2]

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
        elif est is not None and est >= MIN_CANDLES and obs is not None and obs < MIN_CANDLES:
            # 🔴 내 도구의 결함을 고친 부분이다 (2026-09-26). ③ 은 인메모리 버퍼에 남은
            #    **지나간 경고**도 그대로 읽는다. 적재가 뒤늦게 채워진 뒤에도 옛 경고가 남아
            #    허위 신호를 냈다 — 실제로 22코인이 다 채워진 뒤에도 8종이 🔴 로 나왔다.
            #    추정이 요구를 넘으면 관측 시각을 함께 보여주고 판정은 verify 로 넘긴다.
            v = ("⚠️ 엔진 관측 %d봉(%s)은 적재 이전의 옛 경고일 수 있다 — verify 로 확인한다"
                 % (obs, obs_at or "?"))
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

    # 🔴 생성 이후에 돌리면 이 명령의 전제가 깨진다 (2026-10-01).
    #    `check` 는 **생성 전** 점검이다. 66세션이 이미 있으면 그것까지 "앞으로 만들 것"으로
    #    세어 정원을 172/120 으로 틀리게 보고하고, 아래 ③ 이 제외 목록을 덮어쓰면서
    #    **우리 66세션을 '남의 표본'으로 등록한다** — 그러면 `abort` 가 그 66개의 정지를 거부한다.
    #    실제로 2026-10-01 T0 직후에 그렇게 덮어썼다. 그래서 생성 이후에는 읽기만 한다.
    if os.path.exists(STATE):
        print("")
        print("🔴 `sessions_66.json` 이 이미 있다 — **생성 이후**다. 이 명령은 생성 전 점검용이므로")
        print("   정원 계산(+66)과 제외 목록 갱신을 **건너뛴다**. 아래는 현황만이다.")
        print("② 정원(현황) — RUNNING %d / %d   %s"
              % (len(running), SESSION_CAP, "✔" if len(running) <= SESSION_CAP else "✗"))
        own = {str(x.get("id")) for x in
               json.load(open(STATE, encoding="utf-8")).get("sessions", [])}
        live = {str(sid_of(s)) for s in running}
        mine = own & live
        print("   그중 검증 66세션 RUNNING %d / 66   %s"
              % (len(mine), "✔" if len(mine) == 66 else "🔴 빠진 세션이 있다"))
        if len(mine) != 66:
            print("   🔴 RUNNING 아닌 검증 세션: %s"
                  % " ".join(sorted(own - live, key=int)))
        return 0 if ok and len(mine) == 66 else 1
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
    """실패한 66세션을 **정지만** 한다 — 🔴 삭제하지 않는다 (2026-09-28 정정).

    ⚠️ **처음 구현은 stop 뒤에 `DELETE /history/{id}` 까지 했다. 그건 잘못이다 — 제거했다.**
    준비 실패로 판정한 세션이라도 **실행 이력은 자료다.** `paper_order` · 포지션 ·
    `strategy_log` 를 지우면 "그때 무엇이 일어났는지"를 다시 볼 수 없다.
    `sessions_66.json` 과 사전 등록 문서는 **세션 ID 목록과 판단 근거**일 뿐이며
    실행 이력 전체를 대신하지 못한다.

    🔴 `preexisting_excluded.json` 의 기존 세션은 건드리지 않는다 — 남의 표본이다.
    🔴 실거래·동적 세션은 이 명령이 아예 다루지 않는다(페이퍼 세션 엔드포인트만 쓴다).

    정지 결과는 `stopped_66.json` 에 남긴다 — 무엇을 언제 멈췄는지의 기록이다.
    """
    need_env()
    if not os.path.exists(STATE):
        print("%s 가 없다 — 정지할 세션 목록이 없다." % STATE)
        return 0
    state = json.load(open(STATE, encoding="utf-8"))
    sessions = state.get("sessions", [])
    keep = {str(x.get("id")) for x in
            (json.load(open(BASELINE, encoding="utf-8")) if os.path.exists(BASELINE) else [])}

    # 🔴 지정한 ID 만 건드린다는 것을 실행 전에 보여준다.
    ids = [str(s.get("id")) for s in sessions if str(s.get("id")) not in keep]
    print("정지 대상 %d개 (기존 세션 %d개 제외) — 🔴 삭제하지 않는다" % (len(ids), len(keep)))
    print("   대상 ID: %s" % (" ".join(ids) if ids else "(없음)"))

    stopped, failed, already = [], [], []
    for s in sessions:
        sid = str(s.get("id"))
        if sid in keep:
            print("   · %s 기존 세션 — 건드리지 않는다" % sid)
            continue
        st, body = call("POST", "/api/v1/paper-trading/sessions/%s/stop" % sid)
        if st == 200:
            stopped.append(s)
        else:
            txt = str(body)
            # 이미 정지된 세션은 실패가 아니다 — 구분해서 보고한다.
            if "RUNNING" in txt or "이미" in txt or st == 400:
                already.append(sid)
            else:
                failed.append((sid, st, txt[:120]))

    print("\n정지 %d개 · 이미 정지 %d개 · 실패 %d개" % (len(stopped), len(already), len(failed)))
    for f in failed:
        print("   ✗ %s: %s %s" % f)

    rec = os.path.join(_HERE, "stopped_66.json")
    with open(rec, "w", encoding="utf-8") as fh:
        json.dump({"stoppedAt": datetime.now(timezone.utc).isoformat(),
                   "why": "준비 실패로 판정한 66세션 — 정지만 한다. DB 기록(주문·포지션·"
                          "strategy_log)은 보존한다. 사전 등록 문서 참조.",
                   "stopped": [{"id": s.get("id"), "coin": s.get("coin"), "arm": s.get("arm")}
                               for s in stopped],
                   "alreadyStopped": already,
                   "failed": [f[0] for f in failed]},
                  fh, ensure_ascii=False, indent=1)
    print("정지 기록 저장: %s" % rec)
    print("🔴 `sessions_66.json` 을 지우지 않는다 — 어느 세션이 그 실행이었는지 남겨야 한다.")
    print("   집계에서 제외하는 근거는 문서이고, 제외 대상 식별은 이 파일이다.")
    return 0 if not failed else 1


def cmd_lockprobe(action):
    """잠금 제한 시험용 **통제된 실행 경로**를 만든다/치운다 (2026-09-28).

    🔴 왜 별도 경로가 필요한가: 동기화 대상은 **RUNNING 세션의 코인**으로 정해진다
    (`MarketDataSyncService.syncMarketData`). 66세션을 정지하면 22코인은 더 이상 수집되지
    않으므로, 잠금 시험을 하려면 **그 시험만을 위한 세션**이 있어야 한다.

    🔴 대상 코인 선정 기준 — 공유 테이블을 건드리는 시험이므로 영향을 좁힌다.
      · 실거래·동적·기존 페이퍼 세션이 쓰는 코인을 **피한다** (그 행을 잠그면 실제 매매가 막힌다)
      · 전향 검증 22코인도 **피한다** (그 기록을 오염시키지 않는다)
      · 시세가 조회되는 살아 있는 종목이어야 한다
    ⚠️ "ROLLBACK 하면 되돌려진다"는 이유만으로 영향이 없는 것이 아니다 — 잠금이 걸린 동안
       그 행을 쓰는 작업은 **실제로 대기한다.**
    """
    need_env()
    rec = os.path.join(_HERE, "lockprobe.json")

    if action == "stop":
        if not os.path.exists(rec):
            print("%s 가 없다 — 치울 것이 없다." % os.path.basename(rec))
            return 0
        d = json.load(open(rec, encoding="utf-8"))
        st, _ = call("POST", "/api/v1/paper-trading/sessions/%s/stop" % d["sessionId"])
        print("잠금 시험 세션 %s 정지 (HTTP %s) — 🔴 삭제하지 않는다(기록 보존)" % (d["sessionId"], st))
        return 0

    # ── 사용 중인 코인 수집 ───────────────────────────────────────────────
    used = set()
    st, body = call("GET", "/api/v1/paper-trading/sessions")
    for s in (body or {}).get("data", []) if st == 200 else []:
        if s.get("status") == "RUNNING" and s.get("coinPair"):
            used.add(s["coinPair"])
    st, body = call("GET", "/api/v1/dynamic-sessions")
    for s in (body or {}).get("data", []) if st == 200 else []:
        if s.get("status") == "RUNNING" and s.get("currentCoinPair"):
            used.add(s["currentCoinPair"])
    used |= {"KRW-" + c for c in COINS}
    # 동적 세션은 워치리스트로 코인을 옮겨 다니므로, 관측된 수집 대상도 함께 뺀다.
    for e in server_logs("캔들 수집 완료") or []:
        m = RE_COLLECT.search(e.get("message", ""))
        if m:
            used.add(m.group(1))
    print("피할 코인 %d종 (RUNNING 세션·22코인·최근 수집 대상)" % len(used))

    # ── 후보 선정 — 시세가 조회되는 살아 있는 종목 ────────────────────────
    CANDIDATES = ["KRW-STORJ", "KRW-CVC", "KRW-MTL", "KRW-ARDR", "KRW-STEEM",
                  "KRW-QTUM", "KRW-ZRX", "KRW-OMG", "KRW-SNT", "KRW-LSK"]
    pick = None
    for c in CANDIDATES:
        if c in used:
            continue
        st, body = call("GET", "/api/v1/settings/upbit/ticker?markets=%s" % c)
        rows = (body or {}).get("data") or [] if st == 200 else []
        if rows and rows[0].get("trade_price"):
            pick = c
            break
    if not pick:
        print("🔴 쓸 수 있는 코인을 찾지 못했다. 후보 목록을 넓히거나 직접 지정한다.")
        return 1
    print("선정: %s — 실거래·동적·22코인과 겹치지 않는다" % pick)

    st, body = call("POST", "/api/v1/paper-trading/sessions", {
        "strategyType": ARMS["OFF"], "coinPair": pick,
        "timeframe": TIMEFRAME, "initialCapital": CAPITAL})
    if st != 200:
        print("✗ 세션 생성 실패 %s: %s" % (st, body))
        return 1
    sid = sid_of(body["data"])
    with open(rec, "w", encoding="utf-8") as fh:
        json.dump({"sessionId": sid, "coinPair": pick,
                   "createdAt": datetime.now(timezone.utc).isoformat(),
                   "why": "lock_timeout 시험용 통제 경로. 실거래·22코인과 겹치지 않는 코인."},
                  fh, ensure_ascii=False, indent=1)
    print("세션 %s 생성 — 이 코인이 동기화 대상에 들어간다" % sid)
    # 🔴 "행이 있다"로는 부족하다 — 2026-09-28 실측: count>0 이 **이미 있던 낡은 행**
    #    (to=2026-09-15)으로 충족됐다. 그 행은 동기화가 갱신하는 대상이 아닐 수 있다.
    #    syncPair 는 lastStored − GAP_SYNC_OVERLAP_CANDLES(5) 부터 다시 받아 upsert 하므로
    #    **최근 5봉이 매 회차 갱신된다.** 따라서 잠글 행은 동기화가 따라잡은 뒤의 최신 행이어야
    #    한다. 두 조건을 모두 본다: ① 이 코인의 수집 로그가 보이는가 ② 캐시 to 가 최근인가.
    print("")
    print("동기화가 이 코인을 **따라잡을 때까지** 기다린다 (최대 8분)")
    print("   조건 ① 이 코인의 `캔들 수집 완료` 로그  ② 캐시 to 가 최근")
    for _ in range(48):
        time.sleep(10)
        collected = False
        for e in (server_logs("캔들 수집 완료") or []):
            m = RE_COLLECT.search(e.get("message", ""))
            if m and m.group(1) == pick and m.group(2) == TIMEFRAME:
                collected = True
                break
        st, body = call("GET", "/api/v1/settings/upbit/status")
        rows = {(r["coinPair"], r["timeframe"]): r
                for r in ((body or {}).get("data", {}) or {}).get("candleSummary", [])}
        r = rows.get((pick, TIMEFRAME))
        to_recent = False
        if r and r.get("to"):
            try:
                t_last = datetime.fromisoformat(r["to"].replace("Z", "")).replace(tzinfo=timezone.utc)
                to_recent = (datetime.now(timezone.utc) - t_last).total_seconds() / 60 <= 150
            except ValueError:
                pass
        print("   수집로그=%s / to=%s / 최근=%s" % (collected, (r or {}).get("to"), to_recent))
        if collected and to_recent:
            print("✔ 따라잡았다 — %s %s %s건 (to=%s)" % (pick, TIMEFRAME, r["count"], r["to"]))
            break
    else:
        print("🔴 8분 안에 따라잡지 못했다. 이 상태로 잠그면 **동기화가 그 행을 갱신하지 않을 수")
        print("   있어** 시험이 성립하지 않는다. 원인을 먼저 본다.")
        return 1

    print("\n" + "=" * 70)
    print("다음: psql 에서 아래를 실행해 **그 코인의 최신 행**을 잠근다 (커밋하지 않는다)")
    print("=" * 70)
    print("""BEGIN;
UPDATE market_data_cache SET close = close
 WHERE coin_pair='%s' AND timeframe='%s'
   AND time = (SELECT max(time) FROM market_data_cache
                WHERE coin_pair='%s' AND timeframe='%s');
-- 그대로 둔다. 확인이 끝나면 ROLLBACK;""" % (pick, TIMEFRAME, pick, TIMEFRAME))
    print("=" * 70)
    print("그리고 백엔드 로그에서:")
    print("  docker compose -f docker-compose.prod.yml logs -f --since 2m backend \\")
    print("    | grep -E '시장 데이터 동기화 실패|lock timeout|canceling statement|캔들 수집 완료'")
    print("")
    print("🔴 이 행이 시험 대상인 이유: syncPair 는 lastStored 에서 5봉 겹쳐 다시")
    print("   받아 upsert 하므로 **최신 행이 매 회차 갱신된다.** 낡은 행을 잠그면")
    print("   동기화가 그 행을 건드리지 않아 시험이 성립하지 않는다.")
    print("\n판정: ~10초 뒤 잠금 제한 실패가 나오고, ROLLBACK 뒤 다음 회차가 이 코인을")
    print("      정상 수집하면 통과다. 🔴 통과해도 '모든 종류의 장기 정지를 막았다'는 뜻은 아니다.")
    print("치우기: python start_66.py lockprobe stop")
    return 0


PREP = os.path.join(_HERE, "prep_sessions.json")
FRESH = os.path.join(_HERE, "freshness_snap.json")


def cmd_prep(action):
    """준비 점검용 세션 — **22코인을 동기화 대상으로 유지**한다 (2026-09-28).

    🔴 왜 필요한가: 동기화 대상은 **RUNNING 세션의 코인**으로 정해진다
    (`MarketDataSyncService.syncMarketData`). 66세션을 정지한 지금 22코인은 수집되지 않는다.
    따라서 "세션 없이 적재 지속성만 며칠 관찰"하는 것은 불가능하다.

    🔴 이 세션은 검증 대상이 아니다. 사전 기준(§4 가)의 분모는 66이며 여기서 만드는
    세션은 거기에 들어가지 않는다. 지켜야 할 것은 **그 성과를 보고 코인·전략·기준을
    조정하지 않는 것**이다 — 세션 ID 를 파일에 남겨 검증 집계에서 제외한다.

    ⚠️ 팔은 OFF 하나만 쓴다. 세 팔을 다 돌리면 준비 점검이 검증의 예비 실행처럼 되어
       '결과를 먼저 본 뒤 기준을 고치는' 경로가 열린다. 적재 관찰에는 코인당 한 세션이면 된다.
    """
    need_env()

    if action == "stop":
        if not os.path.exists(PREP):
            print("%s 가 없다 — 치울 것이 없다." % os.path.basename(PREP))
            return 0
        d = json.load(open(PREP, encoding="utf-8"))
        ids = d["sessionIds"]
        print("준비 점검용 세션 %d개를 정지한다 — 🔴 삭제하지 않는다(기록 보존)" % len(ids))
        print("   %s" % " ".join(str(i) for i in ids))
        ok = fail = 0
        for sid in ids:
            st, _ = call("POST", "/api/v1/paper-trading/sessions/%s/stop" % sid)
            if st == 200:
                ok += 1
            else:
                fail += 1
                print("   x %s HTTP %s" % (sid, st))
        d["stoppedAt"] = datetime.now(timezone.utc).isoformat()
        with open(PREP, "w", encoding="utf-8") as fh:
            json.dump(d, fh, ensure_ascii=False, indent=1)
        print("정지 %d / 실패 %d" % (ok, fail))
        print("🔴 이 시점부터 22코인은 다시 동기화 대상이 아니다 — 새 T0 는 곧바로 이어서 잡는다.")
        return 0 if fail == 0 else 1

    if os.path.exists(PREP):
        d = json.load(open(PREP, encoding="utf-8"))
        if not d.get("stoppedAt"):
            print("🔴 이미 준비 점검용 세션이 있다 (%d개). 먼저 `prep stop` 을 돌린다."
                  % len(d["sessionIds"]))
            return 1

    made, failed = [], []
    for c in COINS:
        pair = "KRW-" + c
        st, body = call("POST", "/api/v1/paper-trading/sessions", {
            "strategyType": ARMS["OFF"], "coinPair": pair,
            "timeframe": TIMEFRAME, "initialCapital": CAPITAL})
        if st == 200:
            made.append({"sessionId": sid_of(body["data"]), "coinPair": pair})
        else:
            failed.append((pair, st, str(body)[:120]))
    with open(PREP, "w", encoding="utf-8") as fh:
        json.dump({"sessionIds": [m["sessionId"] for m in made], "sessions": made,
                   "createdAt": datetime.now(timezone.utc).isoformat(),
                   "why": "22코인 적재 지속성 확인용. 🔴 전향 검증 집계에서 제외한다 "
                          "(§4 가 의 분모 66 에 들어가지 않는다)."},
                  fh, ensure_ascii=False, indent=1)
    print("준비 점검용 세션 %d/%d 생성 — 기록: %s" % (len(made), len(COINS), os.path.basename(PREP)))
    for pair, st, msg in failed:
        print("   x %s HTTP %s %s" % (pair, st, msg))
    if failed:
        print("🔴 일부 코인이 생성되지 않았다 — 그 코인은 수집되지 않으므로 적재 관찰에서도 빠진다.")
    print("")
    print("다음: `freshness --save` 로 기준 스냅샷을 찍고, 한 시간 이상 뒤 `freshness` 로 전진을 본다.")
    return 0 if not failed else 1


FRESH_LOG = os.path.join(_HERE, "freshness_log.jsonl")
STREAK = os.path.join(_HERE, "freshness_streak.json")
REQUIRED_HOURS = 72          # 운영 준비 점검 기준 — 무중단 보장 기준이 아니다
MAX_GAP_MIN = 90             # 이보다 벌어지면 그 구간은 **관측 누락**이다


def _upbit_latest_h1(pair):
    """그 순간 **거래소가 제공하는** 최신 H1 봉 시각 — 판정의 비교 대상이다 (2026-10-01).

    🔴 왜 벽시계가 아니라 이것인가: H1 봉은 그 시간의 **첫 체결이 일어나야 생긴다.**
    09-28~09-30 관측에서 🔴 16건이 모두 이 때문이었다 — 매시 :19 점검에서 POWR·GLM 의
    1분봉이 18:00~18:20 사이에 **0개**였고(HIVE 는 18:20 에 첫 체결), 즉 그 시간봉은
    거래소에 **존재하지 않았다**. 없는 봉을 적재하지 않은 것은 결함이 아니다.
    인증이 필요 없는 공개 API 다.

    🔴 404 와 통신 실패를 **구분한다**: 404 는 그 마켓이 거래소에 없다는 뜻이고(상장폐지 등)
       사람이 봐야 하는 🔴 다. 통신 실패는 우리가 못 본 것이므로 ⚪ 판정 불가다 — 둘을 섞으면
       상장폐지를 조용히 넘기거나 일시적 네트워크 오류를 결함으로 세게 된다.

    🔴 일시 실패에는 **물러서며 다시 시도한다** (2026-10-01 추가). 업비트 한도는 **IP 단위**이므로
       같은 서버의 앱이 호출을 폭주시키면 이 스크립트까지 429 를 맞는다. 실제로 2026-10-01
       06:20:17 에 그 일이 났다 — 앱이 KRW-G·KRW-INJ 수집에 실패한 바로 그 초에 이 도구는
       KRW-BLAST·KRW-JUP 을 읽지 못해 ⚪ 가 되고 72시간 구간이 초기화됐다(같은 회차 red 0,
       즉 적재는 멈추지 않았다). 한 번의 429 로 '판정 불가'를 선언하지 않는다.
       ⚠️ 기준이 느슨해지는 것이 아니다 — 끝까지 못 읽으면 여전히 ⚪ 이고 구간은 끊긴다.

    @return (봉 시각, 오류종류) — 오류종류는 None · "404" · "net"
    """
    url = ("https://api.upbit.com/v1/candles/minutes/60?market=%s&count=1"
           % urllib.parse.quote(pair))
    err = "net"
    for attempt in range(3):
        if attempt:
            time.sleep(1.5 * attempt)      # 0 → 1.5s → 3.0s
        try:
            req = urllib.request.Request(url, headers={"Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=10) as r:
                arr = json.loads(r.read().decode("utf-8"))
            if not arr:
                return None, "404"
            return datetime.fromisoformat(
                arr[0]["candle_date_time_utc"]).replace(tzinfo=timezone.utc), None
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None, "404"     # 🔴 영구 실패다 — 다시 시도하지 않는다
            err = "net"
        except Exception:
            err = "net"
    return None, err


def _db_latest():
    """코인별 DB 최신 봉(`to`)과 봉수 — 캐시 없이 매번 GROUP BY 로 읽는다
    (`SettingsController` → `findDataSummary`)."""
    st, body = call("GET", "/api/v1/settings/upbit/status")
    if st != 200:
        return None
    data = (body or {}).get("data", {}) or {}
    return {r["coinPair"]: r for r in data.get("candleSummary", [])
            if r.get("timeframe") == TIMEFRAME}


def _parse_iso(s):
    if not s:
        return None
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None


def cmd_freshness(save=False):
    """적재가 **거래소를 따라가고 있는가**를 보고, 72시간 연속 관찰을 누적한다.

    판정 기준 (2026-10-01 정정 — 사전 기준 §적재 지속성):
      🔴  캐시에 없음 · `to` 없음 · **거래소 최신 봉보다 DB 가 뒤처짐**(70초 뒤 재확인에서도)
      ⚪  거래소 조회 실패 — 판정 불가. 보지 못한 것을 통과로 셀 수 없으므로 연속 구간을 끊는다
      🟢  DB 최신 봉 = 거래소 최신 봉

    🔴 **벽시계 지연과 이동량은 판정에서 뺐다.** 종전 기준(경과 시간만큼 최신 봉이 이동해야
       한다)은 거래가 없어 **봉이 생기지 않은 시간**을 적재 정지로 오판했다. 09-28~09-30 의
       🔴 16건이 전부 그 오판이었고, 같은 구간의 운영 로그에는 수집·저장 실패가 한 건도 없었다.
       지연·이동은 이제 참고 출력일 뿐이다.

    ⚠️ 느슨해지는 방향의 변경이므로 범위를 명시한다: **거래소에 봉이 있는데 우리가 없는 경우**는
       여전히 전부 잡는다. 빠지는 것은 거래소에도 없는 봉뿐이다.

    📌 각 회차의 (DB `to`, 거래소 `to`) 쌍을 `freshness_log.jsonl` 에 함께 남긴다 — 종전 기록은
       거래소 값이 없어 **사후 재판정이 불가능했다.** 같은 실수를 반복하지 않는다.

    연속 관찰: 🔴/⚪ 이 나오거나 직전 관측과 90분(MAX_GAP_MIN) 넘게 벌어지면 처음부터 다시 센다.
    """
    need_env()
    rows = _db_latest()
    if rows is None:
        print("x 캔들 현황을 읽지 못했다 — 관측 누락으로 처리한다.")
        _streak_reset("캔들 현황 조회 실패")
        return 1

    now = datetime.now(timezone.utc)

    # ── 1차: DB 와 거래소를 코인별로 맞춘다 ────────────────────────────────
    state = {}
    for c in COINS:
        pair = "KRW-" + c
        r = rows.get(pair)
        db_to = _parse_iso(r.get("to")) if r else None
        ex_to, ex_err = _upbit_latest_h1(pair)
        state[pair] = {"row": r, "dbTo": db_to, "cnt": (r or {}).get("count"),
                       "exTo": ex_to, "exErr": ex_err}
        time.sleep(0.12)          # 공개 API 한도(초당 10회) 여유

    behind = [p for p, s in state.items()
              if (s["dbTo"] and s["exTo"] and s["exTo"] > s["dbTo"])
              or s["exErr"] == "net"]

    # ── 2차: 뒤처진 코인만 70초 뒤 재확인 ──────────────────────────────────
    #    동기화는 60초 주기다. 방금 생긴 봉이 아직 안 들어왔을 뿐이면 여기서 따라붙고,
    #    **진짜 지연은 재확인에서도 살아남는다.**
    rechecked = []
    if behind:
        print("· 거래소보다 뒤처진 %d종 — 70초 뒤 재확인한다: %s\n"
              % (len(behind), " ".join(behind)))
        time.sleep(70)
        again = _db_latest()
        for pair in behind:
            r2 = (again or {}).get(pair)
            if r2:
                state[pair]["row"] = r2
                state[pair]["cnt"] = r2.get("count")
                state[pair]["dbTo"] = _parse_iso(r2.get("to"))
            ex2, err2 = _upbit_latest_h1(pair)
            state[pair]["exErr"] = err2
            if ex2:
                state[pair]["exTo"] = ex2
            rechecked.append(pair)
            time.sleep(0.12)
        now = datetime.now(timezone.utc)

    # ── 판정 ───────────────────────────────────────────────────────────────
    print("%-12s %-22s %-22s %6s %s"
          % ("코인", "DB 최신 봉", "거래소 최신 봉", "봉수", "판정"))
    print("-" * 92)
    red, unknown, ok = [], [], []
    snap, logrows = {}, {}
    for c in COINS:
        pair = "KRW-" + c
        s = state[pair]
        db_to, ex_to = s["dbTo"], s["exTo"]
        snap[pair] = {"to": s["row"].get("to") if s["row"] else None, "count": s["cnt"]}
        logrows[pair] = {"dbTo": db_to.isoformat() if db_to else None,
                         "exTo": ex_to.isoformat() if ex_to else None,
                         "exErr": s["exErr"], "count": s["cnt"],
                         "rechecked": pair in rechecked}
        if s["row"] is None:
            verdict = "🔴 캐시에 없다"
            red.append(pair)
        elif db_to is None:
            verdict = "🔴 to 없음"
            red.append(pair)
        elif ex_to is None and s["exErr"] == "404":
            verdict = "🔴 거래소에 마켓이 없다 (상장폐지 의심)"
            red.append(pair)
        elif ex_to is None:
            # 거래소를 못 읽었으면 뒤처졌는지 알 수 없다 — 🟢 로 세지 않는다.
            verdict = "⚪ 거래소 조회 실패 — 판정 불가"
            unknown.append(pair)
        elif ex_to > db_to:
            gap_h = (ex_to - db_to).total_seconds() / 3600.0
            verdict = "🔴 거래소보다 %.0f봉 뒤처짐%s" % (
                gap_h, " (재확인 후)" if pair in rechecked else "")
            red.append(pair)
        else:
            lag = (now - db_to).total_seconds() / 60.0
            verdict = "🟢 거래소와 일치 (벽시계 지연 %.0f분, 참고)" % lag
            ok.append(pair)
        print("%-12s %-22s %-22s %6s %s"
              % (pair,
                 db_to.strftime("%Y-%m-%dT%H:%M:%SZ") if db_to else "-",
                 ex_to.strftime("%Y-%m-%dT%H:%M:%SZ") if ex_to else "-",
                 s["cnt"] if s["cnt"] is not None else "-", verdict))

    print("-" * 92)
    print("🟢 일치 %d / ⚪ 판정불가 %d / 🔴 %d" % (len(ok), len(unknown), len(red)))

    entry = {"at": now.isoformat(), "green": len(ok), "unknown": len(unknown),
             "red": len(red), "redCoins": red, "unknownCoins": unknown,
             "rechecked": rechecked, "rows": logrows}
    with open(FRESH_LOG, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(entry, ensure_ascii=False) + "\n")

    if unknown and not red:
        _streak_reset("⚪ 거래소 조회 실패 %d종: %s" % (len(unknown), " ".join(unknown[:6])))
    else:
        _streak_update(now, red)

    if red:
        print("")
        print("🔴 **🔴 은 경보이고 확정이 아니다.** 거래소 조회 실패·시각 처리 오류로도 나올 수 있다.")
        print("   확정 전에 확인한다: ① 그 코인의 1분봉으로 해당 봉이 거래소에 실제로 있었는가")
        print("   ② logs/backend/system.log·trade.log 의 같은 시각 수집·저장 기록")
        print("   ③ 같은 회차 다른 코인의 판정 — 2026-09-28~30 의 🔴 16건은 전부 오판이었다")
    print("\n🔴 이 도구가 말하지 않는 것: 최신 봉 시각이 같아도 **봉 안의 값 갱신**"
          "(형성 중 봉의 close·volume)까지 보지는 않는다.")
    print("🔴 72시간은 **운영 준비 점검 기준**이다 — 향후 무중단을 보장하는 기준이 아니다.")

    if save:
        with open(FRESH, "w", encoding="utf-8") as fh:
            json.dump({"at": now.isoformat(), "rows": snap}, fh, ensure_ascii=False, indent=1)
        print("\n기준 스냅샷 갱신: %s (%d종) — 관측 기록은 %s 에 누적된다"
              % (os.path.basename(FRESH), len(snap), os.path.basename(FRESH_LOG)))
    return 0 if not (red or unknown) else 1


ALERTS_MARK = os.path.join(_HERE, "alerts_last_check.json")


def _alerts_cutoff(now, since, days):
    """감시 구간의 시작점 — 기본은 **지난 확인 시점**이다."""
    if since:
        return datetime.fromisoformat(since.replace("Z", "+00:00")), "지정 시각"
    if days:
        return now - timedelta(days=days), "최근 %d일" % days
    if os.path.exists(ALERTS_MARK):
        try:
            return (datetime.fromisoformat(
                json.load(open(ALERTS_MARK, encoding="utf-8"))["checkedAt"]),
                "지난 확인 이후")
        except (ValueError, KeyError):
            return now - timedelta(days=1), "기준 파일 손상 — 최근 1일"
    return now - timedelta(days=1), "기준 기록 없음 — 최근 1일"


def _alerts_mark(now):
    with open(ALERTS_MARK, "w", encoding="utf-8") as fh:
        json.dump({"checkedAt": now.isoformat()}, fh, ensure_ascii=False, indent=1)


def _alerts_report(cutoff, now, why):
    """구간 안의 🔴·⚪·**실행 누락**을 줄 목록으로 돌려준다.

    🔴 왜 `tail` 로는 안 되는가: 마지막 줄 몇 개는 **현재 상태**만 보여준다. 중간에 난 🔴 이
    뒤의 정상 출력에 가려져 하루치 감시가 되지 않는다.
    ⚠️ 실행 누락은 구간 **직전** 관측부터 이어 계산한다 — 그러지 않으면 경계의 공백을 놓친다.
    마지막 관측부터 지금까지의 공백도 본다(cron 이 멈춘 경우).

    @return (줄 목록, 이상 요약 목록, 구간 내 관측 수)
    """
    if not os.path.exists(FRESH_LOG):
        return ["관측 기록이 없다 (%s)" % os.path.basename(FRESH_LOG)], ["기록 없음"], 0

    rows = []
    for line in open(FRESH_LOG, encoding="utf-8"):
        try:
            r = json.loads(line)
            r["_at"] = datetime.fromisoformat(r["at"])
        except (ValueError, KeyError):
            continue
        rows.append(r)
    rows.sort(key=lambda r: r["_at"])
    inwin = [r for r in rows if r["_at"] >= cutoff]

    red = [r for r in inwin if r.get("red")]
    unknown = [r for r in inwin if r.get("unknown")]

    prev = [r for r in rows if r["_at"] < cutoff]
    chain = ([prev[-1]] if prev else []) + inwin
    gaps = []
    for a, b in zip(chain, chain[1:]):
        g = (b["_at"] - a["_at"]).total_seconds() / 60.0
        if g > MAX_GAP_MIN:
            gaps.append((a["at"], b["at"], g))
    if chain:
        tail = (now - chain[-1]["_at"]).total_seconds() / 60.0
        if tail > MAX_GAP_MIN:
            gaps.append((chain[-1]["at"], "(지금)", tail))

    def short(t):
        return t[5:16].replace("T", " ") if len(t) > 16 else t

    lines, bad = [], []
    lines.append("관측 %d회 (구간 %s)" % (len(inwin), why))
    if red:
        bad.append("적재 뒤처짐 %d회" % len(red))
        for r in red:
            lines.append("🔴 %s  %d종: %s"
                         % (short(r["at"]), r["red"], " ".join(r.get("redCoins") or [])))
    if unknown:
        bad.append("판정 불가 %d회" % len(unknown))
        for r in unknown:
            lines.append("⚪ %s  %d종: %s"
                         % (short(r["at"]), r["unknown"], " ".join(r.get("unknownCoins") or [])))
    if gaps:
        bad.append("실행 누락 %d구간" % len(gaps))
        for a, b, g in gaps:
            lines.append("🔴 실행 누락 %s → %s  %.0f분" % (short(a), short(b), g))
    if rows:
        last = rows[-1]
        lines.append("현재 %s: 🟢 %s / ⚪ %s / 🔴 %s"
                     % (short(last["at"]), last.get("green"),
                        last.get("unknown"), last.get("red")))
    return lines, bad, len(inwin)


def _session_report():
    """검증 66세션이 전부 RUNNING 인가 — 하나라도 빠지면 그 코인의 팔 하나가 사라진다."""
    if not os.path.exists(STATE):
        return "⚪ %s 가 없다 — 검증 세션 목록을 모른다" % os.path.basename(STATE), True
    own = set(str(x.get("id")) for x in
              json.load(open(STATE, encoding="utf-8")).get("sessions", []))
    st, body = call("GET", "/api/v1/paper-trading/sessions")
    if st != 200:
        return "⚪ 세션 조회 실패 (HTTP %s)" % st, True
    running = set(str(sid_of(s)) for s in (body.get("data") or [])
                  if s.get("status") == "RUNNING")
    mine = own & running
    if len(mine) == len(own):
        return "🟢 검증 %d/%d RUNNING (전체 RUNNING %d)" % (len(mine), len(own), len(running)), False
    missing = sorted(own - running, key=int)
    return ("🔴 검증 %d/%d RUNNING — 빠진 세션: %s"
            % (len(mine), len(own), " ".join(missing))), True


def cmd_alerts(since=None, days=None, mark=True):
    """지난 확인 이후의 **모든 경보와 실행 누락**을 훑는다 — 적재 감시 전용."""
    now = datetime.now(timezone.utc)
    cutoff, why = _alerts_cutoff(now, since, days)
    print("구간 %s ~ %s  (%s)" % (cutoff.isoformat(), now.isoformat(), why))
    lines, bad, n = _alerts_report(cutoff, now, why)
    for l in lines:
        print(l)
    if n == 0:
        print("⚪ 이 구간에 관측이 없다 — 적재를 **확인하지 않았다**")
    print("")
    print("🟢 경보 없음 · 실행 누락 없음" if not bad else "🔴 " + " / ".join(bad))
    print("")
    print("🔴 🔴 은 경보이고 확정이 아니다 — 분봉·같은 시각 수집/저장 로그·같은 회차 다른")
    print("   코인의 판정을 확인한 뒤 판단한다 (2026-09-28~30 의 🔴 16건은 전부 오판이었다).")
    if mark:
        _alerts_mark(now)
        print("확인 시점 기록: %s" % os.path.basename(ALERTS_MARK))
    return 0 if not bad else 1


NL = chr(10)
LOG_DIR = os.path.abspath(os.path.join(_HERE, "..", "..", "logs", "backend"))


def _telegram(text):
    """감시 결과를 텔레그램으로 보낸다 (2026-10-01 신설).

    🔴 왜 앱을 거치지 않는가: 앱에는 임의 문자열을 보내는 엔드포인트가 없다
    (`/telegram/test` 는 고정 문구다). 추가하면 재배포가 필요하므로, 검증 중에는
    스크립트가 Bot API 를 직접 호출한다 — 운영 컨테이너를 건드리지 않는다.

    토큰은 `.env` 에서 읽고 **출력하지 않는다.**
    """
    tok = os.environ.get("TELEGRAM_BOT_TOKEN") or from_dotenv("TELEGRAM_BOT_TOKEN")
    chat = os.environ.get("TELEGRAM_CHAT_ID") or from_dotenv("TELEGRAM_CHAT_ID")
    if not tok or not chat:
        print("x 텔레그램 설정이 없다 (.env 의 TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID).")
        return False
    body = urllib.parse.urlencode({
        "chat_id": chat,
        "text": text[:4000],          # Bot API 한도 4096 — 여유를 둔다
        "disable_web_page_preview": "true",
    }).encode("utf-8")
    url = "https://api.telegram.org/bot%s/sendMessage" % tok
    try:
        req = urllib.request.Request(url, data=body, method="POST")
        with urllib.request.urlopen(req, timeout=15) as r:
            ok = json.loads(r.read().decode("utf-8")).get("ok") is True
        print("텔레그램 전송 %s" % ("성공" if ok else "실패"))
        return ok
    except Exception as e:
        # 🔴 토큰이 메시지에 섞일 수 있으므로 예외 문자열에서 가린다.
        print("x 텔레그램 전송 실패: %s" % str(e).replace(tok, "<token>"))
        return False


# 🔴 이미 아는 누수의 주체 — 2026-10-01 실측으로 확인됐다. 이것만으로는 장애가 아니다.
KNOWN_LEAKERS = ("SignalQualityService",)


def _log_scan(cutoff):
    """호스트에 보존된 운영 로그에서 **그 시각 이후** 오류·경보를 센다.

    🔴 적재 감시로는 이것을 볼 수 없다 — 적재가 정상이어도 주문이 실패하거나 세션이
    죽으면 표본이 조용히 사라진다. 로그는 2026-10-01 볼륨 마운트 이후 호스트에 남는다.
    ⚠️ 컨테이너 로그 시각은 UTC 다(`StartedAt` 과 일치하는 것을 확인했다).

    🔴 **커넥션 누수는 주체로 분류한다.** 경보 뒤에 붙는 스택에서 대여 스레드를 읽어
    `SignalQualityService` 면 **이미 아는 것**(📌 기록만)이고, 그 밖이면 **🔴 새로운 주체**다.
    왜 이렇게 나누는가: 전자는 매 주기(30분)마다 반복되므로 그것으로 "확인 필요"를 띄우면
    **매일 경보가 와서 신호가 죽는다**. 그리고 후자는 2026-09-26 장기 잠금(①)의 원인을
    확인할 **유일하게 남은 경로**다 — 그때의 로그는 보존되지 않아 사라졌다.

    @return (패턴별 건수, 읽지 못한 파일, 누수 분류)
    """
    pats = {
        "lock timeout": "lock timeout",
        "deadlock": "deadlock",
        "동기화 실패": "시장 데이터 동기화 실패",
        "KILL 경보": "→ KILL(",
        "비상 정지": "EMERGENCY_STOPPED",
        "주문 실패": "주문 실패",
    }
    hits = dict((k, 0) for k in pats)
    leak = {"known": 0, "unknown": 0, "who": []}
    files, missing = [], []
    for name in ("system.log", "trade.log"):
        f = os.path.join(LOG_DIR, name)
        (files if os.path.exists(f) else missing).append(f if os.path.exists(f) else name)

    for f in files:
        try:
            with open(f, encoding="utf-8", errors="replace") as fh:
                pending = None          # 창 안에서 누수 경보를 만났으면 스택을 모은다
                for line in fh:
                    stamped = False
                    t = None
                    if len(line) >= 19:
                        try:
                            t = datetime.strptime(line[:19], "%Y-%m-%d %H:%M:%S").replace(
                                tzinfo=timezone.utc)
                            stamped = True
                        except ValueError:
                            pass
                    # 🔴 스택은 **시각이 붙은 다음 줄**에서 끝난다. 예외 머리줄
                    #    (java.lang.Exception: …) 은 공백으로 시작하지 않으므로 그것으로
                    #    끊으면 스택을 한 줄도 못 모은다 — 실제로 그렇게 틀렸다.
                    if pending is not None and stamped:
                        _leak_close(pending, leak)
                        pending = None
                    if not stamped:
                        if pending is not None and len(pending["stack"]) < 200:
                            pending["stack"].append(line)
                        continue
                    if t < cutoff:
                        continue
                    if "leak detection" in line or "Apparent connection leak" in line:
                        pending = {"stack": [line]}
                        continue
                    for k, p in pats.items():
                        if p in line:
                            hits[k] += 1
                if pending is not None:
                    _leak_close(pending, leak)
        except OSError as e:
            missing.append("%s (%s)" % (os.path.basename(f), e))
    return hits, [os.path.basename(x) for x in missing], leak


def _leak_close(pending, leak):
    """모아둔 누수 스택에서 **대여한 우리 코드**를 찾아 분류한다."""
    blob = "".join(pending["stack"])
    if any(k in blob for k in KNOWN_LEAKERS):
        leak["known"] += 1
        return
    leak["unknown"] += 1
    # 🔴 누가 쥐고 있었는지를 남긴다 — 프레임워크 프레임은 걷어내고 우리 코드만 본다.
    for ln in pending["stack"]:
        if "com.cryptoautotrader" in ln:
            who = ln.strip()
            if who not in leak["who"]:
                leak["who"].append(who)
            break
    else:
        if "스택 없음" not in leak["who"]:
            leak["who"].append("스택 없음")


def cmd_watch(notify=False, only_alerts=False):
    """**하루 한 번 자동 감시** — 적재 + 세션 + 운영 로그를 한 번에 보고한다 (2026-10-01 신설).

    🔴 사람이 기억해서 돌리는 감시는 빠진다. cron 으로 돌리고 결과를 텔레그램으로 받는다.

    🔴 **정상일 때도 보낸다(기본).** 경보만 보내면 "조용한 것"과 "감시가 죽은 것"을 구별할
    수 없다 — 매일 오는 메시지가 곧 감시가 살아 있다는 증거다. `--only-alerts` 로 바꿀 수
    있지만 권하지 않는다.

    보는 것:
      ① 적재 — 지난 확인 이후의 🔴·⚪·실행 누락 (`alerts` 와 같은 판정)
      ② 세션 — 검증 66세션이 전부 RUNNING 인가
      ③ 운영 로그 — 잠금·누수·동기화 실패·주문 실패·KILL 경보·비상 정지 건수

    🔴 이 도구는 **중단하지 않는다.** 자동정지가 OFF 이므로 위험 경보가 떴을 때 실제 정지는
    사람이 사전 규칙에 따라 수행하고 시점·사유를 기록한다.
    """
    out = []

    def say(line=""):
        out.append(line)
        print(line)

    now = datetime.now(timezone.utc)
    cutoff, why = _alerts_cutoff(now, None, None)

    # ① 적재
    a_lines, a_bad, a_n = _alerts_report(cutoff, now, why)
    # 🔴 관측 0회를 "경보 없음" 으로 보고하면 **보지 않은 것을 통과로 세는 것**이다.
    #    (구간이 짧으면 매시간 기록이 하나도 안 들어올 수 있다. 적재가 멈춘 경우는
    #     '실행 누락' 이 따로 잡으므로, 여기서는 확인하지 않았다는 사실만 말한다.)
    if a_bad:
        say("[적재] 🔴 " + " / ".join(a_bad))
    elif a_n == 0:
        say("[적재] ⚪ 이 구간에 관측이 없다 — 확인하지 않았다 (실행 누락은 아래로 판정)")
    else:
        say("[적재] 🟢 경보 없음 (관측 %d회)" % a_n)
    for l in a_lines:
        say("   " + l)

    # ② 세션
    s_line, s_bad = _session_report()
    say("[세션] " + s_line)

    # ③ 운영 로그
    hits, missing, leak = _log_scan(cutoff)
    nonzero = [(k, v) for k, v in hits.items() if v]
    # 🔴 system.log 를 못 읽으면 운영 오류를 **볼 수 없다** — 정상으로 세지 않는다.
    blind = any(x.startswith("system.log") for x in missing)
    if missing:
        say("[로그] %s 읽지 못한 파일: %s"
            % ("🔴" if blind else "⚪", " ".join(missing)))
    say("[로그] " + ("🔴 " + " / ".join("%s %d" % kv for kv in nonzero)
                    if nonzero else "🟢 오류·경보 없음"))
    if leak["known"]:
        say("[누수] 📌 %d건 — SignalQualityService (이미 파악된 것, 검증 후 과제)"
            % leak["known"])
    if leak["unknown"]:
        say("[누수] 🔴 %d건 — **새로운 주체**: %s"
            % (leak["unknown"], " | ".join(leak["who"][:3])))
        say("       🔴 2026-09-26 장기 잠금(①)의 원인을 확인할 경로다. 그 시각 로그를 보존한다.")

    bad = bool(a_bad) or s_bad or bool(nonzero) or blind or bool(leak["unknown"])
    say()
    say("구간 %s ~ %s (%s)" % (cutoff.strftime("%m-%d %H:%MZ"),
                               now.strftime("%m-%d %H:%MZ"), why))
    if bad:
        say("🔴 확인이 필요하다. 🔴 적재 경보는 **확정이 아니다** — 분봉·같은 시각 수집/저장")
        say("   로그·같은 회차 다른 코인을 본 뒤 판단한다.")
        say("🔴 KILL 경보가 있으면 자동정지가 OFF 이므로 **사람이 정지**시키고 시점·사유를 남긴다.")

    _alerts_mark(now)

    if notify and not (only_alerts and not bad):
        head = ("🔴 운영 감시 — 확인 필요" if bad else "🟢 운영 감시 — 정상")
        _telegram(head + NL + NL + NL.join(out))
    return 1 if bad else 0


def _runs_since(started_iso):
    """연속 구간 **안의** 관측 회차만 센다 (2026-10-01).

    🔴 종전에는 `freshness_log.jsonl` 의 전체 줄 수를 셌다. 그 파일은 append 전용이라
    정정 이전 기준으로 쌓인 회차까지 포함되므로, 72시간을 판정하는 자리에서 "관측 N회" 가
    연속 구간의 증거가 되지 못했다(정정 직후 0.0h 인데 77회로 찍혔다).
    """
    if not os.path.exists(FRESH_LOG):
        return 0
    try:
        t0 = datetime.fromisoformat(started_iso)
    except (TypeError, ValueError):
        return 0
    n = 0
    with open(FRESH_LOG, encoding="utf-8") as fh:
        for line in fh:
            try:
                at = datetime.fromisoformat(json.loads(line)["at"])
            except (ValueError, KeyError):
                continue
            if at >= t0:
                n += 1
    return n


def _streak_reset(why):
    """연속 구간을 끊는다 — 🔴 발생과 **관측 누락** 모두 여기로 온다."""
    with open(STREAK, "w", encoding="utf-8") as fh:
        json.dump({"startedAt": None, "lastAt": None, "brokenAt":
                   datetime.now(timezone.utc).isoformat(), "reason": why},
                  fh, ensure_ascii=False, indent=1)
    print("\n🔴 연속 관찰 구간을 초기화했다 — %s" % why)
    print("   원인 확인·복구 후 %d시간을 **처음부터** 다시 센다." % REQUIRED_HOURS)


def _streak_update(now, red):
    s = json.load(open(STREAK, encoding="utf-8")) if os.path.exists(STREAK) else {}
    prev = s.get("lastAt")
    # 🔴 연속 구간의 시작은 **22코인이 모두 준비된 첫 스냅샷**이다 (사용자 고정 기준).
    #    연속 기록이 아직 없으면 그 스냅샷 시각을 시작점으로 쓰고, 그 시각과 이번 관측 사이의
    #    간격도 누락 판정에 넣는다 — 시작점만 빌려오고 그 사이를 안 보면 같은 구멍이 남는다.
    # 🔴 단, **초기화된 직후에는 시작점을 빌려오지 않는다.** 빌려오면 낡은 스냅샷 시각과의
    #    간격이 늘 90분을 넘어 매 회차 다시 초기화되고, 연속 구간이 영원히 시작되지 않는다.
    #    복구 후 첫 정상 관측이 새 시작점이다.
    seed = None
    if not prev and not s.get("brokenAt") and os.path.exists(FRESH):
        try:
            seed = json.load(open(FRESH, encoding="utf-8")).get("at")
        except ValueError:
            seed = None
    if red:
        _streak_reset("🔴 %d종: %s" % (len(red), " ".join(red[:6])))
        return
    ref = prev or seed
    if ref:
        gap = (now - datetime.fromisoformat(ref)).total_seconds() / 60.0
        if gap > MAX_GAP_MIN:
            # 🔴 보지 않은 시간을 통과로 셀 수 없다. 그 구간에 정지가 있었는지 알 수 없다.
            _streak_reset("관측 누락 %.0f분 (허용 %d분) — 직전 관측 %s"
                          % (gap, MAX_GAP_MIN, ref))
            return
    started = s.get("startedAt") or seed or now.isoformat()
    with open(STREAK, "w", encoding="utf-8") as fh:
        json.dump({"startedAt": started, "lastAt": now.isoformat()},
                  fh, ensure_ascii=False, indent=1)
    held = (now - datetime.fromisoformat(started)).total_seconds() / 3600.0
    n = _runs_since(started)
    bar = int(min(held / REQUIRED_HOURS, 1.0) * 30)
    print("\n연속 관찰 %5.1fh / %dh  [%s%s]  관측 %d회 (시작 %s)"
          % (held, REQUIRED_HOURS, "#" * bar, "." * (30 - bar), n, started))
    if held >= REQUIRED_HOURS:
        print("🟢 **72시간 연속 관측에서 🔴 0건** — 준비 점검 기준을 충족했다.")
        print("   다음: `prep stop` → 새 T0 직전 `probe` 로 실제 조회 조건·최신성을 다시 확인 →")
        print("   `create --after <epoch>` → `verify`")
    else:
        print("   남은 시간 %.1fh. 매시간 실행해야 한다 — 시작·종료만 보면 중간 정지를 놓친다."
              % (REQUIRED_HOURS - held))

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
    if c == "lockprobe":
        a = sys.argv[2] if len(sys.argv) > 2 else "start"
        if a not in ("start", "stop"):
            print("lockprobe start | lockprobe stop")
            return 2
        return cmd_lockprobe(a)
    if c == "prep":
        a = sys.argv[2] if len(sys.argv) > 2 else "start"
        if a not in ("start", "stop"):
            print("prep start | prep stop")
            return 2
        return cmd_prep(a)
    if c == "freshness":
        return cmd_freshness("--save" in sys.argv)
    if c == "watch":
        return cmd_watch("--notify" in sys.argv, "--only-alerts" in sys.argv)
    if c == "alerts":
        sv = sys.argv[sys.argv.index("--since") + 1] if "--since" in sys.argv else None
        dv = int(sys.argv[sys.argv.index("--days") + 1]) if "--days" in sys.argv else None
        return cmd_alerts(sv, dv, "--no-mark" not in sys.argv)
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
