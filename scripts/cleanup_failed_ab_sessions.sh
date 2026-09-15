#!/usr/bin/env bash
#
# 손절폭 A/B 1차 시도 정리 — 파라미터가 유실된 세션 40개 제거 (2026-08-24)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 무슨 일이 있었나
#   PaperTradingService.createSession 의 빌더에서 strategyParams 가 빠져 있었다
#   (LIVE·DYNAMIC 은 처음부터 넘기고 있었는데 PAPER 만 누락).
#   API 는 200 을 돌려주고 세션도 정상 생성됐지만 **파라미터만 조용히 사라졌다.**
#
#   결과: 01:21 에 만든 실험군 40세션(id 250~289)이 slAtrMultiplier/tpRrMultiplier 없이
#   대조군과 **완전히 같은 규칙**으로 돌고 있다. 지문(strategy.params)까지 같아
#   사후에 "이건 실험군이었다"고 구분할 방법이 없다.
#
#   그대로 두면 M15 표본이 같은 규칙 80세션으로 부풀 뿐이라 A/B 가 성립하지 않는다.
#   전부 정지하고, 수정 배포 후 create_ab_stoploss_sessions.sh 를 다시 돌린다.
#
# ■ 안전장치
#   ID 를 하드코딩하지 않는다. "01:21 이후 생성 + strategy_params 없음 + M15 + RUNNING"
#   조건으로 API 응답에서 골라내고, 지우기 전에 목록을 보여준다.
#   원래 대조군 40세션(08-18 생성)은 조건에 걸리지 않는다.
#
# 사용법:
#   ssh <운영서버>; cd <리포>; bash scripts/cleanup_failed_ab_sessions.sh

set -uo pipefail

API="http://localhost:8080/api/v1"
CUTOFF="${CUTOFF:-2026-08-24T01:00:00}"   # 이 시각 이후 생성분만 대상

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

IDS=$(echo "$sessions" | CUTOFF="$CUTOFF" python3 -c '
import json,os,sys
cut = os.environ["CUTOFF"]
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
for s in rows:
    if s.get("status") != "RUNNING":   continue
    if s.get("timeframe") != "M15":    continue
    if s.get("strategyParams"):        continue          # 정상 실험군은 건드리지 않는다
    started = (s.get("startedAt") or "")
    if started < cut:                  continue          # 원래 대조군(08-18)은 제외
    print("%s|%s|%s|%s" % (s["id"], s["strategyName"], s.get("coinPair"), started[:19]))
')

if [ -z "$IDS" ]; then
  echo "▶ 정리할 세션이 없습니다 (이미 처리됐거나 생성되지 않음). 종료."
  exit 0
fi

count=$(echo "$IDS" | wc -l)
printf '\n\033[1m▶ 정지 대상 %s건 — 파라미터가 유실된 실험군\033[0m\n' "$count"
echo "$IDS" | awk -F'|' '{printf "    %-5s %-32s %-10s %s\n", $1, $2, $3, $4}'
printf '\n  ※ %s 이후 생성 + strategy_params 없음 + M15 + RUNNING 인 세션만 고른다.\n' "$CUTOFF"
printf '  ※ 원래 대조군 40세션(08-18 생성)은 조건에 걸리지 않는다.\n'

printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
read -r _

ok=0; fail=0
while IFS='|' read -r id strategy coin started; do
  [ -n "$id" ] || continue
  case "$(api -X POST "$API/paper-trading/sessions/$id/stop")" in
    *'"success":true'*) echo "  ✓ $id 정지  ($strategy $coin)"; ok=$((ok+1)) ;;
    *)                  echo "  ✗ $id 실패  ($strategy $coin)"; fail=$((fail+1)) ;;
  esac
done <<EOF
$IDS
EOF

printf '\n\033[1m▶ 결과: 성공 %s / 실패 %s\033[0m\n' "$ok" "$fail"

printf '\n\033[1m▶ 잔여 RUNNING M15 세션 (대조군만 남아야 정상: 40건)\033[0m\n'
api "$API/paper-trading/sessions" | python3 -c '
import json,sys
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
left = [s for s in rows if s.get("status")=="RUNNING" and s.get("timeframe")=="M15"]
armed = [s for s in left if s.get("strategyParams")]
print("  전체 %d건 (파라미터 있음 %d건 / 대조군 %d건)" % (len(left), len(armed), len(left)-len(armed)))
'

cat <<'NOTE'

▶ 다음 단계
  1. 수정본 배포 — PaperTradingService.createSession 의 .strategyParams(req.getStrategyParams())
     (회귀 가드: PaperSessionStrategyParamsTest)
  2. bash scripts/create_ab_stoploss_sessions.sh
     이제 스크립트가 생성 직후 저장 여부를 검증하고, 유실되면 실패로 끝난다.
NOTE
