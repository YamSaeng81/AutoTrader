#!/usr/bin/env bash
#
# DYNAMIC 페이퍼 함대 증설 — 표본 축적 속도 보정 (2026-09-02)
# ─────────────────────────────────────────────────────────────────────────────
# ■ 왜 늘리나 — 09-14 에 5전략 중 1개만 판정 가능한 궤도다
#
#   재기동(08-31 06:25) 후 2.09일 실측 vs 동결 선언 때 세운 예상표:
#
#     전략                     실제/일   예상표   12일 후 예상 n   n>=20
#     MTF_BTC                   1.43      3.4        20.2         겨우
#     MTF_CONFIRMED             0.96      2.0        13.5          ✗
#     MOMENTUM_ICHIMOKU         0.48      1.8         6.8          ✗
#     MEANREV_BB                0.00      2.4         0            ✗
#     MOMENTUM_ICHIMOKU_V2      0.00      1.8         0            ✗
#
#   예상표가 틀린 이유는 단순하다 — 그 숫자는 **구 함대(16세션, PULLBACK_MTF 5세션 포함)**
#   속도였고 지금은 9세션이다. 분모를 같이 적지 않은 탓이다.
#
# ■ 🔴 그런데 무작정 늘리면 안 된다 — 병목이 전략마다 다르다
#
#   게이트 반영(09-01 09:37) 이후 22시간 실측:
#
#     전략                    세션  BUY  체결  '이미보유' 차단   진단
#     MTF_CONFIRMED            2     9    3        6          자리 부족 → 증설 효과 큼
#     MOMENTUM_ICHIMOKU        2     7    2        5          자리 부족 → 증설 효과 큼
#     MTF_BTC                  2     5    3        0          신호가 적음
#     MEANREV_BB               1     1    1        0          M15 세션이 아예 없음
#     MOMENTUM_ICHIMOKU_V2     2     1    1        0          신호 부족 → 증설 무의미
#
#   MTF_CONFIRMED 는 BUY 9건 중 6건이 "이미 보유 중"으로 막혔다. 자리를 늘리면
#   그만큼 표본이 는다. 반면 **ICHIMOKU_V2 는 22시간에 BUY 1건, HOLD 1,284건**이다.
#   세션을 10개로 늘려도 안 산다. 그건 표본 문제가 아니라 전략이 신호를 안 내는 것이다.
#
# ■ 증설안 — 9세션 → 16세션 (+7)
#
#     MTF_CONFIRMED        H1 +1, M15 +1     자리 차단 6건, 효과 확실
#     MOMENTUM_ICHIMOKU    H1 +1, M15 +1     자리 차단 5건, 효과 확실
#     MTF_BTC              H1 +1, M15 +1     n 최다(3)라 20 도달이 관건
#     MEANREV_BB           M15 +1            빠진 조합 보충(M15 는 평가 빈도가 4배)
#     MOMENTUM_ICHIMOKU_V2 +0                🔴 의도적으로 늘리지 않는다
#
#   ⚠️ ICHIMOKU_V2 를 증설하지 않는 것은 게으름이 아니라 **판단**이다.
#      09-14 에 이 전략의 n 이 0이면 그건 "판정 불가"가 아니라
#      **"이 전략은 거래를 하지 않는다"는 것 자체가 판정 근거**다.
#      세션을 늘려 억지로 표본을 만들면 그 사실이 가려진다.
#
# ■ 동결과의 관계 — 규칙 변경이 아니다
#
#   동결 금지 목록: 게이트 임계값 · 노출 상한 · 청산 규칙 · 워치리스트 필터 ·
#                   전략 파라미터 · 신규 A/B
#   세션 **개수**는 여기에 없다. 그리고 아래 파라미터가 기존과 완전히 동일하므로
#   **지문(ruleset_hash)도 같아서 표본을 합칠 수 있다.** 규칙은 그대로고 관측 채널만 는다.
#
#   기존 9세션에서 그대로 읽은 값 (09-02 DB 확인):
#     initialCapital 10000 · investRatio 0.80 · stopLossPct 5.0 · maxHoldHours 24
#     maxCandidateSize 30 · targetWatchSize 10 · minAtrPct 0.5 · maxSpreadPct 0.1
#     watchlistRefreshMin  H1=60 / M15=30 · strategyParams 없음
#
#   현재 지문: H1 = eb87e77d4b86 · M15 = 95a8d3370709
#   → 새 세션의 첫 진입이 이 값과 같은지 반드시 확인할 것(아래 확인 쿼리).
#
# ■ 남는 위험 — 노출 상한이 새 병목이 될 수 있다
#
#   09-02 현재 노출 상한(2) 차단은 **0건**이지만, 세션이 16개가 되면 같은 코인에
#   3세션 이상이 몰리는 일이 늘어난다. 그러면 "누가 먼저 잡느냐"가 표본을 가르게 되고
#   그건 편향이다. **09-07 점검에서 차단 사유에 노출 상한이 등장하는지 반드시 볼 것.**
#
# ■ 기존 세션은 건드리지 않는다
#   76~84 는 그대로 둔다. 정지·재생성하면 MDD 고점과 누적 표본이 리셋된다.
#
# 사용법: 운영 서버에서 (리포 루트에서) 실행
#   bash scripts/expand_dynamic_fleet_0902.sh
#   bash scripts/expand_dynamic_fleet_0902.sh --yes    # 확인 생략

