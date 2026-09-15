#!/usr/bin/env bash
#
# 페이퍼 데이터 생성 격자 — 7전략 × 8코인 × 2타임프레임 = 112세션 (2026-08-18)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. API 는 외부 미개방이라 localhost:8080 전용.
#
# ■ 목적
#   실자본을 멈춘 상태에서 "어느 전략에 우위가 있는가" 를 몇 달이 아니라 몇 주에 판정할
#   표본을 만든다.
#
# ■ 왜 이 구성인가 (2026-08-18 실측)
#   · PaperTradingService 는 틱당 공유 캔들 캐시(tickCandleCache)가 있어
#     API 비용 = (코인 × 타임프레임) × 3 요청 이고 **세션 수와 무관**하다.
#     6코인 × H1 = 18 요청/분으로 42세션이 돌고 있었다 → 세션 추가는 사실상 공짜.
#   · 진짜 제약은 API 가 아니라 MAX_CONCURRENT_SESSIONS = 120.
#   · 병목은 세션 수가 아니라 BUY 신호 희소성. 코인당 1,869회 평가에 BUY 0~25건(0.3%).
#
# ■ 코인 선정
#   유지  SOL(BUY 25) · BTC(6) · DOGE(6)  — 신호가 실제로 나온 종목. **기존 H1 세션을
#         정지하지 않고 그대로 둔다** (11일 이력 34거래 중 25건이 여기서 나왔다).
#   정지  USDT — 스테이블코인. 8거래 전부 손실, 마찰비용만 태운다
#         ETH · XRP — 7전략 × 11일 = 1,869회 평가에 BUY 0건. 슬롯 낭비다
#   추가  LINK · ADA · AVAX — 유동성 있으면서 ETH/XRP 보다 변동성이 큰 메이저
#         PROM · EUL       — 동적 워치리스트가 ATR·스프레드 필터를 통과시킨 실적 종목
#
# ■ 세션 산술 (120 한도)
#   현재 42  −  정지 21(ETH·XRP·USDT)  =  21 유지(SOL·BTC·DOGE @H1)
#   신규   신규코인 5 × H1 × 7전략 = 35
#          전체코인 8 × M15 × 7전략 = 56
#   합계 21 + 91 = 112  (한도 120 이내)
#
# ■ 예상 효과
#   거래 생성 3.1건/일 → 약 20건/일. 전략당 n=20 도달 10개월 → 약 7일.
#   API 는 16조합 × 3 = 48 요청/분 (한도 420 의 11%).
#
# ■ 선행 조건
#   kill criteria 의 엣지 판정이 전략×타임프레임 그룹 단위여야 한다(2026-08-18 반영).
#   세션 단위면 세션당 0.07거래/일 → n=20 에 280일이라 기준이 발동하지 않는다.

set -uo pipefail
API="http://localhost:8080/api/v1"
CAPITAL=10000000      # 기존 42세션과 동일 (세션당 1,000만원)

STRATEGIES='["COMPOSITE_MEANREV_BB","COMPOSITE_MOMENTUM_ICHIMOKU","COMPOSITE_MOMENTUM_ICHIMOKU_V2","COMPOSITE_MTF_BTC","COMPOSITE_MTF_BTC_STRICT","COMPOSITE_MTF_CONFIRMED","COMPOSITE_PULLBACK_MTF"]'

# 2026-08-18 14:20 조회 기준 세션 ID. ETH 131~137 · XRP 124~130 · USDT 152~158
STOP_IDS="131 132 133 134 135 136 137 124 125 126 127 128 129 130 152 153 154 155 156 157 158"

NEW_H1_COINS="KRW-LINK KRW-ADA KRW-AVAX KRW-PROM KRW-EUL"
ALL_M15_COINS="KRW-SOL KRW-BTC KRW-DOGE KRW-LINK KRW-ADA KRW-AVAX KRW-PROM KRW-EUL"

