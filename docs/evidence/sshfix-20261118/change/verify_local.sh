#!/bin/bash
# 개발 PC 에서 실행하는 독립 접속 확인. 설정 파일을 읽지 않고(-F /dev/null: 별칭의 IdentityFile·연결 재사용이 섞이지 않게),
# 연결 재사용(ControlMaster/ControlPath)을 끄고, 지정한 키 하나만 쓴다(IdentitiesOnly). 비밀번호는 보내지 않는다(BatchMode).
# 서버에서 하는 일은 `id -un` 한 번뿐이다. 주소는 화면·파일에 가려서 적는다.
#
#   bash verify_local.sh before <사용자@주소> <포트> <키 경로>                — 적용 전 기준: 키 접속 성공, 알린 방식 기록
#   bash verify_local.sh newkey <사용자@주소> <포트> <새 키 경로>             — addkey 뒤: 새 키 단독 접속 성공(설정 변경 전에 먼저)
#   bash verify_local.sh after  <사용자@주소> <포트> <키 경로> [<새 키 경로>] — reload 뒤: 키 접속 성공 + 알린 방식이 publickey 하나뿐
#
# 종료값: 0 기대대로 · 1 기대와 다름(after 실패면 열어 둔 서버 세션에서 rollback 판단) · 2 입력 이상
set -u
M=${1:-}; H=${2:-}; P=${3:-}; K=${4:-}; K2=${5:-}
[ -n "$M" ] && [ -n "$H" ] && [ -n "$P" ] && [ -n "$K" ] || { echo "입력 필요: 모드 사용자@주소 포트 키"; exit 2; }
case "$M" in before|newkey|after) ;; *) echo "모드 이상"; exit 2;; esac
[[ $H =~ ^[a-z_][a-z0-9_-]*@[A-Za-z0-9.:-]+$ ]] || { echo "사용자@주소 형식 이상"; exit 2; }
[[ $P =~ ^[0-9]{1,5}$ ]] || { echo "포트 형식 이상"; exit 2; }
for k in "$K" ${K2:+"$K2"}; do [ -r "$k" ] || { echo "키 파일 읽기 불가: $k"; exit 2; }; done
O=(-F /dev/null -p "$P" -o ControlMaster=no -o ControlPath=none -o BatchMode=yes -o ConnectTimeout=15)
TMPE=$(mktemp); trap 'rm -f "$TMPE"' EXIT
hide() { sed -E 's/@[A-Za-z0-9.:-]+/@<가림>/g; s/([0-9]{1,3}\.[0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}/\1.x.x/g'; }
bad=0; METH=""
keylogin() {
  local k=$1 out r
  out=$(ssh "${O[@]}" -o IdentitiesOnly=yes -i "$k" "$H" 'id -un' 2>"$TMPE"); r=$?
  echo "키 접속 [$(ssh-keygen -lf "$k" | awk '{print $2}')] rc=$r 사용자=${out:-없음}"
  [ $r -eq 0 ] && [ -n "$out" ] || { bad=1; hide < "$TMPE" | head -3; }
}
methods() {
  local r
  ssh "${O[@]}" -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive -o NumberOfPasswordPrompts=0 "$H" true 2>"$TMPE"; r=$?
  METH=$(grep -oE 'Permission denied \([a-z,-]+\)' "$TMPE"); echo "방식 탐지 rc=$r · ${METH:-알린 방식 줄 없음}"
}
date -u "+local_utc start %F %T · 모드 $M"
case "$M" in
  before) keylogin "$K"; methods ;;
  newkey) keylogin "$K" ;;
  after)  keylogin "$K"; [ -n "$K2" ] && keylogin "$K2"; methods
          [ "$METH" = "Permission denied (publickey)" ] || { bad=1; echo "기대 'Permission denied (publickey)' 아님"; } ;;
esac
date -u "+local_utc end %F %T"; echo "결과=$([ $bad -eq 0 ] && echo 기대대로 || echo 기대와다름)"
exit $bad
