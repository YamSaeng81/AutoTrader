#!/bin/bash
# D81D4R 대역 보충(총괄 v1.199 §3) — 새 U변수·U0(mkd81r.py)로 U0 의 pf3ok 연결만 본다. 함수 머리는 scenarios-d81.sh(r3) 1~38줄 그대로.
# 실행: D2_BLOCKS=/d/tmp/d81d4s/blocks-d81r bash scenarios-d81r.sh <회차 접두어>
# 사용: scenarios-d81.sh <회차 접두어>   — 사례 폴더는 runs/<접두어>-<사례> 로 회차마다 새로 만든다(있으면 init 이 멈춘다)
# 단언: 종료값·멈춤 문구 + 파일/장부/태그/컨테이너 상태 + 변경 명령(tag·build·up·checkout) 수 + 금지 명령 0
set -u
cd /d/tmp/d81d4s/sim; P=${1:?접두어}; T='2026-11-20 13:30:00'
. ./conf.sh
MV=maint-d81
PYREAL='/c/Users/SangJoon_Lee/AppData/Local/Microsoft/WindowsApps/PythonSoftwareFoundation.Python.3.13_qbz5n2kfra8p0/python.exe'
TAMPER=/d/tmp/d81d4s/jars/tamper-d81-class.jar
PASS=0; FAILN=0
ini() { ./init.sh "$P-$1" "$T" > /dev/null || { echo "init 실패 $1"; exit 9; }; }
D() { echo "runs/$P-$1"; }
chg() { awk -F'\t' '($2=="docker" && ($3 ~ /^tag / || $3 ~ /docker-compose.prod.yml (up|build) /)) || ($2=="git" && $3 ~ /^checkout /)' "$(D $1)/calls.log" | wc -l; }
chgseq() { awk -F'\t' '($2=="docker" && ($3 ~ /^tag / || $3 ~ /docker-compose.prod.yml (up|build) /)) || ($2=="git" && $3 ~ /^checkout /) {print $2" "$3}' "$(D $1)/calls.log" | sed -E 's/ +$//; s/-f docker-compose.prod.yml //' | tr '\n' ';'; }
upn() { awk -F'\t' '$2=="docker" && $3 ~ /docker-compose.prod.yml up /' "$(D $1)/calls.log" | wc -l; }
fechg() { awk -F'\t' '$2=="docker" && ($3 ~ /^tag [^ ]+ autotrader-frontend/ || $3 ~ /docker-compose.prod.yml (build|up)[^\t]*frontend/)' "$(D $1)/calls.log" | wc -l; }
tags() { ( cd "$(D $1)/state" && for f in tag/* fetag/*; do printf '%s=%s;' "$f" "$(cut -c1-19 "$f")"; done ); }
fest() { echo "$(cat "$(D $1)/state/ctr/frontend") $(cat "$(D $1)/state/frontend.started") $(cat "$(D $1)/state/fetag/latest")"; }
st_() { cat "$(D $1)/state/$2" 2>/dev/null || echo 없음; }
mf() { cat "$(D $1)/home/$MV/$2" 2>/dev/null || echo 없음; }
ex() { [ -e "$(D $1)/home/$MV/$2" ] && echo 있음 || echo 없음; }
adv() { ./adv.sh $P-$1 $2 > /dev/null; }
env_() { printf '%s\n' "$2" >> "$(D $1)/env.sh"; }
unenv() { sed -i "/^$2=/d" "$(D $1)/env.sh"; }
chk() { local c=$1 b=$2 t=$3 wrc=$4 want=$5 rc o
  ./sim.sh $P-$c $b $t > /dev/null 2>&1; rc=$?
  o=$(D $c)/out/out-$b${t:+-$t}.txt
  if [ "$rc" = "$wrc" ] && grep -qF -- "$want" "$o"; then PASS=$((PASS+1)); echo "PASS $c $b${t:+($t)} — 종료 $rc · '$want'"
  else FAILN=$((FAILN+1)); echo "FAIL $c $b${t:+($t)} — 종료 $rc(기대 $wrc) · '$want'"; grep -E '🔴|✅|대역 끝' "$o" | tail -3; fi; }
is() { if [ "$3" = "$4" ]; then PASS=$((PASS+1)); echo "PASS $1 — $2"; else FAILN=$((FAILN+1)); echo "FAIL $1 — $2 = [$3] (기대 [$4])"; fi; }
mkimg() { local id; id=$(PYTHONUTF8=1 "$PYREAL" -I -X utf8 imgstate.py build "$(D $1)/state" "$2" "third-$3" | tr -d '\r')
  if [ "$2" = backend ]; then echo "$JAR_OLD" > "$(D $1)/state/img/${id#sha256:}"; else echo frontend > "$(D $1)/state/img/${id#sha256:}"; fi; echo "$id"; }
OTHER=sha256:$(printf other | /usr/bin/sha256sum | cut -c1-64)
oldtags() { tags $1 | tr ';' '\n' | grep -vE '^(tag/latest|tag/0e1d2a0|fetag/latest)=' | grep . | tr '\n' ';'; }
ini base; OLDTAGS=$(oldtags base); [ "$(printf %s "$OLDTAGS" | tr ';' '\n' | grep -c .)" = 10 ] || { echo "옛 태그 기준 10개가 아님: $OLDTAGS"; exit 9; }
u01() { ini $1; chk $1 U0 "" 0 "✅ U0 통과"; adv $1 300; chk $1 U1 "" 0 "✅ U1 통과"; }
u23() { chk $1 U2 "" 0 "✅ U2 통과"; adv $1 300; chk $1 U3 "" 0 "✅ U3(deploy) 통과"; }
[ "$BLOCKS" = /d/tmp/d81d4s/blocks-d81r ] && [ "$(sha256sum "$BLOCKS/U변수.sh" | cut -c1-16)" = efb9610811314213 ] && [ "$(sha256sum "$BLOCKS/U0.sh" | cut -c1-16)" = 736351dbaddab382 ] || { echo "새 블록 경로·해시 단언 실패"; exit 9; }
echo "== v01~v10 새 pf3ok — 진행 가능 밖·형식/읽기 실패는 ok-U0 전 멈춤, checkout·build·tag·up 0"
while IFS=';' read -r c body rc w; do ini $c; printf "$body" > $(D $c)/fix/preflight.txt; chk $c U0 "" $rc "$w"; is $c "변경 0·ok-U0 $([ $rc = 0 ] && echo 있음 || echo 없음)" "$(chg $c) $(ex $c ok-U0)" "0 $([ $rc = 0 ] && echo 있음 || echo 없음)"; done <<'TT'
v01;LIVE | 0\nDYNAMIC | 0\nLIVE | -200.5\nDYNAMIC | 0\n4151 | 1 | 0 | 20200 | 0.001 | 1 | 20000 | 0.001 | 0 | 0 | LIVE | 203 | 2026-11-10 01:00:00+00 | -200 | STOP_LOSS | 1\n4152 | 0 | 0 |  |  | 0 |  |  | 0 | 1 | DYNAMIC | 93 |  |  | ENTRY_REJECTED | 1\n;0;R3 RUNNING REAL 세션 청산 행 2 · 판정 불가 0
v02;LIVE | 0\nDYNAMIC | 0\nLIVE | 0\nDYNAMIC | 0\n4151 | 1 | 0 | not-a-number | 0.001 | 1 | 20000 | 0.001 | 0 | 0 | LIVE | 203 | 2026-11-10 01:00:00+00 | -200 | STOP_LOSS | 1\n;1;pf3ok 종료 3 — 1 보수적 사전 기준 미충족 / 3 형식 이상·읽기 실패
v03;LIVE | 0\nDYNAMIC | 0\nLIVE | 0\nDYNAMIC | 0\n4151 | 1 | 0 | 20200 | 0.001 | 1 | 20000 | Infinity | 0 | 0 | LIVE | 203 | 2026-11-10 01:00:00+00 | -200 | STOP_LOSS | 1\n;1;pf3ok 종료 3
v04;LIVE | 0\nDYNAMIC | 0\nLIVE | 0\nDYNAMIC | 0\n4151 | 1 | 0 | 20200 | 0.001 | 1 | 20000 | 0.001 | 0 | 2 | LIVE | 203 | 2026-11-10 01:00:00+00 | -200 | STOP_LOSS | 1\n;1;pf3ok 종료 3
v05;LIVE | 0\nDYNAMIC | 0\nLIVE | 0\nDYNAMIC | 0\n4151 | 1 | 0 | 20200 | 0.001 | 1 |  | 0.001 | 0 | 0 | LIVE | 203 | 2026-11-10 01:00:00+00 | -200 | STOP_LOSS | 1\n;1;판정 | 🔴 보수적 사전 기준 미충족
v06;LIVE | 0\nDYNAMIC | 0\nLIVE | 0\nDYNAMIC | 0\n4151 | 1 | 0 | NaN | 0.001 | 1 | 20000 | 0.001 | 0 | 0 | LIVE | 203 | 2026-11-10 01:00:00+00 | -200 | STOP_LOSS | 1\n;1;pf3ok 종료 3
v07;LIVE | 0\nDYNAMIC | 0\nLIVE | 0\nDYNAMIC | 0\n4151 | 1 | 0 | 20200 | -0.001 | 1 | 20000 | 0.001 | 0 | 0 | LIVE | 203 | 2026-11-10 01:00:00+00 | -200 | STOP_LOSS | 1\n;1;pf3ok 종료 3
v08;LIVE | 0\nDYNAMIC | 0\nLIVE | 0\nDYNAMIC | 0\n4152 | 0 | 0 | abc |  | 0 |  |  | 0 | 1 | DYNAMIC | 93 |  |  | ENTRY_REJECTED | 1\n;1;pf3ok 종료 3
v09;LIVE | 0\nDYNAMIC | 0\nLIVE | 0\n\xff\xfe\n;1;파일 읽기 실패(UnicodeDecodeError)
v10;LIVE | 1\nDYNAMIC | 0\nLIVE | 0\nDYNAMIC | 0\n;1;pf3ok 종료 1 — 1 보수적 사전 기준 미충족
TT
is 전체 "모든 사례 forbidden.log 합계 0" "$(cat runs/$P-v*/forbidden.log | grep -c .)" 0
echo "== 대역 결과 PASS $PASS · FAIL $FAILN"
