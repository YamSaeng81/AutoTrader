#!/usr/bin/env bash
#
# ATR_BREAKOUT 비중첩 2구간 Walk-Forward — 마지막 관문 (2026-09-22)
# ═════════════════════════════════════════════════════════════════════════════
#
# ■ 여기까지 온 경로
#
#   1. v4 재검증 132조합  →  세 기준(표본≥20 · 순기대값>0 · 기간 교차 일관)
#                            교집합 **0건**. 지표 정밀도로는 해결 안 됨이 확정.
#   2. 단독 vs 복합 207조합 →  필터가 신호를 막고 있었다(단독이 5.7배 거래,
#                            표본충족 31%→83%). 맞대결 단독 13:5 우세.
#                            그런데 **양쪽 다 총기대값 0**.
#                            유일한 예외가 **ATR_BREAKOUT** (+0.268, 1,752거래).
#   3. 비중첩 4년 백테스트  →  ATR_BREAKOUT 만 4/4 양수, 대조군은 2/4.
#                            네 해 내내 대조군을 앞섬. **기준 충족 = 신호.**
#
#   그런데 3 에는 세 가지 유보가 붙었다:
#     ① 단조 감소 — +0.323 → +0.288 → +0.175 → +0.082 (4년에 1/4)
#     ② 대조군도 같은 시간 패턴 — "고유 엣지"와 "국면 내성"이 구별 안 됨
#     ③ 코인 수준 4/4 는 우연 기대치와 같음 (33쌍 × 1/16 ≈ 2.1, 관측 2)
#
# ■ 이 실험이 판별하는 것 — 지금까지 아무 실험도 동시에 만족한 적 없는 조건
#
#   **파라미터 적합 후 OOS 에서 유지되는가(WF)** 와
#   **독립된 두 구간에서 유지되는가(비중첩)** 를 **함께** 본다.
#
#     구간 A   2023-01-01 ~ 2024-12-31   24개월 → OOS 약 7.2개월
#     구간 B   2025-01-01 ~ 2026-08-30   20개월 → OOS 약 6.0개월
#
#   🔴 1년 단위로 쪼개지 않는 이유: WF 는 구간의 ~30% 만 OOS 로 쓴다.
#      1년이면 OOS 가 코인당 ~18거래로 무너져 판정이 불가능하다.
#      ATR_BREAKOUT 은 코인당 연 ~60거래이므로 2구간이면 A≈36 · B≈30 으로
#      하한 20 을 넘긴다.
#
#   ⚠️ **구간 B 가 진짜 시험대다.** 위 ①에서 본 "엣지가 얇아진 구간"이 여기다.
#      B 에서 무너지면 단조 감소가 **추세로 확정**되고, 그때는 (다) 접는 판단이 맞다.
#
# ■ 대조군을 그대로 유지한다
#
#     ATR_BREAKOUT   검증 대상
#     SUPERTREND     '엣지 0' 대조 — 단독 4,658거래에 총기대값 −0.022
#     MACD           최하위 대조 — 세 코어 전부에서 가중 0.5 인데 −0.519
#
#   ②(공통 시장 요인)를 가려내려면 대조군이 반드시 있어야 한다.
#   셋 다 B 에서 무너지면 **시장 요인**이고, ATR 만 버티면 **고유 엣지** 쪽이다.
#
# ■ 코인 — A구간 13종 전부. 체리피킹 금지
#
#   ③ 때문에 더욱 그렇다. ETH 만 4/4 였다고 ETH 만 돌리면 그게 과적합이다.
#
# ■ 사용법 — 운영 서버에서, 리포 루트에서
#
#     bash scripts/atr_wf_split_0922.sh --plan     # 제출 계획만
#     bash scripts/atr_wf_split_0922.sh            # 제출
#     bash scripts/atr_wf_split_0922.sh --screen   # 두 구간 대조 + 최종 판정
#
# ■ 판독 기준 — 착수 전에 정한다
#
#   `--screen` 의 **5번 표가 답**이다. 세 기준을 동시에 만족하는 칸의 수:
#
#     (i)   OOS 표본 ≥ 20          두 구간 모두
#     (ii)  순기대값 > 0           두 구간 모두
#     (iii) verdict 가 OVERFITTING 이 아님   (참고 — 필수 아님)
#
#   | 결과 | 뜻 |
#   |---|---|
#   | ATR 에 (i)+(ii) 통과 칸이 여럿, 대조군은 없음 | **진짜 후보.** 처음 실체가 나온 것 |
#   | ATR 도 0~1 칸, 대조군과 비슷 | **노이즈 확정 → (다) 접는다** |
#   | 구간 A 만 통과, B 에서 전멸 | **엣지 소멸 확정 → (다) 접는다** |
#
#   ⚠️ 통과해도 실자본이 아니다. 다음은 **페이퍼 운용으로 전진 검증**이다.
#      백테스트에서 살아남은 것과 실제로 도는 것은 다르다.
#   ⚠️ 연환산 기여(%)를 함께 낸다 — 기대값이 양수여도 **연 1~2%면 운용 가치가 없다.**
#      거래 비용 가정(슬리피지 0.1%)이 실제보다 낙관적일 여지도 있다.
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
IN_SAMPLE_RATIO=0.7
WINDOW_COUNT=5

