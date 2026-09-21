#!/usr/bin/env bash
#
# 백테스트 규칙 v4 재검증 기준선 만들기 (2026-09-22)
# ═════════════════════════════════════════════════════════════════════════════
#
# ■ 왜 이 스크립트가 있나
#
#   Wave 3 의 L·I·H 를 고치면서 BACKTEST_RULESET_VERSION 을 3 → 4 로 올렸다.
#   세 수정 모두 **같은 입력에 대해 백테스트가 다른 결과를 내게 만든다.** 따라서
#   09-18 에 만든 v3 기준선은 전부 무효다.
#
#     L  CandleDownsampler — MTF 상위봉 경계가 조회 시점마다 흔들렸다.
#        500봉 슬라이딩 윈도우가 1칸 밀릴 때마다 H4 봉의 위상이 바뀌어,
#        같은 시각을 두 번 평가하면 다른 상위봉을 봤다. 이제 절대 시각 버킷
#        (floorDiv)으로 고정된다.
#
#     I  GridStrategy — BUY 존(하위 0~3)과 SELL 존(상위 7~10)이 구조적으로
#        분리돼 있어 activeLevels.remove(levelIndex) 가 **한 번도 제거한 적이 없다.**
#        GRID 는 생애 최대 4번 매수하고 영구히 침묵했다. 즉 GRID 가중 0.2 는
#        사실상 0 이었다. 이제 SELL 이 보유 레벨을 실제로 반납한다.
#
#     H  RSI(IndicatorUtils + RsiStrategy) — 완전 무변동 구간에서 avgGain·avgLoss 가
#        둘 다 0 일 때 RSI 100(과매수)을 냈다. 이제 50(중립).
#        VolumeDeltaStrategy — high == low 인 봉에서 매수비율 0 → 거래량 전부를
#        매도로 계산했다. 이제 0.5(중립).
#
# ■ 영향 범위가 넓다 — COMPOSITE_ETH 하나만 무사하다
#
#   의존성을 전부 따라간 결과다. 특히 CompositeRegimeRouter 가 GRID(0.2) ·
#   VolumeDelta(0.3) · RsiVeto 를 전부 물고 있고, breakoutCore 도 VD + RsiVeto 를
#   쓴다. 그래서 "MTF 계열만" 이 아니다.
#
#     프리셋                           L/MTF  I/GRID  H/RSI  H/VD
#     COMPOSITE (레짐적응)                       ●      ●      ●
#     COMPOSITE_REGIME_ROUTER                    ●      ●      ●
#     COMPOSITE_MOMENTUM                         ●
#     COMPOSITE_MOMENTUM_ICHIMOKU                ●
#     COMPOSITE_MOMENTUM_ICHIMOKU_V2             ●
#     COMPOSITE_BREAKOUT                                ●      ●
#     COMPOSITE_BREAKOUT_ICHIMOKU                       ●      ●
#     COMPOSITE_MTF_CONFIRMED             ●      ●      ●      ●
#     COMPOSITE_MTF_BTC                   ●             ●      ●
#     COMPOSITE_MTF_MOMENTUM              ●      ●
#     COMPOSITE_PULLBACK_MTF              ●             ●
#     COMPOSITE_MEANREV_BB                              ●
#     COMPOSITE_ETH                       —      —      —      —   ← 영향 없음
#
#   COMPOSITE_MTF_BTC_STRICT 는 제외한다 — DEPRECATED 이고 MTF_BTC 와 결과가 동일하다
#   (SupertrendStrictHtfNoOpTest 로 증명됨). 돌려 봐야 같은 숫자가 두 벌 쌓인다.
#
# ■ 세 묶음으로 나눈 이유
#
#   Job 1 은 09-18 v3 기준선과 **정확히 같은 조합**이다. v3 → v4 를 1:1 로 비교할 수
#   있는 유일한 묶음이므로 분리했다. Job 2 는 "이번 수정으로 새로 무효가 된" 나머지
#   프리셋이다. 둘을 섞으면 비교 가능한 15조합이 60조합 속에 묻힌다.
#
# ■ 코인 — 메이저 5종으로 유지한다
#
#   09-22 백필 이후 H1 보유가 99종까지 올라왔지만, 여기서 코인을 늘리지 않는다.
#   이 스크립트의 목적은 **규칙 버전 간 비교**이고, 그러려면 v3 기준선과 같은 코인을
#   써야 한다. 코인을 동시에 바꾸면 "규칙이 바꾼 것"과 "코인이 바꾼 것"이 섞인다.
#
#   알트 커버리지 확장은 별건이다 — v4 기준선이 선 다음에
#   wf_watchlist_0918.sh 방식으로 따로 돌릴 것.
#
#   ⚠️ 이 기준선도 **메이저 5종만** 덮는다. 동적 세션은 실제로는 소형 알트를 산다.
#      메이저 통과가 실매매 적합성을 뜻하지 않는다 (09-04 분석 참조).
#
# ■ 종료일은 자동으로 계산한다
#
#   09-18 에는 09-07 로 끊었다. 코인마다 캔들 끝날짜가 달라 **전 코인 공통의 마지막
#   날짜**로 맞춰야 하기 때문이다. 백필로 그 날짜가 올라갔을 것이므로 하드코딩하지
#   않고 DB 에서 5종의 min(max(time)) 을 구해 쓴다.
#   DB 조회가 안 되면 중단한다 — 틀린 날짜로 도는 것보다 낫다.
#   수동 지정: END_DATE=2026-09-20 bash scripts/wf_rebaseline_v4_0922.sh
#
# ■ M15 는 여전히 얇다
#
#   09-22 실측: M15 캔들 0건 61종 / 보유 39종. 메이저 5종이 보유에 들어 있어야
#   Job 3 이 성립한다 — 사전 점검에서 확인한다. 알트를 M15 로 확장하려 하면
#   INSUFFICIENT_DATA 만 쌓인다.
#
# ■ 사용법 — 운영 서버에서, 리포 루트에서
#
#     bash scripts/wf_rebaseline_v4_0922.sh --precheck  # 제출 전 환경 확인만
#     bash scripts/wf_rebaseline_v4_0922.sh             # 제출
#     bash scripts/wf_rebaseline_v4_0922.sh --verify    # 결과가 v4 로 저장됐는지 확인
#
#   제출은 즉시 끝나고 실행은 백그라운드다. 완료 시 텔레그램 알림이 온다.
#   ⚠️ 완료 후 반드시 --verify 를 돌릴 것 — 컨테이너가 옛 이미지로 떠 있으면 결과가
#      v3 로 저장되고, 그러면 **낡은 코드의 숫자를 새 기준선으로 믿게 된다.**
#
# ■ 게이트는 지금 꺼져 있다
#
#   REQUIRE_WALK_FORWARD_GATE 기본값이 false 다. 이 재실행은 거래를 막지도 풀지도
#   않는다 — 비교 기준을 새로 만드는 작업이다.

