#!/usr/bin/env bash
#
# WF 커버리지 확장 — 신규 감시목록 코인 (2026-08-28)
# ─────────────────────────────────────────────────────────────────────────────
# ■ 선행 조건 — 반드시 먼저 확인할 것
#
#   bash scripts/collect_watchlist_candles.sh   가 끝나 있어야 한다.
#   WF 는 candle_data 를 읽는데, 08-28 실측에서 아래 코인들은 **0행**이었다.
#   수집 없이 이 스크립트를 돌리면 조합이 통째로 실패하거나, 더 나쁘게는
#   **표본이 얇은 백테스트가 통과로 나온다**.
#
# ■ 왜 기존 rerun_walk_forward_watchlist.sh 로 안 되나
#   그 스크립트의 코인 목록은 08-25 시점 워치리스트다. 그 뒤 워치리스트가 돌면서
#   구성이 바뀌었다 — 아래 11개는 그때 없던 코인이고, 반대로 그 목록의
#   KRW-FOLD·KRW-TRAC·KRW-WLFI·KRW-PUMP 는 지금 거의 안 잡힌다.
#   **워치리스트는 계속 도므로 이 작업은 주기적으로 반복해야 한다.**
#
# ■ 대상 — 최근 3일 평가 60회 이상이면서 WF 미검증
#     KRW-SLX(332) KRW-ONT(314) KRW-PROM(167) KRW-META2(125) KRW-LIT(108)
#     KRW-ZRO(107) KRW-LSK(104) KRW-MLK(103) KRW-NCT(88) KRW-GAS(65) KRW-BEAM(64)
#
#   6전략 × 11코인 = 66조합. 기존 검증분(22코인)과 합쳐 33코인이 된다.
#
# 사용법: 운영 서버에서 (리포 루트에서) 실행
#   bash scripts/walk_forward_new_watchlist_coins.sh

set -uo pipefail

API="http://localhost:8080/api/v1"
TODAY=$(date -u +%Y-%m-%d)

NEW_COINS='["KRW-SLX","KRW-ONT","KRW-PROM","KRW-META2","KRW-LIT","KRW-ZRO",
"KRW-LSK","KRW-MLK","KRW-NCT","KRW-GAS","KRW-BEAM"]'

STRATEGIES='["COMPOSITE_MEANREV_BB","COMPOSITE_MOMENTUM_ICHIMOKU","COMPOSITE_MOMENTUM_ICHIMOKU_V2",
"COMPOSITE_MTF_BTC","COMPOSITE_MTF_CONFIRMED","COMPOSITE_PULLBACK_MTF"]'

if [ -z "${API_AUTH_TOKEN:-}" ] && [ -f .env ]; then
  API_AUTH_TOKEN=$(grep -E '^API_AUTH_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"'"'"'')
fi
if [ -z "${API_AUTH_TOKEN:-}" ]; then
  echo "✗ API_AUTH_TOKEN 을 찾을 수 없습니다."; exit 1
fi
AUTH="Authorization: Bearer $API_AUTH_TOKEN"
api() { curl -s -H "$AUTH" "$@"; }

probe=$(api "$API/data/summary")
case "$probe" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드 확인"; exit 1 ;;
esac
echo "✓ 인증 확인"

# ── PRECHECK: 캔들 데이터가 실제로 있는가 ────────────────────────────────────
printf '\n\033[1m▶ PRECHECK — 대상 코인의 candle_data 확인\033[0m\n'
api "$API/data/summary" | python3 -c '
import json,sys
targets = ["KRW-SLX","KRW-ONT","KRW-PROM","KRW-META2","KRW-LIT","KRW-ZRO",
           "KRW-LSK","KRW-MLK","KRW-NCT","KRW-GAS","KRW-BEAM"]
have = {}
for r in json.load(sys.stdin)["data"]:
    if r.get("timeframe") != "H1": continue
    c = r.get("coinPair") or r.get("coin_pair")
    have[c] = r.get("count") or r.get("rowCount") or 0
bad = []
for t in targets:
    n = have.get(t, 0)
    mark = "✅" if n >= 2000 else ("⚠️ " if n else "❌")
    if n < 2000: bad.append((t, n))
    print("  %s %-12s %s행" % (mark, t, n))
if bad:
    print()
    print("  🔴 %d개 코인이 2,000행 미만입니다:" % len(bad))
    for t,n in bad: print("     %-12s %s행" % (t,n))
    print("     → collect_watchlist_candles.sh 를 먼저 돌리세요.")
    print("     → 수집해도 부족하면 상장이 최근이라 거래소에 이력이 없는 것입니다.")
    print("       그 코인은 이 배치에서 빼야 합니다 — 얇은 표본은 통과해도 의미가 없습니다.")
    sys.exit(2)
'
precheck=$?
if [ $precheck -ne 0 ]; then
  echo
  # 비대화형(nohup 등)에서는 강행하지 않고 중단한다 — 얇은 표본으로 돌린 WF 가 "통과"로
  # 나오면 검증됐다고 착각하게 만들어 오히려 위험하다. 강행하려면 --force 를 명시할 것.
  if [ "${1:-}" = "--force" ]; then
    echo "  ⚠️  --force — 데이터 부족 상태로 강행합니다(권장하지 않음)."
  elif [ ! -t 0 ]; then
    echo "  ✗ 중단 — 데이터가 부족합니다. collect_watchlist_candles.sh 를 먼저 실행하세요."
    exit 1
  else
    printf '  그래도 강행하시겠습니까? (권장하지 않음) yes 입력: '
    read -r force
    [ "$force" = "yes" ] || { echo "중단. collect_watchlist_candles.sh 를 먼저 실행하세요."; exit 1; }
  fi
fi

# ── 실행 ─────────────────────────────────────────────────────────────────────
printf '\n\033[1m▶ 6전략 × 11코인 = 66조합 WF (2022-01-01 ~ %s, H1)\033[0m\n' "$TODAY"
resp=$(api -X POST "$API/backtest/walk-forward-batch-async" \
  -H 'Content-Type: application/json' \
  -d "{
    \"coinPairs\": $NEW_COINS,
    \"strategyTypes\": $STRATEGIES,
    \"timeframe\": \"H1\",
    \"startDate\": \"2022-01-01\",
    \"endDate\": \"$TODAY\",
    \"inSampleRatio\": 0.7,
    \"windowCount\": 5
  }")
echo "$resp"

cat <<'NOTE'

════════════════════════════════════════════════════════════════════════════
▶ 진행 확인 (수 시간 소요) — 텔레그램으로 완료 알림이 온다
════════════════════════════════════════════════════════════════════════════

  SELECT strategy_name, count(DISTINCT coin_pair) AS coins,
         to_char(max(created_at),'MM-DD HH24:MI') AS latest
  FROM backtest_run WHERE is_walk_forward AND created_at > now() - interval '1 day'
  GROUP BY 1 ORDER BY 1;

▶ 완료 후 — 게이트 커버리지 재확인

  curl -s -H "Authorization: Bearer $API_AUTH_TOKEN" \
    localhost:8080/api/v1/strategies/walk-forward-gate-status | python3 -m json.tool

  · 이 배치의 목적은 "통과시키기"가 아니라 **검증 범위를 넓히는 것**이다.
    fail 이 많이 나와도 그 자체가 결과다 — 지금은 검증 안 된 코인에 진입하고 있고,
    그게 DYNAMIC 을 메인으로 못 올리는 이유다.
  · 통과 조합이 늘면 REQUIRE_WALK_FORWARD_GATE 를 켠 상태로 LIVE 전환을 검토할 수 있다.
NOTE
