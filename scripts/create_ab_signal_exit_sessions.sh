#!/usr/bin/env bash
#
# 전략 SELL 청산 A/B — COMPOSITE_PULLBACK_MTF (2026-08-26)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 무엇을 검증하나
#   PULLBACK_MTF 는 표본이 충분한 유일한 전략인데(14일 58거래), 청산 사유별로 뜯어보면
#   익절이 번 것을 전략신호 청산이 거의 다 까먹고 있다:
#
#     TAKE_PROFIT      10건  승률 100%   평균 +683   합계 +6,830
#     TIME_STOP         3건              평균 +187   합계   +561
#     STOP_LOSS         2건  승률   0%   평균 −441   합계   −882
#     STRATEGY_SIGNAL  39건  승률 23.1%  평균 −118   합계 −4,594   ← 최대 손실원
#
#   39건 중 21건이 −1.0% 아래에서 청산됐다. 손절선에 닿기 전에 전략이 먼저 항복하는
#   패턴이고 평균 보유 6.7시간으로 짧다. 같은 전략의 BUY 신호가 24시간 뒤 +4.13%
#   (게이트에 막힌 분 기준)라는 것과 나란히 놓으면, 청산이 예측이 아니라 후행한다는
#   쪽에 무게가 실린다 — 즉 **더 들고 있었으면 회복했을 것**이라는 가설이다.
#
# ■ 설계 — 파라미터 하나만 바꾼다
#     대조군 : 세션 56(H1) / 63(M15)  — strategy_params NULL, 이미 돌고 있다
#     실험군 : 신규 2세션              — {"lossEscapeThresholdPct": -100}
#
#   −100 은 "손실 구간에서는 전략 SELL 로 못 나간다"는 뜻이다(원화 포지션이 −100%
#   아래로 못 가므로 손실 탈출 조건이 절대 참이 되지 않는다). 수익 구간(+0.30% 이상)
#   청산은 그대로 살아 있다 — 익절을 막는 실험이 아니다.
#
#   실험군에서 손실 포지션은 이제 SL / TP / time stop(24h) 으로만 청산된다.
#   즉 이 실험이 묻는 것은 정확히 이것이다:
#     "작은 손실에서 전략 말을 듣고 나가는 게 나은가, 손절선까지 버티는 게 나은가?"
#
# ■ 선행 조건 (중요)
#   ① 2026-08-26 빌드가 배포돼 있어야 한다 — 이 커밋 이전 코드는
#      lossEscapeThresholdPct 를 읽지 않으므로 실험군이 대조군과 똑같이 돈다.
#      (2026-08-24 손절폭 A/B 가 정확히 그렇게 조용히 실패했다.)
#   ② 같은 빌드의 **노출 상한 PAPER 면제**가 이 실험의 전제다. 면제 전에는 대조군과
#      실험군이 같은 코인을 동시에 못 들어서, 두 arm 이 서로 다른 종목을 보게 되어
#      비교가 성립하지 않았다. 이제 같은 신호에 양쪽이 함께 진입할 수 있다.
#
# ■ 부하
#   같은 빌드에서 DYNAMIC 캔들 조회가 market_data_cache 경유로 바뀌어(갭만 REST)
#   세션당 요청 수가 크게 줄었다. 예전 문서의 "세션당 33요청/분" 은 더 이상 맞지 않는다.
#
# ■ 판정은 2단계 — 기간을 혼동하지 말 것
#   1단계 (2~3일) : 실험군에서 STRATEGY_SIGNAL 청산이 실제로 사라졌는가 (배선 검증)
#   2단계 (3~4주) : 그래서 돈이 되는가 — 실현손익·승률·평균보유시간
#   1단계가 확인돼도 2단계 전에는 기본값을 바꾸지 말 것.
#
# 사용법: 운영 서버에서 (리포 루트에서) 실행
#   bash scripts/create_ab_signal_exit_sessions.sh

