#!/bin/bash
# SSH 변경·복구 묶음 v2 (총괄 v1.181 §3 — 복구/상태 관문 최소 보완). 🔴 운영자 확인 1회 전에는 precheck/check 외 실행 금지.
# v1 원문은 sshadmin-20261117/change/ssh_change.sh(커밋 bc5a39d9)에 그대로 둔다. 00-cat-hardening.conf·verify_local.sh 는 v1 과 같은 바이트.
# 서버 안에서 root 로, 이 파일과 00-cat-hardening.conf 가 같은 폴더에 있는 상태로 부른다. 단계마다 한 번 부르고 결과를 본 뒤 다음으로.
# 자동 재시도·자동 다음 단계 없음.
#
#   sudo env STAMP=YYYYMMDD-HHMM EXP_MAIN_SHA=<sshd_config sha256> EXP_DLIST_SHA=<sshd_config.d 목록 해시> EXP_AK_SHA=<authorized_keys sha256> \
#            DEV_ADDR=<개발PC 출발주소> LADDR=<서버 수신주소> LPORT=<수신포트> EXT_ADDR=<외부 예시주소> \
#            [NEW_PUB=<새 공개키 파일> EXP_NEW_FP=<SHA256:…>] bash ssh_change.sh <단계>
#
#   단계(순서): precheck → backup → [addkey → 개발 PC verify_local.sh newkey] → stage → reload → 개발 PC verify_local.sh after
#   복구: rollback(설정 파일만) · rollback-key(authorized_keys 를 백업으로)
#   check: 읽기만 — 지금 디스크 설정의 조건별 기대값 대조(적용 전에는 실패가 정상)
#
# 종료값: 0 성공 · 2 입력 이상 · 3 root 아님 · 4 검사/구문/reload 명령 실패 · 5 덮어쓰기 거절(백업 폴더 있음 등)
#        · 6 서비스 비활성/실패(is-active 가 active 아님) · 7 전제 불일치·제3 내용·조회 불명(변경 명령 0) · 8 쓰기/복사/기록 실패
#        · 9 서비스 상태 조회 불명(is-active/MainPID 를 읽지 못함)
#
# v1 → v2 (총괄 v1.181 §3 관측 5건):
#   - 이 작업의 산출물 기록: backup 이 $BK/state/ 를 만들고, addkey 직후 authorized_keys 해시·권한·지문 목록, stage 직후 놓은 파일 해시·
#     sshd_config 해시·sshd_config.d 목록 해시를 적는다. 기록 실패는 8 이며 성공으로 가리지 않는다.
#   - rollback: sshd_config 가 백업과 다르거나, 00 파일이 이번 stage 기록과 다른 내용(제3)이거나, 00 을 뺀 sshd_config.d 목록이 백업과 다르거나,
#     어느 하나라도 읽지 못하면(조회 불명) **지우기·reload 없이 7**. v1 은 제3 00 파일을 지우고, sshd_config 차이는 주의만 내고 reload 했다.
#   - rollback-key: 현재 파일이 백업과 같으면 쓰지 않고 0, addkey 기록 해시와 같을 때만 백업으로 되돌린다. 그 밖(후속 키·제3)은 **덮어쓰지 않고 7**.
#   - reload/rollback: 명령 rc 와 별도로 is-active=active(rc0)·MainPID 숫자를 확인한다. 비활성 6, 조회 불명 9. v1 은 inactive 를 출력하고 0 이었다.
#   - stage 검사 실패 때 지우는 파일도 놓은 바이트(고정 해시)인지 확인하고, 아니면 지우지 않고 7.
#   - 백업 목록: find/sort/sha256sum 종료값을 각각 보고, 빈 목록·필수 항목 누락·목록 줄 수≠파일 수를 검증 실패로 본다.
#   - 시험 대역이 바꾸는 것은 없다: 경로·명령은 아래 상수 그대로이며 시험은 일회용 컨테이너 안 실제 경로에서 한다.
set -u
export LC_ALL=C
HERE=$(cd "$(dirname "$0")" && pwd)
U=manicstr
CONF_SRC="$HERE/00-cat-hardening.conf"
CONF_SHA=0a2933a644b044700eabce5b8cfadac98c963a9383147e84a66cc8caf92e367b
CONF_NAME=00-cat-hardening.conf
DST=/etc/ssh/sshd_config.d/$CONF_NAME
MAIN=/etc/ssh/sshd_config
DDIR=/etc/ssh/sshd_config.d
AK=/home/manicstr/.ssh/authorized_keys
SVC=ssh.service
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
ST=$BK/state

