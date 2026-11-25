#!/usr/bin/env bash
# 소액 실주문 시험 로컬 실행기 — 초안 v2(v1.243 §3-1 → v1.249 §3 보완). 🔴 실행 금지: 총괄의 원문 수용·승인 전.
# 사용: launch.sh <변수 파일> <단계>   단계 = p0 | create | verify | start | watch | stop | gate
# 비어 있는 승인값·대상 ID·잘못된 날짜/창이면 ssh 0회로 거절한다(종료 3). 원격 단계는 smoke.sh 가 다시 같은 검사를 한다.
# 같은 단계의 POST(create·start·stop)는 로컬 표지로 한 번만 보낸다. 자동 재시도 없음.
# v2: 시각은 kst.py(고정 UTC+09:00, TZ·tzdata 미사용)로만 판정한다. create/start 는 ssh 직전에 새 시각으로 다시 검사하고,
#     원격 smoke.sh 가 POST 바로 앞에서 한 번 더 검사한다. gate 는 원격 종료값·판정기 종료값·필수 기록을 따로 남기고
#     PASS·원격0·기록 성공일 때만 0 을 돌려준다(HOLD 2·FAIL 1·판정 출력 불일치 6·기록 실패 4·원격 실패는 원래 값).
set -u
here="$(cd "$(dirname "$0")" && pwd)"; VF=${1:?변수 파일}; STEP=${2:?단계}
GATE_PY="$here/../one-entry-gate-v4-20261125/gate_check.py"; GATE_SQL="$here/../one-entry-gate-v4-20261125/recovery-gate-v4.sql"
# ---- 함수 블록 시작 (tests/ 가 이 블록만 잘라 검증한다)
no() { echo "🔴 거절(종료 3, ssh 0회): $*"; exit 3; }
kst_win() { "$PY" -I "$here/kst.py" window "$STEP" "$APPROVED_DATE" "$WIN_START" "$WIN_END"; }
gate_finalize() {  # $1 결과 폴더 $2 원격 종료값 → 최종 종료값을 return 한다
  local L=$1 rrc=$2 wf=0 vrc v
  sed -n '/^GATE-OUT-BEGIN$/,/^GATE-OUT-END$/p' "$L/gate.out" > "$L/gate-block.txt" || wf=1
  sed '1d;$d' "$L/gate-block.txt" > "$L/gate-psql.txt" || wf=1
  sed -n 's/^GATE-RC=//p' "$L/gate.out" > "$L/gate-rc.txt" || wf=1
  printf 'sid=%s\nsession_type=REAL\nstrategy_type=DAILY_SMA20\ncoin_pair=KRW-BTC\ntimeframe=H1\ninitial_capital=10000\ninvest_ratio=0.995\nmax_investment=9950\nmax_entries=1\n' "$SID" > "$L/registration.txt" || wf=1
  : > "$L/gate-verdict.txt" || wf=1
  "$PY" -I "$GATE_PY" "$L/gate-psql.txt" "$L/gate-rc.txt" "$L/registration.txt" > "$L/gate-verdict.txt" 2> "$L/gate-verdict.err"; vrc=$?
  v=$(head -1 "$L/gate-verdict.txt" 2>/dev/null)
  printf 'remote_rc=%s\nverdict_rc=%s\nverdict_line=%s\nrecord_fail=%s\n' "$rrc" "$vrc" "$v" "$wf" > "$L/gate-final.txt" || wf=1
  cat "$L/gate-verdict.txt" 2>/dev/null
  if [ "$rrc" != 0 ]; then echo "🔴 원격 실패 rc=$rrc(판정 rc=$vrc·기록 실패=$wf 는 gate-final.txt) — 원래 값으로 끝낸다"; return "$rrc"; fi
  if [ "$wf" != 0 ]; then echo "🔴 필수 기록 실패 — 게이트 확정·후속 사용 차단"; return 4; fi
  case "$vrc|$v" in
    "0|VERDICT PASS") echo "✅ 게이트 PASS(원격0·판정0·기록 완료)"; return 0;;
    "2|VERDICT HOLD") echo "🟡 게이트 HOLD — 종료 확정 아님, 보고"; return 2;;
    "1|VERDICT FAIL") echo "🔴 게이트 FAIL — 종료 확정 아님, 보고"; return 1;;
    *) echo "🔴 판정기 실패/출력 불일치(rc=$vrc, 첫 줄='$v') — 확정 아님"; return 6;;
  esac
}
# ---- 함수 블록 끝
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
PY=$(command -v python3 || command -v python) || no "python 없음 — 시각 검사 불가"
kres=$(kst_win) || no "시각 검사(kst.py rc≠0): ${kres:-출력 없음}"; echo "KST $kres"
L="$here/../live-smoke-run-$RUN_TAG"; [ "$STEP" = p0 ] && { [ ! -e "$L" ] || no "결과 폴더 $L 가 이미 있다"; }
mkdir -p "$L" || no "결과 폴더 생성 실패"
case "$STEP" in create|start|stop) [ ! -e "$L/sent-$STEP" ] || no "$STEP 은 이미 보냈다 — 다시 보내지 않는다. 확인 읽기(verify/watch/gate)부터";; esac
[ "$STEP" = p0 ] || [ -e "$L/ok-p0" ] || no "P0 통과 표지 없음"
case "$STEP" in start) [ -e "$L/ok-verify" ] || no "생성 대조(verify) 통과 없음";; esac
command -v ssh >/dev/null || no "ssh 없음"
case "$(command -v ssh)" in /usr/bin/ssh|/c/Windows/System32/OpenSSH/ssh*) ;; *) no "ssh 경로 $(command -v ssh) — 가짜 ssh 가능성, 확인 전 거절";; esac
[ -r "$here/kst.py" ] && ! grep -q '^KSTPY_EOF$' "$here/kst.py" || no "kst.py 읽기 실패 또는 구분 줄 충돌"
case "$STEP" in create|start)  # POST 단계: ssh 직전 새 시각으로 재검사(원격도 POST 바로 앞에서 다시 한다)
  kres=$(kst_win) || no "POST 직전 시각 재검사: ${kres:-출력 없음}"; echo "KST 재검사 $kres" > "$L/precheck-$STEP.txt" || no "재검사 기록 실패";; esac
