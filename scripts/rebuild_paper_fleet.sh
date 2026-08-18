#!/usr/bin/env bash
#
# 실자본 전면 중단 + 페이퍼 9세션 재구성 (2026-08-18)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행할 것. 백엔드 API는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 무엇을 하나
#   1) 현재 RUNNING 10세션 전부 정지 (DYN 46~53, LIVE 198/199)
#   2) DYN_PAPER 9세션 신규 생성 — 사용 가능한 전략 7종 전부 × H1, + 활동 최다 2종 × M15
#
# ■ 왜 9개인가 (rate limit 계산)
#   fetchCandles 는 캐시 없이 Upbit REST 를 직접 호출한다. 세션마다 독립적으로.
#     CANDLE_LOOKBACK=500, 요청당 최대 200개        → 코인당 3 요청
#     SCANNING 세션 1개 = (워치리스트 10 + BTC가드 1) × 3 = 33 요청 / 60초 틱
#     UpbitApiRateLimiter.PERMITS_PER_SECOND = 7    → 420 요청/분
#   9세션 = 297/분 (71%, 60초 틱 중 42초). 12세션이면 94%로 틱을 넘길 위험이 있다.
#   ※ PAPER 도 이 예산을 똑같이 쓴다 — REAL 과 코드 경로를 100% 공유하기 때문.
#
# ■ 전략 선정
#   최근 60일 내 실제 가동 이력이 있는 11종 중, strategy_type_enabled 에서
#   비활성(07-07 일괄) 4종(BREAKOUT / MTF_MOMENTUM / REGIME_ROUTER / HEIKIN_ASHI_STOCH)을
#   제외한 7종. 비활성 4종은 그대로 둔다 — 부활 경로는 Walk Forward 재검증뿐이다
#   (docs/KILL_CRITERIA.md §6).
#
# ■ LIVE 198/199 대응
#   MEANREV_BB@H1 와 MTF_BTC_STRICT@H1 이 아래 목록에 포함되므로 별도 단일코인
#   PaperTradingService 세션을 만들지 않는다 — 그 경로는 07-01 이후 운영 가동 이력이
#   없어 9세션을 한꺼번에 올리기엔 검증이 부족하다.

set -uo pipefail
API="http://localhost:8080/api/v1"
CAPITAL=10000
MAX_HOLD=24

RUNNING_DYN="46 47 48 49 50 51 52 53"
RUNNING_LIVE="198 199"

# ── 0. PRECHECK ──────────────────────────────────────────────────────────────
echo "=== PRECHECK: 열린 포지션 (있으면 정지 시 시장가 청산됨) ==="
for id in $RUNNING_DYN; do
  echo -n "  DYN $id : "; curl -s "$API/dynamic-sessions/$id/positions" | head -c 160; echo
done
for id in $RUNNING_LIVE; do
  echo -n "  LIVE $id: "; curl -s "$API/trading/sessions/$id/positions" | head -c 160; echo
done
echo
read -p "포지션이 없으면 Enter, 있으면 Ctrl-C 후 알릴 것: "

# ── 1. 전량 정지 ─────────────────────────────────────────────────────────────
echo
echo "=== 1. 동적 세션 8종 정지 ==="
for id in $RUNNING_DYN; do
  echo -n "  stop DYN $id  -> "; curl -s -X POST "$API/dynamic-sessions/$id/stop" | head -c 200; echo
done

echo
echo "=== 2. LIVE 실자본 2종 정지 ==="
for id in $RUNNING_LIVE; do
  echo -n "  stop LIVE $id -> "; curl -s -X POST "$API/trading/sessions/$id/stop" | head -c 200; echo
done

# ── 2. 페이퍼 9세션 생성 + 시작 ──────────────────────────────────────────────
create_and_start() {
  local strategy="$1" tf="$2"
  local body resp id
  body=$(printf '{"strategyType":"%s","timeframe":"%s","initialCapital":%s,"maxHoldHours":%s,"tradingMode":"PAPER"}' \
         "$strategy" "$tf" "$CAPITAL" "$MAX_HOLD")

  resp=$(curl -s -X POST "$API/dynamic-sessions" -H 'Content-Type: application/json' -d "$body")
  id=$(echo "$resp" | grep -o '"id"[[:space:]]*:[[:space:]]*[0-9]*' | head -1 | grep -o '[0-9]*$')

  if [ -z "$id" ]; then
    echo "  ✗ $strategy@$tf 생성 실패: $(echo "$resp" | head -c 300)"
    return 1
  fi
  curl -s -X POST "$API/dynamic-sessions/$id/start" >/dev/null
  echo "  ✓ $strategy@$tf  -> id=$id"
}

echo
echo "=== 3. DYN_PAPER 9세션 생성 (7전략 × H1 + 2종 × M15) ==="
for s in COMPOSITE_MEANREV_BB \
         COMPOSITE_MOMENTUM_ICHIMOKU \
         COMPOSITE_MOMENTUM_ICHIMOKU_V2 \
         COMPOSITE_MTF_BTC \
         COMPOSITE_MTF_BTC_STRICT \
         COMPOSITE_MTF_CONFIRMED \
         COMPOSITE_PULLBACK_MTF ; do
  create_and_start "$s" H1
done

# 이력상 세션 생성이 가장 많았던 2종 — 신호 빈도가 높아 표본이 빨리 쌓인다
create_and_start COMPOSITE_PULLBACK_MTF  M15
create_and_start COMPOSITE_MTF_CONFIRMED M15

# ── 3. 확인 ──────────────────────────────────────────────────────────────────
echo
echo "=== 4. 결과 ==="
curl -s "$API/dynamic-sessions" | head -c 4000; echo
echo
echo "기대 상태:"
echo "  RUNNING = 신규 DYN_PAPER 9종 (전부 tradingMode=PAPER)"
echo "  STOPPED = DYN 46~53, LIVE 198/199"
echo "  실자본 노출 = 0"
echo
echo "주의: 신규 9세션이 전부 SCANNING 이면 297 요청/분(한도 420의 71%)이다."
echo "      세션을 더 늘리기 전에 CANDLE_LOOKBACK 축소 또는 공유 캔들 캐시가 필요하다."
