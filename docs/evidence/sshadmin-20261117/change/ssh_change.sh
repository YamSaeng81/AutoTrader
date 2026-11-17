#!/bin/bash
# 총괄 v1.180 §4-3 — SSH 변경·복구 묶음. 🔴 운영자 확인 1회 전에는 precheck/check 외 실행 금지.
# 서버 안에서 root 로, 이 파일과 00-cat-hardening.conf 가 같은 폴더에 있는 상태로 부른다. 단계마다 한 번 부르고 결과를 본 뒤 다음으로.
# 자동 재시도·자동 다음 단계 없음. 단, stage 가 검사에 실패하면 방금 놓은 파일 하나만 바로 지운다(reload 전이라 실행 중 sshd 는 그대로).
#
#   sudo env STAMP=YYYYMMDD-HHMM EXP_MAIN_SHA=<sshd_config sha256> EXP_DLIST_SHA=<sshd_config.d 목록 해시> EXP_AK_SHA=<authorized_keys sha256> \
#            DEV_ADDR=<개발PC 출발주소> LADDR=<서버 수신주소> LPORT=<수신포트> EXT_ADDR=<외부 예시주소> \
#            [NEW_PUB=<새 공개키 파일> EXP_NEW_FP=<SHA256:…>] bash ssh_change.sh <단계>
#
#   단계(순서): precheck → backup → [addkey → 개발 PC verify_local.sh newkey] → stage → reload → 개발 PC verify_local.sh after
#   복구: rollback(설정 파일만) · rollback-key(authorized_keys 를 백업으로)
#   check: 읽기만 — 지금 디스크 설정의 조건별 기대값 대조(적용 전에는 실패가 정상)
#
# 종료값: 0 성공 · 2 입력 이상 · 3 root 아님 · 4 검사 실패(reload 안 함) · 5 덮어쓰기 거절(백업 폴더 있음 등) · 7 전제 불일치 · 8 쓰기/복사 실패
# 바꾸는 경로는 아래 상수뿐이다. 변수 경로는 형식 검사 뒤에만 쓴다.
set -u
export LC_ALL=C
HERE=$(cd "$(dirname "$0")" && pwd)
U=manicstr
CONF_SRC="$HERE/00-cat-hardening.conf"
CONF_SHA=0a2933a644b044700eabce5b8cfadac98c963a9383147e84a66cc8caf92e367b
DST=/etc/ssh/sshd_config.d/00-cat-hardening.conf
MAIN=/etc/ssh/sshd_config
DDIR=/etc/ssh/sshd_config.d
AK=/home/manicstr/.ssh/authorized_keys
STEP=${1:-}

say() { printf '%s %s %s\n' "$(date '+%F %T %Z')" "[$STEP]" "$*"; }
die() { say "STOP rc=$1: $2"; exit "$1"; }
[ "$(id -u)" = 0 ] || die 3 "root 필요"
case "$STEP" in precheck|backup|addkey|stage|check|reload|rollback|rollback-key) ;; *) die 2 "단계 이름 이상: '$STEP'";; esac

need() { local n v; for n in "$@"; do v=${!n:-}; [ -n "$v" ] || die 2 "$n 비었음"; done; }
need STAMP DEV_ADDR LADDR LPORT EXT_ADDR
[[ $STAMP =~ ^[0-9]{8}-[0-9]{4}$ ]] || die 2 "STAMP 형식(YYYYMMDD-HHMM)"
for n in DEV_ADDR LADDR EXT_ADDR; do [[ ${!n} =~ ^[0-9A-Fa-f:.]{2,45}$ ]] || die 2 "$n 형식"; done
[[ $LPORT =~ ^[0-9]{1,5}$ ]] || die 2 "LPORT 형식"
[ "$EXT_ADDR" != "$DEV_ADDR" ] || die 2 "EXT_ADDR=DEV_ADDR"
BK=/root/sshd-bak-$STAMP
[[ $BK =~ ^/root/sshd-bak-[0-9]{8}-[0-9]{4}$ ]] || die 2 "백업 경로 형식"

sha() { sha256sum < "$1" | cut -d' ' -f1; }
dlist() { (cd "$DDIR" && for f in *; do [ -f "$f" ] && printf '%s %s\n' "$f" "$(sha256sum < "$f" | cut -d' ' -f1)"; done) | sort | sha256sum | cut -d' ' -f1; }

T=$(mktemp -d /tmp/sshchg.XXXXXX) || die 8 "임시 폴더"
trap 'case "$T" in /tmp/sshchg.??????) rm -rf -- "$T";; esac' EXIT

