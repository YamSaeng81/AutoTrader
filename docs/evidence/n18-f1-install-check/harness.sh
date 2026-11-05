#!/bin/bash
# N18 국소 대역 시험 — 사용: harness.sh <F1블록파일> <사례> <결과폴더>
# 서버·DB·텔레그램·업비트에 접속하지 않는다. 모든 외부 명령은 아래 함수 대역이다.
set -u
F1=$(realpath "$1"); CASE=$2; OUT=$(realpath -m "$3"); HERE=$(cd "$(dirname "$0")" && pwd)
rm -rf "$OUT"; mkdir -p "$OUT/home/ops-scripts" "$OUT/home/maint-n15n16" "$OUT/repo"
export HOME="$OUT/home"; CALLS="$OUT/calls.txt"; : > "$CALLS"
OPS="$HOME/ops-scripts"
printf 'c669ccdc6e248ce8e55e9a7ac7405197d266fa18\n' > "$OPS/INSTALLED_SHA"
cp "$HERE/fixtures/daily_check.sh" "$HERE/fixtures/daily_checks.sql" "$OPS/"
printf '8bfe8a9fa896f400b3fb6681fef021c99d6ba43b\n' > "$OPS/SID_LEDGER_SHA"
cp "$HERE/fixtures/sid_ledger.sql" "$OPS/sid_ledger.sql"
echo 'sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62' > "$HOME/maint-n15n16/image-new.txt"
echo 'API_AUTH_TOKEN=대역토큰' > "$OUT/repo/.env"
# F 변수 원문(첫 줄 cd 만 뺀다)을 그대로 읽어 EG·EGS·LIVE1·KEEP·BASE·BASEX·BTCIN 을 쓴다
source <(tail -n +2 "$HERE/F-orig.sh")
mkdir -p "$MS"; echo '기준선 경보(대역)' > "$MS/alerts-before.txt"
cd "$OUT/repo"
# --- 사례별 설치 상태 ---
case "$CASE" in
  normal) ;;
  marker_bad) printf 'c669ccdc6e248ce8e55e9a7ac7405197d266fa19\n' > "$OPS/INSTALLED_SHA" ;;
  marker_old) printf '843xxxx\n' > "$OPS/INSTALLED_SHA" ;;
  shell_bad) printf '\n# 1바이트 이상 추가\n' >> "$OPS/daily_check.sh" ;;
  sql_bad) printf '\n-- 추가\n' >> "$OPS/daily_checks.sql" ;;
  marker_missing) rm "$OPS/INSTALLED_SHA" ;;
  shell_missing) rm "$OPS/daily_check.sh" ;;
  sql_missing) rm "$OPS/daily_checks.sql" ;;
  sql_unreadable) rm "$OPS/daily_checks.sql"; mkdir "$OPS/daily_checks.sql" ;;
  *) echo "모르는 사례 $CASE"; exit 9 ;;
esac
# 옛 통과 표지·운영자 확인을 미리 둔다(실패 때 새 ok-F1 이 남지 않는지 본다)
echo old > "$M/ok-F1"; printf '경보 네 갈래: 기존 반복만\n휴대폰 수신 시각: 08:40\n' > "$M/operator-confirm.txt"
# --- 외부 명령 대역 ---
git() { case "$1" in rev-parse) echo "$NEW";; status) :;; esac; }
svc() { echo "backend sha256:9818254c… 대역"; }
docker() { echo 'sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62'; }
DC=dcstub
dcstub() { case "$*" in "ps -q backend") echo cid;; "exec -T backend env") printf 'TRADING_CAPITALPOOL_STAGE=STAGE_1\nTRADING_CAPITALPOOL_TOTALCAPKRW=100000\nTRADING_CAPITALPOOL_PERPOSITIONCAPKRW=20000\nTRADING_CAPITALPOOL_MAXCONCURRENTPOSITIONS=3\nOTHER=x\n';; esac; }
PSQL=psqlstub
psqlstub() { local q="" a; for a in "$@"; do q="$a"; done
  case "$q" in
    "$EG") echo "$EGS";; "$LIVE1") echo "203:CREATED";; "$KEEP") echo t;; "$BASE") echo "$BASEX";; "$BTCIN") echo "true|12-01 07:00|12-01 07:00|20/20";;
    *"available_krw = 20200"*) echo t;;
    -F|" ") cat > /dev/null; printf 'real_open_positions 0\nreal_unresolved_orders 0\ncleanup_pending 0\nsid_orders 0\nsid_positions 0\n';;
    *) echo "(대역 -x 출력)";; esac; }
sc() { echo "66 | 63 | 3 | -"; }
curl() { case "$*" in
  *health/exchange*) echo '{"data":{"status":"UP","latencyMs":7}}';;
  *account/summary*) echo '{"data":{"availableKrw":"20500","lockedKrw":"0","holdings":[]}}';;
  *telegram/test*) echo "TELEGRAM_TEST" >> "$CALLS"; echo 200;;
  *sessions/*/start*) echo "START" >> "$CALLS"; echo '{"대역":1}';;
  *) echo "모르는 curl $*" >> "$CALLS";; esac; }
python3() { if [ "$1" = scripts/prospective/start_66.py ]; then printf '🟢 경보 없음(대역)\n🔴 🔴 은 경보이고 확정이 아니다 — 대역\n'; return 0; fi; python "$@"; }
export -f git svc docker dcstub psqlstub sc curl python3
# --- F1 ---
( source "$F1" ) > "$OUT/f1.out" 2>&1; echo "$?" > "$OUT/f1.rc"
# 운영자가 F1 뒤 확인 파일을 쓴 상황(F1 운영자 확인 블록과 같은 형식, 자리표시 없음)
sleep 1; printf '경보 네 갈래: 기존 반복만\n휴대폰 수신 시각: 08:41\n' > "$M/operator-confirm.txt"
# --- F2 원문(같은 날 시도) ---
( source "$HERE/f2-orig.sh" ) > "$OUT/f2.out" 2>&1; echo "$?" > "$OUT/f2.rc"
D=$(TZ=Asia/Seoul date +%F)
okn=0; [ -f "$M/ok-F1" ] && [ "$(cat "$M/ok-F1" 2>/dev/null)" != old ] && okn=1
oldok=0; [ "$(cat "$M/ok-F1" 2>/dev/null)" = old ] && oldok=1
printf '%s f1_rc=%s f2_rc=%s new_ok=%s old_ok_left=%s confirm_written=%s failed_mark=%s telegram=%s start=%s install=[%s] f1_stop=[%s] f2_stop=[%s]\n' \
  "$CASE" "$(cat "$OUT/f1.rc")" "$(cat "$OUT/f2.rc")" "$okn" "$oldok" "$([ -e "$M/operator-confirm.txt" ] && echo 1 || echo 0)" \
  "$([ -e "$M/f1-failed-$D" ] && echo 1 || echo 0)" "$(grep -c TELEGRAM_TEST "$CALLS")" "$(grep -c START "$CALLS")" \
  "$( [ -f "$M/install-f1.txt" ] && tr '\n' ';' < "$M/install-f1.txt" || echo 파일없음)" "$(grep '🔴 멈춤' "$OUT/f1.out" | head -1)" "$(grep '🔴 멈춤' "$OUT/f2.out" | head -1)"
