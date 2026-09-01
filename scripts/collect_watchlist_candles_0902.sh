#!/usr/bin/env bash
#
# 감시목록 캔들 수집 — WF 커버리지 확장의 선행 작업 (2026-09-02)
# ─────────────────────────────────────────────────────────────────────────────
# ■ 왜 또 하나 — 워치리스트가 통째로 바뀌었다
#
#   08-28 판에서 수집한 코인(GAS·ONT·MLK·LSK·BEAM·ZRO·SLX)은 지금 워치리스트에
#   **ONT 하나만** 남아 있다. 워치리스트는 거래량 상위로 계속 돌기 때문에
#   이 작업은 주기적으로 다시 해야 한다.
#
# ■ 09-02 실측 — 워치리스트 20코인 중 WF 가능(H1 2,000행 이상)은 3개뿐
#
#     ✅ KRW-BTC 3,180 · KRW-ETH 3,180 · KRW-XRP 2,843
#     ❌ 나머지 17개는 0~667행
#
#   그 결과 **함대가 실제로 산 12조합 중 10조합이 WF 이력 0건**이다.
#   FLOCK +617 · DOS −437 · TREE −436/−450 — 전부 미검증 코인에서 나온 손익이다.
#   09-14 에 "게이트를 켤까"를 판단하려면 이 공백부터 메워야 한다.
#
# ■ 🔴 중요 — 이 17개는 두 종류가 섞여 있다
#
#   (A) 오래된 코인인데 그냥 수집을 안 한 것 — 수집하면 수년치가 들어온다
#       KRW-ICX(2018 상장, 현재 H1 0행!) · KRW-CRV(H1 0행) · KRW-UNI(557행)
#       KRW-ARB · KRW-ONT · KRW-ENA · KRW-ONDO · KRW-TRUMP · KRW-PROM · KRW-SOL
#
#   (B) 진짜 신규 상장 — 수집해도 거래소에 이력이 없다
#       KRW-FLOCK · KRW-LA · KRW-ZKC · KRW-MIRA · KRW-0G · KRW-DOS · KRW-AXL · KRW-TREE
#
#   (B) 는 수집 후에도 2,000행에 못 미칠 것이다. **그건 실패가 아니라 결론이다** —
#   "게이트를 켜면 신규 상장 코인은 영구히 못 산다"는 사실이 09-14 결정의 입력값이 된다.
#   억지로 WF 를 돌리면 얇은 표본이 "통과"로 나와 검증됐다고 착각하게 만든다(08-28 교훈).
#
# ■ 소요 시간
#   Upbit 공개 API 는 앱 전체가 공유 스로틀(초당 ~9회)로 직렬화된다. H1 4년치면
#   코인당 수백 페이지다. **1~2시간 이상 걸릴 수 있고**, 그동안 DYNAMIC 틱의 시세 조회가
#   뒤로 밀려 사이클이 지연될 수 있다. 한산한 시간대를 권한다.
#
#   ⚠️ 수집 중에는 재빌드하지 말 것. 비동기 큐가 컨테이너와 함께 날아간다(08-30 전례).
#
# 사용법: 운영 서버에서 (리포 루트에서) 실행
#   bash scripts/collect_watchlist_candles_0902.sh                                 # 대화형
#   nohup bash scripts/collect_watchlist_candles_0902.sh > /tmp/collect0902.log 2>&1 &
#   bash scripts/collect_watchlist_candles_0902.sh --yes                           # 확인 생략

set -uo pipefail

API="http://localhost:8080/api/v1"
TIMEFRAME="H1"
START_DATE="2022-01-01"          # 거래소에 없으면 상장일부터만 온다
END_DATE=$(date -u +%Y-%m-%d)

# 09-02 워치리스트 20코인 중 H1 2,000행 미만인 17개
# (BTC·ETH·XRP 는 이미 충분해서 제외 — 갭 채우기 단계에서 따로 다룬다)
TARGET_COINS="KRW-ICX KRW-CRV KRW-UNI KRW-ARB KRW-ONT KRW-ENA KRW-ONDO KRW-TRUMP \
KRW-PROM KRW-SOL KRW-FLOCK KRW-LA KRW-ZKC KRW-MIRA KRW-0G KRW-DOS KRW-AXL"

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
echo "  기간: $START_DATE ~ $END_DATE ($TIMEFRAME) · 대상 $(echo $TARGET_COINS | wc -w)개"

