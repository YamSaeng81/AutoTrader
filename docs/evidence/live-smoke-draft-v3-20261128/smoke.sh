# 소액 실주문 시험 서버 단계 — 초안 v2(v1.243 §3-1 → v1.249 §3 보완). launch.sh 가 변수 줄·KSTPY(kst.py 원문)를 앞에 붙여 ssh bash -s 로 보낸다. 직접 붙여 넣지 않는다.
# 🔴 실행 금지: 총괄의 원문 수용·승인 전. 인증값은 서버 .env 에서 그 자리에서만 읽고 출력·저장하지 않는다.
# v2: 시각은 python3 -c "$KSTPY"(고정 UTC+09:00)로만 판정, create/start 는 POST 바로 앞에서 새 시각으로 재검사.
#     정지 직전 보유 표시는 status IN ('OPEN','CLOSING'). run_gate 는 psql 종료값을 그대로 돌려준다.
set -euo pipefail
# ---- 함수 블록 시작 (tests/ 가 이 블록만 잘라 검증한다)
stop() { echo "🔴 멈춤: $*"; exit 1; }
kst_ok() { python3 -I -c "$KSTPY" window "$STEP" "$APPROVED_DATE" "$WIN_START" "$WIN_END"; }
api() { local m=$1 u=$2 body=''; shift 2
  if [ "$#" != 0 ]; then [ "$#" = 2 ] && [ "$1" = -d ] || return 2; body=$2; fi
  python3 -I -c "$APIPY" "$m" "$u" "$body"
}
q() { $DC exec -T -e 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=15000 -c lock_timeout=2000' db psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -At -F '|' -c "$1"; }
step_create() {  # 생성 1회 — POST 바로 앞 재검사가 실패하면 표지·POST 0
  kst_ok > "$M/precheck-create.txt" || { cat "$M/precheck-create.txt"; stop "POST 직전 시각 재검사 실패 — 생성 안 함"; }
  (set -C; date '+%F %T %Z' > "$M/sent-create") || stop "표지 기록 실패 — 생성 안 함"
  api POST /api/v1/trading/sessions -d '{"strategyType":"DAILY_SMA20","coinPair":"KRW-BTC","timeframe":"H1","initialCapital":10000,"investRatio":0.995,"maxInvestment":9950,"stopLossPct":5.0,"maxHoldHours":0,"strategyParams":{"maxEntries":1}}' > "$M/create.json" || stop "생성 HTTP/응답/기록 실패 — 재전송 금지"
  cat "$M/create.json"
  python3 -c 'import json,sys;r=json.load(open(sys.argv[1]));d=r.get("data") or {};print("생성 id",d.get("id"),"status",d.get("status"));sys.exit(0 if r.get("success") and d.get("status")=="CREATED" else 1)' "$M/create.json" || stop "생성 응답이 성공/CREATED 아님 — 다시 보내지 않고 보고"
}
step_start() {  # 시작 1회 — POST 바로 앞 재검사가 실패하면 표지·POST 0
  kst_ok > "$M/precheck-start.txt" || { cat "$M/precheck-start.txt"; stop "POST 직전 시각 재검사 실패 — 시작 안 함"; }
  (set -C; date '+%F %T %Z' > "$M/sent-start") || stop "표지 기록 실패 — 시작 안 함"
  api POST /api/v1/trading/sessions/$SID/start > "$M/start.json" || stop "시작 HTTP/응답/기록 실패 — 재전송 금지"
  cat "$M/start.json"
  q "SELECT status, started_at IS NOT NULL FROM live_trading_session WHERE id=$SID" > "$M/start-check.txt" || stop "시작 상태 조회/기록 실패"
  [ "$(cat "$M/start-check.txt")" = 'RUNNING|t' ] || stop "시작 후 실제 RUNNING/started_at 확인 실패 — 재전송 금지"
  cat "$M/start-check.txt"
}
HELD_SQL="SELECT count(*) FROM position WHERE session_kind='LIVE' AND session_id=\$SID AND status IN ('OPEN','CLOSING')"
ODD_SQL="SELECT count(*) FROM position WHERE session_kind='LIVE' AND session_id=\$SID AND (status IS NULL OR status NOT IN ('OPEN','CLOSING','CLOSED'))"
step_stop() {  # 정지 1회 — 보유(OPEN/CLOSING)가 있으면 FORCED_STOP 시장가 매도가 함께 나간다(대기 종료와 구분해 기록)
  # 읽기 실패는 '미확인'으로 남기고 정지는 막지 않는다(정지를 못 보내는 쪽이 더 위험). 미확인은 대기 종료로 쓰지 않는다.
  q "${HELD_SQL//\$SID/$SID}" > "$M/held-before-stop.txt" || echo "UNKNOWN read-fail" > "$M/held-before-stop.txt"
  q "${ODD_SQL//\$SID/$SID}" > "$M/odd-status-before-stop.txt" || echo "UNKNOWN read-fail" > "$M/odd-status-before-stop.txt"
  h=$(cat "$M/held-before-stop.txt" 2>/dev/null); o=$(cat "$M/odd-status-before-stop.txt" 2>/dev/null)
  if [ "$h" = 0 ]; then echo "정지 직전 보유(OPEN/CLOSING) 0 → 대기 종료(청산할 것 없음)"
  elif [[ $h =~ ^[1-9][0-9]*$ ]]; then echo "정지 직전 보유(OPEN/CLOSING) $h → 강제 정지(FORCED_STOP 매도 동반, 자연 매도와 구분)"
  else echo "🔴 정지 직전 보유 미확인('$h') — 대기 종료/강제 정지 구분 불가, 정지는 보낸다"; fi
  echo "참고: CLOSED 양수 size 는 보유로 세지 않는다. 거래소 잔량은 이 표시 밖(A3·게이트·계좌 확인 별도). 알 수 없는/NULL 상태 $o 건"
  (set -C; date '+%F %T %Z' > "$M/sent-stop") || stop "표지 기록 실패 — 정지 보내지 않음"
  api POST /api/v1/trading/sessions/$SID/stop > "$M/stop.json" || stop "정지 HTTP/응답/기록 실패 — 상태 확인 전 재전송 금지"
  cat "$M/stop.json"
}
run_gate() {
  echo GATE-OUT-BEGIN
  local r=0
  $DC exec -T -e 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=15000 -c lock_timeout=2000' db psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -A -F '|' -P footer=off -v sid="$SID" -f - < "$M/gate.sql" || r=$?
  echo GATE-OUT-END; echo "GATE-RC=$r"; return "$r"
}
# ---- 함수 블록 끝
for k in APPROVED_DATE WIN_START WIN_END EXPECTED_SHA EXPECTED_IMAGE POOL_EXPECTED ALLOC_BEFORE RUN_TAG STEP KSTPY APIPY OPERATOR_READY EXTERNAL_FREEZE; do
  [ -n "${!k:-}" ] || stop "$k 비어 있음(원격 검사)"; done
case "$STEP" in p0|create) [ -z "$SID" ] || stop "생성 전 단계인데 SID 있음";; *) [[ "$SID" =~ ^[0-9]+$ ]] || stop "SID 없음";; esac
case "$STEP" in create|start) [ "$OPERATOR_READY" = yes ] && [ "$EXTERNAL_FREEZE" = yes ] || stop "수신/대응·외부 수동거래/입출금 중지 확인 없음 — POST 금지";; esac
kres=$(kst_ok) || stop "시각 검사 실패(원격): ${kres:-출력 없음 — python3 부재/오류}"; echo "KST(서버) $kres"
cd ~/crypto_trader/AutoTrader || stop "작업 폴더 없음"
DC="docker compose -f docker-compose.prod.yml"
PSQL="$DC exec -T db psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader"
M=~/smoke-$RUN_TAG
if [ "$STEP" = p0 ]; then [ ! -e "$M" ] || stop "$M 이 이미 있다 — 다른 실행 흔적"; mkdir "$M" || stop "기록 폴더 생성 실패"; fi
[ -d "$M" ] || stop "기록 폴더 없음 — P0 부터"
case "$STEP" in create|start|stop) [ ! -e "$M/sent-$STEP" ] || stop "$STEP 이미 보냄(원격 표지)";; esac

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
  # A3 필요조건만 — /account/summary 는 원 단위 HALF_UP 반올림 표시값이다. 이 부등식 통과로 A3 충분을 확정하지 않는다(v1.249 §4).
  api GET /api/v1/account/summary > "$M/account.json"
  python3 - "$M/account.json" "$ALLOC_BEFORE" <<'PY' | tee "$M/a3.txt" || stop "A3 필요조건 미충족: 표시 주문 가능 KRW < 기존 배정 + 10,000 이거나 표시 묶인 KRW 있음 또는 조회 실패"