set -uo pipefail

API="http://localhost:8080/api/v1"

COINS='["KRW-BTC","KRW-ETH","KRW-SOL","KRW-DOGE","KRW-XRP"]'
COINS_SQL="'KRW-BTC','KRW-ETH','KRW-SOL','KRW-DOGE','KRW-XRP'"
START_DATE="2023-01-01"
IN_SAMPLE_RATIO=0.7
WINDOW_COUNT=5

# Job 1 — v3 기준선과 동일한 조합. v3↔v4 를 1:1 로 비교할 수 있는 유일한 묶음.
J1_H1='["COMPOSITE_MEANREV_BB","COMPOSITE_MOMENTUM_ICHIMOKU_V2","COMPOSITE_MTF_CONFIRMED"]'

# Job 2 — 이번 v4 로 새로 무효가 된 나머지 프리셋 (COMPOSITE_ETH·MTF_BTC_STRICT 제외)
J2_H1='["COMPOSITE","COMPOSITE_REGIME_ROUTER","COMPOSITE_MOMENTUM","COMPOSITE_MOMENTUM_ICHIMOKU","COMPOSITE_BREAKOUT","COMPOSITE_BREAKOUT_ICHIMOKU","COMPOSITE_MTF_BTC","COMPOSITE_MTF_MOMENTUM","COMPOSITE_PULLBACK_MTF"]'

