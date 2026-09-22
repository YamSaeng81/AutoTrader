#!/usr/bin/env bash
#
# 단일 전략 vs 복합 프리셋 — "필터를 벗기면 나아지는가" (2026-09-22)
# ═════════════════════════════════════════════════════════════════════════════
#
# ■ 왜 이 스크립트가 있나
#
#   09-22 `wf_rebaseline_v4_0922.sh --screen` 결과: **세 기준(표본 ≥20 · 순기대값 >0 ·
#   기간 교차 부호 일관)을 동시에 만족하는 칸이 0 건.** 132칸 중 OOS 거래 30건 이상이
#   18칸(14%)뿐이었다. 09-18 에 실측한 시장 노출률 중앙값 3.0% 와 같은 이야기다.
#
#   가설: **필터를 겹겹이 쌓아 신호가 거의 남지 않는다.**
#   복합 프리셋에는 EMA 방향 필터 + ADX 필터 + RSI Veto + Ichimoku 구름 +
#   MTF 상위봉 확인 + BTC Market Guard 가 겹쳐 있고, **전부 통과해야 진입**한다.
#   각각은 "나쁜 진입을 막는다"는 명분이 있지만, 남은 신호가 더 좋다는 근거는 없다.
#   오히려 표본이 얇아져 **판단 자체가 불가능해진다** — 실제로 그렇게 됐다.
#
#   그리고 5번 요약표가 가설을 뒷받침했다 — 평균 순기대값이 양수인 전략은
#   **전부 평균 거래 9.8~18.4 건짜리**였고, 표본이 두꺼운 전략(766·476·431 거래)은
#   예외 없이 음수였다. **양수는 표본이 얇은 곳에서만 나온다.**
#
# ■ 무엇을 판별하는가
#
#   복합 프리셋의 **성분 전략을 단독으로** 같은 코인·같은 기간·같은 규칙(v4)으로 돌린다.
#
#     · 단독이 복합과 같거나 낫다  →  **14개 프리셋의 복잡도는 전부 낭비다.**
#                                    가중 합성과 필터 적층이 값을 하지 못한다는 뜻.
#     · 복합이 단독보다 낫다        →  어느 필터가 값을 하는지 하나씩 가려낼 수 있다.
#     · 둘 다 총기대값 0 근처       →  신호원 자체에 방향성이 없다. (다) 접는 판단으로.
#
#   어느 쪽이 나와도 결론이 명확해진다. 비용은 스크립트 하나 + WF 1회로,
#   착수하지 않기로 한 Wave 3-K(1.5일 + 5일)보다 훨씬 싸다.
#
# ■ 대상 전략 9종 — 복합 프리셋의 실제 성분만
#
#     MACD           momentumCore · momentumV2Core · breakoutCore
#     VWAP           momentumCore · MEANREV_BB
#     SUPERTREND     momentumV2Core · MTF 계열의 HTF 확인자
#     BOLLINGER      MEANREV_BB
#     RSI            MEANREV_BB (+ RsiVeto 가 IndicatorUtils 경유로 공유)
#     ATR_BREAKOUT   breakoutCore · COMPOSITE_ETH
#     VOLUME_DELTA   breakoutCore
#     EMA_CROSS      COMPOSITE_ETH
#     GRID           momentumCore · momentumV2Core
#
#   제외: ORDERBOOK_IMBALANCE — 백테스트는 호가를 캔들로 근사하므로 단독 평가가
#         공정하지 않다(CompositePresets 주석 참조). STOCHASTIC_RSI ·
#         FAIR_VALUE_GAP · HEIKIN_ASHI_STOCH · MACD_STOCH_BB 는 지금 검증 대상
#         프리셋의 성분이 아니라 비교의 축이 흐려진다.
#
#   📌 GRID · RSI · VOLUME_DELTA 는 v4 에서 막 고친 것들이다. 단독으로 돌리면
#      복합의 0.2~0.3 가중에 희석되지 않은 **본래 성능**이 처음으로 보인다.
#
# ■ 기간·코인 — v4 실행에서 복제한다 (B구간 제외)
#
#   비교가 목적이므로 복합이 돌았던 것과 **같은 슬롯**을 써야 한다. 그래서 여기서도
#   하드코딩하지 않고 `backtest_run` 의 v4 실행에서 (타임프레임, 기간, 코인) 을 읽는다.
#
#   🔴 단 **B구간(2025-10-01~)은 제외한다.** 09-22 실측에서 그 6종은 OOS 표본이
#      0~15건이고 부호가 뒤죽박죽이었다 — 노이즈다. 포함하면 조합 수만 54개 늘고
#      판단에 보탬이 없다.
#
# ■ 사용법 — 운영 서버에서, 리포 루트에서
#
#     bash scripts/wf_singles_0922.sh --plan     # 무엇을 제출할지만 출력
#     bash scripts/wf_singles_0922.sh            # 제출
#     bash scripts/wf_singles_0922.sh --screen   # 단독 vs 복합 비교
#
#   ⚠️ 제출은 즉시 끝나고 실행은 백그라운드다. 완료 시 텔레그램 알림이 온다.
#
# ■ 읽는 법 — 미리 정해둔다
#
#   ⚠️ **후보가 나와도 채택 근거가 아니다.** 여기서 하는 일은 "쓸 전략 고르기"가 아니라
#      **"복잡도가 값을 하는가"라는 하나의 질문에 답하기**다.
#   ⚠️ 단독 전략은 필터가 없어 거래가 많아진다. **거래가 많아지면 마찰비용도 비례해
#      커진다** — 총기대값(순기대값 + 0.24)으로 봐야 신호 자체의 방향성이 보인다.
#   🔴 WLD·ONDO·MIRA·WLFI 재배치 금지 (09-18 결론).

