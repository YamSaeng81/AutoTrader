#!/usr/bin/env bash
#
# Walk Forward 전면 재검증 (2026-09-08) — 손절 로직 수정 반영
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 왜 재검증하나
#   `ExitRuleChecker.updateTrailingStops` 의 손실 구간 SL 조임을 2026-09-08 제거했다.
#   그런데 **BacktestEngine 도 같은 함수를 쓴다**(설계 의도 — 백테스트와 운영이 같은
#   청산 규칙을 써야 한다). 즉 기존 WF 350건은 전부 다음 규칙으로 계산된 것이다:
#
#     저가 < 진입가 → SL 을 저점 × (1 − 0.3%) 로 상향  = 사실상 0.3% 손절
#
#   설정값 stop_loss_pct=5.00 이 실제로는 0.43% 로 걸렸고, 운영 페이퍼에서 청산 534건
#   중 85.4% 가 평균 0.9시간 만에 휩쏘 손절됐다. **그 전제로 통과/탈락시킨 조합 판정은
#   전부 무효**다. WalkForwardValidationGate 가 실자본 배정을 이 판정으로 막고 있으므로
#   방치하면 잘못된 근거로 전략을 계속 차단/허용하게 된다.
#
# ■ 두 번째 문제 — WF 가 M15 를 한 번도 검증한 적이 없다
#   backtest_run 350건이 **전부 H1** 이다. 그런데 실제 운영은:
#
#     고정코인 PAPER  40세션 … 전부 M15
#     동적 세션       12세션 … H1 6 / M15 6
#
#   운영 함대의 대부분이 검증된 적 없는 타임프레임으로 돌고 있다. 이번에 함께 채운다.
#
# ■ 세 번째 문제 — M15 캔들이 없거나 5개월 낡았다 (먼저 고쳐야 한다)
#   2026-09-08 기준 운영 8코인의 candle_data 실측:
#
#     coin       H1 최종      M15 행수    M15 최종
#     KRW-ADA    2026-08-30   148,358     2026-03-29
#     KRW-AVAX   2026-08-30         0     —
#     KRW-BTC    2026-08-30   148,463     2026-03-30
#     KRW-DOGE   2026-08-30   148,364     2026-03-29
#     KRW-EUL    2026-08-30         0     —
#     KRW-LINK   2026-08-30         0     —
#     KRW-PROM   2026-09-01         0     —
#     KRW-SOL    2026-08-31   148,366     2026-03-29
#
#   **M15 는 4코인이 0건, 나머지 4코인도 5개월 낡았다.** 이대로 WF 를 돌리면 절반이
#   조용히 누락된다 — 2026-08-24 에 실제로 겪은 사고다(48조합 중 25개 누락,
#   scripts/backfill_candle_data.sh 주석 참조). 그래서 Step 1 이 백필이다.
#
# ■ 순서 (Step 1 완료를 확인한 뒤 Step 2 를 돌릴 것)
#   Step 1  캔들 백필 — M15 8코인 전체 + H1 갭
#   Step 2  WF 재검증 — 5전략 × 8코인 × {H1, M15}
#   Step 3  게이트 판정 재확인
#
#   Step 1 은 Upbit 레이트리밋 때문에 시간이 걸린다(M15 는 H1 의 4배 분량).
#   완료되면 코인마다 텔레그램 알림이 온다. STEP=2 로 나눠 실행하는 것을 권장한다.
#
# 사용법:
#   ssh <운영서버>; cd <리포>
#   bash scripts/revalidate_walk_forward_0908.sh          # 전체 안내 + Step 1
#   STEP=1 bash scripts/revalidate_walk_forward_0908.sh   # 캔들 백필만
#   STEP=2 bash scripts/revalidate_walk_forward_0908.sh   # WF 재검증만 (백필 완료 후)
#   STEP=3 bash scripts/revalidate_walk_forward_0908.sh   # 판정 확인만

set -uo pipefail

API="http://localhost:8080/api/v1"
STEP="${STEP:-1}"

OPS_COINS='["KRW-SOL","KRW-BTC","KRW-DOGE","KRW-LINK","KRW-ADA","KRW-AVAX","KRW-PROM","KRW-EUL"]'
OPS_STRATS='["COMPOSITE_MEANREV_BB","COMPOSITE_MOMENTUM_ICHIMOKU","COMPOSITE_MOMENTUM_ICHIMOKU_V2","COMPOSITE_MTF_BTC","COMPOSITE_MTF_CONFIRMED"]'

# ── 토큰 ─────────────────────────────────────────────────────────────────────
if [ -z "${API_AUTH_TOKEN:-}" ] && [ -f .env ]; then
  API_AUTH_TOKEN=$(grep -E '^API_AUTH_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"'"'"'')
fi
if [ -z "${API_AUTH_TOKEN:-}" ]; then
  echo "✗ API_AUTH_TOKEN 을 찾을 수 없습니다."
  echo "  export API_AUTH_TOKEN=... 후 다시 실행하거나, .env 가 있는 디렉터리에서 실행하세요."
  exit 1
fi
AUTH="Authorization: Bearer $API_AUTH_TOKEN"
api() { curl -s -H "$AUTH" "$@"; }

probe=$(api "$API/backtest/jobs")
case "$probe" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다: $(echo "$probe" | head -c 200)"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요."; exit 1 ;;
esac
echo "✓ 인증 확인"

TODAY=$(date -u +%Y-%m-%d)

