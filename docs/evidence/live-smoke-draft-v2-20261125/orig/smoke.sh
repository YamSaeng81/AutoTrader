# 소액 실주문 시험 서버 단계 — 초안(v1.243 §3-1). launch.sh 가 변수 줄을 앞에 붙여 ssh bash -s 로 보낸다. 직접 붙여 넣지 않는다.
# 🔴 실행 금지: 총괄의 원문 수용·승인 전. 인증값은 서버 .env 에서 그 자리에서만 읽고 출력·저장하지 않는다.
set -u -o pipefail
stop() { echo "🔴 멈춤: $*"; exit 1; }
for k in APPROVED_DATE WIN_START WIN_END EXPECTED_SHA EXPECTED_IMAGE POOL_EXPECTED ALLOC_BEFORE RUN_TAG STEP; do
  [ -n "${!k:-}" ] || stop "$k 비어 있음(원격 검사)"; done
case "$STEP" in p0|create) [ -z "$SID" ] || stop "생성 전 단계인데 SID 있음";; *) [[ "$SID" =~ ^[0-9]+$ ]] || stop "SID 없음";; esac
case "$STEP" in p0|create|verify|start)
  [ "$(TZ=Asia/Seoul date +%F)" = "$APPROVED_DATE" ] || stop "승인 날짜 아님"
  hm=$((10#$(TZ=Asia/Seoul date +%H%M))); [ $hm -ge $((10#$WIN_START)) ] && [ $hm -le $((10#$WIN_END)) ] || stop "승인 창 밖";; esac
cd ~/crypto_trader/AutoTrader || stop "작업 폴더 없음"
DC="docker compose -f docker-compose.prod.yml"
PSQL="$DC exec -T db psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader"
M=~/smoke-$RUN_TAG
if [ "$STEP" = p0 ]; then [ ! -e "$M" ] || stop "$M 이 이미 있다 — 다른 실행 흔적"; mkdir "$M" || stop "기록 폴더 생성 실패"; fi
[ -d "$M" ] || stop "기록 폴더 없음 — P0 부터"
case "$STEP" in create|start|stop) [ ! -e "$M/sent-$STEP" ] || stop "$STEP 이미 보냄(원격 표지)";; esac
tok() { grep -m1 '^API_AUTH_TOKEN=' .env | cut -d= -f2-; }
api() { local m=$1 u=$2; shift 2; curl -s -m 20 -X "$m" -H "Authorization: Bearer $(tok)" -H 'Content-Type: application/json' "$@" "http://localhost:8080$u"; }
q() { $PSQL -At -F '|' -c "$1"; }

case "$STEP" in
p0)  # 사전 읽기 — 하나라도 다르면 생성하지 않는다
  { git rev-parse HEAD; git status --porcelain --untracked-files=no; echo "(끝)"; } | tee "$M/git.txt"
  [ "$(head -1 "$M/git.txt")" = "$EXPECTED_SHA" ] && [ "$(sed -n 2p "$M/git.txt")" = "(끝)" ] || stop "설치 SHA 불일치 또는 추적 파일 변경"
  [ "$(docker inspect --format '{{.Image}}' "$($DC ps -q backend)")" = "$EXPECTED_IMAGE" ] || stop "backend 이미지 불일치"
  $DC exec -T backend env | grep '^TRADING_CAPITALPOOL_' | sort | cut -d= -f2 | paste -sd'|' | tee "$M/pool.txt"
  [ "$(cat "$M/pool.txt")" = "$POOL_EXPECTED" ] || stop "공유 풀 등록값 불일치"
  q "SELECT now(), clock_timestamp()" | tee "$M/clock.txt"; date -u '+%F %T UTC' | tee -a "$M/clock.txt"   # 서버·DB 시계 대조(기록만)
  q "SELECT COALESCE(sum(initial_capital),0) FROM live_trading_session WHERE session_type='REAL' AND status IN ('CREATED','RUNNING')" | tee "$M/alloc-live.txt"
  q "SELECT COALESCE(sum(initial_capital),0) FROM dynamic_session WHERE trading_mode='REAL' AND status IN ('CREATED','RUNNING')" | tee "$M/alloc-dyn.txt"
  python3 -c 'import sys;from decimal import Decimal as D;a=D(open(sys.argv[1]).read())+D(open(sys.argv[2]).read());print("배정 합",a);sys.exit(0 if a==D(sys.argv[3]) else 1)' "$M/alloc-live.txt" "$M/alloc-dyn.txt" "$ALLOC_BEFORE" || stop "활성 실자금 배정 합이 ALLOC_BEFORE 와 다름"
  q "SELECT count(*) FROM \"order\" o LEFT JOIN live_trading_session l ON o.session_kind='LIVE' AND l.id=o.session_id LEFT JOIN dynamic_session d ON o.session_kind='DYNAMIC' AND d.id=o.session_id WHERE o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED') AND (l.session_type='REAL' OR d.trading_mode='REAL')" | tee "$M/active.txt"
  q "SELECT count(*) FROM position_cleanup WHERE done_at IS NULL" | tee "$M/cleanup.txt"
  [ "$(cat "$M/active.txt")" = 0 ] && [ "$(cat "$M/cleanup.txt")" = 0 ] || stop "실자금 활성 주문 또는 정리 대기 있음"
  api GET /api/v1/trading/health/exchange | tee "$M/exchange.json"; echo
  python3 -c 'import json,sys;d=json.load(open(sys.argv[1]))["data"];sys.exit(0 if d["status"]=="UP" else 1)' "$M/exchange.json" || stop "거래소 UP 아님"
  # A3 최소 필드 — 승인된 칸만 출력. '조회 성공 = 충분'으로 보지 않고 아래 부등식으로만 판정한다.
  api GET /api/v1/account/summary > "$M/account.json"
  python3 - "$M/account.json" "$ALLOC_BEFORE" <<'PY' | tee "$M/a3.txt" || stop "A3: 주문 가능 KRW < 기존 배정 + 10,000 이거나 묶인 KRW 있음 또는 조회 실패"
