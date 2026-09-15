#!/usr/bin/env bash
#
# WF 커버리지 확장 — 09-02 워치리스트 기준 (2026-09-02)
# ─────────────────────────────────────────────────────────────────────────────
# ■ 선행 조건 — 사실 없다 (2026-09-02 정정)
#
#   처음엔 "collect_watchlist_candles_0902.sh 가 끝나 있어야 한다"고 적었는데,
#   그건 **market_data_cache**(실거래 시세 캐시)를 보고 세운 판단이었다.
#   WF 가 읽는 것은 **candle_data** 이고, 거기엔 이미 12코인이 준비돼 있었다.
#
#       candle_data        H1  903,030행 / 52코인   ← WF 가 읽는다
#       market_data_cache  H1   44,314행 / 65코인   ← 실거래 틱용, 최근 몇 주치
#
#   PRECHECK 는 /data/summary(=candle_data)를 보므로 처음부터 옳았다.
#   **수집을 기다리지 말고 그냥 돌리면 된다** — 자격 코인은 알아서 골라진다.
#
# ■ 왜 해야 하나 — 09-14 결정의 유일한 입력값이다
#
#   09-14 에 "WF 게이트를 켤까"를 판단해야 한다. 09-02 현재 진짜 공백은
#   캔들이 아니라 **WF 실행 이력**이다:
#
#     ICX  40,687행 · ARB 29,937 · UNI 16,246 · AXL 10,647
#       → 캔들이 충분한데 WF 를 한 번도 안 돌렸다. 데이터가 없어서가 아니라 안 한 것이다.
#
#     워치리스트 중 통과가 있는 코인은 BTC·ENA·ONDO 3개뿐이고,
#     **함대가 실제로 산 12조합 중 10조합이 WF 이력 0건**이다.
#     이대로 게이트를 켜면 08-31 에 함대가 얼어붙은 상황이 재현된다.
#
# ■ 이 배치의 목적은 "통과시키기"가 아니다
#   FAIL 이 많이 나와도 그 자체가 결과다. 지금 함대는 **실제로 산 12조합 중 10조합이
#   WF 이력 0건**인 상태로 거래하고 있고, 그게 DYNAMIC 을 메인으로 못 올리는 이유다.
#
# ■ 대상 전략 — 5개 (PULLBACK_MTF 는 08-31 정지, 제외)
#
# ■ 소요 시간: 수 시간. 완료 시 텔레그램 알림.
#   ⚠️ 도는 동안 재빌드하지 말 것 — 비동기 큐가 함께 날아간다.
#
# 사용법: 운영 서버에서 (리포 루트에서) 실행
#   bash scripts/walk_forward_watchlist_0902.sh
#   bash scripts/walk_forward_watchlist_0902.sh --force   # 데이터 부족해도 강행(비권장)
#   bash scripts/walk_forward_watchlist_0902.sh KRW-ZKC KRW-LA KRW-0G KRW-MIRA   # 일부만

set -uo pipefail

API="http://localhost:8080/api/v1"
TODAY=$(date -u +%Y-%m-%d)
MIN_ROWS=2000

# 09-02 워치리스트 전체(20). PRECHECK 가 2,000행 미만을 자동으로 걸러낸다.
#
# 일부만 돌리려면 인자로 코인을 넘긴다 — 이미 끝난 조합을 다시 돌리지 않아도 된다:
#   bash scripts/walk_forward_watchlist_0902.sh KRW-ZKC KRW-LA KRW-0G KRW-MIRA
#
# (09-02 캔들 수집으로 저 4코인이 새로 자격을 얻었다 — 8,000행대, 2025-09 상장.
#  "신규 상장이라 이력이 없다"던 최초 예상이 틀렸다. market_data_cache 에 500행뿐이었던
#  것은 그 테이블이 최근 몇 주만 들고 있기 때문이지 거래소에 이력이 없어서가 아니었다.)
DEFAULT_CANDIDATES="KRW-BTC KRW-ETH KRW-XRP KRW-SOL KRW-ICX KRW-CRV KRW-UNI KRW-ARB KRW-ONT \
KRW-ENA KRW-ONDO KRW-TRUMP KRW-PROM KRW-FLOCK KRW-LA KRW-ZKC KRW-MIRA KRW-0G \
KRW-DOS KRW-AXL"

# --force 는 플래그라 코인 목록에서 분리한다.
ARGS=""
FORCE=""
for a in "$@"; do
  case "$a" in
    --force) FORCE="--force" ;;
    KRW-*)   ARGS="$ARGS $a" ;;
    *)       echo "⚠️  무시된 인자: $a" ;;
  esac
done
if [ -n "$ARGS" ]; then
  CANDIDATES="$ARGS"
  echo "▶ 지정된 코인만 대상으로 삼습니다:$CANDIDATES"
else
  CANDIDATES="$DEFAULT_CANDIDATES"
fi

STRATEGIES='["COMPOSITE_MEANREV_BB","COMPOSITE_MOMENTUM_ICHIMOKU","COMPOSITE_MOMENTUM_ICHIMOKU_V2",
"COMPOSITE_MTF_BTC","COMPOSITE_MTF_CONFIRMED"]'

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