import json, sys
from decimal import Decimal as D
d = json.load(open(sys.argv[1]))["data"]; a = D(str(d["availableKrw"])); l = D(str(d["lockedKrw"])); need = D(sys.argv[2]) + D("10000")
print("표시(원 단위 반올림) 주문 가능", a, "· 묶인", l, "· 필요(기존 배정+10000)", need, "→ 필요조건", "통과" if a >= need and l == 0 else "미충족")
sys.exit(0 if a >= need and l == 0 else 1)
PY
  { echo "A3 미확정(이 단계로 닫지 않음, create 승인 근거 아님):"
    echo "- 표시 availableKrw·lockedKrw 는 원 단위 HALF_UP 반올림값 — 정확한 여유·묶인 금액0 미확정"
    echo "- A3 시점·배정 합·변화 근거, 계정 실제 수수료율(코드 0.0005 가정)·거래소 최소 주문액(코드 5,000 가정)·장부 밖 잔량"; } | tee "$M/a3-unresolved.txt"
  echo "✅ P0 사전 조건 통과(A3 는 필요조건만 — 충분 판정 아님)";;
create) step_create;;
verify)  # 응답·저장 대조 — 다르면 start 하지 않는다
  python3 - "$M/create.json" "$SID" <<'PY' || stop "생성 응답 대조 실패"