STRATEGIES_JSON='["ATR_BREAKOUT","SUPERTREND","MACD"]'
STRATEGIES_SQL="'ATR_BREAKOUT','SUPERTREND','MACD'"

COINS_JSON='["KRW-ADA","KRW-ARB","KRW-AVAX","KRW-BTC","KRW-DOGE","KRW-ETH","KRW-LINK","KRW-ONDO","KRW-SOL","KRW-STX","KRW-SUI","KRW-XLM","KRW-XRP"]'
COINS_N=13

# 구간|시작|종료|연수(연환산용)
PERIODS="A|2023-01-01|2024-12-31|2.00
B|2025-01-01|2026-08-30|1.66"

psql_q() {
  docker compose -f docker-compose.prod.yml exec -T db \
    psql -U trader -d crypto_auto_trader -At -F'|' -c "$1"
}

# ── --screen ────────────────────────────────────────────────────────────────
if [ "${1:-}" = "--screen" ]; then
  CTE="WITH x AS (
        SELECT DISTINCT ON (strategy_name, coin_pair, start_date)
               strategy_name AS s, coin_pair AS c,
               CASE WHEN (start_date AT TIME ZONE 'Asia/Seoul')::date = DATE '2023-01-01'
                    THEN 'A' ELSE 'B' END AS seg,
               CASE WHEN (start_date AT TIME ZONE 'Asia/Seoul')::date = DATE '2023-01-01'
                    THEN 2.00 ELSE 1.66 END AS yrs,
               wf_result_json->>'verdict' AS verdict,
               (wf_result_json->'aggregatedOutSample'->>'totalTrades')::int AS n,
               (wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric AS exp
          FROM backtest_run
         WHERE is_walk_forward
           AND timeframe = '${TIMEFRAME}'
           AND strategy_name IN (${STRATEGIES_SQL})
           AND (start_date AT TIME ZONE 'Asia/Seoul')::date
               IN (DATE '2023-01-01', DATE '2025-01-01')
           AND (end_date AT TIME ZONE 'Asia/Seoul')::date
               IN (DATE '2024-12-31', DATE '2026-08-30')
         ORDER BY strategy_name, coin_pair, start_date, created_at DESC)"

  echo "▶ 0. 기준: 마찰 ${FRICTION}% · 표본 하한 ${MIN_TRADES} · 총기대값 = 순기대값 + ${FRICTION}"
  echo "        구간 A 2023-01-01~2024-12-31 (2.00년) · 구간 B 2025-01-01~2026-08-30 (1.66년)"
  echo "        🔴 구간 B 가 시험대다 — 엣지가 얇아진 구간이다."
  echo

  echo "▶ 1. 전략 × 구간 요약"
  psql_q "$CTE
          SELECT s AS 전략, seg AS 구간, count(*) AS 코인,
                 sum(n) AS 총OOS거래, round(avg(n),1) AS 평균거래,
                 round(avg(exp),3) AS 평균순기대값,
                 round(avg(exp) + ${FRICTION},3) AS 평균총기대값,
                 sum(CASE WHEN exp > 0 THEN 1 ELSE 0 END) || '/' || count(*) AS 양수코인,
                 sum(CASE WHEN n >= ${MIN_TRADES} THEN 1 ELSE 0 END) AS 표본충족
            FROM x GROUP BY 1,2 ORDER BY 1,2;"
  echo

  echo "▶ 2. 구간 A → B 변화 — ②(공통 시장 요인) 판별"
  echo "  셋 다 B 에서 무너지면 시장 요인, ATR 만 버티면 고유 엣지 쪽이다."
  psql_q "$CTE
          , g AS (SELECT s, seg, avg(exp) AS e FROM x GROUP BY 1,2)
          SELECT a.s AS 전략,
                 round(a.e,3) AS 구간A, round(b.e,3) AS 구간B,
                 round(b.e - a.e,3) AS 변화,
                 CASE WHEN b.e > 0 AND a.e > 0 THEN '🟢 두 구간 양수'
                      WHEN b.e > 0 THEN 'B만 양수'
                      WHEN a.e > 0 THEN '🔴 B에서 무너짐'
                      ELSE '두 구간 음수' END AS 판정
            FROM g a JOIN g b ON a.s = b.s AND a.seg='A' AND b.seg='B'
           ORDER BY b.e DESC;"
  echo

  echo "▶ 3. ATR_BREAKOUT 코인별 A vs B"
  psql_q "$CTE
          , p AS (SELECT c,
                    max(CASE WHEN seg='A' THEN n END) AS na,
                    max(CASE WHEN seg='A' THEN exp END) AS ea,
                    max(CASE WHEN seg='A' THEN verdict END) AS va,
                    max(CASE WHEN seg='B' THEN n END) AS nb,
                    max(CASE WHEN seg='B' THEN exp END) AS eb,
                    max(CASE WHEN seg='B' THEN verdict END) AS vb
                  FROM x WHERE s='ATR_BREAKOUT' GROUP BY 1)
          SELECT c AS 코인,
                 na AS A거래, round(ea,3) AS A기대값,
                 nb AS B거래, round(eb,3) AS B기대값,
                 CASE WHEN na >= ${MIN_TRADES} AND nb >= ${MIN_TRADES}
                       AND ea > 0 AND eb > 0 THEN '🟢 통과'
                      WHEN na < ${MIN_TRADES} OR nb < ${MIN_TRADES} THEN '표본부족'
                      ELSE '탈락' END AS 판정
            FROM p ORDER BY 6, 5 DESC NULLS LAST;"
  echo

  echo "▶ 4. verdict 분포"
  psql_q "$CTE
          SELECT s AS 전략, seg AS 구간, verdict, count(*)
            FROM x GROUP BY 1,2,3 ORDER BY 1,2,4 DESC;"
  echo

  echo "▶ 5. 🔴 최종 판정 — 두 구간 모두 (표본 ≥ ${MIN_TRADES} AND 순기대값 > 0)"
  echo "  이 표가 답이다. 지금까지 어느 실험도 이 조건을 만족한 적이 없다."
  psql_q "$CTE
          , p AS (SELECT s, c,
                    max(CASE WHEN seg='A' THEN n END) AS na,
                    max(CASE WHEN seg='A' THEN exp END) AS ea,
                    max(CASE WHEN seg='A' THEN yrs END) AS ya,
                    max(CASE WHEN seg='B' THEN n END) AS nb,
                    max(CASE WHEN seg='B' THEN exp END) AS eb,
                    max(CASE WHEN seg='B' THEN yrs END) AS yb
                  FROM x GROUP BY 1,2)
          SELECT s AS 전략, c AS 코인,
                 na || '/' || nb AS 거래AB,
                 round(ea,3) || ' / ' || round(eb,3) AS 기대값AB,
                 round(ea*na/ya, 2) || ' / ' || round(eb*nb/yb, 2) AS 연환산기여
            FROM p
           WHERE na >= ${MIN_TRADES} AND nb >= ${MIN_TRADES} AND ea > 0 AND eb > 0
           ORDER BY eb*nb/yb DESC;"
  echo
  echo "▶ 6. 통과 칸 집계 — 전략별"
  psql_q "$CTE
          , p AS (SELECT s, c,
                    max(CASE WHEN seg='A' THEN n END) AS na,
                    max(CASE WHEN seg='A' THEN exp END) AS ea,
                    max(CASE WHEN seg='B' THEN n END) AS nb,
                    max(CASE WHEN seg='B' THEN exp END) AS eb
                  FROM x GROUP BY 1,2)
          SELECT s AS 전략,
                 count(*) AS 코인,
                 sum(CASE WHEN na >= ${MIN_TRADES} AND nb >= ${MIN_TRADES}
                          THEN 1 ELSE 0 END) AS 표본충족,
                 sum(CASE WHEN na >= ${MIN_TRADES} AND nb >= ${MIN_TRADES}
                           AND ea > 0 AND eb > 0 THEN 1 ELSE 0 END) AS 통과
            FROM p GROUP BY 1 ORDER BY 4 DESC;"
  echo
  echo "⚠️ 판독 (착수 전에 정한 것):"
  echo "    ATR 에 통과 칸이 여럿 · 대조군은 없음  →  진짜 후보"
  echo "    ATR 도 0~1 칸, 대조군과 비슷           →  노이즈 확정, (다) 접는다"
  echo "    구간 A 만 통과, B 에서 전멸            →  엣지 소멸 확정, (다) 접는다"
  echo
  echo "⚠️ 통과해도 실자본이 아니다 — 다음은 **페이퍼 전진 검증**이다."
  echo "⚠️ 연환산기여가 연 1~2%면 통과여도 운용 가치가 없다. 슬리피지 0.1% 가정이"
  echo "   실제보다 낙관적일 여지도 있다."
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

