#!/bin/bash
# 보충 읽기(10:2x KST): inv.sh 와 같은 SSH 서비스 로그·같은 구간(22:50~23:10Z)에서 성공 로그인 종류·MaxStartups/drop 줄만. 서버에 쓰지 않는다. IPv4 앞 두 자리만.
L=${1:-.}
{ TZ=Asia/Seoul date "+local %F %T"; date -u "+utc %F %T"; } > "$L/supp-run-time.txt"
ssh -o BatchMode=yes cat-prod 'date "+server %F %T %Z"; t=$(journalctl -u ssh -u sshd --since "2026-11-16 22:50:00 UTC" --until "2026-11-16 23:10:00 UTC" --no-pager -o short-iso 2>&1 | sed -E "s/([0-9]{1,3}\.[0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}/\1.x.x/g"); echo "Accepted 종류:"; printf "%s\n" "$t" | grep -oE "Accepted [a-z-]+ for [^ ]+" | sort | uniq -c; echo "MaxStartups·drop 줄:"; printf "%s\n" "$t" | grep -E "MaxStartups|drop connection" | cut -c1-200; echo "분별 Failed 수:"; printf "%s\n" "$t" | grep -E "Failed" | grep -oE "T[0-9]{2}:[0-9]{2}" | uniq -c; echo "출발지(앞 두 자리)별 Failed:"; printf "%s\n" "$t" | grep -E "Failed" | grep -oE "from [0-9]+\.[0-9]+\.x\.x" | sort | uniq -c | sort -rn | head -5; echo "sshd 설정 PasswordAuthentication(읽기):"; sudo -n sshd -T 2>/dev/null | grep -iE "^(passwordauthentication|kbdinteractiveauthentication|maxstartups|permitrootlogin|port) " || echo "sshd -T 권한 없음(미확인)"' </dev/null > "$L/ssh-supp.txt" 2>&1
echo "rc=$?" >> "$L/supp-run-time.txt"
date -u "+utc end %F %T" >> "$L/supp-run-time.txt"
