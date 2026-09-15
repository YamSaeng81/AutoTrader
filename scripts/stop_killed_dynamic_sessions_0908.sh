#!/usr/bin/env bash
#
# 폐기 판정된 동적 세션 정지 (2026-09-08) — 세션 83·91
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 왜 멈추나
#   시스템 자신의 폐기 기준(StrategyKillCriteriaService)이 오늘 이미 두 세션에
#   KILL/CAPITAL_LOSS 를 내렸다. kill_criteria_judgment 실측:
#
#     83  COMPOSITE_MTF_BTC     M15  9거래  -15.98%  "누적 손실 -15.98% (한도 -15.0%)"
#     91  COMPOSITE_MEANREV_BB  M15  7거래  -15.34%  "누적 손실 -15.34% (한도 -15.0%)"
#
#   그런데 kill-criteria.auto-stop 이 false(기본값, 어느 yml 에도 미설정)라
#   auto_stop_applied=false 로 기록만 되고 실제 정지는 아무것도 되지 않았다.
#   이 스크립트가 그 판정을 손으로 집행한다.
#
#   MDD 여유도 얼마 없다 — 서킷브레이커 한도 20% 대비 83 은 16.46%(여유 3.54%p),
#   91 은 15.82%(여유 4.18%p). 손절 한 번(-5.5%)이면 어차피 자동 정지된다.
#
# ■ 표본이 얇다는 점은 알고 간다 (9거래/7거래)
#   그래서 "전략 폐기"가 아니라 "세션 정지"까지만 한다. 실제로 같은 전략의 다른
#   세션은 멀쩡하다 — MTF_BTC@M15 는 세션 90 이 +1.03%, MEANREV_BB 는 H1 세션 76 이
#   +14.29% 로 전체 1위다. 한 변형의 실패는 그 전략 전체의 실패가 아니다
#   (StrategyKillCriteriaService#disableFullyKilledStrategies 주석과 같은 취지).
#   → strategy_type_enabled 는 건드리지 않는다.
#
#   CAPITAL_LOSS 는 애초에 표본 수와 무관한 **자본 보존** 기준이다. 엣지 판정
#   (NEGATIVE_EV 등)과 달리 n 이 작아도 발동하는 것이 설계 의도다.
#
# ■ 주의 — 고정코인 PAPER 의 KILL 80건은 여기서 집행하지 않는다
#   같은 날 PAPER 세션 80건에도 NEGATIVE_EV KILL 이 찍혔지만, 그 음의 기대값은
#   ExitRuleChecker 손실구간 SL 조임 버그가 만든 것이다(손절폭이 설정 5% 가 아니라
#   0.43% 로 걸려 85% 가 휩쏘 손절). 버그가 만든 통계로 전략을 폐기하면 안 된다.
#   → 수정 배포 + 새 표본 누적 후 재판정할 것.
#
# ■ 부작용
#   stop 은 미청산 포지션을 현재가로 강제 청산한다. 83·91 둘 다 실행 시점에
#   포지션을 들고 있을 수 있다(09-07 기준 83=KRW-XLM, 91=KRW-ORCA).
#   전부 trading_mode=PAPER 라 실제 자금은 없다.
#
# 사용법:
#   ssh <운영서버>; cd <리포>; bash scripts/stop_killed_dynamic_sessions_0908.sh
#   (되돌리려면 POST /dynamic-sessions/{id}/start)

set -uo pipefail

API="http://localhost:8080/api/v1"
TARGETS="83 91"

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
  "")             echo "✗ 응답이 비었습니다 — 백엔드가 떠 있는지 확인하세요."; exit 1 ;;
esac
echo "✓ 인증 확인"

# ── PRECHECK — 대상이 아직 RUNNING 인지, 성적이 근거와 맞는지 확인 ───────────
printf '\n\033[1m▶ 정지 대상 확인\033[0m\n'
RUNNING_TARGETS=$(echo "$probe" | TARGETS="$TARGETS" python3 -c '
import json,os,sys
want = set(int(x) for x in os.environ["TARGETS"].split())
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
live = []
for s in rows:
    if s.get("id") in want:
        status = s.get("status")
        print("  %3s %-28s %-4s %-12s 수익률 %s%%" % (
            s["id"], s.get("strategyType"), s.get("timeframe"),
            status, s.get("returnPct")), file=sys.stderr)
        if status == "RUNNING":
            live.append(s["id"])
missing = want - {s.get("id") for s in rows}
for m in sorted(missing):
    print("  %3s (응답에 없음 — 이미 삭제됐을 수 있음)" % m, file=sys.stderr)
print(" ".join(str(i) for i in sorted(live)))
')

if [ -z "$RUNNING_TARGETS" ]; then
  echo "▶ RUNNING 상태인 대상이 없습니다 (이미 정지/서킷브레이커 발동). 종료."
  exit 0
fi

printf '\n정지할 세션: %s\n' "$RUNNING_TARGETS"
printf '계속하려면 Enter, 중단하려면 Ctrl-C: '
read -r _

# ── 실행 ─────────────────────────────────────────────────────────────────────
ok=0; fail=0
for id in $RUNNING_TARGETS; do
  resp=$(api -X POST "$API/dynamic-sessions/$id/stop")
  case "$resp" in
    *'"success":true'*) echo "  ✓ dynamic $id 중지"; ok=$((ok+1)) ;;
    *)                  echo "  ✗ dynamic $id 실패: $(echo "$resp" | head -c 160)"; fail=$((fail+1)) ;;
  esac
done

printf '\n\033[1m▶ 결과: 성공 %s / 실패 %s\033[0m\n' "$ok" "$fail"

cat <<'NOTE'

▶ 다음 확인 (DB)
  1) 정지 반영 및 잔여 동적 세션의 MDD 여유 — 20% 한도에 가까운 세션이 또 있는지.

     SELECT id, strategy_type, timeframe, status,
            round((1 - total_asset_krw/nullif(mdd_peak_capital,0))*100, 2) AS mdd_pct,
            round((total_asset_krw/initial_capital - 1)*100, 2)            AS pnl_pct
     FROM dynamic_session
     WHERE status = 'RUNNING'
     ORDER BY mdd_pct DESC;

  2) 폐기 판정이 집행되지 않고 쌓이는 구조 자체 (kill-criteria.auto-stop=false).
     최근 10일 KILL 664건 전부 auto_stop_applied=false 다.

     SELECT verdict, code, count(*) AS n,
            count(*) FILTER (WHERE auto_stop_applied) AS applied
     FROM kill_criteria_judgment
     WHERE evaluated_at > now() - interval '10 days'
     GROUP BY 1, 2 ORDER BY 3 DESC;

     ※ auto-stop 을 켜는 것은 별도 판단이다. 지금 켜면 SL 버그가 만든 PAPER
       NEGATIVE_EV 80건까지 한꺼번에 집행된다. 수정 배포 후 새 표본이 쌓인 뒤에 검토할 것.
NOTE
