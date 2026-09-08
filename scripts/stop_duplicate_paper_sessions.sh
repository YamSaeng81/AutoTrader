#!/usr/bin/env bash
#
# 고정코인 PAPER 세션 중복분 정리 (2026-09-08)
# ─────────────────────────────────────────────────────────────────────────────
# 운영 서버에서 실행. 백엔드 API 는 외부 미개방이라 localhost:8080 으로만 접근된다.
#
# ■ 무엇이 문제인가 (2026-09-07 운영DB 실측)
#   paper_trading.virtual_balance 의 RUNNING 세션이 96건인데
#   (strategy_name, coin_pair, timeframe) 고유 조합은 56개뿐이다.
#   40개 조합이 **세션 2건씩 동시에 돌고 있다**:
#
#     구배치 40건 (id 194~248, 08-07 기동) … stop_loss_pct / strategy_params 가 전부 NULL
#     신배치 40건 (id 330~369, 08-24 기동) … 설정 정상
#
#   08-24 신배치를 띄우면서 구배치를 정리하지 않은 것이다. 같은 신호에 같은 초,
#   같은 진입가로 두 건이 체결된다(예: 09-07 23:15:28 KRW-DOGE — position 3573/3574,
#   entry 124.124 동일, TP만 다름). 표본이 사실상 이중 계상돼 성과 통계가 오염된다.
#
#   구배치는 stop_loss_pct 가 NULL 이라 ExitRuleCalculator.resolveStopLossPct 의
#   floorPct 가 0 이 된다 — ATR 하한이 아예 없는 상태로 돌고 있다는 뜻이기도 하다.
#
# ■ 범위 — 왜 40건만인가
#   구배치 56건 중 40건만 신배치와 겹친다. 나머지 16건(id 118·121·141·144·148·151·
#   159·162·166·169·173·176·180·183·187·190 — 전부 H1)은 신배치에 대응 조합이 없는
#   **단독 세션**이라 멈추면 그 조합의 표본이 사라진다. → 건드리지 않는다.
#   (다만 이 16건도 stop_loss_pct 가 NULL 이다. 아래 "다음 확인" 참조.)
#
#   판별은 ID 하드코딩이 아니라 **조합별 최신 id 만 남기는 방식**이다.
#   신배치가 항상 id 가 크므로 재배포로 세션이 바뀌어도 어긋나지 않는다.
#
# ■ 선행 조건 (중요)
#   이 정리는 ExitRuleChecker 손실구간 SL 조임 제거(2026-09-08) 배포와 **함께** 가야
#   의미가 있다. 그 버그가 살아 있는 채로 중복만 걷어내면, 남은 40건이 계속 같은
#   0.3% 손절로 털린다. 배포 전이라면 먼저 배포할 것.
#
# ■ 부작용
#   stop 은 미청산 포지션을 현재가로 강제 청산한다(PaperTradingService#stop).
#   실행 시점의 대상은 아래 PRECHECK 가 찍어준다. 전부 PAPER 라 실제 자금은 없다.
#
# 사용법:
#   ssh <운영서버>; cd <리포>; bash scripts/stop_duplicate_paper_sessions.sh
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
# 조합(strategyName, coinPair, timeframe)별로 id 가 가장 큰 세션만 남기고 나머지를 뽑는다.
REPORT=$(echo "$probe" | python3 -c '
import json,sys
from collections import defaultdict

d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
running = [s for s in rows if s.get("status") == "RUNNING"]

groups = defaultdict(list)
for s in running:
    groups[(s.get("strategyName"), s.get("coinPair"), s.get("timeframe"))].append(s)

stop, keep, solo = [], [], []
for key, members in groups.items():
    members.sort(key=lambda s: s["id"])
    if len(members) == 1:
        solo.append(members[0])
    else:
        keep.append(members[-1])          # 최신 = 신배치
        stop.extend(members[:-1])         # 나머지 = 구배치 중복

stop.sort(key=lambda s: s["id"])
print("STOP_IDS=" + " ".join(str(s["id"]) for s in stop))
print("SUMMARY=RUNNING %d건 / 고유조합 %d개 / 중복정지 %d건 / 유지 %d건 / 단독 %d건"
      % (len(running), len(groups), len(stop), len(keep), len(solo)))
for s in stop:
    print("ROW=  %4s %-32s %-10s %-4s  수익률 %s%%" % (
        s["id"], s.get("strategyName"), s.get("coinPair"),
        s.get("timeframe"), s.get("totalReturnPct")))
')

STOP_IDS=$(echo "$REPORT" | sed -n 's/^STOP_IDS=//p')
if [ -z "$STOP_IDS" ]; then
  echo "▶ 중복 RUNNING 고정코인 세션이 없습니다. 종료."
  exit 0
fi

printf '\n\033[1m▶ %s\033[0m\n\n' "$(echo "$REPORT" | sed -n 's/^SUMMARY=//p')"
echo "$REPORT" | sed -n 's/^ROW=//p'

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

printf '\n\033[1m▶ 잔여 RUNNING 고정코인 세션 중 중복 조합\033[0m\n'
api "$API/paper-trading/sessions" | python3 -c '
import json,sys
from collections import defaultdict
d = json.load(sys.stdin)["data"]
rows = d["content"] if isinstance(d, dict) and "content" in d else d
g = defaultdict(list)
for s in rows:
    if s.get("status") == "RUNNING":
        g[(s.get("strategyName"), s.get("coinPair"), s.get("timeframe"))].append(s["id"])
dups = {k: v for k, v in g.items() if len(v) > 1}
print("  없음 (정상) — RUNNING %d건" % sum(len(v) for v in g.values()) if not dups else
      "\n".join("  %s %s %s → %s" % (k[0], k[1], k[2], v) for k, v in dups.items()))
'

cat <<'NOTE'

▶ 다음 확인 (DB)
  1) 손절폭이 설정대로 돌아왔는지 — 정지 후 새로 열린 포지션만 본다.
     기대: sl_gap_pct 가 −0.4% 근처가 아니라 하한(5%) 이상.

     SELECT round(avg((p.stop_loss_price/p.entry_price-1)*100)::numeric,2) AS sl_gap_pct,
            count(*) AS n
     FROM paper_trading.position p
     JOIN paper_trading.virtual_balance v ON v.id = p.session_id
     WHERE v.status='RUNNING' AND p.opened_at >= '2026-09-08';

  2) 단독 잔존 16세션의 stop_loss_pct 가 NULL 인 문제.
     floorPct 가 0 이라 ATR 하한 없이 도는 상태다. 채울지 정지할지 별도 판단.

     SELECT id, strategy_name, coin_pair, timeframe, stop_loss_pct
     FROM paper_trading.virtual_balance
     WHERE status='RUNNING' AND stop_loss_pct IS NULL ORDER BY id;
NOTE
