#!/bin/bash
# 총괄 v1.136 §104-2 — 11-09 관측 기록 공백(16:19Z→19:56Z) 기존 기록 읽기만. 실행·재실행·수집·API·연결 시험 없음. 서버에 쓰지 않는다.
set -u
L=/d/tmp/gap-1109
ssh -o BatchMode=yes cat-prod 'cd ~/crypto_trader/AutoTrader && echo "HEAD $(git rev-parse HEAD)"; echo "start_66.py blob $(git hash-object scripts/prospective/start_66.py) · HEAD blob $(git rev-parse HEAD:scripts/prospective/start_66.py)"; git status --porcelain -- scripts/prospective/start_66.py | sed "s/^/status: /"; echo "crontab start_66 줄:"; crontab -l | grep -n "start_66" ; echo "TZ $(date +%Z) $(cat /etc/timezone 2>/dev/null)"; cd scripts/prospective && ls -l --time-style=full-iso freshness_log.jsonl freshness_runs.log alerts_last_check.json 2>&1; ls -l --time-style=full-iso | grep -i -E "streak|fresh" ' </dev/null > $L/server-state.txt 2>&1
ssh -o BatchMode=yes cat-prod 'cd ~/crypto_trader/AutoTrader/scripts/prospective && grep -E "\"at\": \"2026-11-08T(1[4-9]|2[0-2])" freshness_log.jsonl' </dev/null > $L/freshness_log-1108T14-22.jsonl 2>&1
ssh -o BatchMode=yes cat-prod 'cd ~/crypto_trader/AutoTrader/scripts/prospective && grep -n -E "\"at\": \"2026-11-08T(1[4-9]|2[0-2])" freshness_log.jsonl | cut -d: -f1 | tr "\n" " "; echo; wc -l freshness_log.jsonl' </dev/null > $L/freshness_log-linenos.txt 2>&1
ssh -o BatchMode=yes cat-prod 'for f in /var/log/syslog /var/log/syslog.1; do [ -r $f ] && { echo "== $f"; grep -E "Nov  ?[89] .*CRON.*(start_66|daily_check)" $f | grep -E "Nov  ?(9 0[0-9]|8 2[0-3]|9 1[0-1])" ; } || echo "읽기 불가 $f"; done; ls /var/log/syslog* 2>&1' </dev/null > $L/syslog-cron.txt 2>&1
ssh -o BatchMode=yes cat-prod 'journalctl --since "2026-11-09 00:00" --until "2026-11-09 07:00" -t CRON --no-pager 2>&1 | grep -E "start_66|daily_check|Hint|No journal|Permission" | head -60' </dev/null > $L/journal-cron.txt 2>&1
# 보충: 실행 출력 로그의 회차 표지 줄(시각 없음) — 읽기만
ssh -o BatchMode=yes cat-prod 'cd ~/crypto_trader/AutoTrader/scripts/prospective && grep -n -E "^🟢 일치 [0-9]+ / ⚪|뒤처진 [0-9]+종 — 70초|Traceback|Error|^x " freshness_runs.log | tail -80; wc -l freshness_runs.log' </dev/null > $L/runs-markers-tail80.txt 2>&1
