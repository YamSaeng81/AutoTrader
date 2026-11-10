#!/bin/bash
# O1·O3+N21 초안 D3 대역(총괄 v1.139 §107-5). 변경된 단계·N21 경계·복구 경로만 다시 검증한다. 각 사례는 새 대역 폴더에서 시작한다.
# 가짜 ssh/docker/git/DB/pgrep 만 쓴다. 단언 = 블록 종료값 + 통과/멈춤 이유(출력 문구) + 실제 checkout 호출 누적 수, 필요한 곳은 HEAD·실행 이미지·latest·표지 파일.
# 사용: scenarios-d3.sh <접두어>
set -u
cd /d/tmp/o13n21d3/sim; P=${1:?접두어}; T='2026-11-10 10:30:00'
. ./conf.sh
D2UV="/d/Claude Code/projects/crypto-auto-trader/docs/evidence/v1135-h2-d2-1ec9104/d2/blocks-d2/U변수.sh"
NHOLD=adca8829b9fb6b4c6996dc853fadade58d685d9c0c044135eb6f92665dba337d; NHNEW=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b
RUNPY='4242 /usr/bin/python3 scripts/prospective/start_66.py freshness --save'
RUNSH='4241 /bin/sh -c cd ~/crypto_trader/AutoTrader && /usr/bin/python3 scripts/prospective/start_66.py freshness --save >> scripts/prospective/freshness_runs.log 2>&1'
PASS=0; FAILN=0
ini() { ./init.sh "$P-$1" "${2:-$T}" > /dev/null; }
co() { grep -c "$(printf '\tgit\tcheckout ')" runs/$P-$1/calls.log; }   # r1 은 grep -P 가 이 로캘에서 동작하지 않아 셈이 비었다(대역 결함, 블록 무관)
# chk <사례> <블록> <꼬리> <기대 종료> <기대 문구> <기대 누적 checkout>
chk() { local c1=$1 b=$2 t=$3 wrc=$4 want=$5 wco=$6 rc c o
  ./sim.sh $P-$c1 $b $t > /dev/null 2>&1; rc=$?
  o=runs/$P-$c1/out/out-$b${t:+-$t}.txt; c=$(co $c1)
  if [ "$rc" = "$wrc" ] && grep -qF -- "$want" "$o" && [ "$c" = "$wco" ]; then PASS=$((PASS+1)); echo "PASS $c1 $b${t:+($t)} — 종료 $rc · '$want' · checkout $c"
  else FAILN=$((FAILN+1)); echo "FAIL $c1 $b${t:+($t)} — 종료 $rc(기대 $wrc) · '$want' · checkout $c(기대 $wco)"; grep -E '🔴|✅|대역 끝' "$o" | tail -3; fi; }
is() { if [ "$3" = "$4" ]; then PASS=$((PASS+1)); echo "PASS $1 — $2 = ${4:0:19}"; else FAILN=$((FAILN+1)); echo "FAIL $1 — $2 = $3 (기대 $4)"; fi; }
st_() { cat runs/$P-$1/state/$2; }
fsha() { /usr/bin/sha256sum < runs/$P-$1/home/crypto_trader/AutoTrader/scripts/prospective/start_66.py | cut -c1-64; }
tfile() { [ -e runs/$P-$1/home/crypto_trader/AutoTrader/scripts/prospective/test_freshness_recheck_fail.py ] && echo 있음 || echo 없음; }
mk() { [ -e runs/$P-$1/home/maint-o13n21/$2 ] && echo 있음 || echo 없음; }
adv() { ./adv.sh $P-$1 $2 > /dev/null; }
cyc() {  # 적용 뒤 :19 회차 하나를 흉내 — 시작 줄(nocron 이면 없음)·관측 행·실행 기록(tb 면 새 Traceback)
  local A=runs/$P-$1/home/crypto_trader/AutoTrader/scripts/prospective
  [ "${2:-}" = nocron ] || echo 'Nov 10 11:19:01 yhdeabba CRON[2297465]: (manicstr) CMD (cd ~/crypto_trader/AutoTrader && /usr/bin/python3 scripts/prospective/start_66.py freshness --save >> scripts/prospective/freshness_runs.log 2>&1)' >> runs/$P-$1/fix/syslog
  echo '{"at": "2026-11-10T02:19:30+00:00", "green": 21, "unknown": 1, "red": 0, "redCoins": [], "unknownCoins": ["KRW-BTC"], "rechecked": ["KRW-BTC"], "rows": {"KRW-BTC": {"dbTo": "x", "exTo": null, "exErr": "net", "count": 500, "rechecked": true}}}' >> $A/freshness_log.jsonl
  if [ "${3:-}" = tb ]; then printf 'Traceback (most recent call last):\nValueError: 대역\n' >> $A/freshness_runs.log; else echo 'freshness 저장' >> $A/freshness_runs.log; fi; }
