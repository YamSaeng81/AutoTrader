#!/usr/bin/env bash
#
# 손절폭 A/B 실험군 생성 (2026-08-24)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 무엇을 검증하나
#   실전 307건·페이퍼 404건이 전부 적자인데, 원인은 손익비가 아니라 **승률**이다:
#
#     실측 R:R 3.26 (평균이익 +2.87% / 평균손실 -0.88%)  → 손익분기 승률 23.5%
#     실제 승률 19.7%                                    → 3.8%p 부족
#
#   손절이 **평균 36분 만에 -0.88%** 에서 걸린다. 15분봉 암호화폐의 일상 노이즈 폭이
#   그 정도라, 논지가 전개되기 전에 잡음에 털린다는 가설이다.
#   (익절까지 간 거래는 평균 153분 — 4배 오래 들고 있었다.)
#
# ■ ⚠️ 왜 SL 만 넓히면 안 되나 (2026-07-31 실패 전례)
#   TP = SL폭 × TP_RR_MULTIPLIER(2.0) 으로 **연동**돼 있다. SL 만 넓히면 TP 도 멀어져
#   도달 불가가 된다. 07-31 개편이 정확히 그렇게 실패했다 —
#   ExitRuleCalculator.TP_PCT_MAX javadoc: "KRW-META2 TP +14.10%, 5일간 익절 0건 / 손절 3건".
#
#   그래서 실험군은 두 값을 **함께** 움직인다:
#     slAtrMultiplier 1.5 → 2.5   (손절폭 1.67배 — 노이즈 손절 감소)
#     tpRrMultiplier  2.0 → 1.2   (TP 절대거리 유지: 2.5 × 1.2 = 3.0 ≒ 1.5 × 2.0)
#   목표는 R:R 확대가 아니라 **승률을 손익분기 위로 올리는 것**이다.
#
# ■ 설계 — 짝지은(paired) 비교
#   대조군은 이미 돌고 있는 M15 세션 40개다(5전략 × 8코인, strategy_params NULL).
#   실험군은 **같은 전략·같은 코인·같은 설정**으로 파라미터 두 개만 다르게 복제한다.
#   같은 코인·같은 기간을 보므로 시장 국면 차이가 상쇄된다.
#
#   M15 만 쓰는 이유: 5일간 청산이 M15 122건 vs H1 27건. H1 은 표본이 너무 느리게 쌓인다
#   (감쇠 A/B 가 arm 당 3~11건에 머물러 판정까지 1~2개월 걸리는 것과 같은 문제).
#   M15 40세션이면 arm 당 주 ~170건 → **1주면 판정 가능**하다.
#
#   지문: slAtrMultiplier/tpRrMultiplier 는 strategy_params 로 들어가고
#   RulesetRegistry 가 strategy.params 키로 지문에 담으므로 arm 이 자동으로 갈린다.
#
# 사용법:
#   ssh <운영서버>; cd <리포>; bash scripts/create_ab_stoploss_sessions.sh
#   (되돌리려면 생성된 세션에 POST /paper-trading/sessions/{id}/stop)

set -uo pipefail

API="http://localhost:8080/api/v1"
ARM_PARAMS='{"slAtrMultiplier":2.5,"tpRrMultiplier":1.2}'

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

sessions=$(api "$API/paper-trading/sessions")
case "$sessions" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다: $(echo "$sessions" | head -c 200)"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요."; exit 1 ;;
esac
echo "✓ 인증 확인"

# ── PRECHECK: 배포 반영 여부 ─────────────────────────────────────────────────
# ExitRuleOverrides 가 배포되지 않은 백엔드에 실험군을 만들면, 파라미터가 무시된 채
# 대조군과 똑같이 돌면서 지문만 갈린다 — A/B 가 아니라 그냥 오염된 표본이 된다.
echo
echo "⚠️  전제 확인: ExitRuleOverrides 가 포함된 빌드가 배포돼 있어야 합니다."
echo "    (미배포 상태로 만들면 파라미터가 무시된 채 지문만 갈려 표본이 오염됩니다)"
printf '    배포를 마쳤으면 yes 입력: '
read -r deployed
[ "$deployed" = "yes" ] || { echo "중단."; exit 1; }

