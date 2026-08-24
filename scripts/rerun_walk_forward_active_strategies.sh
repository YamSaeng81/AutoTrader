#!/usr/bin/env bash
#
# 활성 전략 6종 Walk Forward 재검증 (2026-08-24)
# ─────────────────────────────────────────────────────────────────────────────
# 배경: WalkForwardValidationGate 판정 결과 현재 RUNNING 중인 6개 전략이 전부 FAIL —
#   3개는 verdict=OVERFITTING(2026-04-24~08-20 실행분, 낡음), 1개는 OOS 기대값<=0,
#   2개(MTF_CONFIRMED·PULLBACK_MTF)는 WF 실행 이력 자체가 없다.
#
#   반면 08-24 운영 로그 분석은 PULLBACK_MTF 를 빼면 나머지 6전략 합산 페이퍼 손익이
#   흑자(+819,443원, n=191, 승률 25.7%)다 — 백테스트(WF) 판정과 실측(페이퍼 운영)이
#   엇갈린다. 최신 데이터로 WF 를 다시 돌려 이 모순을 정리한다.
#
#   PULLBACK_MTF 는 고정 코인이 아니라 감시목록 기반 동적 스캔 전략이라 "대표 코인"이
#   따로 없다 — 실제 DYN_PAPER 포지션에서 가장 많이 거래된 상위 8종으로 대신 검증한다.
#
# 사용법: 운영 서버에서 (SSH 접속 후, 리포 루트에서) 실행
#   bash scripts/rerun_walk_forward_active_strategies.sh
#
# 완료되면 텔레그램으로 알림이 온다(배치가 백그라운드 실행이라 즉시 끝나지 않음).
# 완료 후 GET /api/v1/strategies/walk-forward-gate-status 로 새 판정을 확인할 것.

set -uo pipefail

API="http://localhost:8080/api/v1"

if [ -z "${API_AUTH_TOKEN:-}" ] && [ -f .env ]; then
  API_AUTH_TOKEN=$(grep -E '^API_AUTH_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"'"'"'')
fi
if [ -z "${API_AUTH_TOKEN:-}" ]; then
  echo "✗ API_AUTH_TOKEN 을 찾을 수 없습니다."
  exit 1
fi
AUTH="Authorization: Bearer $API_AUTH_TOKEN"
api() { curl -s -H "$AUTH" "$@"; }

sessions=$(api "$API/paper-trading/sessions")
case "$sessions" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요"; exit 1 ;;
esac
echo "✓ 인증 확인"

TODAY=$(date -u +%Y-%m-%d)

# ── 1. 고정코인 5전략 × 운영 중인 페어링 8코인 (§23 A/B 대조군과 동일 세트) ──
echo
echo "▶ Job 1: 고정코인 5전략 × 8코인 (SOL·BTC·DOGE·LINK·ADA·AVAX·PROM·EUL) — 40조합"
resp1=$(api -X POST "$API/backtest/walk-forward-batch-async" \
  -H 'Content-Type: application/json' \
  -d "{
    \"coinPairs\": [\"KRW-SOL\",\"KRW-BTC\",\"KRW-DOGE\",\"KRW-LINK\",\"KRW-ADA\",\"KRW-AVAX\",\"KRW-PROM\",\"KRW-EUL\"],
    \"strategyTypes\": [\"COMPOSITE_MEANREV_BB\",\"COMPOSITE_MOMENTUM_ICHIMOKU\",\"COMPOSITE_MOMENTUM_ICHIMOKU_V2\",\"COMPOSITE_MTF_BTC\",\"COMPOSITE_MTF_CONFIRMED\"],
    \"timeframe\": \"H1\",
    \"startDate\": \"2022-01-01\",
    \"endDate\": \"$TODAY\",
    \"inSampleRatio\": 0.7,
    \"windowCount\": 5
  }")
echo "$resp1"

# ── 2. PULLBACK_MTF × 실거래 상위 8코인 (고정 페어링이 없는 동적 스캔 전략) ──
echo
echo "▶ Job 2: COMPOSITE_PULLBACK_MTF × 실거래 상위 8코인 — 8조합"
resp2=$(api -X POST "$API/backtest/walk-forward-batch-async" \
  -H 'Content-Type: application/json' \
  -d "{
    \"coinPairs\": [\"KRW-SOL\",\"KRW-XLM\",\"KRW-BTC\",\"KRW-ONDO\",\"KRW-GRVT\",\"KRW-ETH\",\"KRW-ENA\",\"KRW-WLD\"],
    \"strategyTypes\": [\"COMPOSITE_PULLBACK_MTF\"],
    \"timeframe\": \"H1\",
    \"startDate\": \"2022-01-01\",
    \"endDate\": \"$TODAY\",
    \"inSampleRatio\": 0.7,
    \"windowCount\": 5
  }")
echo "$resp2"

echo
echo "▶ 두 job 모두 백그라운드에서 실행 중. 진행 상황: GET $API/backtest/jobs"
echo "▶ 완료 후 판정 재확인: GET $API/strategies/walk-forward-gate-status"
