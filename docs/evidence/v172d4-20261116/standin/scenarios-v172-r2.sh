#!/bin/bash
# V172D4 대역 — 총괄 v1.172 §4-2. scenarios-v171.sh 의 단언 함수를 옮기고 사례를 R1~R3 보완 범위와 그 영향 경로로 한정했다.
# 장치 보완(이번): 보존 태그 조회 API 흉내(있음 200·없음 404·고장 rc7/500/형식 이상/다른 404 본문 — 운영 응답 형식 state-1258),
#   뉴스 설정 SQL 실패(NEWS_FAIL)·backend 로그 조회 실패/빈 로그(LOGS_FAIL·LOGS_EMPTY), 옛 판 재현용 docker image inspect 실패(INSPECT_FAIL).
# 묶음: a = R1 보존 태그·함수 단위 시험·정상 전 구간·판 혼합 / b = R2 복구 전 이름 출처·정상 되돌림 / c = R3 P0 우선·영향 회귀·옛 V171D4 재현
# r2 묶음: d1 = 정상 전 구간·r1 시나리오 실수 재확인 / d2 = 두 번째 로그 P0·판정 실패·R3 사례(바뀐 U3·U4·U뒤관측)
# 사용: scenarios-v172-r2.sh <접두어> <d1|d2>
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


# ── r2(총괄 추가 검토 메모 postlog 반영 뒤) — 바뀐 블록 U3·U4·U뒤관측을 지나는 사례와 새 사례, r1 시나리오 쪽 실수 4건의 재확인 ──
# 장치 추가: logs-next(첫 backend 로그 조회 뒤 로그 내용 교체) · logs-fail-next(두 번째 조회부터 실패) · PY_HOLD_FAIL(<경로 일부>: 그 로그를 받은 holdobs 만 종료 2)
swaplog() { cp runs/$P-$1/fix/log-$2.log runs/$P-$1/state/logs-next; }   # 다음 블록의 첫 로그 조회 뒤 로그를 $2 로 바꾼다
if [ "$SEC" = d1 ]; then
echo "== x01 정상 전 구간(U3·U4·U뒤관측 새 해시)"
ini x01; u0u1 x01; u2u3 x01; chk x01 UF2 "" 0 "✅ UF2 통과" 1
adv x01 1500; chk x01 U4 "" 0 "✅ U4 통과" 1; chk x01 U뒤관측 "" 0 "" 1
is x01 "U3·U4 P0 1차 검사 기록" "$(ex x01 holdobs-deploy-p0.txt) $(ex x01 holdobs-after30-p0.txt)" "있음 있음"
is x01 "U뒤관측 두 검사 기록" "$(ls runs/$P-x01/home/$MV/ | grep -cE '^holdobs-2026[0-9-]*(-p0)?\.txt$')" 2
is x01 "start_66.py sha256" "$(fsha x01)" "$NH"

echo "== r1 시나리오 쪽 실수 재확인 — k01·k05·k08 판정 줄 전체(5필드), x08 새 U변수 + 옛 V171D4 블록"
ini k01; rm runs/$P-k01/state/tag/b5cd1d0; chk k01 U0 "" 0 "보존 태그 autotrader-backend:b5cd1d0 없음(API 404 확인) — U1 이 만든다" 0; adv k01 300
chk k01 U1 "" 0 "✅ U1 통과" 1; is k01 "판정" "$(mf k01 tag-keep-u1.txt | cut -d' ' -f1-5)" "보존 태그 판정 backend=1 frontend=1"
ini k05; chk k05 U0 "" 0 "✅ U0 통과" 0; adv k05 300; env_ k05 "TAGQ_FAULT='*=rc7'"
chk k05 U1 "" 1 "autotrader-backend:b5cd1d0 태그 조회 불명" 0; is k05 "변경 명령 0" "$(chg k05)" 0
is k05 "두 태그 판정 기록(둘 다 3)" "$(mf k05 tag-keep-u1.txt | cut -d' ' -f1-5)" "보존 태그 판정 backend=3 frontend=3"
ini k08; chk k08 U0 "" 0 "✅ U0 통과" 0; adv k08 300; I8=$(mkimg k08 frontend k08); echo "$I8" > runs/$P-k08/state/fetag/b5cd1d0
chk k08 U1 "" 1 "autotrader-frontend:b5cd1d0 태그가 이미 다른 이미지를 가리킨다" 0; is k08 "변경 명령 0" "$(chg k08)" 0
is k08 "판정 frontend=2" "$(mf k08 tag-keep-u1.txt | cut -d' ' -f1-5)" "보존 태그 판정 backend=0 frontend=2"
ini x08; UVAR=/d/tmp/v172d4s/blocks-d4/U변수.sh D2_BLOCKS=$OB chk x08 U0 "" 1 "변수 순서 — F 변수 → G 변수 → v1.171 통합 후보 초안 D4 U 변수" 0
fi

if [ "$SEC" = d2 ]; then
echo "== p11 U뒤관측: 첫 로그 정상 → 두 번째 로그에 P0 → 즉시 멈춤(postlog 지적 분기)"
ini p11; u0u1 p11; u2u3 p11; chk p11 UF2 "" 0 "✅ UF2 통과" 1; adv p11 1500; chk p11 U4 "" 0 "✅ U4 통과" 1; swaplog p11 p0
chk p11 U뒤관측 "" 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다(두 번째 로그)" 1
is p11 "첫 로그 검사 기록 있음·첫 검사 P0 멈춤 없음" "$(ls runs/$P-p11/home/$MV/ | grep -c '^holdobs-2026.*-p0.txt$') $(grep -c '줄이 있다. 즉시 보고' runs/$P-p11/out/out-U뒤관측.txt)" "1 0"
is p11 "뒤 관측 미진행(newsobs 기록 없음)" "$(ls runs/$P-p11/home/$MV/ | grep -c '^newsobs-2026')" 0

