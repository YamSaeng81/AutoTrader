#!/usr/bin/env bash
#
# H1 승률 0% 전략 세션 중지 (2026-08-24)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 왜 이 3개만인가
#   PULLBACK_MTF 를 제외한 잔여 6전략의 08-19~23 청산 191건을 타임프레임으로 가르면:
#
#     M15  160건  승률 28.8%  +1,766,303   (손익분기 25.0%)
#     H1    31건  승률  9.7%    -946,860   (손익분기 16.3%)
#
#   H1 부진은 PULLBACK 탓이 아니다 — 순수 타임프레임 효과다. 다만 **H1 표본이 31건으로 얇아**
#   전면 중지는 근거가 부족하다. 전략별로 보면 성격이 갈린다:
#
#     MOMENTUM_ICHIMOKU_V2  n=7  승률 0.0%  -579,744   ← 중지
#     MTF_CONFIRMED         n=6  승률 0.0%  -495,660   ← 중지
#     MOMENTUM_ICHIMOKU     n=6  승률 0.0%  -495,660   ← 중지
#     ────────────────────────────────────── 13건 연속 전패, 합 -157만
#     MEANREV_BB            n=4  승률 25.0% +361,495   ← 유지 (1승, 운의 영역)
#     MTF_BTC               n=4  승률 25.0% +131,355   ← 유지
#     MTF_BTC_STRICT        n=4  승률 25.0% +131,355   ← 이미 비활성(중복)
#
#   흑자 쪽은 n=4 에 1승씩이라 판단을 미룬다. 적자 쪽은 **13건 연속 전패**라 우연으로 보기 어렵다
#   (승률 25% 가정 시 13연패 확률 ≒ 2.4%).
#
# ■ 범위
#   고정코인 PAPER 의 **H1 세션만** 중지한다. 같은 전략의 M15 세션은 흑자이므로 건드리지 않는다.
#   동적 세션도 건드리지 않는다 — 모집단(워치리스트 스캔)이 다르고 표본이 별도다.
#
# ■ 전략 자체는 비활성화하지 않는다
#   M15 에서는 흑자다. strategy_type_enabled 를 끄면 M15 신규 세션까지 막힌다.
#   여기서는 H1 세션만 정지한다.
#
# 사용법:
#   ssh <운영서버>; cd <리포>; bash scripts/stop_h1_zero_winrate.sh
#   (되돌리려면 POST /paper-trading/sessions/{id}/start)

set -uo pipefail

API="http://localhost:8080/api/v1"
TARGET_STRATEGIES="COMPOSITE_MOMENTUM_ICHIMOKU_V2 COMPOSITE_MTF_CONFIRMED COMPOSITE_MOMENTUM_ICHIMOKU"

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

IDS=$(echo "$sessions" | STRATS="$TARGET_STRATEGIES" python3 -c '
import json,os,sys
want = set(os.environ["STRATS"].split())
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
for s in rows:
    if s.get("status")=="RUNNING" and s.get("timeframe")=="H1" and s.get("strategyName") in want:
        print("%s|%s|%s" % (s["id"], s["strategyName"], s.get("coinPair")))
')

if [ -z "$IDS" ]; then
  echo "▶ 중지할 RUNNING H1 세션이 없습니다. 종료."
  exit 0
fi

count=$(echo "$IDS" | wc -l)
printf '\n\033[1m▶ 중지 대상 H1 세션 %s건\033[0m\n' "$count"
echo "$IDS" | awk -F'|' '{printf "    %-5s %-32s %s\n", $1, $2, $3}'
printf '\n  ※ 정지 시 미청산 포지션은 현재가로 강제 청산된다(전부 PAPER, 실자금 없음).\n'
printf '  ※ 같은 전략의 M15 세션은 흑자이므로 건드리지 않는다.\n'

printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
read -r _

ok=0; fail=0
while IFS='|' read -r id strategy coin; do
  [ -n "$id" ] || continue
  case "$(api -X POST "$API/paper-trading/sessions/$id/stop")" in
    *'"success":true'*) echo "  ✓ $id 정지  ($strategy $coin)"; ok=$((ok+1)) ;;
    *)                  echo "  ✗ $id 실패  ($strategy $coin)"; fail=$((fail+1)) ;;
  esac
done <<EOF
$IDS
EOF

printf '\n\033[1m▶ 결과: 성공 %s / 실패 %s\033[0m\n' "$ok" "$fail"

printf '\n\033[1m▶ 잔여 RUNNING H1 세션\033[0m\n'
api "$API/paper-trading/sessions" | python3 -c '
import json,sys
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
left = [s for s in rows if s.get("status")=="RUNNING" and s.get("timeframe")=="H1"]
if not left:
    print("  없음")
else:
    from collections import Counter
    for name, c in sorted(Counter(s["strategyName"] for s in left).items()):
        print("  %-34s %d건" % (name, c))
'
