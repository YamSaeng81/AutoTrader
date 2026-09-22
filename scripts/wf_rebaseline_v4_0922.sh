#!/usr/bin/env bash
#
# 백테스트 규칙 v4 재검증 기준선 만들기 (2026-09-22)
# ═════════════════════════════════════════════════════════════════════════════
#
# ■ 왜 이 스크립트가 있나
#
#   Wave 3 의 L·I·H 를 고치면서 BACKTEST_RULESET_VERSION 을 3 → 4 로 올렸다.
#   세 수정 모두 **같은 입력에 대해 백테스트가 다른 결과를 내게 만든다.** 따라서
#   09-18 에 만든 v3 결과 82건은 전부 무효다.
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
# ■ 🔴 기간과 코인을 v3 실행에서 **그대로 복제한다** — 이 스크립트의 핵심
#
#   초판(09-22 오전)은 코인을 메이저 5종으로 고정하고 종료일을 "캔들 보유 기준
#   자동 계산"했다. **둘 다 틀렸다.**
#
#   · 종료일 자동 계산이 위험했다 — v3 는 2026-09-07 로 끊겨 있는데 백필로 캔들이
#     늘어난 지금 자동 계산하면 더 늦은 날짜가 잡힌다. 그러면 v3↔v4 차이에
#     **"규칙이 바꾼 것"과 "기간이 13일 늘어난 것"이 섞인다.** 비교가 목적인
#     스크립트에서 기간을 움직이면 그 비교가 무의미해진다.
#
#   · 코인 5종 고정이 손해였다 — v3 결과는 이미 **19코인**에 대해 존재한다
#     (rebaseline 25건 + wf_watchlist 57건 = 82건). 그리고 워치리스트가 쓴 3전략이
#     Job 1 의 3전략과 정확히 같다. 5종으로 줄이면 비교 가능한 칸을 57 → 15 로
#     스스로 버리는 셈이다.
#
#   그래서 Job 1·3 은 **하드코딩하지 않는다.** DB 에서 v3 실행의
#   (기간, 코인) 조합을 읽어 같은 기간·같은 코인으로 다시 제출한다.
#   기간이 여러 묶음이면(워치리스트는 상장 시점 때문에 A/B 두 구간을 썼다)
#   묶음별로 따로 제출한다 — 이력 길이가 다른 코인을 한 배치에 섞으면 서로 다른
#   시장 국면을 비교하게 된다.
#
#   ⚠️ 따라서 이 스크립트는 **v3 결과가 DB 에 있어야 동작한다.** 없으면 중단한다.
#
# ■ Job 2 만 코인을 고정한다
#
#   Job 2 는 v3 대응짝이 없는 프리셋들이라 "복제할 원본"이 없다. 비교가 아니라
#   **v4 출발점을 만드는 것**이 목적이므로 메이저 5종으로 제한한다. 9전략 × 19코인
#   (171조합)을 돌리면 시간이 크게 늘어나는데, 비교 대상도 없는 숫자에 그만큼
#   쓸 이유가 없다. 기간은 Job 1 에서 BTC 가 속한 묶음을 따라간다.
#
# ■ 알트 확장은 별건이다
#
#   ⚠️ 이 기준선은 v3 가 덮은 범위만 덮는다. 동적 세션은 그 밖의 코인도 산다.
#      메이저 통과가 실매매 적합성을 뜻하지 않는다 (09-04 분석 참조).
#      🔴 WLD·ONDO·MIRA·WLFI 로 재배치 금지 (09-18 결론).
#
# ■ 사용법 — 운영 서버에서, 리포 루트에서
#
#     bash scripts/wf_rebaseline_v4_0922.sh --plan     # 무엇을 제출할지만 출력
#     bash scripts/wf_rebaseline_v4_0922.sh            # 제출
#     bash scripts/wf_rebaseline_v4_0922.sh --verify   # 결과가 v4 로 저장됐는지 확인
#     bash scripts/wf_rebaseline_v4_0922.sh --detail   # 🔴 verdict 가 아니라 수치를 대조
#     bash scripts/wf_rebaseline_v4_0922.sh --jobs2    # v3 대응짝 없는 프리셋 결과
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

