#!/usr/bin/env bash
#
# 백테스트 규칙 v3 재검증 기준선 만들기 (2026-09-18)
# ═════════════════════════════════════════════════════════════════════════════
#
# ■ 왜 이 스크립트가 있나
#
#   09-18 에 백테스트가 "재는 대상" 자체가 바뀌었다. 청산 규칙은 그대로인데:
#
#     · COMPOSITE 계열이 운영과 같은 구성으로 통일됐다 (이전엔 정의가 셋이었고,
#       core-engine 단위 테스트는 운영이 실행하지 않는 전략을 검증해 왔다)
#     · COMPOSITE 가 국면 적응형으로 전환됐다 (전체 기간 정보로 조합을 굳히던
#       미래 참조 제거)
#     · 평가 입력에 timeframe 이 주입돼 시간봉별 가중치 override 가 적용되기 시작했다
#     · BTC_MARKET_GUARD 가 종료된 봉만 참조한다 (look-ahead 제거)
#     · 기간 종료 미청산 포지션을 강제청산해 성과 지표에 반영한다
#     · SELL 순손익에서 진입 수수료를 뺀다 (운영 realizedPnl 과 정의 일치)
#
#   그래서 기존 WF 결과는 **다른 전략을, 미래를 보면서, 다른 손익 정의로** 잰 값이다.
#   BACKTEST_RULESET_VERSION 을 2→3 으로 올려 그 결과들을 무효화했고, 이 스크립트는
#   **현재 운영 중인 조합만** 새 규칙으로 다시 돌려 이후 비교의 기준선을 만든다.
#
#   2026-09-18 실측 기준 기존 WF 실행 530건이 전부 무효다:
#     exit_rules_version = 2  →  100건 (최신 09-14)
#     exit_rules_version = NULL → 430건 (09-08 이전)
#
# ■ 무엇을 돌리는가 — 운영 중인 5개 조합만
#
#   전 조합을 돌리지 않는다. 안 쓰는 전략까지 재실행하면 몇 시간이 더 들고,
#   기준선으로 봐야 할 숫자가 묻힌다.
#
#     H1   COMPOSITE_MEANREV_BB            동적 1
#     H1   COMPOSITE_MOMENTUM_ICHIMOKU_V2  동적 2
#     H1   COMPOSITE_MTF_CONFIRMED         동적 1
#     M15  COMPOSITE_MTF_CONFIRMED         동적 1
#     M15  COMPOSITE_MEANREV_BB            고정 1 (KRW-XRP)
#
#   동적 세션은 코인을 워치리스트에서 고르므로 "그 세션의 코인"이 없다. 최근 6개월
#   실제 포지션 상위 5종으로 대신한다(ETH 101 · BTC 100 · SOL/DOGE/XRP 각 57 —
#   전체의 대부분). KRW-XRP 는 M15 고정 세션이 쓰는 코인이라 어차피 필수다.
#
#   ⚠️ 이 기준선은 **메이저 5종만** 덮는다. 동적 세션은 실제로는 ONDO·TRUMP·ENA 같은
#      소형 알트도 산다(09-04 분석: "MTF 계열이 부적합으로 적힌 소형 알트만 사고 있다 —
#      함대 손실 전부"). 메이저에서 통과해도 실매매 대상과 다르다는 뜻이다.
#      알트 검증은 별건이며 캔들 이력이 짧아 3년 WF 가 애초에 성립하지 않을 수 있다.
#
# ■ 기간을 2026-09-07 로 끊는 이유
#
#   캔들 보유가 코인마다 다르다 — ETH·XRP 는 09-17 까지지만 BTC·DOGE·SOL 은 09-07 까지다.
#   코인마다 기간이 다르면 같은 배치 안에서 비교가 어긋난다. **전 코인이 공통으로 가진
#   끝날짜**로 맞춘다. 캔들을 더 수집했다면 END_DATE 를 올려도 되지만, 그때도
#   5종 모두가 그 날짜까지 있는지 먼저 확인할 것.
#
# ■ 사용법 — 운영 서버에서, 리포 루트에서
#
#     bash scripts/rebaseline_wf_0918.sh            # 제출
#     bash scripts/rebaseline_wf_0918.sh --verify   # 결과가 v3 로 저장됐는지 확인
#
#   제출은 즉시 끝나고 실제 실행은 백그라운드다. 완료 시 텔레그램 알림이 온다.
#   ⚠️ 완료 후 반드시 --verify 를 돌릴 것 — 컨테이너가 옛 이미지로 떠 있으면 결과가
#      v2 로 저장되고, 그러면 **낡은 코드의 숫자를 새 기준선으로 믿게 된다.**
#
# ■ 게이트는 지금 꺼져 있다
#
#   REQUIRE_WALK_FORWARD_GATE 기본값이 false 다. 즉 이 재실행은 지금 거래를 막지도
#   풀지도 않는다 — **비교 기준을 새로 만드는 작업**이다. 게이트를 켤지는 v3 결과를
#   보고 판단할 일이다.

