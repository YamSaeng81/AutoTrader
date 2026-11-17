#!/bin/bash
# 운영자 관리자 제한 읽기 보충(총괄 v1.181 §2) — admin_read.sh(0b8f3c32…) 와 같은 세션에서 이어 부른다. 읽기만, 서버에 남는 파일 없음.
# admin_read.sh 는 sshd -T -C 의 host=addr 를 UseDNS no 로 가정했지만 usedns 값을 수집하지 않았다. 이 보충이 그 값과 hostname 조건 유무를 읽는다.
#   sudo bash ~/admin_read_add.sh > ~/admin-read-add-out.txt; echo "rc=$?"
# 종료값: 0 = 아래 읽기를 모두 실행함(개별 결과는 rc[...] 줄로 판단 — 0 이 변경 가능 표지가 아니다) · 3 root 아님 · 8 임시 폴더 실패
set -u
export LC_ALL=C
[ "$(id -u)" = 0 ] || { echo "STOP: root(sudo) 필요 — 아무것도 읽지 않음"; exit 3; }
T=$(mktemp -d /tmp/sshadd.XXXXXX) || { echo "STOP: 임시 폴더 실패"; exit 8; }
trap 'case "$T" in /tmp/sshadd.??????) rm -rf -- "$T";; esac' EXIT
rc() { echo "rc[$1]=$2"; }
echo "== 시각 $(date '+%F %T %Z')"
/usr/sbin/sshd -T > "$T/plain" 2> "$T/plain.err"; r=$?; rc sshd_T.plain $r; echo "  stderr 줄 수=$(grep -c . "$T/plain.err")"
grep -E '^usedns ' "$T/plain" > "$T/u"; g=$?; rc grep.usedns $g; sed 's/^/  /' "$T/u"
echo "== hostname 이 끼는 설정 줄(모든 sshd 설정 파일, 줄 번호·값만)"
for f in /etc/ssh/sshd_config /etc/ssh/sshd_config.d/*; do
  [ -f "$f" ] || continue
  grep -nEi '^[[:space:]]*(UseDNS|Match[[:space:]].*\bHost\b|Include)\b' "$f" > "$T/h" 2>&1; g=$?
  echo "-- $f grep rc=$g"; sed -E 's/^/  /; s/([0-9]{1,3}\.[0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}/\1.x.x/g' "$T/h"
done
echo "== 서비스 실행 인자(-f/-o 명령줄 옵션 확인)"
systemctl show -p ExecStart --value ssh.service > "$T/x" 2>&1; rc systemctl.execstart $?; sed 's/^/  /' "$T/x"
ps -o args= -C sshd > "$T/p" 2>&1; rc ps.sshd $?; grep -E '^/usr/sbin/sshd|sshd: .*listener' "$T/p" | sort | uniq -c | sed 's/^/  /'
echo "판정 안내: rc[sshd_T.plain]=0 이고 'usedns no' 한 줄, Match Host 줄 0, 명령줄 -f/-o 없음일 때만 admin_read 의 5조건을 실제 접속 정책으로 쓴다. 아니면 hostname 조건까지 다시 고정."
