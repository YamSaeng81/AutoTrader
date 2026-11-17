#!/bin/bash
# 총괄 v1.179 §4-1 SSH 보안 제한 읽기(서버 안에서 표준 입력으로 실행). 서버에 쓰지 않는다. sudo 는 -n(비밀번호 묻지 않음) 확인만.
# 전체 IP 는 서버 안 변수에서만 다루고, 출력은 IPv4 앞 두 자리(a.b.x.x)·IPv6 앞 두 묶음으로 가린다. 비밀키·비밀번호·설정 전문은 출력하지 않는다.
# 읽기마다 종료값을 따로 적는다: rc[이름]=값. grep 은 0=일치·1=일치 없음·2 이상=검사 실패로 따로 적는다(gre 함수).
export LC_ALL=C
mask() { sed -E 's/([0-9]{1,3}\.[0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}/\1.x.x/g; s/([0-9a-fA-F]{1,4}:[0-9a-fA-F]{1,4}):[0-9a-fA-F:]+/\1:…/g'; }
rc() { echo "rc[$1]=$2"; }
gre() { # gre 이름 패턴 <입력 — grep 결과 개수와 grep 종료값을 분리
  local n out r; out=$(grep -cE "$2"); r=$?; n=${out:-?}
  case $r in 0) s="일치";; 1) s="일치없음";; *) s="검사실패";; esac
  echo "grep[$1] 개수=$n rc=$r($s)"; }
echo "== A 서버 시각"; date "+server %F %T %Z"; date -u "+server_utc %F %T"; rc A $?
echo "== B 최근 5분 SSH 서비스 로그"
J5=$(journalctl -u ssh -u sshd -u ssh.socket --since "-5min" --no-pager -o short-iso 2>&1); r=$?; rc B.journal $r
echo "줄 수=$(printf '%s\n' "$J5" | grep -c .)"
printf '%s\n' "$J5" | gre B.failed "Failed (password|publickey|none)|Invalid user"
printf '%s\n' "$J5" | gre B.maxstartups "MaxStartups|drop connection"
printf '%s\n' "$J5" | gre B.accepted "Accepted "
printf '%s\n' "$J5" | gre B.journal_denied "No journal files|insufficient permissions|Permission denied"
echo "B 고유 출발지 수(실패, 전체 IP 서버 안 집계)=$(printf '%s\n' "$J5" | grep -E "Failed|Invalid user" | grep -oE "from [0-9a-fA-F:.]+" | sort -u | wc -l)"
echo "B 마지막 MaxStartups 줄(가림):"; printf '%s\n' "$J5" | grep -E "MaxStartups" | tail -2 | mask | cut -c1-200
echo "== C 07:50~08:10 KST(22:50~23:10Z) 원본 정확 IP 집계"
JC=$(journalctl -u ssh -u sshd --since "2026-11-16 22:50:00 UTC" --until "2026-11-16 23:10:00 UTC" --no-pager -o short-iso 2>&1); rc C.journal $?
F=$(printf '%s\n' "$JC" | grep -E "Failed (password|publickey|none)" | grep -oE "from [0-9a-fA-F:.]+" | sed 's/^from //'); echo "Failed(출발지 있는 줄)=$(printf '%s\n' "$F" | grep -c .)"
echo "고유 출발지 IP 수=$(printf '%s\n' "$F" | grep . | sort -u | wc -l)"
echo "고유 /16(a.b) 수=$(printf '%s\n' "$F" | grep . | cut -d. -f1-2 | sort -u | wc -l)"
echo "IP별 상위 5(건수 · 가린 IP · 순위 표지):"; printf '%s\n' "$F" | grep . | sort | uniq -c | sort -rn | head -5 | awk '{printf "%s\t%s\n",$1,$2}' | mask
echo "143.198 /16 안 고유 IP 수=$(printf '%s\n' "$F" | grep -E '^143\.198\.' | sort -u | wc -l), 그 건수=$(printf '%s\n' "$F" | grep -cE '^143\.198\.')"
echo "== D 성공 로그인 귀속(같은 구간)"
CUR=$(printf '%s' "${SSH_CONNECTION:-}" | awk '{print $1}')
printf '%s\n' "$JC" | grep -E "Accepted " | while read -r l; do
  u=$(printf '%s' "$l" | grep -oE "for [^ ]+" | head -1 | cut -c5-); m=$(printf '%s' "$l" | grep -oE "Accepted [a-z-]+" | cut -c10-)
  ip=$(printf '%s' "$l" | grep -oE "from [0-9a-fA-F:.]+" | cut -c6-); fp=$(printf '%s' "$l" | grep -oE "(ED25519|RSA|ECDSA|DSA)[^ ]* SHA256:[A-Za-z0-9+/=]+" )
  same=$([ -n "$CUR" ] && [ "$ip" = "$CUR" ] && echo 지금접속과같은IP || echo 다른IP)
  echo "$u|$m|$fp|$same"; done | sort | uniq -c
