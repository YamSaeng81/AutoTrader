#!/usr/bin/env bash
#
# 손절폭 A/B 실험군 재기동 (2026-08-24 버그 수정 후)
# ─────────────────────────────────────────────────────────────────────────────
# 배경: PaperTradingService/LiveTradingService/DynamicTradingService 의
#   "전략 제안 SL이 더 넓으면 채택" min() 로직이 exitOverrides 를 가려, 실험군(290~329)이
#   대조군과 동일한 SL로 돌고 있었다(2026-08-24 실측: 두 arm SL거리 완전 동일 0.716%).
#   ExitRuleOverrides.isPresent() 일 때 min() 을 건너뛰도록 3곳 수정, 배포 완료.
#
# 대조군(194~248, strategy_params NULL)은 이 버그의 영향을 받지 않았다 — isPresent()가
# false 라 수정 전후 동작이 동일하다. 그래서 대조군은 유지하고 실험군만 정지 후 재생성한다.
#
# 사용법: 운영 서버에서 (SSH 접속 후, 리포 루트에서) 실행
#   bash scripts/restart_ab_stoploss_treatment.sh

set -uo pipefail

API="http://localhost:8080/api/v1"
ARM_PARAMS='{"slAtrMultiplier":2.5,"tpRrMultiplier":1.2}'

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

echo
echo "⚠️  전제 확인: exitOverrides.isPresent() 시 suggestedStopLoss min() 을 건너뛰는 수정이"
echo "    포함된 빌드가 배포돼 있어야 합니다(PaperTradingService/LiveTradingService/DynamicTradingService)."
printf '    배포를 마쳤으면 yes 입력: '
read -r deployed
[ "$deployed" = "yes" ] || { echo "중단."; exit 1; }

# ── 1. 기존 실험군(버그로 오염된 표본) 정지 ──────────────────────────────────
OLD_TREATMENT_IDS="290 291 292 293 294 295 296 297 298 299 300 301 302 303 304 305 306 307 308 309 310 311 312 313 314 315 316 317 318 319 320 321 322 323 324 325 326 327 328 329"

echo
echo "▶ 기존 실험군 40개 정지 (오버라이드 버그로 오염된 표본)"
stopped=0
for id in $OLD_TREATMENT_IDS; do
  resp=$(api -X POST "$API/paper-trading/sessions/$id/stop")
  if echo "$resp" | grep -q '"success":true\|"data"'; then
    stopped=$((stopped+1))
  else
    echo "  ✗ 정지 실패 id=$id — $(echo "$resp" | head -c 150)"
  fi
done
echo "  정지 완료: $stopped/40"

