#!/usr/bin/env bash
#
# candle_data 백필 (2026-08-24)
# ─────────────────────────────────────────────────────────────────────────────
# 발견: WF 재검증 배치(48조합)를 돌렸더니 25개 조합이 조용히 누락됐다. 원인 —
#   candle_data 테이블이 **전 코인·전 타임프레임에서 2026-03-30을 마지막으로 5개월째
#   갱신이 멈춰 있었다**. DataCollectionService 는 스케줄러 없이 수동 트리거
#   (POST /api/v1/data/collect/batch)만 있어서, 마지막으로 누가 돌린 뒤로 그대로 방치됐다.
#
#   게다가 LINK·AVAX·PROM·EUL(고정코인 페이퍼 함대가 실제로 쓰는 코인)과
#   ONDO·GRVT·ENA·WLD(PULLBACK_MTF 가 실제로 거래한 코인)는 기존 22종 수집 목록에
#   애초에 포함된 적이 없다 — 운영 중인 코인 구성과 백테스트 데이터 저장소가 따로 논다.
#
# 이 스크립트가 하는 일:
#   1. 기존 22코인 — 마지막 수집일(2026-03-30) 근처부터 오늘까지 갭 채우기
#   2. 신규 8코인(LINK·AVAX·PROM·EUL·ONDO·GRVT·ENA·WLD) — 2022-01-01부터 오늘까지 전체 수집
#   둘 다 H1. saveAll 이 PK(time,coin_pair,timeframe) 기준 upsert 라 겹치는 구간을
#   다시 수집해도 안전하다(중복/에러 없음).
#
# 사용법: 운영 서버에서 (SSH 접속 후, 리포 루트에서) 실행
#   bash scripts/backfill_candle_data.sh
#
# 완료되면 코인마다 텔레그램으로 결과가 온다(collectBatch 가 비동기라 즉시 안 끝남).
# 8+22=30개 코인 × 최대 4.5년치 H1 캔들이라 시간이 꽤 걸릴 수 있다(Upbit 레이트리밋 버퍼 2초/코인).

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

# ── 1. 기존 22코인 갭 채우기 (2026-03-25 ~ 오늘, 며칠 겹쳐서 안전하게) ──────────
EXISTING_COINS='["KRW-ADA","KRW-AXL","KRW-BIO","KRW-BLUR","KRW-BTC","KRW-CFG","KRW-CHZ",
"KRW-CPOOL","KRW-DOGE","KRW-ETH","KRW-FLOCK","KRW-IP","KRW-KAT","KRW-MET2","KRW-NEWT",
"KRW-RED","KRW-SENT","KRW-SOL","KRW-SUPER","KRW-USDT","KRW-XLM","KRW-XRP"]'

echo
echo "▶ 1단계: 기존 22코인 갭 채우기 (2026-03-25 ~ $TODAY, H1)"
resp1=$(api -X POST "$API/data/collect/batch" \
  -H 'Content-Type: application/json' \
  -d "{\"coinPairs\": $EXISTING_COINS, \"timeframe\": \"H1\", \"startDate\": \"2026-03-25\", \"endDate\": \"$TODAY\"}")
echo "$resp1"

# ── 2. 신규 8코인 전체 수집 (2022-01-01 ~ 오늘) ──────────────────────────────
NEW_COINS='["KRW-LINK","KRW-AVAX","KRW-PROM","KRW-EUL","KRW-ONDO","KRW-GRVT","KRW-ENA","KRW-WLD"]'

echo
echo "▶ 2단계: 신규 8코인 전체 수집 (2022-01-01 ~ $TODAY, H1)"
resp2=$(api -X POST "$API/data/collect/batch" \
  -H 'Content-Type: application/json' \
  -d "{\"coinPairs\": $NEW_COINS, \"timeframe\": \"H1\", \"startDate\": \"2022-01-01\", \"endDate\": \"$TODAY\"}")
echo "$resp2"

echo
echo "▶ 두 배치 모두 백그라운드 실행 중. 진행 상황: GET $API/data/summary"
echo "▶ 완료되면 텔레그램 알림. 완료 후 scripts/rerun_walk_forward_active_strategies.sh 를"
echo "  다시 돌리면 이번엔 LINK/AVAX/PROM/EUL/ONDO/GRVT/ENA/WLD 도 포함해서 재검증할 수 있다."