u0u1() { chk $1 U0 "" 0 "✅ U0 통과" 0; adv $1 300; chk $1 U1 "" 0 "✅ U1 통과" 1; }
u1bad() { echo "BUILD_JAR=$JAR_OLD" > runs/$P-$1/env.sh; chk $1 U0 "" 0 "✅ U0 통과" 0; adv $1 300; chk $1 U1 "" 1 "jar 차이가 O1·O3 3줄이 아님" 1; }

echo "== d01 정상: U0→U1→U2→U3→U4→U5(회차 전 미확인→회차 뒤 통과)→U뒤관측"
ini d01; u0u1 d01
is d01 "U0 프로세스 조회 결과(블록 원문 bash -c 를 회차로 잡지 않음)" "$(cat runs/$P-d01/home/maint-o13n21/pgrep-u0.txt)" ""
is d01 HEAD "$(st_ d01 HEAD)" "$NEWC"; is d01 "start_66.py sha256" "$(fsha d01)" "$NHNEW"; is d01 "새 시험 파일" "$(tfile d01)" 있음
chk d01 U2 "" 0 "✅ U2 통과" 1; adv d01 300; chk d01 U3 "" 0 "✅ U3(deploy) 통과" 1; adv d01 1500; chk d01 U4 "" 0 "✅ U4 통과" 1
chk d01 U5 before 1 "미확인: 적용 뒤 cron 시작 줄이 없다" 1
cyc d01; chk d01 U5 "" 0 "✅ U5" 1
chk d01 U뒤관측 "" 0 "" 1

echo "== U0·U1 경계(바꾸기 전 멈춤)"
ini d02; chk d02 U0 "" 0 "✅ U0 통과" 0; adv d02 300; printf '%s\n%s\n' "$RUNSH" "$RUNPY" >> runs/$P-d02/state/procs.txt
chk d02 U1 "" 1 "freshness 회차가 돌고 있다(아직 바꾼 것 없음, U1-되돌림 하지 않는다)" 0
is d02 HEAD "$(st_ d02 HEAD)" "$OLDC"; is d02 latest "$(st_ d02 tag/latest)" "$I26"; is d02 "보존 태그 1899b49" "$([ -e runs/$P-d02/state/tag/1899b49 ] && echo 있음 || echo 없음)" 없음
ini d03; chk d03 U0 "" 0 "✅ U0 통과" 0; adv d03 300; echo 2 > runs/$P-d03/state/pgrep.rc
chk d03 U1 "" 1 "프로세스 조회 실패(아직 바꾼 것 없음, U1-되돌림 하지 않는다)" 0; is d03 HEAD "$(st_ d03 HEAD)" "$OLDC"
ini d04 '2026-11-10 10:50:00'; chk d04 U0 "" 0 "✅ U0 통과" 0; adv d04 1740
chk d04 U1 "" 1 "매시 :14~:24" 0; is d04 HEAD "$(st_ d04 HEAD)" "$OLDC"
ini d05; echo 2 > runs/$P-d05/state/pgrep.rc; chk d05 U0 "" 1 "프로세스 조회 실패 — 회차 없음으로 보지 않는다" 0; is d05 ok-U0 "$(mk d05 ok-U0)" 없음
ini d06; rm runs/$P-d06/fix/syslog; chk d06 U0 "" 1 "읽을 수 없다 — 배포하지 않고 보고" 0
ini d07; printf '%s\n' "$RUNPY" >> runs/$P-d07/state/procs.txt; chk d07 U0 "" 1 "freshness 회차가 돌고 있다 — 끝난 뒤 U0 부터" 0
ini d08; echo 'DIFF_EXTRA=web-api/src/main/resources/db/migration/V95__x.sql' > runs/$P-d08/env.sh; chk d08 U0 "" 1 "8개 파일이 아님" 0
ini d09; echo '# 대역 변경' >> runs/$P-d09/home/crypto_trader/AutoTrader/scripts/prospective/start_66.py; chk d09 U0 "" 1 "start_66.py 가 1899b49 원문(460031d3…)과 다름" 0
ini d10; UVAR="$D2UV" chk d10 U0 "" 1 "변수 순서" 0; UVAR="$D2UV" chk d10 U1 "" 1 "D3 판이 아님" 0

