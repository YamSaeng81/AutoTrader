#!/usr/bin/env bash
# P·G·O·F04·F05·S: 함수 블록만 잘라 부작용 없는 대역으로 검증한다. launch.sh/smoke.sh 전체·ssh·curl·docker·PG 는 실행하지 않는다.
# 사용: t_shell.sh smoke   (서버 쪽 — WSL bash/python3 에서)
#       t_shell.sh launch  (로컬 쪽 — Git Bash/python 에서)
set -u
T="$(cd "$(dirname "$0")" && pwd)"; V="$T/.."; EV="$V/.."; R3="$EV/one-entry-pg2-20261125/runs/r3/gate"; R1="$EV/one-entry-pg2-20261125/runs/r1/gate"
W=$(mktemp -d); ok=0; bad=0
rep() { if [ "$2" = 1 ]; then ok=$((ok+1)); echo "ok $1 $3"; else bad=$((bad+1)); echo "NG $1 $3"; fi; }
blk() { sed -n '/^# ---- 함수 블록 시작/,/^# ---- 함수 블록 끝/p' "$1"; }
# 실제 ssh/curl/docker 로 빠지지 않게: 이 시험 PATH 앞에 '부르면 실패 기록' 대역을 둔다(Unix 경로만)
mkdir -p "$W/bin"; for c in ssh curl docker psql; do printf '#!/bin/sh\necho "%s $*" >> "%s/forbidden"\nexit 99\n' "$c" "$W" > "$W/bin/$c"; chmod +x "$W/bin/$c"; done
export PATH="$W/bin:$PATH"; [ "$(command -v ssh)" = "$W/bin/ssh" ] || { echo "🔴 ssh 대역 경로 아님 — 중단"; exit 9; }
MODE=${1:-}
case "$MODE" in
smoke)
  blk "$V/smoke.sh" > "$W/fn.sh"; KSTPY=$(cat "$V/kst.py")
  TODAY=$(python3 -I -c "$KSTPY" window watch 2000-01-01 0000 2359 | cut -d' ' -f2)
  run() {  # $1 사례 $2 단계함수 $3 kst 모드(real|seq|pass) $4 날짜 $5 시작 $6 끝
    local c=$1 f=$2 mode=$3 M="$W/$1"; mkdir -p "$M"; : > "$M/api"
    ( . "$W/fn.sh"; STEP=${f#step_}; APPROVED_DATE=$4 WIN_START=$5 WIN_END=$6 SID=7; n=0
      api() { echo "$*" >> "$M/api"; echo '{"success":true,"data":{"id":7,"status":"CREATED"}}'; }
      q() { echo "RUNNING|t"; }
      case $mode in seq) kst_ok() { n=$((n+1)); [ $n -le 1 ] && echo "통과(모형)" || { echo "🔴 창 만료(모형)"; return 3; }; }; kst_ok > /dev/null;;
                    pass) kst_ok() { echo "통과(모형)"; };; esac
      $f ) > "$M/stdout" 2>&1; local rc=$?
    echo "$rc $(wc -l < "$M/api" | tr -d ' ') $( [ -e "$M/sent-${f#step_}" ] && echo 표지있음 || echo 표지없음)"
  }
  r=$(run P01 step_create real 2000-01-01 1000 1030); rep P01 "$([ "$r" = "1 0 표지없음" ] && echo 1)" "$r"
  r=$(run P02 step_start real 2000-01-01 1000 1030);  rep P02 "$([ "$r" = "1 0 표지없음" ] && echo 1)" "$r"
  r=$(run P03 step_create seq "$TODAY" 0000 2359);    rep P03 "$([ "$r" = "1 0 표지없음" ] && echo 1)" "$r"
  r=$(run P04 step_start seq "$TODAY" 0000 2359);     rep P04 "$([ "$r" = "1 0 표지없음" ] && echo 1)" "$r"
  r=$(run P05 step_create pass "$TODAY" 0000 2359);   rep P05 "$([ "$r" = "0 1 표지있음" ] && echo 1)" "$r"
  r=$(run P06 step_start pass "$TODAY" 0000 2359);    rep P06 "$([ "$r" = "0 1 표지있음" ] && echo 1)" "$r"
  r=$(run P07 step_create real "$TODAY" 0000 2359);   rep P07 "$([ "$r" = "0 1 표지있음" ] && echo 1)" "$r (오늘 $TODAY)"
  ( . "$W/fn.sh"; KSTPY='import sys; raise SystemExit(' STEP=create APPROVED_DATE=$TODAY WIN_START=0000 WIN_END=2359; kst_ok ) > /dev/null 2>&1; f=$?
  rep F05 "$([ $f != 0 ] && echo 1)" "깨진 KSTPY rc=$f";;
