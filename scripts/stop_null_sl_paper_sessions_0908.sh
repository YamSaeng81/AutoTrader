#!/usr/bin/env bash
#
# stop_loss_pct 가 NULL 인 잔존 고정코인 PAPER 세션 정지 (2026-09-08)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 무엇이 남았나
#   stop_duplicate_paper_sessions.sh 로 구배치 중복 40건을 정지한 뒤,
#   08-07/08-18 배치의 **단독 16건**이 RUNNING 으로 남았다(전부 H1).
#   신배치(330~369)에 대응 조합이 없어 중복 정리에서 제외했던 것들이다.
#
#     118·141·148·159·166·173·180·187  COMPOSITE_MEANREV_BB  @ H1
#     121·144·151·162·169·176·183·190  COMPOSITE_MTF_BTC     @ H1
#
# ■ 왜 멈추나
#   1) 설정이 비어 있다 — stop_loss_pct 와 strategy_params 가 둘 다 NULL 이다.
#      ExitRuleCalculator.resolveStopLossPct 의 floorPct 가 0 이 되어 **ATR 하한이
#      아예 없는 상태**로 돈다. 트레일링 SL 버그(09-08 수정)와는 별개로 남는 문제이며,
#      운영 중인 다른 어떤 세션과도 다른 유일한 설정 상태다.
#
#   2) 표본을 거의 못 만든다 — 32일간 청산이 세션당 0~9건, 16세션 합쳐 44건이다
#      (세션 141 은 한 달간 0건). 유지 비용 대비 얻는 데이터가 없다.
#      오늘 판정에서도 NEGATIVE_ALPHA KILL 8건 + NO_SIGNAL WARN 3건이 찍혔다.
#
#   3) 성적 자체는 −3.53% ~ +3.50% 로 거의 평평하다. 거래를 안 해서 그렇다 —
#      "살려 둘 만큼 좋다"는 근거가 아니라 "표본이 없다"는 뜻이다.
#
# ■ 대조군을 버리는 것 아닌가
#   floorPct=0 arm 을 대조군으로 남길 수도 있었다. 그러나 (a) 거래량이 없어 대조가
#   성립하지 않고, (b) 손절폭 A/B 는 이미 strategy_params 오버라이드(ExitRuleOverrides)
#   라는 제대로 된 경로가 있다. 설정 누락을 실험군처럼 쓰는 건 그 경로를 우회하는 것이다.
#
# ■ 범위 — 신배치 40건은 건드리지 않는다
#   판별은 ID 하드코딩이 아니라 **strategyParams 가 null 인 RUNNING 세션**이다.
#   운영 DB 실측상 stop_loss_pct NULL 16건과 strategy_params NULL 16건이 정확히
#   같은 집합이고, 신배치 40건은 둘 다 채워져 있다(2026-09-08 확인).
#   API 응답에 stopLossPct 는 없고 strategyParams 는 있어 이 조건을 쓴다.
#
# ■ 부작용
#   stop 은 미청산 포지션을 현재가로 강제 청산한다(PaperTradingService#stop).
#   2026-09-08 확인 시점에 16건 모두 **보유 포지션 0건**이라 강제청산은 발생하지
#   않을 전망이다. 실제 대상은 아래 PRECHECK 가 찍어준다. 전부 PAPER 라 실제 자금은 없다.
#
# 사용법:
#   ssh <운영서버>; cd <리포>; bash scripts/stop_null_sl_paper_sessions_0908.sh
#   (되돌리려면 POST /paper-trading/sessions/{id}/start)

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
REPORT=$(echo "$probe" | python3 -c '
import json,sys

d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
running = [s for s in rows if s.get("status") == "RUNNING"]

target = [s for s in running if s.get("strategyParams") in (None, "")]
keep   = [s for s in running if s not in target]
target.sort(key=lambda s: s["id"])

print("STOP_IDS=" + " ".join(str(s["id"]) for s in target))
print("SUMMARY=RUNNING %d건 중 설정 비어있는 세션 %d건 정지 / %d건 유지"
      % (len(running), len(target), len(keep)))
for s in target:
    print("ROW=  %4s %-24s %-10s %-4s  수익률 %s%%" % (
        s["id"], s.get("strategyName"), s.get("coinPair"),
        s.get("timeframe"), s.get("totalReturnPct")))
')

STOP_IDS=$(echo "$REPORT" | sed -n 's/^STOP_IDS=//p')
if [ -z "$STOP_IDS" ]; then
  echo "▶ 설정이 비어 있는 RUNNING 세션이 없습니다. 종료."
  exit 0
fi

printf '\n\033[1m▶ %s\033[0m\n\n' "$(echo "$REPORT" | sed -n 's/^SUMMARY=//p')"
echo "$REPORT" | sed -n 's/^ROW=//p'

# ── 보유 포지션 확인 (강제청산 여부를 미리 보여준다) ─────────────────────────
printf '\n\033[1m▶ 보유 포지션 확인\033[0m\n'
held=0
for id in $STOP_IDS; do
  n=$(api "$API/paper-trading/sessions/$id/positions?status=OPEN" | python3 -c '
import json,sys
try:
    d = json.load(sys.stdin).get("data") or []
    print(len(d))
except Exception:
    print("?")
')
  if [ "$n" != "0" ]; then
    echo "  ⚠ 세션 $id — 보유 $n 건 (정지 시 현재가로 강제 청산됨)"
    held=$((held+1))
  fi
done
[ "$held" = "0" ] && echo "  보유 포지션 없음 — 강제청산 발생하지 않습니다."

printf '\n계속하려면 Enter, 중단하려면 Ctrl-C: '
read -r _

# ── 실행 ─────────────────────────────────────────────────────────────────────
ok=0; fail=0
for id in $STOP_IDS; do
  resp=$(api -X POST "$API/paper-trading/sessions/$id/stop")
  case "$resp" in
    *'"success":true'*) echo "  ✓ paper $id 중지"; ok=$((ok+1)) ;;
    *)                  echo "  ✗ paper $id 실패: $(echo "$resp" | head -c 160)"; fail=$((fail+1)) ;;
  esac
