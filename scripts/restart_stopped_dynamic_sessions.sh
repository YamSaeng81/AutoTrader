#!/usr/bin/env bash
#
# 정지된 동적 세션 재기동 — 세션 60 / 62 (2026-08-28)
# ─────────────────────────────────────────────────────────────────────────────
# ■ 왜 "재시작"이 아니라 "신규 생성"인가
#
#   두 세션 모두 mdd_peak_capital 이 남아 있어 MDD 가 이미 한도(20%)를 넘은 상태다:
#
#     세션 60  MOMENTUM_ICHIMOKU_V2 H1   peak 10,639 → total 8,444   MDD 20.64%  (trips=1)
#     세션 62  MOMENTUM_ICHIMOKU    M15  peak 10,630 → total 8,405   MDD 20.93%  (trips=0)
#
#   startSession() 은 status 만 RUNNING 으로 되돌릴 뿐 mdd_peak_capital 을 손대지 않는다.
#   그래서 그냥 start 하면 **다음 틱에 서킷 브레이커가 즉시 재발동**한다. 연속손실 카운트는
#   startedAt 이후 청산분만 집계해 리셋되지만 MDD 는 피크 기준이라 리셋되지 않는다.
#
#   → 같은 설정으로 신규 세션을 만든다. 페이퍼라 비용은 0이고, 설정이 같으면 지문도 같아
#     기존 표본과 이어진다.
#
# ■ 왜 재기동하나
#   두 전략 모두 KILL_CRITERIA 엣지 판정 기준(n≥20)에 못 미친다:
#     MOMENTUM_ICHIMOKU_V2  15건 (5건 부족)   승률 33.3%  −1,577원
#     MOMENTUM_ICHIMOKU      9건 (11건 부족)  승률 33.3%  −2,607원
#   지금은 "나쁘다"는 방향만 보이고 통계적 유의성이 없다. 표본을 채워야 죽일지 말지
#   데이터로 확정할 수 있다.
#
#   ⚠️ 재기동해도 같은 이유(MDD 20% 초과)로 또 멈출 가능성이 있다. 그 자체가 전략에 대한
#      정보다 — 두 번째 발동은 "우연히 나쁜 구간"이 아니라 구조적 문제라는 근거가 된다.
#
# ■ 정지된 세션은 지우지 않는다
#   60·62 는 EMERGENCY_STOPPED 로 남겨둔다. 청산 이력이 KILL_CRITERIA 집계에 들어가야 하고,
#   서킷 브레이커 발동 기록(08-27 00:59, 데드락 수정 실전 검증 근거)도 보존해야 한다.
#
# 사용법: 운영 서버에서 (리포 루트에서) 실행
#   bash scripts/restart_stopped_dynamic_sessions.sh

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

running=$(echo "$probe" | python3 -c '
import json,sys
print(sum(1 for s in json.load(sys.stdin)["data"] if s["status"]=="RUNNING"))')
echo "  현재 RUNNING 동적 세션: $running → 생성 후 $((running + 2))"

# ── 생성 — 60·62 의 설정을 그대로 복제 ───────────────────────────────────────
# (DB 에서 읽은 실제 값: investRatio 0.80, stopLossPct 5.00, maxCandidateSize 30,
#  targetWatchSize 10, minAtrPct 0.50, maxSpreadPct 0.10, maxHoldHours 24,
#  watchlistRefreshMin H1=60 / M15=30, strategyParams NULL)
create() {
  local label="$1" strategy="$2" tf="$3" refresh="$4"
  printf '\n\033[1m▶ %s\033[0m\n' "$label"
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
    echo "  ✗ 생성 실패: $(echo "$resp" | head -c 300)"
    return 1
  fi
  api -X POST "$API/dynamic-sessions/$id/start" > /dev/null
  echo "  ✓ id=$id 시작 ($strategy $tf)"
}

create "세션 60 대체 — MOMENTUM_ICHIMOKU_V2 H1"  COMPOSITE_MOMENTUM_ICHIMOKU_V2 H1  60
create "세션 62 대체 — MOMENTUM_ICHIMOKU M15"    COMPOSITE_MOMENTUM_ICHIMOKU    M15 30

# ── 확인 ─────────────────────────────────────────────────────────────────────
printf '\n\033[1m▶ ICHIMOKU 계열 세션 현황\033[0m\n'
api "$API/dynamic-sessions" | python3 -c '
import json,sys
for s in json.load(sys.stdin)["data"]:
    if "ICHIMOKU" in s["strategyType"]:
        print("  %3s  %-32s %-4s %-18s %s" % (
            s["id"], s["strategyType"], s["timeframe"], s["status"],
            s.get("strategyParams") or ""))
'

cat <<'NOTE'

▶ 확인 (DB) — 신규 세션의 지문이 기존 표본과 같은지

    SELECT d.id, d.strategy_type, d.timeframe, left(l.ruleset_hash,12) AS hash, count(*)
    FROM strategy_log l JOIN dynamic_session d ON d.id = l.session_id
    WHERE l.session_type='DYN_PAPER' AND d.strategy_type LIKE '%ICHIMOKU%'
      AND l.created_at > now() - interval '2 hours'
    GROUP BY 1,2,3,4 ORDER BY 2,3,1;

  기대: 신규 세션의 해시가 기존 60·62 의 것과 **같아야** 표본이 이어진다.
  다르면 그 사이 배포로 지문이 갈린 것 — KILL_CRITERIA 집계 시 분리해서 봐야 한다.

▶ 관찰 포인트
  · 두 세션이 며칠 안에 다시 MDD 20% 로 멈추는지. 멈추면 그것이 곧 판정 근거다.
  · KILL_CRITERIA 도달까지: ICHIMOKU_V2 5건 / ICHIMOKU 11건 남았다.
NOTE
