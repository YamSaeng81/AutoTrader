#!/bin/bash
# V171D4 대역 — 총괄 v1.171 §4. scenarios-v168.sh 의 단언 함수를 옮기고 사례를 이번 보완 범위로 바꿨다.
# 장치 보완: containerd 저장소 흉내(이미지 = 대상 ID·linux/amd64 manifest·config, 컨테이너 = ID·.Image·ImageManifestDescriptor,
#   이미지 API·docker image save 흉내, 실제 서버 save 원문 blob 사용). 정상 jar = 서버가 0e1d2a02 로 빌드한 실제 jar(JARM b6587953…).
# 다루는 것: ① 정상 전 구간 ② JARM: LF 기대 목록 수용·클래스/SQL 한 항목 변조 거절·로컬 CRLF jar 거절 ③ 이미지 대응: 대상≠config 정상 이미지·
#   잘못된 플랫폼·config·blob·manifest 2개·API/save 조회 실패·태그 변경·보존 태그 오염·제3의 이미지 ④ U1 frontend 빌드 전/중/뒤 실패와 U1되돌림
#   ⑤ UF2 교체·실패 뒤 U되돌림, frontend 미교체 U되돌림 ⑥ P0 우선 ⑦ 옛 판 혼합·판 표지 ⑧ 옛 V168D4 블록이 이 장치에서 10:27 실패를 재현
# 사용: scenarios-v171.sh <접두어>
set -u
cd /d/tmp/v171d4s/sim; P=${1:?접두어}; T='2026-11-16 12:30:00'
. ./conf.sh
NH=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b
MV=maint-v171
JARMX=b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8
JARCRLF=/d/tmp/v168-ev/jars/0e1d2a02.jar   # 로컬 Windows bootJar(be723125…, CRLF 리소스, 종전 JARM 7df5758f…)
TCLASS=/d/tmp/v171/jars/tamper-class.jar; TSQL=/d/tmp/v171/jars/tamper-sql.jar
PASS=0; FAILN=0
ini() { ./init.sh "$P-$1" "${2:-$T}" > /dev/null; }
co() { grep -c "$(printf '\tgit\tcheckout ')" runs/$P-$1/calls.log; }
cnt() { grep -c "$(printf '\t')$2" runs/$P-$1/calls.log; }   # 가짜 명령 기록에서 '<명령>\t<인자 앞부분>' 횟수
chk() { local c1=$1 b=$2 t=$3 wrc=$4 want=$5 wco=$6 rc c o
  ./sim.sh $P-$c1 $b $t > /dev/null 2>&1; rc=$?
  o=runs/$P-$c1/out/out-$b${t:+-$t}.txt; c=$(co $c1)
  if [ "$rc" = "$wrc" ] && grep -qF -- "$want" "$o" && [ "$c" = "$wco" ]; then PASS=$((PASS+1)); echo "PASS $c1 $b${t:+($t)} — 종료 $rc · '$want' · checkout $c"
  else FAILN=$((FAILN+1)); echo "FAIL $c1 $b${t:+($t)} — 종료 $rc(기대 $wrc) · '$want' · checkout $c(기대 $wco)"; grep -E '🔴|✅|대역 끝' "$o" | tail -3; fi; }
is() { if [ "$3" = "$4" ]; then PASS=$((PASS+1)); echo "PASS $1 — $2 = ${4:0:24}"; else FAILN=$((FAILN+1)); echo "FAIL $1 — $2 = $3 (기대 $4)"; fi; }
st_() { cat runs/$P-$1/state/$2 2>/dev/null || echo 없음; }
ctrf() { cut -d' ' -f$3 runs/$P-$1/state/ctr/$2; }   # 컨테이너 필드: 1 ID · 2 .Image · 3 manifest
mf() { cat runs/$P-$1/home/$MV/$2 2>/dev/null || echo 없음; }
fsha() { /usr/bin/sha256sum < runs/$P-$1/home/crypto_trader/AutoTrader/scripts/prospective/start_66.py | cut -c1-64; }
adv() { ./adv.sh $P-$1 $2 > /dev/null; }
env_() { printf '%s\n' "$2" >> runs/$P-$1/env.sh; }
fault() { printf '%s\n' "$3" > runs/$P-$1/state/fault/$2; }
u0u1() { chk $1 U0 "" 0 "✅ U0 통과" 0; adv $1 300; chk $1 U1 "" 0 "✅ U1 통과" 1; }
u2u3() { chk $1 U2 "" 0 "✅ U2 통과" 1; adv $1 300; chk $1 U3 "" 0 "✅ U3(deploy) 통과" 1; }
oldfe() { is $1 "frontend 실행 컨테이너 그대로(ID·.Image d825a9f5·manifest fa5c9ba8)" "$(cat runs/$P-$1/state/ctr/frontend)" "9a63d15ab1106830ca5d4d59bda98b440005f5310743b8e16c2e2f0079c0fe4a $FEI0 $FEM0"; }
oldbe() { is $1 "backend 실행 컨테이너 그대로(ID·.Image 52e12428·manifest ca440b84)" "$(cat runs/$P-$1/state/ctr/backend)" "743dfebac8c062011d7711bb59ebe727ee1e174724526b0160f6921afd5bfece $I26 $I26M"; }
names0() { is $1 "backend latest·보존 태그 = #27 대상" "$(st_ $1 tag/latest) $(st_ $1 tag/b5cd1d0)" "$I26 $I26"; is $1 "frontend 이미지 이름 = 837db515" "$(st_ $1 fetag/latest)" "$FET0"; }