# ── 토큰 ─────────────────────────────────────────────────────────────────────
if [ -z "${API_AUTH_TOKEN:-}" ] && [ -f .env ]; then
  API_AUTH_TOKEN=$(grep -E '^API_AUTH_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"'"'"'')
fi
if [ -z "${API_AUTH_TOKEN:-}" ]; then
  echo "✗ API_AUTH_TOKEN 을 찾을 수 없습니다. export 하거나 .env 가 있는 디렉터리에서 실행하세요."
  exit 1
fi
api() { curl -s -H "Authorization: Bearer $API_AUTH_TOKEN" "$@"; }

probe=$(api "$API/paper-trading/sessions")
case "$probe" in
  *UNAUTHORIZED*) echo "✗ 토큰 거부: $(echo "$probe" | head -c 200)"; exit 1 ;;
esac
echo "✓ 인증 확인"

echo
echo "실행 계획:"
echo "  1) 정지  ETH·XRP·USDT 21세션 (BUY 0건 / 스테이블코인)"
echo "  2) 생성  신규 5코인 × H1  = 35세션"
echo "  3) 생성  전체 8코인 × M15 = 56세션"
echo "  → 최종 112세션 (SOL·BTC·DOGE @H1 21세션은 이력 보존을 위해 유지)"
echo
if [ "${ASSUME_YES:-0}" != "1" ]; then
  read -p "진행하려면 Enter, 중단하려면 Ctrl-C: "
fi

# ── 1. 신호가 안 나오는 세션 정지 ────────────────────────────────────────────
echo
echo "=== 1. ETH·XRP·USDT 21세션 정지 ==="
stopped=0
for id in $STOP_IDS; do
  resp=$(api -X POST "$API/paper-trading/sessions/$id/stop")
  case "$resp" in
    *'"success":true'*) stopped=$((stopped + 1)) ;;
    *) echo "  ✗ id=$id — $(echo "$resp" | head -c 180)" ;;
  esac
done
echo "  정지 $stopped / 21"

# ── 2. 격자 생성 ─────────────────────────────────────────────────────────────
# /sessions/multi 는 (코인, 타임프레임) 하나당 전략 7개를 한 번에 만든다
create_grid() {
  local tf="$1"; shift
  for coin in "$@"; do
    local body resp n
    body=$(printf '{"strategyTypes":%s,"coinPair":"%s","timeframe":"%s","initialCapital":%s}' \
           "$STRATEGIES" "$coin" "$tf" "$CAPITAL")
    resp=$(api -X POST "$API/paper-trading/sessions/multi" \
                -H 'Content-Type: application/json' -d "$body")
    case "$resp" in
      *'"success":true'*)
        n=$(echo "$resp" | grep -o '"sessionId"' | wc -l)
        created=$((created + n))
        echo "  ✓ $coin @$tf — $n 세션" ;;
      *)
        echo "  ✗ $coin @$tf — $(echo "$resp" | head -c 220)" ;;
    esac
  done
}

created=0
echo
echo "=== 2. 신규 코인 × H1 ==="
create_grid H1 $NEW_H1_COINS

echo
echo "=== 3. 전체 코인 × M15 ==="
create_grid M15 $ALL_M15_COINS

# ── 3. 확인 ──────────────────────────────────────────────────────────────────
echo
echo "=== 결과 ==="
echo "  정지 $stopped 세션 / 생성 $created 세션"
echo "  기대: 정지 21, 생성 91, 최종 RUNNING 112"
echo
echo "확인 사항:"
echo "  · '세션 한도 초과' 로 실패하면 1) 정지가 덜 된 것이다 — 위 정지 결과를 볼 것"
echo "  · 신규 세션의 maxHoldHours 는 24(기본값)로 들어간다"
echo "  · API 부하 = (코인 × 타임프레임) × 3 = 48 요청/분. 세션 수와 무관하다"
