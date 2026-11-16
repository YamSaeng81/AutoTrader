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

echo "== f01~f03 U1 frontend 빌드 전·중·뒤 실패 → U1되돌림"
ini f01; chk f01 U0 "" 0 "✅ U0 통과" 0; adv f01 300; FS0=sha256:$(printf fe-other | /usr/bin/sha256sum | cut -c1-64)
  echo "$FS0" > runs/$P-f01/state/fetag/b5cd1d0; echo frontend > runs/$P-f01/state/img/${FS0#sha256:}   # frontend 보존 태그가 이미 다른 이미지(빌드 전·바꾸기 전 멈춤)
  chk f01 U1 "" 1 "autotrader-frontend:b5cd1d0 태그가 이미 다른 이미지를 가리키거나 조회 실패" 0
  is f01 "변경 명령 0" "$(( $(cnt f01 'docker	tag') + $(cnt f01 'docker	compose -f docker-compose.prod.yml build') ))" 0
ini f02; chk f02 U0 "" 0 "✅ U0 통과" 0; adv f02 300; env_ f02 FE_BUILD_FAIL=1
  chk f02 U1 "" 1 "frontend 빌드 실패" 1; is f02 "fe-stage" "$(mf f02 fe-stage.txt)" 빌드시작; is f02 "frontend 이름 그대로" "$(st_ f02 fetag/latest)" "$FET0"
  chk f02 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2; names0 f02; oldfe f02; oldbe f02
  is f02 "계획" "$(mf f02 u1rb-plan.txt)" "되돌릴 이미지 이름: backend latest 보존 태그로 · frontend 그대로"
ini f03; chk f03 U0 "" 0 "✅ U0 통과" 0; adv f03 300; env_ f03 FE_BUILD_FAIL_AFTER_TAG=1
  chk f03 U1 "" 1 "frontend 빌드 실패" 1; is f03 "fe-stage" "$(mf f03 fe-stage.txt)" 빌드시작
  is f03 "frontend 이름이 바뀜(빌드 중)" "$([ "$(st_ f03 fetag/latest)" != "$FET0" ] && echo 바뀜)" 바뀜
  chk f03 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2; names0 f03; oldfe f03; oldbe f03
  is f03 "계획" "$(mf f03 u1rb-plan.txt)" "되돌릴 이미지 이름: backend latest 보존 태그로 · frontend 보존 태그로"
ini f04; u0u1 f04; FS4=sha256:$(printf fe-third | /usr/bin/sha256sum | cut -c1-64); echo "$FS4" > runs/$P-f04/state/fetag/latest; echo frontend > runs/$P-f04/state/img/${FS4#sha256:}
  chk f04 U1되돌림 "" 1 "frontend 이미지 이름이 U1 새 이미지가 아님" 1   # 빌드끝 뒤 제3의 이미지 → 바꾸지 않고 멈춤
  is f04 "HEAD 그대로(NEW)" "$(st_ f04 HEAD)" "$NEWC"; is f04 "backend latest 그대로(새 이미지)" "$(st_ f04 tag/latest)" "$(mf f04 image-new.txt)"

echo "== t01~t03 태그 변경·조회 실패 → 재기동/교체 전 멈춤"
ini t01; u0u1 t01; TS=sha256:$(printf be-other | /usr/bin/sha256sum | cut -c1-64); echo "$TS" > runs/$P-t01/state/tag/latest; echo x > runs/$P-t01/state/img/${TS#sha256:}
  chk t01 U2 "" 1 "latest 가 image-new 와 다름" 1; is t01 "up 0" "$(cnt t01 'docker	compose -f docker-compose.prod.yml up')" 0
ini t02; u0u1 t02; u2u3 t02; FT=sha256:$(printf fe-other2 | /usr/bin/sha256sum | cut -c1-64); echo "$FT" > runs/$P-t02/state/fetag/latest; echo frontend > runs/$P-t02/state/img/${FT#sha256:}
  chk t02 UF2 "" 1 "frontend 이미지 이름이 image-fe-new 대상·manifest·config 를 가리키지 않음" 1
  is t02 "frontend up 0" "$(grep -c 'up -d --no-build --no-deps frontend' runs/$P-t02/calls.log)" 0
ini t03; u0u1 t03; env_ t03 API_FAIL=1; chk t03 U2 "" 1 "latest 가 image-new 대상·manifest·config 와 다름 또는 조회 실패(아직 바꾼 것 없음)" 1
  is t03 "up 0" "$(cnt t03 'docker	compose -f docker-compose.prod.yml up')" 0

echo "== c01 새 컨테이너 .Image 가 config 로 보이는 경우도 같은 이미지로 수용(대상≠config) · c02 daemon 사용 목록·manifest 불일치 거절"
ini c01; u0u1 c01; env_ c01 UP_IMG_CONFIG=1; u2u3 c01; is c01 "backend .Image = 새 config" "$(ctrf c01 backend 2)" "$(cut -d' ' -f3 runs/$P-c01/home/$MV/image-new-chain.txt)"
ini c02; u0u1 c02; env_ c02 CTR_ARCH_NEW=arm64; chk c02 U2 "" 1 "되돌림 필요: 새 컨테이너가 image-new 대상·manifest·config 와 연결되지 않음" 1
ini c03; u0u1 c03; chk c03 U2 "" 0 "✅ U2 통과" 1; adv c03 300; fault c03 "api-nousers-$(cut -d' ' -f1 runs/$P-c03/home/$MV/image-new-chain.txt | cut -c8-)" 1
  chk c03 U3 "" 1 "실행 이미지가 기대 대상·manifest·config 와 다름 또는 조회 실패" 1   # daemon 사용 목록에 실행 컨테이너 없음

F=0; for d in runs/$P-*; do F=$((F + $(wc -l < $d/forbidden.log))); done
is 전체 "금지 명령 기록 줄 수 합" "$F" 0
echo "== 결과: PASS $PASS · FAIL $FAILN"