printf '\n\033[1m▶ 수집 전 현황\033[0m\n'
echo "$probe" | python3 -c '
import json,sys
targets = """'"$TARGET_COINS"'""".split()
have = {}
for r in json.load(sys.stdin)["data"]:
    if r.get("timeframe") != "H1": continue
    c = r.get("coinPair") or r.get("coin_pair")
    have[c] = r.get("count") or r.get("rowCount") or 0
for t in targets:
    n = have.get(t, 0)
    print("  %-12s %6s행  %s" % (t, n, "OK" if n>=2000 else "부족"))
'

echo
echo "⚠️  1~2시간 이상 걸릴 수 있고 그동안 DYNAMIC 시세 조회가 지연될 수 있습니다."
echo "⚠️  수집이 끝나기 전에는 재빌드하지 마세요 — 비동기 큐가 함께 날아갑니다."

# nohup/백그라운드로 돌리면 stdin 이 없다 — TTY 일 때만 묻는다.
if [ "${1:-}" = "--yes" ] || [ ! -t 0 ]; then
  echo "    (비대화형 실행 — 확인 없이 진행합니다)"
else
  printf '    계속하려면 yes 입력: '
  read -r go
  [ "$go" = "yes" ] || { echo "중단."; exit 1; }
fi

# ── 수집 ─────────────────────────────────────────────────────────────────────
# 08-30 교훈: 코인마다 따로 비동기 배치를 던지면 큐가 조각나고 재빌드 한 번에 전부
# 사라진다. **한 번의 배치로 전 코인을 넘긴다.**
printf '\n\033[1m▶ 일괄 수집 요청 (단일 배치)\033[0m\n'
COIN_JSON=$(printf '%s\n' $TARGET_COINS | python3 -c '
import json,sys
print(json.dumps([l.strip() for l in sys.stdin if l.strip()]))')
echo "  대상: $COIN_JSON"

resp=$(api -X POST "$API/data/collect/batch" \
  -H 'Content-Type: application/json' \
  -d "{\"coinPairs\": $COIN_JSON, \"timeframe\":\"$TIMEFRAME\",
       \"startDate\":\"$START_DATE\",\"endDate\":\"$END_DATE\"}")
echo "$resp" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
    print("  응답:", json.dumps(d.get("data", d), ensure_ascii=False)[:400])
except Exception:
    print("  응답 파싱 실패:", sys.stdin.read()[:300])' 2>/dev/null

cat <<'NOTE'

════════════════════════════════════════════════════════════════════════════
▶ 완료 확인 (수집은 비동기다 — 응답이 STARTED 여도 아직 도는 중)
════════════════════════════════════════════════════════════════════════════

  # 진행 상황 — 행수가 계속 늘면 도는 중이다
  docker compose -f docker-compose.prod.yml logs --since 5m backend | grep -i "collect\|batch" | tail

  # 최종 확인 (DB)
  SELECT coin_pair, count(*) AS h1_rows,
         to_char(min(time),'YYYY-MM-DD') AS first_candle
  FROM market_data_cache WHERE timeframe='H1'
    AND coin_pair IN ('KRW-ICX','KRW-CRV','KRW-UNI','KRW-ARB','KRW-ONT','KRW-ENA',
                      'KRW-ONDO','KRW-TRUMP','KRW-PROM','KRW-SOL','KRW-FLOCK','KRW-LA',
                      'KRW-ZKC','KRW-MIRA','KRW-0G','KRW-DOS','KRW-AXL')
  GROUP BY 1 ORDER BY 2 DESC;

▶ 다음 단계
  2,000행을 넘긴 코인만 골라 WF 를 돌린다:
      bash scripts/walk_forward_watchlist_0902.sh
  그 스크립트가 PRECHECK 에서 자동으로 걸러내므로, 먼저 돌려보고 목록을 확인해도 된다.

▶ 2,000행에 못 미치는 코인이 남는다면
  그건 실패가 아니라 **결론**이다 — 거래소에 이력 자체가 없다는 뜻이고,
  "게이트를 켜면 신규 상장 코인은 영구히 못 산다"가 09-14 결정의 입력값이 된다.
  얇은 표본으로 WF 를 강행하지 말 것(통과로 나와도 의미가 없고, 오히려 위험하다).
NOTE