# ── 🔴 모르는 인자는 즉시 중단한다 ───────────────────────────────────────────
#    2026-09-22 사고: `--detail` 을 추가한 커밋이 서버에 아직 없는 상태에서
#    `--detail` 로 실행했더니, 인자가 어느 분기에도 안 걸려 **그대로 제출 경로로
#    떨어져 132조합이 재제출됐다.** 결과는 결정적이라 데이터는 안 깨졌지만
#    연산 시간을 통째로 버렸다.
#
#    제출은 이 스크립트에서 가장 비싼 동작이다. **인자 없음** 하나만 제출로 간주하고,
#    나머지는 전부 명시적으로 허용된 것만 통과시킨다. 오타나 미배포 플래그가
#    조용히 제출로 이어지면 안 된다.
case "${1:-}" in
  "") ;;  # 인자 없음 = 제출
  --plan|--verify|--detail|--jobs2|--screen) ;;
  *)
    echo "✗ 알 수 없는 인자: '$1'"
    echo
    echo "  사용 가능:"
    echo "    (없음)     제출"
    echo "    --plan     무엇을 제출할지만 출력"
    echo "    --verify   결과가 v4 로 저장됐는지 확인 + v3↔v4 판정 대조"
    echo "    --detail   verdict 가 아니라 수치(거래수·기대값·과적합점수) 대조"
    echo "    --jobs2    v3 대응짝 없는 프리셋 결과"
    echo "    --screen   엣지가 있는 칸이 하나라도 있는가 (마찰비용 기준 선별)"
    echo
    echo "  이 플래그가 있어야 하는데 안 먹는다면 서버 쪽 스크립트가 옛 버전입니다 —"
    echo "  git pull 후 다시 실행하세요. (제출은 하지 않았습니다.)"
    exit 2 ;;
esac

API="http://localhost:8080/api/v1"

IN_SAMPLE_RATIO=0.7
WINDOW_COUNT=5

# Job 1·3 — v3 실행을 복제할 대상 전략 (SQL IN 절 / JSON 배열 두 형태)
BASE_SQL="'COMPOSITE_MEANREV_BB','COMPOSITE_MOMENTUM_ICHIMOKU_V2','COMPOSITE_MTF_CONFIRMED'"
BASE_JSON='["COMPOSITE_MEANREV_BB","COMPOSITE_MOMENTUM_ICHIMOKU_V2","COMPOSITE_MTF_CONFIRMED"]'

# Job 2 — v4 로 새로 무효가 된 나머지 프리셋 (COMPOSITE_ETH·MTF_BTC_STRICT 제외)
J2_JSON='["COMPOSITE","COMPOSITE_REGIME_ROUTER","COMPOSITE_MOMENTUM","COMPOSITE_MOMENTUM_ICHIMOKU","COMPOSITE_BREAKOUT","COMPOSITE_BREAKOUT_ICHIMOKU","COMPOSITE_MTF_BTC","COMPOSITE_MTF_MOMENTUM","COMPOSITE_PULLBACK_MTF"]'
J2_COINS='["KRW-BTC","KRW-ETH","KRW-SOL","KRW-DOGE","KRW-XRP"]'

psql_q() {
  docker compose -f docker-compose.prod.yml exec -T db \
    psql -U trader -d crypto_auto_trader -At -F'|' -c "$1"
}

