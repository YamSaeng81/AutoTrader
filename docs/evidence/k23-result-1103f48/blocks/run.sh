#!/bin/bash
# 사용: run.sh <블록이름> — F·G·R변수·블록을 붙여 ssh cat-prod 에 bash -c 한 번으로 보낸다(표준 입력 비움)
set -u
B=/d/tmp/k23run/blocks; n=$1; o=/d/tmp/k23run/out/out-${n}.txt
s="$(cat $B/F.sh $B/G.sh $B/R변수.sh $B/$n.sh)"
{ echo "[로컬 시작] $(date '+%F %T +0900')"; } | tee "$o"
ssh -o BatchMode=yes cat-prod "bash -c $(printf '%q' "$s")" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[로컬 끝] $(date '+%F %T +0900') rc=$rc" | tee -a "$o"
