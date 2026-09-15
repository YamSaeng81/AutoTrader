#!/usr/bin/env bash
#
# 고정 격자에서 EUL·PROM 을 ETH·XRP 로 교체 (2026-09-14)
# ═════════════════════════════════════════════════════════════════════════════
#
# ■ 왜 교체하나 — 검증이 구조적으로 불가능한 코인이 격자의 25% 를 쓰고 있다
#
#   고정코인 페이퍼 격자는 5전략 × 8코인 = 40세션이고, 그중 10세션이 EUL·PROM 이다.
#   두 코인은 상장이 최근이라 캔들 자체가 없다:
#
#     코인        M15 행수     시작
#     KRW-BTC    163,880     2021-12-31     ← 정상
#     KRW-EUL      4,138     2026-07-26     ← 40배 부족
#     KRW-PROM     2,540     2026-08-12     ← 64배 부족
#
#   그 결과 09-09 WF 재검증에서 이 두 코인의 19조합이 n≤4 로 나왔다. **어떤 판정도
#   나올 수 없다.** 게다가 verdict 계산이 표본을 안 봐서 `ACCEPTABLE` 로 표시되기까지 했다
#   (2026-09-09 에 INSUFFICIENT_DATA 로 수정).
#
#   그리고 실제로 돈도 잃고 있다. 09-08~09-12 실측에서 PROM 은 **시장이 오른 구간에서**
#   손실을 낸 유일한 코인이었다(시장 +1.84% / 함대 −3.02%).
#
# ■ 왜 ETH·XRP 인가
#
#   candle_data 실측(H1 기준) — 두 코인 모두 다른 6코인과 동일하게 2021-12-31 부터 있다:
#
#     KRW-ETH   40,802행   2021-12-31 ~
#     KRW-XRP   40,800행   2021-12-31 ~
#
#   업비트 KRW 마켓에서 BTC 다음으로 유동성이 크고, 기존 6코인과 이력 길이가 같아
#   **격자가 균일해진다** — 코인별 표본 편차가 판정을 흔들지 않는다.
#
# ■ 순서 (각 STEP 완료를 확인하고 다음으로)
#
#   STEP=1  ETH·XRP 캔들 백필   — M15 는 2026-03-29 부터, H1 은 2026-08-30 부터 낡았다
#   STEP=2  ETH·XRP WF 검증     — 5전략 × 2코인 × {H1,M15} = 20조합
#   STEP=3  세션 교체           — EUL·PROM 10세션 정지 → ETH·XRP 10세션 생성
#
#   STEP=3 을 먼저 하지 말 것. 검증 없는 조합으로 갈아타면 교체한 의미가 없다.
#
# 사용법:
#   STEP=1 bash scripts/replace_eul_prom_0914.sh
#   STEP=2 bash scripts/replace_eul_prom_0914.sh
#   STEP=3 bash scripts/replace_eul_prom_0914.sh      # DRY_RUN=1 이 기본
#   DRY_RUN=0 STEP=3 bash scripts/replace_eul_prom_0914.sh   # 실제 반영

set -uo pipefail

API="http://localhost:8080/api/v1"
STEP="${STEP:-1}"
DRY_RUN="${DRY_RUN:-1}"

OLD_COINS='["KRW-EUL","KRW-PROM"]'
NEW_COINS='["KRW-ETH","KRW-XRP"]'
STRATS='["COMPOSITE_MEANREV_BB","COMPOSITE_MOMENTUM_ICHIMOKU","COMPOSITE_MOMENTUM_ICHIMOKU_V2","COMPOSITE_MTF_BTC","COMPOSITE_MTF_CONFIRMED"]'

if [ -z "${API_AUTH_TOKEN:-}" ] && [ -f .env ]; then
  API_AUTH_TOKEN=$(grep -E '^API_AUTH_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"'"'"'')
fi
if [ -z "${API_AUTH_TOKEN:-}" ]; then
  echo "✗ API_AUTH_TOKEN 을 찾을 수 없습니다. .env 가 있는 디렉터리에서 실행하세요."
  exit 1
