#!/bin/bash
# v1.167 주문 확인 경합 보완 후보 D4(V167D4) 대역 — 총괄 v1.167 §4-6 "새 D4 초안과 대역 검증". V161D4 대역 장치(scenarios-v161.sh)의 단언 함수를 그대로 쓴다.
# 이번 판에서 바뀐 값: 판 표지 UB·기록 폴더 M·NEW 99e6ab10·DIFFN 130경로·JARM(새 jar 항목 내용 목록 해시) 추가.
# 다루는 것: ① 실제 새 jar 로 정상 전 구간(U0→U뒤관측)·JARM 기록 ② 되돌림 두 경로 ③ 이름 목록이 같은 옛 후보 jar 를 JARM 이 거부(재기동 전)
#            ④ JARM 없음·변조·판 표지·기록 폴더·옛 판 혼합 거부 ⑤ FEIMGOLD 대조(그대로인 값) 확인.
# 사용: scenarios-v167.sh <접두어>
set -u
cd /d/tmp/v167d4s/sim; P=${1:?접두어}; T='2026-11-11 10:30:00'
. ./conf.sh
NH=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b
MV=maint-v167
JARMX=7df5758fc48231372a9a507b228bafb5bfed1648332a8566e0881d80e2e1176d
OLDJAR=/d/tmp/v159/jar/4a4a04a3.jar   # 88f06345 입력 로컬 bootJar(0834382a…) — b5cd1d0 대비 이름 목록이 새 jar 와 같다
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

echo "== x01 정상: 실제 새 jar(99e6ab10)로 U0→U1→U2(V96)→U3→UF2→U4→U뒤관측, JARM 기록"
ini x01; u0u1 x01
is x01 "jar-manifest-sha(U1 JARM 기록)" "$(cat runs/$P-x01/home/$MV/jar-manifest-sha.txt)" "JARM $JARMX"
is x01 "jar-diff 62줄+끝 줄" "$(wc -l < runs/$P-x01/home/$MV/jar-diff.txt)" 63
is x01 HEAD "$(st_ x01 HEAD)" "$NEWC"
is x01 "image-fe-before" "$(cat runs/$P-x01/home/$MV/image-fe-before.txt)" "$FEI0"
u2u3 x01; chk x01 UF2 "" 0 "✅ UF2 통과" 1
adv x01 1500; chk x01 U4 "" 0 "✅ U4 통과" 1; chk x01 U뒤관측 "" 0 "" 1
is x01 "start_66.py sha256" "$(fsha x01)" "$NH"
is x01 "옛 기록 폴더 maint-v161 안 만듦" "$([ -e runs/$P-x01/home/maint-v161 ] && echo 있음 || echo 없음)" 없음

echo "== x02 UF2 뒤 U되돌림 — b5cd1d0·#27 이미지·frontend 원복"
ini x02; u0u1 x02; u2u3 x02; chk x02 UF2 "" 0 "✅ UF2 통과" 1
chk x02 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2; is x02 "frontend 원복" "$(st_ x02 svc/frontend)" "$FEI0"; is x02 backend "$(st_ x02 backend.image)" "$I26"
is x02 HEAD "$(st_ x02 HEAD)" "$OLDC"

echo "== x03 U1 뒤 U2 하지 않음 → U1-되돌림"
ini x03; u0u1 x03; chk x03 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2
is x03 "backend latest" "$(st_ x03 tag/latest)" "$I26"; is x03 "frontend latest" "$(st_ x03 fetag/latest)" "$FEI0"; is x03 HEAD "$(st_ x03 HEAD)" "$OLDC"

echo "== x04 옛 후보 jar(88f06345 입력, 이름 목록 같음) → RARX·JARN 통과, JARM 에서 재기동 전 멈춤 → U1-되돌림"
ini x04; chk x04 U0 "" 0 "✅ U0 통과" 0; adv x04 300
BUILD_JAR=$OLDJAR chk x04 U1 "" 1 "새 jar 항목 내용이 후보 99e6ab10 목록(JARM)과 다름" 1
is x04 "옛 jar 도 이름 목록 62줄+끝 줄(RARX·JARN 통과)" "$(wc -l < runs/$P-x04/home/$MV/jar-diff.txt)" 63
is x04 "실행 backend(재기동 없음)" "$(st_ x04 backend.image)" "$I26"
chk x04 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2; is x04 HEAD "$(st_ x04 HEAD)" "$OLDC"; is x04 "backend latest" "$(st_ x04 tag/latest)" "$I26"

echo "== x05 b5cd1d0 jar 로 빌드됨 → 이름 목록(RARX)에서 먼저 멈춤(JARM 까지 가지 않음)"
ini x05; chk x05 U0 "" 0 "✅ U0 통과" 0; adv x05 300
BUILD_JAR=$JAR_OLD chk x05 U1 "" 1 "jar 차이가 기대 62줄" 1
is x05 "JARM 기록 없음" "$([ -e runs/$P-x05/home/$MV/jar-manifest-sha.txt ] && echo 있음 || echo 없음)" 없음

echo "== x06·x12 JARM 없음 / 변조"
ini x06; printf 'JARM=\n' >> runs/$P-x06/vars.sh; chk x06 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.167 통합 후보 초안 D4 U 변수" 0
ini x12; chk x12 U0 "" 0 "✅ U0 통과" 0; adv x12 300; printf 'JARM=%s\n' "${JARMX%?}0" >> runs/$P-x12/vars.sh
chk x12 U1 "" 1 "새 jar 항목 내용이 후보 99e6ab10 목록(JARM)과 다름" 1

echo "== 판 표지·기록 폴더·옛 판 혼합 거부(checkout 0)"
ini x07; UVAR=/d/tmp/v161d4/blocks-d4/U변수.sh chk x07 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.167 통합 후보 초안 D4 U 변수" 0   # 옛 V161D4 U변수 + 새 블록
ini x08; UVAR=/d/tmp/v167d4s/blocks-d4/U변수.sh D2_BLOCKS=/d/tmp/v161d4/blocks-d4 chk x08 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.161 통합 후보 초안 D4 U 변수" 0   # 새 U변수 + 옛 블록
ini x09; printf 'M=~/maint-v161\n' >> runs/$P-x09/vars.sh; chk x09 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.167 통합 후보 초안 D4 U 변수" 0
ini x10; u0u1 x10; printf 'UB=V161D4\n' >> runs/$P-x10/vars.sh; chk x10 U2 "" 1 "U 변수가 v1.167 통합 후보 초안 D4 판이 아님" 1
ini x11; echo "sha256:$(printf x | /usr/bin/sha256sum | cut -c1-64)" > runs/$P-x11/state/svc/frontend; chk x11 U0 "" 1 "실행 중 frontend 이미지가 FEIMGOLD 와 다름" 0

F=0; for d in runs/$P-x*; do F=$((F + $(wc -l < $d/forbidden.log))); done
is 전체 "금지 명령 기록 줄 수 합" "$F" 0
echo "== 결과: PASS $PASS · FAIL $FAILN"