set -uo pipefail

# ── 모르는 인자는 즉시 중단 (09-22 사고 재발 방지) ──────────────────────────
#    `--detail` 미배포 상태에서 실행했다가 인자가 어느 분기에도 안 걸려
#    그대로 제출 경로로 떨어져 132조합이 재제출된 적이 있다.
case "${1:-}" in
  "") ;;  # 인자 없음 = 제출
  --plan|--screen) ;;
  *)
    echo "✗ 알 수 없는 인자: '$1'"
    echo
    echo "  사용 가능:"
    echo "    (없음)     제출"
    echo "    --plan     무엇을 제출할지만 출력"
    echo "    --screen   단독 vs 복합 비교"
    echo
    echo "  플래그가 안 먹으면 서버 스크립트가 옛 버전입니다 — git pull 후 재실행."
    echo "  (제출은 하지 않았습니다.)"
    exit 2 ;;
esac

API="http://localhost:8080/api/v1"

IN_SAMPLE_RATIO=0.7
WINDOW_COUNT=5
FRICTION="0.24"          # 자본 대비 왕복 마찰비용 — wf_rebaseline_v4_0922.sh --screen 참조
MIN_TRADES="${MIN_TRADES:-20}"

SINGLES_JSON='["MACD","VWAP","SUPERTREND","BOLLINGER","RSI","ATR_BREAKOUT","VOLUME_DELTA","EMA_CROSS","GRID"]'
SINGLES_SQL="'MACD','VWAP','SUPERTREND','BOLLINGER','RSI','ATR_BREAKOUT','VOLUME_DELTA','EMA_CROSS','GRID'"
SINGLES_N=9

psql_q() {
  docker compose -f docker-compose.prod.yml exec -T db \
    psql -U trader -d crypto_auto_trader -At -F'|' -c "$1"
}

