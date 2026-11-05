#!/bin/bash
# 사용: run.sh <출력이름> — F·G·T변수·M아침 을 붙여 ssh cat-prod 에 bash -c 한 번(표준 입력 비움). 읽기 전용.
set -u
B=/d/tmp/k24run/blocks; o=/d/tmp/morning1106/$1.txt
s="$(cat $B/F.sh $B/G.sh $B/T변수.sh $B/M아침.sh)"
echo "[로컬 시작] $(date '+%F %T +0900')" | tee "$o"
ssh -o BatchMode=yes cat-prod "bash -c $(printf '%q' "$s")" < /dev/null 2>&1 | tee -a "$o"
echo "[로컬 끝] $(date '+%F %T +0900') rc=${PIPESTATUS[0]}" | tee -a "$o"