echo "== p12 U뒤관측: 첫 로그 정상 → 두 번째 holdobs 판정 실패(종료 2) → 검사 미완료로 멈춤 · p16 두 번째 로그 조회 실패 → 멈춤"
ini p12; u0u1 p12; u2u3 p12; chk p12 UF2 "" 0 "✅ UF2 통과" 1; adv p12 1500; chk p12 U4 "" 0 "✅ U4 통과" 1; env_ p12 PY_HOLD_FAIL=app-news-
chk p12 U뒤관측 "" 1 "🔴 P0 검사 미완료 — 두 번째 로그 holdobs 종료 2(판정 실패)" 1
ini p16; u0u1 p16; u2u3 p16; chk p16 UF2 "" 0 "✅ UF2 통과" 1; adv p16 1500; chk p16 U4 "" 0 "✅ U4 통과" 1; : > runs/$P-p16/state/logs-fail-next
chk p16 U뒤관측 "" 1 "🔴 P0 검사 미완료 — 두 번째 로그를 확보하지 못함" 1

echo "== p13 U3·p14 U4: 첫 로그 정상 → 두 번째 holdobs 판정 실패 → 멈춤 · p15 U3 첫 로그 정상 → 두 번째 로그 P0 → 멈춤"
ini p13; u0u1 p13; chk p13 U2 "" 0 "✅ U2 통과" 1; adv p13 300; env_ p13 PY_HOLD_FAIL=app-deploy.log
chk p13 U3 "" 1 "🔴 P0 검사 미완료 — 두 번째 로그 holdobs 종료 2(판정 실패)" 1; is p13 "ok-U3 없음" "$(ex p13 ok-U3-deploy)" 없음
ini p14; u0u1 p14; u2u3 p14; chk p14 UF2 "" 0 "✅ UF2 통과" 1; adv p14 1500; env_ p14 PY_HOLD_FAIL=app-after30.log
chk p14 U4 "" 1 "🔴 P0 검사 미완료 — 두 번째 로그 holdobs 종료 2(판정 실패)" 1; is p14 "ok-U4 없음" "$(ex p14 ok-U4)" 없음
ini p15; u0u1 p15; chk p15 U2 "" 0 "✅ U2 통과" 1; adv p15 300; swaplog p15 p0
chk p15 U3 "" 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다" 1; is p15 "첫 로그 검사 기록 있음" "$(ex p15 holdobs-deploy-p0.txt)" 있음

echo "== R3 사례(바뀐 블록으로 다시) — p03 U3 P0+뉴스 실패 · p06 뉴스만 · p07 U4 P0+뉴스 · p08 U뒤관측 첫 로그 P0+조회 실패 · p09 U3 rollback · p10 U4 로그 실패"
ini p03; u0u1 p03; env_ p03 NEWLOG=p0; chk p03 U2 "" 0 "✅ U2 통과" 1; adv p03 300; env_ p03 NEWS_FAIL=1
chk p03 U3 "" 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다" 1; is p03 "뉴스 SQL 앞에서 멈춤" "$(ex p03 news-deploy.txt)" 없음
ini p06; u0u1 p06; chk p06 U2 "" 0 "✅ U2 통과" 1; adv p06 300; env_ p06 NEWS_FAIL=1
chk p06 U3 "" 1 "뉴스 설정 조회 실패 — 1분 뒤 U3 를 다시" 1; is p06 "P0 1차 검사 기록" "$(ex p06 holdobs-deploy-p0.txt)" 있음
ini p07; u0u1 p07; u2u3 p07; chk p07 UF2 "" 0 "✅ UF2 통과" 1; adv p07 1500; cp runs/$P-p07/fix/log-p0.log runs/$P-p07/state/backend.log; env_ p07 NEWS_FAIL=1
chk p07 U4 "" 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다" 1; is p07 "뉴스 SQL 앞에서 멈춤" "$(ex p07 news-after30.txt)" 없음
ini p08; u0u1 p08; u2u3 p08; chk p08 UF2 "" 0 "✅ UF2 통과" 1; adv p08 1500; chk p08 U4 "" 0 "✅ U4 통과" 1
cp runs/$P-p08/fix/log-p0.log runs/$P-p08/state/backend.log; env_ p08 API_FAIL=1; env_ p08 NEWS_FAIL=1
chk p08 U뒤관측 "" 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다. 즉시 보고" 1
ini p09; u0u1 p09; u2u3 p09; env_ p09 ROLLLOG=p0; chk p09 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2; adv p09 300; env_ p09 NEWS_FAIL=1
chk p09 U3 rb 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다" 2
ini p10; u0u1 p10; u2u3 p10; chk p10 UF2 "" 0 "✅ UF2 통과" 1; adv p10 1500; env_ p10 LOGS_FAIL=1
chk p10 U4 "" 1 "🔴 P0 검사 미완료 — 기동 구간 로그를 확보하지 못함" 1
fi

F=0; for d in runs/$P-*; do F=$((F + $(wc -l < $d/forbidden.log))); done
is 전체 "금지 명령 기록 줄 수 합" "$F" 0
echo "== 결과($SEC): PASS $PASS · FAIL $FAILN"
