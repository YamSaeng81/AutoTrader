#!/bin/bash
# D81B-EXP1 서버 배치 시작기(총괄 v1.250 §2). 총괄의 원문/해시 수용 뒤 1회만 쓴다 — 이 파일의 존재는 시작 승인이 아니다.
# 사용(서버에서): bash ~/work-batches/d81b-20261127-exp1/batch/launch.sh
# 하는 일: 배치·출처 해시 확인 → 잠금 비어 있음·runs/ 비어 있음 확인 → setsid nohup 으로 batch.sh 1개를 띄우고(stdin 닫음, 출력·오류는 logs/<작업>.txt) 3초 뒤 상태를 보여 준다.
# 띄운 뒤 SSH 가 끊겨도 batch.sh 는 계속 돈다(새 세션·SIGHUP 무시). 이 시작기는 재시도하지 않는다.
set -u
BASE=$HOME/work-batches/d81b-20261127-exp1
cd "$BASE/batch" && sha256sum --quiet -c BATCH.sha256 || { echo "🔴 배치 파일 해시 불일치 — 시작 안 함"; exit 2; }
cd "$BASE/src" && sha256sum --quiet -c MANIFEST.sha256 || { echo "🔴 출처 파일 해시 불일치 — 시작 안 함"; exit 2; }
mkdir -p "$BASE/runs" "$BASE/logs" || exit 4
[ -z "$(ls -A "$BASE/runs")" ] || { echo "🔴 runs/ 에 이전 실행 기록이 있다 — 1회 작업, 시작 안 함"; ls -l "$BASE/runs"; exit 3; }
( exec 9>>"$BASE/batch.lock" && flock -n 9 ) || { echo "🔴 잠금이 잡혀 있다 — 다른 배치가 돈다, 시작 안 함"; exit 3; }
J=d81b-u0u1-$(date -u +%Y%m%dT%H%M%SZ)-$$
L=$BASE/logs/$J.txt
[ ! -e "$L" ] || exit 3
setsid nohup bash "$BASE/batch/batch.sh" "$J" < /dev/null > "$L" 2>&1 &
P=$!
echo "시작 작업=$J 띄운 pid=$P 로그=$L"
sleep 3
ps -o pid,ppid,sid,etimes,args -p "$P" 2>/dev/null || echo "(pid $P 가 이미 끝났다 — 로그·runs 를 본다)"
cat "$BASE/runs/$J/state.txt" 2>/dev/null || echo "(state.txt 아직 없음)"
cat "$L"