# ── --verify: 결과가 v4 로 저장됐는지 확인 ───────────────────────────────────
#    대조는 (전략·코인·타임프레임·기간) 네 가지가 모두 같은 칸끼리만 한다.
#    초판은 기간을 빼고 맞춰서 wf_watchlist 결과까지 섞여 나왔다.
if [ "${1:-}" = "--verify" ]; then
  echo "▶ WF 실행의 규칙 버전 분포 (NULL = 09-08 이전)"
  psql_q "SELECT COALESCE(exit_rules_version::text,'NULL') AS v, count(*), max(created_at)::date
            FROM backtest_run WHERE is_walk_forward GROUP BY 1 ORDER BY 1;"
  echo
  echo "▶ 오늘 저장된 실행의 버전 (여기가 전부 4 여야 한다)"
  psql_q "SELECT COALESCE(exit_rules_version::text,'NULL') AS v, timeframe, count(*)
            FROM backtest_run
           WHERE is_walk_forward AND created_at::date = CURRENT_DATE
           GROUP BY 1,2 ORDER BY 1,2;"
  echo
  echo "▶ v3 → v4 대조 — 전략·코인·타임프레임·기간이 모두 같은 칸만"
  echo "  (v4 열이 비어 있으면 그 칸은 아직 재실행되지 않은 것)"
  psql_q "SELECT v3.timeframe, v3.strategy_name, v3.coin_pair,
                 (v3.start_date AT TIME ZONE 'Asia/Seoul')::date || '~'
                 || (v3.end_date AT TIME ZONE 'Asia/Seoul')::date AS 기간,
                 v3.verdict AS v3, COALESCE(v4.verdict,'-') AS v4,
                 CASE WHEN v4.verdict IS NULL THEN ''
                      WHEN v3.verdict = v4.verdict THEN '='
                      ELSE '변동' END AS diff
            FROM (SELECT DISTINCT ON (strategy_name, coin_pair, timeframe, start_date, end_date)
                         strategy_name, coin_pair, timeframe, start_date, end_date,
                         wf_result_json->>'verdict' AS verdict
                    FROM backtest_run
                   WHERE is_walk_forward AND exit_rules_version = 3
                   ORDER BY strategy_name, coin_pair, timeframe, start_date, end_date,
                            created_at DESC) v3
            LEFT JOIN (SELECT DISTINCT ON (strategy_name, coin_pair, timeframe, start_date, end_date)
                         strategy_name, coin_pair, timeframe, start_date, end_date,
                         wf_result_json->>'verdict' AS verdict
                    FROM backtest_run
                   WHERE is_walk_forward AND exit_rules_version = 4
                   ORDER BY strategy_name, coin_pair, timeframe, start_date, end_date,
                            created_at DESC) v4
              ON  v3.strategy_name = v4.strategy_name
              AND v3.coin_pair     = v4.coin_pair
              AND v3.timeframe     = v4.timeframe
              AND v3.start_date    = v4.start_date
              AND v3.end_date      = v4.end_date
           ORDER BY 1,2,3;"
  echo
  echo "▶ 판정 변동 요약"
  psql_q "SELECT v3.verdict AS v3, COALESCE(v4.verdict,'(미실행)') AS v4, count(*)
            FROM (SELECT DISTINCT ON (strategy_name, coin_pair, timeframe, start_date, end_date)
                         strategy_name, coin_pair, timeframe, start_date, end_date,
                         wf_result_json->>'verdict' AS verdict
                    FROM backtest_run WHERE is_walk_forward AND exit_rules_version = 3
                   ORDER BY strategy_name, coin_pair, timeframe, start_date, end_date,
                            created_at DESC) v3
            LEFT JOIN (SELECT DISTINCT ON (strategy_name, coin_pair, timeframe, start_date, end_date)
                         strategy_name, coin_pair, timeframe, start_date, end_date,
                         wf_result_json->>'verdict' AS verdict
                    FROM backtest_run WHERE is_walk_forward AND exit_rules_version = 4
                   ORDER BY strategy_name, coin_pair, timeframe, start_date, end_date,
                            created_at DESC) v4
              ON  v3.strategy_name = v4.strategy_name AND v3.coin_pair = v4.coin_pair
              AND v3.timeframe = v4.timeframe
              AND v3.start_date = v4.start_date AND v3.end_date = v4.end_date
           GROUP BY 1,2 ORDER BY 3 DESC;"
  echo
  echo "  v 가 3 이나 NULL 이면 컨테이너가 옛 이미지입니다 — 재빌드 후 다시 제출하세요."
  echo "  🔴 verdict 가 같아도 수치는 바뀌었을 수 있습니다 — --detail 로 확인하세요."
  exit 0
fi