# 읽기 실패를 빈 값으로 두지 않는다: 실패면 UNKNOWN 을 내고 1
fsha() { local o; [ -f "$1" ] && [ -r "$1" ] || { echo UNKNOWN; return 1; }; o=$(sha256sum < "$1") || { echo UNKNOWN; return 1; }; echo "${o%% *}"; }
# 목록 해시(v1·admin_read 와 같은 형식: "이름 sha256" 정렬). $1 폴더, $2 뺄 이름(선택)
dlist() {
  local d=${1:-$DDIR} ex=${2:-} f b h lines=""
  [ -d "$d" ] && [ -r "$d" ] && [ -x "$d" ] || { echo UNKNOWN; return 1; }
  for f in "$d"/*; do
    b=${f##*/}; [ -f "$f" ] || continue; [ -n "$ex" ] && [ "$b" = "$ex" ] && continue
    h=$(fsha "$f") || { echo UNKNOWN; return 1; }; lines+="$b $h"$'\n'
  done
  printf '%s' "$lines" | sort | sha256sum | cut -d' ' -f1
}
perm() { stat -c '%a %U:%G' "$1" 2>/dev/null || echo UNKNOWN; }
fps() { local o; o=$(ssh-keygen -lf "$1" 2>/dev/null) || { echo UNKNOWN; return 1; }; printf '%s\n' "$o" | awk '{print $2}' | sort | sha256sum | cut -d' ' -f1; }

T=$(mktemp -d /tmp/sshchg.XXXXXX) || die 8 "임시 폴더"
trap 'case "$T" in /tmp/sshchg.??????) rm -rf -- "$T";; esac' EXIT

# 이 작업의 산출물 기록(임시→이름 바꾸기→다시 읽어 대조). 실패는 8
rec() {
  local k=$1 v=$2
  [ -d "$ST" ] || die 8 "상태 기록 폴더 $ST 없음"
  { printf '%s\n' "$v" > "$ST/$k.tmp" && mv -f "$ST/$k.tmp" "$ST/$k" && [ "$(cat "$ST/$k")" = "$v" ]; } || die 8 "상태 기록 실패: $k"
  say "기록 $k=$v"
}
get() { cat "$ST/$1" 2>/dev/null; }

# 서비스 상태: 0 active·MainPID 숫자, 6 비활성/실패, 9 조회 불명
svc_state() {
  local st sr pid pr
  st=$(systemctl is-active "$SVC" 2>/dev/null); sr=$?
  pid=$(systemctl show -p MainPID --value "$SVC" 2>/dev/null); pr=$?
  say "서비스 is-active='$st'(rc=$sr) · MainPID='$pid'(rc=$pr)"
  if [ $sr -eq 0 ] && [ "$st" = active ] && [ $pr -eq 0 ] && [[ $pid =~ ^[1-9][0-9]*$ ]]; then return 0; fi
  case "$st" in inactive|failed|deactivating|activating|reloading) return 6 ;; esac
  return 9
}
do_reload() {   # reload 명령 rc 와 상태를 따로 보고 최종 종료값을 정한다
  local p0 r s
  p0=$(systemctl show -p MainPID --value "$SVC" 2>/dev/null)
  systemctl reload "$SVC"; r=$?; sleep 2
  say "reload 명령 rc=$r · MainPID 이전=$p0"
  svc_state; s=$?
  [ $r -eq 0 ] || die 4 "reload 명령 실패(상태 판정 $s) — 열어 둔 세션에서 확인"
  case $s in 0) return 0 ;; 6) die 6 "reload 뒤 서비스가 active 아님 — 성공 아님, 열어 둔 세션에서 확인" ;;
             *) die 9 "reload 뒤 서비스 상태 조회 불명 — 성공 아님, 열어 둔 세션에서 확인" ;; esac
}

