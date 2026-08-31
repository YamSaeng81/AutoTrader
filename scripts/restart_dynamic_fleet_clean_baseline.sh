#!/usr/bin/env bash
#
# DYNAMIC 페이퍼 함대 재기동 — 깨끗한 기준선 + 규칙 동결 (2026-08-31)
# ─────────────────────────────────────────────────────────────────────────────
# ■ 왜 재기동하나 — 세 가지
#
#   ① 규칙이 12일간 5번 넘게 바뀌었다. 08-19 이후 DYN_PAPER 지문이 18개로 갈렸다.
#      지금 세션들의 누적 성과는 서로 다른 규칙의 거래가 섞인 혼합물이다.
#
#   ② **오늘 한 WS 실시간 감시 개방은 지문에 안 실린다.** 코드 상수가 아니라 감시 경로의
#      변화라서다. SL/TP 체결 시점이 빨라졌는데 사후에 구분할 방법이 시각뿐이다.
#
#   ③ MDD 고점이 발목을 잡는다 — 서킷 브레이커는 `mdd_peak_capital` 기준이라 과거 고점이
#      계속 따라다닌다:
#
#        세션 64  MTF_BTC M15    자본 11,910원(+19%)인데 고점 14,143 → MDD 15.79%, 여유 4.21%p
#        세션 54  MEANREV_BB H1  자본  8,599원        고점 10,001 → MDD 14.01%, 여유 5.99%p
#
#      64는 **성과가 좋은데도** 조금만 빠지면 서킷에 걸린다.
#
# ■ 잃는 것은 없다
#   `position` 행은 세션 상태와 무관하게 남고, KILL_CRITERIA 는 `strategy_type` 기준으로
#   집계한다. 재기동해도 판정 표본(MTF_BTC 26건 · MEANREV_BB 22건 등)은 그대로다.
#   그래서 기존 세션은 **정지만 하고 삭제하지 않는다.**
#
# ■ 🔴 재기동보다 중요한 것 — 규칙 동결
#
#   "매번 바뀌면서 데이터를 새로 쌓는 게 문제"라는 지적이 정확하다. 문제는 리셋 장치가
#   아니라 **변경 속도**다. 12일에 5번 바꾸면 어떤 측정 체계도 무너진다.
#
#   그래서 이 재기동은 **동결 선언과 한 세트**다:
#
#     ┌─────────────────────────────────────────────────────────────┐
#     │  2026-08-31 ~ 09-14 (2주) 매매 규칙 변경 금지               │
#     │                                                             │
#     │  금지: 게이트 임계값·노출 상한·청산 규칙·워치리스트 필터    │
#     │        전략 파라미터·신규 A/B                               │
#     │  허용: P0 버그 수정(버그는 규칙이 아니다) · 관측/로깅 추가  │
#     │        WF 배치 · 캔들 수집                                  │
#     └─────────────────────────────────────────────────────────────┘
#
#   2주인 근거 — 현재 거래 속도로 5전략이 KILL_CRITERIA 판정선(n≥20)에 닿는 데 걸리는 시간:
#
#     MTF_BTC          3.4건/일 →  6일
#     MEANREV_BB       2.4건/일 →  9일
#     MTF_CONFIRMED    2.0건/일 → 10일
#     MOMENTUM_ICHIMOKU     1.8건/일 → 12일
#     MOMENTUM_ICHIMOKU_V2  1.8건/일 → 12일
#
#   2주 뒤에는 **5전략 전부를 같은 규칙 위에서 판정**할 수 있다. 그게 지난 12일 동안
#   한 번도 못 한 일이다.
#
# ■ 선행 조건
#   2026-08-31 빌드(PAPER↔LIVE 규칙 일치 — WF 게이트 면제 철회 + WS 실시간 감시 개방)가
#   배포돼 있어야 한다. 배포 전에 만들면 옛 규칙으로 시작해 재기동 의미가 없다.
#
# 사용법: 운영 서버에서 (리포 루트에서) 실행
#   bash scripts/rebuild_dynamic_paper_fleet.sh

set -uo pipefail

API="http://localhost:8080/api/v1"

# 현재 RUNNING 9세션 — 정지 대상
OLD_IDS="54 55 57 58 64 65 67 74 75"

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

printf '\n\033[1m▶ 정지 대상 (현재 RUNNING)\033[0m\n'
echo "$probe" | python3 -c '
import json,sys
n=0
for s in json.load(sys.stdin)["data"]:
    if s["status"]=="RUNNING":
        n+=1
        print("  %3s  %-32s %-4s  %s" % (s["id"], s["strategyType"], s["timeframe"],
              s.get("scanState","")))
print("  %d세션" % n)
'

# ── 1. 정지 (보유 포지션은 시장가/시뮬레이션 청산된다) ───────────────────────
printf '\n\033[1m▶ 1단계: 기존 세션 정지\033[0m\n'
ok=0; fail=0
for id in $OLD_IDS; do
  resp=$(api -X POST "$API/dynamic-sessions/$id/stop")
  if echo "$resp" | grep -q '"success":true'; then
    echo "  ✓ id=$id 정지"; ok=$((ok+1))
  else
    echo "  ✗ id=$id — $(echo "$resp" | head -c 140)"; fail=$((fail+1))
  fi
