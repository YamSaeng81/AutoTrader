#!/bin/bash
# 사용: adv.sh <대역 이름> <초> — 가짜 시계만 민다
S=/d/tmp/o13d2/sim/runs/$1; echo $(( $(cat $S/state/clock) + $2 )) > $S/state/clock
echo "$(date '+%T') 가짜 시계 +$2초 → $(TZ=KST-9 /usr/bin/date -d @"$(cat $S/state/clock)" '+%F %T %Z')" | tee -a $S/out/clock.txt