echo
echo "▶ 제출 계획 — 비중첩 2구간 Walk-Forward"
echo "─────────────────────────────────────────────────────────────────────────"
TOTAL=0
while IFS='|' read -r seg sd ed yrs; do
  [ -z "$seg" ] && continue
  c=$(( COINS_N * 3 ))
  TOTAL=$(( TOTAL + c ))
  printf "  구간 %s  %s ~ %s  (%s년)   %2s코인 × 3전략 = %2s 조합\n" \
         "$seg" "$sd" "$ed" "$yrs" "$COINS_N" "$c"
done <<< "$PERIODS"
echo "─────────────────────────────────────────────────────────────────────────"
echo "  합계 $TOTAL 조합 · IS 비율 $IN_SAMPLE_RATIO · 윈도우 $WINDOW_COUNT"
echo
echo "  전략   ATR_BREAKOUT(검증) · SUPERTREND(엣지0 대조) · MACD(최하위 대조)"
echo "  코인   A구간 13종 전부 — 체리피킹 금지"
echo
echo "  🔴 구간 B 가 시험대다. 거기서 무너지면 엣지 소멸이 확정된다."

if [ "${1:-}" = "--plan" ]; then
  echo
  echo "── --plan 이므로 제출하지 않고 종료합니다."
  exit 0
