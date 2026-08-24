#!/usr/bin/env bash
#
# COMPOSITE_PULLBACK_MTF 고정코인 PAPER 세션 중지 (2026-08-24)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 왜 멈추나 (2026-08-24 운영DB 실측, 08-19 재기동 ~ 08-23)
#   paper_trading 청산 354건을 전략별로 가르면 손실이 한 전략에 몰려 있다:
#
#     COMPOSITE_PULLBACK_MTF … 163건  승률 11.7%  누적 -4,834,211
#     나머지 6전략          … 191건  승률 25.7%  누적   +819,443
#
#   평균이익/평균손실 = 214,155 / 61,828 → R:R 3.46 이면 손익분기 승률이 22.4% 인데
#   실측 11.7% 다. M15(-349만, 12.8%)·H1(-135만, 6.7%) 양쪽에서 동일하게 무너진다.
#   n=163 이라 표본도 충분하다. 이 전략만 빼면 나머지 시스템은 흑자다.
#
# ■ 범위 — 왜 고정코인 16세션만인가 (중요)
#   근거가 되는 163건은 전부 **고정코인 페이퍼**(paper_trading 스키마) 표본이다.
#   동적 세션의 PULLBACK_MTF 는 **정반대** 성적이다 — DYN_PAPER n=42 에서 +1,865 로
#   7전략 중 2위다. 모집단(고정 1코인 vs 워치리스트 스캔)이 달라 근거를 옮겨 쓸 수 없다.
#
#   게다가 동적 PULLBACK 6세션은 08-19 부터 돌고 있는 **감쇠 A/B 실험 그 자체**다:
#       56(H1) / 63(M15) … 대조군 (strategy_params NULL)
#       68(H1) / 69(M15) … A군  {"emaFilterDampenFactor": 1.0}
#       70(H1) / 71(M15) … B군  {"transitionalDampenFactor": 1.0}
#   여기를 멈추면 5일치 A/B 가 통째로 날아간다. → 건드리지 않는다.
#   (그래도 멈춰야 한다면 STOP_DYNAMIC=1 로 실행. A/B 폐기를 감수한다는 뜻이다.)
#
# ■ 부작용
#   stop 은 미청산 포지션을 현재가로 강제 청산한다(PaperTradingService#stop).
#   실행 시점의 대상은 아래 PRECHECK 가 찍어준다. 전부 PAPER 라 실제 자금은 없다.
#
# 사용법:
#   ssh <운영서버>; cd <리포>; bash scripts/stop_pullback_paper_fleet.sh
#   (되돌리려면 각 세션에 POST /paper-trading/sessions/{id}/start)

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

probe=$(api "$API/paper-trading/sessions")
case "$probe" in
  *UNAUTHORIZED*) echo "✗ 토큰이 거부됐습니다: $(echo "$probe" | head -c 200)"; exit 1 ;;
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요."; exit 1 ;;
esac
echo "✓ 인증 확인"

# ── 대상 확정 ────────────────────────────────────────────────────────────────
# ID 를 하드코딩하지 않고 API 응답에서 뽑는다. 재배포로 세션이 바뀌어도 어긋나지 않는다.
PAPER_IDS=$(echo "$probe" | python3 -c '
import json,sys
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
print(" ".join(str(s["id"]) for s in rows
                if s.get("status") == "RUNNING"
                and s.get("strategyName") == "COMPOSITE_PULLBACK_MTF"))
')

if [ -z "$PAPER_IDS" ]; then
  echo "▶ 중지할 RUNNING PULLBACK_MTF 고정코인 세션이 없습니다. 종료."
  exit 0
fi

count=$(echo "$PAPER_IDS" | wc -w)
printf '\n\033[1m▶ 중지 대상 고정코인 PAPER 세션 %s건\033[0m\n  %s\n' "$count" "$PAPER_IDS"
printf '\n  동적 세션 6건(56·63·68·69·70·71)은 A/B 실험이라 %s\n' \
       "$([ "${STOP_DYNAMIC:-0}" = "1" ] && echo '★ 함께 중지합니다 (STOP_DYNAMIC=1)' || echo '건드리지 않습니다.')"

printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
read -r _

# ── 실행 ─────────────────────────────────────────────────────────────────────
ok=0; fail=0
for id in $PAPER_IDS; do
  resp=$(api -X POST "$API/paper-trading/sessions/$id/stop")
  case "$resp" in
    *'"success":true'*) echo "  ✓ paper $id 중지"; ok=$((ok+1)) ;;
    *)                  echo "  ✗ paper $id 실패: $(echo "$resp" | head -c 160)"; fail=$((fail+1)) ;;
  esac
done

if [ "${STOP_DYNAMIC:-0}" = "1" ]; then
  echo
  echo "▶ 동적 세션 중지 (A/B 실험 폐기)"
  for id in 56 63 68 69 70 71; do
    resp=$(api -X POST "$API/dynamic-sessions/$id/stop")
    case "$resp" in
      *'"success":true'*) echo "  ✓ dynamic $id 중지"; ok=$((ok+1)) ;;
      *)                  echo "  ✗ dynamic $id 실패: $(echo "$resp" | head -c 160)"; fail=$((fail+1)) ;;
    esac
  done
fi

# ── 검증 ─────────────────────────────────────────────────────────────────────
printf '\n\033[1m▶ 결과: 성공 %s / 실패 %s\033[0m\n' "$ok" "$fail"

printf '\n\033[1m▶ 잔여 RUNNING PULLBACK_MTF 고정코인 세션\033[0m\n'
api "$API/paper-trading/sessions" | python3 -c '
import json,sys
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
left = [s for s in rows if s.get("status")=="RUNNING"
                       and s.get("strategyName")=="COMPOSITE_PULLBACK_MTF"]
print("  없음 (정상)" if not left else
      "\n".join("  %3s %-10s %s" % (s["id"], s.get("coinPair"), s.get("timeframe")) for s in left))
'

cat <<'NOTE'

▶ 다음 확인 (DB)
  중지 후 잔여 함대 성적을 다시 뽑아 H1 축소 여부를 판단한다.
  PULLBACK 을 걷어낸 뒤에도 H1 이 나쁘면 그건 순수 타임프레임 효과다.

    SELECT v.timeframe,
           count(*) AS n,
           round(100.0*count(*) FILTER (WHERE p.realized_pnl>0)/count(*),1) AS winrate,
           round(sum(p.realized_pnl),0) AS pnl
    FROM paper_trading.position p
    JOIN paper_trading.virtual_balance v ON v.id = p.session_id
    WHERE p.status='CLOSED' AND p.closed_at >= '2026-08-24'
      AND v.strategy_name <> 'COMPOSITE_PULLBACK_MTF'
    GROUP BY 1;
NOTE
