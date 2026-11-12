#!/usr/bin/env bash
# 총괄 v1.153 H / v1.154 §4 — 구판(b5cd1d0) 복구 전 관문 2판 (2026-11-12). 초안: 운영 실행은 승인 뒤에만. 자동 되돌림 없음 — 판정만 한다.
# 1판(../rollback_gate.sh, 원문 보존)과 판정 조건은 같다. 바뀐 것은 장부 기록뿐이다:
#   1판은 체크섬 생성이 실패해도(빈 SHA256SUMS) POST_CREATION_OK·종료 0 으로 끝났다(총괄 재현 gate-result.json).
#   2판은 장부 파일·gate-line·체크섬 생성·체크섬 재검증·완료 표지 중 하나라도 실패하면 LEDGER_REFUSED·종료 4 로 끝내고
#   복구 가능 문구를 내지 않는다. 불완전 사본은 지우지 않고 LEDGER_FAILED 표지와 함께 실패로 남긴다.
#
# 사용: PSQL='docker exec -i <db컨테이너> psql -U <사용자> -d <db> -At' ./rollback_gate.sh <장부출력폴더(없는 경로)>
#
# 판정(종료 코드):
#   0 PRE_CREATION_OK   새 모드 세션 0 — 구판 복구는 종전 D4 U되돌림 그대로.
#   0 POST_CREATION_OK  새 모드 세션이 있으나 RUNNING 0·OPEN 0·CLOSING 0·이상 상태 0, 그리고 마지막 장부가 완결(재검증 통과·완료 표지)됨.
#   2 HOLD_PENDING      청산 대기(CLOSING)가 남음 — 구판 복구 보류. 새 바이너리를 유지해 첫 유효 시세 정산을 기다린다.
#   3 STOP_RUNNING      새 모드 RUNNING 이 있음 — 먼저 새 바이너리에서 그 세션들만 정지(세션별 정지).
#   3 STOP_MISMATCH     정지된 새 모드 세션에 OPEN 포지션 — 계약 밖 상태. 사람 판정 전 아무것도 바꾸지 않는다.
#   3 STOP_UNKNOWN      이상 상태(알 수 없는 상태값·판단 시각 없는 CLOSING·V96 열 없음인데 새 모드 존재·집계 실패).
#   4 LEDGER_REFUSED    장부 출력 경로가 이미 있음(덮어쓰지 않음), 또는 장부 기록의 어느 단계든 실패 — 구판 복구 불가.
set -u
OUT="${1:?장부 출력 폴더를 인자로 준다(없는 경로)}"
HERE="$(cd "$(dirname "$0")" && pwd)"
: "${PSQL:?PSQL 명령(표준 입력으로 SQL)을 환경 변수로 준다}"

RAW="$($PSQL < "$HERE/gate.sql" 2>&1)"; RC=$?
echo "$RAW"
if [ $RC -ne 0 ]; then echo "VERDICT STOP_UNKNOWN (집계 실패 rc=$RC)"; exit 3; fi
LINE="$(printf '%s\n' "$RAW" | grep '^GATE|' | tail -1)"
DEC="$(printf '%s\n' "$RAW" | grep '^DECIDED|' | tail -1)"
[ -n "$LINE" ] || { echo "VERDICT STOP_UNKNOWN (GATE 줄 없음)"; exit 3; }
get() { printf '%s\n' "$LINE" | tr '|' '\n' | sed -n "s/^$1=//p"; }
V96=$(get v96); CREATED=$(get created); RUNNING=$(get running); ODDS=$(get odd_session)
OPEN=$(get open); CLOSING=$(get closing); ODDP=$(get odd_position); SWO=$(get stopped_with_open)
NODEC="$(printf '%s\n' "$DEC" | sed -n 's/^DECIDED|closing_without_decided_at=//p')"
for v in "$V96" "$CREATED" "$RUNNING" "$ODDS" "$OPEN" "$CLOSING" "$ODDP" "$SWO"; do
  case "$v" in ''|*[!0-9]*) echo "VERDICT STOP_UNKNOWN (집계 값 비정상: $LINE)"; exit 3;; esac
done

