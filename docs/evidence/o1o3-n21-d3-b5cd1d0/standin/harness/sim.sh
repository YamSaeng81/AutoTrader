#!/bin/bash
# 사용: sim.sh <대역 이름> <블록> [꼬리] — F·G·D2 U변수·(대역 덧붙임 vars.sh)·블록을 붙여 로컬 bash 로만 돌린다. ssh 없음.
# PATH 맨 앞은 가짜 명령, 그 뒤는 Git Bash 기본 도구뿐(Windows 의 docker·git·python 경로는 빼고 넣지 않는다).
# 출력 파일이 이미 있으면 덮어쓰지 않고 종료 2(블록을 돌리지 않는다).
set -u
. /d/tmp/o13n21d3/sim/conf.sh
S=$R/runs/$1; n=$2; o=$S/out/out-${n}${3:+-$3}.txt
[ ! -e "$o" ] || { echo "🔴 대역 거절(종료 2): 이미 있는 출력 $o — 블록을 돌리지 않았다"; exit 2; }
blk=$BLOCKS/$n.sh; [ -e "$blk" ] || blk=$R/extra/$n.sh
UV=${UVAR:-$BLOCKS/U변수.sh}
s="$(cat $FG/F.sh $FG/G.sh "$UV"; [ -e "$S/vars.sh" ] && cat "$S/vars.sh"; cat "$blk")"
echo "[대역 시작] $n · 가짜 시각 $(TZ=KST-9 /usr/bin/date -d @"$(cat $S/state/clock)" '+%F %T %Z') · 실제 $(/usr/bin/date '+%T')" | tee "$o"
HOME=$S/home PATH=$R/stubs:/usr/bin:/bin TZ=KST-9 SIM=$S bash -c "$s" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[대역 끝] rc=$rc · 가짜 시각 $(TZ=KST-9 /usr/bin/date -d @"$(cat $S/state/clock)" '+%T')" | tee -a "$o"
exit $rc
