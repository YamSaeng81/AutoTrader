#!/bin/bash
# 개발 PC 에서 1회: 서버가 개발 계정(개발 PC 출발 조건)에 내놓는 인증 방식 목록만 관측한다.
# 공개키를 끄고 BatchMode 로 비밀번호를 묻지 않으므로 어떤 비밀값도 보내지 않는다. 연결 재사용 없음.
H=${1:?접속 별칭 필요}
date -u "+local_utc start %F %T"
ssh -o ControlMaster=no -o ControlPath=none -o BatchMode=yes -o ConnectTimeout=15 \
    -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive \
    -o NumberOfPasswordPrompts=0 "$H" true 2> probe-stderr.txt; r=$?
echo "ssh rc=$r"; date -u "+local_utc end %F %T"
echo "서버가 알린 방식 줄: $(grep -oE 'Permission denied \([a-z,-]+\)' probe-stderr.txt || echo 없음)"