if [ "$CREATED" -eq 0 ]; then echo "VERDICT PRE_CREATION_OK (새 모드 세션 0, v96=$V96)"; exit 0; fi
if [ "$V96" -ne 1 ]; then echo "VERDICT STOP_UNKNOWN (새 모드 세션 $CREATED 인데 V96 열 없음)"; exit 3; fi
if [ "$ODDS" -ne 0 ] || [ "$ODDP" -ne 0 ]; then echo "VERDICT STOP_UNKNOWN (이상 상태 세션 $ODDS·포지션 $ODDP)"; exit 3; fi
if [ "$NODEC" != "0" ]; then echo "VERDICT STOP_UNKNOWN (판단 시각 없는 CLOSING $NODEC)"; exit 3; fi
if [ "$RUNNING" -ne 0 ]; then echo "VERDICT STOP_RUNNING (새 모드 RUNNING $RUNNING — 새 바이너리에서 세션별 정지 먼저)"; exit 3; fi
if [ "$SWO" -ne 0 ] || [ "$OPEN" -ne 0 ]; then echo "VERDICT STOP_MISMATCH (정지된 새 모드 세션의 OPEN $OPEN)"; exit 3; fi
if [ "$CLOSING" -ne 0 ]; then echo "VERDICT HOLD_PENDING (청산 대기 $CLOSING — 구판 복구 보류, 새 바이너리 유지·첫 유효 시세 정산 대기)"; exit 2; fi

# 여기까지 왔으면 새 모드 RUNNING 0·OPEN 0·CLOSING 0 — 마지막 장부를 남긴다(읽기 전용 COPY, 덮어쓰기 없음).
# 장부의 어느 단계든 실패하면 refuse: 불완전 사본은 지우지 않고 LEDGER_FAILED 표지(가능하면)를 남기고 종료 4. 복구 가능 문구 없음.
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
  # 1판 2회차 예행: psql 명령 표지(BEGIN/ROLLBACK)가 CSV 첫·끝 줄에 섞였다(원문 보존) — QUIET 로 끈다.
  printf '\\set QUIET on\nBEGIN TRANSACTION READ ONLY;\nCOPY (%s) TO STDOUT WITH CSV HEADER;\nROLLBACK;\n' "$2" | $PSQL > "$OUT/$1" 2> "$OUT/$1.err" \
    || refuse "$1 쓰기 실패(psql rc≠0)"
  [ -s "$OUT/$1.err" ] && refuse "$1 오류 출력 있음($1.err 보존)"
  [ -s "$OUT/$1" ] || refuse "$1 비어 있음(머리 줄도 없음)"
  rm -f "$OUT/$1.err" || refuse "$1.err 정리 실패"
}
dump sessions.csv  "SELECT * FROM paper_trading.virtual_balance WHERE id IN $NM ORDER BY id"
dump positions.csv "SELECT * FROM paper_trading.position WHERE session_id IN $NM ORDER BY id"
dump orders.csv    "SELECT * FROM paper_trading.\"order\" WHERE session_id IN $NM ORDER BY id"
printf '%s\n' "$LINE" > "$OUT/gate-line.txt" || refuse "gate-line.txt 쓰기 실패"
[ -s "$OUT/gate-line.txt" ] || refuse "gate-line.txt 비어 있음"
FILES="sessions.csv positions.csv orders.csv gate-line.txt"
( cd "$OUT" && sha256sum $FILES > SHA256SUMS ) || refuse "체크섬 생성 실패"
[ "$(grep -c . "$OUT/SHA256SUMS" 2>/dev/null)" = "4" ] || refuse "체크섬 목록이 4줄이 아님"
for f in $FILES; do grep -q "[ *]$f\$" "$OUT/SHA256SUMS" || refuse "체크섬 목록에 $f 없음"; done
( cd "$OUT" && sha256sum -c SHA256SUMS > CHECK.txt 2>&1 ) || refuse "체크섬 재검증 불일치(CHECK.txt 보존)"
printf 'ledger complete %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" > "$OUT/LEDGER_COMPLETE" || refuse "완료 표지 쓰기 실패"
[ -s "$OUT/LEDGER_COMPLETE" ] || refuse "완료 표지 비어 있음"
echo "VERDICT POST_CREATION_OK (새 모드 $CREATED 세션 모두 정지·정산 — 마지막 장부 완결·재검증 통과 $OUT, 이후 종전 U되돌림)"
exit 0