done

# ── 검증 ─────────────────────────────────────────────────────────────────────
printf '\n\033[1m▶ 결과: 성공 %s / 실패 %s\033[0m\n' "$ok" "$fail"

printf '\n\033[1m▶ 잔여 RUNNING 고정코인 세션\033[0m\n'
api "$API/paper-trading/sessions" | python3 -c '
import json,sys
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
run = [s for s in rows if s.get("status") == "RUNNING"]
empty = [s for s in run if s.get("strategyParams") in (None, "")]
print("  RUNNING %d건 (기대: 40건 — 신배치 330~369)" % len(run))
print("  설정 비어있는 세션: %s" % ("없음 (정상)" if not empty else
      ", ".join(str(s["id"]) for s in sorted(empty, key=lambda s: s["id"]))))
'

cat <<'NOTE'

▶ 다음 확인 (DB)
  1) 잔여 세션이 신배치 40건뿐이고 설정 누락이 없는지.

     SELECT count(*) AS running,
            count(*) FILTER (WHERE stop_loss_pct IS NULL)   AS sl_null,
            count(*) FILTER (WHERE strategy_params IS NULL) AS params_null,
            min(id) AS min_id, max(id) AS max_id
     FROM paper_trading.virtual_balance WHERE status='RUNNING';

  2) 손절폭이 설정대로 유지되는지 — 09-08 09:26 KST 이후 진입분만 본다.
     기대: sl_gap_pct −5.0% 근처 (버그 시절 −0.4% 가 아니라).

     SELECT round(avg((p.stop_loss_price/p.entry_price-1)*100)::numeric,3) AS sl_gap_pct,
            count(*) AS n
     FROM paper_trading.position p
     JOIN paper_trading.virtual_balance v ON v.id = p.session_id
     WHERE v.status='RUNNING' AND p.opened_at >= timestamptz '2026-09-08 00:20+00';

  3) 새 지문으로 표본이 n≥20 쌓인 뒤 승률·기대값을 이전 표본과 갈라서 비교할 것.
     ruleset_hash 가 exit.slTightenOnLoss 키로 갈리므로 섞이지 않는다.

     SELECT p.ruleset_hash, count(*) AS n,
            round(100.0*count(*) FILTER (WHERE p.realized_pnl>0)/count(*),1) AS winrate,
            round(sum(p.realized_pnl),0) AS pnl
     FROM paper_trading.position p
     JOIN paper_trading.virtual_balance v ON v.id = p.session_id
     WHERE p.status='CLOSED' AND p.closed_at >= '2026-09-08'
     GROUP BY 1 ORDER BY 2 DESC;
NOTE
