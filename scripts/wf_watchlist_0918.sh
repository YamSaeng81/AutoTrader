#!/usr/bin/env bash
#
# 감시 범위 WF 확장 — "전략이 나쁜가, 코인 배치가 나쁜가" (2026-09-18)
# ═════════════════════════════════════════════════════════════════════════════
#
# ■ 왜 이 스크립트가 있나
#
#   09-18 v3 기준선(`rebaseline_wf_0918.sh`)은 메이저 5종에서 25조합을 돌려 **통과 0건**이
#   나왔다. 그런데 그 5종은 **함대가 실제로 사는 코인이 아니다.** 동적 세션은 워치리스트에서
#   코인을 고르고, 최근 6개월 실제 포지션에는 ONDO·TRUMP·ENA·XLM·LA 같은 알트가 섞여 있다.
#
#   09-04 분석의 결론이 정확히 이 지점이었다 —
#     "문제는 전략이 아니라 전략을 잘못 배치한 것"
#     "MTF 계열이 코드에 부적합이라고 적힌 소형 알트만 사고 있다 — 함대 손실 전부"
#
#   즉 지금은 **"전략이 나쁜 것"과 "배치가 나쁜 것"을 구분하지 못한 상태**다.
#   이 구분 없이 Wave 3(지표 계산 정밀도)에 6~16일을 쓰는 건 순서가 틀렸다 — 지표를
#   정확하게 만들어도 배치가 문제면 숫자는 그대로다.
#
#   결과가 갈리는 방식:
#     · 알트에서도 전부 불합격  →  **전략 문제.** Wave 3 의 L(MTF 시각 경계)이 의미를 얻는다
#     · 알트에서 일부 통과      →  **배치 문제.** 코드가 아니라 세션을 갈아끼우는 일이다
#
# ■ 코인 선정 — strategy_log 실측(최근 60일 평가 횟수) ∩ 캔들 보유량
#
#   워치리스트는 매 사이클 메모리에서 계산되고 테이블로 저장되지 않는다. 그래서 "감시 범위"의
#   실측치는 **실제로 평가된 로그**(strategy_log, 60일 236만 건)다.
#
#   🔴 평가는 하는데 캔들이 **0건**인 코인이 많다 — NEAR(3,885회) · KAITO(2,678) · SHIB(2,029) ·
#      O · ONG · ZAMA · SOPH · TREE · CHIP · CAP. **감시는 하면서 검증할 수단이 없다.**
#      이들을 WF 로 판정하려면 먼저 캔들을 수집해야 한다(`scripts/collect_watchlist_candles.sh`).
#      이 스크립트에서는 제외한다 — 넣어 봐야 INSUFFICIENT_DATA 만 쌓인다.
#
#   그리고 캔들이 있어도 **상장 시점이 제각각**이다(PROM 636개 · EUL 1,042개 · GRVT 607개는
#   3년은커녕 몇 달치도 안 된다). 이력 길이가 크게 다른 코인을 한 배치에 섞으면 **서로 다른
#   기간·서로 다른 시장 국면을 비교**하게 되므로 두 묶음으로 나눈다.
#
# ■ 두 묶음은 서로 비교하지 말 것
#
#   A 와 B 는 기간도 윈도우 수도 다르다. **각 묶음 안에서만 비교**하고, 두 묶음을 합쳐
#   "통과율 몇 %" 를 내지 말 것. 그 숫자는 아무 의미가 없다.
#
# ■ 타임프레임은 H1 만
#
#   알트는 M15 캔들이 사실상 없다(ADA·XLM 제외). M15 는 메이저 5종 결과로 갈음한다.
#
# ■ 종료일을 2026-08-30 으로 끊는 이유
#
#   캔들 끝날짜가 코인마다 다르다(XLM·WLD·STX 08-30 / 대부분 08-31~09-07 / ETH·XRP 09-17).
#   **전 코인이 공통으로 가진 마지막 날짜**로 맞춰야 같은 기간을 비교한다.
#
# ■ WF 와 **같은 조건의 백테스트**를 함께 돌린다 — 그리고 반드시 이 순서로 읽는다
#
#   WF 는 `expectancyPct`(거래당 %)와 하락률을 준다. 그런데 그것만으로는 답할 수 없는 게 있다:
#     · 거래가 3.7년에 8건이면 판정이 '통과'여도 운용할 수 없다 → **거래 빈도·총수익·MDD**
#     · 그냥 들고 있는 것보다 나은가 → **단순보유 대비 알파**
#   그래서 전 구간 백테스트를 같은 코인·같은 기간으로 함께 돌린다.
#
#   🔴 **읽는 순서를 지킬 것: WF 판정 먼저, 백테스트는 그 다음.**
#      전 구간 백테스트는 파라미터를 정할 때 본 구간까지 포함한 숫자다 — **채택 근거가 아니다.**
#      WF 가 불합격인데 백테스트가 좋다면 그건 "좋다"가 아니라 **과적합의 크기**다
#      (그 차이를 수치화한 것이 바로 overfittingScore 다). 백테스트는 WF 를 통과한 조합에
#      한해 "규모가 쓸 만한가"를 보는 용도로만 쓴다.
#
# ■ 단순보유 벤치마크 (2026-09-18 실측, H1 종가 기준) — 백테스트 수익률은 이것과 비교해야 한다
#
#   09-14 에 벤치마크를 잘못 잡아 부호까지 뒤집힌 적이 있다("기간·대상이 정확히 겹쳐야 한다").
#   아래는 이 스크립트가 쓰는 **정확히 같은 기간**의 단순보유 수익률이다.
#
#   A 구간 2023-01-01 ~ 2026-08-30 — 편차가 극단적이다
#     SOL +1009.1% · BTC +412.4% · XRP +336.0% · XLM +176.9% · ETH +120.4% · LINK +119.8%
#     DOGE  +29.1% · STX  +27.9% · ADA  −13.8% · AVAX −28.5% · SUI  −47.3%
#     ONDO  −74.1% · ARB  −93.4%
#
#   🔴 B 구간 2025-10-01 ~ 2026-08-30 — **6종 전부 폭락했다**
#     TRUMP −69.2% · WLD −70.3% · WLFI −71.4% · ENA −74.2% · LA −84.2% · MIRA −92.9%
#
#   B 의 의미를 놓치지 말 것: **함대가 실제로 사는 종목군이 이 구간에 전멸했다.**
#   여기서는 백테스트 수익률이 −20% 여도 시장을 50%p 이긴 것이다. 반대로 A 의 SOL 은
#   +300% 를 내도 단순보유(+1009%)에 크게 진 것이다. **절대 수익률만 보면 양방향으로 오독한다.**
#
# ■ 사용법 — 운영 서버에서, 리포 루트에서
#
#     bash scripts/wf_watchlist_0918.sh            # 제출
#     bash scripts/wf_watchlist_0918.sh --verify   # 결과 확인 (v3 여부 + 판정 분포)
#
#   57 조합이라 25 조합(약 48분)의 두 배 남짓 걸린다. 완료 시 텔레그램 알림이 온다.

