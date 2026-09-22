#!/usr/bin/env bash
#
# ATR_BREAKOUT 기간 안정성 — "유일한 예외가 진짜인가" (2026-09-22)
# ═════════════════════════════════════════════════════════════════════════════
#
# ■ 왜 이 스크립트가 있나
#
#   09-22 단독 vs 복합 실험(`wf_singles_0922.sh`)에서 단 하나가 살아남았다.
#
#     전략           총거래  평균거래  순기대값  총기대값  양수칸
#     ATR_BREAKOUT    1,752    76.2    +0.028   +0.268   10/23   ← 유일
#     RSI               540    23.5    −0.120   +0.120    9/23
#     VOLUME_DELTA    3,327   144.7    −0.161   +0.079    6/23
#     SUPERTREND      4,658   202.5    −0.262   −0.022    1/23
#     MACD            1,215    52.8    −0.519   −0.279    2/23
#
#   ATR_BREAKOUT 만 **표본이 두꺼우면서 순기대값이 양수**다.
#   나머지는 표본이 두꺼워질수록 총기대값이 0 으로 수렴했다
#   (SUPERTREND/BTC/M15 는 379거래에 총기대값 −0.012).
#
#   그런데 그 결과를 "기간 교차 검증했다"고 읽을 수 없다.
#
#   🔴 **검증 설계 결함이 있었다** — `--screen` 의 세 번째 기준("기간 교차 부호 일관")이
#      이 데이터셋에서 아무것도 검증하지 못했다. 두 기간이
#      `2023-01-01~2026-08-30` 과 `2023-01-01~2026-09-07` 로 **8일 차이의 중첩**이다.
#      표본도 55 vs 59, 47 vs 50 으로 사실상 같은 데이터다.
#      "두 기간 모두 양수"는 같은 숫자를 두 번 센 것이다.
#
#   그래서 **겹치지 않는 기간**으로 다시 묻는다.
#
# ■ 왜 Walk-Forward 가 아니라 전 구간 백테스트인가
#
#   WF 는 구간의 ~30% 만 OOS 로 쓴다. 1년 구간을 WF 로 돌리면 OOS 거래가
#   ATR_BREAKOUT 기준 연 20건 × 0.3 ≈ **6건**으로 무너진다. 판정이 불가능하다.
#
#   전 구간 백테스트는 그 해의 모든 거래를 센다(연 ~20건 × 13코인). 그리고
#   **여기서는 그게 정당하다** — 이 실험은 파라미터를 고르지 않는다.
#   ATR_BREAKOUT 을 기본값 그대로 돌려 **"해마다 부호가 유지되는가"**만 본다.
#   맞출 대상이 없으므로 과적합이 낄 자리가 없다.
#
#   ⚠️ 그래도 `wf_watchlist_0918.sh` 의 경고는 유효하다 — 전 구간 백테스트 수익률은
#      **채택 근거가 아니다.** 여기서 쓰는 것은 수익률이 아니라 **기대값의 부호 일관성**이다.
#
# ■ 기간 — 겹치지 않는 4구간
#
#     2023-01-01 ~ 2023-12-31     상승 전환기
#     2024-01-01 ~ 2024-12-31
#     2025-01-01 ~ 2025-12-31
#     2026-01-01 ~ 2026-08-30     (캔들 공통 보유 끝)
#
#   서로 다른 시장 국면이다. **네 구간 전부 양수면** 국면 의존이 아니라는 뜻이고,
#   **부호가 갈리면** 3.7년 통합 수치는 특정 구간이 끌어올린 평균이었다는 뜻이다.
#
# ■ 대조군을 함께 돌린다 — 이게 없으면 결과를 읽을 수 없다
#
#     ATR_BREAKOUT   검증 대상
#     SUPERTREND     '엣지 0' 대조군 — 4,658거래에 총기대값 −0.022
#     MACD           최하위 대조군 — 세 코어 전부에서 가중 0.5 인데 −0.519
#
#   ATR_BREAKOUT 만 일관되고 대조군은 흔들린다  →  **신호다.**
#   셋 다 비슷하게 흔들린다                      →  **노이즈다.** (다) 접는 근거가 된다.
#   셋 다 일관되게 양수                          →  구간 선택이 잘못됐거나 벤치마크 문제다.
#
# ■ 코인 — A구간 13종 전부. 체리피킹 금지
#
#   ATR_BREAKOUT 이 양수였던 코인(XRP·XLM·SOL·ONDO…)만 고르면 그 자체가 과적합이다.
#   09-22 단독 실험이 쓴 A구간 13종을 그대로 쓴다.
#
# ■ 사용법 — 운영 서버에서, 리포 루트에서
#
#     bash scripts/atr_stability_0922.sh --plan     # 제출 계획만
#     bash scripts/atr_stability_0922.sh            # 제출
#     bash scripts/atr_stability_0922.sh --screen   # 구간별 부호 일관성
#
# ■ 읽는 법 — 착수 전에 정해둔다
#
#   ⚠️ 이 실험의 답은 "ATR_BREAKOUT 을 쓰자"가 **아니다.**
#      "3.7년 통합 수치가 국면에 기댄 것인가"에만 답한다.
#   ⚠️ 통과해도 다음 단계는 **비중첩 구간 WF** 이지 실자본이 아니다.
#   🔴 WLD·ONDO·MIRA·WLFI 재배치 금지 (09-18 결론).

