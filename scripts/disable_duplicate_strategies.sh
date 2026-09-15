#!/usr/bin/env bash
#
# 중복 전략 비활성화 — COMPOSITE_MTF_BTC_STRICT (2026-08-24)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 왜 끄나 — strictHtf 가 구조적으로 무효다 (증명 완료)
#   COMPOSITE_MTF_BTC_STRICT 는 COMPOSITE_MTF_BTC 와 `strictHtf=true` 하나만 다르다.
#   그런데 운영 실측에서 두 전략의 청산 43건이 코인·진입시각·실현손익까지 전부 같았다
#   (진입 시각 차 30ms = 같은 tick).
#
#   원인: MtfConfirmedStrategy 에서 strictHtf 가 동작을 바꾸는 분기는 둘뿐인데
#     ① HTF 캔들 부족  ② HTF 신호가 HOLD
#   HTF 확인자인 SupertrendStrategy 는 **데이터만 있으면 절대 HOLD 를 내지 않고**
#   (추세선 위=BUY / 아래=SELL 이분법), getMinimumCandleCount() = max(ltf, 4×12) 이라
#   호출 시점에 HTF 캔들 12개가 이미 보장된다. → 두 분기 모두 도달 불가.
#
#   회귀 고정: core-engine SupertrendStrictHtfNoOpTest (300 시나리오 × BUY/SELL 동일성 검증)
#
#   ※ strict 를 의미 있게 되살리려면 HTF 확인자를 HOLD 를 낼 수 있는 전략으로 바꿔야 한다
#     (예: ADX 임계 미달 시 HOLD). 지금 상태로는 표본만 둘로 쪼갠다.
#
# ■ 비활성화가 하는 일 / 안 하는 일
#   StrategyEnablementGate 는 **신규 세션 생성만** 막는다(LIVE·DYNAMIC·PAPER 공통).
#   이미 RUNNING 인 세션은 계속 돈다 → 이 스크립트가 세션 정지까지 함께 처리한다.
#
# 사용법:
#   ssh <운영서버>; cd <리포>; bash scripts/disable_duplicate_strategies.sh

set -uo pipefail

API="http://localhost:8080/api/v1"
TARGET="COMPOSITE_MTF_BTC_STRICT"

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

probe=$(api "$API/strategies")
case "$probe" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다: $(echo "$probe" | head -c 200)"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요."; exit 1 ;;
esac
echo "✓ 인증 확인"

# ── 현재 활성 상태 ───────────────────────────────────────────────────────────
# PATCH .../active 는 set 이 아니라 **toggle** 이다. 이미 false 면 켜 버리므로 먼저 읽는다.
current=$(api "$API/strategies/$TARGET" | python3 -c '
import json,sys
try:
    d = json.load(sys.stdin)
    print(str(d["data"]["isActive"]).lower() if d.get("data") else "missing")
except Exception:
    print("missing")
')

echo "▶ $TARGET 현재 활성 상태: $current"
if [ "$current" = "missing" ]; then
  echo "✗ 전략을 조회하지 못했습니다. 이름/등록 여부를 확인하세요."; exit 1
fi

# ── 대상 세션 ────────────────────────────────────────────────────────────────
PAPER_IDS=$(api "$API/paper-trading/sessions" | python3 -c '
import json,sys
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
print(" ".join(str(s["id"]) for s in rows
                if s.get("status")=="RUNNING" and s.get("strategyName")=="COMPOSITE_MTF_BTC_STRICT"))
')
DYN_IDS=$(api "$API/dynamic-sessions" | python3 -c '
import json,sys
print(" ".join(str(s["id"]) for s in json.load(sys.stdin)["data"]
                if s.get("status")=="RUNNING" and s.get("strategyType")=="COMPOSITE_MTF_BTC_STRICT"))
')

printf '\n\033[1m▶ 정지할 RUNNING 세션\033[0m\n'
printf '  고정코인 PAPER : %s\n' "${PAPER_IDS:-(없음)}"
printf '  동적          : %s\n' "${DYN_IDS:-(없음)}"
printf '\n  ※ 정지 시 미청산 포지션은 현재가로 강제 청산된다(전부 PAPER, 실자금 없음).\n'

printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
read -r _

# ── 1) 세션 정지 ─────────────────────────────────────────────────────────────
ok=0; fail=0
for id in ${PAPER_IDS:-}; do
  case "$(api -X POST "$API/paper-trading/sessions/$id/stop")" in
    *'"success":true'*) echo "  ✓ paper $id 정지"; ok=$((ok+1)) ;;
    *)                  echo "  ✗ paper $id 실패"; fail=$((fail+1)) ;;
  esac
done
for id in ${DYN_IDS:-}; do
  case "$(api -X POST "$API/dynamic-sessions/$id/stop")" in
    *'"success":true'*) echo "  ✓ dynamic $id 정지"; ok=$((ok+1)) ;;
    *)                  echo "  ✗ dynamic $id 실패"; fail=$((fail+1)) ;;
  esac
done

# ── 2) 전략 비활성화 (신규 세션 생성 차단) ───────────────────────────────────
if [ "$current" = "true" ]; then
  resp=$(api -X PATCH "$API/strategies/$TARGET/active")
  after=$(echo "$resp" | python3 -c '
import json,sys
try: print(str(json.load(sys.stdin)["data"]["isActive"]).lower())
except Exception: print("?")
')
  if [ "$after" = "false" ]; then
    echo "  ✓ $TARGET 비활성화 (isActive=false)"
  else
    echo "  ✗ 비활성화 실패 — isActive=$after / 응답: $(echo "$resp" | head -c 200)"
    fail=$((fail+1))
  fi
else
  echo "  · $TARGET 은 이미 비활성 상태 — 토글하지 않음"
fi

# ── 검증 ─────────────────────────────────────────────────────────────────────
printf '\n\033[1m▶ 결과: 성공 %s / 실패 %s\033[0m\n' "$ok" "$fail"

printf '\n\033[1m▶ 활성 전략 목록\033[0m\n'
api "$API/strategies" | python3 -c '
import json,sys
for s in sorted(json.load(sys.stdin)["data"], key=lambda x: x["name"]):
    if s.get("isActive") and s["name"].startswith("COMPOSITE"):
        print("  %s" % s["name"])
'

cat <<'NOTE'

▶ 참고 — 남은 전략들의 신호원 중복 (비활성화 대상 아님, 판단 자료)
  활성 전략 6개가 독립적이지 않다. 실제 신호 코어는 3종뿐이다:

    MACD+VWAP+GRID (CMI_V1) … MOMENTUM_ICHIMOKU
                              MTF_CONFIRMED (RegimeRouter 가 4개 레짐 중 3개를 CMI_V1 로 위임)
    ATR+VD+MACD    (CB)     … MTF_BTC, [MTF_BTC_STRICT ← 방금 제거]
                              MTF_CONFIRMED (VOLATILITY 레짐)
    MACD+SUPERTREND+GRID    … MOMENTUM_ICHIMOKU_V2
    BB+RSI+VWAP             … MEANREV_BB  ← 유일하게 직교

  MACD 가 6개 중 5개에 들어간다. 포트폴리오 분산 효과가 기대보다 훨씬 작다는 뜻이다.
NOTE