echo "== r01 UF2 교체 뒤 실패(응답 500) → U되돌림 frontend 복구 · r02 frontend 교체 전 U되돌림(이름만 복구, 재생성 0)"
ini r01; u0u1 r01; u2u3 r01; env_ r01 FE_HTTP=500; chk r01 UF2 "" 1 "frontend 되돌림 필요: / 응답 500" 1
  sed -i '/FE_HTTP=500/d' runs/$P-r01/env.sh; chk r01 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2
  is r01 "frontend 재생성 .Image·manifest" "$(ctrf r01 frontend 2-3)" "$FET0 $FEM0"; names0 r01; is r01 "fe-restored" "$(mf r01 fe-restored.txt)" "frontend 복구 — 이미지 이름·실행 컨테이너 모두 U0 대상(837db515)"
ini r02; u0u1 r02; u2u3 r02; chk r02 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2
  oldfe r02; names0 r02; is r02 "frontend 재생성 0" "$(grep -c 'force-recreate frontend' runs/$P-r02/calls.log)" 0
  is r02 "fe-restored" "$(mf r02 fe-restored.txt)" "frontend 실행은 구판 그대로 — 이미지 이름만 U0 대상으로 되돌렸다(재생성 없음)"
ini r03; u0u1 r03; u2u3 r03; T3=sha256:$(printf third | /usr/bin/sha256sum | cut -c1-64)
  sed -i "s/^\([0-9a-f]*\) [^ ]* [^ ]*$/\1 $T3 sha256:$(printf third-m | /usr/bin/sha256sum | cut -c1-64)/" runs/$P-r03/state/ctr/backend   # 실행 backend 가 제3의 이미지
  N0=$(awk -F'	' '$2=="docker" && ($3 ~ /^tag / || $3 ~ /up -d/)' runs/$P-r03/calls.log | wc -l)
  chk r03 U되돌림 "" 1 "실행 backend 가 image-before·image-new 대응 어느 것도 아니거나 조회 실패" 1
  is r03 "U되돌림 구간 변경 명령 0(tag·up)" "$(( $(awk -F'	' '$2=="docker" && ($3 ~ /^tag / || $3 ~ /up -d/)' runs/$P-r03/calls.log | wc -l) - N0 ))" 0
  is r03 "HEAD 그대로(NEW)" "$(st_ r03 HEAD)" "$NEWC"; is r03 "backend latest 그대로(새)" "$(st_ r03 tag/latest)" "$(mf r03 image-new.txt)"
ini r04; u0u1 r04; u2u3 r04; chk r04 UF2 "" 0 "✅ UF2 통과" 1; env_ r04 API_FAIL=1
  chk r04 U되돌림 "" 1 "실행 backend 가 image-before·image-new 대응 어느 것도 아니거나 조회 실패" 1; is r04 "frontend 그대로(새)" "$(ctrf r04 frontend 2)" "$(mf r04 image-fe-new.txt)"

echo "== p01 P0 우선: 기동 로그 연결 획득 시간 초과 + 이미지 조회 실패 → P0 로 멈춤"
ini p01; u0u1 p01; env_ p01 NEWLOG=p0; chk p01 U2 "" 0 "✅ U2 통과" 1; adv p01 300; env_ p01 API_FAIL=1
  chk p01 U3 "" 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다" 1
ini p02; u0u1 p02; chk p02 U2 "" 0 "✅ U2 통과" 1; adv p02 300; env_ p02 API_FAIL=1
  chk p02 U3 "" 1 "실행 이미지가 기대 대상·manifest·config 와 다름 또는 조회 실패" 1

echo "== o01 옛 V168D4 블록(d5790c1c 원문)을 이 장치로 — 11-16 10:25~10:27 실패 재현(U1 JARM 7df5758f 불일치 → U1되돌림 frontend No such image)"
ini o01; D2_BLOCKS=/d/tmp/v168d4s/blocks-d4 UVAR=/d/tmp/v168d4s/blocks-d4/U변수.sh chk o01 U0 "" 0 "✅ U0 통과" 0; adv o01 300
D2_BLOCKS=/d/tmp/v168d4s/blocks-d4 UVAR=/d/tmp/v168d4s/blocks-d4/U변수.sh chk o01 U1 "" 1 "새 jar 항목 내용이 후보 0e1d2a02(제품 입력 99e6ab10) 목록(JARM)과 다름" 1
is o01 "JARM(서버 값과 같음)" "$(cat runs/$P-o01/home/maint-v168/jar-manifest-sha.txt)" "JARM $JARMX"
D2_BLOCKS=/d/tmp/v168d4s/blocks-d4 UVAR=/d/tmp/v168d4s/blocks-d4/U변수.sh chk o01 U1되돌림 "" 1 "" 2
is o01 "frontend 단계 No such image d825a9f5" "$(grep -c 'No such image: sha256:d825a9f5' runs/$P-o01/out/out-U1되돌림.txt)" 1

F=0; for d in runs/$P-*; do F=$((F + $(wc -l < $d/forbidden.log))); done
is 전체 "금지 명령 기록 줄 수 합" "$F" 0
echo "== 결과: PASS $PASS · FAIL $FAILN"
