#!/bin/bash
# 사용: run.sh <블록이름> [출력 꼬리] — F·G·U변수·블록을 붙여 ssh cat-prod 에 bash -c 한 번으로 보낸다(표준 입력 비움).
# 블록은 342f461 §5-9 92 원문 추출본(blocks/blocks.json). 출력 파일이 이미 있으면 덮어쓰지 않고 멈춘다(실패 이력 보존).
set -u
B=/d/tmp/k25run/blocks; n=$1; o=/d/tmp/k25run/out/out-${n}${2:+-$2}.txt
[ ! -e "$o" ] || { echo "이미 있는 출력 $o — 꼬리를 붙여 다시"; exit 2; }
s="$(cat $B/F.sh $B/G.sh $B/U변수.sh $B/$n.sh)"
{ echo "[로컬 시작] $(date '+%F %T +0900')"; } | tee "$o"
ssh -o BatchMode=yes cat-prod "bash -c $(printf '%q' "$s")" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[로컬 끝] $(date '+%F %T +0900') rc=$rc" | tee -a "$o"
