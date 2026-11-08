#!/bin/bash
# O1·O3 초안 D2 전체 단계 대역(총괄 v1.135 §5-3). 각 시나리오는 새 대역 폴더에서 시작한다. 가짜 ssh/docker/git/DB 만 쓴다.
# 사용: scenarios.sh <접두어> — 대역 이름 앞에 붙인다(같은 이름 재사용 금지, init.sh 가 거부).
set -u
cd /d/tmp/o13d2/sim; P=${1:?접두어}; T='2026-11-10 10:20:00'
D1UV="/d/Claude Code/projects/crypto-auto-trader/docs/evidence/o1o3-candidate-1899b49/w6/blocks-d1/U변수.sh"
. ./conf.sh
k() { echo "--- [$1] $2"; }
st() { local S=runs/$1; echo "  상태: HEAD=$(cut -c1-7 $S/state/HEAD) backend=$(cut -c8-19 $S/state/backend.image) latest=$(cut -c8-19 $S/state/tag/latest) ok=[$(ls $S/home/maint-o13 2>/dev/null | grep '^ok-' | tr '\n' ' ')] 호출=$(wc -l < $S/calls.log) 금지=$(wc -l < $S/forbidden.log)"; }
last() { ./sim.sh "$@" | grep -E '🔴|✅|⚪ 장기|^\[대역 끝\]|^[0-9a-f]{40}$|^sha256:' | tail -4; }
ini() { ./init.sh "$P-$1" "${2:-$T}" > /dev/null; }
deploy_to_u2() { last $1 U0; ./adv.sh $1 300 > /dev/null; last $1 U1; last $1 U2; }

# 1. 정상 진행: U0→U1→U2→U3 조기→U3→U4 조기→U4→U뒤관측
S=$P-s01-normal; ini s01-normal; k s01 "U0"; last $S U0; ./adv.sh $S 300 > /dev/null
k s01 "U1(실제 jar, 비교기 v2)"; ./sim.sh $S U1 | sed -n '/^변경\|^추가\|^삭제\|^(끝)\|🔴\|✅\|대역 끝/p'
k s01 "U2"; last $S U2; ./adv.sh $S 60 > /dev/null; k s01 "U3 조기(기동 1분 뒤)"; last $S U3 early; ./adv.sh $S 285 > /dev/null
k s01 "U3"; last $S U3; ./adv.sh $S 360 > /dev/null; k s01 "U4 조기"; last $S U4 early; ./adv.sh $S 1140 > /dev/null
k s01 "U4"; last $S U4; k s01 "U뒤관측"; ./sim.sh $S U뒤관측 | grep -E '🔴|⚪|대역 끝'; st $S

# 2. 판 표지: D1 U변수(UB=O13D1) + D2 블록
S=$P-s02-marker-d1; ini s02-marker-d1; k s02 "U0(D1 U변수)"; UVAR="$D1UV" last $S U0; k s02 "U1(D1 U변수)"; UVAR="$D1UV" last $S U1; st $S
# 3. IMGOLD 형식 아님
S=$P-s03-imgold; ini s03-imgold; echo 'IMGOLD=미정   # 대역: 형식 아님' > runs/$S/vars.sh; k s03 "U0(IMGOLD=미정)"; last $S U0; st $S