# ── --detail: verdict 가 아니라 **수치**를 대조한다 ─────────────────────────
#    🔴 이게 필요한 이유: verdict 는 이산값이라 OVERFITTING 안에서 기대값이
#       −5% → −1% 로 움직여도 똑같이 OVERFITTING 으로 나온다. verdict 만 보면
#       "v4 가 아무것도 바꾸지 않았다"고 오독한다. v4 수정(특히 GRID 침묵 해제)이
#       실제로 작동했는지는 **OOS 거래 수**와 **기대값**에서 드러난다.
if [ "${1:-}" = "--detail" ]; then
  echo "▶ v3 → v4 수치 대조 — OOS 거래수 · 기대값(%) · 과적합점수"
  echo "  거래수가 늘었으면 GRID 침묵 해제(I) 또는 RSI/VD 중립화(H)가 신호를 늘린 것"
  echo
  psql_q "WITH x AS (
            SELECT DISTINCT ON (exit_rules_version, strategy_name, coin_pair, timeframe,
                                start_date, end_date)
                   exit_rules_version AS v, strategy_name, coin_pair, timeframe,
                   start_date, end_date,
                   wf_result_json->>'verdict' AS verdict,
                   (wf_result_json->'aggregatedOutSample'->>'totalTrades')::int AS trades,
                   round((wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric, 3) AS exp,
                   round((wf_result_json->>'overfittingScore')::numeric, 2) AS ofs
              FROM backtest_run
             WHERE is_walk_forward AND exit_rules_version IN (3,4)
             ORDER BY exit_rules_version, strategy_name, coin_pair, timeframe,
                      start_date, end_date, created_at DESC)
          SELECT a.timeframe, a.strategy_name, a.coin_pair,
                 (a.start_date AT TIME ZONE 'Asia/Seoul')::date AS 시작,
                 a.trades || '→' || b.trades       AS 거래수,
                 a.exp    || '→' || b.exp          AS 기대값,
                 a.ofs    || '→' || b.ofs          AS 과적합,
                 CASE WHEN a.trades IS DISTINCT FROM b.trades
                       OR a.exp    IS DISTINCT FROM b.exp  THEN '●' ELSE '' END AS 변화
            FROM x a JOIN x b
              ON a.v=3 AND b.v=4
             AND a.strategy_name=b.strategy_name AND a.coin_pair=b.coin_pair
             AND a.timeframe=b.timeframe
             AND a.start_date=b.start_date AND a.end_date=b.end_date
           ORDER BY 1,2,3,4;"
  echo
  echo "▶ 수치가 실제로 움직인 칸이 몇 개인가 (verdict 동일 여부와 무관)"
  psql_q "WITH x AS (
            SELECT DISTINCT ON (exit_rules_version, strategy_name, coin_pair, timeframe,
                                start_date, end_date)
                   exit_rules_version AS v, strategy_name, coin_pair, timeframe,
                   start_date, end_date,
                   wf_result_json->>'verdict' AS verdict,
                   (wf_result_json->'aggregatedOutSample'->>'totalTrades')::int AS trades,
                   (wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric AS exp
              FROM backtest_run
             WHERE is_walk_forward AND exit_rules_version IN (3,4)
             ORDER BY exit_rules_version, strategy_name, coin_pair, timeframe,
                      start_date, end_date, created_at DESC)
          SELECT CASE WHEN a.verdict = b.verdict THEN 'verdict 동일' ELSE 'verdict 변동' END AS v,
                 CASE WHEN a.trades IS DISTINCT FROM b.trades
                       OR a.exp IS DISTINCT FROM b.exp THEN '수치 변동' ELSE '수치 동일' END AS m,
                 count(*)
            FROM x a JOIN x b
              ON a.v=3 AND b.v=4
             AND a.strategy_name=b.strategy_name AND a.coin_pair=b.coin_pair
             AND a.timeframe=b.timeframe
             AND a.start_date=b.start_date AND a.end_date=b.end_date
           GROUP BY 1,2 ORDER BY 3 DESC;"
  echo
  echo "  🔴 'verdict 동일 · 수치 동일' 이 대다수면 v4 수정이 이 조합들에 닿지 않았다는 뜻입니다."
  echo "     그 경우 무엇이 막고 있는지(필터가 먼저 HOLD 를 내는지 등) 따로 봐야 합니다."
  exit 0