fi
AUTH="Authorization: Bearer $API_AUTH_TOKEN"
api() { curl -s -H "$AUTH" "$@"; }

probe=$(api "$API/backtest/jobs")
case "$probe" in
  *UNAUTHORIZED*) echo "✗ 토큰 거부: $(echo "$probe" | head -c 200)"; exit 1 ;;
  "")             echo "✗ 응답 없음 — 백엔드 확인"; exit 1 ;;
esac
echo "✓ 인증 확인"
TODAY=$(date -u +%Y-%m-%d)

case "$STEP" in
  1)
    printf '\n\033[1m▶ STEP 1: ETH·XRP 캔들 백필\033[0m\n'
    echo "  M15 는 2026-03-29, H1 은 2026-08-30 이후가 비어 있습니다."
    echo "  연 단위로 쪼개 요청합니다 — 09-08 에 822회 사슬이 통째로 실패한 전례가 있습니다."
    printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
    read -r _

    for coin in KRW-ETH KRW-XRP; do
      for spec in "M15 2026-03-01" "H1 2026-08-01"; do
        tf=${spec%% *}; from=${spec##* }
        printf '  %-4s %-8s %s ~ %s … ' "$tf" "$coin" "$from" "$TODAY"
        resp=$(api -X POST "$API/data/collect/batch" -H 'Content-Type: application/json' \
          -d "{\"coinPairs\":[\"$coin\"],\"timeframe\":\"$tf\",\"startDate\":\"$from\",\"endDate\":\"$TODAY\"}")
        case "$resp" in
          *'"success":true'*) echo "요청 접수" ;;
          *)                  echo "거부: $(echo "$resp" | head -c 120)" ;;
        esac
        sleep 45
      done
    done

    cat <<'NOTE'

▶ 수집은 백그라운드입니다. 코인마다 텔레그램 알림이 옵니다.
  완료 확인:

    SELECT coin_pair, timeframe, count(*), max(time)::date
    FROM candle_data WHERE coin_pair IN ('KRW-ETH','KRW-XRP')
    GROUP BY 1,2 ORDER BY 1,2;

  M15 가 오늘까지 차면 STEP=2 로.
NOTE
    ;;

  2)
    printf '\n\033[1m▶ STEP 2: ETH·XRP Walk Forward (5전략 × 2코인 × 2TF = 20조합)\033[0m\n'
    echo "  교체 후보가 실제로 근거를 갖는지 먼저 확인합니다."
    printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
    read -r _

    for tf in H1 M15; do
      echo; echo "▶ $tf"
      api -X POST "$API/backtest/walk-forward-batch-async" -H 'Content-Type: application/json' \
        -d "{\"coinPairs\": $NEW_COINS, \"strategyTypes\": $STRATS, \"timeframe\": \"$tf\",
             \"startDate\": \"2022-01-01\", \"endDate\": \"$TODAY\",
             \"inSampleRatio\": 0.7, \"windowCount\": 5}"
      echo
    done

    cat <<'NOTE'