# Job 3 — M15. v3 기준선과 동일.
J3_M15='["COMPOSITE_MEANREV_BB","COMPOSITE_MTF_CONFIRMED"]'

psql_q() {
  docker compose -f docker-compose.prod.yml exec -T db \
    psql -U trader -d crypto_auto_trader -At -F' | ' -c "$1"
}

# ── --verify: 결과가 v4 로 저장됐는지 확인 ───────────────────────────────────
if [ "${1:-}" = "--verify" ]; then
  echo "▶ WF 실행의 규칙 버전 분포 (NULL = 09-08 이전)"
  psql_q "SELECT COALESCE(exit_rules_version::text,'NULL') AS v, count(*), max(created_at)::date
            FROM backtest_run WHERE is_walk_forward GROUP BY 1 ORDER BY 1;"
  echo
  echo "▶ 오늘 저장된 실행 (여기가 전부 4 여야 한다)"
  psql_q "SELECT strategy_name, coin_pair, timeframe,
                 COALESCE(exit_rules_version::text,'NULL') AS v,
                 wf_result_json->>'verdict' AS verdict
            FROM backtest_run
           WHERE is_walk_forward AND created_at::date = CURRENT_DATE
           ORDER BY timeframe, strategy_name, coin_pair;"
  echo
  echo "▶ v3 → v4 대조 (Job 1 조합만 — 같은 코인·같은 전략·같은 타임프레임)"
  psql_q "SELECT strategy_name, coin_pair,
                 max(CASE WHEN exit_rules_version=3 THEN wf_result_json->>'verdict' END) AS v3,
                 max(CASE WHEN exit_rules_version=4 THEN wf_result_json->>'verdict' END) AS v4
            FROM backtest_run
           WHERE is_walk_forward AND timeframe='H1'
             AND exit_rules_version IN (3,4)
             AND strategy_name IN ('COMPOSITE_MEANREV_BB','COMPOSITE_MOMENTUM_ICHIMOKU_V2','COMPOSITE_MTF_CONFIRMED')
           GROUP BY 1,2 ORDER BY 1,2;"
  echo
  echo "  v 가 3 이나 NULL 이면 컨테이너가 옛 이미지입니다 — 재빌드 후 다시 제출하세요."
  exit 0
fi

# ── 인증 ─────────────────────────────────────────────────────────────────────
if [ -z "${API_AUTH_TOKEN:-}" ] && [ -f .env ]; then
  API_AUTH_TOKEN=$(grep -E '^API_AUTH_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d "\"'")
fi
if [ -z "${API_AUTH_TOKEN:-}" ]; then
  echo "✗ API_AUTH_TOKEN 을 찾을 수 없습니다 (.env 또는 환경변수)."
  exit 1
fi
AUTH="Authorization: Bearer $API_AUTH_TOKEN"
api() { curl -s -H "$AUTH" "$@"; }

# ── 사전 점검 1: 백엔드가 응답하는가 ─────────────────────────────────────────
resp=$(api "$API/backtest/walk-forward/history")
case "$resp" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요"; exit 1 ;;
esac
echo "✓ 인증 확인"

# ── 사전 점검 2: 종료일 — 5종 공통의 마지막 캔들 날짜 ────────────────────────
if [ -z "${END_DATE:-}" ]; then
  echo
  echo "▶ 코인별 H1 캔들 끝날짜 (가장 이른 날짜로 맞춘다)"
  psql_q "SELECT coin_pair, max(time)::date, count(*)
            FROM candle_data WHERE timeframe='H1' AND coin_pair IN ($COINS_SQL)
           GROUP BY 1 ORDER BY 2;"
  END_DATE=$(psql_q "SELECT min(d)::date FROM (
                       SELECT max(time)::date AS d FROM candle_data
                        WHERE timeframe='H1' AND coin_pair IN ($COINS_SQL)
                        GROUP BY coin_pair) t;" | tr -d ' \r')
fi
case "$END_DATE" in
  20[0-9][0-9]-[0-1][0-9]-[0-3][0-9]) ;;
  *) echo "✗ 종료일을 구하지 못했습니다 (받은 값: '${END_DATE}')."
     echo "  DB 조회가 실패했거나 5종 중 캔들이 없는 코인이 있습니다."
     echo "  수동 지정: END_DATE=2026-09-20 bash scripts/wf_rebaseline_v4_0922.sh"
     exit 1 ;;
