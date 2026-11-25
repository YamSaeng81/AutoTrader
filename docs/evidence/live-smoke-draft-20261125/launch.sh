#!/usr/bin/env bash
# 소액 실주문 시험 로컬 실행기 — 초안(v1.243 §3-1). 🔴 실행 금지: 총괄의 원문 수용·승인 전.
# 사용: launch.sh <변수 파일> <단계>   단계 = p0 | create | verify | start | watch | stop | gate
# 비어 있는 승인값·대상 ID·잘못된 날짜/창이면 ssh 0회로 거절한다(종료 3). 원격 단계는 smoke.sh 가 다시 같은 검사를 한다.
# 같은 단계의 POST(create·start·stop)는 로컬 표지로 한 번만 보낸다. 자동 재시도 없음.
set -u
here="$(cd "$(dirname "$0")" && pwd)"; VF=${1:?변수 파일}; STEP=${2:?단계}
no() { echo "🔴 거절(종료 3, ssh 0회): $*"; exit 3; }
[ -r "$VF" ] || no "변수 파일 없음"
grep -qiE 'token|password|secret|bearer' "$VF" && no "변수 파일에 인증값처럼 보이는 줄이 있다"
# shellcheck disable=SC1090
. "$VF"
for k in SSH_HOST APPROVED_DATE WIN_START WIN_END EXPECTED_SHA EXPECTED_IMAGE POOL_EXPECTED ALLOC_BEFORE RUN_TAG; do
  [ -n "${!k:-}" ] || no "$k 비어 있음"
done
case "$STEP" in p0|create) [ -z "${SID:-}" ] || no "생성 전 단계인데 SID 가 채워져 있다";;
  verify|start|watch|stop|gate) [[ "${SID:-}" =~ ^[0-9]+$ ]] || no "SID 비어 있거나 숫자 아님";;
  *) no "알 수 없는 단계 $STEP";; esac
[[ "$APPROVED_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ && "$WIN_START" =~ ^[0-9]{4}$ && "$WIN_END" =~ ^[0-9]{4}$ ]] || no "날짜/창 형식"
today=$(TZ=Asia/Seoul date +%F); hm=$((10#$(TZ=Asia/Seoul date +%H%M)))
case "$STEP" in p0|create|verify|start)
  [ "$today" = "$APPROVED_DATE" ] || no "오늘 $today KST ≠ 승인 날짜 $APPROVED_DATE"
  [ $hm -ge $((10#$WIN_START)) ] && [ $hm -le $((10#$WIN_END)) ] || no "지금 $hm KST 는 창 $WIN_START~$WIN_END 밖";; esac
L="$here/../live-smoke-run-$RUN_TAG"; [ "$STEP" = p0 ] && { [ ! -e "$L" ] || no "결과 폴더 $L 가 이미 있다"; }
mkdir -p "$L" || no "결과 폴더 생성 실패"
case "$STEP" in create|start|stop) [ ! -e "$L/sent-$STEP" ] || no "$STEP 은 이미 보냈다 — 다시 보내지 않는다. 확인 읽기(verify/watch/gate)부터";; esac
[ "$STEP" = p0 ] || [ -e "$L/ok-p0" ] || no "P0 통과 표지 없음"
case "$STEP" in start) [ -e "$L/ok-verify" ] || no "생성 대조(verify) 통과 없음";; esac
command -v ssh >/dev/null || no "ssh 없음"
case "$(command -v ssh)" in /usr/bin/ssh|/c/Windows/System32/OpenSSH/ssh*) ;; *) no "ssh 경로 $(command -v ssh) — 가짜 ssh 가능성, 확인 전 거절";; esac
case "$STEP" in create|start|stop) date '+%F %T %Z' > "$L/sent-$STEP" || no "표지 기록 실패";; esac
{ printf 'APPROVED_DATE=%q WIN_START=%q WIN_END=%q EXPECTED_SHA=%q EXPECTED_IMAGE=%q POOL_EXPECTED=%q ALLOC_BEFORE=%q SID=%q RUN_TAG=%q STEP=%q\n' \
    "$APPROVED_DATE" "$WIN_START" "$WIN_END" "$EXPECTED_SHA" "$EXPECTED_IMAGE" "$POOL_EXPECTED" "$ALLOC_BEFORE" "${SID:-}" "$RUN_TAG" "$STEP"
  cat "$here/smoke.sh"; [ "$STEP" = gate ] && { echo "cat > \"\$M/gate.sql\" <<'GATESQL'"; cat "$here/../one-entry-gate-v3-20261125/recovery-gate-v3.sql"; echo GATESQL; echo 'run_gate'; }
} | ssh "$SSH_HOST" bash -s > "$L/$STEP.out" 2>&1
rc=${PIPESTATUS[1]}; echo "rc=$rc" >> "$L/$STEP.out"; cat "$L/$STEP.out"
[ "$rc" = 0 ] && case "$STEP" in p0|verify) touch "$L/ok-$STEP";; esac
if [ "$STEP" = gate ]; then
  sed -n '/^GATE-OUT-BEGIN$/,/^GATE-OUT-END$/p' "$L/gate.out" | sed '1d;$d' > "$L/gate-psql.txt"
  sed -n 's/^GATE-RC=//p' "$L/gate.out" > "$L/gate-rc.txt"
  printf 'sid=%s\nsession_type=REAL\nstrategy_type=DAILY_SMA20\ncoin_pair=KRW-BTC\ntimeframe=H1\ninitial_capital=10000\ninvest_ratio=0.995\nmax_investment=9950\nmax_entries=1\n' "$SID" > "$L/registration.txt"
  python -I "$here/../one-entry-gate-v3-20261125/gate_check.py" "$L/gate-psql.txt" "$L/gate-rc.txt" "$L/registration.txt" | tee "$L/gate-verdict.txt"
fi
exit "$rc"
