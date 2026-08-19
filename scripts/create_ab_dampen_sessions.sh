#!/usr/bin/env bash
#
# 신호 감쇠 A/B 실험군 세션 생성 (2026-08-19)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 무엇을 검증하나
#   페이퍼 함대 112세션 중 93개가 12일간 거래 0건이었다. 원인을 분해하니 2일치 HOLD
#   7,033건 중 88% 가 "지표가 하나도 안 켜짐"(buy=0.00) 이었고, 나머지 중 두 감쇠 장치가
#   임계값(0.3)을 넘겼을 매수 점수를 죽이고 있었다:
#
#     TRANSITIONAL 감쇠 … 45건    EMA 하락추세 필터 … 21건
#     같은 2일간 실제 통과한 BUY 신호 … 51건   → 통과분보다 죽은 쪽이 많다
#
#   이 감쇠들이 **손실을 막고 있었는지 기회를 죽이고 있었는지**는 A/B 로만 알 수 있다.
#   지금 아는 것은 죽인 신호의 **양**이지 **질**이 아니다(함대 승률 22%).
#
# ■ 설계
#   대조군은 이미 돌고 있다 — 세션 56(H1) / 63(M15), strategy_params = NULL.
#   실험군은 **파라미터 하나만** 다르다. 둘을 동시에 바꾸면 어느 효과인지 못 가린다.
#
#     실험 A : emaFilterDampenFactor    0.0 → 1.0   (EMA 역추세 필터 끄기)
#     실험 B : transitionalDampenFactor 0.5 → 1.0   (TRANSITIONAL 감쇠 끄기)
#
#   전량 off(1.0) 인 이유 — 동적 세션 거래 빈도가 12일 7건이라 표본이 귀하다.
#   대비를 최대로 줘야 적은 표본에서도 차이가 보인다. 효과 확인 후 중간값을 찾는다.
#
#   COMPOSITE_PULLBACK_MTF 만 쓰는 이유 — 유일하게 신호가 나오는 전략이다.
#   BUY 비율 3.14% (나머지 6개 전략 0.05~0.26%), 함대 52거래 중 41건이 여기서 나왔다.
#
# ■ 판정은 2단계 — 기간을 혼동하지 말 것
#   1단계 (1~2일) : 감쇠를 끄면 진입이 실제로 느는가   → BUY 신호·진입 수 (n = 수천 틱)
#   2단계 (수 주) : 그 진입이 돈이 되는가              → 실현손익·exit_reason (n = 수 건)
#   1단계가 양수여도 2단계 전에는 기본값을 바꾸지 말 것.
#
# ■ 선행 조건 (중요)
#   ① dampen.* 지문 수정이 배포돼 있어야 한다. 이 배포는 모든 해시를 다시 가르므로
#      **세션 생성보다 먼저** 해야 실험이 단일 지문 위에서 끝까지 굴러간다.
#      확인: ruleset_snapshot 최신 DYN_PAPER 행이 59키이고 params_text 에 'dampen.' 포함.
#   ② API 부하 여유. DYNAMIC 은 세션마다 워치리스트를 각자 조회해 33 요청/분/세션이다
#      (PaperTradingService 의 tickCandleCache 같은 공유 캐시가 없다).
#      현재 14세션 ≈ 462/분으로 문서상 한도 420 을 이미 넘는 계산이다.
#      **백엔드 로그에서 rate limit(429) 오류가 없는지 먼저 확인할 것.**
#      여유가 없으면 아래 대안을 검토한다:
#        · 신호가 안 나오는 동적 전략 세션을 줄여 예산을 확보 (단, 동적은 코인을 고정하지
#          않으므로 함대에서 조용하던 전략이 여기서도 조용하리라 단정할 수 없다)
#        · 또는 PAPER 함대에서 A/B — API 비용이 세션 수와 무관해 사실상 공짜다.
#          단 MAX_CONCURRENT_SESSIONS=120 이고 현재 112 라 슬롯이 8개뿐이다.
set -uo pipefail

API="http://localhost:8080/api/v1"

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
esac
echo "✓ 인증 확인"