# ── 대조군 목록 = 복제 대상 ──────────────────────────────────────────────────
# strategy_params 가 비어 있는(=대조군) RUNNING M15 세션만 고른다.
# 이미 만든 실험군을 다시 복제하면 arm 이 중첩된다.
TARGETS=$(echo "$sessions" | python3 -c '
import json,sys
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
for s in rows:
    if s.get("status") != "RUNNING":            continue
    if s.get("timeframe") != "M15":             continue
    if s.get("strategyParams"):                 continue   # 이미 실험군
    print("%s|%s" % (s["strategyName"], s["coinPair"]))
' | sort -u)

if [ -z "$TARGETS" ]; then
  echo "▶ 복제할 대조군 M15 세션이 없습니다. 종료."
  exit 0
fi

count=$(echo "$TARGETS" | wc -l)
printf '\n\033[1m▶ 실험군 %s개 생성 예정\033[0m — 파라미터 %s\n' "$count" "$ARM_PARAMS"
echo "$TARGETS" | sed 's/|/  /' | sed 's/^/    /'

printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
read -r _

# ── 생성 ─────────────────────────────────────────────────────────────────────
# 설정은 대조군과 완전히 동일해야 한다 — 하나라도 다르면 지문이 갈린 이유가
# 손절폭인지 설정인지 구분되지 않는다. 페이퍼 함대 표준값을 그대로 쓴다.
ok=0; fail=0
while IFS='|' read -r strategy coin; do
  [ -n "$strategy" ] || continue
  resp=$(api -X POST "$API/paper-trading/sessions" \
    -H 'Content-Type: application/json' \
    -d "{\"strategyType\":\"$strategy\",\"coinPair\":\"$coin\",\"timeframe\":\"M15\",
         \"initialCapital\":10000000,\"stopLossPct\":5.0,\"investRatio\":0.80,
         \"maxHoldHours\":24,\"strategyParams\":$ARM_PARAMS}")
  id=$(echo "$resp" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin); print(d["data"]["id"] if d.get("data") else "")
except Exception: print("")' 2>/dev/null)
  if [ -n "$id" ]; then
    echo "  ✓ id=$id  $strategy  $coin"
    ok=$((ok+1))
  else
    echo "  ✗ 실패  $strategy  $coin — $(echo "$resp" | head -c 200)"
    fail=$((fail+1))
  fi
done <<EOF
$TARGETS
EOF

printf '\n\033[1m▶ 결과: 성공 %s / 실패 %s\033[0m\n' "$ok" "$fail"

# ── 사후 검증: 파라미터가 실제로 저장됐는가 ──────────────────────────────────
# 2026-08-24 사고: PaperTradingService.createSession 빌더에서 strategyParams 가 빠져 있어
# API 는 200 을 돌려주는데 파라미터만 조용히 사라졌다. 실험군 40세션이 대조군과 똑같이
# 돌았고 지문까지 같아 사후 구분조차 불가능했다. 같은 일이 반복되지 않게 여기서 확인한다.
echo
echo "▶ 사후 검증 — 저장된 strategy_params"
verified=$(api "$API/paper-trading/sessions" | python3 -c '
import json,sys
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
print(len([s for s in rows if s.get("status")=="RUNNING" and s.get("strategyParams")]))
')
echo "  strategy_params 가 실린 RUNNING 세션: ${verified}건 (기대: $ok건)"
if [ "$verified" != "$ok" ]; then
  echo
  echo "  🔴 파라미터가 저장되지 않았습니다 — 실험군이 대조군과 동일하게 돕니다."
  echo "     방금 만든 세션을 전부 정지하고, 배포본에 PaperTradingService.createSession 의"
  echo "     .strategyParams(req.getStrategyParams()) 가 있는지 확인하세요."
  echo "     (회귀 가드: PaperSessionStrategyParamsTest)"
  exit 1
fi
echo "  ✓ 실험군이 대조군과 다른 규칙으로 돕니다."


cat <<'NOTE'

▶ 판정 쿼리 (1주 후 — arm 당 100건 넘으면 결론 가능)

  SELECT CASE WHEN v.strategy_params IS NULL THEN '대조군(SL 1.5 ATR)'
              ELSE '실험군(SL 2.5 ATR)' END AS arm,
         count(*)                                                        AS n,
         round(100.0*count(*) FILTER (WHERE p.realized_pnl>0)/count(*),1) AS winrate,
         round(avg(100.0*p.realized_pnl/nullif(p.invested_krw,0)),3)      AS avg_ret_pct,
         round(avg(EXTRACT(epoch FROM (p.closed_at-p.opened_at))/60),0)   AS hold_min,
         count(*) FILTER (WHERE p.exit_reason='STOP_LOSS')                AS sl,
         count(*) FILTER (WHERE p.exit_reason='TAKE_PROFIT')              AS tp,
         round(sum(p.realized_pnl),0)                                     AS pnl
  FROM paper_trading.position p
  JOIN paper_trading.virtual_balance v ON v.id = p.session_id
  WHERE p.status='CLOSED' AND v.timeframe='M15' AND p.closed_at >= '2026-08-25'
  GROUP BY 1;

▶ 판정 기준
  1차: 실험군 승률 > 대조군 승률   ← 가설의 직접 검증(노이즈 손절 감소)
  2차: 실험군 평균 보유시간 > 36분 ← 실제로 덜 털렸는지
  3차: 실험군 거래당 기대수익률 > 0 ← 최종 목표(손익분기 돌파)

  1·2차만 개선되고 3차가 음수면 손절폭이 원인은 맞지만 배수가 부족한 것 —
  slAtrMultiplier 를 3.0~3.5 로 올려 재실험한다.
  1차부터 개선이 없으면 가설이 틀렸다 — 손절폭이 아니라 **진입 선별**이 문제다
  (§4 의 "실행된 신호가 차단된 신호보다 나쁘다" 와 연결된다).
NOTE