echo "== U5 구간 판정(U2 전, 이미지 = image-before)"
ini d11; u0u1 d11; cyc d11 nocron; chk d11 U5 "" 1 "미확인: 적용 뒤 cron 시작 줄이 없다" 1
ini d12; u0u1 d12; cyc d12 "" tb; chk d12 U5 "" 1 "이상: 적용 뒤 실행 기록에 새 Traceback" 1
ini d13; u0u1 d13; printf 'Traceback (most recent call last):\nOLD 적용 전\n' >> runs/$P-d13/home/crypto_trader/AutoTrader/scripts/prospective/freshness_runs.log
cyc d13; chk d13 U5 "" 1 "이상: 적용 뒤 실행 기록에 새 Traceback" 1   # U1 뒤에 덧붙인 Traceback 은 적용 뒤 구간이다
ini d14; printf 'Traceback (most recent call last):\nOLD 적용 전\n' >> runs/$P-d14/home/crypto_trader/AutoTrader/scripts/prospective/freshness_runs.log
u0u1 d14; cyc d14; chk d14 U5 "" 0 "✅ U5" 1   # U1 전의 Traceback 은 구간 밖
ini d15; u0u1 d15; cyc d15; echo 3 > runs/$P-d15/state/pgrep.rc; chk d15 U5 "" 1 "프로세스 조회 실패" 1

echo "== U1-되돌림(재기동 전)"
ini d16; u1bad d16; chk d16 U1되돌림 "" 0 "↩ U1-되돌림 끝" 2
is d16 HEAD "$(st_ d16 HEAD)" "$OLDC"; is d16 latest "$(st_ d16 tag/latest)" "$I26"; is d16 backend "$(st_ d16 backend.image)" "$I26"
is d16 "start_66.py sha256" "$(fsha d16)" "$NHOLD"; is d16 "새 시험 파일" "$(tfile d16)" 없음
chk d16 U2 "" 1 "U1 미통과" 2; chk d16 U5 "" 1 "U1 미통과" 2
ini d17; u1bad d17; echo "$MIDC" > runs/$P-d17/state/HEAD; chk d17 U1되돌림 "" 1 "어느 것도 아님" 1; is d17 HEAD "$(st_ d17 HEAD)" "$MIDC"
ini d18; u1bad d18; echo 2 > runs/$P-d18/state/pgrep.rc; chk d18 U1되돌림 "" 1 "프로세스 조회 실패(아직 바꾼 것 없음)" 1; is d18 HEAD "$(st_ d18 HEAD)" "$NEWC"
ini d19; u0u1 d19; chk d19 U2 "" 0 "✅ U2 통과" 1; chk d19 U1되돌림 "" 1 "U2(재기동)를 시작했다" 1

echo "== U되돌림(재기동 뒤)"
ini d20; u0u1 d20; chk d20 U2 "" 0 "✅ U2 통과" 1; I1=$(st_ d20 backend.image)
chk d20 U되돌림 "" 0 "로 복귀" 2; is d20 HEAD "$(st_ d20 HEAD)" "$OLDC"; is d20 backend "$(st_ d20 backend.image)" "$I26"
is d20 "start_66.py sha256" "$(fsha d20)" "$NHOLD"; is d20 "새 시험 파일" "$(tfile d20)" 없음
adv d20 300; chk d20 U3 rollback 0 "✅ U3(rollback) 통과" 2; chk d20 U5 "" 1 "U되돌림을 했다" 2
for v in "d21|HEAD=MID|어느 것도 아님" "d22|회차|freshness 회차가 돌고 있다(아직 바꾼 것 없음)" "d23|조회 실패|프로세스 조회 실패(아직 바꾼 것 없음)" \
         "d24|cron|crontab 이 f2dce280… 과 다름 — 바꾸지 않고 보고" "d25|이미지|어느 것도 아님 — 다른 작업이 있었을 수 있다" "d26|추적 변경|덮어쓰지 않고 보고"; do
  n=${v%%|*}; r=${v#*|}; k=${r%%|*}; want=${r#*|}
  ini $n; u0u1 $n; chk $n U2 "" 0 "✅ U2 통과" 1; I1=$(st_ $n backend.image)
  case "$k" in
    HEAD=MID) echo "$MIDC" > runs/$P-$n/state/HEAD ;;
    회차) printf '%s\n' "$RUNPY" >> runs/$P-$n/state/procs.txt ;;
    "조회 실패") echo 3 > runs/$P-$n/state/pgrep.rc ;;
    cron) printf '#STANDIN-SHA256=%064d\n# 다른 cron\n' 1 > runs/$P-$n/home/.standin-crontab ;;
    이미지) echo sha256:$(printf '%064d' 7) > runs/$P-$n/state/backend.image ;;
    "추적 변경") echo 'DIRTY=scripts/prospective/start_66.py' > runs/$P-$n/env.sh ;;
  esac
  chk $n U되돌림 "" 1 "$want" 1; is $n "t-rollback.utc(만들지 않음)" "$(mk $n t-rollback.utc)" 없음
  [ "$k" = 이미지 ] || is $n "backend 그대로" "$(st_ $n backend.image)" "$I1"
done

echo "== 금지 명령 기록(전 사례)"
is 전체 "forbidden.log 줄 수 합" "$(cat runs/$P-*/forbidden.log | wc -l)" 0
echo "대역 단언 $PASS/$((PASS+FAILN)) 통과"
[ "$FAILN" = 0 ]