# 조건별 기대값 대조. 반환 0=모두 기대값, 4=하나라도 다름/ sshd -T 실패 (v1 과 같음)
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

pre_files() {   # sshd 설정 파일 전제(운영자 읽기 값과 같은 상태)
  need EXP_MAIN_SHA EXP_DLIST_SHA
  [ "$(fsha "$MAIN")" = "$EXP_MAIN_SHA" ] || die 7 "sshd_config 해시가 운영자 읽기 값과 다름/조회 불명"
  [ ! -e "$DST" ] || die 7 "$DST 이미 있음"
  [ "$(dlist)" = "$EXP_DLIST_SHA" ] || die 7 "sshd_config.d 목록 해시가 운영자 읽기 값과 다름/조회 불명"
  [ "$(fsha "$CONF_SRC")" = "$CONF_SHA" ] || die 7 "묶음 00-cat-hardening.conf 해시가 고정값과 다름"
}
bk_ok() {   # 백업 체크섬 + 목록 완전성(빈 목록·필수 누락·목록 줄 수≠파일 수는 실패)
  local nl nf
  [ -d "$BK" ] || die 7 "백업 $BK 없음"
  [ -s "$BK/SHA256SUMS" ] || die 7 "백업 목록 없음/빈 파일"
  (cd "$BK" && sha256sum --quiet --strict -c SHA256SUMS) || die 7 "백업 검증 실패"
  grep -qE '^[0-9a-f]{64}  \./sshd_config$' "$BK/SHA256SUMS" && grep -qE '^[0-9a-f]{64}  \./authorized_keys$' "$BK/SHA256SUMS" || die 7 "백업 목록에 필수 항목 없음"
  nl=$(grep -c . "$BK/SHA256SUMS"); nf=$(cd "$BK" && find . -type f ! -name SHA256SUMS ! -path './state/*' | wc -l)
  [ "$nl" = "$nf" ] || die 7 "백업 목록 줄 수($nl) ≠ 백업 파일 수($nf)"
  [ -d "$ST" ] || die 7 "상태 기록 폴더 없음(v2 backup 이 아님)"
}

case "$STEP" in
precheck)   # 읽기만
  pre_files; need EXP_AK_SHA; [ "$(fsha "$AK")" = "$EXP_AK_SHA" ] || die 7 "authorized_keys 해시가 운영자 읽기 값과 다름/조회 불명"
  say "파일 전제 일치(sshd_config·목록·00 없음·묶음 conf·authorized_keys)"
  svc_state; say "서비스 상태 판정 rc=$?(참고, 읽기만)"
  check_all; say "참고: 적용 전 check 불일치는 정상(현재 값 기록용)"; exit 0 ;;
check) check_all; exit $? ;;
backup)
  pre_files; need EXP_AK_SHA; [ "$(fsha "$AK")" = "$EXP_AK_SHA" ] || die 7 "authorized_keys 해시 다름/조회 불명"
  [ ! -e "$BK" ] || die 5 "$BK 이미 있음 — 덮어쓰지 않음"
  install -d -m 700 -o root -g root "$BK" && cp -a "$MAIN" "$BK/sshd_config" && cp -a "$DDIR" "$BK/sshd_config.d" && cp -a "$AK" "$BK/authorized_keys" || die 8 "백업 복사 실패"
  (cd "$BK" || exit 1; find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 -r sha256sum > SHA256SUMS; s="${PIPESTATUS[*]}"
   echo "백업 목록 find/sort/sha256sum rc=$s"; [ "$s" = "0 0 0" ]) || die 8 "백업 목록 생성 실패"
  install -d -m 700 -o root -g root "$ST" || die 8 "상태 기록 폴더 만들기 실패"
  bk_ok
  rec bk.main "$(fsha "$BK/sshd_config")"; rec bk.dlist "$(dlist "$BK/sshd_config.d")"; rec bk.ak "$(fsha "$BK/authorized_keys")"
  [ "$(get bk.main)" = "$EXP_MAIN_SHA" ] && [ "$(get bk.dlist)" = "$EXP_DLIST_SHA" ] && [ "$(get bk.ak)" = "$EXP_AK_SHA" ] || die 8 "백업 기록 값이 운영자 읽기 값과 다름"
  say "백업 $BK 확인($(grep -c . "$BK/SHA256SUMS")파일)"; exit 0 ;;
