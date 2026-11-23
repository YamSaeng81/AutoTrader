#!/usr/bin/env bash
# A1 소유 세션 없는 행 시각 집계 1회 실행기 a1-read (총괄 v1.224 §3-2). **미실행 초안 — 별도 승인 전 실행 금지.**
# v2 초안(v1.225 §3): 15e3e412 에서 설치 상태 표준오류 정지만 추가. 실행기 최종 검토·운영 허가는 별도 판단.
# 개발 PC 에서 실행한다. 서버에는 아무것도 쓰지 않는다(SSH 표준 출력만 로컬로 받는다). 원격 호출은 정확히 2회:
#   (1) 서버 UTC·HEAD·backend 이미지/상태/재시작 읽기  (2) a1-time-1.sql 1회(READ ONLY·ROLLBACK, PGOPTIONS 15s/2s, SQL 첫 DO 블록이 보호값 재확인)
#   bash a1-read.sh <보존폴더 경로(이름 a1-read-YYYYMMDD-HHMM, 없어야 함)> <NOT_AFTER: 승인 창 끝 UTC 예 2026-11-24T01:20:00Z>
# 기록·종료값 규칙은 pm-read-2(fa767e7d)의 검토된 계약을 따른다:
#   미완료 표지 INCOMPLETE.txt(맨 처음) → 각 원격 출력/표준오류/rc → run-time → 해시 목록 → read-status.txt(완료 줄, 맨 마지막).
#   모든 기록이 성공한 뒤에만 표지를 지운다. 기록 실패 → 종료 4(원래 실패값이 비영이면 그 값), 표지 남김, 자동 재시도 없음.
# 종료값: 0 READ_OK · 2 시작 전 거절(원격 0회) · 3 STOP_PIN(HEAD·이미지·상태·재시작·Flyway 불일치/조회 불명)
#         · 5 STOP_INSTALL_STDERR / STOP_SQL_STDERR(rc0 인데 표준오류 있음 — 설치 상태는 SQL 호출 전에 멈춤, v1.225 §3) · 7 STOP_SQL_SHAPE(빈 출력·META/END/CNT 형태·보호값 표지 불일치)
#         · 원격/SQL 실패는 그 rc 그대로(예 255 SSH, 3 psql ON_ERROR_STOP — A1_GUARD 보호값 불일치 포함) · 4 기록 실패
#   상태 이름(read-status.txt 첫 낱말)으로 같은 숫자의 원인을 구분한다(예 STOP_PIN_MISMATCH 3 과 STOP_SQL_FAILED 3).
# 1회 범위: 보존폴더는 없어야 하고, 같은 docs/evidence 아래 다른 a1-read-* 폴더가 있으면 거절한다(같은 승인으로 두 번 읽지 않음).
# 결과 해석: 개수·시각뿐이다. 정산 확정·소유 예외·시간 면제·등록표 변경의 근거로 자동 소비하지 않는다(변환기 없음, 보고만).
set -u
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
A1="$(dirname "$SELF_DIR")"; EVI="$(dirname "$A1")"
SQL="$A1/sql/a1-time-1.sql"; SQL_SHA=b84a89e7fec21a95dd80ba8f9491b39a7e65697e3ea05b12ae21a8de49feefcf
EXP_HEAD=0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d
EXP_IMG=sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f
EXP_STATUS=running; EXP_RESTART=0; EXP_FLYWAY=96
PGO="-c statement_timeout=15s -c lock_timeout=2s"
AP="cd ~/crypto_trader/AutoTrader && docker compose -f docker-compose.prod.yml"
deny() { echo "🔴 거절(종료 2): $* — 원격 호출 0회"; exit 2; }
hashok() { [ "$(sha256sum < "$1" | cut -d' ' -f1)" = "$2" ]; }
utc() { date -u -d "$1" +%s 2>/dev/null; }
hashok "$SQL" "$SQL_SHA" || deny "고정 SQL 해시 불일치(a1-time-1.sql)"
L=${1:-}; NA=${2:-}
[ -n "$L" ] || deny "보존폴더 인자 없음"
case "$(basename "$L")" in a1-read-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9]) ;; *) deny "보존폴더 이름 형식(a1-read-YYYYMMDD-HHMM)";; esac
[ "$(cd "$(dirname "$L")" 2>/dev/null && pwd)" = "$EVI" ] || deny "보존폴더는 $EVI 바로 아래여야 함"
[ ! -e "$L" ] || deny "보존폴더가 이미 있음 — 같은 승인으로 다시 읽지 않는다"
[ -z "$(find "$EVI" -maxdepth 1 -name 'a1-read-*' -print -quit)" ] || deny "다른 a1-read-* 폴더가 이미 있음 — 1회 범위 초과"
[[ "$NA" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] && [ -n "$(utc "$NA")" ] || deny "NOT_AFTER 형식(UTC …Z)"
[ "$(date -u +%s)" -le "$(utc "$NA")" ] || deny "승인 창 끝($NA) 지남(PC 시각 기준)"
I="$L/INCOMPLETE.txt"; WR=0
rec() { printf '%s\n' "$2" | tee -a "$1" > /dev/null; }
remote() {
  ssh -o BatchMode=yes cat-prod "$4" < "$3" 2> "$2" | tee "$1" > /dev/null
  local ps=("${PIPESTATUS[@]}"); RRC=${ps[0]}
  { [ "${ps[1]}" = 0 ] && [ -f "$2" ]; } || WR=1
  rec "$L/rc.txt" "$(basename "$1") rc=$RRC" || WR=1
}
finish() {
  local st=$1 code=$2
  rec "$L/run-time.txt" "pc-end $(date -u +%FT%TZ)" || WR=1
  (cd "$L" && find . -type f ! -name read-SHA256SUMS ! -name read-status.txt ! -name INCOMPLETE.txt -print \
     | sed 's|^\./||' | sort | xargs -r sha256sum > read-SHA256SUMS) || WR=1
  [ "$WR" = 0 ] && rec "$L/read-status.txt" "$st exit=$code" || WR=1
  [ "$WR" = 0 ] && rm -f "$I" && [ ! -e "$I" ] || WR=1
  if [ "$WR" != 0 ]; then
    local fr=4; [ "$code" = 0 ] || fr=$code
    echo "🔴 기록 실패 — 원래 결과 $st(종료 $code) · 이 실행 종료=$fr. 미완료 표지를 남겼다: 보고 전 재실행 금지(자동 재시도 없음)"
    exit "$fr"
  fi
  echo "a1-read 끝 — $st (종료 $code). 같은 승인으로 다시 읽지 않는다."
  exit "$code"
}

mkdir "$L" || { echo "🔴 기록 실패(종료 4): 보존폴더 생성 실패 — 원격 호출 0회"; exit 4; }
rec "$I" "미완료 — a1-read 시작 $(date -u +%FT%TZ)(PC) NOT_AFTER=$NA" || { rm -rf "$L"; echo "🔴 기록 실패(종료 4): 미완료 표지 — 원격 호출 0회"; exit 4; }
{ cp "$SQL" "$L/a1-time-1.sql" && rec "$L/run-time.txt" "pc-start $(date -u +%FT%TZ)" && rec "$L/range-args.txt" "NOT_AFTER $NA"; } \
  || { echo "🔴 기록 실패(종료 4): 시작 기록 — 원격 호출 0회, 미완료 표지 남김"; exit 4; }
# 1) 서버 UTC + 설치 상태(읽기만)
remote "$L/install_state.txt" "$L/install_state.stderr" /dev/null \
  "echo \"server_utc \$(date -u +%FT%TZ)\"; cd ~/crypto_trader/AutoTrader && echo \"HEAD \$(git rev-parse HEAD)\" && c=\$(docker compose -f docker-compose.prod.yml ps -q backend) && docker inspect --format 'backend Image={{.Image}} Status={{.State.Status}} RestartCount={{.RestartCount}} StartedAt={{.State.StartedAt}}' \$c"
[ "$RRC" = 0 ] || finish STOP_REMOTE_INSTALL_STATE "$RRC"
[ "$WR" = 0 ] || finish RECORD_FAILED_INSTALL_STATE 0
[ ! -s "$L/install_state.stderr" ] || finish STOP_INSTALL_STDERR 5
F="$L/install_state.txt"
SU=$(grep -cE '^server_utc [0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' "$F"); HDn=$(grep -cE '^HEAD [0-9a-f]{40}$' "$F")
BLn=$(grep -cE '^backend Image=[^ ]+ Status=[^ ]+ RestartCount=[0-9]+ StartedAt=[^ ]+$' "$F")
[ "$SU" = 1 ] && [ "$HDn" = 1 ] && [ "$BLn" = 1 ] || finish STOP_PIN_UNREADABLE 3
HD=$(sed -n 's/^HEAD //p' "$F"); BL=$(grep '^backend ' "$F")
IMG=${BL#*Image=}; IMG=${IMG%% *}; STS=${BL#*Status=}; STS=${STS%% *}; RST=${BL#*RestartCount=}; RST=${RST%% *}
rec "$L/pins.txt" "HEAD $([ "$HD" = "$EXP_HEAD" ] && echo 일치 || echo 불일치) · 이미지 $([ "$IMG" = "$EXP_IMG" ] && echo 일치 || echo 불일치) · 상태 $STS · 재시작 $RST" || WR=1
[ "$HD" = "$EXP_HEAD" ] && [ "$IMG" = "$EXP_IMG" ] && [ "$STS" = "$EXP_STATUS" ] && [ "$RST" = "$EXP_RESTART" ] || finish STOP_PIN_MISMATCH 3
[ "$WR" = 0 ] || finish RECORD_FAILED_PINS 0
# 2) 집계 SQL 1회(표준 입력 -f -, PGOPTIONS 보호값)
remote "$L/a1.tsv" "$L/a1.stderr" "$L/a1-time-1.sql" "$AP exec -T -e PGOPTIONS='$PGO' db psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f -"
SRC_=$RRC; rec "$L/a1.rc" "$SRC_" || WR=1
[ "$SRC_" = 0 ] || finish STOP_SQL_FAILED "$SRC_"
[ "$WR" = 0 ] || finish RECORD_FAILED_SQL 0
[ ! -s "$L/a1.stderr" ] || finish STOP_SQL_STDERR 5
T="$L/a1.tsv"
[ -s "$T" ] || finish STOP_SQL_SHAPE 7
META=$(head -n1 "$T" | awk -F'\t' '$1=="META" && $2=="a1-time-1" && NF==9 && $6=="repeatable read" && $8=="15s" && $9=="2s" {print $5}')
[ "$(tail -n1 "$T" | awk -F'\t' '$1=="END" && NF==2 {print "ok"}')" = ok ] && [ "$(grep -c '^CNT	' "$T")" = 1 ] && [ -n "$META" ] \
  && [ -z "$(grep -vE '^(META|REF|SESS|OT|PT|OWN|CNT|END)	' "$T")" ] || finish STOP_SQL_SHAPE 7
rec "$L/pins.txt" "Flyway $META" || WR=1
[ "$META" = "$EXP_FLYWAY" ] || finish STOP_PIN_MISMATCH 3
# 행 수 대조(같은 스냅숏): OT 합 = CNT 주문, PT 합 = CNT 포지션. 다르면 형태 불일치로 멈춘다(보고는 그대로)
OS=$(awk -F'\t' '$1=="OT"{s+=$4} END{print s+0}' "$T"); PS=$(awk -F'\t' '$1=="PT"{s+=$4} END{print s+0}' "$T")
CO=$(awk -F'\t' '$1=="CNT"{print $2}' "$T"); CP=$(awk -F'\t' '$1=="CNT"{print $3}' "$T")
rec "$L/sum-check.txt" "OT합 $OS / CNT주문 $CO · PT합 $PS / CNT포지션 $CP" || WR=1
[ "$OS" = "$CO" ] && [ "$PS" = "$CP" ] || finish STOP_SQL_SHAPE 7
[ "$WR" = 0 ] || finish RECORD_FAILED_SUM 0
finish READ_OK 0