set -uo pipefail

API="http://localhost:8080/api/v1"

if [ -z "${API_AUTH_TOKEN:-}" ] && [ -f .env ]; then
  API_AUTH_TOKEN=$(grep -E '^API_AUTH_TOKEN=' .env | head -1 | cut -d= -f2- | tr -d '"'"'"'')
fi
if [ -z "${API_AUTH_TOKEN:-}" ]; then
  echo "✗ API_AUTH_TOKEN 을 찾을 수 없습니다."; exit 1
fi
AUTH="Authorization: Bearer $API_AUTH_TOKEN"
api() { curl -s -H "$AUTH" "$@"; }

probe=$(api "$API/dynamic-sessions")
case "$probe" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드 확인"; exit 1 ;;
esac
echo "✓ 인증 확인"

printf '\n\033[1m▶ 증설 전 현황 (RUNNING)\033[0m\n'
echo "$probe" | python3 -c '
import json,sys
from collections import Counter
c=Counter(); n=0
for s in json.load(sys.stdin)["data"]:
    if s["status"]=="RUNNING":
        n+=1; c[(s["strategyType"], s["timeframe"])]+=1
for (st,tf),v in sorted(c.items()):
    print("  %-32s %-4s %d세션" % (st, tf, v))
print("  ─────────────────────────────────────")
print("  총 %d세션" % n)
'

cat <<'PLAN'

▶ 증설 계획 (+7 → 총 16세션)
    COMPOSITE_MTF_CONFIRMED        H1 +1  M15 +1    (자리 차단 6건)
    COMPOSITE_MOMENTUM_ICHIMOKU    H1 +1  M15 +1    (자리 차단 5건)
    COMPOSITE_MTF_BTC              H1 +1  M15 +1    (n 최다, 20 도달 관건)
    COMPOSITE_MEANREV_BB                  M15 +1    (빠진 조합 보충)
    COMPOSITE_MOMENTUM_ICHIMOKU_V2        증설 없음  ← 22시간 BUY 1건, 신호 부족
PLAN

if [ "${1:-}" = "--yes" ] || [ ! -t 0 ]; then
  echo "    (비대화형 실행 — 확인 없이 진행합니다)"
else
  printf '\n    계속하려면 yes 입력: '
  read -r go
  [ "$go" = "yes" ] || { echo "중단."; exit 1; }
fi