set -uo pipefail

API="http://localhost:8080/api/v1"

# 운영 중인 3전략 (rebaseline_wf_0918.sh 와 동일)
STRATEGIES='["COMPOSITE_MEANREV_BB","COMPOSITE_MOMENTUM_ICHIMOKU_V2","COMPOSITE_MTF_CONFIRMED"]'

END_DATE="2026-08-30"

# ── A: 장기 이력 13종 (H1 캔들 19,000건 이상 · 2024-06 이전 상장) ───────────
#    KRW-USDT 는 캔들이 19,505건으로 조건을 만족하지만 제외한다 — 스테이블코인이라
#    변동성 구조가 근본적으로 다르고(ATR≈0), 다른 코인과 같은 표본에 섞으면 안 된다.
COINS_A='["KRW-BTC","KRW-ETH","KRW-XRP","KRW-SOL","KRW-ADA","KRW-DOGE","KRW-LINK","KRW-AVAX","KRW-XLM","KRW-STX","KRW-SUI","KRW-ARB","KRW-ONDO"]'
START_A="2023-01-01"
WINDOWS_A=5

# ── B: 2025년 상장 6종 (H1 8,000~13,500건) ─────────────────────────────────
#    전 코인이 데이터를 가진 2025-10-01 부터. 기간이 짧아 윈도우를 3 으로 줄인다 —
#    5 로 쪼개면 창당 OOS 구간이 2개월도 안 되고 거래가 하한(5건)에 못 미쳐
#    INSUFFICIENT_DATA 만 나온다.
COINS_B='["KRW-TRUMP","KRW-ENA","KRW-WLD","KRW-LA","KRW-MIRA","KRW-WLFI"]'
START_B="2025-10-01"
WINDOWS_B=3