# ── 배포 확인 — 수정이 안 들어간 빌드로 재검증하면 의미가 없다 ────────────────
printf '\n\033[1m▶ 선행 확인\033[0m\n'
echo "  이 재검증은 손절 수정이 **배포된 빌드**에서 돌아야 의미가 있습니다."
echo "  운영 DB 로 확인: 2026-09-08 09:26 KST 이후 진입 포지션의 sl_gap 이 −5% 근처여야 합니다."
echo "    SELECT round((stop_loss_price/entry_price-1)*100,3) FROM paper_trading.position"
echo "    WHERE opened_at >= timestamptz '2026-09-08 00:20+00' ORDER BY opened_at DESC LIMIT 3;"

case "$STEP" in
  1)
    printf '\n\033[1m▶ Step 1: 캔들 백필\033[0m\n'
    echo "  M15 는 4코인이 0건, 4코인이 5개월 낡았습니다. 전 코인 전 구간을 다시 받습니다."
    echo "  (saveAll 이 PK(time,coin_pair,timeframe) upsert 라 겹쳐 받아도 안전합니다.)"
    printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
    read -r _

    echo
    echo "▶ M15 8코인 × 2022-01-01 ~ $TODAY (H1 의 4배 분량 — 오래 걸립니다)"
    api -X POST "$API/data/collect/batch" -H 'Content-Type: application/json' \
      -d "{\"coinPairs\": $OPS_COINS, \"timeframe\": \"M15\", \"startDate\": \"2022-01-01\", \"endDate\": \"$TODAY\"}"

    echo
    echo "▶ H1 8코인 갭 채우기 × 2026-08-25 ~ $TODAY"
    api -X POST "$API/data/collect/batch" -H 'Content-Type: application/json' \
      -d "{\"coinPairs\": $OPS_COINS, \"timeframe\": \"H1\", \"startDate\": \"2026-08-25\", \"endDate\": \"$TODAY\"}"

    cat <<'NOTE'

▶ 백그라운드 수집 중입니다. 코인마다 텔레그램 알림이 옵니다.
  완료 여부를 DB 로 확인한 뒤 Step 2 를 돌리세요 — 8코인 전부 M15 가 최근 날짜여야 합니다.

    SELECT coin_pair, timeframe, count(*) AS n, max(time)::date AS last_candle
    FROM candle_data
    WHERE coin_pair IN ('KRW-ADA','KRW-AVAX','KRW-BTC','KRW-DOGE',
                        'KRW-EUL','KRW-LINK','KRW-PROM','KRW-SOL')
    GROUP BY 1,2 ORDER BY 2,1;

  ※ KRW-EUL(2026-07-26~)·KRW-PROM(2026-08-12~)은 상장이 최근이라 이력이 짧습니다.
    WF 윈도 5개를 못 채우면 해당 조합만 빠질 수 있습니다 — Step 2 결과에서 확인하세요.

  다음: STEP=2 bash scripts/revalidate_walk_forward_0908.sh
NOTE
    ;;

  2)
    printf '\n\033[1m▶ Step 2: WF 재검증 (5전략 × 8코인 × 2타임프레임 = 80조합)\033[0m\n'
    echo "  기존 350건은 0.3% 손절 기준이라 폐기 대상입니다. 새 결과로 판정을 다시 냅니다."
    printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
    read -r _

    for tf in H1 M15; do
      echo
      echo "▶ $tf — 5전략 × 8코인 = 40조합"
      api -X POST "$API/backtest/walk-forward-batch-async" -H 'Content-Type: application/json' \
        -d "{
          \"coinPairs\": $OPS_COINS,
          \"strategyTypes\": $OPS_STRATS,
          \"timeframe\": \"$tf\",
          \"startDate\": \"2022-01-01\",
          \"endDate\": \"$TODAY\",
          \"inSampleRatio\": 0.7,
          \"windowCount\": 5
        }"
      echo
    done

    cat <<'NOTE'

▶ 두 배치 모두 백그라운드 실행 중. 진행: GET /api/v1/backtest/jobs
  완료 후 STEP=3 으로 판정을 확인하세요.

  ⚠️ 누락 점검 — 2026-08-24 에 25조합이 조용히 빠진 적이 있습니다. 80건이 다 돌았는지
     반드시 세어 볼 것:

    SELECT timeframe, count(*) AS n, count(DISTINCT strategy_name) AS strats,
           count(DISTINCT coin_pair) AS coins
    FROM backtest_run
    WHERE is_walk_forward AND created_at >= '2026-09-08'
    GROUP BY 1;
NOTE
    ;;

  3)
    printf '\n\033[1m▶ Step 3: 게이트 판정 재확인\033[0m\n'
    api "$API/strategies/walk-forward-gate-status"
    cat <<'NOTE'


▶ 해석 시 주의
  1) 이번 판정은 **0.3% 손절이 아닌 정상 손절** 기준의 첫 판정입니다.
     이전 판정과 뒤집히는 조합이 나오는 것이 정상이며, 그것이 재검증의 목적입니다.

  2) H1 과 M15 를 나눠서 볼 것. 운영 함대는 고정코인 40세션이 전부 M15,
     동적 12세션이 H1 6 / M15 6 입니다. H1 판정으로 M15 함대를 판단할 수 없습니다.

  3) 판정이 나와도 `kill-criteria.auto-stop` 은 계속 OFF 로 둡니다.
     운영 페이퍼 표본(n≥20)이 새 지문으로 쌓인 뒤에 함께 봐야 합니다.

    SELECT p.ruleset_hash, count(*) AS n,
           round(100.0*count(*) FILTER (WHERE p.realized_pnl>0)/count(*),1) AS winrate
    FROM paper_trading.position p
    JOIN paper_trading.virtual_balance v ON v.id = p.session_id
    WHERE p.status='CLOSED' AND p.closed_at >= '2026-09-08'
    GROUP BY 1 ORDER BY 2 DESC;
NOTE
    ;;

  *)
    echo "✗ STEP 은 1·2·3 중 하나여야 합니다 (받은 값: $STEP)"
    exit 1
    ;;
esac