fi

echo
echo "▶ 제출"
while IFS='|' read -r seg sd ed yrs; do
  [ -z "$seg" ] && continue
  echo "  · 구간 $seg  $sd ~ $ed"
  api -X POST "$API/backtest/walk-forward-batch-async" \
    -H 'Content-Type: application/json' \
    -d "{
      \"coinPairs\": $COINS_JSON,
      \"strategyTypes\": $STRATEGIES_JSON,
      \"timeframe\": \"$TIMEFRAME\",
      \"startDate\": \"$sd\",
      \"endDate\": \"$ed\",
      \"inSampleRatio\": $IN_SAMPLE_RATIO,
      \"windowCount\": $WINDOW_COUNT
    }"
  echo
done <<< "$PERIODS"

echo
echo "─────────────────────────────────────────────────────────────────────────"
echo "제출 완료. 실행은 백그라운드이며 완료 시 텔레그램 알림이 옵니다."
echo
echo "  진행 상황   curl -s -H \"\$AUTH\" $API/backtest/jobs"
echo "  완료 후     bash scripts/atr_wf_split_0922.sh --screen"
echo
echo "⚠️ 5번 표가 답입니다 — 두 구간 모두 (표본 ≥ ${MIN_TRADES} AND 순기대값 > 0)."
echo "   지금까지 어느 실험도 이 조건을 만족한 적이 없습니다."