# 조건별 기대값 대조. 반환 0=모두 기대값, 4=하나라도 다름/ sshd -T 실패
check_all() {
  local bad=0 n c r
  /usr/sbin/sshd -t > "$T/t" 2>&1; r=$?; say "sshd -t rc=$r"; [ $r -eq 0 ] || { head -5 "$T/t"; return 4; }
  for n in dev dev_ext root_ext root_dev nouser; do
    case $n in
      dev)      c="user=$U,host=$DEV_ADDR,addr=$DEV_ADDR,laddr=$LADDR,lport=$LPORT" ;;
      dev_ext)  c="user=$U,host=$EXT_ADDR,addr=$EXT_ADDR,laddr=$LADDR,lport=$LPORT" ;;
      root_ext) c="user=root,host=$EXT_ADDR,addr=$EXT_ADDR,laddr=$LADDR,lport=$LPORT" ;;
      root_dev) c="user=root,host=$DEV_ADDR,addr=$DEV_ADDR,laddr=$LADDR,lport=$LPORT" ;;
      nouser)   c="user=nosuchuser-catchk,host=$EXT_ADDR,addr=$EXT_ADDR,laddr=$LADDR,lport=$LPORT" ;;
    esac
    /usr/sbin/sshd -T -C "$c" > "$T/$n" 2> "$T/$n.err"; r=$?
    say "sshd -T[$n] rc=$r"; [ $r -eq 0 ] || { bad=1; head -3 "$T/$n.err"; continue; }
    for kv in "passwordauthentication no" "kbdinteractiveauthentication no" "authenticationmethods publickey" "permitrootlogin no" "pubkeyauthentication yes"; do
      grep -qxF "$kv" "$T/$n"; r=$?
      case $r in 0) ;; 1) bad=1; say "  [$n] 기대 '$kv' 아님 → 실제: $(grep -E "^${kv%% *} " "$T/$n" | head -2 | tr '\n' ' ')";; *) bad=1; say "  [$n] grep 검사실패 rc=$r";; esac
    done
    if [ "$n" = dev ] || [ "$n" = dev_ext ]; then
      a=$(grep -E '^allowusers ' "$T/$n"); [ "$a" = "allowusers $U" ] || { bad=1; say "  [$n] allowusers 가 '$U' 하나가 아님 → $(printf '%s' "$a" | tr '\n' ' ')"; }
      d=$(grep -cE '^(denyusers|denygroups|allowgroups) ' "$T/$n"); [ "$d" = 0 ] || say "  [$n] 참고: deny/allowgroups 줄 $d — 승인 계정에 영향 없는지 사람 확인"
    fi
  done
  [ $bad -eq 0 ] && { say "조건별 기대값 모두 일치"; return 0; }; say "기대값 불일치 있음"; return 4
}

pre_files() {   # sshd 설정 파일 전제(백업 시점과 같은 상태)
  need EXP_MAIN_SHA EXP_DLIST_SHA
  [ "$(sha "$MAIN")" = "$EXP_MAIN_SHA" ] || die 7 "sshd_config 해시가 운영자 읽기 값과 다름"
  [ ! -e "$DST" ] || die 7 "$DST 이미 있음"
  [ "$(dlist)" = "$EXP_DLIST_SHA" ] || die 7 "sshd_config.d 목록 해시가 운영자 읽기 값과 다름"
  [ "$(sha "$CONF_SRC")" = "$CONF_SHA" ] || die 7 "묶음 00-cat-hardening.conf 해시가 고정값과 다름"
}
bk_ok() { [ -d "$BK" ] && (cd "$BK" && sha256sum --quiet -c SHA256SUMS) || die 7 "백업 $BK 없음/검증 실패"; }

case "$STEP" in
precheck)   # 읽기만
  pre_files; need EXP_AK_SHA; [ "$(sha "$AK")" = "$EXP_AK_SHA" ] || die 7 "authorized_keys 해시가 운영자 읽기 값과 다름"
  say "파일 전제 일치(sshd_config·목록·00 없음·묶음 conf·authorized_keys)"
  check_all; say "참고: 적용 전 check 불일치는 정상(현재 값 기록용)"; exit 0 ;;
check) check_all; exit $? ;;
backup)
  pre_files; need EXP_AK_SHA; [ "$(sha "$AK")" = "$EXP_AK_SHA" ] || die 7 "authorized_keys 해시 다름"
  [ ! -e "$BK" ] || die 5 "$BK 이미 있음 — 덮어쓰지 않음"
  install -d -m 700 -o root -g root "$BK" && cp -a "$MAIN" "$BK/sshd_config" && cp -a "$DDIR" "$BK/sshd_config.d" && cp -a "$AK" "$BK/authorized_keys" || die 8 "백업 복사 실패"
  (cd "$BK" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS) || die 8 "백업 해시 쓰기 실패"
  bk_ok; say "백업 $BK 확인($(grep -c . "$BK/SHA256SUMS")파일)"; exit 0 ;;
