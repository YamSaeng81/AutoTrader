#!/usr/bin/env bash
# 총괄 v1.159 §5 — 구판(b5cd1d0) 복구 전 상태 기반 관문 3판 (2026-11-13). 초안: 운영 실행은 승인 뒤에만. 자동 되돌림 없음 — 판정만 한다.
# 2판(v153 h-rollback/v2, 원문 보존)과 다른 점:
#   - "새 모드 세션 0(생성 전)" 만으로 통과하지 않는다. 생성 전이든 후든 아래를 모두 요구한다.
#   - 실계좌 진행 상태: 미종결 주문·회계 반영 대기·반영값/보고값 없음·보고≠반영·정리 대기·미해결 정산 불일치·실계좌 CLOSING 이 0.
#   - 대조: RUNNING 실계좌 세션의 주문 순체결량 = 열린 포지션 수량, RUNNING LIVE 잔액 = 장부 기대. 실계좌 보유·RUNNING 이 있으면
#     거래소 잔액·수량 대조 근거 파일(EXCH_RECON, 승인된 읽기 블록의 출력)이 있어야 하며 그 해시를 장부에 남긴다.
#   - 장부(새 모드·실계좌 상태 CSV + 관문 줄 + 대조 근거 사본)를 덮어쓰기 없이 남기고, 체크섬 재검증·완료 표지까지 성공해야 OK.
#
# 사용: PSQL='docker exec -i <db컨테이너> psql -U <사용자> -d <db> -At' [EXCH_RECON=<대조 출력 파일>] ./rollback_gate.sh <장부출력폴더(없는 경로)>
#
# 판정(종료 코드):
#   0 ROLLBACK_GATE_OK  새 모드 RUNNING·OPEN·CLOSING 0, 실계좌 진행·미정산·정리 대기 0, 대조 일치, 장부 완결 — 이후 종전 U되돌림.
#   2 HOLD_PENDING      진행 중 상태가 남음(새 모드 CLOSING·실계좌 미종결 주문·회계 반영 대기·정리 대기·미해결 불일치·실계좌 CLOSING).
#                       구판 복구 보류 — 현재 바이너리를 유지해 정지·유효 가격 대기·정산/정리를 계속하고 보고한다.
#   3 STOP_RUNNING      새 모드 RUNNING — 새 바이너리에서 그 세션들만 세션별 정지 먼저.
#   3 STOP_MISMATCH     정지된 새 모드 세션의 OPEN, 수량·잔액 대조 불일치, 반영값 없음·보고≠반영.
#   3 STOP_UNKNOWN      이상 상태값·판단 시각 없는 CLOSING·V96 열 없음인데 새 모드 존재·집계 실패·대조 근거 없음.
#   4 LEDGER_REFUSED    장부 출력 경로가 이미 있음, 또는 장부 기록의 어느 단계든 실패 — 구판 복구 불가.
# 어느 판정이든 0원 정산·행 삭제·상태 강제 변경·마이그레이션 삭제로 조건을 만들지 않는다.
set -u
OUT="${1:?장부 출력 폴더를 인자로 준다(없는 경로)}"
HERE="$(cd "$(dirname "$0")" && pwd)"
: "${PSQL:?PSQL 명령(표준 입력으로 SQL)을 환경 변수로 준다}"
EXCH_RECON="${EXCH_RECON:-}"

RAW="$($PSQL < "$HERE/gate.sql" 2>&1)"; RC=$?
echo "$RAW"
if [ $RC -ne 0 ]; then echo "VERDICT STOP_UNKNOWN (집계 실패 rc=$RC)"; exit 3; fi
LINE="$(printf '%s\n' "$RAW" | grep '^GATE|' | tail -1)"
DEC="$(printf '%s\n' "$RAW" | grep '^DECIDED|' | tail -1)"
[ -n "$LINE" ] || { echo "VERDICT STOP_UNKNOWN (GATE 줄 없음)"; exit 3; }
get() { printf '%s\n' "$LINE" | tr '|' '\n' | sed -n "s/^$1=//p"; }
KEYS="v96 created running odd_session open closing odd_position stopped_with_open unresolved_orders acct_pending applied_missing reported_ne_applied cleanup discrepancy real_closing real_running real_held qty_mismatch balance_mismatch"
for k in $KEYS; do
  v="$(get "$k")"
  case "$v" in ''|*[!0-9]*) echo "VERDICT STOP_UNKNOWN (집계 값 비정상 $k='$v': $LINE)"; exit 3;; esac
  eval "G_$k=$v"
