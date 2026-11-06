#!/bin/bash
# 사용: init.sh <대역 이름> <가짜 시작 시각(KST, 예 '2026-11-07 10:20:00')> [IMGOLD 채움: yes|no]
# 새 대역 폴더를 만든다. 이미 있으면 멈춘다(실행 출력 보존).
set -eu
R=/d/tmp/n20d1sim; S=$R/runs/$1; [ ! -e "$S" ] || { echo "이미 있음 $S"; exit 2; }
mkdir -p "$S"/{state/tag,state/img,state/svc,fix,out,home/crypto_trader/AutoTrader/scripts/prospective,home/maint-n19,home/ops-scripts,home/sid203-ledger,home/daily-check}
TZ=KST-9 /usr/bin/date -d "$2" +%s > "$S/state/clock"
OLDC=ee509cf855ffb8ebc1ed31f1c2bbe772d0545ce8; NEWC=1899b49f633f8aefdc66b15c42debdaba8b3852c
echo $OLDC > "$S/state/HEAD"
h() { printf '%s' "$1" | /usr/bin/sha256sum | cut -c1-64; }
I25=sha256:$(h standin-image-25-ee509cf); echo "$I25" > "$S/fix/img25"
echo /d/tmp/n19v2/jars/ee509cf.jar > "$S/state/img/${I25#sha256:}"
echo /d/tmp/n19v2/jars/ee509cf.jar > "$S/fix/jar-$OLDC"; echo /d/tmp/n20c/jars/1899b49.jar > "$S/fix/jar-$NEWC"
# 이전 복구 태그 — D1 U변수의 IMGPREV* 값 그대로(이 이미지들의 jar 는 대역에서 꺼내지 않는다)
for p in ea44900:618046540764e49aa40efef7ec950f28cd5882e8bf7d6bdfdc3da0ed1f0112f9 b1d7664:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6 \
         6995c52:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07 fe01207:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7 \
         1103f48:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62 7a94472:e51b512a72cc3740313bb2a9e6f369d4f480554c588f322cff518c827f9c7be6; do
  echo "sha256:${p#*:}" > "$S/state/tag/${p%%:*}"; echo /d/tmp/n19/jars/7a94472.jar > "$S/state/img/${p#*:}"; done
echo "$I25" > "$S/state/tag/latest"; echo "$I25" > "$S/state/backend.image"; echo 2026-11-07T00:40:00.000000000Z > "$S/state/backend.started"
for s in db redis db-backup frontend; do echo "sha256:$(h standin-$s)" > "$S/state/svc/$s"; done
echo "$I25" > "$S/home/maint-n19/image-new.txt"
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
printf 'ledger a\n' > "$S/home/sid203-ledger/20261107-0930.txt"; printf 'ledger b\n' > "$S/home/sid203-ledger/20261106-0930.txt"
printf '대역 정기 보고\n' > "$S/home/daily-check/$(TZ=KST-9 /usr/bin/date -d "$2" +%Y%m%d)-1000.txt"
: > "$S/calls.log"; : > "$S/forbidden.log"; : > "$S/env.sh"
[ "${3:-yes}" = yes ] && echo "IMGOLD=$I25   # 대역 고정값 — #25 이미지 자리(실제 값 아님)" > "$S/imgold.sh"
echo "대역 $S · 가짜 시각 $(TZ=KST-9 /usr/bin/date -d @"$(cat "$S/state/clock")" '+%F %T %Z') · IMGOLD 채움 ${3:-yes}"