# ── --screen: 단독 vs 복합 ───────────────────────────────────────────────────
if [ "${1:-}" = "--screen" ]; then
  CTE="WITH x AS (
        SELECT DISTINCT ON (strategy_name, coin_pair, timeframe, start_date, end_date)
               strategy_name AS s, coin_pair AS c, timeframe AS tf,
               (start_date AT TIME ZONE 'Asia/Seoul')::date AS sd,
               (end_date   AT TIME ZONE 'Asia/Seoul')::date AS ed,
               CASE WHEN strategy_name LIKE 'COMPOSITE%' THEN '복합' ELSE '단독' END AS kind,
               wf_result_json->>'verdict' AS verdict,
               (wf_result_json->'aggregatedOutSample'->>'totalTrades')::int AS n,
               (wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric AS exp
          FROM backtest_run
         WHERE is_walk_forward AND exit_rules_version = 4
         ORDER BY strategy_name, coin_pair, timeframe, start_date, end_date, created_at DESC)"

  echo "▶ 0. 기준: 마찰비용 자본 대비 왕복 ${FRICTION}% · 표본 하한 ${MIN_TRADES}"
  echo "        총기대값 = 순기대값 + ${FRICTION}  (신호 자체의 방향성 크기)"
  echo

  echo "▶ 1. 단독 vs 복합 — 전체 요약"
  psql_q "$CTE
          SELECT kind AS 구분, count(*) AS 칸,
                 sum(n) AS 총OOS거래, round(avg(n),1) AS 평균거래,
                 round(avg(exp),3) AS 평균순기대값,
                 round(avg(exp) + ${FRICTION},3) AS 평균총기대값,
                 sum(CASE WHEN exp > 0 THEN 1 ELSE 0 END) AS 양수칸,
                 sum(CASE WHEN n >= ${MIN_TRADES} THEN 1 ELSE 0 END) AS 표본충족칸
            FROM x GROUP BY 1 ORDER BY 1;"
  echo

  echo "▶ 2. 전략별 — 단독이 위, 복합이 아래 (평균 순기대값 순)"
  psql_q "$CTE
          SELECT kind AS 구분, s AS 전략, count(*) AS 칸,
                 sum(n) AS 총OOS거래, round(avg(n),1) AS 평균거래,
                 round(avg(exp),3) AS 평균순기대값,
                 round(avg(exp) + ${FRICTION},3) AS 평균총기대값,
                 sum(CASE WHEN exp > 0 THEN 1 ELSE 0 END) AS 양수칸
            FROM x GROUP BY 1,2 ORDER BY 1 DESC, 6 DESC;"
  echo

  echo "▶ 3. 같은 슬롯에서 맞대결 — 코인·기간·타임프레임이 같은 칸끼리"
  echo "  '단독최고'가 '복합최고'보다 높으면 그 슬롯에서는 복잡도가 값을 못 한 것이다."
  psql_q "$CTE
          , best AS (
            SELECT c, tf, sd, ed, kind,
                   max(exp) FILTER (WHERE n >= ${MIN_TRADES}) AS best_exp,
                   count(*) FILTER (WHERE n >= ${MIN_TRADES}) AS cells
              FROM x GROUP BY 1,2,3,4,5)
          SELECT a.c AS 코인, a.tf, a.sd || '~' || a.ed AS 기간,
                 round(b.best_exp,3) AS 단독최고,
                 round(a.best_exp,3) AS 복합최고,
                 CASE WHEN b.best_exp IS NULL OR a.best_exp IS NULL THEN '표본부족'
                      WHEN b.best_exp > a.best_exp THEN '🔴 단독 우세'
                      ELSE '복합 우세' END AS 판정
            FROM best a JOIN best b
              ON a.c=b.c AND a.tf=b.tf AND a.sd=b.sd AND a.ed=b.ed
             AND a.kind='복합' AND b.kind='단독'
           ORDER BY 2,1;"
  echo

  echo "▶ 4. 맞대결 집계"
  psql_q "$CTE
          , best AS (
            SELECT c, tf, sd, ed, kind,
                   max(exp) FILTER (WHERE n >= ${MIN_TRADES}) AS best_exp
              FROM x GROUP BY 1,2,3,4,5)
          SELECT CASE WHEN b.best_exp IS NULL OR a.best_exp IS NULL THEN '표본부족'
                      WHEN b.best_exp > a.best_exp THEN '단독 우세'
                      ELSE '복합 우세' END AS 판정, count(*)
            FROM best a JOIN best b
              ON a.c=b.c AND a.tf=b.tf AND a.sd=b.sd AND a.ed=b.ed
             AND a.kind='복합' AND b.kind='단독'
           GROUP BY 1 ORDER BY 2 DESC;"
  echo

  echo "▶ 5. 단독 후보 — 표본 ≥ ${MIN_TRADES} 이고 순기대값 > 0"
  psql_q "$CTE
          SELECT s AS 전략, c AS 코인, tf, sd || '~' || ed AS 기간,
                 n AS 거래, round(exp,3) AS 순기대값,
                 round(exp + ${FRICTION},3) AS 총기대값, verdict
            FROM x
           WHERE kind = '단독' AND n >= ${MIN_TRADES} AND exp > 0
           ORDER BY exp DESC;"
  echo

  echo "▶ 6. 단독 '엣지 0' 구간 — 총기대값 ±0.05 안, 표본 ≥ 30"
  echo "  🔴 여기 걸리면 그 신호원 자체에 방향성이 없다는 뜻이다."
  psql_q "$CTE
          SELECT s AS 전략, c AS 코인, tf, n AS 거래,
                 round(exp,3) AS 순기대값, round(exp + ${FRICTION},3) AS 총기대값
            FROM x
           WHERE kind = '단독' AND n >= 30 AND abs(exp + ${FRICTION}) < 0.05
           ORDER BY n DESC;"
  echo
  echo "⚠️ 여기서 하는 일은 '쓸 전략 고르기'가 아니라 '복잡도가 값을 하는가'에 답하는 것이다."
  echo "⚠️ 단독은 필터가 없어 거래가 많다 — 마찰도 비례해 커진다. 총기대값으로 볼 것."
  echo "🔴 WLD·ONDO·MIRA·WLFI 재배치 금지 (09-18 결론)."
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

