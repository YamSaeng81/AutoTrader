#!/bin/bash
# 사용: init.sh <대역 이름> <가짜 시작 시각(KST)>
# D81D4 대역 — 현재 운영 상태에서 시작한다: HEAD 0e1d2a02 · backend 2c5d8427(대역 합성 I26) · frontend 9d7f123a(대역 합성 FET0) · V96 적용됨 ·
# 옛 보존 태그 9개(ea44900…1899b49·b5cd1d0)와 frontend b5cd1d0 태그 있음 · 새 보존 태그 0e1d2a0 없음 · ~/maint-v172/image-new.txt = 운영 backend.
# 이미 있으면 멈춘다(실행 출력 보존).
set -eu
. /d/tmp/d81d4s/sim/conf.sh
S=$R/runs/$1; [ ! -e "$S" ] || { echo "이미 있음 $S"; exit 2; }
mkdir -p "$S"/{state/fetag,state/tag,state/img,state/svc,state/ctr,state/man,state/blob,state/fault,fix,out,home/crypto_trader/AutoTrader/scripts/prospective,home/maint-v172,home/ops-scripts,home/sid203-ledger,home/daily-check}
TZ=KST-9 /usr/bin/date -d "$2" +%s > "$S/state/clock"
echo $OLDC > "$S/state/HEAD"
h() { printf '%s' "$1" | /usr/bin/sha256sum | cut -c1-64; }
PYREAL='/c/Users/SangJoon_Lee/AppData/Local/Microsoft/WindowsApps/PythonSoftwareFoundation.Python.3.13_qbz5n2kfra8p0/python.exe'
# 원판 이미지 두 개(합성, 결정적 — conf.sh 의 값과 같아야 한다)
b=$(PYTHONUTF8=1 "$PYREAL" -I $R/imgstate.py build "$S/state" backend op-0e1d2a02-2c5d8427 | tr -d '\r')
f=$(PYTHONUTF8=1 "$PYREAL" -I $R/imgstate.py build "$S/state" frontend op-fe-9d7f123a | tr -d '\r')
[ "$b" = "$I26" ] && [ "$f" = "$FET0" ] && [ "$(cat "$S/state/man/${I26#sha256:}")" = "$I26M $I26C" ] && [ "$(cat "$S/state/man/${FET0#sha256:}")" = "$FEM0 $FEC0" ] || { echo "합성 원판 값이 conf.sh 와 다름"; exit 3; }
echo "$I26" > "$S/fix/img26"
echo "$JAR_OLD" > "$S/state/img/${I26#sha256:}"; echo frontend > "$S/state/img/${FET0#sha256:}"
echo "$JAR_OLD" > "$S/fix/jar-$OLDC"; echo "$JAR_NEW" > "$S/fix/jar-$NEWC"
# 옛 보존 태그 — U변수 IMGPREV* 값 그대로(jar 는 대역에서 꺼내지 않는다), b5cd1d0 두 개 포함
for p in ea44900:618046540764e49aa40efef7ec950f28cd5882e8bf7d6bdfdc3da0ed1f0112f9 b1d7664:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6 \
         6995c52:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07 fe01207:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7 \
         1103f48:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62 7a94472:e51b512a72cc3740313bb2a9e6f369d4f480554c588f322cff518c827f9c7be6 \
         ee509cf:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6 1899b49:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1 \
         b5cd1d0:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055; do
  echo "sha256:${p#*:}" > "$S/state/tag/${p%%:*}"; echo "$JAR_CRLF_OLD" > "$S/state/img/${p#*:}"; done
echo "$FP9" > "$S/state/fetag/b5cd1d0"; echo frontend > "$S/state/img/${FP9#sha256:}"
echo "$I26" > "$S/state/tag/latest"; echo "$FET0" > "$S/state/fetag/latest"
# 실행 컨테이너 — 11-16 V172D4 가 만든 backend(14:16:14 KST)·frontend(16:15:16 KST), .Image = 대상 ID
echo "$I26" > "$S/state/backend.image"; echo 2026-11-16T05:16:14.000000000Z > "$S/state/backend.started"
echo 2026-11-16T07:15:16.000000000Z > "$S/state/frontend.started"
for s in db redis db-backup; do echo "sha256:$(h standin-$s)" > "$S/state/svc/$s"; done
echo "$FET0" > "$S/state/svc/frontend"
echo "743dfebac8c062011d7711bb59ebe727ee1e174724526b0160f6921afd5bfece $I26 $I26M" > "$S/state/ctr/backend"
echo "9a63d15ab1106830ca5d4d59bda98b440005f5310743b8e16c2e2f0079c0fe4a $FET0 $FEM0" > "$S/state/ctr/frontend"
for s in db redis db-backup; do echo "$(h cid-$s) $(cat "$S/state/svc/$s") sha256:$(h man-$s)" > "$S/state/ctr/$s"; done
touch "$S/state/v96"   # V96 기적용(11-16)
echo "$I26" > "$S/home/maint-v172/image-new.txt"   # V172D4 U1 이 남긴 운영 backend 기록
# 배포 전 읽기 v3 정상 출력(R1 0·R2 0·R3 0행)·관문 4판 정상 줄
printf 'LIVE | 0\nDYNAMIC | 0\nLIVE | 0\nDYNAMIC | 0\n' > "$S/fix/preflight.txt"
printf 'GATE|v96=1|created=0|running=0|odd_session=0|open=0|closing=0|odd_position=0|stopped_with_open=0|unresolved_orders=0|acct_pending=0|applied_missing=0|reported_ne_applied=0|cleanup=0|discrepancy=0|real_closing=0|real_running=0|real_held=0|qty_mismatch=0|balance_mismatch=0|exit_reason_unknown_to_old=0\nDECIDED|closing_without_decided_at=0\n' > "$S/fix/gate.txt"
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
P=$S/home/crypto_trader/AutoTrader/scripts/prospective
for c in $OLDC $NEWC $MIDC; do git -C "$REPOSRC" show $c:scripts/prospective/start_66.py > "$S/fix/start_66.$c.py"; done
git -C "$REPOSRC" show $NEWC:scripts/prospective/test_freshness_recheck_fail.py > "$S/fix/testfile.py"
cp "$S/fix/start_66.$OLDC.py" "$P/start_66.py"
cp "$S/fix/testfile.py" "$P/test_freshness_recheck_fail.py"
echo '{"at": "2026-11-10T00:19:30+00:00", "green": 22, "unknown": 0, "red": 0, "redCoins": [], "unknownCoins": [], "rechecked": [], "rows": {"KRW-BTC": {"dbTo": "x", "exTo": "x", "exErr": null, "count": 500, "rechecked": false}}}' > "$P/freshness_log.jsonl"
printf 'freshness 저장(이전 회차)\n' > "$P/freshness_runs.log"
printf 'Nov 10 09:19:01 yhdeabba CRON[1]: (manicstr) CMD (이전 회차 — 기준 위치 앞)\n' > "$S/fix/syslog"
# 대역 치환: 원판 이미지 값(합성) — 실제 U변수 값 대신 쓴다. 블록 원문은 바꾸지 않는다
{ echo "SYSLOG=$S/fix/syslog   # 대역 치환: 가짜 시스템 기록"
  echo "IMGOLD=$I26; IMGOLDM=$I26M; IMGOLDC=$I26C   # 대역 치환: 원판 backend(합성)"
  echo "FETOLD=$FET0; FEMOLD=$FEM0; FECOLD=$FEC0; FEIMGOLD=$FEI0   # 대역 치환: 원판 frontend(합성)"
  [ -z "${VARS_EXTRA:-}" ] || printf '%s\n' "$VARS_EXTRA"; } > "$S/vars.sh"
mkdir -p "$S/fix/gs" && cp "$GSD"/gate.sql "$GSD"/rollback_gate.sh "$GSD"/predeploy_read.v3.sql "$S/fix/gs/"
{ printf '9001 bash -c '; cat $FG/F.sh $FG/G.sh $BLOCKS/*.sh | tr '\n' ' '; echo
  echo '9002 /usr/bin/python3 scripts/prospective/start_66.py alerts --days 1 --no-mark'; } > "$S/state/procs.txt"
echo "대역 $S · 가짜 시각 $(TZ=KST-9 /usr/bin/date -d @"$(cat "$S/state/clock")" '+%F %T %Z')"