# 4. 시각·단계 표지
ini s04a-window '2026-11-10 09:30:00'; k s04a "U0(KST 09:30)"; last $P-s04a-window U0; st $P-s04a-window
ini s04b-noreport; rm runs/$P-s04b-noreport/home/daily-check/*; k s04b "U0(10:00 보고 파일 없음)"; last $P-s04b-noreport U0
S=$P-s04c-u1late; ini s04c-u1late; last $S U0; ./adv.sh $S 1801 > /dev/null; k s04c "U1(U0 뒤 30분 1초 — checkout 전 멈춤)"; last $S U1; st $S

# 5. U0 불일치: BTC·세션·SID 203·설치본·cron·diff·작업 사본
for v in "s05a-btc|BTC_OUT='false|11-10 08:00|11-10 09:00|20/20'" "s05b-btc20|BTC_OUT='true|11-10 09:00|11-10 09:00|19/20'" \
         "s05c-session|SC_OUT='66 | 62 | 4 | 530:STOPPED'" "s05d-sid203|LIVE1_OUT='203:RUNNING'" \
         "s05g-diff|DIFF_EXTRA=web-api/src/main/resources/db/migration/V95__x.sql" "s05h-dirty|DIRTY=web-api/src/main/resources/application.yml"; do
  n=${v%%|*}; ini $n; echo "${v#*|}" > runs/$P-$n/env.sh; k $n "U0 ($(cat runs/$P-$n/env.sh))"; last $P-$n U0; st $P-$n; done
ini s05e-install; printf '#STANDIN-SHA256=%064d\n# 다른 정기 점검\n' 0 > runs/$P-s05e-install/home/ops-scripts/daily_check.sh; k s05e "U0(daily_check.sh 해시 다름)"; last $P-s05e-install U0; st $P-s05e-install
ini s05f-cron; printf '#STANDIN-SHA256=%064d\n# 다른 cron\n' 1 > runs/$P-s05f-cron/home/.standin-crontab; k s05f "U0(crontab 해시 다름)"; last $P-s05f-cron U0; st $P-s05f-cron

# 6. 잘못된 jar(checkout 뒤 멈춤 → U1-되돌림): 빌드가 1899b49 jar 를 낸 경우
S=$P-s06-jar-mismatch; ini s06-jar-mismatch; last $S U0; ./adv.sh $S 300 > /dev/null
echo "BUILD_JAR=$JAR_OLD   # 대역: 빌드가 기점 jar 를 낸 경우" > runs/$S/env.sh
k s06 "U1(빌드 jar=1899b49)"; ./sim.sh $S U1 | sed -n '/^변경\|^추가\|^삭제\|^(끝)\|🔴\|✅\|대역 끝/p'; st $S
k s06 "U2"; last $S U2; k s06 "U1-되돌림"; last $S U1되돌림; st $S

# 7. jar 비교기 v2 중복 거절(checkout 뒤 멈춤 → U1-되돌림). 합성 jar 는 실제 jar 에 중복 한 항목만 더한 것
for v in "s07a-new-inner-diff|BUILD_JAR=$R/jars/new-inner-diff-dup.jar" "s07b-new-inner-same|BUILD_JAR=$R/jars/new-inner-same-dup.jar" \
         "s07c-new-outer-same|BUILD_JAR=$R/jars/new-outer-same-dup.jar" "s07d-old-inner|OLDJAR=$R/jars/old-inner-diff-dup.jar" \
         "s07e-both-inner|OLDJAR=$R/jars/old-inner-diff-dup.jar BUILD_JAR=$R/jars/new-inner-diff-dup.jar"; do
  n=${v%%|*}; S=$P-$n; ini $n; e=${v#*|}
  case "$e" in *OLDJAR=*) o=${e#*OLDJAR=}; o=${o%% *}; echo "$o" > runs/$S/state/img/${I26#sha256:} ;; esac
  case "$e" in *BUILD_JAR=*) echo "BUILD_JAR=${e#*BUILD_JAR=}" > runs/$S/env.sh ;; esac
  last $S U0; ./adv.sh $S 300 > /dev/null; k $n "U1($e)"; ./sim.sh $S U1 | sed -n '/^변경\|^추가\|^삭제\|^(끝)\|^(거절)\|🔴\|✅\|대역 끝/p'; st $S
  k $n "U2"; last $S U2; k $n "U1-되돌림"; last $S U1되돌림; st $S; done

# 8. 출력 덮어쓰기·단계 재실행 거부
S=$P-s08-rerun; ini s08-rerun; last $S U0; k s08 "같은 U0 출력 이름으로 다시"; ./sim.sh $S U0; echo "  sim 종료 $?"
./adv.sh $S 300 > /dev/null; last $S U1; k s08 "U1 통과 뒤 U0 다시(꼬리 again)"; last $S U0 again; k s08 "U1 다시(꼬리 again)"; last $S U1 again; st $S

# 9. 기동 뒤 로그: P0(ERROR/WARN/Caused by)·장기 대여·다른 ERROR — U3 와 U4 각각
for L in normal errto warnto causedto lease othererr; do
  S=$P-s09-u3-$L; ini s09-u3-$L; echo "NEWLOG=$L" > runs/$S/env.sh; deploy_to_u2 $S > /dev/null; ./adv.sh $S 345 > /dev/null
  k s09-u3-$L "U3(기동 로그 $L)"; ./sim.sh $S U3 | grep -E '🔴|✅|⚪ 장기|대역 끝' | tail -3; st $S; done
for L in normal errto warnto causedto lease othererr; do
  S=$P-s09-u4-$L; ini s09-u4-$L; deploy_to_u2 $S > /dev/null; ./adv.sh $S 345 > /dev/null; last $S U3 > /dev/null
  cp runs/$S/fix/log-$L.log runs/$S/state/backend.log; ./adv.sh $S 1500 > /dev/null
  k s09-u4-$L "U4(30분 로그 $L)"; ./sim.sh $S U4 | grep -E '🔴|✅|⚪ 장기|대역 끝' | tail -3; st $S; done

# 10. 명시 복구: 배포 뒤 세션 변화 → U3 '되돌림 필요' → (명시 되돌림 자리) U되돌림 → U3(rollback) — 복귀 로그 정상 / P0
for RL in normal errto; do
  S=$P-s10-rollback-$RL; ini s10-rollback-$RL; deploy_to_u2 $S > /dev/null
  echo "SC_OUT='66 | 62 | 4 | 530:STOPPED'   # 대역: 배포 뒤 검증 세션 상태 변화" > runs/$S/env.sh; ./adv.sh $S 345 > /dev/null
  k s10-$RL "U3(배포 뒤 세션 변화)"; ./sim.sh $S U3 | grep -E '🔴|✅|대역 끝' | tail -3; st $S
  echo "== (대역) 명시 '되돌림 필요' 자리 — 대역 표시만" > runs/$S/out/rollback-gate.txt
  printf "SC_OUT='66 | 63 | 3 | -'   # 대역: 되돌림 뒤 세션 상태가 기준선으로\nROLLLOG=%s\n" $RL > runs/$S/env.sh
  k s10-$RL "U되돌림"; last $S U되돌림; st $S; ./adv.sh $S 345 > /dev/null
  k s10-$RL "U3(rollback, 복귀 로그 $RL)"; ./sim.sh $S U3 rollback | grep -E '🔴|✅|대역 끝' | tail -3
  k s10-$RL "U4(되돌림 뒤 — 하지 않는 단계)"; last $S U4 rollback; st $S; done
echo "=== 금지 명령 기록 합계(접두어 $P): $(cat runs/$P-*/forbidden.log | wc -l)줄 · 대역 $(ls -d runs/$P-* | wc -l)개"
