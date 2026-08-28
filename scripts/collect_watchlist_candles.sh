#!/usr/bin/env bash
#
# 감시목록 코인 캔들 수집 — WF 커버리지 확장의 선행 작업 (2026-08-28)
# ─────────────────────────────────────────────────────────────────────────────
# ■ 왜 필요한가 — WF 를 돌리려 했더니 데이터가 없었다
#
#   DYNAMIC 메인 전환의 전제조건이 "감시목록 코인이 WF 로 검증돼 있을 것"인데, 실측해보니
#   **미검증 21개 코인 중 18개는 candle_data 가 아예 0행**이었다. WF 는 candle_data 를 읽으므로
#   수집 없이는 실행 자체가 불가능하다. 순서가 캔들 수집 → WF 다.
#
#     KRW-SLX  332회 평가 / 0행      KRW-ZRO  107회 / 0행     KRW-NCT   88회 / 0행
#     KRW-ONT  314회 평가 / 0행      KRW-LSK  104회 / 0행     KRW-GAS   65회 / 0행
#     KRW-META2 125회 / 0행          KRW-MLK  103회 / 0행     KRW-BEAM  64회 / 0행
#     KRW-LIT  108회 / 0행           KRW-PROM 167회 / 276행(부족)
#
# ■ 왜 이 11개만인가
#   최근 3일 평가 **60회 이상** — 감시목록에 지속적으로 들어온 코인만 고른다. 그 아래
#   (DRV 41 · FLUID 24 · PYTH 22 · SNT 22 · MINA 18 · EUL 15 · VIRTUAL 8 · FOLD 7 ·
#   ONG 4 · KERNEL 1)는 잠깐 스쳐 지나간 것이라, 지금 수집해도 다음 주엔 감시목록에 없을 수 있다.
#   워치리스트가 계속 도는 이상 이 작업은 **주기적으로 다시** 해야 한다.
#
# ■ candle_data 수집은 자동이 아니다
#   DataCollectionService 에는 @Scheduled 가 없다 — POST /data/collect/batch 수동 호출뿐이다.
#   그래서 최신이 **2026-08-24 에 멈춰 있다**(38코인). 기존 코인 갭도 함께 채운다.
#
# ■ 소요 시간
#   Upbit 공개 API 는 앱 전체가 공유 스로틀(초당 ~9회)로 직렬화된다. H1 4년치면 코인당
#   수백 페이지다. **1~2시간 이상 걸릴 수 있고**, 그동안 DYNAMIC 틱의 시세 조회가 뒤로 밀려
#   사이클이 지연될 수 있다. 거래가 한산한 시간대에 돌리는 것을 권한다.
#
# 사용법: 운영 서버에서 (리포 루트에서) 실행
#   bash scripts/collect_watchlist_candles.sh
#   nohup bash scripts/collect_watchlist_candles.sh > /tmp/collect.log 2>&1 &   # 백그라운드

set -uo pipefail

API="http://localhost:8080/api/v1"
TIMEFRAME="H1"
START_DATE="2022-01-01"          # 거래소에 없으면 상장일부터만 온다
END_DATE=$(date -u +%Y-%m-%d)

# 신규 수집 대상 — 최근 3일 평가 60회 이상 & candle_data 없음/부족
NEW_COINS="KRW-SLX KRW-ONT KRW-META2 KRW-LIT KRW-ZRO KRW-LSK KRW-MLK KRW-NCT KRW-GAS KRW-BEAM KRW-PROM"

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
echo "  기간: $START_DATE ~ $END_DATE ($TIMEFRAME)"

echo
echo "⚠️  이 작업은 1~2시간 이상 걸릴 수 있고 그동안 DYNAMIC 시세 조회가 지연될 수 있습니다."
printf '    계속하려면 yes 입력: '
read -r go
[ "$go" = "yes" ] || { echo "중단."; exit 1; }

collect() {
  local coin="$1" label="$2"
  printf '  %-12s %-8s ' "$coin" "$label"
  local resp
  resp=$(api -X POST "$API/data/collect/batch" \
    -H 'Content-Type: application/json' \
    -d "{\"coinPairs\":[\"$coin\"],\"timeframe\":\"$TIMEFRAME\",
         \"startDate\":\"$START_DATE\",\"endDate\":\"$END_DATE\"}")
  echo "$resp" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
    if d.get("data") is not None:
        print("✓", json.dumps(d["data"], ensure_ascii=False)[:110])
    else:
        print("✗", str(d)[:150])
except Exception:
    print("✗ 응답 파싱 실패")' 2>/dev/null || echo "✗"
}

