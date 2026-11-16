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

echo "== x04·x04s 한 항목 변조 jar(클래스 / V96 SQL) → 이름 목록(RARX·JARN) 통과, JARM 에서 재기동 전 멈춤 → U1되돌림(frontend 빌드 전 단계)"
for v in x04:$TCLASS x04s:$TSQL; do c=${v%%:*}; j=${v#*:}
  ini $c; chk $c U0 "" 0 "✅ U0 통과" 0; adv $c 300; env_ $c "BUILD_JAR=$j"
  chk $c U1 "" 1 "새 jar 항목 내용이 후보 0e1d2a02(제품 입력 99e6ab10) 목록(JARM)과 다름" 1
  is $c "이름 목록 62줄+끝 줄(RARX·JARN 통과)" "$(wc -l < runs/$P-$c/home/$MV/jar-diff.txt)" 63
  is $c "fe-stage(frontend 빌드 전)" "$(mf $c fe-stage.txt)" 보존태그
  is $c "frontend 빌드 안 함" "$(cnt $c 'docker	compose -f docker-compose.prod.yml build frontend')" 0
  chk $c U1되돌림 "" 0 "↩ U1-되돌림 끝" 2; names0 $c; oldbe $c; oldfe $c
  is $c "계획" "$(mf $c u1rb-plan.txt)" "되돌릴 이미지 이름: backend latest 보존 태그로 · frontend 그대로"; done

echo "== x05 b5cd1d0 jar · x05c 로컬 CRLF bootJar(종전 JARM 7df5758f) → 이름 목록(RARX)에서 먼저 멈춤"
for v in x05:$JAR_OLD x05c:$JARCRLF; do c=${v%%:*}; j=${v#*:}
  ini $c; chk $c U0 "" 0 "✅ U0 통과" 0; adv $c 300; env_ $c "BUILD_JAR=$j"
  chk $c U1 "" 1 "jar 차이가 기대 62줄" 1
  is $c "JARM 기록 없음" "$([ -e runs/$P-$c/home/$MV/jar-manifest-sha.txt ] && echo 있음 || echo 없음)" 없음; done
is x05c "CRLF jar 의 리소스 변경 줄(LF 옛 jar 대비, 97개 중 V96 은 추가 항목)" "$(grep -c '^변경 BOOT-INF/classes/\(application.yml\|logback-spring.xml\|db/migration/\)' runs/$P-x05c/home/$MV/jar-diff.txt)" 96

echo "== x06·x12 JARM 없음 / 한 글자 변조"
ini x06; printf 'JARM=\n' >> runs/$P-x06/vars.sh; chk x06 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.171 통합 후보 초안 D4 U 변수" 0
ini x12; chk x12 U0 "" 0 "✅ U0 통과" 0; adv x12 300; printf 'JARM=%s\n' "${JARMX%?}0" >> runs/$P-x12/vars.sh
chk x12 U1 "" 1 "새 jar 항목 내용이 후보 0e1d2a02(제품 입력 99e6ab10) 목록(JARM)과 다름" 1

echo "== 판 표지·기록 폴더·옛 판 혼합 거부"
ini x07; UVAR=/d/tmp/v168d4s/blocks-d4/U변수.sh chk x07 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.171 통합 후보 초안 D4 U 변수" 0   # 옛 V168D4 U변수 + 새 블록
ini x08; UVAR=/d/tmp/v171d4s/blocks-d4/U변수.sh D2_BLOCKS=/d/tmp/v168d4s/blocks-d4 chk x08 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.168 통합 후보 초안 D4 U 변수" 0   # 새 U변수 + 옛 V168D4 블록
ini x09; printf 'M=~/maint-v168\n' >> runs/$P-x09/vars.sh; chk x09 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.171 통합 후보 초안 D4 U 변수" 0
ini x10; u0u1 x10; printf 'UB=V168D4\n' >> runs/$P-x10/vars.sh; chk x10 U2 "" 1 "U 변수가 v1.171 통합 후보 초안 D4 판이 아님" 1
ini x11; echo "sha256:$(printf x | /usr/bin/sha256sum | cut -c1-64)" > runs/$P-x11/state/svc/frontend
  sed -i "s/ $FEI0 / sha256:$(printf x | /usr/bin/sha256sum | cut -c1-64) /" runs/$P-x11/state/ctr/frontend
  chk x11 U0 "" 1 "실행 중 frontend 이미지가 FEIMGOLD 와 다름" 0

echo "== i01~i08 이미지 대응 조회·형식 이상 → 멈춤(변경 명령 0)"
ini i01; fault i01 "api-plat-${FET0#sha256:}" arm64; chk i01 U0 "" 1 "frontend 이미지 이름의 대상·manifest·config 조회 실패" 0
ini i02; fault i02 "save-cfgos-${I26#sha256:}" windows; chk i02 U0 "" 1 "backend 실행 이미지의 대상·manifest·config 조회 실패" 0
ini i03; fault i03 "save-badblob-${FET0#sha256:}" 1; chk i03 U0 "" 1 "frontend 이미지 이름의 대상·manifest·config 조회 실패" 0
ini i04; fault i04 "api-two-${I26#sha256:}" 1; chk i04 U0 "" 1 "backend 실행 이미지의 대상·manifest·config 조회 실패" 0
ini i05; env_ i05 CTR_NOMAN=frontend; chk i05 U0 "" 1 "실행 중 frontend 컨테이너가 이미지 이름의 manifest·config 와 연결되지 않음" 0
ini i06; echo "sha256:$(printf other | /usr/bin/sha256sum | cut -c1-64)" > runs/$P-i06/state/tag/b5cd1d0; echo x > runs/$P-i06/state/img/$(printf other | /usr/bin/sha256sum | cut -c1-64)
  chk i06 U0 "" 1 "보존 태그 autotrader-backend:b5cd1d0 이 이미 다른 이미지를 가리킨다" 0
ini i07; chk i07 U0 "" 0 "✅ U0 통과" 0; adv i07 300; env_ i07 API_FAIL=1
  chk i07 U1 "" 1 "실행 중 backend 가 U0 대응과 다름 또는 조회 실패(아직 바꾼 것 없음, U1-되돌림 하지 않는다)" 0
  is i07 "변경 명령 0(docker tag·build)" "$(( $(cnt i07 'docker	tag') + $(cnt i07 'docker	compose -f docker-compose.prod.yml build') ))" 0
ini i08; chk i08 U0 "" 0 "✅ U0 통과" 0; adv i08 300; env_ i08 SAVE_FAIL=1
  chk i08 U1 "" 1 "(아직 바꾼 것 없음, U1-되돌림 하지 않는다)" 0
  is i08 "변경 명령 0" "$(( $(cnt i08 'docker	tag') + $(cnt i08 'docker	compose -f docker-compose.prod.yml build') ))" 0

F=0; for d in runs/$P-*; do F=$((F + $(wc -l < $d/forbidden.log))); done
is 전체 "금지 명령 기록 줄 수 합" "$F" 0
echo "== 결과: PASS $PASS · FAIL $FAILN"
