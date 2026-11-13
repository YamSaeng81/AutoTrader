#!/bin/bash
# v1.159 통합 후보 초안 D4(V159D4) 대역 — SMA20PD4 대역 장치 사본에 frontend·Flyway V96·손실 조회 v2·관문 3판·GS 대역을 더했다.
# 단언 = 블록 종료값 + 통과/멈춤 문구 + 누적 checkout 수, 필요한 곳은 HEAD·이미지·태그·파일. 각 사례는 새 대역 폴더에서 시작한다.
# 사용: scenarios-v159.sh <접두어>
set -u
cd /d/tmp/v159d4/sim; P=${1:?접두어}; T='2026-11-11 10:30:00'
. ./conf.sh
NH=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b
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
env_() { echo "$2" >> runs/$P-$1/env.sh; }
u0u1() { chk $1 U0 "" 0 "✅ U0 통과" 0; adv $1 300; chk $1 U1 "" 0 "✅ U1 통과" 1; }
u2u3() { chk $1 U2 "" 0 "✅ U2 통과" 1; adv $1 300; chk $1 U3 "" 0 "✅ U3(deploy) 통과" 1; }

echo "== v01 정상: U0→U1→U2(V96)→U3→UF2(frontend)→U4→U뒤관측"
ini v01; u0u1 v01
is v01 HEAD "$(st_ v01 HEAD)" "$NEWC"; is v01 "backend 보존 태그 b5cd1d0" "$(st_ v01 tag/b5cd1d0)" "$I26"
is v01 "frontend 보존 태그 b5cd1d0" "$(st_ v01 fetag/b5cd1d0)" "$FEI0"; is v01 "U1 뒤 실행 frontend(구판 그대로)" "$(st_ v01 svc/frontend)" "$FEI0"
is v01 "image-fe-new = frontend latest" "$(cat runs/$P-v01/home/maint-v159/image-fe-new.txt)" "$(st_ v01 fetag/latest)"
u2u3 v01; is v01 "U2 뒤 V96 적용 표지" "$([ -e runs/$P-v01/state/v96 ] && echo 있음 || echo 없음)" 있음
is v01 "U3 뒤 실행 frontend(아직 구판)" "$(st_ v01 svc/frontend)" "$FEI0"
chk v01 UF2 "" 0 "✅ UF2 통과" 1; is v01 "UF2 뒤 실행 frontend" "$(st_ v01 svc/frontend)" "$(cat runs/$P-v01/home/maint-v159/image-fe-new.txt)"
adv v01 1500; chk v01 U4 "" 0 "✅ U4 통과" 1; chk v01 U뒤관측 "" 0 "" 1
is v01 "start_66.py sha256" "$(fsha v01)" "$NH"

echo "== U0 의 새 확인(바꾸기 전 멈춤)"
NO_UV159=1 ini v02; chk v02 U0 "" 1 "FEIMGOLD 가 미정" 0
ini v03; cp $R/fixbase159/preflight-impossible.txt runs/$P-v03/fix/preflight.txt; chk v03 U0 "" 1 "배포 전 손실 관문이 진행 가능이 아님" 0
ini v04; env_ v04 PF_RC=2; chk v04 U0 "" 1 "배포 전 손실 조회 실패" 0
ini v05; env_ v05 "V95EXTRA=V95__paper_session_slippage.sql"; chk v05 U0 "" 1 "V95 이후 마이그레이션이 V96 하나" 0
ini v06; env_ v06 'DIFF_EXTRA=web-api/src/main/resources/db/migration/V95__x.sql'; chk v06 U0 "" 1 "기대 100경로" 0
ini v07; echo "sha256:$(printf x | sha256sum | cut -c1-64)" > runs/$P-v07/state/svc/frontend; chk v07 U0 "" 1 "실행 중 frontend 이미지가 FEIMGOLD 와 다름" 0
ini v08; printf 'GS=미정\n' >> runs/$P-v08/vars.sh; chk v08 U0 "" 1 "GS(관문·조회문 고정 커밋)가 미정" 0
ini v09; echo "판정 | - |  |  |  |  |  | 진행 가능(손실 세 보호식 모두 허용)" >> runs/$P-v09/fix/preflight.txt; chk v09 U0 "" 1 "배포 전 손실 관문이 진행 가능이 아님" 0

