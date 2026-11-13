#!/bin/bash
# N03 읽기 전용 — n03-1112-1000/read.sh 와 같은 명령, 회차·폴더·장부 날짜만 인자로 바꿈. 서버에 쓰지 않는다. 재실행·재전송·수집 호출 없음.
# 인자: $1=회차(예 20261113-1000) $2=로컬 폴더 $3=장부 날짜(없으면 생략)
set -u
TAG=$1; L=$2; LD=${3:-}; F=$TAG.txt
mkdir -p $L
TZ=Asia/Seoul date '+%F %T' > $L/run-time.txt; ssh -o BatchMode=yes cat-prod 'date "+server %F %T %Z"' </dev/null >> $L/run-time.txt
ssh -o BatchMode=yes cat-prod "cd ~/daily-check && ls -l --time-style=full-iso $F $F.msg $F.raw $F.log.gz last_send && sha256sum $F $F.msg $F.raw $F.log.gz last_send" </dev/null > $L/server-files.txt 2>&1
ssh -o BatchMode=yes cat-prod "cd ~/daily-check && tar cf - $F $F.msg $F.raw last_send" </dev/null | tar xf - -C $L
ssh -o BatchMode=yes cat-prod "cd ~/daily-check && zcat $F.log.gz | grep ' ERROR ' > /dev/stdout" </dev/null > $L/error_lines.txt
ssh -o BatchMode=yes cat-prod 'cd ~/daily-check && echo "error_lines sha256 $(zcat '$F'.log.gz | grep " ERROR " | sha256sum | cut -d" " -f1)"; echo "log.gz 첫/끝 줄 머리:"; zcat '$F'.log.gz | grep -oE "^[^|]*\| ?[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}" | sed -n "1p;\$p"; echo "crontab $(crontab -l | sha256sum | cut -d" " -f1)"; echo "INSTALLED_SHA $(cat ~/ops-scripts/INSTALLED_SHA)"; echo "daily_check.sh $(sha256sum < ~/ops-scripts/daily_check.sh | cut -d" " -f1)"; echo "daily_checks.sql $(sha256sum < ~/ops-scripts/daily_checks.sql | cut -d" " -f1)"; echo "SID_LEDGER_SHA $(cat ~/ops-scripts/SID_LEDGER_SHA) sid_ledger.sql $(sha256sum < ~/ops-scripts/sid_ledger.sql | cut -d" " -f1)"; cd ~/crypto_trader/AutoTrader && echo "HEAD $(git rev-parse HEAD)"; c=$(docker compose -f docker-compose.prod.yml ps -q backend); docker inspect --format "backend Image={{.Image}} Created={{.Created}} StartedAt={{.State.StartedAt}} RestartCount={{.RestartCount}} Status={{.State.Status}}" $c' </dev/null > $L/install_state.txt 2>&1
ssh -o BatchMode=yes cat-prod 'cd ~/daily-check && z() { zcat '$F'.log.gz; }; echo "HTTP 비200 줄(클래스·상태):"; z | grep -oE "[A-Za-z]+Source[^ ]* .{0,40}HTTP [0-9]{3}" | grep -oE "^[A-Za-z]+Source|HTTP [0-9]{3}" | paste - - | sort | uniq -c; z | grep -E "HTTP [0-9]{3}" | grep -oE "^[^|]*\| ?[0-9:.]+ \[[^]]*\] [A-Z]+ +[^ ]+" | sed -n "1p;\$p"; echo "뉴스 수집 완료 $(z | grep -c "수집 완료 —") · 미완료 $(z | grep -c "수집 미완료")"; z | grep -oE "수집 미완료 — 결과=[A-Z_]+ 종류=[A-Z_0-9]+" | sort | uniq -c; echo "DOCTYPE $(z | grep -c DOCTYPE) · <html $(z | grep -c "<html")"; echo "Hikari 누수 경고 $(z | grep -c "Connection leak detection") · unleaked $(z | grep -c "unleaked") · 획득 실패 $(z | grep -c "Connection is not available")"; echo "N17 줄 $(z | grep -c "REST 강제 갱신 시세가 가격이 아님") · N19 줄 $(z | grep -c "REST 폴백 시세 항목 건너뜀") · N20 줄 $(z | grep -cF "[시세 보강] 시세 항목 건너뜀") · N20 회차 오류 $(z | grep -cF "[시세 보강] 회차 오류") · 시세 처리 실패 $(z | grep -cF "[시세 보강] 시세 처리 실패")"' </dev/null > $L/observations.txt 2>&1
(cd $L && sha256sum $F $F.msg $F.raw last_send | sed 's/ \*/  /') > $L/local-sha256.txt
grep -cE '[0-9]{8,10}:[A-Za-z0-9_-]{30,}' $L/$F $L/$F.msg $L/$F.raw $L/last_send $L/error_lines.txt $L/observations.txt > $L/token-check.txt
ssh -o BatchMode=yes cat-prod 'cd ~/daily-check && z() { zcat '$F'.log.gz; }; echo "수집 미완료 결과·종류:"; z | grep "수집 미완료" | grep -oE "sourceId=[a-z_]+ 결과=[A-Z_]+ 종류=[A-Za-z_0-9]+" | sort | uniq -c; echo "Connection reset 줄 $(z | grep -c "Connection reset")"; echo "ERROR 줄 로거별:"; z | grep " ERROR " | grep -oE " ERROR +[0-9]+ --- \[[^]]*\] [^ ]+| ERROR +[^ ]+" | sed -E "s/\[[^]]*\]//" | sort | uniq -c' </dev/null > $L/observations-supp.txt 2>&1
grep -cE '[0-9]{8,10}:[A-Za-z0-9_-]{30,}' $L/observations-supp.txt >> $L/token-check.txt
if [ -n "$LD" ]; then
ssh -o BatchMode=yes cat-prod 'cd ~/sid203-ledger && ls -l --time-style=full-iso | tail -4 && sha256sum '$LD'.txt && (sha256sum --quiet -c ~/maint-n17/ledger-files-before.sha && echo "과거 장부 파일 그대로(maint-n17 기준)" || echo "🔴 과거 장부 파일 바뀜")' </dev/null > $L/ledger-files.txt 2>&1
ssh -o BatchMode=yes cat-prod 'cat ~/sid203-ledger/'$LD'.txt' </dev/null > $L/ledger-$LD.txt
grep -cE '[0-9]{8,10}:[A-Za-z0-9_-]{30,}' $L/ledger-$LD.txt $L/ledger-files.txt >> $L/token-check.txt
fi
# v1.161 §4-1 추가(읽기만): frontend 실제 컨테이너 ID·전체 .Image ID·조회 시각
ssh -o BatchMode=yes cat-prod 'cd ~/crypto_trader/AutoTrader && echo "조회 $(date "+%F %T %Z")"; c=$(docker compose -f docker-compose.prod.yml ps -q frontend); echo "frontend 컨테이너 $c"; docker inspect --format "frontend Name={{.Name}} Image={{.Image}} ConfigImage={{.Config.Image}} Created={{.Created}} StartedAt={{.State.StartedAt}} RestartCount={{.RestartCount}} Status={{.State.Status}}" $c' </dev/null > $L/frontend-image.txt 2>&1
TZ=Asia/Seoul date '+%F %T' >> $L/run-time.txt