# ── PRECHECK: 실제로 WF 를 돌릴 수 있는 코인만 남긴다 ────────────────────────
printf '\n\033[1m▶ PRECHECK — H1 %s행 이상인 코인만 대상으로 삼는다\033[0m\n' "$MIN_ROWS"
# 표(사람이 읽는 용도)와 목록(스크립트가 쓰는 용도)을 각각 한 번씩 만든다.
echo "$probe" | python3 -c '
import json,sys
MIN = '"$MIN_ROWS"'
targets = """'"$CANDIDATES"'""".split()
have = {}
for r in json.load(sys.stdin)["data"]:
    if r.get("timeframe") != "H1": continue
    c = r.get("coinPair") or r.get("coin_pair")
    have[c] = r.get("count") or r.get("rowCount") or 0
ok  = [(t, have.get(t,0)) for t in targets if have.get(t,0) >= MIN]
bad = [(t, have.get(t,0)) for t in targets if have.get(t,0) <  MIN]
for t,n in sorted(ok,  key=lambda x:-x[1]): print("  OK   %-12s %7s행" % (t,n))
for t,n in sorted(bad, key=lambda x:-x[1]): print("  skip %-12s %7s행  (제외)" % (t,n))
print()
print("  대상 %d코인 / 제외 %d코인" % (len(ok), len(bad)))
'

ELIGIBLE=$(echo "$probe" | python3 -c '
import json,sys
MIN = '"$MIN_ROWS"'
targets = """'"$CANDIDATES"'""".split()
have = {}
for r in json.load(sys.stdin)["data"]:
    if r.get("timeframe") != "H1": continue
    c = r.get("coinPair") or r.get("coin_pair")
    have[c] = r.get("count") or r.get("rowCount") or 0
print(" ".join(t for t in targets if have.get(t, 0) >= MIN))')

if [ -z "$ELIGIBLE" ]; then
  echo "  ✗ 조건을 만족하는 코인이 없습니다 — collect_watchlist_candles_0902.sh 를 먼저 실행하세요."
  exit 1
fi

ELIGIBLE_COUNT=$(echo $ELIGIBLE | wc -w)
if [ "$ELIGIBLE_COUNT" -lt 6 ] && [ -z "$FORCE" ] && [ -z "$ARGS" ]; then
  echo
  echo "  ⚠️  대상이 $ELIGIBLE_COUNT 코인뿐입니다. 캔들 수집이 아직 안 끝났을 수 있습니다."
  if [ ! -t 0 ]; then
    echo "  ✗ 중단(비대화형) — 수집을 먼저 끝내거나 --force 로 강행하세요."
    exit 1
  fi
  printf '  그래도 진행하시겠습니까? yes 입력: '
  read -r go
  [ "$go" = "yes" ] || { echo "중단."; exit 1; }
fi

COIN_JSON=$(printf '%s\n' $ELIGIBLE | python3 -c '
import json,sys
print(json.dumps([l.strip() for l in sys.stdin if l.strip()]))')

# ── 실행 ─────────────────────────────────────────────────────────────────────
printf '\n\033[1m▶ 5전략 × %s코인 = %s조합 WF (2022-01-01 ~ %s, H1)\033[0m\n' \
  "$ELIGIBLE_COUNT" "$((ELIGIBLE_COUNT * 5))" "$TODAY"
echo "  $COIN_JSON"

resp=$(api -X POST "$API/backtest/walk-forward-batch-async" \
  -H 'Content-Type: application/json' \
  -d "{
    \"coinPairs\": $COIN_JSON,
    \"strategyTypes\": $STRATEGIES,
    \"timeframe\": \"H1\",
    \"startDate\": \"2022-01-01\",
    \"endDate\": \"$TODAY\",
    \"inSampleRatio\": 0.7,
    \"windowCount\": 5
  }")
echo "  응답: $resp"

cat <<'NOTE'

════════════════════════════════════════════════════════════════════════════
▶ 진행 확인 (수 시간 소요) — 완료 시 텔레그램 알림
════════════════════════════════════════════════════════════════════════════

  -- 얼마나 돌았나
  SELECT strategy_name, count(DISTINCT coin_pair) AS coins,
         to_char(max(created_at) AT TIME ZONE 'Asia/Seoul','MM-DD HH24:MI') AS latest
  FROM backtest_run WHERE is_walk_forward AND created_at > now() - interval '1 day'
  GROUP BY 1 ORDER BY 1;

  -- 통과 조합 (게이트를 켜면 살 수 있는 것들)
  SELECT strategy_name, coin_pair,
         wf_result_json->>'verdict' AS verdict,
         (wf_result_json->'aggregatedOutSample'->>'totalTrades')::int   AS n,
         round((wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric, 3) AS oos_exp
  FROM backtest_run
  WHERE is_walk_forward AND created_at > now() - interval '1 day'
    AND wf_result_json->>'verdict' <> 'OVERFITTING'
    AND (wf_result_json->'aggregatedOutSample'->>'expectancyPct')::numeric > 0
    AND (wf_result_json->'aggregatedOutSample'->>'totalTrades')::int >= 5
  ORDER BY oos_exp DESC;

▶ 09-14 에 이 결과로 답해야 할 질문
  · 통과 조합이 워치리스트를 몇 % 덮는가 — 게이트를 켰을 때 거래가 유지되는가
  · 정말 검증 불가능한 코인은 어디까지인가 — 09-02 수집 결과 ZKC·LA·0G·MIRA 는
    8,000행대로 자격을 얻었고, 남은 것은 DOS(512) · PROM(492) · CRV(266) 뿐이다.
    전부 2026-08 상장이라 이력이 정말 없다.
    → "게이트 ON = 최근 3주 내 상장 코인 배제"이고, 그 교환을 받아들일지가 결정이다
  · 함대가 실제로 산 조합이 통과 목록에 있는가 — 없다면 지금 쌓는 표본의 성격이 달라진다
NOTE