set -uo pipefail

case "${1:-}" in
  "") ;;  # 인자 없음 = 제출
  --plan|--screen) ;;
  *)
    echo "✗ 알 수 없는 인자: '$1'"
    echo
    echo "  사용 가능:  (없음) 제출 / --plan 계획만 / --screen 결과"
    echo "  플래그가 안 먹으면 서버 스크립트가 옛 버전입니다 — git pull 후 재실행."
    echo "  (제출은 하지 않았습니다.)"
    exit 2 ;;
esac

API="http://localhost:8080/api/v1"
TIMEFRAME="H1"
FRICTION="0.24"
MIN_TRADES="${MIN_TRADES:-20}"

STRATEGIES_JSON='["ATR_BREAKOUT","SUPERTREND","MACD"]'
STRATEGIES_SQL="'ATR_BREAKOUT','SUPERTREND','MACD'"

# 09-22 단독 실험이 쓴 A구간 13종 — 체리피킹을 막기 위해 그대로 쓴다
COINS_JSON='["KRW-ADA","KRW-ARB","KRW-AVAX","KRW-BTC","KRW-DOGE","KRW-ETH","KRW-LINK","KRW-ONDO","KRW-SOL","KRW-STX","KRW-SUI","KRW-XLM","KRW-XRP"]'
COINS_N=13

PERIODS="2023-01-01|2023-12-31
2024-01-01|2024-12-31
2025-01-01|2025-12-31
2026-01-01|2026-08-30"

psql_q() {
  docker compose -f docker-compose.prod.yml exec -T db \
    psql -U trader -d crypto_auto_trader -At -F'|' -c "$1"
}

