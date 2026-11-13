#!/bin/bash
# SMA20 PAPER 후보 초안 D4 대역(총괄 v1.144 H). 바뀐 분기와 복구 경로만 검증한다. 각 사례는 새 대역 폴더에서 시작한다.
# 장치는 D3 대역 사본이다(가짜 ssh/docker/git/DB/pgrep). 단언 = 블록 종료값 + 통과/멈춤 문구 + 누적 checkout 수, 필요한 곳은 HEAD·이미지·태그·파일.
# 사용: scenarios-d4.sh <접두어>
set -u
cd /d/tmp/v159d4/sim; P=${1:?접두어}; T='2026-11-11 10:30:00'
. ./conf.sh
D3UV="/d/Claude Code/projects/crypto-auto-trader/docs/evidence/o1o3-n21-d3-b5cd1d0/d3/blocks-d3/U변수.sh"
NH=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b
I26OLD=sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1
PASS=0; FAILN=0
ini() { ./init.sh "$P-$1" "${2:-$T}" > /dev/null; }
co() { grep -c "$(printf '\tgit\tcheckout ')" runs/$P-$1/calls.log; }
chk() { local c1=$1 b=$2 t=$3 wrc=$4 want=$5 wco=$6 rc c o
  ./sim.sh $P-$c1 $b $t > /dev/null 2>&1; rc=$?
  o=runs/$P-$c1/out/out-$b${t:+-$t}.txt; c=$(co $c1)
  if [ "$rc" = "$wrc" ] && grep -qF -- "$want" "$o" && [ "$c" = "$wco" ]; then PASS=$((PASS+1)); echo "PASS $c1 $b${t:+($t)} — 종료 $rc · '$want' · checkout $c"
  else FAILN=$((FAILN+1)); echo "FAIL $c1 $b${t:+($t)} — 종료 $rc(기대 $wrc) · '$want' · checkout $c(기대 $wco)"; grep -E '🔴|✅|대역 끝' "$o" | tail -3; fi; }
is() { if [ "$3" = "$4" ]; then PASS=$((PASS+1)); echo "PASS $1 — $2 = ${4:0:19}"; else FAILN=$((FAILN+1)); echo "FAIL $1 — $2 = $3 (기대 $4)"; fi; }
st_() { cat runs/$P-$1/state/$2 2>/dev/null || echo 없음; }
fsha() { /usr/bin/sha256sum < runs/$P-$1/home/crypto_trader/AutoTrader/scripts/prospective/start_66.py | cut -c1-64; }
tfile() { [ -e runs/$P-$1/home/crypto_trader/AutoTrader/scripts/prospective/test_freshness_recheck_fail.py ] && echo 있음 || echo 없음; }
adv() { ./adv.sh $P-$1 $2 > /dev/null; }
u0u1() { chk $1 U0 "" 0 "✅ U0 통과" 0; adv $1 300; chk $1 U1 "" 0 "✅ U1 통과" 1; }

echo "== e01 정상: U0→U1→U2→U3→U4→U뒤관측, 감시 스크립트·시험 파일 불변, 보존 태그 b5cd1d0 = #27"
ini e01; u0u1 e01
is e01 HEAD "$(st_ e01 HEAD)" "$NEWC"; is e01 "start_66.py sha256" "$(fsha e01)" "$NH"; is e01 "시험 파일" "$(tfile e01)" 있음
is e01 "보존 태그 b5cd1d0" "$(st_ e01 tag/b5cd1d0)" "$I26"; is e01 "복구 태그 1899b49(손대지 않음)" "$(st_ e01 tag/1899b49)" "$I26OLD"
chk e01 U2 "" 0 "✅ U2 통과" 1; adv e01 300; chk e01 U3 "" 0 "✅ U3(deploy) 통과" 1; adv e01 1500; chk e01 U4 "" 0 "✅ U4 통과" 1
chk e01 U뒤관측 "" 0 "" 1

