#!/bin/bash
# 총괄 v1.180 §4-2 — 운영자용 SSH 유효 설정 제한 읽기 (읽기만). 운영자가 서버 안에서 root 로 실행한다.
#
#   sudo bash admin_read.sh <개발계정> <개발PC 출발주소> <서버 수신주소> <서버 수신포트> <외부 예시주소> > admin-read-out.txt
#
#   - 앞 네 값은 개발 PC 에서 접속한 세션의 `echo "$SSH_CONNECTION"`(출발주소 출발포트 수신주소 수신포트)에서
#     출발주소·수신주소·수신포트를 그대로 쓴다. 개발계정은 그 세션의 `id -un`.
#   - 외부 예시주소는 공격자 조건 모사용이며 개발 PC 주소와 달라야 한다(문서용 주소 203.0.113.1 을 권장).
#   - 값이 비었거나 형식이 다르면 아무것도 읽지 않고 종료 2. root 가 아니면 종료 3.
#
# 하지 않는 것: 설정·키·방화벽·서비스 변경, 비밀번호 입력 요청, 설정 전문·키 본문 출력.
# 서버에 남기는 것: 없음. 임시 폴더(/tmp/sshread.XXXXXX)는 끝에 지운다(경로 형식이 다르면 지우지 않고 알린다).
# 출력: 표준 출력 한 곳. IPv4 는 앞 두 자리(a.b.x.x), IPv6 는 앞 두 묶음으로 가린다. 마지막 줄에 가림 확인 개수를 적는다.
# 종료값 규칙: 읽기마다 rc[이름]=값. grep 은 rc 0=일치·1=일치없음·2 이상=검사실패를 따로 적는다.
#   sshd -T 의 종료값은 grep 과 분리해 먼저 적는다(파이프 하지 않음).
set -u
export LC_ALL=C

[ "$(id -u)" = 0 ] || { echo "STOP: root(sudo) 필요 — 아무것도 읽지 않음"; exit 3; }
[ $# -eq 5 ] || { echo "STOP: 인자 5개 필요(개발계정 출발주소 수신주소 수신포트 외부예시주소) — 아무것도 읽지 않음"; exit 2; }
U=$1 A=$2 LA=$3 LP=$4 X=$5
bad=""
[[ $U  =~ ^[a-z_][a-z0-9_-]{0,31}$ ]]  || bad="$bad 개발계정"
[[ $A  =~ ^[0-9A-Fa-f:.]{2,45}$ ]]     || bad="$bad 출발주소"
[[ $LA =~ ^[0-9A-Fa-f:.]{2,45}$ ]]     || bad="$bad 수신주소"
[[ $LP =~ ^[0-9]{1,5}$ ]] && [ "$LP" -ge 1 ] && [ "$LP" -le 65535 ] || bad="$bad 수신포트"
[[ $X  =~ ^[0-9A-Fa-f:.]{2,45}$ ]]     || bad="$bad 외부예시주소"
[ "$X" != "$A" ] || bad="$bad 외부예시주소=출발주소"
[ -z "$bad" ] || { echo "STOP: 빈 값/형식 이상:$bad — 아무것도 읽지 않음"; exit 2; }

T=$(mktemp -d /tmp/sshread.XXXXXX) || { echo "STOP: 임시 폴더 실패"; exit 8; }
cleanup() { case "$T" in /tmp/sshread.??????) rm -rf -- "$T" ;; *) echo "임시 폴더 경로 형식 이상 — 지우지 않음" ;; esac; }
trap cleanup EXIT

mask() { sed -E 's/([0-9]{1,3}\.[0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}/\1.x.x/g; s/([0-9A-Fa-f]{1,4}:[0-9A-Fa-f]{0,4}):([0-9A-Fa-f]{0,4}:){2,}[0-9A-Fa-f]{0,4}/\1:…/g'; }
rc() { echo "rc[$1]=$2"; }
grc() { case $1 in 0) echo "일치";; 1) echo "일치없음";; *) echo "검사실패";; esac; }

