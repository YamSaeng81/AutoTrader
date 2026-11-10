#!/bin/bash
# 사용: init.sh <대역 이름> <가짜 시작 시각(KST, 예 '2026-11-10 10:20:00')>
# 새 대역 폴더를 만든다. 이미 있으면 멈춘다(실행 출력 보존). N20 D1 대역(n20-d1-standin-1899b49 init.sh)을 O1·O3 D2 값으로 옮겼고, D3 에서 N21 감시 스크립트 대역을 더했다.
set -eu
. /d/tmp/o13n21d3/sim/conf.sh
S=$R/runs/$1; [ ! -e "$S" ] || { echo "이미 있음 $S"; exit 2; }
mkdir -p "$S"/{state/tag,state/img,state/svc,fix,out,home/crypto_trader/AutoTrader/scripts/prospective,home/maint-n20,home/ops-scripts,home/sid203-ledger,home/daily-check}
TZ=KST-9 /usr/bin/date -d "$2" +%s > "$S/state/clock"
echo $OLDC > "$S/state/HEAD"
h() { printf '%s' "$1" | /usr/bin/sha256sum | cut -c1-64; }
echo "$I26" > "$S/fix/img26"
echo "$JAR_OLD" > "$S/state/img/${I26#sha256:}"
echo "$JAR_OLD" > "$S/fix/jar-$OLDC"; echo "$JAR_NEW" > "$S/fix/jar-$NEWC"
# 이전 복구 태그 — D2 U변수의 IMGPREV* 값 그대로(이 이미지들의 jar 는 대역에서 꺼내지 않는다)
for p in ea44900:618046540764e49aa40efef7ec950f28cd5882e8bf7d6bdfdc3da0ed1f0112f9 b1d7664:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6 \
         6995c52:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07 fe01207:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7 \
         1103f48:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62 7a94472:e51b512a72cc3740313bb2a9e6f369d4f480554c588f322cff518c827f9c7be6 \
         ee509cf:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6; do
  echo "sha256:${p#*:}" > "$S/state/tag/${p%%:*}"; echo "$JAR_OLD" > "$S/state/img/${p#*:}"; done
echo "$I26" > "$S/state/tag/latest"; echo "$I26" > "$S/state/backend.image"; echo 2026-11-06T04:36:36.000000000Z > "$S/state/backend.started"
for s in db redis db-backup frontend; do echo "sha256:$(h standin-$s)" > "$S/state/svc/$s"; done
echo "$I26" > "$S/home/maint-n20/image-new.txt"
cp $R/fixbase/maint_checks.sql $R/fixbase/maint_verify.py $R/fixbase/log-*.log "$S/fix/"
cp $R/fixbase/log-before.log "$S/state/backend.log"
printf '== Q3 (대역 고정 출력)\n q3 | 0\n' > "$S/fix/qc.txt"
printf '🔴 🔴 은 경보이고 확정이 아니다 — (대역 고정 출력, 경보 조회를 실제로 하지 않음)\n' > "$S/fix/alerts.txt"
O=$S/home/ops-scripts
printf '#STANDIN-SHA256=02a8585021fb2a1a52d9b3b56b60e957cb71b9ca3c11bf819595df15cf334f1c\n-- 대역 설치 장부\n' > $O/sid_ledger.sql
echo 8bfe8a9fa896f400b3fb6681fef021c99d6ba43b > $O/SID_LEDGER_SHA; echo c669ccdc6e248ce8e55e9a7ac7405197d266fa18 > $O/INSTALLED_SHA
printf '#STANDIN-SHA256=ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc\n# 대역 정기 점검\n' > $O/daily_check.sh
printf '#STANDIN-SHA256=653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8\n-- 대역 정기 점검 SQL\n' > $O/daily_checks.sql
printf '#STANDIN-SHA256=f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b\n# 대역 crontab\n' > "$S/home/.standin-crontab"
printf 'ledger a\n' > "$S/home/sid203-ledger/20261110.txt"; printf 'ledger b\n' > "$S/home/sid203-ledger/20261109.txt"
printf '대역 정기 보고\n' > "$S/home/daily-check/$(TZ=KST-9 /usr/bin/date -d "$2" +%Y%m%d)-1000.txt"
: > "$S/calls.log"; : > "$S/forbidden.log"; : > "$S/env.sh"
# D3: N21 감시 스크립트 대역 — start_66.py 원문(1899b49·통합 후보, 로컬 저장소에서 읽기만)·시험 파일·관측 행·실행 기록·가짜 시스템 기록·프로세스 목록
P=$S/home/crypto_trader/AutoTrader/scripts/prospective
for c in $OLDC $NEWC $MIDC; do git -C "$REPOSRC" show $c:scripts/prospective/start_66.py > "$S/fix/start_66.$c.py"; done
git -C "$REPOSRC" show $NEWC:scripts/prospective/test_freshness_recheck_fail.py > "$S/fix/testfile.py"
cp "$S/fix/start_66.$OLDC.py" "$P/start_66.py"
echo '{"at": "2026-11-10T00:19:30+00:00", "green": 22, "unknown": 0, "red": 0, "redCoins": [], "unknownCoins": [], "rechecked": [], "rows": {"KRW-BTC": {"dbTo": "x", "exTo": "x", "exErr": null, "count": 500, "rechecked": false}}}' > "$P/freshness_log.jsonl"
printf 'freshness 저장(이전 회차)\n' > "$P/freshness_runs.log"
printf 'Nov 10 09:19:01 yhdeabba CRON[1]: (manicstr) CMD (이전 회차 — 기준 위치 앞)\n' > "$S/fix/syslog"
echo "SYSLOG=$S/fix/syslog   # 대역 치환: 가짜 시스템 기록(실제 /var/log/syslog 는 서버에만 있다)" > "$S/vars.sh"
{ printf '9001 bash -c '; cat $FG/F.sh $FG/G.sh $BLOCKS/*.sh | tr '\n' ' '; echo
  echo '9002 /usr/bin/python3 scripts/prospective/start_66.py alerts --days 1 --no-mark'; } > "$S/state/procs.txt"
echo "대역 $S · 가짜 시각 $(TZ=KST-9 /usr/bin/date -d @"$(cat "$S/state/clock")" '+%F %T %Z')"