fi

# ── --screen: 엣지가 있는 칸이 하나라도 있는가 ──────────────────────────────
#
#    🔴 왜 이 모드가 있나 — 질문이 바뀌었다 (2026-09-22)
#
#    Wave 0~3 에서 실제 버그 10여 건을 고쳤다(미래 참조·손익 회계·전략 정의 3중화·
#    MTF 경계·GRID 침묵·무변동 경계). 전부 뮤테이션으로 검증한 진짜 결함이었다.
#    그런데 **기대값은 0.1%p 움직였다.** L 수정으로 과적합점수가 4.49→1.42 로
#    내려간 ETH 조차 기대값은 −1.245 → −1.063 이다.
#
#    즉 "지표를 정확하게 만들어 WF 를 통과시킨다"는 경로는 실측으로 부정됐다.
#    Wave 3-K(ADX Wilder 평활)는 같은 종류의 변경이므로 착수하지 않는다.
#
#    대신 물어야 할 것: **엣지가 있는 칸이 하나라도 있는가.**
#    이건 코드 작업이 아니라 선별 작업이다.
#
#    ■ 마찰비용 = 자본 대비 왕복 0.24%
#
#      수수료 0.05% × 2 + 슬리피지 0.1% × 2 = 포지션 대비 0.30%
#      investRatio = 0.80 (ExitRuleConfig, 리스크 사이징 기본 비활성)
#      → 자본 대비 0.30% × 0.80 = 0.24%
#
#      expectancyPct 는 `totalReturnPct / totalTrades` 로 **초기자본 대비**이고
#      수수료·슬리피지가 이미 차감된 순액이다(BacktestEngine:189).
#      그래서 **총기대값 ≈ 순기대값 + 0.24** 로 환산된다.
#
#      실측 검증: COMPOSITE_PULLBACK_MTF / KRW-BTC 는 95거래에 순기대값 −0.241 이다.
#      환산하면 총기대값 −0.001 — **소수점 셋째 자리까지 0.** 지는 전략이 아니라
#      **방향성이 없는 신호가 통행료만 내고 있는 것**이다.
#
#    ■ 선별 기준 세 가지
#
#      1. 표본     OOS 거래 ≥ MIN_TRADES (기본 20). 게이트 하한 5 는 너무 낮다 —
#                  09-18 에 "표본 5~14 인 통과 6건이 전부 덫"이었다.
#      2. 순수익   순기대값 > 0. 마찰을 넘었다는 뜻이다(순액이므로 부등호만 보면 된다).
#      3. 일관성   같은 (전략·코인·타임프레임)이 여러 기간에서 부호가 유지되는가.
#                  한 기간에서만 양수면 그 기간을 뽑은 것일 수 있다.
#
#    ■ 읽는 법
#
#      ⚠️ 여기 올라온 칸도 WF 판정은 대부분 OVERFITTING 이다. 그건 "수익이 없다"가
#         아니라 **"학습 구간 성과가 검증 구간으로 이어지지 않는다"**는 뜻이다.
#         OOS 에서 표본 있게 양수라면 그 자체가 정보다.
#      ⚠️ 그래도 **채택 근거는 아니다.** 여기서 나온 후보는 "다음에 볼 곳"이지
#         "실자본을 넣을 곳"이 아니다. 🔴 WLD·ONDO·MIRA·WLFI 재배치 금지(09-18).
#      ⚠️ 후보가 0 건이면 결론은 명확하다 — **이 프리셋 조합에는 엣지가 없다.**
#         지표를 더 손봐도 소용없다.
if [ "${1:-}" = "--screen" ]; then
  MIN_TRADES="${MIN_TRADES:-20}"
  FRICTION="0.24"

  echo "▶ 선별 기준"
  echo "    마찰비용   자본 대비 왕복 ${FRICTION}%  (수수료 0.05×2 + 슬리피지 0.1×2, investRatio 0.80)"
  echo "    표본 하한  OOS 거래 ≥ ${MIN_TRADES}      (게이트 하한 5 는 너무 낮다)"
  echo "    총기대값   순기대값 + ${FRICTION}"
  echo "    (하한 변경: MIN_TRADES=30 bash scripts/wf_rebaseline_v4_0922.sh --screen)"
  echo

  SCREEN_CTE="WITH x AS (
        SELECT DISTINCT ON (strategy_name, coin_pair, timeframe, start_date, end_date)
               strategy_name AS s, coin_pair AS c, timeframe AS tf,
               (start_date AT TIME ZONE 'Asia/Seoul')::date AS sd,
               (end_date   AT TIME ZONE 'Asia/Seoul')::date AS ed,
               wf_result_json->>'verdict' AS verdict,
               (wf_result_json->'aggregatedOutSample'->>'totalTrades')::int AS n,
               (wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric AS exp
          FROM backtest_run
         WHERE is_walk_forward AND exit_rules_version = 4
         ORDER BY strategy_name, coin_pair, timeframe, start_date, end_date, created_at DESC)"

  echo "▶ 1. 깔때기 — 몇 칸이 남는가"
  psql_q "$SCREEN_CTE
          SELECT '전체 v4 칸'                         AS 단계, count(*) FROM x
          UNION ALL SELECT '① 표본 ≥ ${MIN_TRADES}',   count(*) FROM x WHERE n >= ${MIN_TRADES}
          UNION ALL SELECT '② + 순기대값 > 0',          count(*) FROM x WHERE n >= ${MIN_TRADES} AND exp > 0
          UNION ALL SELECT '③ + 판정이 OVERFITTING 아님', count(*) FROM x
                     WHERE n >= ${MIN_TRADES} AND exp > 0 AND verdict <> 'OVERFITTING';"
  echo

  echo "▶ 2. 후보 — 표본 ≥ ${MIN_TRADES} 이고 순기대값 > 0"
  echo "  (총기대값 = 마찰을 되돌린 값. 신호 자체의 방향성 크기)"
  psql_q "$SCREEN_CTE
          SELECT s AS 전략, c AS 코인, tf, sd || '~' || ed AS 기간,
                 n AS 거래, round(exp,3) AS 순기대값,
                 round(exp + ${FRICTION}, 3) AS 총기대값,
                 round(exp * n, 2) AS 기여합계,
                 verdict
            FROM x
           WHERE n >= ${MIN_TRADES} AND exp > 0
           ORDER BY exp DESC;"
  echo

  echo "▶ 3. 기간 교차 일관성 — 같은 (전략·코인·tf)가 여러 기간에 걸쳐 있는 경우"
  echo "  부호가 갈리면 '기간을 뽑은 것'이다. 일관되게 양수여야 후보로 볼 값이 있다."
  psql_q "$SCREEN_CTE
          SELECT s AS 전략, c AS 코인, tf,
                 count(*) AS 기간수,
                 sum(CASE WHEN exp > 0 THEN 1 ELSE 0 END) AS 양수,
                 min(n) || '~' || max(n) AS 거래범위,
                 round(min(exp),3) || ' ~ ' || round(max(exp),3) AS 기대값범위,
                 CASE WHEN min(exp) > 0 THEN '일관 양수'
                      WHEN max(exp) < 0 THEN '일관 음수'
                      ELSE '🔴 부호 갈림' END AS 판정
            FROM x
           GROUP BY 1,2,3 HAVING count(*) > 1
           ORDER BY CASE WHEN min(exp) > 0 THEN 0 ELSE 1 END, min(exp) DESC;"
  echo

  echo "▶ 4. '엣지 0' 구간 — 총기대값이 ±0.05 안, 표본 ≥ 30"
  echo "  🔴 여기 걸린 칸은 방향성이 없는 신호가 통행료만 내고 있는 것이다."
  echo "     지표를 정밀하게 만들어도 0 은 0 이다."
  psql_q "$SCREEN_CTE
          SELECT s AS 전략, c AS 코인, tf, n AS 거래,
                 round(exp,3) AS 순기대값,
                 round(exp + ${FRICTION},3) AS 총기대값
            FROM x
           WHERE n >= 30 AND abs(exp + ${FRICTION}) < 0.05
           ORDER BY n DESC;"
  echo

  echo "▶ 5. 전략별 요약 — 어느 전략이 표본을 만드는가"
  psql_q "$SCREEN_CTE
          SELECT s AS 전략, count(*) AS 칸,
                 sum(n) AS 총OOS거래, round(avg(n),1) AS 평균거래,
                 round(avg(exp),3) AS 평균순기대값,
                 sum(CASE WHEN exp > 0 THEN 1 ELSE 0 END) AS 양수칸
            FROM x GROUP BY 1 ORDER BY 5 DESC;"
  echo
  echo "⚠️ 2번 표에 올라온 칸도 대부분 판정은 OVERFITTING 이다 — 그건 '수익이 없다'가"
  echo "   아니라 '학습 성과가 검증으로 이어지지 않는다'는 뜻이다."
  echo "⚠️ 후보는 '다음에 볼 곳'이지 '실자본을 넣을 곳'이 아니다."
  echo "🔴 WLD·ONDO·MIRA·WLFI 재배치 금지 (09-18 결론)."
  exit 0