addkey)   # 새 키 추가(별도 승인 항목). 개인키는 서버에 오지 않는다 — 공개키 한 줄만
  bk_ok; need NEW_PUB EXP_NEW_FP
  [ -f "$NEW_PUB" ] || die 2 "NEW_PUB 파일 없음"
  [ "$(grep -cvE '^[[:space:]]*(#|$)' "$NEW_PUB")" = 1 ] || die 2 "NEW_PUB 은 키 한 줄이어야 함"
  fp=$(ssh-keygen -lf "$NEW_PUB" | awk '{print $2}'); [ "$fp" = "$EXP_NEW_FP" ] || die 7 "새 키 지문이 승인 값과 다름($fp)"
  ssh-keygen -lf "$AK" | awk '{print $2}' | grep -qxF "$fp" && die 7 "이미 있는 키"
  [ "$(sha "$AK")" = "$(sha "$BK/authorized_keys")" ] || die 7 "authorized_keys 가 백업과 다름 — 사람 확인"
  n0=$(grep -cvE '^[[:space:]]*(#|$)' "$AK"); m0=$(stat -c '%a %U:%G' "$AK")
  grep -vE '^[[:space:]]*(#|$)' "$NEW_PUB" >> "$AK" || die 8 "추가 실패 — rollback-key 로 복구"
  n1=$(grep -cvE '^[[:space:]]*(#|$)' "$AK"); m1=$(stat -c '%a %U:%G' "$AK")
  say "키 줄 $n0 → $n1, 권한 $m0 → $m1, 새 sha256=$(sha "$AK")"
  [ $((n0 + 1)) = "$n1" ] && [ "$m0" = "$m1" ] || die 8 "줄 수/권한 이상 — rollback-key 로 복구"
  say "개발 PC 에서 verify_local.sh newkey 로 새 키 독립 접속을 먼저 확인"; exit 0 ;;
stage)
  bk_ok; pre_files
  install -o root -g root -m 0644 "$CONF_SRC" "$DST" || die 8 "설치 실패"
  [ "$(sha "$DST")" = "$CONF_SHA" ] || { rm -f -- "$DST"; die 8 "설치 파일 해시 다름 — 지움"; }
  say "놓음: $DST $(stat -c '%a %U:%G' "$DST")"
  if check_all; then say "reload 전 단계 끝 — 결과 보고 뒤 reload"; exit 0; fi
  rm -f -- "$DST" && say "검사 실패 → 방금 놓은 $DST 지움(reload 안 함)"; /usr/sbin/sshd -t; say "지운 뒤 sshd -t rc=$?"; exit 4 ;;
reload)
  bk_ok; [ -f "$DST" ] && [ "$(sha "$DST")" = "$CONF_SHA" ] || die 7 "놓인 설정 파일이 없거나 해시 다름"
  check_all || die 4 "reload 직전 검사 실패 — reload 안 함"
  p0=$(systemctl show -p MainPID --value ssh.service)
  systemctl reload ssh.service; r=$?; sleep 2
  say "reload rc=$r · is-active=$(systemctl is-active ssh.service) · MainPID $p0 → $(systemctl show -p MainPID --value ssh.service)"
  [ $r -eq 0 ] || die 4 "reload 실패 — 열어 둔 세션에서 상태 확인, 필요하면 rollback"
  say "열어 둔 세션은 닫지 말고 개발 PC 에서 verify_local.sh after"; exit 0 ;;
rollback)   # 설정 파일만 원래대로(백업과 같은지 확인)
  bk_ok
  if [ -e "$DST" ]; then rm -f -- "$DST" || die 8 "지우기 실패"; say "지움: $DST"; else say "$DST 없음(이미 원래)"; fi
  [ "$(sha "$MAIN")" = "$(sha "$BK/sshd_config")" ] || say "주의: sshd_config 가 백업과 다름 — 이 묶음 밖 변경, 사람 판단"
  /usr/sbin/sshd -t; r=$?; say "sshd -t rc=$r"; [ $r -eq 0 ] || die 4 "구문 실패 — reload 안 함(실행 중 sshd 는 그대로)"
  systemctl reload ssh.service; r=$?; say "reload rc=$r · is-active=$(systemctl is-active ssh.service)"; exit $r ;;
rollback-key)
  bk_ok
  cp -p "$BK/authorized_keys" "$AK" || die 8 "복사 실패"
  [ "$(sha "$AK")" = "$(sha "$BK/authorized_keys")" ] || die 8 "복구 뒤 해시 다름"
  say "authorized_keys 백업으로 복구 $(stat -c '%a %U:%G' "$AK") sha256=$(sha "$AK")"; exit 0 ;;
esac