# ── --screen: 구간별 부호 일관성 ────────────────────────────────────────────
if [ "${1:-}" = "--screen" ]; then
  CTE="WITH x AS (
        SELECT DISTINCT ON (r.strategy_name, r.coin_pair, r.start_date, r.end_date)
               r.strategy_name AS s, r.coin_pair AS c,
               to_char((r.start_date AT TIME ZONE 'Asia/Seoul')::date,'YYYY') AS yr,
               m.total_trades AS n,
               m.expectancy_pct AS exp,
               m.total_return_pct AS ret
          FROM backtest_run r
          JOIN backtest_metrics m ON m.backtest_run_id = r.id
         WHERE r.is_walk_forward = false
           AND r.timeframe = '${TIMEFRAME}'
           AND r.strategy_name IN (${STRATEGIES_SQL})
           AND (r.start_date AT TIME ZONE 'Asia/Seoul')::date >= DATE '2023-01-01'
           AND COALESCE(m.segment,'FULL') = 'FULL'
         ORDER BY r.strategy_name, r.coin_pair, r.start_date, r.end_date, r.created_at DESC)"

  echo "▶ 0. 기준: 마찰 ${FRICTION}% · 표본 하한 ${MIN_TRADES} · 총기대값 = 순기대값 + ${FRICTION}"
  echo "        전 구간 백테스트이므로 WF 판정은 없다 — 보는 것은 **부호 일관성**이다."
  echo

  echo "▶ 1. 전략 × 연도 — 이 표가 답이다"
  echo "  ATR_BREAKOUT 만 네 해 모두 양수면 신호, 대조군처럼 흔들리면 노이즈."
  psql_q "$CTE
          SELECT s AS 전략, yr AS 연도, count(*) AS 코인수,
                 sum(n) AS 총거래, round(avg(n),1) AS 평균거래,
                 round(avg(exp),3) AS 평균순기대값,
                 round(avg(exp) + ${FRICTION},3) AS 평균총기대값,
                 sum(CASE WHEN exp > 0 THEN 1 ELSE 0 END) || '/' || count(*) AS 양수코인
            FROM x GROUP BY 1,2 ORDER BY 1,2;"
  echo

  echo "▶ 2. 부호 일관성 판정 — 전략별"
  psql_q "$CTE
          , y AS (SELECT s, yr, avg(exp) AS e FROM x GROUP BY 1,2)
          SELECT s AS 전략, count(*) AS 연도수,
                 sum(CASE WHEN e > 0 THEN 1 ELSE 0 END) AS 양수연도,
                 round(min(e),3) || ' ~ ' || round(max(e),3) AS 기대값범위,
                 CASE WHEN min(e) > 0 THEN '🟢 네 해 모두 양수'
                      WHEN max(e) < 0 THEN '일관 음수'
                      ELSE '🔴 부호 갈림' END AS 판정
            FROM y GROUP BY 1 ORDER BY 3 DESC;"
  echo

  echo "▶ 3. ATR_BREAKOUT 코인별 × 연도 — 표본 ≥ ${MIN_TRADES} 만"
  echo "  특정 코인이 평균을 끌어올리고 있는지 본다."
  psql_q "$CTE
          SELECT c AS 코인,
                 max(CASE WHEN yr='2023' THEN round(exp,2)::text END) AS y2023,
                 max(CASE WHEN yr='2024' THEN round(exp,2)::text END) AS y2024,
                 max(CASE WHEN yr='2025' THEN round(exp,2)::text END) AS y2025,
                 max(CASE WHEN yr='2026' THEN round(exp,2)::text END) AS y2026,
                 sum(n) AS 총거래,
                 sum(CASE WHEN exp > 0 THEN 1 ELSE 0 END) || '/' || count(*) AS 양수해
            FROM x WHERE s='ATR_BREAKOUT' AND n >= ${MIN_TRADES}
           GROUP BY 1 ORDER BY 7 DESC, 6 DESC;"
  echo

  echo "▶ 4. 코인별 전 연도 일관 — 세 전략 모두, 표본 ≥ ${MIN_TRADES}"
  psql_q "$CTE
          SELECT s AS 전략, c AS 코인, count(*) AS 해,
                 sum(CASE WHEN exp > 0 THEN 1 ELSE 0 END) AS 양수해,
                 sum(n) AS 총거래,
                 round(min(exp),3) || ' ~ ' || round(max(exp),3) AS 범위,
                 CASE WHEN min(exp) > 0 THEN '🟢 일관 양수'
                      WHEN max(exp) < 0 THEN '일관 음수'
                      ELSE '부호 갈림' END AS 판정
            FROM x WHERE n >= ${MIN_TRADES}
           GROUP BY 1,2 HAVING count(*) >= 3
           ORDER BY CASE WHEN min(exp) > 0 THEN 0 ELSE 1 END, 5 DESC;"
  echo
  echo "⚠️ 이 실험의 답은 'ATR_BREAKOUT 을 쓰자'가 아니다 —"
  echo "   '3.7년 통합 수치가 국면에 기댄 것인가'에만 답한다."
  echo "⚠️ 통과해도 다음은 **비중첩 구간 WF** 이지 실자본이 아니다."
  echo "⚠️ 전 구간 백테스트 수익률은 채택 근거가 아니다 — 보는 것은 기대값 부호다."
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