addkey)   # 새 키 추가(별도 승인 항목). 개인키는 서버에 오지 않는다 — 공개키 한 줄만
  bk_ok; need NEW_PUB EXP_NEW_FP
  [ -z "$(get ak.added)" ] || die 7 "이미 addkey 기록이 있음 — 사람 확인"
  [ -f "$NEW_PUB" ] || die 2 "NEW_PUB 파일 없음"
  [ "$(grep -cvE '^[[:space:]]*(#|$)' "$NEW_PUB")" = 1 ] || die 2 "NEW_PUB 은 키 한 줄이어야 함"
  fp=$(ssh-keygen -lf "$NEW_PUB" | awk '{print $2}'); [ "$fp" = "$EXP_NEW_FP" ] || die 7 "새 키 지문이 승인 값과 다름($fp)"
  ssh-keygen -lf "$AK" | awk '{print $2}' | grep -qxF "$fp" && die 7 "이미 있는 키"
  [ "$(fsha "$AK")" = "$(get bk.ak)" ] || die 7 "authorized_keys 가 백업과 다름/조회 불명 — 사람 확인"
  n0=$(grep -cvE '^[[:space:]]*(#|$)' "$AK"); m0=$(perm "$AK")
  grep -vE '^[[:space:]]*(#|$)' "$NEW_PUB" >> "$AK" || die 8 "추가 실패 — 현재 파일 출처 판정 필요(기록 없음 → rollback-key 는 7 로 멈춤)"
  n1=$(grep -cvE '^[[:space:]]*(#|$)' "$AK"); m1=$(perm "$AK")
  say "키 줄 $n0 → $n1, 권한 $m0 → $m1"
  rec ak.added "$(fsha "$AK")"; rec ak.added.perm "$m1"; rec ak.added.fps "$(fps "$AK")"; rec ak.added.newfp "$fp"
  [ $((n0 + 1)) = "$n1" ] && [ "$m0" = "$m1" ] || die 8 "줄 수/권한 이상 — rollback-key 로 복구"
  say "개발 PC 에서 verify_local.sh newkey 로 새 키 독립 접속을 먼저 확인"; exit 0 ;;
stage)
  bk_ok; pre_files
  [ -z "$(get staged.dst)" ] || die 7 "이미 stage 기록이 있음 — 새 STAMP·사람 판단"
  [ "$(fsha "$MAIN")" = "$(get bk.main)" ] && [ "$(dlist)" = "$(get bk.dlist)" ] || die 7 "설정이 백업 시점과 다름/조회 불명"
  install -o root -g root -m 0644 "$CONF_SRC" "$DST" || die 8 "설치 실패 — 현재 $DST 출처 판정 필요(기록 없음 → rollback 은 7 로 멈춤)"
  s=$(fsha "$DST"); [ "$s" = "$CONF_SHA" ] || die 8 "설치 파일 해시 다름($s) — 지우지 않음, 사람 판단"
  rec staged.dst "$s"; rec staged.main "$(fsha "$MAIN")"; rec staged.dlist "$(dlist)"; rec staged.perm "$(perm "$DST")"
  say "놓음: $DST $(get staged.perm)"
  if check_all; then say "reload 전 단계 끝 — 결과 보고 뒤 reload"; exit 0; fi
  s=$(fsha "$DST")
  [ "$s" = "$CONF_SHA" ] || die 7 "검사 실패 + $DST 가 놓은 바이트가 아님($s) — 지우지 않음, reload 안 함"
  rm -f -- "$DST" || die 8 "검사 실패 뒤 지우기 실패"
  rec staged.dst "removed-after-check-fail"
  [ "$(dlist)" = "$(get bk.dlist)" ] || die 7 "지운 뒤 목록이 백업과 다름 — 사람 판단"
  say "검사 실패 → 방금 놓은 $DST 지움(reload 안 함)"; /usr/sbin/sshd -t; say "지운 뒤 sshd -t rc=$?"; exit 4 ;;