echo "== U1 frontend 빌드 실패 → U1-되돌림(재기동 없음, backend·frontend latest 원복)"
ini v10; chk v10 U0 "" 0 "✅ U0 통과" 0; adv v10 300; env_ v10 FE_BUILD_FAIL=1
chk v10 U1 "" 1 "frontend 빌드 실패" 1; chk v10 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2
is v10 HEAD "$(st_ v10 HEAD)" "$OLDC"; is v10 "backend latest" "$(st_ v10 tag/latest)" "$I26"; is v10 "frontend latest" "$(st_ v10 fetag/latest)" "$FEI0"
is v10 "실행 backend" "$(st_ v10 backend.image)" "$I26"; is v10 "실행 frontend" "$(st_ v10 svc/frontend)" "$FEI0"
echo "== U1 성공 뒤 운영자가 U2 를 하지 않기로 함 → U1-되돌림(frontend latest 가 새 이미지에서 구판으로)"
ini v11; u0u1 v11; chk v11 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2; is v11 "frontend latest" "$(st_ v11 fetag/latest)" "$FEI0"; is v11 "backend latest" "$(st_ v11 tag/latest)" "$I26"

echo "== U2 Flyway V96 이력 불일치 → 되돌림 필요로 멈춤"
ini v12; u0u1 v12; env_ v12 V96CK=-1; chk v12 U2 "" 1 "V95 이후 이력이 V96 한 줄" 1

echo "== UF2 순서 경계"
ini v13; u0u1 v13; chk v13 U2 "" 0 "✅ U2 통과" 1; chk v13 UF2 "" 1 "U3(deploy) 미통과" 1; is v13 "실행 frontend" "$(st_ v13 svc/frontend)" "$FEI0"
ini v14; u0u1 v14; u2u3 v14; env_ v14 FE_HTTP=502; chk v14 UF2 "" 1 "frontend 되돌림 필요: / 응답 502" 1

echo "== U되돌림 — 관문 3판 통과 뒤에만 frontend → backend"
ini v15; u0u1 v15; u2u3 v15
chk v15 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2; is v15 HEAD "$(st_ v15 HEAD)" "$OLDC"; is v15 backend "$(st_ v15 backend.image)" "$I26"
is v15 "frontend(교체 전이라 그대로)" "$(st_ v15 svc/frontend)" "$FEI0"; is v15 "관문 장부 완결 표지" "$([ -s runs/$P-v15/home/maint-v159/rollback-ledger/LEDGER_COMPLETE ] && echo 있음 || echo 없음)" 있음
is v15 "V96 표지(되돌려도 남는다)" "$([ -e runs/$P-v15/state/v96 ] && echo 있음 || echo 없음)" 있음
adv v15 300; chk v15 U3 rb 0 "b5cd1d0 복귀 확인" 2
ini v16; u0u1 v16; u2u3 v16; chk v16 UF2 "" 0 "✅ UF2 통과" 1
chk v16 U되돌림 "" 0 "b5cd1d0 (#27 이미지) 로 복귀" 2; is v16 "frontend 원복" "$(st_ v16 svc/frontend)" "$FEI0"; is v16 backend "$(st_ v16 backend.image)" "$I26"
ini v17; u0u1 v17; u2u3 v17; cp $R/fixbase159/gate-hold.txt runs/$P-v17/fix/gate.txt
chk v17 U되돌림 "" 1 "관문이 ROLLBACK_GATE_OK 가 아님(종료 2)" 1; is v17 "HEAD(되돌리지 않음)" "$(st_ v17 HEAD)" "$NEWC"
is v17 "backend(새 바이너리 유지)" "$(st_ v17 backend.image)" "$(cat runs/$P-v17/home/maint-v159/image-new.txt)"
is v17 "되돌림 시각 표지 없음" "$([ -e runs/$P-v17/home/maint-v159/t-rollback.utc ] && echo 있음 || echo 없음)" 없음
ini v18; u0u1 v18; u2u3 v18; printf 'GS=미정\n' >> runs/$P-v18/vars.sh; chk v18 U되돌림 "" 1 "GS(관문 고정 커밋)가 미정" 1
ini v19; u0u1 v19; chk v19 U2 "" 0 "✅ U2 통과" 1; mkdir -p runs/$P-v19/home/maint-v159/rollback-ledger
chk v19 U되돌림 "" 1 "관문이 ROLLBACK_GATE_OK 가 아님(종료 4)" 1; is v19 "HEAD(되돌리지 않음)" "$(st_ v19 HEAD)" "$NEWC"

F=0; for d in runs/$P-v*; do F=$((F + $(wc -l < $d/forbidden.log))); done
is 전체 "금지 명령 기록 줄 수 합" "$F" 0
echo "== 결과: PASS $PASS · FAIL $FAILN"