# ── 생성 ─────────────────────────────────────────────────────────────────────
# ⚠️ 워치리스트 필터를 **명시적으로** 넘긴다. risk_config 값이 나중에 바뀌어도
#    이 함대의 기준선이 흔들리지 않게 하기 위해서다(08-07 에 필터가 조용히 바뀌어
#    감시 코인이 62종→10종으로 붕괴한 전례가 있다).
create() {
  local strategy="$1" tf="$2" refresh="$3"
  local resp id
  resp=$(api -X POST "$API/dynamic-sessions" \
    -H 'Content-Type: application/json' \
    -d "{\"strategyType\":\"$strategy\",\"timeframe\":\"$tf\",
         \"initialCapital\":10000,\"stopLossPct\":5.0,\"investRatio\":80,
         \"maxCandidateSize\":30,\"targetWatchSize\":10,
         \"minAtrPct\":0.5,\"maxSpreadPct\":0.1,
         \"watchlistRefreshMin\":$refresh,\"maxHoldHours\":24,
         \"tradingMode\":\"PAPER\"}")
  id=$(echo "$resp" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin); print(d["data"]["id"] if d.get("data") else "")
except Exception: print("")' 2>/dev/null)
  if [ -z "$id" ]; then
    echo "  ✗ $strategy $tf — $(echo "$resp" | head -c 220)"
    return 1
  fi
  api -X POST "$API/dynamic-sessions/$id/start" > /dev/null
  echo "  ✓ id=$id  $strategy $tf (refresh ${refresh}분)"
}

printf '\n\033[1m▶ 세션 생성\033[0m\n'
ok=0; fail=0
for spec in \
  "COMPOSITE_MTF_CONFIRMED H1 60" \
  "COMPOSITE_MTF_CONFIRMED M15 30" \
  "COMPOSITE_MOMENTUM_ICHIMOKU H1 60" \
  "COMPOSITE_MOMENTUM_ICHIMOKU M15 30" \
  "COMPOSITE_MTF_BTC H1 60" \
  "COMPOSITE_MTF_BTC M15 30" \
  "COMPOSITE_MEANREV_BB M15 30" ; do
  # shellcheck disable=SC2086
  if create $spec; then ok=$((ok+1)); else fail=$((fail+1)); fi
done
printf '\n  결과: 생성 %s / 실패 %s\n' "$ok" "$fail"

printf '\n\033[1m▶ 증설 후 현황\033[0m\n'
api "$API/dynamic-sessions" | python3 -c '
import json,sys
from collections import Counter
c=Counter(); n=0
for s in json.load(sys.stdin)["data"]:
    if s["status"]=="RUNNING":
        n+=1; c[(s["strategyType"], s["timeframe"])]+=1
for (st,tf),v in sorted(c.items()):
    print("  %-32s %-4s %d세션" % (st, tf, v))
print("  ─────────────────────────────────────")
print("  총 %d세션" % n)
'

cat <<'NOTE'

════════════════════════════════════════════════════════════════════════════
▶ 🔴 반드시 확인할 것 — 지문이 기존과 같은가
════════════════════════════════════════════════════════════════════════════

  새 세션의 첫 진입이 나온 뒤(수 시간) 아래를 돌린다. 지문이 다르면 **표본을 합칠 수
  없고**, 증설이 오히려 데이터를 갈라놓은 셈이 된다. 그때는 새 세션을 정지할 것.

  SELECT d.timeframe, p.ruleset_hash,
         count(*) AS positions,
         string_agg(DISTINCT p.session_id::text, ',' ORDER BY p.session_id::text) AS sessions
  FROM position p JOIN dynamic_session d ON d.id = p.session_id
  WHERE p.session_kind='DYN_PAPER'
    AND p.opened_at > now() - interval '2 days'
  GROUP BY 1,2 ORDER BY 1 DESC, 3 DESC;

  기대값:  H1 = eb87e77d4b86 (기존과 동일) · M15 = 95a8d3370709 (기존과 동일)
  타임프레임당 지문이 하나면 정상이다.

▶ 09-07 점검에서 볼 것
  · 표본 축적 속도가 실제로 올랐는가 (전략별 건/일)
  · **노출 상한(2) 차단이 등장했는가** — 등장했다면 병목이 자리에서 상한으로 옮겨간
    것이고, 그때부터는 "누가 먼저 잡느냐"가 표본을 가른다(편향).
  · ICHIMOKU_V2 는 여전히 n=0 인가 — 그렇다면 그것이 09-14 판정의 답이다.

▶ 하지 않은 것
  · 기존 76~84 는 건드리지 않았다 — 정지·재생성하면 MDD 고점과 누적 표본이 리셋된다.
  · 매매 규칙·파라미터는 하나도 바꾸지 않았다. 동결은 유지된다.
NOTE