# ── PRECHECK: 부하 예산 ──────────────────────────────────────────────────────
running=$(echo "$probe" | python3 -c '
import json,sys
print(sum(1 for s in json.load(sys.stdin)["data"] if s["status"]=="RUNNING"))')
echo
echo "=== PRECHECK ==="
echo "  현재 RUNNING 동적 세션 : $running"
echo "  추정 부하              : $((running * 33)) 요청/분  (세션당 33)"
echo "  생성 후                : $(((running + 4) * 33)) 요청/분  (문서상 한도 420)"
echo
if [ "$((running + 4))" -gt 12 ]; then
  echo "  ⚠️  생성 후 한도를 넘는 계산입니다."
  echo "     백엔드 로그에 rate limit 오류가 없는지 확인했습니까?"
  read -r -p "     계속하려면 yes 입력: " ok
  [ "$ok" = "yes" ] || { echo "중단."; exit 1; }
fi

# ── 생성 ─────────────────────────────────────────────────────────────────────
# 설정은 대조군과 완전히 동일해야 한다. M15 는 watchlistRefreshMin 이 30 이다(H1 은 60).
# 하나라도 다르면 지문이 갈린 이유가 감쇠인지 설정인지 구분되지 않는다.
create() {
  local label="$1" tf="$2" refresh="$3" params="$4"
  printf '\n\033[1m▶ %s\033[0m\n' "$label"
  local resp id
  resp=$(api -X POST "$API/dynamic-sessions" \
    -H 'Content-Type: application/json' \
    -d "{\"strategyType\":\"COMPOSITE_PULLBACK_MTF\",\"timeframe\":\"$tf\",
         \"initialCapital\":10000,\"stopLossPct\":5.0,\"investRatio\":80,
         \"maxCandidateSize\":30,\"targetWatchSize\":10,
         \"minAtrPct\":0.5,\"maxSpreadPct\":0.1,
         \"watchlistRefreshMin\":$refresh,\"maxHoldHours\":24,
         \"tradingMode\":\"PAPER\",\"strategyParams\":$params}")
  id=$(echo "$resp" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print(d["data"]["id"] if d.get("data") else "")' 2>/dev/null)
  if [ -z "$id" ]; then
    echo "  ✗ 생성 실패: $(echo "$resp" | head -c 300)"
    return 1
  fi
  api -X POST "$API/dynamic-sessions/$id/start" > /dev/null
  echo "  ✓ id=$id 시작 ($params)"
}

create "A1  EMA off    H1"  H1  60  '{"emaFilterDampenFactor":1.0}'
create "A2  EMA off    M15" M15 30  '{"emaFilterDampenFactor":1.0}'
create "B1  TRANS off  H1"  H1  60  '{"transitionalDampenFactor":1.0}'
create "B2  TRANS off  M15" M15 30  '{"transitionalDampenFactor":1.0}'

# ── 검증 ─────────────────────────────────────────────────────────────────────
printf '\n\033[1m▶ PULLBACK_MTF 세션 현황 (대조군 + 실험군)\033[0m\n'
api "$API/dynamic-sessions" | python3 -c '
import json,sys
for s in json.load(sys.stdin)["data"]:
    if s["status"]=="RUNNING" and s["strategyType"]=="COMPOSITE_PULLBACK_MTF":
        print(f"  {s[\"id\"]:>3}  {s[\"timeframe\"]:<4} {s.get(\"strategyParams\") or \"(대조군)\"}")
'

cat <<'NOTE'

▶ 다음 확인 (DB)
  실험군 지문이 대조군과 **달라야** A/B 가 성립한다. 같으면 파라미터가 지문에 안 실린 것이다.

    SELECT session_id, ruleset_hash, count(*)
    FROM strategy_log WHERE session_type='DYN_PAPER'
      AND created_at > now() - interval '1 hour'
    GROUP BY 1,2 ORDER BY 1;

  1단계 판정(1~2일 뒤) — 감쇠를 끈 쪽이 BUY 신호를 더 내는가:

    SELECT l.session_id, d.strategy_params, l.signal, count(*)
    FROM strategy_log l JOIN dynamic_session d ON d.id = l.session_id
    WHERE l.session_type='DYN_PAPER' AND l.created_at > now() - interval '2 days'
    GROUP BY 1,2,3 ORDER BY 1,3;
NOTE