import json, sys
from decimal import Decimal as D
d = json.load(open(sys.argv[1]))["data"]; p = d.get("strategyParams") or {}
ok = (str(d["id"]) == sys.argv[2] and d["status"] == "CREATED" and d.get("sessionType") == "REAL"
      and d.get("strategyType") == "DAILY_SMA20" and d.get("coinPair") == "KRW-BTC" and d.get("timeframe") == "H1"
      and D(str(d.get("initialCapital"))) == D("10000") and D(str(d.get("investRatio"))) == D("0.995")
      and D(str(d.get("maxInvestment"))) == D("9950") and D(str(d.get("stopLossPct"))) == D("5")
      and d.get("maxHoldHours") == 0 and d.get("startedAt") is None
      and type(p.get("maxEntries")) is int and p["maxEntries"] == 1)
print("응답", d["id"], d["status"], d.get("sessionType"), p); sys.exit(0 if ok else 1)
PY
  q "SELECT status, session_type, jsonb_typeof(strategy_params->'maxEntries'), strategy_params->>'maxEntries', initial_capital=10000, invest_ratio=0.995, max_investment=9950, started_at IS NULL, strategy_type='DAILY_SMA20', coin_pair='KRW-BTC', timeframe='H1', stop_loss_pct=5, max_hold_hours=0 FROM live_trading_session WHERE id=$SID" | tee "$M/verify.txt"
  [ "$(cat "$M/verify.txt")" = "CREATED|REAL|number|1|t|t|t|t|t|t|t|t|t" ] || stop "DB 저장값 불일치";;
start) step_start;;
watch)  # 관측 읽기만 — 신호를 만들지 않는다
  q "SELECT id, side, state, exchange_order_id IS NOT NULL, applied_executed_funds, applied_paid_fee, fill_accounting_pending, sell_applied_at IS NOT NULL, failure_code, created_at FROM \"order\" WHERE session_kind='LIVE' AND session_id=$SID ORDER BY id" | tee -a "$M/watch.txt"
  q "SELECT id, status, size, exit_reason, closed_at FROM position WHERE session_kind='LIVE' AND session_id=$SID ORDER BY id" | tee -a "$M/watch.txt"
  q "SELECT status, available_krw FROM live_trading_session WHERE id=$SID" | tee -a "$M/watch.txt"
  $DC logs --since 2h backend > "$M/watch-app.log" 2>&1 || stop "관측 로그 조회 실패"
  grep -E "ENTRY_LIMIT_REACHED|sessionId=$SID[,)]" "$M/watch-app.log" > "$M/watch-matching.txt" && wrc=0 || wrc=$?
  [ "$wrc" = 0 ] || [ "$wrc" = 1 ] || stop "관측 로그 검색 실패"
  tail -20 "$M/watch-matching.txt" | tee -a "$M/watch.txt";;
stop) step_stop;;
esac
