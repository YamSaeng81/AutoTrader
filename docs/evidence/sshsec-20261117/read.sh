#!/bin/bash
# 로컬에서 부른다: remote.sh 를 표준 입력으로 서버에 보내 읽기만 한다. 실제 시각·종료값을 따로 남긴다.
L=${1:-.}
{ date -u "+local_utc start %F %T"; } > "$L/run-time.txt"
ssh -o BatchMode=yes cat-prod 'bash -s' < "$L/remote.sh" > "$L/out-raw.txt" 2> "$L/read-stderr.txt"; r=$?
echo "ssh rc=$r" >> "$L/run-time.txt"; date -u "+local_utc end %F %T" >> "$L/run-time.txt"
exit $r
