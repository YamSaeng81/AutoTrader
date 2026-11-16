#!/bin/bash
# V172D4 대역 — 총괄 v1.172 §4-2. scenarios-v171.sh 의 단언 함수를 옮기고 사례를 R1~R3 보완 범위와 그 영향 경로로 한정했다.
# 장치 보완(이번): 보존 태그 조회 API 흉내(있음 200·없음 404·고장 rc7/500/형식 이상/다른 404 본문 — 운영 응답 형식 state-1258),
#   뉴스 설정 SQL 실패(NEWS_FAIL)·backend 로그 조회 실패/빈 로그(LOGS_FAIL·LOGS_EMPTY), 옛 판 재현용 docker image inspect 실패(INSPECT_FAIL).
# 묶음: a = R1 보존 태그·함수 단위 시험·정상 전 구간·판 혼합 / b = R2 복구 전 이름 출처·정상 되돌림 / c = R3 P0 우선·영향 회귀·옛 V171D4 재현
# 사용: scenarios-v172.sh <접두어> <a|b|c>
set -u
cd /d/tmp/v172d4s/sim; P=${1:?접두어}; SEC=${2:?묶음}; T='2026-11-16 13:30:00'
. ./conf.sh
NH=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b
MV=maint-v172
JARMX=b65879533e1ba1c552f57e82b35b3993000319c4b0a81464d37a726ba42111f8
TCLASS=/d/tmp/v171/jars/tamper-class.jar
OB=/d/tmp/v172d4s/blocks-v171   # 1644851b V171D4 원문 10블록(재현 사례 전용)
PYREAL='/c/Users/SangJoon_Lee/AppData/Local/Microsoft/WindowsApps/PythonSoftwareFoundation.Python.3.13_qbz5n2kfra8p0/python.exe'
PASS=0; FAILN=0
ini() { ./init.sh "$P-$1" "${2:-$T}" > /dev/null; }
co() { grep -c "$(printf '\tgit\tcheckout ')" runs/$P-$1/calls.log; }
cnt() { grep -c "$(printf '\t')$2" runs/$P-$1/calls.log; }
chg() { awk -F'\t' '($2=="docker" && ($3 ~ /^tag / || $3 ~ /docker-compose.prod.yml (up|build) /)) || ($2=="git" && $3 ~ /^checkout /)' runs/$P-$1/calls.log | wc -l; }   # 변경 명령(tag·build·up·checkout) 누계
chk() { local c1=$1 b=$2 t=$3 wrc=$4 want=$5 wco=$6 rc c o
  ./sim.sh $P-$c1 $b $t > /dev/null 2>&1; rc=$?
  o=runs/$P-$c1/out/out-$b${t:+-$t}.txt; c=$(co $c1)
  if [ "$rc" = "$wrc" ] && grep -qF -- "$want" "$o" && [ "$c" = "$wco" ]; then PASS=$((PASS+1)); echo "PASS $c1 $b${t:+($t)} — 종료 $rc · '$want' · checkout $c"
  else FAILN=$((FAILN+1)); echo "FAIL $c1 $b${t:+($t)} — 종료 $rc(기대 $wrc) · '$want' · checkout $c(기대 $wco)"; grep -E '🔴|✅|대역 끝' "$o" | tail -3; fi; }