# ── 인증 ─────────────────────────────────────────────────────────────────────
if [ -z "${API_AUTH_TOKEN:-}" ] && [ -f .env ]; then
  API_AUTH_TOKEN=$(grep -E '^API_AUTH_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"'"'"'')
fi
if [ -z "${API_AUTH_TOKEN:-}" ]; then
  echo "✗ API_AUTH_TOKEN 을 찾을 수 없습니다 (.env 또는 환경변수)."
  exit 1
fi
AUTH="Authorization: Bearer $API_AUTH_TOKEN"
api() { curl -s -H "$AUTH" "$@"; }

psql_q() {
  docker compose -f docker-compose.prod.yml exec -T db \
    psql -U trader -d crypto_auto_trader -At -F' | ' -c "$1"
}

# ── --verify ─────────────────────────────────────────────────────────────────
if [ "${1:-}" = "--verify" ]; then
  echo "▶ 오늘 저장된 WF 실행 — 버전별"
  psql_q "SELECT COALESCE(exit_rules_version::text,'NULL'), count(*)
            FROM backtest_run WHERE is_walk_forward AND created_at::date=CURRENT_DATE
           GROUP BY 1 ORDER BY 1;"
  echo
  echo "▶ 판정 분포"
  psql_q "SELECT wf_result_json->>'verdict' AS verdict, count(*)
            FROM backtest_run WHERE is_walk_forward AND created_at::date=CURRENT_DATE
           GROUP BY 1 ORDER BY 2 DESC;"
  echo
  echo "▶ 통과·주의 조합 (여기에 뭐라도 있으면 '배치 문제'라는 뜻이다)"
  psql_q "SELECT strategy_name, coin_pair, timeframe,
                 wf_result_json->>'verdict',
                 round((wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric,3),
                 wf_result_json->'aggregatedOutSample'->>'totalTrades'
            FROM backtest_run
           WHERE is_walk_forward AND created_at::date=CURRENT_DATE
             AND wf_result_json->>'verdict' IN ('ACCEPTABLE','CAUTION')
           ORDER BY 5 DESC;"
  echo
  echo "▶ 전 구간 백테스트 — 총수익률 상위 15 (⚠️ 위 WF 판정을 먼저 본 뒤에 읽을 것)"
  echo "   열: 전략 | 코인 | 총수익% | MDD% | 거래수 | 승률% | PF | 기대값%"
  echo "   ⚠️ 총수익%% 는 **단순보유 대비**로 읽어야 한다 — 벤치마크는 이 파일 헤더에 있다."
  psql_q "SELECT r.strategy_name, r.coin_pair,
                 round(m.total_return_pct,1), round(m.mdd_pct,1), m.total_trades,
                 round(m.win_rate_pct,1), round(m.profit_factor,2), round(m.expectancy_pct,3)
            FROM backtest_run r JOIN backtest_metrics m ON m.backtest_run_id = r.id
           WHERE NOT COALESCE(r.is_walk_forward,false) AND r.created_at::date=CURRENT_DATE
           ORDER BY m.total_return_pct DESC NULLS LAST LIMIT 15;"
  echo
  echo "▶ 전 구간 백테스트 — 거래가 너무 적어 운용 불가인 조합 (30건 미만)"
  psql_q "SELECT r.strategy_name, r.coin_pair, m.total_trades, round(m.total_return_pct,1)
            FROM backtest_run r JOIN backtest_metrics m ON m.backtest_run_id = r.id
           WHERE NOT COALESCE(r.is_walk_forward,false) AND r.created_at::date=CURRENT_DATE
             AND COALESCE(m.total_trades,0) < 30
           ORDER BY m.total_trades ASC;"
  echo
  echo "▶ OOS 기대값 상위 10 (판정과 무관하게)"
  psql_q "SELECT strategy_name, coin_pair,
                 wf_result_json->>'verdict',
                 round((wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric,3) AS exp,
                 wf_result_json->'aggregatedOutSample'->>'totalTrades' AS n
            FROM backtest_run
           WHERE is_walk_forward AND created_at::date=CURRENT_DATE
           ORDER BY (wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric DESC NULLS LAST
           LIMIT 10;"
  exit 0
fi

# ── 사전 점검 ────────────────────────────────────────────────────────────────
resp=$(api "$API/backtest/walk-forward/history")
case "$resp" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요"; exit 1 ;;
esac
echo "✓ 인증 확인"

echo
echo "  제출 예정 — 전부 H1, 전략 3종:"
echo "    A  장기 13코인  $START_A ~ $END_DATE  윈도우 $WINDOWS_A  → 39 조합"
echo "    B  2025상장 6코인  $START_B ~ $END_DATE  윈도우 $WINDOWS_B  → 18 조합"
echo "    ⚠️ A 와 B 는 기간·윈도우가 달라 서로 비교할 수 없다. 묶음 안에서만 비교할 것."

submit() {
  local coins="$1" start="$2" windows="$3"
  api -X POST "$API/backtest/walk-forward-batch-async" \
    -H 'Content-Type: application/json' \
    -d "{
      \"coinPairs\": $coins,
      \"strategyTypes\": $STRATEGIES,
      \"timeframe\": \"H1\",
      \"startDate\": \"$start\",
      \"endDate\": \"$END_DATE\",
      \"inSampleRatio\": 0.7,
      \"windowCount\": $windows
    }"
}

echo
echo "▶ Job A: 장기 13코인 × 3전략 = 39 조합"
submit "$COINS_A" "$START_A" "$WINDOWS_A"
echo

echo "▶ Job B: 2025상장 6코인 × 3전략 = 18 조합"
submit "$COINS_B" "$START_B" "$WINDOWS_B"
echo

submit_bt() {
  local coins="$1" start="$2"
  api -X POST "$API/backtest/batch-async"     -H 'Content-Type: application/json'     -d "{
      \"coinPairs\": $coins,
      \"strategyTypes\": $STRATEGIES,
      \"timeframe\": \"H1\",
      \"startDate\": \"$start\",
      \"endDate\": \"$END_DATE\"
    }"
}

echo "▶ Job C: 전 구간 백테스트 — A 와 동일 조건 (39 조합)"
submit_bt "$COINS_A" "$START_A"
echo

echo "▶ Job D: 전 구간 백테스트 — B 와 동일 조건 (18 조합)"
submit_bt "$COINS_B" "$START_B"
echo

echo
echo "─────────────────────────────────────────────────────────────────────────"
echo "제출 완료 (WF 57 + 백테스트 57 = 114 조합). 완료 시 텔레그램 알림이 옵니다."
echo
echo "  진행 상황   curl -s -H \"\$AUTH\" $API/backtest/jobs"
echo "  완료 후     bash scripts/wf_watchlist_0918.sh --verify"
echo
echo "판독 기준 — 반드시 이 순서로:"
echo "  1) WF 판정을 먼저 본다."
echo "     통과·주의가 하나라도 나온다  ->  배치 문제. 세션을 갈아끼우는 것이 먼저다."
echo "     전부 불합격                  ->  전략 문제. Wave 3 의 L(MTF 시각 경계)부터 착수."
echo "  2) 그 다음에만 백테스트를 본다. 단순보유 대비로 읽을 것 (벤치마크는 이 파일 헤더에)."
echo "     WF 불합격 + 백테스트 좋음 = '좋다'가 아니라 '과적합이 크다'는 뜻이다."