echo "== U0 의 바뀐 확인(바꾸기 전 멈춤)"
ini e02; echo sha256:0000 > runs/$P-e02/home/maint-o13n21/image-new.txt; chk e02 U0 "" 1 "#27 배포 이미지(~/maint-o13n21/image-new.txt)와 다름" 0
ini e03; echo sha256:1111 > runs/$P-e03/state/tag/1899b49; chk e03 U0 "" 1 "1899b49 이 #26 이미지(426a09a2…)가 아님" 0
ini e04; echo 'DIFF_EXTRA=web-api/src/main/resources/db/migration/V95__x.sql' > runs/$P-e04/env.sh; chk e04 U0 "" 1 "12개 파일이 아님" 0
ini e05; echo '# 대역 변경' >> runs/$P-e05/home/crypto_trader/AutoTrader/scripts/prospective/start_66.py; chk e05 U0 "" 1 "start_66.py 가 b5cd1d0 원문(68d441ce…)과 다름" 0
ini e06; rm runs/$P-e06/home/crypto_trader/AutoTrader/scripts/prospective/test_freshness_recheck_fail.py; chk e06 U0 "" 1 "후보의 start_66.py·시험 파일 블롭이 운영과 다름" 0
ini e07; UVAR="$D3UV" chk e07 U0 "" 1 "변수 순서" 0; UVAR="$D3UV" chk e07 U1 "" 1 "D4 판이 아님" 0
ini e08 '2026-11-11 10:50:00'; chk e08 U0 "" 0 "✅ U0 통과" 0; adv e08 1740; chk e08 U1 "" 1 "매시 :14~:24" 0; is e08 HEAD "$(st_ e08 HEAD)" "$OLDC"

echo "== U1 jar 기대 불일치 → U1-되돌림(재기동 전)"
ini e09; echo "BUILD_JAR=$JAR_OLD" > runs/$P-e09/env.sh; chk e09 U0 "" 0 "✅ U0 통과" 0; adv e09 300
chk e09 U1 "" 1 "jar 차이가 기대" 1; chk e09 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2
is e09 HEAD "$(st_ e09 HEAD)" "$OLDC"; is e09 latest "$(st_ e09 tag/latest)" "$I26"; is e09 backend "$(st_ e09 backend.image)" "$I26"
is e09 "start_66.py sha256" "$(fsha e09)" "$NH"; is e09 "시험 파일" "$(tfile e09)" 있음
chk e09 U2 "" 1 "U1 미통과" 2
ini e10; u0u1 e10; echo "$MIDC" > runs/$P-e10/state/HEAD; chk e10 U1되돌림 "" 1 "어느 것도 아님" 1; is e10 HEAD "$(st_ e10 HEAD)" "$MIDC"
ini e11; u0u1 e11; chk e11 U2 "" 0 "✅ U2 통과" 1; chk e11 U1되돌림 "" 1 "U2(재기동)를 시작했다" 1

echo "== U되돌림(재기동 뒤)"
ini e12; u0u1 e12; chk e12 U2 "" 0 "✅ U2 통과" 1
chk e12 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2; is e12 HEAD "$(st_ e12 HEAD)" "$OLDC"; is e12 backend "$(st_ e12 backend.image)" "$I26"
is e12 "start_66.py sha256" "$(fsha e12)" "$NH"; is e12 "시험 파일" "$(tfile e12)" 있음
adv e12 300; chk e12 U3 "" 0 "b5cd1d0 복귀 확인" 2
ini e13; u0u1 e13; chk e13 U2 "" 0 "✅ U2 통과" 1; echo sha256:2222 > runs/$P-e13/state/backend.image; chk e13 U되돌림 "" 1 "image-before·image-new 어느 것도 아님" 1

F=0; for d in runs/$P-e*; do F=$((F + $(wc -l < $d/forbidden.log))); done
is 전체 "금지 명령 기록 줄 수 합" "$F" 0
echo "== 결과: PASS $PASS · FAIL $FAILN"