ochk() { D2_BLOCKS=$OB UVAR=$OB/U변수.sh chk "$@"; }   # 옛 V171D4 원문으로 실행
is() { if [ "$3" = "$4" ]; then PASS=$((PASS+1)); echo "PASS $1 — $2 = ${4:0:24}"; else FAILN=$((FAILN+1)); echo "FAIL $1 — $2 = $3 (기대 $4)"; fi; }
st_() { cat runs/$P-$1/state/$2 2>/dev/null || echo 없음; }
ctrf() { cut -d' ' -f$3 runs/$P-$1/state/ctr/$2; }
mf() { cat runs/$P-$1/home/$MV/$2 2>/dev/null || echo 없음; }
ex() { [ -e runs/$P-$1/home/${3:-$MV}/$2 ] && echo 있음 || echo 없음; }
fsha() { /usr/bin/sha256sum < runs/$P-$1/home/crypto_trader/AutoTrader/scripts/prospective/start_66.py | cut -c1-64; }
adv() { ./adv.sh $P-$1 $2 > /dev/null; }
env_() { printf '%s\n' "$2" >> runs/$P-$1/env.sh; }
unenv() { sed -i "/^$2=/d" runs/$P-$1/env.sh; }
fault() { printf '%s\n' "$3" > runs/$P-$1/state/fault/$2; }
mkimg() {   # mkimg <사례> <backend|frontend> <씨앗> → 저장소에 완전한(대상·manifest·config) 제3의 이미지를 만들고 대상 ID 출력
  local id; id=$(PYTHONUTF8=1 "$PYREAL" -I -X utf8 imgstate.py build "runs/$P-$1/state" "$2" "third-$3" | tr -d '\r')
  if [ "$2" = backend ]; then echo "$JAR_OLD" > runs/$P-$1/state/img/${id#sha256:}; else echo frontend > runs/$P-$1/state/img/${id#sha256:}; fi; echo "$id"; }
u0u1() { chk $1 U0 "" 0 "✅ U0 통과" 0; adv $1 300; chk $1 U1 "" 0 "✅ U1 통과" 1; }
u2u3() { chk $1 U2 "" 0 "✅ U2 통과" 1; adv $1 300; chk $1 U3 "" 0 "✅ U3(deploy) 통과" 1; }
ou0u1() { ochk $1 U0 "" 0 "✅ U0 통과" 0; adv $1 300; ochk $1 U1 "" 0 "✅ U1 통과" 1; }
oldfe() { is $1 "frontend 실행 컨테이너 그대로" "$(cat runs/$P-$1/state/ctr/frontend)" "9a63d15ab1106830ca5d4d59bda98b440005f5310743b8e16c2e2f0079c0fe4a $FEI0 $FEM0"; }
oldbe() { is $1 "backend 실행 컨테이너 그대로" "$(cat runs/$P-$1/state/ctr/backend)" "743dfebac8c062011d7711bb59ebe727ee1e174724526b0160f6921afd5bfece $I26 $I26M"; }
names0() { is $1 "backend latest·보존 태그 = #27 대상" "$(st_ $1 tag/latest) $(st_ $1 tag/b5cd1d0)" "$I26 $I26"; is $1 "frontend 이미지 이름·보존 태그 = 837db515" "$(st_ $1 fetag/latest) $(st_ $1 fetag/b5cd1d0)" "$FET0 $FET0"; }
OTHER=sha256:$(printf other | /usr/bin/sha256sum | cut -c1-64)
setother() { echo "$OTHER" > runs/$P-$1/state/$2; echo x > runs/$P-$1/state/img/${OTHER#sha256:}; }   # 보존 태그가 해석 불가한 다른 대상(i06 과 같은 방식)

if [ "$SEC" = a ]; then
echo "== UT 함수 단위 — same3 0/1/2 · tagkeep 0/1/2/3 · namecls 원판/새판/제3/불명(U변수 원문 정의, rchain·tagq 만 대역 함수)"
ini ut; chk ut UT "" 0 "UT 결과 15/15" 0

echo "== x01 정상 전 구간 — 운영과 같은 시작 상태(backend 보존 태그 있음·같음 → 다시 쓰지 않음, frontend 보존 태그 404 → 만든다)"
ini x01; chk x01 U0 "" 0 "보존 태그 autotrader-backend:b5cd1d0 있음 — #27 backend 대응과 같음" 0
is x01 "U0 frontend 보존 태그 404 판정 줄" "$(grep -c '보존 태그 autotrader-frontend:b5cd1d0 없음(API 404 확인)' runs/$P-x01/out/out-U0.txt)" 1
adv x01 300; chk x01 U1 "" 0 "✅ U1 통과" 1
is x01 "U1 보존 태그 판정" "$(mf x01 tag-keep-u1.txt)" "보존 태그 판정 backend=0 frontend=1 (0 있음·같음 / 1 명시적 부재 / 2 다른 대상 / 3 조회 불명)"
is x01 "backend 보존 태그 다시 쓰지 않음" "$(cnt x01 'docker	tag sha256:52e12428')" 0
is x01 "frontend 보존 태그 1회 생성" "$(cnt x01 'docker	tag sha256:837db515')" 1
is x01 JARM "$(mf x01 jar-manifest-sha.txt)" "JARM $JARMX"
u2u3 x01; chk x01 UF2 "" 0 "✅ UF2 통과" 1
is x01 "U3 P0 1차 검사 기록" "$(ex x01 holdobs-deploy-p0.txt)" 있음
adv x01 1500; chk x01 U4 "" 0 "✅ U4 통과" 1; chk x01 U뒤관측 "" 0 "" 1
is x01 "U4·U뒤관측 P0 1차 검사 기록" "$(ex x01 holdobs-after30-p0.txt) $(ls runs/$P-x01/home/$MV/ | grep -c '^holdobs-2026.*-p0.txt$')" "있음 1"
is x01 "start_66.py sha256" "$(fsha x01)" "$NH"
is x01 "imgchain.py = ICSUM" "$(/usr/bin/sha256sum < runs/$P-x01/home/$MV/imgchain.py | cut -c1-64)" fddd5d1cae1b85e2a002d30afe24044869b79c4983c6cc46fe3363a0aba354f6

echo "== k01 두 보존 태그 모두 명시적 부재(404) → U0 통과 · U1 이 두 개를 만든다"
ini k01; rm runs/$P-k01/state/tag/b5cd1d0; chk k01 U0 "" 0 "보존 태그 autotrader-backend:b5cd1d0 없음(API 404 확인) — U1 이 만든다" 0; adv k01 300
chk k01 U1 "" 0 "✅ U1 통과" 1; is k01 "판정" "$(mf k01 tag-keep-u1.txt | cut -d' ' -f1-4)" "보존 태그 판정 backend=1 frontend=1"
is k01 "만든 태그" "$(st_ k01 tag/b5cd1d0) $(st_ k01 fetag/b5cd1d0)" "$I26 $FET0"

echo "== k02 U0 backend 보존 태그 조회 rc7 → U0 멈춤(ok-U0 없음)"
ini k02; env_ k02 'TAGQ_FAULT=autotrader-backend:b5cd1d0=rc7'; chk k02 U0 "" 1 "보존 태그 autotrader-backend:b5cd1d0 조회 불명(권한·daemon·API·형식) — 없음으로 보지 않는다" 0
is k02 "ok-U0" "$(ex k02 ok-U0)" 없음
ini k02f; env_ k02f 'TAGQ_FAULT=autotrader-frontend:b5cd1d0=http500'; chk k02f U0 "" 1 "보존 태그 autotrader-frontend:b5cd1d0 조회 불명" 0

echo "== k03~k07 U1 태그 조회 불명 → 바꾸기 전 멈춤(tag·build·up·checkout 0)"
while IFS='|' read -r c f w; do
  ini $c; chk $c U0 "" 0 "✅ U0 통과" 0; adv $c 300; env_ $c "TAGQ_FAULT='$f'"; n0=$(chg $c)
  chk $c U1 "" 1 "$w" 0; is $c "변경 명령 0" "$(( $(chg $c) - n0 ))" 0
  is $c "태그 그대로(backend 보존=#27·frontend 보존 없음)" "$(st_ $c tag/b5cd1d0) $(st_ $c fetag/b5cd1d0)" "$I26 없음"
done <<'KEOF'
k03|autotrader-frontend:b5cd1d0=rc7|autotrader-frontend:b5cd1d0 태그 조회 불명
k04|autotrader-backend:b5cd1d0=http500|autotrader-backend:b5cd1d0 태그 조회 불명
k05|*=rc7|autotrader-backend:b5cd1d0 태그 조회 불명
k06|autotrader-backend:b5cd1d0=badbody|autotrader-backend:b5cd1d0 태그 조회 불명
k07|autotrader-frontend:b5cd1d0=nf404|autotrader-frontend:b5cd1d0 태그 조회 불명
KEOF
is k05 "두 태그 판정 기록(둘 다 3)" "$(mf k05 tag-keep-u1.txt | cut -d' ' -f1-4)" "보존 태그 판정 backend=3 frontend=3"

echo "== k08 U1 backend 보존 태그가 다른 대상 → 멈춤 · k10 다른 대상 + 조회 rc7 → 멈춤, 덮어쓰지 않음(o02 와 대조)"
ini k08; chk k08 U0 "" 0 "✅ U0 통과" 0; adv k08 300; I8=$(mkimg k08 frontend k08); echo "$I8" > runs/$P-k08/state/fetag/b5cd1d0
chk k08 U1 "" 1 "autotrader-frontend:b5cd1d0 태그가 이미 다른 이미지를 가리킨다" 0; is k08 "변경 명령 0" "$(chg k08)" 0
is k08 "판정 frontend=2" "$(mf k08 tag-keep-u1.txt | cut -d' ' -f1-4)" "보존 태그 판정 backend=0 frontend=2"
ini k10; chk k10 U0 "" 0 "✅ U0 통과" 0; adv k10 300; setother k10 tag/b5cd1d0; env_ k10 "TAGQ_FAULT='autotrader-backend:b5cd1d0=rc7'"
chk k10 U1 "" 1 "autotrader-backend:b5cd1d0 태그 조회 불명" 0; is k10 "다른 대상 보존 태그 그대로" "$(st_ k10 tag/b5cd1d0)" "$OTHER"; is k10 "변경 명령 0" "$(chg k10)" 0

echo "== 판 혼합·기록 폴더 거부"
ini x07; UVAR=$OB/U변수.sh chk x07 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.172 통합 후보 초안 D4 U 변수" 0   # 옛 V171D4 U변수 + 새 블록
ini x08; D2_BLOCKS=$OB chk x08 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.171 통합 후보 초안 D4 U 변수" 0   # 새 U변수 + 옛 V171D4 블록
ini x09; printf 'M=~/maint-v171\n' >> runs/$P-x09/vars.sh; chk x09 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.172 통합 후보 초안 D4 U 변수" 0
ini x10; u0u1 x10; printf 'UB=V171D4\n' >> runs/$P-x10/vars.sh; chk x10 U2 "" 1 "U 변수가 v1.172 통합 후보 초안 D4 판이 아님" 1
fi

if [ "$SEC" = b ]; then
echo "== x02 UF2 뒤 U되돌림(정상 명시 복구) — backend latest=새판 → 되돌림, frontend 재생성"
ini x02; u0u1 x02; u2u3 x02; chk x02 UF2 "" 0 "✅ UF2 통과" 1
chk x02 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2
is x02 "판정" "$(mf x02 rb-identity.txt)" "실행 판정 backend=새판 frontend=새판 · frontend 이미지 이름 U1새판 · 단계 교체끝 · backend latest 새판"
is x02 "frontend 재생성 .Image·manifest" "$(ctrf x02 frontend 2-3)" "$FET0 $FEM0"; is x02 "backend 재생성 .Image·manifest" "$(ctrf x02 backend 2-3)" "$I26 $I26M"
names0 x02; adv x02 300; chk x02 U3 rb 0 "✅ U3(rollback) 통과" 2

echo "== x03 U1 통과 뒤 U1되돌림(정상) — 두 이름 새판 → 보존 대상으로, 재기동 없음"
ini x03; u0u1 x03; chk x03 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2
is x03 "이름 판정" "$(mf x03 u1rb-names.txt)" "이름 출처 판정 backend latest=새판 · frontend=새판 · frontend 단계 빌드끝"
names0 x03; oldbe x03; oldfe x03; is x03 "up 0" "$(cnt x03 'docker	compose -f docker-compose.prod.yml up')" 0

echo "== x04 클래스 변조 jar → JARM 멈춤 → U1되돌림(backend=새판·frontend=원판, 단계 보존태그)"
ini x04; chk x04 U0 "" 0 "✅ U0 통과" 0; adv x04 300; env_ x04 "BUILD_JAR=$TCLASS"
chk x04 U1 "" 1 "새 jar 항목 내용이 후보 0e1d2a02(제품 입력 99e6ab10) 목록(JARM)과 다름" 1
chk x04 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2; is x04 "이름 판정" "$(mf x04 u1rb-names.txt)" "이름 출처 판정 backend latest=새판 · frontend=원판 · frontend 단계 보존태그"; names0 x04

echo "== f02 frontend 빌드 실패(이름 그대로) → U1되돌림 backend 만"
ini f02; chk f02 U0 "" 0 "✅ U0 통과" 0; adv f02 300; env_ f02 FE_BUILD_FAIL=1
chk f02 U1 "" 1 "frontend 빌드 실패" 1; chk f02 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2; names0 f02; oldfe f02
is f02 "계획" "$(mf f02 u1rb-plan.txt)" "되돌릴 이미지 이름: backend latest 보존 태그로 · frontend 그대로"

echo "== f03 빌드시작 뒤 frontend 이름이 바뀐 채 빌드 실패(새 대응 기록 없음) → U1되돌림이 출처 미확인으로 바꾸기 전 멈춤"
ini f03; chk f03 U0 "" 0 "✅ U0 통과" 0; adv f03 300; env_ f03 FE_BUILD_FAIL_AFTER_TAG=1
chk f03 U1 "" 1 "frontend 빌드 실패" 1; is f03 "새 frontend 대응 기록 없음" "$(ex f03 image-fe-new-chain.txt)" 없음
FL3=$(st_ f03 fetag/latest); n0=$(chg f03)
chk f03 U1되돌림 "" 1 "frontend 이미지 이름이 U0 원판·U1 이번 빌드 기록(image-fe-new-chain) 어느 것도 아님" 1
is f03 "변경 명령 0" "$(( $(chg f03) - n0 ))" 0; is f03 "HEAD 그대로(NEW)" "$(st_ f03 HEAD)" "$NEWC"
is f03 "이름 그대로(backend 새·frontend 바뀐 값)" "$(st_ f03 tag/latest) $(st_ f03 fetag/latest)" "$(mf f03 image-new.txt) $FL3"

echo "== q01 빌드시작 + 제3의 frontend(빌드 실패 뒤 이름이 무관한 대상) → 멈춤 · 총괄 R2a 분기"
ini q01; chk q01 U0 "" 0 "✅ U0 통과" 0; adv q01 300; env_ q01 FE_BUILD_FAIL=1; chk q01 U1 "" 1 "frontend 빌드 실패" 1
Q1=$(mkimg q01 frontend q01); echo "$Q1" > runs/$P-q01/state/fetag/latest; n0=$(chg q01)
chk q01 U1되돌림 "" 1 "frontend 이미지 이름이 U0 원판·U1 이번 빌드 기록(image-fe-new-chain) 어느 것도 아님" 1
is q01 "변경 명령 0" "$(( $(chg q01) - n0 ))" 0; is q01 "제3 frontend 그대로" "$(st_ q01 fetag/latest)" "$Q1"

echo "== q02 U1 통과 뒤 backend latest = 제3 → U1되돌림 멈춤"
ini q02; u0u1 q02; Q2=$(mkimg q02 backend q02); echo "$Q2" > runs/$P-q02/state/tag/latest; n0=$(chg q02)
chk q02 U1되돌림 "" 1 "backend latest 가 U0 원판·U1 이번 빌드 기록(image-new-chain) 어느 것도 아님" 1
is q02 "변경 명령 0" "$(( $(chg q02) - n0 ))" 0; is q02 "제3 latest 그대로" "$(st_ q02 tag/latest)" "$Q2"

echo "== q03·q07 U1 통과 뒤 backend latest(이번 빌드판) 대응 조회 실패 — save(q03)·API(q07) → U1되돌림 조회 불명 멈춤"
for v in q03:save-badblob q07:api-two; do c=${v%%:*}; ini $c; u0u1 $c; fault $c "${v#*:}-$(cut -c8- runs/$P-$c/home/$MV/image-new.txt)" 1; n0=$(chg $c)
  chk $c U1되돌림 "" 1 "backend latest 대응 조회 불명 — 원판·새판으로 추정하지 않는다" 1; is $c "변경 명령 0" "$(( $(chg $c) - n0 ))" 0; is $c "HEAD 그대로(NEW)" "$(st_ $c HEAD)" "$NEWC"; done

echo "== q04 U2 뒤 backend latest = 제3 → U되돌림 바꾸기 전 멈춤(총괄 R2b 분기)"
ini q04; u0u1 q04; u2u3 q04; Q4=$(mkimg q04 backend q04); echo "$Q4" > runs/$P-q04/state/tag/latest; n0=$(chg q04)
chk q04 U되돌림 "" 1 "backend latest 가 U0 원판·U1 이번 빌드판 어느 것도 아님(제3의 대상)" 1
is q04 "변경 명령 0" "$(( $(chg q04) - n0 ))" 0; is q04 "제3 latest 그대로" "$(st_ q04 tag/latest)" "$Q4"; is q04 "관문 실행 안 함" "$(ex q04 gate-out.txt)" 없음

echo "== q05 U3 뒤(UF2 전) frontend 이름 대응 조회 실패(API) → U되돌림 멈춤 · q06 제3 latest + 조회 실패(save) → 조회 불명 멈춤"
ini q05; u0u1 q05; u2u3 q05; fault q05 "api-two-$(cut -c8- runs/$P-q05/home/$MV/image-fe-new.txt)" 1; n0=$(chg q05)
chk q05 U되돌림 "" 1 "frontend 이미지 이름 대응 조회 불명" 1; is q05 "변경 명령 0" "$(( $(chg q05) - n0 ))" 0
ini q06; u0u1 q06; u2u3 q06; Q6=$(mkimg q06 backend q06); echo "$Q6" > runs/$P-q06/state/tag/latest; fault q06 "save-badblob-${Q6#sha256:}" 1; n0=$(chg q06)
chk q06 U되돌림 "" 1 "backend latest 대응 조회 불명" 1; is q06 "변경 명령 0" "$(( $(chg q06) - n0 ))" 0

echo "== q08 U2 뒤 backend latest 가 이미 원판(#27)으로 돌아가 있음 → U되돌림이 태그를 쓰지 않고 재생성·복귀"
ini q08; u0u1 q08; u2u3 q08; echo "$I26" > runs/$P-q08/state/tag/latest; t0=$(cnt q08 'docker	tag')
chk q08 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2; is q08 "backend latest 판정 원판" "$(mf q08 rb-identity.txt | grep -o 'backend latest .*')" "backend latest 원판"
is q08 "이번 U되돌림의 docker tag 는 frontend 이름 1회뿐" "$(( $(cnt q08 'docker	tag') - t0 )) $(grep -c 'tag sha256:52e12428.* autotrader-backend:latest' runs/$P-q08/calls.log)" "1 0"
is q08 "backend 재생성 .Image·manifest" "$(ctrf q08 backend 2-3)" "$I26 $I26M"

echo "== r01 UF2 응답 500 → U되돌림 frontend 복구 · r02 frontend 교체 전 U되돌림(이름만)"
ini r01; u0u1 r01; u2u3 r01; env_ r01 FE_HTTP=500; chk r01 UF2 "" 1 "frontend 되돌림 필요: / 응답 500" 1
unenv r01 FE_HTTP; chk r01 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2; is r01 "frontend 재생성 .Image·manifest" "$(ctrf r01 frontend 2-3)" "$FET0 $FEM0"; names0 r01
ini r02; u0u1 r02; u2u3 r02; chk r02 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2; oldfe r02; names0 r02
is r02 "frontend 재생성 0" "$(grep -c 'force-recreate frontend' runs/$P-r02/calls.log)" 0
fi

if [ "$SEC" = c ]; then
echo "== p01 P0 + 이미지 조회 실패 → P0 · p02 이미지 조회 실패만 → 대응 멈춤"
ini p01; u0u1 p01; env_ p01 NEWLOG=p0; chk p01 U2 "" 0 "✅ U2 통과" 1; adv p01 300; env_ p01 API_FAIL=1
chk p01 U3 "" 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다" 1
ini p02; u0u1 p02; chk p02 U2 "" 0 "✅ U2 통과" 1; adv p02 300; env_ p02 API_FAIL=1
chk p02 U3 "" 1 "실행 이미지가 기대 대상·manifest·config 와 다름 또는 조회 실패" 1

echo "== p03 P0 + 뉴스 설정 SQL 실패 동시 → P0 로 멈춤(총괄 R3 분기) · p06 뉴스 SQL 실패만 → P0 1차 검사 뒤 뉴스 멈춤"
ini p03; u0u1 p03; env_ p03 NEWLOG=p0; chk p03 U2 "" 0 "✅ U2 통과" 1; adv p03 300; env_ p03 NEWS_FAIL=1; n0=$(chg p03)
chk p03 U3 "" 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다" 1; is p03 "P0 1차 검사 기록" "$(ex p03 holdobs-deploy-p0.txt)" 있음
is p03 "뉴스 SQL 앞에서 멈춤(news-deploy 없음)" "$(ex p03 news-deploy.txt)" 없음; is p03 "변경 명령 0" "$(( $(chg p03) - n0 ))" 0
ini p06; u0u1 p06; chk p06 U2 "" 0 "✅ U2 통과" 1; adv p06 300; env_ p06 NEWS_FAIL=1
chk p06 U3 "" 1 "뉴스 설정 조회 실패 — 1분 뒤 U3 를 다시" 1; is p06 "P0 1차 검사 기록" "$(ex p06 holdobs-deploy-p0.txt)" 있음

echo "== p04 로그 조회 실패 · p05 빈 로그 → P0 검사 미완료로 멈춤(0 으로 보지 않음)"
ini p04; u0u1 p04; chk p04 U2 "" 0 "✅ U2 통과" 1; adv p04 300; env_ p04 LOGS_FAIL=1; env_ p04 NEWS_FAIL=1
chk p04 U3 "" 1 "🔴 P0 검사 미완료 — 기동 구간 로그를 확보하지 못함" 1; is p04 "ok-U3 없음" "$(ex p04 ok-U3-deploy)" 없음
ini p05; u0u1 p05; chk p05 U2 "" 0 "✅ U2 통과" 1; adv p05 300; env_ p05 LOGS_EMPTY=1
chk p05 U3 "" 1 "🔴 P0 검사 미완료 — 기동 구간 로그가 비었다" 1

echo "== p07 U4: P0 + 뉴스 SQL 실패 → P0 · p08 U뒤관측: P0 + 이미지·뉴스 조회 실패 → P0 · p10 U4 로그 실패 → 미완료"
ini p07; u0u1 p07; u2u3 p07; chk p07 UF2 "" 0 "✅ UF2 통과" 1; adv p07 1500; cp runs/$P-p07/fix/log-p0.log runs/$P-p07/state/backend.log; env_ p07 NEWS_FAIL=1
chk p07 U4 "" 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다" 1; is p07 "뉴스 SQL 앞에서 멈춤" "$(ex p07 news-after30.txt)" 없음
ini p08; u0u1 p08; u2u3 p08; chk p08 UF2 "" 0 "✅ UF2 통과" 1; adv p08 1500; chk p08 U4 "" 0 "✅ U4 통과" 1
cp runs/$P-p08/fix/log-p0.log runs/$P-p08/state/backend.log; env_ p08 API_FAIL=1; env_ p08 NEWS_FAIL=1
chk p08 U뒤관측 "" 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다" 1
ini p10; u0u1 p10; u2u3 p10; chk p10 UF2 "" 0 "✅ UF2 통과" 1; adv p10 1500; env_ p10 LOGS_FAIL=1
chk p10 U4 "" 1 "🔴 P0 검사 미완료 — 기동 구간 로그를 확보하지 못함" 1

echo "== p09 U되돌림 뒤 U3(rollback): P0 + 뉴스 SQL 실패 → P0"
ini p09; u0u1 p09; u2u3 p09; env_ p09 ROLLLOG=p0; chk p09 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2; adv p09 300; env_ p09 NEWS_FAIL=1
chk p09 U3 rb 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다" 2

echo "== 영향 회귀 — i06 U0 다른 대상 보존 태그 · t01 latest 변경 → U2 멈춤 · i07 API 실패 → U1 변경 0"
ini i06; setother i06 tag/b5cd1d0; chk i06 U0 "" 1 "보존 태그 autotrader-backend:b5cd1d0 조회 불명" 0   # 다른 대상이지만 대응 조회도 실패(해석 불가 대상) → 조회 불명으로 멈춤
ini i06b; I6=$(mkimg i06b backend i06b); echo "$I6" > runs/$P-i06b/state/tag/b5cd1d0
chk i06b U0 "" 1 "보존 태그 autotrader-backend:b5cd1d0 이 이미 다른 이미지를 가리킨다" 0
ini t01; u0u1 t01; T1=$(mkimg t01 backend t01); echo "$T1" > runs/$P-t01/state/tag/latest
chk t01 U2 "" 1 "latest 가 image-new 와 다름" 1; is t01 "up 0" "$(cnt t01 'docker	compose -f docker-compose.prod.yml up')" 0
ini i07; chk i07 U0 "" 0 "✅ U0 통과" 0; adv i07 300; env_ i07 API_FAIL=1
chk i07 U1 "" 1 "(아직 바꾼 것 없음, U1-되돌림 하지 않는다)" 0; is i07 "변경 명령 0" "$(chg i07)" 0

echo "== 옛 V171D4 원문(1644851b)을 같은 장치로 — 총괄이 지적한 세 공백 재현"
ini o02; ochk o02 U0 "" 0 "✅ U0 통과" 0; adv o02 300; setother o02 tag/b5cd1d0; env_ o02 "INSPECT_FAIL=autotrader-backend:b5cd1d0"
ochk o02 U1 "" 0 "✅ U1 통과" 1; is o02 "R1 재현: 조회 실패를 부재로 보고 다른 대상 보존 태그를 덮어씀" "$(st_ o02 tag/b5cd1d0)" "$I26"
ini o03; ou0u1 o03; ochk o03 U2 "" 0 "✅ U2 통과" 1; adv o03 300; ochk o03 U3 "" 0 "✅ U3(deploy) 통과" 1
O3=$(mkimg o03 backend o03); echo "$O3" > runs/$P-o03/state/tag/latest
ochk o03 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2; is o03 "R2b 재현: 제3 latest 를 확인 없이 덮어씀" "$(st_ o03 tag/latest)" "$I26"
ini o04; ochk o04 U0 "" 0 "✅ U0 통과" 0; adv o04 300; env_ o04 FE_BUILD_FAIL=1; ochk o04 U1 "" 1 "frontend 빌드 실패" 1
O4=$(mkimg o04 frontend o04); echo "$O4" > runs/$P-o04/state/fetag/latest
ochk o04 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2; is o04 "R2a 재현: 빌드시작 + 제3 frontend 를 복구 계획에 넣고 덮어씀" "$(st_ o04 fetag/latest)" "$FET0"
ini o05; ou0u1 o05; env_ o05 NEWLOG=p0; ochk o05 U2 "" 0 "✅ U2 통과" 1; adv o05 300; env_ o05 NEWS_FAIL=1
ochk o05 U3 "" 1 "뉴스 설정 조회 실패" 1; is o05 "R3 재현: P0 로그 검사 미도달" "$(ex o05 holdobs-deploy.txt maint-v171) $(ex o05 app-deploy.log maint-v171)" "없음 없음"
fi

F=0; for d in runs/$P-*; do F=$((F + $(wc -l < $d/forbidden.log))); done
is 전체 "금지 명령 기록 줄 수 합" "$F" 0
echo "== 결과($SEC): PASS $PASS · FAIL $FAILN"
