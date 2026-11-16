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

F=0; for d in runs/$P-*; do F=$((F + $(wc -l < $d/forbidden.log))); done
is 전체 "금지 명령 기록 줄 수 합" "$F" 0
echo "== 결과: PASS $PASS · FAIL $FAILN"
