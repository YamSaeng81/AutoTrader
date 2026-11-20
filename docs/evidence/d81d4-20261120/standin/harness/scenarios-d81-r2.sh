#!/bin/bash
# D81D4 대역(총괄 v1.198 §3-5) — 바뀐 연결만 확인한다. 같은 본체(이미지 대응·P0·관측 함수·jar 비교기)의 기존 사례는 V172D4 대역 r2·u2c r3 를 연결하고 여기서는 영향 경로만 돌린다.
# 사용: scenarios-d81.sh <회차 접두어>   — 사례 폴더는 runs/<접두어>-<사례> 로 회차마다 새로 만든다(있으면 init 이 멈춘다)
# 단언: 종료값·멈춤 문구 + 파일/장부/태그/컨테이너 상태 + 변경 명령(tag·build·up·checkout) 수 + 금지 명령 0
set -u
cd /d/tmp/d81d4s/sim; P=${1:?접두어}; T='2026-11-20 13:30:00'
. ./conf.sh
MV=maint-d81
PYREAL='/c/Users/SangJoon_Lee/AppData/Local/Microsoft/WindowsApps/PythonSoftwareFoundation.Python.3.13_qbz5n2kfra8p0/python.exe'
TAMPER=/d/tmp/d81d4s/jars/tamper-d81-class.jar
PASS=0; FAILN=0
ini() { ./init.sh "$P-$1" "$T" > /dev/null || { echo "init 실패 $1"; exit 9; }; }
D() { echo "runs/$P-$1"; }
chg() { awk -F'\t' '($2=="docker" && ($3 ~ /^tag / || $3 ~ /docker-compose.prod.yml (up|build) /)) || ($2=="git" && $3 ~ /^checkout /)' "$(D $1)/calls.log" | wc -l; }
chgseq() { awk -F'\t' '($2=="docker" && ($3 ~ /^tag / || $3 ~ /docker-compose.prod.yml (up|build) /)) || ($2=="git" && $3 ~ /^checkout /) {print $2" "$3}' "$(D $1)/calls.log" | sed -E 's/ +$//; s/-f docker-compose.prod.yml //' | tr '\n' ';'; }
upn() { awk -F'\t' '$2=="docker" && $3 ~ /docker-compose.prod.yml up /' "$(D $1)/calls.log" | wc -l; }
fechg() { awk -F'\t' '$2=="docker" && ($3 ~ /^tag [^ ]+ autotrader-frontend/ || $3 ~ /docker-compose.prod.yml (build|up)[^\t]*frontend/)' "$(D $1)/calls.log" | wc -l; }
tags() { ( cd "$(D $1)/state" && for f in tag/* fetag/*; do printf '%s=%s;' "$f" "$(cut -c1-19 "$f")"; done ); }
fest() { echo "$(cat "$(D $1)/state/ctr/frontend") $(cat "$(D $1)/state/frontend.started") $(cat "$(D $1)/state/fetag/latest")"; }
st_() { cat "$(D $1)/state/$2" 2>/dev/null || echo 없음; }
mf() { cat "$(D $1)/home/$MV/$2" 2>/dev/null || echo 없음; }
ex() { [ -e "$(D $1)/home/$MV/$2" ] && echo 있음 || echo 없음; }
adv() { ./adv.sh $P-$1 $2 > /dev/null; }
env_() { printf '%s\n' "$2" >> "$(D $1)/env.sh"; }
unenv() { sed -i "/^$2=/d" "$(D $1)/env.sh"; }
chk() { local c=$1 b=$2 t=$3 wrc=$4 want=$5 rc o
  ./sim.sh $P-$c $b $t > /dev/null 2>&1; rc=$?
  o=$(D $c)/out/out-$b${t:+-$t}.txt
  if [ "$rc" = "$wrc" ] && grep -qF -- "$want" "$o"; then PASS=$((PASS+1)); echo "PASS $c $b${t:+($t)} — 종료 $rc · '$want'"
  else FAILN=$((FAILN+1)); echo "FAIL $c $b${t:+($t)} — 종료 $rc(기대 $wrc) · '$want'"; grep -E '🔴|✅|대역 끝' "$o" | tail -3; fi; }
is() { if [ "$3" = "$4" ]; then PASS=$((PASS+1)); echo "PASS $1 — $2"; else FAILN=$((FAILN+1)); echo "FAIL $1 — $2 = [$3] (기대 [$4])"; fi; }
mkimg() { local id; id=$(PYTHONUTF8=1 "$PYREAL" -I -X utf8 imgstate.py build "$(D $1)/state" "$2" "third-$3" | tr -d '\r')
  if [ "$2" = backend ]; then echo "$JAR_OLD" > "$(D $1)/state/img/${id#sha256:}"; else echo frontend > "$(D $1)/state/img/${id#sha256:}"; fi; echo "$id"; }
OTHER=sha256:$(printf other | /usr/bin/sha256sum | cut -c1-64)
oldtags() { tags $1 | tr ';' '\n' | grep -vE '^(tag/latest|tag/0e1d2a0|fetag/latest)=' | grep . | tr '\n' ';'; }
ini base; OLDTAGS=$(oldtags base); [ "$(printf %s "$OLDTAGS" | tr ';' '\n' | grep -c .)" = 10 ] || { echo "옛 태그 기준 10개가 아님: $OLDTAGS"; exit 9; }
u01() { ini $1; chk $1 U0 "" 0 "✅ U0 통과"; adv $1 300; chk $1 U1 "" 0 "✅ U1 통과"; }
u23() { chk $1 U2 "" 0 "✅ U2 통과"; adv $1 300; chk $1 U3 "" 0 "✅ U3(deploy) 통과"; }
V96OK="V95 이후 이력(행수|내용): 1|96:true:-424836241"
gatel() { printf 'GATE|v96=1|created=0|running=0|odd_session=0|open=0|closing=0|odd_position=0|stopped_with_open=0|unresolved_orders=%s|acct_pending=0|applied_missing=0|reported_ne_applied=0|cleanup=0|discrepancy=0|real_closing=0|real_running=0|real_held=0|qty_mismatch=0|balance_mismatch=0%s\nDECIDED|closing_without_decided_at=0\n' "$2" "$3" > "$(D $1)/fix/gate.txt"; }
nochg() { is $1 "변경 명령 늘지 않음·HEAD 그대로·t-rollback 없음·frontend 그대로" "$(chg $1) $(st_ $1 HEAD | cut -c1-8) $(ex $1 t-rollback.utc) $(fest $1 | md5sum | cut -c1-8)" "$2 $3 없음 $4"; }

echo "== n01 정상 backend 전용 순서 U0→U1→(운영자 확인)→U2→U3→U4→U뒤관측"
u01 n01; FE0=$(fest n01 | md5sum | cut -c1-8)
is n01 "U1 끝 문구에 운영자 확인 1회 위치(U2 직전)" "$(grep -c '⏸ 운영자 확인 1회 뒤 U2(backend 만 재기동, frontend 교체 없음)' $(D n01)/out/out-U1.txt)" 1
chk n01 U0 2 1 "U1 을 이미 시작했다"; chk n01 U1 2 1 "U1 을 이미 통과했다"
u23 n01; adv n01 1500; chk n01 U4 "" 0 "✅ U4 통과"; chk n01 U뒤관측 "" 0 "대역 끝] rc=0"
is n01 "변경 명령 순서 = 보존 태그 0e1d2a0 → checkout NEW → build backend → up backend" "$(chgseq n01)" "docker tag $I26 autotrader-backend:0e1d2a0;git checkout --quiet $NEWC;docker compose build backend;docker compose up -d --no-build --no-deps backend;"
is n01 "frontend 변경 명령 0·frontend 실행·기동 시각·이름 그대로" "$(fechg n01) $(fest n01 | md5sum | cut -c1-8)" "0 $FE0"
is n01 "옛 보존 태그 10개 불변·새 보존 태그 0e1d2a0 = 원판" "$(oldtags n01) $(st_ n01 tag/0e1d2a0)" "$OLDTAGS $I26"
is n01 "V96 기적용 확인 기록 5개(U0·U2 전·U2 뒤·U3·U4) 모두 1|96:true:-424836241" "$(mf n01 v96-before.txt)|$(mf n01 v96-pre-deploy.txt)|$(mf n01 v96-deploy.txt)|$(mf n01 v96-u3-deploy.txt)|$(mf n01 v96-after30.txt)" "$V96OK|$V96OK|$V96OK|$V96OK|$V96OK"
is n01 "frontend 단계·새 frontend 기록·UF2 표지 없음" "$(ex n01 fe-stage.txt) $(ex n01 image-fe-new-chain.txt) $(ex n01 ok-UF2) $(ex n01 svc-fe.txt)" "없음 없음 없음 없음"
is n01 "U3 는 5분 창 안 — 늦은 실행 표시 없음" "$(grep -c '⚪ 기동 5~10분 창을 지났다' $(D n01)/out/out-U3.txt)" 0
is n01 "배포 전 읽기 v3 판정 줄" "$(tail -1 $(D n01)/home/$MV/predeploy-verdict.txt)" "판정 | 진행 가능(R1 두 종류 0 · R3 판정 불가 0)"
is n01 "금지 명령 0" "$(grep -c . $(D n01)/forbidden.log)" 0

echo "== l01 U3 늦은 실행(기동 15분 뒤) — 기준 시각 그대로, 편차 표시만"
u01 l01; chk l01 U2 "" 0 "✅ U2 통과"; adv l01 900; chk l01 U3 "" 0 "⚪ 기동 5~10분 창을 지났다"
is l01 "U3 통과 문구" "$(grep -c '✅ U3(deploy) 통과' $(D l01)/out/out-U3.txt)" 1

echo "== f01~f05 frontend 무변경/변경/조회 불명 — 변경 없이 멈춤"
ini f01; chk f01 U0 "" 0 "✅ U0 통과"; adv f01 300; X=$(mkimg f01 frontend f01); echo "$X" > $(D f01)/state/fetag/latest
chk f01 U1 "" 1 "실행 중 frontend 가 U0 대응과 다름 또는 조회 실패(아직 바꾼 것 없음"; is f01 "변경 명령 0·HEAD 그대로" "$(chg f01) $(st_ f01 HEAD | cut -c1-8)" "0 ${OLDC:0:8}"
u01 f02; n=$(chg f02); X=$(mkimg f02 frontend f02); echo "$X" > $(D f02)/state/fetag/latest; FEB=$(fest f02 | md5sum | cut -c1-8)
chk f02 U2 "" 1 "frontend 이미지 이름이 U0 대응과 다름 또는 조회 불명(아직 바꾼 것 없음)"
is f02 "재기동 0·t-deploy 없음" "$(upn f02) $(ex f02 t-deploy.utc)" "0 없음"
chk f02 U1되돌림 "" 1 "frontend 이미지 이름이 U0 대응과 다름 — 이 판은 frontend 를 바꾸지 않았다"; nochg f02 $n ${NEWC:0:8} $FEB
u01 f03; n=$(chg f03); echo sha256:$(printf dead | /usr/bin/sha256sum | cut -c1-64) > $(D f03)/state/fetag/latest; FEB=$(fest f03 | md5sum | cut -c1-8)
chk f03 U1되돌림 "" 1 "frontend 이미지 이름 대응 조회 불명 — 같음으로 추정하지 않는다"; nochg f03 $n ${NEWC:0:8} $FEB
chk f03 U2 "" 1 "frontend 이미지 이름이 U0 대응과 다름 또는 조회 불명(아직 바꾼 것 없음)"; is f03 "재기동 0" "$(upn f03)" 0
u01 f04; u23 f04; adv f04 1500; echo 2026-11-20T04:50:00.000000000Z > $(D f04)/state/frontend.started
chk f04 U4 "" 1 "backend 밖 서비스(frontend·db·redis·db-backup)의 이미지·기동 시각이 시작 기록(svc-before)과 다름"; is f04 "ok-U4 없음" "$(ex f04 ok-U4)" 없음
u01 f05; chk f05 U2 "" 0 "✅ U2 통과"; X=$(mkimg f05 frontend f05); m=$(cut -d' ' -f1 $(D f05)/state/man/${X#sha256:}); echo "$(printf cid-f05-third | /usr/bin/sha256sum | cut -c1-64) $X $m" > $(D f05)/state/ctr/frontend; adv f05 300
chk f05 U3 "" 1 "실행 중 frontend 가 U0 대응이 아님 또는 조회 실패 — 바꾸지 않고 보고"; is f05 "ok-U3-deploy 없음" "$(ex f05 ok-U3-deploy)" 없음

echo "== v01~v06 U0 의 V96 기적용 확인 — 정상 밖은 ok-U0 전에 멈춤"
while IFS=';' read -r c e w; do ini $c; env_ $c "$e"; chk $c U0 "" 1 "$w"; is $c "ok-U0 없음·V96 원문 기록" "$(ex $c ok-U0) $(ex $c v96-before.txt)" "없음 $([ "${e%%=*}" = V_OUT ] && echo 없음 || echo 있음)"; done <<'TT'
v01;V96C_OUT='1|96:false:-424836241';V95 이후 이력이 1|96:true:-424836241 하나가 아님 또는 조회 실패(종료 1)
v02;V96C_OUT='2|95:true:NULL,96:true:-424836241';V95 이후 이력이 1|96:true:-424836241 하나가 아님 또는 조회 실패(종료 1)
v03;V96C_OUT='1|96:true:NULL';V95 이후 이력이 1|96:true:-424836241 하나가 아님 또는 조회 실패(종료 1)
v04;V96C_OUT='2|96:true:-424836241,97:true:5';V95 이후 이력이 1|96:true:-424836241 하나가 아님 또는 조회 실패(종료 1)
v05;V96H_FAIL=1;V95 이후 이력이 1|96:true:-424836241 하나가 아님 또는 조회 실패(종료 2)
v06;V_OUT=95;Flyway 최고 버전이 96 이 아님
TT
echo "== v07~v09 U2 전·뒤·U3 의 V96/마이그레이션"
u01 v07; env_ v07 "V96C_OUT='0|-'"; chk v07 U2 "" 1 "재기동 전 V95 이후 이력이 1|96:true:-424836241 하나가 아님 또는 조회 실패(종료 1) — 보고(아직 바꾼 것 없음)"
is v07 "재기동 0·t-deploy 없음·ok-U2 없음" "$(upn v07) $(ex v07 t-deploy.utc) $(ex v07 ok-U2)" "0 없음 없음"
u01 v08; env_ v08 "V96C_NEW='1|96:false:-424836241'"; chk v08 U2 "" 1 "되돌림 필요: V95 이후 이력이 1|96:true:-424836241 하나가 아님 또는 조회 실패(종료 1)"
is v08 "재기동 1·ok-U2 없음·재기동 전 기록은 정상" "$(upn v08) $(ex v08 ok-U2) $(mf v08 v96-pre-deploy.txt)" "1 없음 $V96OK"
u01 v09; env_ v09 "NEWLOG=migrate"; chk v09 U2 "" 0 "✅ U2 통과"; adv v09 300; chk v09 U3 "" 1 "기동 뒤 마이그레이션이 돌았다(D81D4 는 DDL 없음, deploy)"

echo "== g01~g08 U되돌림 관문 4판 — 거절·조회 실패·기록 실패면 다음 변경 명령 0"
u01 g01; u23 g01; n=$(chg g01); FEB=$(fest g01 | md5sum | cut -c1-8)
chk g01 U되돌림 "" 0 "✅ 0e1d2a02 (운영 2c5d8427 이미지) 로 복귀"
is g01 "되돌림 변경 = checkout OLD·latest 원판·backend up 3개" "$(( $(chg g01) - n ))" 3
is g01 "HEAD·latest·실행 backend = 원판, 새 보존 태그 그대로, 옛 태그 불변" "$(st_ g01 HEAD | cut -c1-8) $(st_ g01 tag/latest) $(cut -d' ' -f2 $(D g01)/state/ctr/backend) $(st_ g01 tag/0e1d2a0) $(oldtags g01)" "${OLDC:0:8} $I26 $I26 $I26 $OLDTAGS"
is g01 "frontend 변경 0·그대로·장부 완결" "$(fechg g01) $(fest g01 | md5sum | cut -c1-8) $(ex g01 rollback-ledger/LEDGER_COMPLETE)" "0 $FEB 있음"
adv g01 300; chk g01 U3 2 0 "✅ U3(rollback) 통과 — 0e1d2a02 복귀 확인"
while IFS=';' read -r c u k inj w; do u01 $c; u23 $c; n=$(chg $c); FEB=$(fest $c | md5sum | cut -c1-8)
  gatel $c "$u" "$k"; [ -z "$inj" ] || { case "$inj" in mkdir) mkdir -p $(D $c)/home/$MV/rollback-ledger ;; v3line) sed -i 's/|exit_reason_unknown_to_old=[0-9]*//' $(D $c)/fix/gate.txt ;; *) env_ $c "$inj" ;; esac; }
  chk $c U되돌림 "" 1 "$w"; nochg $c $n ${NEWC:0:8} $FEB
  is $c "실행 backend 새판 그대로·LEDGER_COMPLETE 없음" "$(cut -d' ' -f2 $(D $c)/state/ctr/backend | cut -c1-12) $(ex $c rollback-ledger/LEDGER_COMPLETE)" "$(cut -c1-12 $(D $c)/home/$MV/image-new.txt) 없음"
done <<'TT'
g02;0;|exit_reason_unknown_to_old=1;;관문 4판 STOP_INCOMPATIBLE(종료 3) — 구판 0e1d2a02 가 읽지 못하는 청산 사유
g03;1;|exit_reason_unknown_to_old=0;;관문이 ROLLBACK_GATE_OK 가 아님(종료 2)
g04;0;|exit_reason_unknown_to_old=0;GATE_RC=2;관문이 ROLLBACK_GATE_OK 가 아님(종료 3)
g05;0;|exit_reason_unknown_to_old=0;COPY_FAIL=1;관문이 ROLLBACK_GATE_OK 가 아님(종료 4)
g06;0;|exit_reason_unknown_to_old=0;mkdir;관문이 ROLLBACK_GATE_OK 가 아님(종료 4)
g07;1;|exit_reason_unknown_to_old=2;;관문 4판 STOP_INCOMPATIBLE(종료 3)
g08;0;;v3line;관문이 ROLLBACK_GATE_OK 가 아님(종료 3)
TT
is g04 "관문 출력 STOP_UNKNOWN(집계 실패)" "$(grep -c '^VERDICT STOP_UNKNOWN (집계 실패' $(D g04)/home/$MV/gate-out.txt)" 1
is g05 "관문 출력 LEDGER_REFUSED·LEDGER_FAILED 남음" "$(grep -c '^VERDICT LEDGER_REFUSED' $(D g05)/home/$MV/gate-out.txt) $(ex g05 rollback-ledger/LEDGER_FAILED)" "1 있음"
is g06 "미리 둔 장부 경로 — 덮어쓰기 거절" "$(grep -c '^VERDICT LEDGER_REFUSED (이미 있음' $(D g06)/home/$MV/gate-out.txt) $(ls $(D g06)/home/$MV/rollback-ledger | wc -l)" "1 0"
is g07 "STOP_INCOMPATIBLE 이 HOLD_PENDING 보다 먼저" "$(grep -c '^VERDICT STOP_INCOMPATIBLE' $(D g07)/home/$MV/gate-out.txt) $(grep -c '^VERDICT HOLD' $(D g07)/home/$MV/gate-out.txt)" "1 0"
is g08 "새 키 없는 3판 형식 줄 → STOP_UNKNOWN" "$(grep -c '^VERDICT STOP_UNKNOWN (집계 값 비정상 exit_reason_unknown_to_old' $(D g08)/home/$MV/gate-out.txt)" 1

echo "== t01~t07 보존 태그 네 판정 + 옛 태그 불변"
ini t01; echo "$I26" > $(D t01)/state/tag/0e1d2a0; chk t01 U0 "" 0 "보존 태그 autotrader-backend:0e1d2a0 있음 — 운영 backend 대응과 같음"; adv t01 300; chk t01 U1 "" 0 "✅ U1 통과"
is t01 "있음이면 다시 쓰지 않는다(tag 명령 0)" "$(awk -F'\t' '$2=="docker" && $3 ~ /^tag /' $(D t01)/calls.log | wc -l)" 0
ini t02; echo "$OTHER" > $(D t02)/state/tag/0e1d2a0; echo x > $(D t02)/state/img/${OTHER#sha256:}; chk t02 U0 "" 1 "보존 태그 autotrader-backend:0e1d2a0 이 이미 다른 이미지를 가리킨다"
ini t03; env_ t03 "TAGQ_FAULT=autotrader-backend:0e1d2a0=rc7"; chk t03 U0 "" 1 "조회 불명(권한·daemon·API·형식) — 없음으로 보지 않는다"
ini t04; rm $(D t04)/state/tag/b5cd1d0; chk t04 U0 "" 0 "⚪ 옛 보존 태그 autotrader-backend:b5cd1d0 없음(API 404) — 이번 복구 대상이 아니므로 만들지 않는다"; adv t04 300; chk t04 U1 "" 0 "✅ U1 통과"
is t04 "U1 뒤에도 옛 태그 b5cd1d0 없음(만들지 않음)" "$(st_ t04 tag/b5cd1d0)" 없음
ini t05; echo "$OTHER" > $(D t05)/state/tag/b5cd1d0; echo x > $(D t05)/state/img/${OTHER#sha256:}; chk t05 U0 "" 1 "옛 보존 태그 autotrader-backend:b5cd1d0 이 #27 이미지가 아닌 대상을 가리킨다"
ini t06; env_ t06 "TAGQ_FAULT=autotrader-frontend:b5cd1d0=http500"; chk t06 U0 "" 1 "옛 보존 태그 autotrader-frontend:b5cd1d0 조회 불명"
ini t07; rm $(D t07)/state/fetag/b5cd1d0; chk t07 U0 "" 0 "⚪ 옛 보존 태그 autotrader-frontend:b5cd1d0 없음(API 404)"
for c in t02 t03 t05 t06; do is $c "멈춘 U0 — ok-U0 없음·변경 0" "$(ex $c ok-U0) $(chg $c)" "없음 0"; done

echo "== r01~r03 U1되돌림(재기동 전)과 재기동 뒤 구분, jar 거절"
u01 r01; n=$(chg r01); chk r01 U1되돌림 "" 0 "↩ U1-되돌림 끝 — HEAD 0e1d2a02·backend latest = 운영 2c5d8427·frontend 무변경(9d7f123a)"
is r01 "HEAD·latest 원판·재기동 0·frontend 변경 0·새 보존 태그 남김·옛 태그 불변" "$(st_ r01 HEAD | cut -c1-8) $(st_ r01 tag/latest) $(upn r01) $(fechg r01) $(st_ r01 tag/0e1d2a0) $(oldtags r01)" "${OLDC:0:8} $I26 0 0 $I26 $OLDTAGS"
u01 r02; chk r02 U2 "" 0 "✅ U2 통과"; chk r02 U1되돌림 "" 1 "U2(재기동)를 시작했다 — U1-되돌림이 아니라 U되돌림"
ini r03; chk r03 U0 "" 0 "✅ U0 통과"; adv r03 300; env_ r03 "BUILD_JAR=$JAR_CRLF_OLD"; chk r03 U1 "" 1 "jar 차이가 기대 38줄(변경 35·추가 3·삭제 0)이 아님"
is r03 "재기동 0" "$(upn r03)" 0; unenv r03 BUILD_JAR; chk r03 U1되돌림 "" 0 "↩ U1-되돌림 끝"; is r03 "HEAD 원판" "$(st_ r03 HEAD | cut -c1-8)" "${OLDC:0:8}"
ini j01; chk j01 U0 "" 0 "✅ U0 통과"; adv j01 300; env_ j01 "BUILD_JAR=$TAMPER"; chk j01 U1 "" 1 "새 jar 항목 내용이 후보 d81c3f2f LF 목록(JARM 33a06f0e…)과 다름"
is j01 "이름 목록·항목 수는 통과(JARM 에서만 거절)·재기동 0" "$(grep -c '^(끝) 895 898$' $(D j01)/home/$MV/jar-diff.txt) $(upn j01)" "1 0"

echo "== p01~p04 P0 우선·빈 로그·로그 실패·두 번째 로그(기존 계약 유지 확인)"
u01 p01; env_ p01 "NEWLOG=p0"; chk p01 U2 "" 0 "✅ U2 통과"; adv p01 300; chk p01 U3 "" 1 "🔴 P0 — 연결 획득 시간 초과 줄이 있다"
is p01 "P0 멈춤이 이미지 대응·뉴스 조회보다 앞" "$(grep -c 'news_source_config' <(awk -F'\t' -v t="$(cat $(D p01)/state/clock)" '$1==t' $(D p01)/calls.log)) $(ex p01 news-deploy.txt)" "0 없음"
u01 p02; chk p02 U2 "" 0 "✅ U2 통과"; adv p02 300; env_ p02 "LOGS_EMPTY=1"; chk p02 U3 "" 1 "🔴 P0 검사 미완료 — 기동 구간 로그가 비었다"
u01 p03; u23 p03; adv p03 1500; chk p03 U4 "" 0 "✅ U4 통과"; touch $(D p03)/state/logs-fail-next; chk p03 U뒤관측 "" 1 "🔴 P0 검사 미완료 — 두 번째 로그를 확보하지 못함"
u01 p04; u23 p04; adv p04 1500; env_ p04 "LOGS_FAIL=1"; chk p04 U4 "" 1 "🔴 P0 검사 미완료 — 기동 구간 로그를 확보하지 못함"; is p04 "ok-U4 없음" "$(ex p04 ok-U4)" 없음

echo "== u01~u06 배포 전 읽기 v3(pf3ok) — 진행 가능 밖은 빌드·체크아웃 전 멈춤"
while IFS=';' read -r c body rc w; do ini $c; printf "$body" > $(D $c)/fix/preflight.txt; chk $c U0 "" $rc "$w"; is $c "변경 0·ok-U0 $([ $rc = 0 ] && echo 있음 || echo 없음)" "$(chg $c) $(ex $c ok-U0)" "0 $([ $rc = 0 ] && echo 있음 || echo 없음)"; done <<'TT'
u01;LIVE | 1\nDYNAMIC | 0\nLIVE | 0\nDYNAMIC | 0\n;1;배포 전 읽기 v3 가 진행 가능이 아님(pf3ok 종료 1
u02;LIVE | 0\nDYNAMIC | 0\nLIVE | -5\nDYNAMIC | 0\n4151 | 1 | 0 | 20200 | 0.001 | 0 |  |  | 0 | 0 | LIVE | 203 | 2026-11-10 01:00:00+00 | -100 | STOP_LOSS | 1\n;1;배포 전 읽기 v3 가 진행 가능이 아님(pf3ok 종료 1
u03;LIVE | 0\nDYNAMIC | 0\nLIVE | -200.5\nDYNAMIC | 0\n4151 | 1 | 0 | 20200 | 0.001 | 1 | 20000 | 0.001 | 0 | 0 | LIVE | 203 | 2026-11-10 01:00:00+00 | -200 | STOP_LOSS | 1\n4152 | 0 | 0 |  |  | 0 |  |  | 0 | 1 | DYNAMIC | 93 |  |  | ENTRY_REJECTED | 1\n;0;R3 RUNNING REAL 세션 청산 행 2 · 판정 불가 0
u04;ERROR:  relation "order" does not exist\n;1;배포 전 읽기 v3 가 진행 가능이 아님(pf3ok 종료 3
u05;LIVE | 0\nDYNAMIC | 0\nLIVE | 0\nDYNAMIC | 0\n4151 | 1 | 0 | 20200 | 0.002 | 1 | 20000 | 0.001 | 0 | 0 | LIVE | 203 | 2026-11-10 01:00:00+00 | -200 | STOP_LOSS | 1\n;1;R3 RUNNING REAL 세션 청산 행 1 · 판정 불가 1 (LIVE:203 1)
u06;LIVE | 0\nDYNAMIC | 0\nLIVE | 0\n;1;배포 전 읽기 v3 가 진행 가능이 아님(pf3ok 종료 3
TT
ini u07; env_ u07 "PF_RC=2"; chk u07 U0 "" 1 "🔴 배포 전 읽기 v3 조회 실패 — 배포 전 중단·보고"

echo "== 전체 금지 명령 기록"
is 전체 "모든 사례 forbidden.log 합계 0" "$(cat runs/$P-*/forbidden.log | grep -c .)" 0
echo "== 대역 결과 PASS $PASS · FAIL $FAILN"