set -uo pipefail

API="http://localhost:8080/api/v1"

COINS='["KRW-BTC","KRW-ETH","KRW-SOL","KRW-DOGE","KRW-XRP"]'
START_DATE="2023-01-01"
END_DATE="2026-09-07"
IN_SAMPLE_RATIO=0.7
WINDOW_COUNT=5

H1_STRATEGIES='["COMPOSITE_MEANREV_BB","COMPOSITE_MOMENTUM_ICHIMOKU_V2","COMPOSITE_MTF_CONFIRMED"]'
M15_STRATEGIES='["COMPOSITE_MEANREV_BB","COMPOSITE_MTF_CONFIRMED"]'

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

# ── --verify: 결과가 v3 로 저장됐는지 확인 ───────────────────────────────────
if [ "${1:-}" = "--verify" ]; then
  echo "▶ WF 실행의 규칙 버전 분포 (NULL = 09-08 이전)"
  psql_q "SELECT COALESCE(exit_rules_version::text,'NULL') AS v, count(*), max(created_at)::date
            FROM backtest_run WHERE is_walk_forward GROUP BY 1 ORDER BY 1;"
  echo
  echo "▶ 오늘 저장된 실행 (여기가 전부 3 이어야 한다)"
  psql_q "SELECT strategy_name, coin_pair, timeframe,
                 COALESCE(exit_rules_version::text,'NULL') AS v,
                 wf_result_json->>'verdict' AS verdict
            FROM backtest_run
           WHERE is_walk_forward AND created_at::date = CURRENT_DATE
           ORDER BY timeframe, strategy_name, coin_pair;"
  echo
  echo "  v 가 2 나 NULL 이면 컨테이너가 옛 이미지입니다 — 재빌드 후 다시 제출하세요."
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
echo "▶ 지금 운영 중인 조합 (아래 제출 내용과 맞는지 눈으로 확인할 것)"
psql_q "SELECT 'DYNAMIC', strategy_type, timeframe, count(*)
          FROM dynamic_session WHERE status='RUNNING' GROUP BY 1,2,3
        UNION ALL
        SELECT 'FIXED', strategy_type, timeframe, count(*)
          FROM live_trading_session WHERE status='RUNNING' GROUP BY 1,2,3
        ORDER BY 1,2,3;" \
  || echo "  (DB 조회 실패 — 아래 목록은 2026-09-18 실측값으로 하드코딩돼 있습니다)"

echo
echo "  제출 예정:"
echo "    기간      $START_DATE ~ $END_DATE  (IS 비율 $IN_SAMPLE_RATIO · 윈도우 $WINDOW_COUNT)"
echo "    코인      $COINS"
echo "    H1        $H1_STRATEGIES   → 15 조합"
echo "    M15       $M15_STRATEGIES  → 10 조합"

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
echo "▶ Job 1: H1 — 3전략 × 5코인 = 15 조합"
submit "H1" "$H1_STRATEGIES"
echo

echo "▶ Job 2: M15 — 2전략 × 5코인 = 10 조합"
submit "M15" "$M15_STRATEGIES"
echo

echo
echo "─────────────────────────────────────────────────────────────────────────"
echo "제출 완료. 실행은 백그라운드이며 완료 시 텔레그램 알림이 옵니다."
echo
echo "  진행 상황   curl -s -H \"\$AUTH\" $API/backtest/jobs"
echo "  완료 후     bash scripts/rebaseline_wf_0918.sh --verify    ← 반드시 확인"
echo
echo "⚠️ --verify 에서 exit_rules_version 이 3 이 아니면 그 결과는 기준선이 아닙니다."