esac
echo "✓ 종료일 $END_DATE (5종 공통)"

# ── 사전 점검 3: M15 보유 확인 — Job 3 이 성립하는가 ─────────────────────────
echo
echo "▶ 메이저 5종 M15 보유 (여기 5줄이 다 나와야 Job 3 이 의미가 있다)"
psql_q "SELECT coin_pair, max(time)::date, count(*)
          FROM candle_data WHERE timeframe='M15' AND coin_pair IN ($COINS_SQL)
         GROUP BY 1 ORDER BY 1;"

echo
echo "▶ 지금 운영 중인 조합"
psql_q "SELECT 'DYNAMIC', strategy_type, timeframe, count(*)
          FROM dynamic_session WHERE status='RUNNING' GROUP BY 1,2,3
        UNION ALL
        SELECT 'FIXED', strategy_type, timeframe, count(*)
          FROM live_trading_session WHERE status='RUNNING' GROUP BY 1,2,3
        ORDER BY 1,2,3;"

echo
echo "  제출 예정:"
echo "    기간      $START_DATE ~ $END_DATE  (IS 비율 $IN_SAMPLE_RATIO · 윈도우 $WINDOW_COUNT)"
echo "    코인      $COINS"
echo "    Job 1 H1  3전략 × 5코인 = 15 조합   ← v3 와 직접 비교되는 묶음"
echo "    Job 2 H1  9전략 × 5코인 = 45 조합   ← v4 로 새로 무효가 된 프리셋"
echo "    Job 3 M15 2전략 × 5코인 = 10 조합"
echo "                                 합계 70 조합"

if [ "${1:-}" = "--precheck" ]; then
  echo
  echo "── --precheck 이므로 제출하지 않고 종료합니다."
  exit 0
fi

submit() {
  local tf="$1" strategies="$2"
  api -X POST "$API/backtest/walk-forward-batch-async" \
    -H 'Content-Type: application/json' \
    -d "{
      \"coinPairs\": $COINS,
      \"strategyTypes\": $strategies,
      \"timeframe\": \"$tf\",
      \"startDate\": \"$START_DATE\",
      \"endDate\": \"$END_DATE\",
      \"inSampleRatio\": $IN_SAMPLE_RATIO,
      \"windowCount\": $WINDOW_COUNT
    }"
}

echo
echo "▶ Job 1: H1 기준선 승계 — 3전략 × 5코인 = 15 조합"
submit "H1" "$J1_H1"
echo

echo "▶ Job 2: H1 영향 범위 — 9전략 × 5코인 = 45 조합"
submit "H1" "$J2_H1"
echo

echo "▶ Job 3: M15 — 2전략 × 5코인 = 10 조합"
submit "M15" "$J3_M15"
echo

echo
echo "─────────────────────────────────────────────────────────────────────────"
echo "제출 완료. 실행은 백그라운드이며 완료 시 텔레그램 알림이 옵니다."
echo
echo "  진행 상황   curl -s -H \"\$AUTH\" $API/backtest/jobs"
echo "  완료 후     bash scripts/wf_rebaseline_v4_0922.sh --verify    ← 반드시 확인"
echo
echo "⚠️ --verify 에서 exit_rules_version 이 4 가 아니면 그 결과는 기준선이 아닙니다."
echo "⚠️ Job 2 는 v3 대응짝이 없습니다. '나빠졌다/좋아졌다'로 읽지 말고 v4 의 출발점으로만 쓸 것."