launch)
  blk "$V/launch.sh" > "$W/fn.sh"
  PYOK=$(command -v python3 || command -v python); here=$V
  TODAY=$("$PYOK" -I "$V/kst.py" window watch 2000-01-01 0000 2359 | cut -d' ' -f2)
  ( . "$W/fn.sh"; PY=$PYOK STEP=create APPROVED_DATE=2000-01-01 WIN_START=1000 WIN_END=1030; kst_win ) > /dev/null 2>&1; a=$?
  ( . "$W/fn.sh"; PY=$PYOK STEP=create APPROVED_DATE=$TODAY WIN_START=0000 WIN_END=2359; kst_win ) > /dev/null 2>&1; b=$?
  rep P08 "$([ $a != 0 ] && [ $b = 0 ] && echo 1)" "지난 날짜 rc=$a · 오늘 0000-2359 rc=$b"
  ( . "$W/fn.sh"; PY=/nonexistent/python3 STEP=create APPROVED_DATE=$TODAY WIN_START=0000 WIN_END=2359; kst_win ) > /dev/null 2>&1; f=$?
  rep F04 "$([ $f != 0 ] && echo 1)" "python 없음 rc=$f"
  g() {  # $1 사례 $2 입력(.out 없으면 '-') $3 GATE-RC 줄(없으면 '-') $4 원격 rc $5 sid $6 변형(none|regdir|nopy|finaldir) → 최종 rc
    local L="$W/$1"; mkdir -p "$L"
    { [ "$2" = conn ] && echo "Connection to cat-prod closed by remote host." || { echo GATE-OUT-BEGIN; cat "$2"; echo GATE-OUT-END; }
      [ "$3" = - ] || echo "GATE-RC=$3"; echo "rc=$4"; } > "$L/gate.out"
    case $6 in regdir) mkdir "$L/registration.txt";; finaldir) mkdir "$L/gate-final.txt";; esac
    ( . "$W/fn.sh"; PY=$PYOK; [ "$6" = nopy ] && PY=/nonexistent/python3; GATE_PY="$EV/one-entry-gate-v4-20261125/gate_check.py"; SID=$5
      gate_finalize "$L" "$4" ) > "$L/stdout" 2>&1; echo $?
  }
  sid() { sed -n 's/^sid=//p' "$1"; }
  P=$R3/c_closed_size_sold; H=$R1/g_discrepancy_open; F=$R3/c_open_size0
  head -2 "$P.out" | tail -1 | cut -d'|' -f1-3 > "$W/partial.out"   # 부분 출력(머리줄 없음)
  # 경로에 공백이 있어 단어 분리 없이 배열로 넘긴다(r1 시험기 결함 수정)
  gc() { local c=$1 exp=$8 r; r=$(g "$2" "$3" "$4" "$5" "$6" "$7"); rep "$c" "$([ "$r" = "$exp" ] && echo 1)" "최종 rc=$r 기대 $exp · $(tr '
' ' ' < "$W/$2/gate-final.txt" 2>/dev/null)"; }
  gc G01 G01 "$P.out" 0 0 "$(sid "$P.reg")" none 0
  gc G02 G02 "$H.out" 0 0 "$(sid "$H.reg")" none 2
  gc G03 G03 "$F.out" 0 0 "$(sid "$F.reg")" none 1
  gc G04 G04 conn - 255 1 none 255
  gc G05 G05 "$W/partial.out" 1 1 1 none 1
  gc G06 G06 "$P.out" 0 0 "$(sid "$P.reg")" regdir 4
  gc G07 G07 "$P.out" 0 0 "$(sid "$P.reg")" nopy 6
  gc G08 G08 "$P.out" - 0 "$(sid "$P.reg")" none 1
  gc G09 G09 "$P.out" 0 0 "$(sid "$P.reg")" finaldir 4
  # O: 원래 초안 종료 처리 38~44행만(실행기 전체 아님). here=원래 폴더 → 원래 v4 판정기 경로
  sed -n 38,44p "$V/orig/launch.sh" > "$W/orig-tail.sh"; cp "$W/orig-tail.sh" "$T/out/orig-launch-38-44.sh"
  o() { local L="$W/$1"; mkdir -p "$L"; { echo GATE-OUT-BEGIN; cat "$2"; echo GATE-OUT-END; echo "GATE-RC=0"; echo "rc=0"; } > "$L/gate.out"
    ( rc=0 STEP=gate SID=$3 here="$EV/live-smoke-draft-20261125"; . "$W/orig-tail.sh" ) > "$L/stdout" 2>&1; echo $?; }
  r=$(o O01 "$F.out" "$(sid "$F.reg")"); rep O01 "$([ "$r" = 0 ] && grep -q 'VERDICT FAIL' "$W/O01/stdout" && echo 1)" "원래 초안: 판정 $(head -1 "$W/O01/stdout") → 최종 rc=$r (반례 재현)"
  r=$(o O02 "$H.out" "$(sid "$H.reg")"); rep O02 "$([ "$r" = 0 ] && grep -q 'VERDICT HOLD' "$W/O02/stdout" && echo 1)" "원래 초안: 판정 $(head -1 "$W/O02/stdout") → 최종 rc=$r (반례 재현)"
  # S: 정적
  bash -n "$V/launch.sh" && bash -n "$V/smoke.sh"; rep S01 "$([ $? = 0 ] && echo 1)" "bash -n"
  n=$(grep -c 'TZ=' "$V/launch.sh" "$V/smoke.sh" | awk -F: '{s+=$2} END{print s}'); rep S02 "$([ "$n" = 0 ] && echo 1)" "TZ= ${n}건"
  n=$(grep -vE '^[[:space:]]*#' "$V/kst.py" | grep -cE 'os\.environ|zoneinfo|getenv'); rep S03 "$([ "$n" = 0 ] && echo 1)" "kst.py 환경/zoneinfo ${n}건"
  n=$(cat "$V/launch.sh" "$V/smoke.sh" | grep -cE '\b(NOW|FAKE[A-Z_]*|TEST_[A-Z_]*)\b'); rep S04 "$([ "$n" = 0 ] && echo 1)" "시각 주입 변수 ${n}건";;
*) echo "사용: t_shell.sh smoke|launch"; exit 2;;
esac
[ -e "$W/forbidden" ] && { echo "🔴 금지 도구 호출 기록:"; cat "$W/forbidden"; bad=$((bad+1)); } || echo "금지 도구(ssh/curl/docker/psql) 호출 0"
echo "t_shell $MODE ok $ok / $((ok+bad))"; rm -rf "$W"; [ $bad = 0 ]