done
NODEC="$(printf '%s\n' "$DEC" | sed -n 's/^DECIDED|closing_without_decided_at=//p')"

# ── 새 모드(LIVE_ALIGNED) — 2판과 같은 조건, 단 생성 전 통과 지름길 없음 ──
if [ "$G_created" -ne 0 ]; then
  [ "$G_v96" -eq 1 ] || { echo "VERDICT STOP_UNKNOWN (새 모드 세션 $G_created 인데 V96 열 없음)"; exit 3; }
  if [ "$G_odd_session" -ne 0 ] || [ "$G_odd_position" -ne 0 ]; then echo "VERDICT STOP_UNKNOWN (이상 상태 세션 $G_odd_session·포지션 $G_odd_position)"; exit 3; fi
  [ "$NODEC" = "0" ] || { echo "VERDICT STOP_UNKNOWN (판단 시각 없는 CLOSING $NODEC)"; exit 3; }
  [ "$G_running" -eq 0 ] || { echo "VERDICT STOP_RUNNING (새 모드 RUNNING $G_running — 새 바이너리에서 세션별 정지 먼저)"; exit 3; }
  if [ "$G_stopped_with_open" -ne 0 ] || [ "$G_open" -ne 0 ]; then echo "VERDICT STOP_MISMATCH (정지된 새 모드 세션의 OPEN $G_open)"; exit 3; fi
fi
# ── 실계좌 대조 불일치는 진행 대기보다 먼저 멈춘다(기다려도 맞춰지지 않는 상태) ──
if [ "$G_applied_missing" -ne 0 ] || [ "$G_reported_ne_applied" -ne 0 ]; then
  echo "VERDICT STOP_MISMATCH (체결 주문 반영값·보고값 없음 $G_applied_missing·보고≠반영 $G_reported_ne_applied)"; exit 3; fi
# ── 진행 중 상태 — 현재 바이너리 유지 ──
PEND="새모드CLOSING=$G_closing 미종결주문=$G_unresolved_orders 회계반영대기=$G_acct_pending 정리대기=$G_cleanup 미해결불일치=$G_discrepancy 실계좌CLOSING=$G_real_closing"
if [ "$G_closing" -ne 0 ] || [ "$G_unresolved_orders" -ne 0 ] || [ "$G_acct_pending" -ne 0 ] || [ "$G_cleanup" -ne 0 ] \
   || [ "$G_discrepancy" -ne 0 ] || [ "$G_real_closing" -ne 0 ]; then
  echo "VERDICT HOLD_PENDING ($PEND — 구판 복구 보류, 현재 바이너리로 정지·유효 가격 대기·정산/정리 계속, 보고)"; exit 2; fi
if [ "$G_qty_mismatch" -ne 0 ] || [ "$G_balance_mismatch" -ne 0 ]; then
  echo "VERDICT STOP_MISMATCH (RUNNING 실계좌 수량 불일치 $G_qty_mismatch·LIVE 잔액 불일치 $G_balance_mismatch)"; exit 3; fi
if [ "$G_real_running" -ne 0 ] || [ "$G_real_held" -ne 0 ]; then
  if [ -z "$EXCH_RECON" ] || [ ! -s "$EXCH_RECON" ]; then
    echo "VERDICT STOP_UNKNOWN (실계좌 RUNNING $G_real_running·보유 $G_real_held 인데 거래소 잔액·수량 대조 근거(EXCH_RECON) 없음)"; exit 3; fi
fi