resp=$(api "$API/backtest/walk-forward/history")
case "$resp" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요"; exit 1 ;;
esac
echo "✓ 인증 확인"

# ── v4 실행에서 슬롯 복제 (B구간 제외) ──────────────────────────────────────
#    날짜는 KST 로 되돌려 읽는다 — 저장 경로가 atStartOfDay(Asia/Seoul) 이라
#    ::date 를 그대로 쓰면 하루가 밀린다 (wf_rebaseline_v4_0922.sh 주석 참조).
PLAN=$(psql_q "SELECT timeframe,
                      (start_date AT TIME ZONE 'Asia/Seoul')::date,
                      (end_date   AT TIME ZONE 'Asia/Seoul')::date,
                      '[\"' || string_agg(DISTINCT coin_pair, '\",\"' ORDER BY coin_pair) || '\"]',
                      count(DISTINCT coin_pair)
                 FROM backtest_run
                WHERE is_walk_forward
                  AND exit_rules_version = 4
                  AND strategy_name LIKE 'COMPOSITE%'
                  AND (start_date AT TIME ZONE 'Asia/Seoul')::date < DATE '2025-01-01'
                GROUP BY 1,2,3
                ORDER BY 1,2,3;")

if [ -z "$PLAN" ]; then
  echo "✗ v4 복합 실행을 찾지 못했습니다 — 복제할 슬롯이 없습니다."
  echo "  먼저 bash scripts/wf_rebaseline_v4_0922.sh 를 완료하세요."
  exit 1
fi

echo
echo "▶ 제출 계획 — 단일 전략 ${SINGLES_N}종을 복합과 같은 슬롯에 돌린다"
echo "  (B구간 2025-10-01~ 은 제외 — 표본 0~15건의 노이즈 구간)"
echo "─────────────────────────────────────────────────────────────────────────"
TOTAL=0
while IFS='|' read -r tf sd ed coins n; do
  [ -z "$tf" ] && continue
  combos=$(( n * SINGLES_N ))
  TOTAL=$(( TOTAL + combos ))
  printf "  %-4s %s ~ %s   코인 %2s종 × %s전략 = %3s 조합\n" "$tf" "$sd" "$ed" "$n" "$SINGLES_N" "$combos"
done <<< "$PLAN"
echo "─────────────────────────────────────────────────────────────────────────"
echo "  합계 $TOTAL 조합 · IS 비율 $IN_SAMPLE_RATIO · 윈도우 $WINDOW_COUNT"
echo
echo "  대상 전략: MACD · VWAP · SUPERTREND · BOLLINGER · RSI"
echo "             ATR_BREAKOUT · VOLUME_DELTA · EMA_CROSS · GRID"

if [ "${1:-}" = "--plan" ]; then
  echo
  echo "── --plan 이므로 제출하지 않고 종료합니다."
  exit 0
fi

submit() {
  local tf="$1" coins="$2" sd="$3" ed="$4"
  api -X POST "$API/backtest/walk-forward-batch-async" \
    -H 'Content-Type: application/json' \
    -d "{
      \"coinPairs\": $coins,
      \"strategyTypes\": $SINGLES_JSON,
      \"timeframe\": \"$tf\",
      \"startDate\": \"$sd\",
      \"endDate\": \"$ed\",
      \"inSampleRatio\": $IN_SAMPLE_RATIO,
      \"windowCount\": $WINDOW_COUNT
    }"
}

echo
echo "▶ 제출"
while IFS='|' read -r tf sd ed coins n; do
  [ -z "$tf" ] && continue
  echo "  · $tf $sd ~ $ed ($n종 × $SINGLES_N)"
  submit "$tf" "$coins" "$sd" "$ed"
  echo
done <<< "$PLAN"

echo
echo "─────────────────────────────────────────────────────────────────────────"
echo "제출 완료. 실행은 백그라운드이며 완료 시 텔레그램 알림이 옵니다."
echo
echo "  진행 상황   curl -s -H \"\$AUTH\" $API/backtest/jobs"
echo "  완료 후     bash scripts/wf_singles_0922.sh --screen"
echo
echo "⚠️ 이 실험은 '쓸 전략 고르기'가 아니라 '복잡도가 값을 하는가'에 답하는 것입니다."
echo "⚠️ 단독은 필터가 없어 거래가 많습니다 — 마찰비용도 비례해 커집니다."
echo "   총기대값(순기대값 + ${FRICTION})으로 봐야 신호 자체의 방향성이 보입니다."