import json, sys
from decimal import Decimal as D
d = json.load(open(sys.argv[1]))["data"]; a = D(str(d["availableKrw"])); l = D(str(d["lockedKrw"])); need = D(sys.argv[2]) + D("10000")
print("주문 가능", a, "· 묶인", l, "· 필요(기존 배정+10000)", need)
sys.exit(0 if a >= need and l == 0 else 1)
PY
  echo "미확정(이 단계로 닫지 않음): 계정 실제 수수료율(코드 0.0005 가정)·거래소 최소 주문액(코드 5,000 가정)·장부 밖 잔량"
  echo "✅ P0 통과";;
create)  # 생성 1회
  date '+%F %T %Z' > "$M/sent-create"
  api POST /api/v1/trading/sessions -d '{"strategyType":"DAILY_SMA20","coinPair":"KRW-BTC","timeframe":"H1","initialCapital":10000,"investRatio":0.995,"maxInvestment":9950,"stopLossPct":5.0,"maxHoldHours":0,"strategyParams":{"maxEntries":1}}' | tee "$M/create.json"; echo
  python3 -c 'import json,sys;r=json.load(open(sys.argv[1]));d=r.get("data") or {};print("생성 id",d.get("id"),"status",d.get("status"));sys.exit(0 if r.get("success") and d.get("status")=="CREATED" else 1)' "$M/create.json" || stop "생성 응답이 성공/CREATED 아님 — 다시 보내지 않고 보고";;
verify)  # 응답·저장 대조 — 다르면 start 하지 않는다
  python3 - "$M/create.json" "$SID" <<'PY' || stop "생성 응답 대조 실패"
import json, sys
d = json.load(open(sys.argv[1]))["data"]; p = d.get("strategyParams") or {}
ok = (str(d["id"]) == sys.argv[2] and d["status"] == "CREATED" and d.get("sessionType") == "REAL"
      and type(p.get("maxEntries")) is int and p["maxEntries"] == 1)
print("응답", d["id"], d["status"], d.get("sessionType"), p); sys.exit(0 if ok else 1)
PY
  q "SELECT status, session_type, jsonb_typeof(strategy_params->'maxEntries'), strategy_params->>'maxEntries', initial_capital=10000, invest_ratio=0.995, max_investment=9950, started_at IS NULL FROM live_trading_session WHERE id=$SID" | tee "$M/verify.txt"
  [ "$(cat "$M/verify.txt")" = "CREATED|REAL|number|1|t|t|t|t" ] || stop "DB 저장값 불일치";;
start)
  date '+%F %T %Z' > "$M/sent-start"
  api POST /api/v1/trading/sessions/$SID/start | tee "$M/start.json"; echo
  q "SELECT status, started_at IS NOT NULL FROM live_trading_session WHERE id=$SID" | tee "$M/start-check.txt";;
watch)  # 관측 읽기만 — 신호를 만들지 않는다
  q "SELECT id, side, state, exchange_order_id IS NOT NULL, applied_executed_funds, applied_paid_fee, fill_accounting_pending, sell_applied_at IS NOT NULL, failure_code, created_at FROM \"order\" WHERE session_kind='LIVE' AND session_id=$SID ORDER BY id" | tee -a "$M/watch.txt"
  q "SELECT id, status, size, exit_reason, closed_at FROM position WHERE session_kind='LIVE' AND session_id=$SID ORDER BY id" | tee -a "$M/watch.txt"
  q "SELECT status, available_krw FROM live_trading_session WHERE id=$SID" | tee -a "$M/watch.txt"
  $DC logs --since 2h backend 2>&1 | grep -E "ENTRY_LIMIT_REACHED|sessionId=$SID[,)]" | tail -20 | tee -a "$M/watch.txt";;
stop)  # 정지 1회 — 보유가 있으면 FORCED_STOP 시장가 매도가 함께 나간다(대기 종료와 구분해 기록)
  q "SELECT count(*) FROM position WHERE session_kind='LIVE' AND session_id=$SID AND (status IN ('OPEN','CLOSING') OR size<>0)" | tee "$M/held-before-stop.txt"
  date '+%F %T %Z' > "$M/sent-stop"
  api POST /api/v1/trading/sessions/$SID/stop | tee "$M/stop.json"; echo;;
esac
run_gate() {
  echo GATE-OUT-BEGIN
  $DC exec -T db psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -A -F '|' -P footer=off -v sid="$SID" -f - < "$M/gate.sql"
  r=$?; echo GATE-OUT-END; echo "GATE-RC=$r"
}
