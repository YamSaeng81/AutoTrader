#!/bin/bash
# D2 영향 범위 대역 — U3·U4 의 P0 검사 위치 이동만 본다(총괄 v1.128 §96). 다른 D1 멈춤 경로는 블록 차이로 재사용.
cd /d/tmp/n20d1sim; T='2026-11-07 10:20:00'
k() { echo "--- [$1] $2"; }
st() { local S=runs/$1; echo "  상태: HEAD=$(cut -c1-7 $S/state/HEAD) backend=$(cut -c8-19 $S/state/backend.image) latest=$(cut -c8-19 $S/state/tag/latest) img25=$(cut -c8-19 $S/fix/img25) ok=[$(ls $S/home/maint-n20 2>/dev/null | grep '^ok-' | tr '\n' ' ')] 금지=$(wc -l < $S/forbidden.log)"; }
show() { grep -E '🔴|✅|⚪ 장기|^HOLD |^\[대역 끝\]' | tail -6; }
prep() { ./init.sh $1 "$T" > /dev/null; cp d2/fix/*.log runs/$1/fix/; ./sim2.sh $1 U0 | show; ./adv.sh $1 300 > /dev/null; ./sim2.sh $1 U1 | show; }
# U3 여섯 입력
for v in normal errto warnto causedto lease othererr; do
  S=d2-u3-$v; prep $S; echo "NEWLOG=$v" > runs/$S/env.sh; ./sim2.sh $S U2 | show; ./adv.sh $S 345 > /dev/null
  k $S "U3(기동 뒤 로그=$v)"; ./sim2.sh $S U3 | show; st $S
done
# U4 여섯 입력 — U3 는 정상 로그로 통과, 그 뒤 30분 사이 로그에 입력 줄이 생긴 경우
for v in normal errto warnto causedto lease othererr; do
  S=d2-u4-$v; prep $S; echo "NEWLOG=normal" > runs/$S/env.sh; ./sim2.sh $S U2 | show; ./adv.sh $S 345 > /dev/null; ./sim2.sh $S U3 | show
  cp runs/$S/fix/log-$v.log runs/$S/state/backend.log; ./adv.sh $S 1500 > /dev/null
  k $S "U4(30분 로그=$v)"; ./sim2.sh $S U4 | show; st $S
done
# 명시 복구 뒤 U3(rollback) 의 P0 — 배포 뒤 세션 변화 → 되돌림 필요 → U되돌림(복귀 로그에 ERROR 수준 시간 초과) → U3(rollback)
S=d2-rollback-p0; prep $S; echo "NEWLOG=normal" > runs/$S/env.sh; ./sim2.sh $S U2 | show
printf "SC_OUT='66 | 62 | 4 | 530:STOPPED'\nROLLLOG=errto\n" > runs/$S/env.sh; ./adv.sh $S 345 > /dev/null
k $S "U3(배포 뒤 세션 변화)"; ./sim2.sh $S U3 | show; st $S
echo "== (대역) 명시 '되돌림 필요' 자리 — 대역 표시만" > runs/$S/out/rollback-gate.txt
k $S "U되돌림"; ./sim2.sh $S U되돌림 | show; st $S
printf "SC_OUT='66 | 63 | 3 | -'\nROLLLOG=errto\n" > runs/$S/env.sh; ./adv.sh $S 330 > /dev/null
k $S "U3(rollback, 복귀 로그 ERROR 수준 시간 초과)"; ./sim2.sh $S U3 | show; st $S
echo "=== 금지 명령 기록 합계(d2-*): $(cat runs/d2-*/forbidden.log | wc -l)줄"