rc D.accepted_loop $?
echo "D 지금 접속 IP(가림)=$(printf '%s' "$CUR" | mask)"
echo "D 이 계정 authorized_keys 지문(주석 제외):"; ssh-keygen -lf ~/.ssh/authorized_keys 2>&1 | awk '{print $1,$2,$NF}'; rc D.authkeys ${PIPESTATUS[0]}
echo "D authorized_keys 줄 수(주석·빈 줄 제외)=$(grep -cvE '^\s*(#|$)' ~/.ssh/authorized_keys 2>/dev/null)"; ls -l ~/.ssh/authorized_keys 2>&1 | awk '{print $1,$3,$4}'
echo "D 7일 Accepted 종류·사용자(전체 기간 아님, 출발지 가림):"; journalctl -u ssh -u sshd --since "-7days" --no-pager 2>/dev/null | grep -oE "Accepted [a-z-]+ for [^ ]+" | sort | uniq -c; rc D.7d ${PIPESTATUS[0]}
echo "D 로그인 가능한 셸 계정(사용자명만):"; awk -F: '$7 !~ /(nologin|false|sync)$/ {print $1}' /etc/passwd | tr '\n' ' '; echo
echo "== E OpenSSH 판·서비스"
ssh -V 2>&1; dpkg-query -W -f='${Package} ${Version}\n' openssh-server 2>&1; rc E.dpkg $?
systemctl is-active ssh.service ssh.socket sshd.service 2>&1 | tr '\n' ' '; echo; systemctl is-enabled ssh.service ssh.socket 2>&1 | tr '\n' ' '; echo
systemctl show ssh.service -p ExecStart -p ActiveEnterTimestamp --no-pager 2>&1 | cut -c1-250; systemctl show ssh.socket -p Listen --no-pager 2>&1
echo "sshd 프로세스 인자:"; ps -o args= -C sshd 2>&1 | grep -v "^sshd: [a-z]" | sort | uniq -c | head -5
echo "== F 읽을 수 있는 설정(관련 키만, 주석 제외)"
ls -l /etc/ssh/sshd_config 2>&1 | awk '{print $1,$3,$4,$NF}'; ls -l /etc/ssh/sshd_config.d/ 2>&1 | awk 'NR>1{print $1,$3,$4,$NF}'
K='^\s*(PasswordAuthentication|KbdInteractiveAuthentication|ChallengeResponseAuthentication|AuthenticationMethods|PubkeyAuthentication|PermitRootLogin|AllowUsers|AllowGroups|DenyUsers|DenyGroups|MaxStartups|MaxAuthTries|LoginGraceTime|UsePAM|Port|ListenAddress|AddressFamily|Include|Match)\b'
for f in /etc/ssh/sshd_config /etc/ssh/sshd_config.d/*.conf; do
  [ -e "$f" ] || { echo "$f 없음"; continue; }
  o=$(grep -nEi "$K" "$f" 2>&1); r=$?; echo "-- $f grep rc=$r($( [ $r -eq 0 ] && echo 일치 || { [ $r -eq 1 ] && echo 일치없음 || echo 검사실패; } ))"; printf '%s\n' "$o" | grep . | mask
done
echo "sshd -T(권한):"; sudo -n /usr/sbin/sshd -T >/dev/null 2>&1; rc F.sudo_sshd_T $?
sudo -n true 2>/dev/null; rc F.sudo_n $?
echo "== G 방화벽·대기 포트"
systemctl is-active ufw nftables firewalld fail2ban 2>&1 | tr '\n' ' '; echo "(ufw nftables firewalld fail2ban)"
ufw status 2>&1 | head -3; rc G.ufw ${PIPESTATUS[0]}
ss -Htln 2>&1 | awk '{print $4}' | mask | sort -u | tr '\n' ' '; echo; rc G.ss ${PIPESTATUS[0]}
echo "IPv6 전역 주소 수=$(ip -6 addr show scope global 2>/dev/null | grep -c inet6)"
id -nG | tr ' ' '\n' | grep -E '^(sudo|adm|systemd-journal|docker)$' | tr '\n' ' '; echo "(관련 그룹)"
echo "== 끝"; date "+server %F %T %Z"
