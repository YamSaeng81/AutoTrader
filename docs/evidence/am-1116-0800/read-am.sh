#!/bin/bash
# 11-16 아침 운영 읽기 1회 — n03-1115-1000/read.sh 의 읽기 명령 일부(설치 상태·frontend·daily-check 목록)만. 서버에 쓰지 않음.
set -u
L=$1; mkdir -p $L
TZ=Asia/Seoul date '+%F %T' > $L/run-time.txt; ssh -o BatchMode=yes cat-prod 'date "+server %F %T %Z"' </dev/null >> $L/run-time.txt
ssh -o BatchMode=yes cat-prod 'cd ~/daily-check && ls -l --time-style=full-iso | tail -12' </dev/null > $L/daily-check-ls.txt 2>&1
ssh -o BatchMode=yes cat-prod 'echo "crontab $(crontab -l | sha256sum | cut -d" " -f1)"; echo "INSTALLED_SHA $(cat ~/ops-scripts/INSTALLED_SHA)"; echo "daily_check.sh $(sha256sum < ~/ops-scripts/daily_check.sh | cut -d" " -f1)"; echo "daily_checks.sql $(sha256sum < ~/ops-scripts/daily_checks.sql | cut -d" " -f1)"; echo "SID_LEDGER_SHA $(cat ~/ops-scripts/SID_LEDGER_SHA) sid_ledger.sql $(sha256sum < ~/ops-scripts/sid_ledger.sql | cut -d" " -f1)"; cd ~/crypto_trader/AutoTrader && echo "HEAD $(git rev-parse HEAD)"; c=$(docker compose -f docker-compose.prod.yml ps -q backend); docker inspect --format "backend Image={{.Image}} Created={{.Created}} StartedAt={{.State.StartedAt}} RestartCount={{.RestartCount}} Status={{.State.Status}}" $c' </dev/null > $L/install_state.txt 2>&1
ssh -o BatchMode=yes cat-prod 'cd ~/crypto_trader/AutoTrader && echo "조회 $(date "+%F %T %Z")"; c=$(docker compose -f docker-compose.prod.yml ps -q frontend); echo "frontend 컨테이너 $c"; docker inspect --format "frontend Name={{.Name}} Image={{.Image}} ConfigImage={{.Config.Image}} Created={{.Created}} StartedAt={{.State.StartedAt}} RestartCount={{.RestartCount}} Status={{.State.Status}}" $c' </dev/null > $L/frontend-image.txt 2>&1
ssh -o BatchMode=yes cat-prod 'cd ~/sid203-ledger && ls -l --time-style=full-iso | tail -4' </dev/null > $L/ledger-ls.txt 2>&1
TZ=Asia/Seoul date '+%F %T' >> $L/run-time.txt