reload)
  bk_ok
  [ "$(get staged.dst)" = "$CONF_SHA" ] || die 7 "stage 기록 없음/이미 지움"
  [ "$(fsha "$DST")" = "$CONF_SHA" ] || die 7 "놓인 설정 파일이 없거나 해시 다름/조회 불명"
  [ "$(fsha "$MAIN")" = "$(get staged.main)" ] && [ "$(dlist)" = "$(get staged.dlist)" ] || die 7 "stage 뒤 설정·목록이 바뀜/조회 불명 — reload 안 함"
  check_all || die 4 "reload 직전 검사 실패 — reload 안 함"
  do_reload; rec reloaded 1
  say "열어 둔 세션은 닫지 말고 개발 PC 에서 verify_local.sh after"; exit 0 ;;
rollback)   # 설정 파일만 원래대로 — 이 작업 산출물인지 먼저 판정, 하나라도 제3/불명이면 변경 0
  bk_ok
  m=$(fsha "$MAIN"); [ "$m" != UNKNOWN ] || die 7 "sshd_config 조회 불명 — 변경 없음"
  [ "$m" = "$(get bk.main)" ] || die 7 "sshd_config 가 백업과 다름(이 묶음 밖 변경) — 변경·reload 없음, 사람 판단"
  o=$(dlist "$DDIR" "$CONF_NAME"); [ "$o" != UNKNOWN ] || die 7 "sshd_config.d 조회 불명 — 변경 없음"
  [ "$o" = "$(get bk.dlist)" ] || die 7 "$CONF_NAME 밖 sshd_config.d 목록이 백업과 다름(제3 파일) — 변경·reload 없음"
  if [ -e "$DST" ] || [ -L "$DST" ]; then
    s=$(fsha "$DST"); [ "$s" != UNKNOWN ] || die 7 "$DST 조회 불명 — 변경 없음"
    [ "$s" = "$CONF_SHA" ] && [ "$(get staged.dst)" = "$CONF_SHA" ] || die 7 "$DST 가 이번 stage 산출물이 아님(제3 내용 또는 기록 없음) — 지우지 않음, reload 없음"
    rm -f -- "$DST" || die 8 "지우기 실패"; rec staged.dst "removed-by-rollback"; say "지움: $DST(이번 stage 산출물 확인 뒤)"
  else
    say "$DST 없음(이미 원래)"
  fi
  [ ! -e "$DST" ] && [ "$(dlist)" = "$(get bk.dlist)" ] || die 7 "되돌린 뒤 목록이 백업과 다름 — reload 없음"
  /usr/sbin/sshd -t; r=$?; say "sshd -t rc=$r"; [ $r -eq 0 ] || die 4 "구문 실패 — reload 안 함(실행 중 sshd 는 그대로)"
  do_reload; say "설정 되돌림·reload·서비스 active 확인"; exit 0 ;;
rollback-key)   # 이번 addkey 산출물일 때만 백업으로. 후속 키·제3 내용은 덮어쓰지 않는다
  bk_ok
  c=$(fsha "$AK"); [ "$c" != UNKNOWN ] || die 7 "authorized_keys 조회 불명 — 변경 없음"
  if [ "$c" = "$(get bk.ak)" ]; then say "authorized_keys 가 이미 백업과 같음 — 쓰지 않음"; exit 0; fi
  a=$(get ak.added); [ -n "$a" ] || die 7 "addkey 기록 없음 — 현재 내용 출처 불명, 덮어쓰지 않음"
  [ "$c" = "$a" ] || die 7 "authorized_keys 가 addkey 직후와 다름(후속 키/제3 변경, 키 줄 $(grep -cvE '^[[:space:]]*(#|$)' "$AK")) — 덮어쓰지 않음, 사람 판단"
  [ "$(fsha "$AK")" = "$a" ] || die 7 "직전 재확인 불일치 — 덮어쓰지 않음"
  cp -p "$BK/authorized_keys" "$AK" || die 8 "복사 실패"
  [ "$(fsha "$AK")" = "$(get bk.ak)" ] || die 8 "복구 뒤 해시 다름"
  [ "$(perm "$AK")" = "$(perm "$BK/authorized_keys")" ] || die 8 "복구 뒤 권한/소유 다름"
  rec ak.added "restored"; say "authorized_keys 백업으로 복구 $(perm "$AK") sha256=$(fsha "$AK")"; exit 0 ;;
esac