resp=$(api "$API/backtest/list?size=1")
case "$resp" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요"; exit 1 ;;
esac
echo "✓ 인증 확인"

echo
echo "▶ 제출 계획 — 겹치지 않는 4구간 × 13코인 × 3전략"
echo "─────────────────────────────────────────────────────────────────────────"
TOTAL=0
while IFS='|' read -r sd ed; do
  [ -z "$sd" ] && continue
  c=$(( COINS_N * 3 ))
  TOTAL=$(( TOTAL + c ))
  printf "  %s %s ~ %s   %2s코인 × 3전략 = %2s 조합\n" "$TIMEFRAME" "$sd" "$ed" "$COINS_N" "$c"
done <<< "$PERIODS"
echo "─────────────────────────────────────────────────────────────────────────"
echo "  합계 $TOTAL 조합 (전 구간 백테스트 — WF 아님)"
echo
echo "  전략   ATR_BREAKOUT(검증)  ·  SUPERTREND(엣지0 대조)  ·  MACD(최하위 대조)"
echo "  코인   A구간 13종 전부 — 체리피킹 금지"

echo
echo "▶ 코인별 캔들 보유 확인 (2023-01-01 이전 시작분이 있어야 4구간이 성립한다)"
psql_q "SELECT coin_pair, min(time)::date AS 시작, max(time)::date AS 끝, count(*)
          FROM candle_data
         WHERE timeframe='${TIMEFRAME}'
           AND coin_pair IN ('KRW-ADA','KRW-ARB','KRW-AVAX','KRW-BTC','KRW-DOGE','KRW-ETH',
                             'KRW-LINK','KRW-ONDO','KRW-SOL','KRW-STX','KRW-SUI','KRW-XLM','KRW-XRP')
         GROUP BY 1 ORDER BY 2 DESC;"
echo "  ⚠️ 시작일이 2023 보다 늦은 코인은 그 해 구간이 비거나 짧습니다 — 표본 하한이 걸러냅니다."

if [ "${1:-}" = "--plan" ]; then
  echo
  echo "── --plan 이므로 제출하지 않고 종료합니다."
  exit 0
fi

echo
echo "▶ 제출"
while IFS='|' read -r sd ed; do
  [ -z "$sd" ] && continue
  echo "  · $sd ~ $ed"
  api -X POST "$API/backtest/batch-async" \
    -H 'Content-Type: application/json' \
    -d "{
      \"coinPairs\": $COINS_JSON,
      \"strategyTypes\": $STRATEGIES_JSON,
      \"timeframe\": \"$TIMEFRAME\",
      \"startDate\": \"$sd\",
      \"endDate\": \"$ed\"
    }"
  echo
done <<< "$PERIODS"

echo
echo "─────────────────────────────────────────────────────────────────────────"
echo "제출 완료. 실행은 백그라운드이며 완료 시 텔레그램 알림이 옵니다."
echo
echo "  진행 상황   curl -s -H \"\$AUTH\" $API/backtest/jobs"
echo "  완료 후     bash scripts/atr_stability_0922.sh --screen"
echo
echo "⚠️ 판독 기준 (착수 전에 정한 것):"
echo "    ATR_BREAKOUT 만 네 해 일관 양수 · 대조군은 흔들림  →  신호"
echo "    셋 다 비슷하게 흔들림                              →  노이즈, (다) 접는 근거"
echo "    셋 다 일관 양수                                    →  구간·벤치마크 설계 문제"
