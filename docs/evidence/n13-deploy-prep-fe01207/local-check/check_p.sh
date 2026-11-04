#!/usr/bin/env bash
# P 변수 블록 로컬 확인 — 서버 명령 아님. HOME 을 임시 폴더로 바꿔 ~/maint-n13 이 이 PC 의 실제 HOME 에 생기지 않게 한다.
set -u
D="$(cd "$(dirname "$0")" && pwd)"
export HOME="$D/fakehome"; rm -rf "$HOME"; mkdir -p "$HOME"
python3() { python -I -X utf8 "$@"; }
# shellcheck disable=SC1091
source "$D/blocks/P변수.sh"
pass=0; fail=0
ok() { if [ "$1" = 0 ]; then echo "PASS $2"; pass=$((pass+1)); else echo "FAIL $2"; fail=$((fail+1)); fi; }
[ "$M" = "$HOME/maint-n13" ] && [ "$PV" = 69 ]; ok $? "판 표지 PV=69·M=~/maint-n13"
[ "$OLD" = 6995c52259e3de4c7c922a32f9fb2aa9ddd49f00 ] && [ "$NEW" = fe01207f3f60e97e308925157f84a1bb2da04388 ]; ok $? "OLD 6995c52·NEW fe01207"
[ "$IMGOLD" = sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07 ]; ok $? "IMGOLD = #21 이미지 67294e80…"
[ "$IMGPREV2" = sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6 ] && [ "$IMGPREV" = sha256:618046540764e49aa40efef7ec950f28cd5882e8bf7d6bdfdc3da0ed1f0112f9 ]; ok $? "이전 복구 태그 두 이미지"
[ "$DIFFN" = "$(git -C "$REPO_N13" diff --name-only "$OLD" "$NEW")" ]; ok $? "DIFFN = git diff --name-only 6995c52 fe01207(4개)"
[ "$(grep -v '^(끝)' "$D/jars/jar-diff-p1.txt")" = "$PARX" ]; ok $? "PARX = 실제 jar 비교 출력(끝 줄 제외)"
grep -qx '(끝) 879 879' "$D/jars/jar-diff-p1.txt"; ok $? "jar 비교 끝 줄 (끝) 879 879"
[ "$LS" = 8bfe8a9fa896f400b3fb6681fef021c99d6ba43b ] && [ "$CK" = d491fd1609b700d195ecc4d0ccea6be2987bf2fe ] && [ "$CRON" = f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b ]; ok $? "장부·점검·cron 기준값 그대로"
type holdobs >/dev/null 2>&1 && type httpobs >/dev/null 2>&1 && type newsobs >/dev/null 2>&1; ok $? "관측 함수 셋 정의"