# ── 2. 대조군과 동일 페어링으로 신규 실험군 생성 ─────────────────────────────
PAIRS="COMPOSITE_MEANREV_BB|KRW-ADA
COMPOSITE_MEANREV_BB|KRW-AVAX
COMPOSITE_MEANREV_BB|KRW-BTC
COMPOSITE_MEANREV_BB|KRW-DOGE
COMPOSITE_MEANREV_BB|KRW-EUL
COMPOSITE_MEANREV_BB|KRW-LINK
COMPOSITE_MEANREV_BB|KRW-PROM
COMPOSITE_MEANREV_BB|KRW-SOL
COMPOSITE_MOMENTUM_ICHIMOKU|KRW-ADA
COMPOSITE_MOMENTUM_ICHIMOKU|KRW-AVAX
COMPOSITE_MOMENTUM_ICHIMOKU|KRW-BTC
COMPOSITE_MOMENTUM_ICHIMOKU|KRW-DOGE
COMPOSITE_MOMENTUM_ICHIMOKU|KRW-EUL
COMPOSITE_MOMENTUM_ICHIMOKU|KRW-LINK
COMPOSITE_MOMENTUM_ICHIMOKU|KRW-PROM
COMPOSITE_MOMENTUM_ICHIMOKU|KRW-SOL
COMPOSITE_MOMENTUM_ICHIMOKU_V2|KRW-ADA
COMPOSITE_MOMENTUM_ICHIMOKU_V2|KRW-AVAX
COMPOSITE_MOMENTUM_ICHIMOKU_V2|KRW-BTC
COMPOSITE_MOMENTUM_ICHIMOKU_V2|KRW-DOGE
COMPOSITE_MOMENTUM_ICHIMOKU_V2|KRW-EUL
COMPOSITE_MOMENTUM_ICHIMOKU_V2|KRW-LINK
COMPOSITE_MOMENTUM_ICHIMOKU_V2|KRW-PROM
COMPOSITE_MOMENTUM_ICHIMOKU_V2|KRW-SOL
COMPOSITE_MTF_BTC|KRW-ADA
COMPOSITE_MTF_BTC|KRW-AVAX
COMPOSITE_MTF_BTC|KRW-BTC
COMPOSITE_MTF_BTC|KRW-DOGE
COMPOSITE_MTF_BTC|KRW-EUL
COMPOSITE_MTF_BTC|KRW-LINK
COMPOSITE_MTF_BTC|KRW-PROM
COMPOSITE_MTF_BTC|KRW-SOL
COMPOSITE_MTF_CONFIRMED|KRW-ADA
COMPOSITE_MTF_CONFIRMED|KRW-AVAX
COMPOSITE_MTF_CONFIRMED|KRW-BTC
COMPOSITE_MTF_CONFIRMED|KRW-DOGE
COMPOSITE_MTF_CONFIRMED|KRW-EUL
COMPOSITE_MTF_CONFIRMED|KRW-LINK
COMPOSITE_MTF_CONFIRMED|KRW-PROM
COMPOSITE_MTF_CONFIRMED|KRW-SOL"

echo
printf '\033[1m▶ 신규 실험군 40개 생성\033[0m — 파라미터 %s\n' "$ARM_PARAMS"
ok=0; fail=0
while IFS='|' read -r strategy coin; do
  [ -n "$strategy" ] || continue
  resp=$(api -X POST "$API/paper-trading/sessions" \
    -H 'Content-Type: application/json' \
    -d "{\"strategyType\":\"$strategy\",\"coinPair\":\"$coin\",\"timeframe\":\"M15\",
         \"initialCapital\":10000000,\"stopLossPct\":5.0,\"investRatio\":0.80,
         \"maxHoldHours\":24,\"strategyParams\":$ARM_PARAMS}")
  id=$(echo "$resp" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin); print(d["data"]["id"] if d.get("data") else "")
except Exception: print("")' 2>/dev/null)
  if [ -n "$id" ]; then
    echo "  ✓ id=$id  $strategy  $coin"
    ok=$((ok+1))
  else
    echo "  ✗ 실패  $strategy  $coin — $(echo "$resp" | head -c 200)"
    fail=$((fail+1))
  fi
done <<EOF
$PAIRS
EOF

printf '\n\033[1m▶ 결과: 성공 %s / 실패 %s\033[0m\n' "$ok" "$fail"

# ── 3. 사후 검증: SL 거리가 실제로 갈리는지 (기존 버그가 재현되지 않았는지) ──
echo
echo "▶ 사후 검증은 첫 진입이 쌓인 뒤 아래 쿼리로 확인할 것 (§23 판정 쿼리와 동일):"
cat <<'NOTE'

  SELECT CASE WHEN v.strategy_params IS NULL THEN '대조군' ELSE '실험군' END AS arm,
         count(*) AS n,
         round(avg(100.0*(1 - p.stop_loss_price/nullif(p.entry_price,0))),3) AS sl_dist_pct,
         round(avg(100.0*(p.take_profit_price/nullif(p.entry_price,0) - 1)),3) AS tp_dist_pct
  FROM paper_trading.position p
  JOIN paper_trading.virtual_balance v ON v.id = p.session_id
  WHERE v.timeframe='M15'
    AND p.opened_at >= '<이 스크립트 실행 시각>'
  GROUP BY 1;

  기대: 실험군 sl_dist_pct ≈ 대조군의 1.67배. 이번에도 같게 나오면 배포가 실제로
  반영되지 않았거나(재빌드 확인) 다른 경로가 남아있는 것 — 즉시 재중단.
NOTE
