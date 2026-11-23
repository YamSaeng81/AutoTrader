#!/bin/bash
# U0 원문에서 A2 구간(조회 줄 ~ ok-U0 줄)을 기계적으로 잘라 시험 구간을 만든다. stop 은 U0 3행, h256·pf3ok 는 cd8bd76f U변수 원문(17행·296~350행 해시 확인 + 닫는 351행 '}').
set -euo pipefail
U0=$1; UV=$2; OUT=$3
[ "$(sed -n 296,350p "$UV" | sha256sum | cut -d' ' -f1)" = 6fb38fccbedf04aaf8608a5ff26f4a61f36957591809fa3b2bd8e09003b6862a ] || { echo "pf3ok 줄 범위 해시 다름"; exit 2; }
[ "$(sed -n 351p "$UV")" = '}' ] || { echo "pf3ok 닫는 줄(351행) 다름"; exit 2; }
a=$(grep -nE '^(PR=0; )?\$PSQL -At -F' "$U0" | cut -d: -f1); b=$(grep -n '^touch "\$M/ok-U0"' "$U0" | cut -d: -f1)
[ -n "$a" ] && [ -n "$b" ] && [ "$a" -lt "$b" ] || { echo "구간 못 찾음"; exit 2; }
{ echo '('; echo 'set -euo pipefail'; sed -n 3p "$U0"; sed -n 17p "$UV"; sed -n 296,351p "$UV"; sed -n "${a},${b}p" "$U0"; echo ')'; } > "$OUT"
# 시험 구간에 원격·운영 이름이 주석 아닌 줄에 있으면 실패
if grep -vE '^\s*#' "$OUT" | grep -nE '\b(ssh|scp|sftp|curl|wget|nc|telnet|docker)\b|cat-prod|\$DC\b'; then echo "시험 구간에 원격/운영 이름 있음"; exit 2; fi
echo "구간 $a~$b행"