done
echo "  정지: 성공 $ok / 실패 $fail"

# ── 2. 동일 설정으로 재생성 ──────────────────────────────────────────────────
# 설정값은 기존 9세션에서 그대로 읽은 것(전부 동일):
#   initialCapital 10000 · investRatio 0.80 · stopLossPct 5.0
#   maxCandidateSize 30 · targetWatchSize 10 · minAtrPct 0.5 · maxSpreadPct 0.1
#   maxHoldHours 24 · watchlistRefreshMin H1=60 / M15=30 · strategyParams 없음
#
# ⚠️ 워치리스트 필터를 **명시적으로** 넘긴다. risk_config 의 해당 값이 현재 전부 NULL 이라
#    지금은 코드 기본값과 같지만, 명시하면 나중에 risk_config 가 바뀌어도 이 함대의 기준선이
#    흔들리지 않는다. (08-07 재생성 때 필터가 조용히 바뀌어 감시 코인이 62종→10종으로
#    붕괴한 전례가 있다 — 방향은 반대지만 교훈은 같다: 기준선은 명시할 것.)
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
  echo "  ✓ id=$id  $strategy $tf"
}

printf '\n\033[1m▶ 2단계: 동일 설정으로 재생성 (H1 5 + M15 4 = 9세션)\033[0m\n'
create COMPOSITE_MEANREV_BB           H1  60
create COMPOSITE_MOMENTUM_ICHIMOKU    H1  60
create COMPOSITE_MOMENTUM_ICHIMOKU_V2 H1  60
create COMPOSITE_MTF_BTC              H1  60
create COMPOSITE_MTF_CONFIRMED        H1  60
create COMPOSITE_MOMENTUM_ICHIMOKU    M15 30
create COMPOSITE_MOMENTUM_ICHIMOKU_V2 M15 30
create COMPOSITE_MTF_BTC              M15 30
create COMPOSITE_MTF_CONFIRMED        M15 30

# ── 3. 확인 ──────────────────────────────────────────────────────────────────
printf '\n\033[1m▶ 재기동 후 현황\033[0m\n'
api "$API/dynamic-sessions" | python3 -c '
import json,sys
from collections import Counter
c=Counter(); rows=[]
for s in json.load(sys.stdin)["data"]:
    if s["status"]=="RUNNING":
        c[s["strategyType"]]+=1
        rows.append((s["id"], s["strategyType"], s["timeframe"]))
for i,st,tf in sorted(rows): print("  %3s  %-32s %s" % (i,st,tf))
print("  ─────────────────────────────────────")
print("  총 %d세션 / %d전략" % (sum(c.values()), len(c)))
if sum(c.values()) != 9:
    print("  ⚠️ 9세션이 아닙니다 — 실패한 항목을 확인하세요.")
'

cat <<'NOTE'

════════════════════════════════════════════════════════════════════════════
▶ 확인 (DB)
════════════════════════════════════════════════════════════════════════════

  -- MDD 고점이 초기자본으로 리셋됐는지 (재기동의 핵심 효과)
  SELECT id, strategy_type, timeframe,
         round(initial_capital::numeric,0) AS init,
         round(total_asset_krw::numeric,0) AS total,
         round(mdd_peak_capital::numeric,0) AS peak
  FROM dynamic_session WHERE status='RUNNING' ORDER BY timeframe, strategy_type;

  · peak 이 10,000(또는 NULL)이어야 한다. 옛 고점이 안 따라왔는지 확인.

  -- 지문이 하나로 모였는지 (같은 규칙 위에 있는가)
  SELECT left(ruleset_hash,12) AS hash, count(DISTINCT session_id) AS sessions, count(*) AS logs
  FROM strategy_log WHERE session_type='DYN_PAPER' AND created_at > now() - interval '30 minutes'
  GROUP BY 1 ORDER BY 3 DESC;

  · 타임프레임·전략별로 갈리는 것은 정상(지문에 실린 값이라서). 개수가 9개 이하여야 한다.

════════════════════════════════════════════════════════════════════════════
▶ 🔴 이제부터 2주간 (~09-14) 매매 규칙을 바꾸지 말 것
════════════════════════════════════════════════════════════════════════════

  바꾸면 지문이 갈리고 표본이 또 0부터다. 지난 12일이 정확히 그랬다.

  · 금지 — 게이트 임계값 · 노출 상한 · 청산 규칙 · 워치리스트 필터 · 전략 파라미터 · 신규 A/B
  · 허용 — P0 버그 수정 · 관측/로깅 추가 · WF 배치 · 캔들 수집

  09-14 에 할 수 있는 것:
    · 5전략 전부 n≥20 → KILL_CRITERIA 로 존폐 판정
    · 같은 규칙 위의 전략 간 성과 비교 (지금까지 한 번도 못 한 것)
    · WF 게이트를 켰을 때/껐을 때 비교 → DYNAMIC 메인 전환 판단

  중간에 규칙을 바꾸고 싶어지면, 그 변경이 **2주를 다시 기다릴 만큼** 급한지 먼저 물을 것.
NOTE
