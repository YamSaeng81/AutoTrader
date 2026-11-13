#!/bin/bash
# v1.163 §3-1: 첨부 읽기 전용 조회문 1회 실행. 기존 F 블록의 PSQL 경로(ON_ERROR_STOP=1) 사용.
# 출력 파일이 있으면 덮어쓰지 않고 종료 2. ssh 종료값은 PIPESTATUS[0]로 즉시 기록.
set -u
cd "$(dirname "$0")"
o=out-loss-window.txt
[ ! -e "$o" ] || { echo "거절: 이미 있는 출력 $o"; exit 2; }
q=$(cat loss-window-readonly.sql; echo x); q=${q%x}
r='set -u; cd ~/crypto_trader/AutoTrader || exit 3
echo "[서버 시작] $(date "+%F %T %Z") / UTC $(date -u "+%F %T")"
printf "%s" "$Q" | sha256sum | sed "s/^/[서버 입력 SQL sha256] /"
printf "%s" "$Q" | docker compose -f docker-compose.prod.yml exec -T db psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -x -f -
rc=$?
echo "[서버 끝] $(date "+%F %T %Z") psql rc=$rc"
exit $rc'
echo "[로컬 시작] $(date '+%F %T') 입력 SQL sha256 $(sha256sum loss-window-readonly.sql | cut -c1-64)" | tee "$o"
ssh -o BatchMode=yes cat-prod "Q=$(printf '%q' "$q") bash -c $(printf '%q' "$r")" < /dev/null 2>&1 | tee -a "$o"
rc=${PIPESTATUS[0]}
echo "[로컬 끝] $(date '+%F %T') rc=$rc" | tee -a "$o"
exit "$rc"