# ── 장부: 덮어쓰기 없이, 어느 단계든 실패하면 LEDGER_REFUSED ──
refuse() {
  printf '%s\n' "$1" > "$OUT/LEDGER_FAILED" 2>/dev/null
  echo "VERDICT LEDGER_REFUSED ($1 — 장부 미완결, 구판 복구 불가)"
  exit 4
}
if [ -e "$OUT" ]; then echo "VERDICT LEDGER_REFUSED (이미 있음: $OUT — 덮어쓰지 않음, 구판 복구 불가)"; exit 4; fi
PARENT="$(dirname "$OUT")"
[ -d "$PARENT" ] || { echo "VERDICT LEDGER_REFUSED (상위 폴더 없음: $PARENT — 구판 복구 불가)"; exit 4; }
mkdir "$OUT" 2>/dev/null || { echo "VERDICT LEDGER_REFUSED (폴더 생성 실패: $OUT — 구판 복구 불가)"; exit 4; }
NM="(SELECT id FROM paper_trading.virtual_balance WHERE strategy_params ->> 'paperExecutionMode' = 'LIVE_ALIGNED')"
dump() {   # $1 파일 이름, $2 SELECT
  printf '\\set QUIET on\nBEGIN TRANSACTION READ ONLY;\nCOPY (%s) TO STDOUT WITH CSV HEADER;\nROLLBACK;\n' "$2" | $PSQL > "$OUT/$1" 2> "$OUT/$1.err" \
    || refuse "$1 쓰기 실패(psql rc≠0)"
  [ -s "$OUT/$1.err" ] && refuse "$1 오류 출력 있음($1.err 보존)"
  [ -s "$OUT/$1" ] || refuse "$1 비어 있음(머리 줄도 없음)"
  rm -f "$OUT/$1.err" || refuse "$1.err 정리 실패"
}
dump sessions.csv       "SELECT * FROM paper_trading.virtual_balance WHERE id IN $NM ORDER BY id"
dump positions.csv      "SELECT * FROM paper_trading.position WHERE session_id IN $NM ORDER BY id"
dump orders.csv         "SELECT * FROM paper_trading.\"order\" WHERE session_id IN $NM ORDER BY id"
dump real-sessions.csv  "SELECT 'LIVE' AS kind, id, status, initial_capital, available_krw, total_asset_krw FROM live_trading_session WHERE status = 'RUNNING' UNION ALL SELECT 'DYNAMIC', id, status, initial_capital, available_krw, total_asset_krw FROM dynamic_session WHERE status = 'RUNNING' AND trading_mode <> 'PAPER' ORDER BY 1, 2"
dump real-positions.csv "SELECT * FROM position WHERE status IN ('OPEN', 'CLOSING') AND session_kind IN ('LIVE', 'DYNAMIC') ORDER BY id"
printf '%s\n%s\n' "$LINE" "$DEC" > "$OUT/gate-line.txt" || refuse "gate-line.txt 쓰기 실패"
[ -s "$OUT/gate-line.txt" ] || refuse "gate-line.txt 비어 있음"
FILES="sessions.csv positions.csv orders.csv real-sessions.csv real-positions.csv gate-line.txt"
if [ -n "$EXCH_RECON" ]; then
  cp "$EXCH_RECON" "$OUT/exch-recon.txt" || refuse "거래소 대조 근거 복사 실패"
  [ -s "$OUT/exch-recon.txt" ] || refuse "거래소 대조 근거 사본 비어 있음"
  FILES="$FILES exch-recon.txt"
fi
N=$(printf '%s\n' $FILES | grep -c .)
( cd "$OUT" && sha256sum $FILES > SHA256SUMS ) || refuse "체크섬 생성 실패"
[ "$(grep -c . "$OUT/SHA256SUMS" 2>/dev/null)" = "$N" ] || refuse "체크섬 목록이 ${N}줄이 아님"
for f in $FILES; do grep -q "[ *]$f\$" "$OUT/SHA256SUMS" || refuse "체크섬 목록에 $f 없음"; done
( cd "$OUT" && sha256sum -c SHA256SUMS > CHECK.txt 2>&1 ) || refuse "체크섬 재검증 불일치(CHECK.txt 보존)"
printf 'ledger complete %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" > "$OUT/LEDGER_COMPLETE" || refuse "완료 표지 쓰기 실패"
[ -s "$OUT/LEDGER_COMPLETE" ] || refuse "완료 표지 비어 있음"
echo "VERDICT ROLLBACK_GATE_OK (새 모드 $G_created 세션 모두 정지·정산, 실계좌 진행·미정산·정리 대기 0, 대조 일치, 장부 완결·재검증 통과 $OUT — 이후 종전 U되돌림)"
exit 0
