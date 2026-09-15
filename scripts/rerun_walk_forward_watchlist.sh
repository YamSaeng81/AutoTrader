#!/usr/bin/env bash
#
# 실제 DYNAMIC 감시목록 커버리지 WF 재검증 (2026-08-24)
# ─────────────────────────────────────────────────────────────────────────────
# scripts/backfill_watchlist_coins.sh 로 데이터를 채운 뒤 실행한다.
#
# 현재 RUNNING 6전략 × 실제 감시목록 합집합 18코인 = 108조합. 목적: DYNAMIC 을
# 메인으로 쓰기 전에, processScanningTick 이 실제로 마주칠 수 있는 코인 전부에 대해
# 각 전략의 검증 커버리지를 최대한 넓혀둔다 — 지금은 6전략 중 5개가 코인 1개씩만
# 검증된 상태라, 스캔 루프에 코인 단위 게이트를 걸면 사실상 단일 코인 매매로
# 붕괴한다(§ DYNAMIC 운영 가능성 논의, 2026-08-24).
#
# 108조합이라 이전 배치(40+8=48)보다 훨씬 오래 걸린다 — 조합당 ~1.2분 기준 2시간 안팎.
#
# 사용법: 운영 서버에서 (SSH 접속 후, 리포 루트에서) 실행
#   bash scripts/rerun_walk_forward_watchlist.sh

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

WATCHLIST_COINS='["KRW-BTC","KRW-ETH","KRW-SOL","KRW-XRP","KRW-XLM","KRW-ONDO","KRW-SUI","KRW-ADA",
"KRW-WLD","KRW-GRVT","KRW-ENA","KRW-RE","KRW-TRUMP","KRW-FOLD","KRW-TRAC","KRW-STX","KRW-WLFI","KRW-PUMP"]'

STRATEGIES='["COMPOSITE_MEANREV_BB","COMPOSITE_MOMENTUM_ICHIMOKU","COMPOSITE_MOMENTUM_ICHIMOKU_V2",
"COMPOSITE_MTF_BTC","COMPOSITE_MTF_CONFIRMED","COMPOSITE_PULLBACK_MTF"]'

echo
echo "▶ 6전략 × 18코인 = 108조합 WF 재검증 (2022-01-01 ~ $TODAY, H1)"
resp=$(api -X POST "$API/backtest/walk-forward-batch-async" \
  -H 'Content-Type: application/json' \
  -d "{
    \"coinPairs\": $WATCHLIST_COINS,
    \"strategyTypes\": $STRATEGIES,
    \"timeframe\": \"H1\",
    \"startDate\": \"2022-01-01\",
    \"endDate\": \"$TODAY\",
    \"inSampleRatio\": 0.7,
    \"windowCount\": 5
  }")
echo "$resp"

echo
echo "▶ 백그라운드 실행 중(예상 2시간 안팎). 진행 상황: GET $API/backtest/jobs"
echo "▶ 완료되면 텔레그램 알림. 완료 후 GET $API/strategies/walk-forward-gate-status 로 확인 가능."