L="$D/holdobs-cases"; rm -rf "$L"; mkdir -p "$L"
P='backend-1  | '
HOLDST="${P}	at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)
${P}	at jdk.proxy2/jdk.proxy2.\$Proxy212.findPendingHoldFor4hEval(Unknown Source)
${P}	at com.cryptoautotrader.api.service.SignalQualityService.evaluateHoldBaselineLoop(SignalQualityService.java:227)
${P}	at com.cryptoautotrader.api.service.SignalQualityService.evaluateSignalQuality(SignalQualityService.java:70)"
printf '%s\n' "${P}07:40:00.000 [main] INFO  c.c.a.CryptoAutoTraderApplication - Started CryptoAutoTraderApplication" \
  "${P}07:40:01.000 [task-1] INFO  c.c.a.service.SignalQualityService - [SignalQuality] 시작 Catchup 시작" \
  "${P}07:40:05.000 [task-1] INFO  c.c.a.service.SignalQualityService - [SignalQuality] 시작 Catchup 완료 — 4h: 0건, 24h: 0건 (HOLD 기준선 3/2건)" > "$L/none.log"
{ cat "$L/none.log"
  echo "${P}07:46:00.100 [HikariPool-1 housekeeper] WARN  com.zaxxer.hikari.pool.ProxyLeakTask - Connection leak detection triggered for org.postgresql.jdbc.PgConnection@1a2b on thread scheduler-3, stack trace follows"
  echo "${P}java.lang.Exception: Apparent connection leak detected"; echo "$HOLDST"
  echo "${P}07:47:10.200 [scheduler-3] INFO  com.zaxxer.hikari.pool.ProxyLeakTask - Previously reported leaked connection org.postgresql.jdbc.PgConnection@1a2b on thread scheduler-3 was returned to the pool (unleaked)"; } > "$L/hold.log"
{ cat "$L/none.log"
  echo "${P}07:50:00.000 [HikariPool-1 housekeeper] WARN  com.zaxxer.hikari.pool.ProxyLeakTask - Connection leak detection triggered for org.postgresql.jdbc.PgConnection@9f on thread task-2, stack trace follows"
  echo "${P}java.lang.Exception: Apparent connection leak detected"
  echo "${P}	at com.cryptoautotrader.api.service.MarketDataSyncService.sync(MarketDataSyncService.java:88)"; } > "$L/other.log"
{ cat "$L/hold.log"
  echo "${P}07:55:00.000 [scheduler-1] ERROR c.c.a.service.LiveTradingService - 처리 실패: HikariPool-1 - Connection is not available, request timed out after 30000ms."; } > "$L/timeout.log"
{ cat "$L/none.log"
  echo "${P}07:46:00.100 [HikariPool-1 housekeeper] WARN  com.zaxxer.hikari.pool.ProxyLeakTask - Connection leak detection triggered for org.postgresql.jdbc.PgConnection@77 on thread scheduler-1, stack trace follows"
  echo "${P}java.lang.Exception: Apparent connection leak detected"; echo "$HOLDST"; } > "$L/hold-noreturn.log"

run() { holdobs "$@" > "$L/$1.out" 2>&1; echo $?; }
for c in none hold other timeout hold-noreturn; do
  holdobs "$L/$c.log" > "$L/$c.out" 2>&1; rc=$?
  case $c in
    none) [ $rc = 0 ] && grep -q '^HOLD 기준선 질의 장기 대여 경고 0건(반환 0·반환 미관측 0) · 그 밖의 장기 대여 경고 0건 · 연결 획득 시간 초과 줄 0건$' "$L/$c.out" && grep -q '시작 Catchup: 시작 07:40:01.000 · 완료 07:40:05.000' "$L/$c.out"; ok $? "holdobs 경고 없음 — 종료 0·catchup 시각" ;;
    hold) [ $rc = 3 ] && grep -q '경고 1건(반환 1·반환 미관측 0) · 그 밖의 장기 대여 경고 0건' "$L/$c.out" && grep -q '· HOLD 07:46:00.100 scheduler-N · 반환 07:47:10.200' "$L/$c.out"; ok $? "holdobs HOLD 경고+반환 — 종료 3·스레드 종류만" ;;
    other) [ $rc = 3 ] && grep -q 'HOLD 기준선 질의 장기 대여 경고 0건.* 그 밖의 장기 대여 경고 1건' "$L/$c.out" && grep -q 'api.service.MarketDataSyncService.sync · 반환 미관측' "$L/$c.out"; ok $? "holdobs 그 밖의 경고 — 종료 3" ;;
    timeout) [ $rc = 1 ] && grep -q '연결 획득 시간 초과 줄 1건' "$L/$c.out" && grep -q '🔴 연결 획득 시간 초과 07:55:00.000 scheduler-N' "$L/$c.out"; ok $? "holdobs 획득 시간 초과 — 종료 1(P0)" ;;
    hold-noreturn) [ $rc = 3 ] && grep -q '경고 1건(반환 0·반환 미관측 1)' "$L/$c.out"; ok $? "holdobs 반환 미관측 — 종료 3" ;;
  esac
done
holdobs > "$L/noarg.out" 2>&1; rc=$?; [ $rc = 3 ] && grep -q '인자는 1개' "$L/noarg.out"; ok $? "holdobs 인자 없음 — ⚪ 종료 3"
! grep -h 'PgConnection@\|1a2b\|scheduler-3\|task-2' "$L"/*.out > /dev/null; ok $? "출력에 연결 식별자·정확한 스레드 이름 없음"
echo "== $pass 통과 / $fail 실패"
[ $fail = 0 ]