fi

# ── --jobs2: v3 대응짝이 없는 Job 2 결과만 따로 본다 ────────────────────────
if [ "${1:-}" = "--jobs2" ]; then
  echo "▶ Job 2 프리셋의 v4 판정 (v3 대응짝이 없어 --verify 대조표에 안 나온다)"
  psql_q "SELECT strategy_name, coin_pair,
                 wf_result_json->>'verdict' AS verdict,
                 (wf_result_json->'aggregatedOutSample'->>'totalTrades')::int AS oos거래,
                 round((wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric,3) AS 기대값
            FROM backtest_run
           WHERE is_walk_forward AND exit_rules_version = 4
             AND strategy_name NOT IN ($BASE_SQL)
           ORDER BY 3, 1, 2;"
  echo
  echo "▶ 판정 분포"
  psql_q "SELECT wf_result_json->>'verdict' AS verdict, count(*)
            FROM backtest_run
           WHERE is_walk_forward AND exit_rules_version = 4
             AND strategy_name NOT IN ($BASE_SQL)
           GROUP BY 1 ORDER BY 2 DESC;"
  echo
  echo "⚠️ v3 대응짝이 없습니다. '좋아졌다/나빠졌다'로 읽지 말고 v4 출발점으로만 쓸 것."
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

# ── v3 실행에서 (타임프레임, 기간, 코인묶음) 복제 계획을 읽는다 ──────────────
#    출력 한 줄 = 하나의 배치. 형식: tf|start|end|["KRW-A","KRW-B",...]|코인수
#
#    🔴 날짜를 KST 로 되돌려 읽는다. 백엔드는 요청의 LocalDate 를
#       atStartOfDay(Asia/Seoul).toInstant() 로 변환해 TIMESTAMPTZ 에 넣는다
#       (BacktestJobService:117). 그래서 2023-01-01 요청은 2022-12-31T15:00Z 로
#       저장되고, psql 세션이 UTC 면 start_date::date 가 2022-12-31 로 나온다.
#       그 값을 그대로 다시 제출하면 **하루가 더 밀려** v3 와 다른 기간이 되고,
#       --verify 의 기간 JOIN 이 한 칸도 맞지 않는다.
PLAN=$(psql_q "SELECT timeframe,
                      (start_date AT TIME ZONE 'Asia/Seoul')::date,
                      (end_date   AT TIME ZONE 'Asia/Seoul')::date,
                      '[\"' || string_agg(DISTINCT coin_pair, '\",\"' ORDER BY coin_pair) || '\"]',
                      count(DISTINCT coin_pair)
                 FROM backtest_run
                WHERE is_walk_forward
                  AND exit_rules_version = 3
                  AND strategy_name IN ($BASE_SQL)
                GROUP BY 1,2,3
                ORDER BY 1,2,3;")

if [ -z "$PLAN" ]; then
  echo "✗ v3 실행을 찾지 못했습니다 — 복제할 원본이 없습니다."
  echo "  exit_rules_version=3 인 WF 실행이 DB 에 있어야 합니다."
  echo "  확인: bash scripts/wf_rebaseline_v4_0922.sh --verify"
  exit 1
fi

echo
echo "▶ v3 에서 복제할 배치 (기간·코인을 그대로 따라간다)"
echo "─────────────────────────────────────────────────────────────────────────"
TOTAL=0
while IFS='|' read -r tf sd ed coins n; do
  [ -z "$tf" ] && continue
  combos=$(( n * 3 ))
  TOTAL=$(( TOTAL + combos ))
  printf "  %-4s %s ~ %s   코인 %2s종 × 3전략 = %3s 조합\n" "$tf" "$sd" "$ed" "$n" "$combos"
done <<< "$PLAN"

# Job 2 의 기간 — BTC 가 속한 H1 묶음을 따라간다
J2_PERIOD=$(psql_q "SELECT (start_date AT TIME ZONE 'Asia/Seoul')::date || '|'
                        || (end_date   AT TIME ZONE 'Asia/Seoul')::date
                      FROM backtest_run
                     WHERE is_walk_forward AND exit_rules_version = 3
                       AND timeframe = 'H1' AND coin_pair = 'KRW-BTC'
                       AND strategy_name IN ($BASE_SQL)
                     ORDER BY created_at DESC LIMIT 1;")
J2_START="${J2_PERIOD%%|*}"
J2_END="${J2_PERIOD##*|}"
case "$J2_START" in
  20[0-9][0-9]-[0-1][0-9]-[0-3][0-9]) ;;
  *) echo "✗ Job 2 기간을 정하지 못했습니다 (KRW-BTC 의 v3 H1 실행이 없습니다)."; exit 1 ;;
esac
printf "  %-4s %s ~ %s   코인  5종 × 9전략 =  45 조합   ← Job 2 (v3 대응짝 없음)\n" \
       "H1" "$J2_START" "$J2_END"
TOTAL=$(( TOTAL + 45 ))
echo "─────────────────────────────────────────────────────────────────────────"
echo "  합계 $TOTAL 조합 · IS 비율 $IN_SAMPLE_RATIO · 윈도우 $WINDOW_COUNT"

echo
echo "▶ 지금 운영 중인 조합"
psql_q "SELECT 'DYNAMIC', strategy_type, timeframe, count(*)
          FROM dynamic_session WHERE status='RUNNING' GROUP BY 1,2,3
        UNION ALL
        SELECT 'FIXED', strategy_type, timeframe, count(*)
          FROM live_trading_session WHERE status='RUNNING' GROUP BY 1,2,3
        ORDER BY 1,2,3;"

if [ "${1:-}" = "--plan" ]; then
  echo
  echo "── --plan 이므로 제출하지 않고 종료합니다."
  exit 0
fi

submit() {
  local tf="$1" coins="$2" sd="$3" ed="$4" strategies="$5"
  api -X POST "$API/backtest/walk-forward-batch-async" \
    -H 'Content-Type: application/json' \
    -d "{
      \"coinPairs\": $coins,
      \"strategyTypes\": $strategies,
      \"timeframe\": \"$tf\",
      \"startDate\": \"$sd\",
      \"endDate\": \"$ed\",
      \"inSampleRatio\": $IN_SAMPLE_RATIO,
      \"windowCount\": $WINDOW_COUNT
    }"
}

echo
echo "▶ Job 1·3: v3 배치 복제 — 기준선 승계"
while IFS='|' read -r tf sd ed coins n; do
  [ -z "$tf" ] && continue
  echo "  · $tf $sd ~ $ed ($n종)"
  submit "$tf" "$coins" "$sd" "$ed" "$BASE_JSON"
  echo
done <<< "$PLAN"

echo
echo "▶ Job 2: H1 영향 범위 — 9전략 × 5코인 = 45 조합"
submit "H1" "$J2_COINS" "$J2_START" "$J2_END" "$J2_JSON"
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
echo "⚠️ 기간이 다른 배치끼리는 비교하지 말 것 — 서로 다른 시장 국면입니다."