# sshd -T 에서 볼 키(8.9p1 이름)
KEYS='passwordauthentication|kbdinteractiveauthentication|authenticationmethods|pubkeyauthentication|permitrootlogin|permitemptypasswords|hostbasedauthentication|gssapiauthentication|usepam|allowusers|allowgroups|denyusers|denygroups|authorizedkeysfile|authorizedkeyscommand|authorizedkeyscommanduser|maxstartups|maxauthtries|logingracetime|port|listenaddress|addressfamily'
# 설정 파일에서 볼 줄
CK='^[[:space:]]*(Include|Match|PasswordAuthentication|KbdInteractiveAuthentication|ChallengeResponseAuthentication|AuthenticationMethods|PubkeyAuthentication|PermitRootLogin|PermitEmptyPasswords|AllowUsers|AllowGroups|DenyUsers|DenyGroups|AuthorizedKeysFile|AuthorizedKeysCommand|UsePAM|MaxStartups|Port|ListenAddress)\b'

tcheck() {  # tcheck 이름 조건 — 종료값 먼저, 그 다음 키 줄(grep 종료값 따로)
  local n=$1 c=$2 r g
  /usr/sbin/sshd -T -C "$c" > "$T/$n.out" 2> "$T/$n.err"; r=$?
  rc "sshd_T.$n" "$r"
  echo "  stderr 줄 수=$(grep -c . "$T/$n.err")"; head -3 "$T/$n.err" | cut -c1-200 | sed 's/^/  stderr: /'
  grep -Ei "^($KEYS) " "$T/$n.out" > "$T/$n.keys"; g=$?
  echo "  grep[keys.$n] 줄수=$(grep -c . "$T/$n.keys") rc=$g($(grc $g))"
  sed 's/^/  /' "$T/$n.keys"
}

