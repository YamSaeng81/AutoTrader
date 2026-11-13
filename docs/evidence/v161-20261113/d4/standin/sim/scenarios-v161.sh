#!/bin/bash
# v1.161 최종 후보 D4(V161D4) 대역 — 총괄 v1.161 §4-2 "영향받는 값 거부 대역". V160D4 대역 장치를 값만 옮겼다(scenarios-v160.sh 의 단언 함수 그대로).
# 이번 판에서 바뀐 값은 판 표지 UB·기록 폴더 M·FEIMGOLD(실제 운영 읽기 값) 뿐이다. NEW·GS·DIFFN·jar 입력은 V160D4 와 같아 22사례 96/96 을 일괄 반복하지 않는다.
# 다루는 것: ① 실제 FEIMGOLD 로 정상 전 구간(U0→U뒤관측) ② frontend 를 건드리는 되돌림 두 경로 ③ FEIMGOLD·UB·M 거부.
# 사용: scenarios-v161.sh <접두어>
set -u
cd /d/tmp/v161d4/sim; P=${1:?접두어}; T='2026-11-11 10:30:00'
. ./conf.sh
NH=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b
MV=maint-v161
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
adv() { ./adv.sh $P-$1 $2 > /dev/null; }
u0u1() { chk $1 U0 "" 0 "✅ U0 통과" 0; adv $1 300; chk $1 U1 "" 0 "✅ U1 통과" 1; }
u2u3() { chk $1 U2 "" 0 "✅ U2 통과" 1; adv $1 300; chk $1 U3 "" 0 "✅ U3(deploy) 통과" 1; }

echo "== w01 정상: U변수 실제 FEIMGOLD(대역 치환 없음)로 U0→U1→U2(V96)→U3→UF2→U4→U뒤관측"
ini w01; is w01 "대역 덧붙임에 FEIMGOLD 없음" "$(grep -c '^FEIMGOLD=' runs/$P-w01/vars.sh)" 0
u0u1 w01
is w01 "image-fe-before(U0 실제 대조값)" "$(cat runs/$P-w01/home/$MV/image-fe-before.txt)" "$FEI0"
is w01 "frontend 보존 태그 b5cd1d0" "$(st_ w01 fetag/b5cd1d0)" "$FEI0"; is w01 "U1 뒤 실행 frontend(구판 그대로)" "$(st_ w01 svc/frontend)" "$FEI0"
is w01 HEAD "$(st_ w01 HEAD)" "$NEWC"
u2u3 w01; is w01 "U3 뒤 실행 frontend(아직 구판)" "$(st_ w01 svc/frontend)" "$FEI0"
chk w01 UF2 "" 0 "✅ UF2 통과" 1; is w01 "UF2 뒤 실행 frontend" "$(st_ w01 svc/frontend)" "$(cat runs/$P-w01/home/$MV/image-fe-new.txt)"
adv w01 1500; chk w01 U4 "" 0 "✅ U4 통과" 1; chk w01 U뒤관측 "" 0 "" 1
is w01 "start_66.py sha256" "$(fsha w01)" "$NH"
is w01 "옛 기록 폴더 maint-v160 안 만듦" "$([ -e runs/$P-w01/home/maint-v160 ] && echo 있음 || echo 없음)" 없음

echo "== w02 UF2 뒤 U되돌림 — frontend 를 실제 FEIMGOLD 이미지로 원복"
ini w02; u0u1 w02; u2u3 w02; chk w02 UF2 "" 0 "✅ UF2 통과" 1
chk w02 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2; is w02 "frontend 원복" "$(st_ w02 svc/frontend)" "$FEI0"; is w02 backend "$(st_ w02 backend.image)" "$I26"
is w02 HEAD "$(st_ w02 HEAD)" "$OLDC"

echo "== w03 U1 뒤 U2 하지 않음 → U1-되돌림(frontend latest 를 실제 FEIMGOLD 로)"
ini w03; u0u1 w03; chk w03 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2
is w03 "frontend latest" "$(st_ w03 fetag/latest)" "$FEI0"; is w03 "실행 frontend" "$(st_ w03 svc/frontend)" "$FEI0"; is w03 "backend latest" "$(st_ w03 tag/latest)" "$I26"

echo "== FEIMGOLD 거부(바꾸기 전 멈춤, checkout 0)"
FE_OVERRIDE=미정 ini w04; chk w04 U0 "" 1 "FEIMGOLD 가 미정" 0
FE_OVERRIDE=${FEI0#sha256:} ini w05; chk w05 U0 "" 1 "FEIMGOLD 가 미정" 0   # sha256: 접두어 없는 값
FE_OVERRIDE=$FEIOLD ini w06; chk w06 U0 "" 1 "실행 중 frontend 이미지가 FEIMGOLD 와 다름" 0   # V160D4 대역 값
ini w07; echo "sha256:$(printf x | /usr/bin/sha256sum | cut -c1-64)" > runs/$P-w07/state/svc/frontend; chk w07 U0 "" 1 "실행 중 frontend 이미지가 FEIMGOLD 와 다름" 0
is w07 "image-fe-before(실제 읽은 값 기록)" "$(cat runs/$P-w07/home/$MV/image-fe-before.txt)" "$(st_ w07 svc/frontend)"

echo "== 판 표지·기록 폴더 거부(옛 판과 섞으면 멈춤, checkout 0)"
ini w12; UVAR=/d/tmp/v160d4/blocks-d4/U변수.sh D2_BLOCKS=/d/tmp/v160d4/blocks-d4 chk w12 U0 "" 1 "FEIMGOLD 가 미정" 0   # 옛 V160D4 한 쌍 그대로는 실행되지 않음(r1 w09 에서 드러난 경로를 사례로 고정)
ini w08; UVAR=/d/tmp/v160d4/blocks-d4/U변수.sh chk w08 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.161 통합 후보 초안 D4 U 변수" 0   # 옛 V160D4 U변수 + 새 블록
ini w09; UVAR=/d/tmp/v161d4/blocks-d4/U변수.sh D2_BLOCKS=/d/tmp/v160d4/blocks-d4 chk w09 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.160 통합 후보 초안 D4 U 변수" 0   # 새 U변수 + 옛 V160D4 블록(r1 은 UVAR 를 빠뜨려 옛 판 한 쌍이 돌았다 — FEIMGOLD 미정으로 멈춤)
ini w10; printf 'M=~/maint-v160\n' >> runs/$P-w10/vars.sh; chk w10 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.161 통합 후보 초안 D4 U 변수" 0
ini w11; u0u1 w11; printf 'UB=V160D4\n' >> runs/$P-w11/vars.sh; chk w11 U2 "" 1 "U 변수가 v1.161 통합 후보 초안 D4 판이 아님" 1   # U0 뒤 판 표지가 바뀌면 U2 가 멈춤

F=0; for d in runs/$P-w*; do F=$((F + $(wc -l < $d/forbidden.log))); done
is 전체 "금지 명령 기록 줄 수 합" "$F" 0
echo "== 결과: PASS $PASS · FAIL $FAILN"