set -uo pipefail

API="http://localhost:8080/api/v1"
ARM_PARAMS='{"lossEscapeThresholdPct":-100}'

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

probe=$(api "$API/dynamic-sessions")
case "$probe" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다: $(echo "$probe" | head -c 200)"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요"; exit 1 ;;
esac
echo "✓ 인증 확인"

# ── PRECHECK: 배포 여부 ──────────────────────────────────────────────────────
echo
echo "=== PRECHECK ==="
echo "  2026-08-26 빌드(lossEscapeThresholdPct 오버라이드 + 노출상한 PAPER 면제)가"
echo "  배포돼 있어야 합니다. 배포 전에 실행하면 실험군이 대조군과 똑같이 돕니다."
printf '  배포를 마쳤으면 yes 입력: '
read -r deployed
[ "$deployed" = "yes" ] || { echo "중단."; exit 1; }

running=$(echo "$probe" | python3 -c '
import json,sys
print(sum(1 for s in json.load(sys.stdin)["data"] if s["status"]=="RUNNING"))')
echo "  현재 RUNNING 동적 세션 : $running  → 생성 후 $((running + 2))"

# ── 대조군 확인 ──────────────────────────────────────────────────────────────
printf '\n\033[1m▶ 대조군 확인 — PULLBACK_MTF 중 strategy_params 가 없는 세션\033[0m\n'
echo "$probe" | python3 -c '
import json,sys
rows=[s for s in json.load(sys.stdin)["data"]
      if s["status"]=="RUNNING" and s["strategyType"]=="COMPOSITE_PULLBACK_MTF"]
for s in rows:
    p = s.get("strategyParams")
    tag = "대조군" if not p else "다른 실험군(%s)" % p
    print("  %3s  %-4s  %s" % (s["id"], s["timeframe"], tag))
if not any(not s.get("strategyParams") for s in rows):
    print("  ⚠️  대조군이 없습니다 — 비교 대상 없이 실험군만 만들면 판정이 불가능합니다.")
'

# ── 생성 ─────────────────────────────────────────────────────────────────────
# 설정은 대조군과 완전히 동일해야 한다. M15 는 watchlistRefreshMin 이 30(H1 은 60).
# 하나라도 다르면 지문이 갈린 이유가 청산 규칙인지 설정인지 구분되지 않는다.
create() {
  local label="$1" tf="$2" refresh="$3"
  printf '\n\033[1m▶ %s\033[0m\n' "$label"
  local resp id
  resp=$(api -X POST "$API/dynamic-sessions" \
    -H 'Content-Type: application/json' \
    -d "{\"strategyType\":\"COMPOSITE_PULLBACK_MTF\",\"timeframe\":\"$tf\",
         \"initialCapital\":10000,\"stopLossPct\":5.0,\"investRatio\":80,
         \"maxCandidateSize\":30,\"targetWatchSize\":10,
         \"minAtrPct\":0.5,\"maxSpreadPct\":0.1,
         \"watchlistRefreshMin\":$refresh,\"maxHoldHours\":24,
         \"tradingMode\":\"PAPER\",\"strategyParams\":$ARM_PARAMS}")
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
  echo "  ✓ id=$id 시작 ($ARM_PARAMS)"
}

create "손실탈출 OFF  H1  (대조군 56 과 짝)"  H1  60
create "손실탈출 OFF  M15 (대조군 63 과 짝)"  M15 30

# ── 현황 ─────────────────────────────────────────────────────────────────────
printf '\n\033[1m▶ PULLBACK_MTF 세션 현황\033[0m\n'
api "$API/dynamic-sessions" | python3 -c '
import json,sys
for s in json.load(sys.stdin)["data"]:
    if s["status"]=="RUNNING" and s["strategyType"]=="COMPOSITE_PULLBACK_MTF":
        print("  %3s  %-4s  %s" % (s["id"], s["timeframe"], s.get("strategyParams") or "(대조군)"))
'

cat <<'NOTE'

════════════════════════════════════════════════════════════════════════════
▶ 1단계 검증 (즉시 ~ 2일) — 배선이 실제로 먹었는가
════════════════════════════════════════════════════════════════════════════

① 지문이 갈렸는가 — 같으면 파라미터가 지문에 안 실린 것이라 A/B 자체가 무효다.

    SELECT l.session_id, left(l.ruleset_hash,12) AS hash, count(*)
    FROM strategy_log l
    WHERE l.session_type='DYN_PAPER' AND l.created_at > now() - interval '1 hour'
      AND l.strategy_name='COMPOSITE_PULLBACK_MTF'
    GROUP BY 1,2 ORDER BY 1;

② 실험군에서 손실 구간 SELL 이 막히기 시작했는가 —
   실험군 blocked_reason 에 '본전 근처' 가 손실 pnl 값과 함께 늘어야 한다.

    SELECT d.id,
           CASE WHEN d.strategy_params::text LIKE '%lossEscape%' THEN '실험군' ELSE '대조군' END AS arm,
           count(*) FILTER (WHERE l.signal='SELL')                       AS sell_signals,
           count(*) FILTER (WHERE l.signal='SELL' AND l.was_executed)    AS sell_executed,
           count(*) FILTER (WHERE l.blocked_reason LIKE '본전 근처%')     AS blocked_breakeven
    FROM strategy_log l JOIN dynamic_session d ON d.id = l.session_id
    WHERE l.session_type='DYN_PAPER' AND l.strategy_name='COMPOSITE_PULLBACK_MTF'
      AND l.created_at > now() - interval '2 days'
    GROUP BY 1,2 ORDER BY 2,1;

  기대: 실험군의 sell_executed 가 대조군보다 뚜렷이 적고, blocked_breakeven 이 많다.
  두 arm 이 같게 나오면 배포가 반영되지 않은 것 — 즉시 중단하고 빌드를 확인할 것.

════════════════════════════════════════════════════════════════════════════
▶ 2단계 판정 (3~4주) — 그래서 돈이 되는가
════════════════════════════════════════════════════════════════════════════

    SELECT CASE WHEN d.strategy_params::text LIKE '%lossEscape%' THEN '실험군' ELSE '대조군' END AS arm,
           p.exit_reason,
           count(*) AS n,
           round(100.0*count(*) FILTER (WHERE p.realized_pnl>0)/nullif(count(*),0),1) AS win_pct,
           round(avg(p.realized_pnl)::numeric,0)  AS avg_pnl,
           round(sum(p.realized_pnl)::numeric,0)  AS total_pnl,
           round(avg(EXTRACT(EPOCH FROM (p.closed_at-p.opened_at))/3600)::numeric,1) AS avg_hold_h
    FROM position p JOIN dynamic_session d ON d.id = p.session_id
    WHERE p.status='CLOSED' AND p.session_kind='DYN_PAPER'
      AND d.strategy_type='COMPOSITE_PULLBACK_MTF'
      AND p.opened_at >= '<이 스크립트 실행 시각>'
    GROUP BY 1,2 ORDER BY 1,2;

  판정 기준:
    · 실험군 STRATEGY_SIGNAL 이 거의 0 이어야 한다 (배선 확인)
    · 실험군 총 실현손익 > 대조군  → 손절선까지 버티는 게 낫다 → 기본값 변경 검토
    · 실험군 STOP_LOSS 가 늘고 총손익이 더 나쁘다 → 전략 SELL 이 실제로 방어였다
      → 되돌리고, 대신 임계값 조정(−0.30 → 더 깊게)을 다음 실험으로

  ⚠️ arm 당 청산 20건 이상 쌓이기 전에는 판정하지 말 것. 현재 속도로 3~4주 예상이다.
NOTE
