#!/usr/bin/env bash
#
# 실제 DYNAMIC 감시목록 커버리지 백필 (2026-08-24)
# ─────────────────────────────────────────────────────────────────────────────
# 배경: DYNAMIC을 메인으로 쓰려면, 세션이 실제로 매수할 수 있는 코인 전부에 대해
#   해당 전략이 검증됐는지 알아야 한다(WalkForwardValidationGate 를 스캔 루프에
#   연결하기 전 선행 작업 — processScanningTick 은 워치리스트 코인마다 전략을 그대로
#   적용하는데, 지금 검증된 조합은 6전략 중 5개가 코인 1개씩뿐이었다).
#
#   현재 RUNNING 중인 16개 DYNAMIC 세션의 실제 watchlist_json 을 모아보니 18개 고유
#   코인으로 좁혀진다(거래대금 상위 30 → ATR/스프레드 필터 통과 상위 10, 세션마다 소폭
#   다름). 이게 "감시목록 전체"보다 훨씬 현실적인 검증 대상이다 — 실제로 마주치는 코인들.
#
#   이 중 10개(BTC·ETH·SOL·XRP·ADA·XLM·ONDO·ENA·GRVT·WLD)는 이미 candle_data 에 있고,
#   8개(SUI·RE·TRUMP·FOLD·TRAC·STX·WLFI·PUMP)는 전혀 없다 — 백필한다.
#   XLM 은 H1 이 2026-03-24 부터만 있어서(M15 는 2021년부터 있는데 비대칭) 전체 재수집한다.
#
# 사용법: 운영 서버에서 (SSH 접속 후, 리포 루트에서) 실행
#   bash scripts/backfill_watchlist_coins.sh
#
# 완료되면 텔레그램 알림. 완료 후 scripts/rerun_walk_forward_watchlist.sh 로 WF 재검증.

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

NEW_COINS='["KRW-SUI","KRW-RE","KRW-TRUMP","KRW-FOLD","KRW-TRAC","KRW-STX","KRW-WLFI","KRW-PUMP","KRW-XLM"]'

echo
echo "▶ 감시목록 신규 8코인 + XLM 재수집 (2022-01-01 ~ $TODAY, H1)"
resp=$(api -X POST "$API/data/collect/batch" \
  -H 'Content-Type: application/json' \
  -d "{\"coinPairs\": $NEW_COINS, \"timeframe\": \"H1\", \"startDate\": \"2022-01-01\", \"endDate\": \"$TODAY\"}")
echo "$resp"

echo
echo "▶ 백그라운드 실행 중. 진행 상황: GET $API/data/summary"
echo "▶ 완료되면 텔레그램 알림. 완료 후 scripts/rerun_walk_forward_watchlist.sh 실행할 것."
