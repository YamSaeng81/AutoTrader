#!/usr/bin/env bash
# steps-20261118.sh 관문 시험 — 고정 경로 R·TOK 만 임시 경로로 바꾼 사본에서, run_bundle 을 부르기 전 멈춤만 본다. 운영 접속 없음.
set -u
SRC=$(cd "$(dirname "$0")/.." && pwd); W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
R="$W/ab-run"; mkdir -p "$R"; cp -r "$SRC/bundle" "$SRC/create" "$SRC/tool" "$SRC/sma20" "$SRC/preflight" "$R/"; RP=$(cd "$R" && pwd -P)
mk() { sed -e "s|^R=.*|R=$RP|" -e "s|^TOK=.*|TOK=$W/tok|" -e "s|^AB_DATE=.*|AB_DATE=${1}|" "$SRC/steps-20261118.sh" > "$R/s.sh"; }
pass=0; fail=0
t() { local name=$1 want=$2; shift 2; (cd "$R" && bash s.sh "$@") > "$W/o" 2>&1; local r=$?
      if [ $r -eq "$want" ]; then pass=$((pass+1)); echo "PASS $name rc=$r"; else fail=$((fail+1)); echo "FAIL $name rc=$r 기대 $want"; sed 's/^/  /' "$W/o"; fi; }
TODAY=$(TZ=Asia/Seoul date +%F)
mk "$TODAY"
t "S1 단계 인자 이상 → 20" 20 9
(cd "$W" && bash "$R/s.sh" 0) > /dev/null 2>&1; r=$?; [ $r -eq 21 ] && { pass=$((pass+1)); echo "PASS S2 다른 폴더에서 → 21 rc=$r"; } || { fail=$((fail+1)); echo "FAIL S2 rc=$r"; }
t "S3 토큰 파일 없음 → 23" 23 0
mk 2000-01-01; t "S4 서버 KST 날짜≠승인 날짜 → 22(보내지 않음)" 22 2
mk "$TODAY"; printf '\n' >> "$R/create/ab_create.py"; t "S5 묶음 파일 변조 → 21" 21 2
echo "통과 $pass/$((pass+fail))"; [ $fail -eq 0 ]