# ── 1. 신규 코인 ─────────────────────────────────────────────────────────────
printf '\n\033[1m▶ 1단계: 신규 코인 %s개 전체 수집\033[0m\n' "$(echo $NEW_COINS | wc -w)"
for coin in $NEW_COINS; do
  collect "$coin" "신규"
done

# ── 2. 기존 코인 갭 채우기 (08-24 에서 멈춰 있음) ────────────────────────────
printf '\n\033[1m▶ 2단계: 기존 코인 갭 채우기\033[0m\n'
existing=$(api "$API/data/summary" | python3 -c '
import json,sys
seen=set()
for r in json.load(sys.stdin)["data"]:
    c=r.get("coinPair") or r.get("coin_pair")
    tf=r.get("timeframe")
    if c and tf=="H1": seen.add(c)
print(" ".join(sorted(seen)))' 2>/dev/null)

if [ -z "$existing" ]; then
  echo "  ⚠️ 기존 코인 목록을 읽지 못했습니다 — 1단계 결과만 확인하고 수동으로 진행하세요."
else
  GAP_START=$(date -u -d '10 days ago' +%Y-%m-%d 2>/dev/null || date -u -v-10d +%Y-%m-%d)
  echo "  갭 구간: $GAP_START ~ $END_DATE"
  for coin in $existing; do
    case " $NEW_COINS " in *" $coin "*) continue ;; esac   # 1단계에서 이미 받음
    printf '  %-12s %-8s ' "$coin" "갭"
    resp=$(api -X POST "$API/data/collect/batch" \
      -H 'Content-Type: application/json' \
      -d "{\"coinPairs\":[\"$coin\"],\"timeframe\":\"$TIMEFRAME\",
           \"startDate\":\"$GAP_START\",\"endDate\":\"$END_DATE\"}")
    echo "$resp" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
    print("✓" if d.get("data") is not None else "✗ "+str(d)[:100])
except Exception: print("✗")' 2>/dev/null || echo "✗"
  done
fi

# ── 3. 결과 ──────────────────────────────────────────────────────────────────
printf '\n\033[1m▶ 수집 결과\033[0m\n'
api "$API/data/summary" | python3 -c '
import json,sys
rows=[r for r in json.load(sys.stdin)["data"] if (r.get("timeframe")=="H1")]
rows.sort(key=lambda r: -(r.get("count") or r.get("rowCount") or 0))
print("  %-12s %8s  %s" % ("코인","행수","기간"))
for r in rows[:50]:
    c=r.get("coinPair") or r.get("coin_pair")
    n=r.get("count") or r.get("rowCount") or 0
    print("  %-12s %8s  %s ~ %s" % (c, n, str(r.get("startTime"))[:10], str(r.get("endTime"))[:10]))
' 2>/dev/null || echo "  (요약 파싱 실패 — DB 로 직접 확인)"

cat <<'NOTE'

════════════════════════════════════════════════════════════════════════════
▶ 다음 단계 — WF 배치는 이 수집이 끝난 뒤에 실행할 것
════════════════════════════════════════════════════════════════════════════

  bash scripts/rerun_walk_forward_watchlist.sh    (기존 스크립트)

  실행 전 DB 로 수집 결과부터 확인:

    SELECT coin_pair, count(*) AS rows,
           to_char(min("time"),'YYYY-MM-DD') AS oldest,
           to_char(max("time"),'YYYY-MM-DD') AS newest
    FROM candle_data WHERE timeframe='H1'
      AND coin_pair IN ('KRW-SLX','KRW-ONT','KRW-META2','KRW-LIT','KRW-ZRO','KRW-LSK',
                        'KRW-MLK','KRW-NCT','KRW-GAS','KRW-BEAM','KRW-PROM')
    GROUP BY 1 ORDER BY 2;

  · 행수가 2,000 미만인 코인은 WF 구간(in-sample + out-of-sample)이 안 나온다 —
    상장이 최근이라 거래소에 이력 자체가 없는 경우다. 그 코인은 WF 대상에서 빼고,
    "이력 부족"으로 기록해 둘 것(억지로 돌리면 표본이 얇은 백테스트가 통과로 나온다).
  · newest 가 오늘이 아니면 수집이 중간에 끊긴 것 — 해당 코인만 재실행.
NOTE