▶ 완료 후 확인 — 20건이 다 돌았는지, 그리고 통과 조합이 있는지:

    SELECT strategy_name, timeframe, coin_pair,
           wf_result_json->>'verdict' AS verdict,
           round((wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric,3) AS exp_pct,
           (wf_result_json->'aggregatedOutSample'->>'totalTrades')::int AS n
    FROM backtest_run
    WHERE is_walk_forward AND exit_rules_version = 2
      AND coin_pair IN ('KRW-ETH','KRW-XRP')
    ORDER BY timeframe, strategy_name, coin_pair;

  ※ 통과 조합이 하나도 없어도 교체는 진행할 가치가 있다 — EUL·PROM 은 **판정 자체가
    불가능**했지만 ETH·XRP 는 최소한 판정을 받을 수 있다. 측정 가능한 것으로 바꾸는 게 목적이다.

  다음: STEP=3
NOTE
    ;;

  3)
    printf '\n\033[1m▶ STEP 3: 세션 교체 (EUL·PROM 10 → ETH·XRP 10)\033[0m\n'
    if [ "$DRY_RUN" = "0" ]; then
      echo "  ⚠️ 실제 반영 모드"
    else
      echo "  미리보기 모드 — 무엇을 할지만 보여줍니다. 반영하려면 DRY_RUN=0"
    fi

    printf '\n\033[1m▶ 정지 대상\033[0m\n'
    sessions=$(api "$API/paper-trading/sessions" \
      | python3 -c 'import json,sys
d = json.load(sys.stdin).get("data", [])
for s in d:
    if s.get("status") == "RUNNING" and s.get("coinPair") in ("KRW-EUL", "KRW-PROM"):
        print("%s|%s|%s|%s" % (s["id"], s["strategyName"], s["coinPair"], s.get("timeframe","M15")))')

    if [ -z "$sessions" ]; then
      echo "  (RUNNING 인 EUL·PROM 세션이 없습니다)"
    else
      echo "$sessions" | awk -F'|' '{printf "  #%-5s %-32s %-9s %s\n", $1,$2,$3,$4}'
      echo "  총 $(echo "$sessions" | wc -l) 세션"
    fi

    printf '\n\033[1m▶ 생성 대상 (5전략 × ETH·XRP = 10세션, M15)\033[0m\n'
    echo "$STRATS" | python3 -c 'import json,sys
for s in json.load(sys.stdin):
    for c in ("KRW-ETH", "KRW-XRP"):
        print("  %-32s %s M15" % (s, c))'

    if [ "$DRY_RUN" != "0" ]; then
      echo
      echo "▶ 미리보기 종료. 반영: DRY_RUN=0 STEP=3 bash scripts/replace_eul_prom_0914.sh"
      exit 0
    fi

    printf '\n정지+생성을 실제로 진행합니다. 계속하려면 Enter, 중단하려면 Ctrl-C: '
    read -r _

    echo
    echo "$sessions" | while IFS='|' read -r id strat coin tf; do
      [ -z "$id" ] && continue
      printf '  정지 #%-5s %-32s %-9s … ' "$id" "$strat" "$coin"
      resp=$(api -X POST "$API/paper-trading/sessions/$id/stop")
      case "$resp" in
        *'"success":true'*) echo "ok" ;;
        *)                  echo "실패: $(echo "$resp" | head -c 100)" ;;
      esac
      sleep 1
    done

    echo
    # 새 세션의 초기자본은 기존 격자와 맞춘다 — 첫 세션에서 읽어 온다.
    CAPITAL=$(api "$API/paper-trading/sessions" \
      | python3 -c 'import json,sys
d = [s for s in json.load(sys.stdin).get("data", []) if s.get("status") == "RUNNING"]
print(int(d[0]["initialCapital"]) if d else 1000000)')
    echo "  신규 세션 초기자본: ${CAPITAL} (기존 격자와 동일)"
    echo

    for strat in $(echo "$STRATS" | python3 -c 'import json,sys; print(" ".join(json.load(sys.stdin)))'); do
      for coin in KRW-ETH KRW-XRP; do
        printf '  생성 %-32s %-9s … ' "$strat" "$coin"
        resp=$(api -X POST "$API/paper-trading/sessions" -H 'Content-Type: application/json' \
          -d "{\"strategyType\":\"$strat\",\"coinPair\":\"$coin\",\"timeframe\":\"M15\",
               \"initialCapital\":$CAPITAL}")
        case "$resp" in
          *'"success":true'*) echo "ok" ;;
          *)                  echo "실패: $(echo "$resp" | head -c 140)" ;;
        esac
        sleep 1
      done
    done

    cat <<'NOTE'

▶ 확인:

    SELECT coin_pair, count(*) FROM paper_trading.virtual_balance
    WHERE status='RUNNING' GROUP BY 1 ORDER BY 1;

  기대: 8코인 × 5 = 40, EUL·PROM 0, ETH·XRP 각 5.

  ※ 새 세션의 표본은 0 부터 시작한다. 기존 6코인과 청산 이력 길이가 달라지므로,
    당분간 코인별 비교에서는 그 점을 감안할 것.
NOTE
    ;;

  *)
    echo "✗ STEP 은 1·2·3 중 하나여야 합니다 (받은 값: $STEP)"
    exit 1
    ;;
esac
