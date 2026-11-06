#!/bin/bash
# 사용: sim.sh <대역 이름> <블록> [꼬리] — F·G·D2 U변수·(대역 IMGOLD)·블록을 붙여 로컬 bash 로만 돌린다. ssh 없음.
# PATH 맨 앞은 가짜 명령, 그 뒤는 Git Bash 기본 도구뿐(Windows 의 docker·git·python 경로는 빼고 넣지 않는다).
set -u
R=/d/tmp/n20d1sim; S=$R/runs/$1; n=$2; o=$S/out/out-${n}${3:+-$3}.txt
B=/d/tmp/n20c/blocks-d2; K=/d/tmp/k25run/blocks
[ ! -e "$o" ] || { echo "이미 있는 출력 $o"; exit 2; }
blk=$B/$n.sh; [ -e "$blk" ] || blk=$R/extra/$n.sh
s="$(cat $K/F.sh $K/G.sh $B/U변수.sh; [ -e "$S/imgold.sh" ] && cat "$S/imgold.sh"; cat "$blk")"
echo "[대역 시작] $n · 가짜 시각 $(TZ=KST-9 /usr/bin/date -d @"$(cat $S/state/clock)" '+%F %T %Z') · 실제 $(/usr/bin/date '+%T')" | tee "$o"
HOME=$S/home PATH=$R/stubs:/usr/bin:/bin TZ=KST-9 SIM=$S bash -c "$s" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[대역 끝] rc=$rc · 가짜 시각 $(TZ=KST-9 /usr/bin/date -d @"$(cat $S/state/clock)" '+%T')" | tee -a "$o"
