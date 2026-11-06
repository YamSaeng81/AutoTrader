#!/bin/bash
# 멈춤·복구 경로 대역 시나리오(정상 경로 s1b 는 따로 돌렸다). 각 시나리오는 새 대역 폴더에서 시작한다.
cd /d/tmp/n20d1sim; T='2026-11-07 10:20:00'
k() { echo "--- [$1] $2"; }
st() { local S=runs/$1; echo "  상태: HEAD=$(cut -c1-7 $S/state/HEAD) backend=$(cut -c8-19 $S/state/backend.image) latest=$(cut -c8-19 $S/state/tag/latest) img25=$(cut -c8-19 $S/fix/img25) ok=[$(ls $S/home/maint-n20 2>/dev/null | grep '^ok-' | tr '\n' ' ')] 금지=$(wc -l < $S/forbidden.log)"; }
last() { ./sim.sh "$@" | grep -E '🔴|✅|^\[대역 끝\]|^ee509cf|^sha256:' | tail -4; }
# S2 IMGOLD 미정(초안 그대로)
./init.sh s2-imgold-unset "$T" no; k s2 "U0(IMGOLD=미정)"; last s2-imgold-unset U0; k s2 "U1"; last s2-imgold-unset U1; st s2-imgold-unset
ls runs/s2-imgold-unset/home/maint-n20 | sed 's/^/  maint-n20: /'
# S3 jar 차이 → 재기동 전 멈춤 → U2 거부 → U1-되돌림
./init.sh s3-jar-mismatch "$T"; last s3-jar-mismatch U0; ./adv.sh s3-jar-mismatch 300 > /dev/null
echo 'BUILD_JAR=/d/tmp/n19/jars/7a94472.jar   # 대역: 빌드가 다른 jar 를 낸 경우' > runs/s3-jar-mismatch/env.sh
k s3 "U1(빌드 jar=7a94472)"; ./sim.sh s3-jar-mismatch U1 | sed -n '/^변경\|^추가\|^삭제\|^(끝)\|🔴\|✅\|대역 끝/p'; st s3-jar-mismatch
k s3 "U2"; last s3-jar-mismatch U2; k s3 "U1-되돌림"; last s3-jar-mismatch U1되돌림; st s3-jar-mismatch
# S4 시간·단계 표지
./init.sh s4a-window '2026-11-07 09:30:00'; k s4a "U0(KST 09:30)"; last s4a-window U0
./init.sh s4b-noreport "$T"; rm runs/s4b-noreport/home/daily-check/*; k s4b "U0(10:00 보고 파일 없음)"; last s4b-noreport U0
./init.sh s4c-u1late "$T"; last s4c-u1late U0; ./adv.sh s4c-u1late 1801 > /dev/null; k s4c "U1(U0 뒤 30분 1초)"; last s4c-u1late U1; st s4c-u1late
# S5 BTC·세션·설치본·SID 203 불일치(U0)
for v in "s5a-btc|BTC_OUT='false|11-07 08:00|11-07 09:00|20/20'" "s5b-btc20|BTC_OUT='true|11-07 09:00|11-07 09:00|19/20'" "s5c-session|SC_OUT='66 | 62 | 4 | 530:STOPPED'" "s5d-sid203|LIVE1_OUT='203:RUNNING'"; do
  n=${v%%|*}; ./init.sh $n "$T" > /dev/null; echo "${v#*|}" > runs/$n/env.sh; k $n "U0 ($(cat runs/$n/env.sh))"; last $n U0; st $n; done
./init.sh s5e-install "$T" > /dev/null; printf '#STANDIN-SHA256=%064d\n# 다른 정기 점검\n' 0 > runs/s5e-install/home/ops-scripts/daily_check.sh; k s5e "U0(daily_check.sh 해시 다름)"; last s5e-install U0; st s5e-install
./init.sh s5f-cron "$T" > /dev/null; printf '#STANDIN-SHA256=%064d\n# 다른 cron\n' 1 > runs/s5f-cron/home/.standin-crontab; k s5f "U0(crontab 해시 다름)"; last s5f-cron U0; st s5f-cron
# S6 배포 뒤 세션 상태 변화 → U3 '되돌림 필요' → (명시 되돌림) U되돌림 → U3(rollback)
S=s6-rollback; ./init.sh $S "$T" > /dev/null; last $S U0; ./adv.sh $S 300 > /dev/null; last $S U1; last $S U2
echo "SC_OUT='66 | 62 | 4 | 530:STOPPED'   # 대역: 배포 뒤 검증 세션 상태 변화" > runs/$S/env.sh; ./adv.sh $S 345 > /dev/null
k s6 "U3(배포 뒤 세션 변화)"; ./sim.sh $S U3 | grep -E '^[<>]|🔴|✅|대역 끝' | tail -5; st $S
echo "== (대역) 명시 '되돌림 필요' 자리 — 대역 표시만" > runs/$S/out/rollback-gate.txt
k s6 "U되돌림"; last $S U되돌림; st $S
echo "SC_OUT='66 | 63 | 3 | -'   # 대역: 되돌림 뒤 세션 상태가 기준선으로" > runs/$S/env.sh; ./adv.sh $S 330 > /dev/null
k s6 "U3(rollback 자동 전환)"; ./sim.sh $S U3 | grep -E '🔴|✅|대역 끝' | tail -3; k s6 "U4(되돌림 뒤)"; last $S U4; st $S
# S7 P0 — 기동 뒤 연결 획득 시간 초과 줄
S=s7-p0; ./init.sh $S "$T" > /dev/null; last $S U0; ./adv.sh $S 300 > /dev/null; last $S U1; echo 'NEWLOG=p0' > runs/$S/env.sh; last $S U2; ./adv.sh $S 345 > /dev/null
k s7 "U3(P0 줄)"; ./sim.sh $S U3 | grep -E '🔴|✅|대역 끝|ERROR' | tail -4; st $S
# S8 자연 발생 N20 줄이 있을 때(관측) — 미관측 문구가 나오지 않고 멈추지 않는다
S=s8-n20seen; ./init.sh $S "$T" > /dev/null; last $S U0; ./adv.sh $S 300 > /dev/null; last $S U1; echo 'NEWLOG=n20seen' > runs/$S/env.sh; last $S U2; ./adv.sh $S 345 > /dev/null; last $S U3; ./adv.sh $S 1500 > /dev/null
k s8 "U4(N20 줄 자연 발생)"; ./sim.sh $S U4 | grep -E 'N20|🔴|✅|대역 끝'; st $S
echo "=== 금지 명령 기록 합계: $(cat runs/*/forbidden.log | wc -l)줄"
