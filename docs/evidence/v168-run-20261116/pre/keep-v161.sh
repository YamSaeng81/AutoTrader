#!/bin/bash
# v1.170 §2 보완판 — 원격 구간은 같은 폴더의 keep-v161-remote.sh 원문을 그대로 보낸다(F/G·V168D4 블록과 무관).
# 사용: keep-v161.sh <로컬 출력 폴더>. 출력 keep-v161.txt 가 이미 있으면 종료2. ssh 종료값을 즉시 PIPESTATUS 로 잡아 기록·반환한다.
set -u
L=${1:?로컬 출력 폴더}; SSH=${KEEPSSH:-ssh}; R="$(dirname "$0")/keep-v161-remote.sh"
mkdir -p "$L"; o="$L/keep-v161.txt"
[ ! -e "$o" ] || { echo "🔴 거절(종료 2): 이미 있는 출력 $o"; exit 2; }
[ -f "$R" ] || { echo "🔴 거절(종료 2): 원격 구간 $R 없음"; exit 2; }
s="$(cat "$R")"
echo "[로컬 시작] $(date '+%F %T') KST remote-sha256=$(sha256sum "$R" | cut -c1-64)" | tee "$o"
$SSH -o BatchMode=yes cat-prod "bash -c $(printf '%q' "$s")" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[로컬 끝] $(date '+%F %T') KST rc=$rc" | tee -a "$o"
exit "$rc"