case "$STEP" in create|start|stop) date '+%F %T %Z' > "$L/sent-$STEP" || no "표지 기록 실패";; esac
{ printf 'APPROVED_DATE=%q WIN_START=%q WIN_END=%q EXPECTED_SHA=%q EXPECTED_IMAGE=%q POOL_EXPECTED=%q ALLOC_BEFORE=%q SID=%q RUN_TAG=%q STEP=%q\n' \
    "$APPROVED_DATE" "$WIN_START" "$WIN_END" "$EXPECTED_SHA" "$EXPECTED_IMAGE" "$POOL_EXPECTED" "$ALLOC_BEFORE" "${SID:-}" "$RUN_TAG" "$STEP"
  echo "KSTPY=\$(cat <<'KSTPY_EOF'"; cat "$here/kst.py"; echo "KSTPY_EOF"; echo ")"
  cat "$here/smoke.sh"
  [ "$STEP" = gate ] && { echo "cat > \"\$M/gate.sql\" <<'GATESQL' || stop \"gate.sql 기록 실패\""; cat "$GATE_SQL"; echo GATESQL; echo 'run_gate'; }
} | ssh "$SSH_HOST" bash -s > "$L/$STEP.out" 2>&1
ps=("${PIPESTATUS[@]}"); rc=${ps[1]}
[ "${ps[0]}" = 0 ] || { echo "🔴 원격 원문 조립 실패 rc=${ps[0]}(ssh rc=$rc)" | tee -a "$L/$STEP.local-err"; [ "$rc" = 0 ] && rc=5; }
echo "rc=$rc" >> "$L/$STEP.out" || { echo "🔴 종료값 기록 실패"; [ "$rc" = 0 ] && rc=4; }
cat "$L/$STEP.out"
if [ "$rc" = 0 ]; then case "$STEP" in p0|verify) touch "$L/ok-$STEP" || { echo "🔴 ok-$STEP 표지 기록 실패"; rc=4; };; esac; fi
if [ "$STEP" = gate ]; then gate_finalize "$L" "$rc"; rc=$?; fi
exit "$rc"
