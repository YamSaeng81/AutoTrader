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

echo "== x01 정상: 서버 LF jar 로 U0→U1→U2(V96)→U3→UF2→U4→U뒤관측 — JARM b6587953·대상/manifest/config 기록·새 컨테이너 대응"
ini x01; u0u1 x01
is x01 "jar-manifest-sha(JARM)" "$(mf x01 jar-manifest-sha.txt)" "JARM $JARMX"
is x01 "jar-diff 62줄+끝 줄" "$(wc -l < runs/$P-x01/home/$MV/jar-diff.txt)" 63
is x01 "image-before-chain" "$(cut -d' ' -f1-3 runs/$P-x01/home/$MV/image-before-chain.txt)" "$I26 $I26M $I26C"
is x01 "image-fe-before-chain" "$(cut -d' ' -f1-3 runs/$P-x01/home/$MV/image-fe-before-chain.txt)" "$FET0 $FEM0 $FEC0"
is x01 "frontend 보존 태그 = 대상 837db515(.Image d825a9f5 아님)" "$(st_ x01 fetag/b5cd1d0)" "$FET0"
is x01 "fe-stage" "$(mf x01 fe-stage.txt)" 빌드끝
is x01 "새 backend: 대상≠config(정상 이미지)" "$([ "$(cut -d' ' -f1 runs/$P-x01/home/$MV/image-new-chain.txt)" != "$(cut -d' ' -f3 runs/$P-x01/home/$MV/image-new-chain.txt)" ] && echo 다름)" 다름
is x01 HEAD "$(st_ x01 HEAD)" "$NEWC"; oldbe x01; oldfe x01
u2u3 x01
is x01 "U2 뒤 backend .Image = 새 대상·manifest = 새 manifest" "$(ctrf x01 backend 2-3)" "$(cut -d' ' -f1-2 runs/$P-x01/home/$MV/image-new-chain.txt)"
chk x01 UF2 "" 0 "✅ UF2 통과" 1
is x01 "UF2 뒤 frontend .Image·manifest = 새 대상·manifest" "$(ctrf x01 frontend 2-3)" "$(cut -d' ' -f1-2 runs/$P-x01/home/$MV/image-fe-new-chain.txt)"
is x01 "fe-stage" "$(mf x01 fe-stage.txt)" 교체끝
adv x01 1500; chk x01 U4 "" 0 "✅ U4 통과" 1; chk x01 U뒤관측 "" 0 "" 1
is x01 "start_66.py sha256" "$(fsha x01)" "$NH"
is x01 "옛 기록 폴더 maint-v168 안 만듦" "$([ -e runs/$P-x01/home/maint-v168 ] && echo 있음 || echo 없음)" 없음
is x01 "imgchain.py 원문 = ICSUM" "$(/usr/bin/sha256sum < runs/$P-x01/home/$MV/imgchain.py | cut -c1-64)" fddd5d1cae1b85e2a002d30afe24044869b79c4983c6cc46fe3363a0aba354f6

echo "== x02 UF2 뒤 U되돌림 — frontend 교체 뒤 복구(보존 대상으로 이름·재생성), backend #27"
ini x02; u0u1 x02; u2u3 x02; chk x02 UF2 "" 0 "✅ UF2 통과" 1
chk x02 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2
is x02 "판정" "$(mf x02 rb-identity.txt | cut -d' ' -f1-4)" "실행 판정 backend=새판 frontend=새판"
is x02 "frontend 재생성: .Image = 837db515(대상)·manifest fa5c9ba8" "$(ctrf x02 frontend 2-3)" "$FET0 $FEM0"
is x02 "backend 재생성: .Image = 52e12428·manifest ca440b84" "$(ctrf x02 backend 2-3)" "$I26 $I26M"
names0 x02; is x02 HEAD "$(st_ x02 HEAD)" "$OLDC"
adv x02 300; chk x02 U3 rb 0 "✅ U3(rollback) 통과" 2

echo "== x03 U1 통과(빌드끝) 뒤 U2 하지 않음 → U1되돌림 — 이름 두 개를 보존 대상으로, 실행 컨테이너 그대로"
ini x03; u0u1 x03; chk x03 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2
names0 x03; oldbe x03; oldfe x03; is x03 HEAD "$(st_ x03 HEAD)" "$OLDC"
is x03 "계획" "$(mf x03 u1rb-plan.txt)" "되돌릴 이미지 이름: backend latest 보존 태그로 · frontend 보존 태그로"
is x03 "재기동 없음(up 0)" "$(cnt x03 'docker	compose -f docker-compose.prod.yml up')" 0

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