main() {
  echo "== A 시각·판"
  date "+server %F %T %Z"; date -u "+server_utc %F %T"
  dpkg-query -W -f='${Package} ${Version}\n' openssh-server; rc A.dpkg $?
  echo "조건 값(가림): user=$U addr=$A laddr=$LA lport=$LP ext=$X"

  echo "== B 구문 검사"
  /usr/sbin/sshd -t > "$T/t.out" 2>&1; r=$?; rc sshd_t "$r"; head -5 "$T/t.out" | cut -c1-200

  echo "== C 조건별 유효 설정(sshd -T -C). host 는 UseDNS no 기준으로 주소와 같게 준다"
  echo "-- C1 개발계정·개발 PC 출발";   tcheck dev       "user=$U,host=$A,addr=$A,laddr=$LA,lport=$LP"
  echo "-- C2 개발계정·외부 출발";      tcheck dev_ext   "user=$U,host=$X,addr=$X,laddr=$LA,lport=$LP"
  echo "-- C3 root·외부 출발";          tcheck root_ext  "user=root,host=$X,addr=$X,laddr=$LA,lport=$LP"
  echo "-- C4 root·개발 PC 출발";       tcheck root_dev  "user=root,host=$A,addr=$A,laddr=$LA,lport=$LP"
  echo "-- C5 없는 사용자·외부 출발";   tcheck nouser    "user=nosuchuser-catchk,host=$X,addr=$X,laddr=$LA,lport=$LP"
  echo "-- C1 과 C2 의 키 줄 차이(Match Address 등 출발지 조건 효과)"
  diff "$T/dev.keys" "$T/dev_ext.keys" > "$T/d12"; r=$?; rc diff.dev_vs_ext "$r"; sed 's/^/  /' "$T/d12"

  echo "== D 설정 파일(관련 줄만, 주석 제외) · 소유/권한 · 해시"
  for f in /etc/ssh/sshd_config /etc/ssh/sshd_config.d/*; do
    [ -e "$f" ] || { echo "-- $f 없음"; continue; }
    echo "-- $f $(stat -c '%a %U:%G %s바이트' "$f")"
    sha256sum "$f" | awk '{print "  sha256 " $1}'
    grep -nEi "$CK" "$f" > "$T/cf" 2>&1; r=$?; echo "  grep rc=$r($(grc $r))"; sed 's/^/  /' "$T/cf"
  done
  [ -e /etc/ssh/sshd_config.d/00-cat-hardening.conf ] && echo "00-cat-hardening.conf 있음(변경 전제 위반)" || echo "00-cat-hardening.conf 없음(변경 전제 충족)"
  echo "sshd_config.d 파일 목록 해시(이름·sha256 정렬)=$( (cd /etc/ssh/sshd_config.d 2>/dev/null && for f in *; do [ -f "$f" ] && printf '%s %s\n' "$f" "$(sha256sum < "$f" | cut -d' ' -f1)"; done) | sort | sha256sum | cut -d' ' -f1)"

  echo "== E 서비스 인자"
  systemctl show ssh.service -p ExecStart -p ExecReload -p DropInPaths -p MainPID -p ActiveState --no-pager | cut -c1-250; rc E.show "${PIPESTATUS[0]}"
  systemctl is-active ssh.socket; systemctl is-enabled ssh.socket
  grep -nE '^[[:space:]]*SSHD_OPTS' /etc/default/ssh 2>&1; rc E.default_opts $?
  ps -o args= -C sshd | grep -E 'listener|^/usr/sbin/sshd' | sort | uniq -c

  echo "== F PAM 인증 줄(sshd) · MFA 모듈 사용 파일 수"
  grep -nE '^[[:space:]]*(auth|@include)' /etc/pam.d/sshd; rc F.pam_sshd $?
  echo "MFA 모듈(google_authenticator/oath/duo/u2f/yubico) 쓰는 pam 파일 수=$(grep -lE 'pam_(google_authenticator|oath|duo|u2f|yubico)' /etc/pam.d/* 2>/dev/null | wc -l)"

  echo "== G authorized_keys(키 본문 출력 안 함: 줄 수·지문·해시만)"
  for u in "$U" root; do
    h=$(getent passwd "$u" | cut -d: -f6); [ -n "$h" ] || { echo "-- $u 홈 없음"; continue; }
    for k in "$h/.ssh/authorized_keys" "$h/.ssh/authorized_keys2"; do
      [ -e "$k" ] || { echo "-- $u $(basename "$k") 없음"; continue; }
      echo "-- $u $(basename "$k") $(stat -c '%a %U:%G' "$k") 키줄=$(grep -cvE '^[[:space:]]*(#|$)' "$k") sha256=$(sha256sum < "$k" | cut -d' ' -f1)"
      ssh-keygen -lf "$k" 2>&1 | awk '{print "  " $1, $2, $NF}'
    done
    echo "-- $u .ssh 폴더 $(stat -c '%a %U:%G' "$h/.ssh" 2>&1)"
  done

  echo "== H 방화벽·Docker 경계"
  ufw status verbose; rc H.ufw $?
  iptables -S INPUT 2>&1 | head -40; rc H.ipt_input "${PIPESTATUS[0]}"
  iptables -S DOCKER-USER 2>&1 | head -20; rc H.ipt_docker_user "${PIPESTATUS[0]}"
  docker ps --format '{{.Names}}\t{{.Ports}}' 2>&1 | cut -c1-200; rc H.docker_ps "${PIPESTATUS[0]}"
  echo "IPv6 전역 주소 수=$(ip -6 addr show scope global 2>/dev/null | grep -c inet6)"

  echo "== I 지금 상태(개수만)"
  echo "22 수립 연결 수=$(ss -Htn state established '( sport = :22 )' | wc -l)"
  echo "== 끝"; date "+server %F %T %Z"
}

OUTPUT=$(main 2>&1 | mask)
printf '%s\n' "$OUTPUT"
echo "가림 확인: 전체 IPv4 남은 줄=$(printf '%s\n' "$OUTPUT" | grep -cE '([0-9]{1,3}\.){3}[0-9]{1,3}') · 키 본문 줄=$(printf '%s\n' "$OUTPUT" | grep -cE 'PRIVATE KEY|(ssh-(ed25519|rsa|dss)|ecdsa-sha2-[a-z0-9]+) AAAA')"
