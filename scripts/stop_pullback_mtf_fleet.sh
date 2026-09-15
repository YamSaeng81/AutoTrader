#!/usr/bin/env bash
#
# COMPOSITE_PULLBACK_MTF 전면 정지 (2026-08-31)
# ─────────────────────────────────────────────────────────────────────────────
# ■ 왜 죽이나 — 독립적인 두 방법이 같은 결론을 냈다
#
#   실거래(페이퍼)   143거래   승률 30.8%   −3,135원
#   Walk Forward     928거래   7코인 전부 out-of-sample 기대값 음수
#
#   WF 상세 (08-31, H1, 2022-01-01~):
#     KRW-GAS  OVERFITTING  n=172  expect −0.162%     KRW-ZRO  ACCEPTABLE  n=116  expect −0.303%
#     KRW-LSK  OVERFITTING  n=218  expect −0.155%     KRW-BEAM ACCEPTABLE  n= 72  expect −0.014%
#     KRW-MLK  OVERFITTING  n=154  expect −0.305%     KRW-SLX  ACCEPTABLE  n=  0  (표본 없음)
#     KRW-ONT  OVERFITTING  n=196  expect −0.103%
#
#   → 게이트 통과 **0/7**. 표본이 있는 6코인 전부 음수다.
#
# ■ 왜 청산 A/B 를 더 안 기다리나
#   진행 중이던 실험은 "손실 구간에서 전략 SELL 을 막으면 살아나는가"였다. 그런데 WF 는
#   **진입 신호 자체에 엣지가 없다**고 말한다. 청산을 고치면 음수 기대값에서 마찰비용을
#   덜 잃는 정도지 양수로 뒤집히지 않는다. 실험의 전제가 무너졌다.
#
# ■ 부수 효과
#   이 전략이 페이퍼 함대 서킷 브레이커 발동 5회 중 **3회**의 주범이다(세션 56·71·68,
#   08-29~08-30, 전부 MDD 20% 초과). 정지하면 함대 전체의 노이즈가 크게 준다.
#
# ■ 정지된 세션은 지우지 않는다
#   청산 이력이 KILL_CRITERIA 집계와 사후 분석에 필요하다. status 만 STOPPED 로 바꾼다.
#
# ■ 되살리려면
#   진입 신호를 고친 **다른 전략**으로 만들어야 한다. 같은 이름으로 재기동하면 같은 결과가
#   나온다 — 이미 928거래로 확인됐다.
#
# 사용법: 운영 서버에서 (리포 루트에서) 실행
#   bash scripts/stop_pullback_mtf_fleet.sh

set -uo pipefail

API="http://localhost:8080/api/v1"

# 08-31 기준 RUNNING 인 PULLBACK_MTF 세션
TARGETS="63 69 70 72 73"

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

# ── 정지 전 현황 ─────────────────────────────────────────────────────────────
printf '\n\033[1m▶ 정지 대상 (RUNNING 인 PULLBACK_MTF)\033[0m\n'
api "$API/dynamic-sessions" | python3 -c '
import json,sys
n=0
for s in json.load(sys.stdin)["data"]:
    if s["status"]=="RUNNING" and s["strategyType"]=="COMPOSITE_PULLBACK_MTF":
        n+=1
        print("  %3s  %-4s  %-20s  %s" % (
            s["id"], s["timeframe"], s.get("scanState",""),
            s.get("strategyParams") or "(대조군)"))
print("  대상 %d세션" % n)
'

# ── 정지 ─────────────────────────────────────────────────────────────────────
# stop 은 보유 포지션을 시장가 청산한다(세션 72 가 KRW-DKA 보유 중).
printf '\n\033[1m▶ 정지 실행\033[0m\n'
ok=0; fail=0
for id in $TARGETS; do
  resp=$(api -X POST "$API/dynamic-sessions/$id/stop")
  if echo "$resp" | grep -q '"success":true'; then
    echo "  ✓ id=$id 정지"
    ok=$((ok+1))
  else
    echo "  ✗ id=$id 실패 — $(echo "$resp" | head -c 160)"
    fail=$((fail+1))
  fi
done
printf '\n  결과: 성공 %s / 실패 %s\n' "$ok" "$fail"

# ── 확인 ─────────────────────────────────────────────────────────────────────
printf '\n\033[1m▶ 정지 후 RUNNING 세션 현황\033[0m\n'
api "$API/dynamic-sessions" | python3 -c '
import json,sys
from collections import Counter
c=Counter()
for s in json.load(sys.stdin)["data"]:
    if s["status"]=="RUNNING": c[s["strategyType"]]+=1
for k,v in sorted(c.items()): print("  %-32s %d" % (k,v))
print("  ─────────────────────────────────────")
print("  총 %d세션" % sum(c.values()))
if c.get("COMPOSITE_PULLBACK_MTF"):
    print("  ⚠️ PULLBACK_MTF 가 아직 남아 있습니다 — 실패한 id 를 수동 확인하세요.")
'

cat <<'NOTE'

════════════════════════════════════════════════════════════════════════════
▶ 확인 (DB)
════════════════════════════════════════════════════════════════════════════

  -- 정지 상태 + 보유 포지션이 전부 청산됐는지
  SELECT d.id, d.status, d.scan_state,
         round(d.total_asset_krw::numeric,0) AS total,
         count(p.id) FILTER (WHERE p.status IN ('OPEN','CLOSING')) AS open_pos
  FROM dynamic_session d
  LEFT JOIN position p ON p.session_id = d.id
  WHERE d.strategy_type='COMPOSITE_PULLBACK_MTF'
  GROUP BY 1,2,3,4 ORDER BY 1;

  · open_pos 가 0이어야 한다. 세션 72가 KRW-DKA 를 들고 있었으므로 청산 주문이
    체결됐는지 확인할 것.

▶ 남는 것
  · 함대가 10세션(5전략)으로 줄어든다. 서킷 브레이커 발동 5회 중 3회가 이 전략이었으므로
    함대 전체 노이즈가 크게 준다.
  · 청산 A/B(세션 72·73)도 함께 끝난다 — 진입에 엣지가 없어 실험 전제가 무너졌다.

▶ 다음 판단
  · MEANREV_BB 도 실거래 n=21 승률 23.8% −3,399원에 WF 1/7 이다. 같은 근거로 정지 후보다.
    다만 WF 에서 KRW-MLK 1건이 통과(n=6, +0.213%)라 PULLBACK_MTF 만큼 명확하지는 않다.
  · WF 게이트(REQUIRE_WALK_FORWARD_GATE) 활성화 검토 — 통과 조합 6개가 확보됐다.
    단 BEAM·GAS·MLK 3코인뿐이라 켜면 DYNAMIC 이 사실상 그 3코인만 거래하게 된다.
NOTE
