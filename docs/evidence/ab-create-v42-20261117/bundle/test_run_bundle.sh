#!/usr/bin/env bash
# run_bundle.sh v4.2.2 기록 규칙 시험 (2026-11-17, 총괄 11-17 추가 지시: 출력 덮어쓰기·기록 실패·종료값 가림 거절).
# 운영 서버·DB·HTTP 를 부르지 않는다: PATH 앞에 대역(python3·docker·git·tee)을 두고 임시 OUT_DIR 에서 단계를 부른다.
#   python3 대역: 도구 스크립트 호출은 횟수만 세고 STUB_OUT 을 출력한 뒤 STUB_RC 로 끝난다. -c(초안 읽기)·--version 은 실제 python 으로 넘긴다.
#   docker 대역: psql 호출 횟수를 세고 STUB_PSQL_RC 로 끝난다. tee 대역: 대상 파일 이름이 FAIL_TEE 와 같으면 출력은 넘기되 1 로 끝난다(쓰기 실패 모사).
# 사용: bash bundle/test_run_bundle.sh   (끝에 "통과 N/N" — 하나라도 실패하면 종료 1)
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
RB="$HERE/run_bundle.sh"
REALPY=$(command -v python3); REALTEE=$(command -v tee)
WU=$(mktemp -d); W=$(cd "$WU" && (pwd -W 2>/dev/null || pwd)); STUB="$WU/stub"; mkdir -p "$STUB"   # PATH 에는 유닉스형(WU), 파일 경로는 python 이 읽는 형(W)
trap 'rm -rf "$WU"' EXIT

cat > "$STUB/python3" <<EOF
#!/usr/bin/env bash
for a in "\$@"; do case "\$a" in -c|--version) exec "$REALPY" "\$@";; esac; done
echo "\$*" >> "\$CALLS/py"
[ -n "\${STUB_OUT:-}" ] && printf '%s\n' "\$STUB_OUT"
exit "\${STUB_RC:-0}"
EOF
cat > "$STUB/docker" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *" ps -q backend"*) echo cid0; exit 0;;
  inspect*) echo "img 2026-11-17T00:00:00Z 0"; exit 0;;
  *psql*) cat > /dev/null; echo "psql $*" >> "$CALLS/psql"; echo "@@stub"; exit "${STUB_PSQL_RC:-0}";;
esac
exit 99
EOF
cat > "$STUB/git" <<'EOF'
#!/usr/bin/env bash
echo 0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d
EOF
cat > "$STUB/tee" <<EOF
#!/usr/bin/env bash
last="\${@: -1}"
if [ -n "\${FAIL_TEE:-}" ] && [ "\$(basename "\$last")" = "\$FAIL_TEE" ]; then cat; exit 1; fi
exec "$REALTEE" "\$@"
EOF
REALDATE=$(command -v date); REALMV=$(command -v mv)
cat > "$STUB/date" <<EOF
#!/usr/bin/env bash
# v4.2.2 시험용: FAIL_LOG_AT 번째 호출(=log 한 줄)에서 steps.log 를 폴더로 바꿔 그 줄의 기록을 실패시킨다
n=\$(( \$(cat "\$CALLS/date" 2>/dev/null || echo 0) + 1 )); echo \$n > "\$CALLS/date"
if [ -n "\${FAIL_LOG_AT:-}" ] && [ "\$n" -eq "\$FAIL_LOG_AT" ]; then rm -f "\$OUT_DIR/steps.log"; mkdir "\$OUT_DIR/steps.log"; fi
exec "$REALDATE" "\$@"
EOF
cat > "$STUB/mv" <<EOF
#!/usr/bin/env bash
# v4.2.2 시험용: MV_MODE=fail 이면 rc 확정(이름 바꾸기) 실패, MV_MODE=kill 이면 확정 직전에 실행 틀을 강제 종료(중단 모사)
case "\${MV_MODE:-}" in
  fail) exit 1;;
  kill) kill -9 \$PPID; sleep 5; exit 1;;
esac
exec "$REALMV" "\$@"
EOF
chmod +x "$STUB"/*
for c in python3 docker git tee date mv; do   # 대역이 실제 도구보다 앞에 오지 않으면 시험을 시작하지 않는다(실제 도구·DB 호출 방지)
  [ "$(PATH="$STUB:$PATH" command -v $c)" = "$STUB/$c" ] || { echo "대역 $c 가 앞에 오지 않음 — 시험 중단"; exit 2; }
done

PASS=0; FAIL=0; N=0
TOKEN="$W/token.txt"; printf 'stub-token-%s' "$RANDOM$RANDOM" > "$TOKEN"
new_case() {   # 새 OUT·호출 기록
  N=$((N + 1)); OUT="$W/out$N"; CALLS="$W/calls$N"; mkdir -p "$OUT" "$CALLS"; : > "$CALLS/py"; : > "$CALLS/psql"
}
run() {   # run STEP [env...] → RC, 화면 출력은 $W/last.txt
  local st=$1; shift
  env PATH="$STUB:$PATH" CALLS="$CALLS" OUT_DIR="$OUT" STEP="$st" REPO_DIR="$W/repo" TOKEN_FILE="$TOKEN" "$@" bash "$RB" > "$W/last.txt" 2>&1
  RC=$?
}
pycalls() { wc -l < "$CALLS/py" | tr -d ' '; }
psqlcalls() { wc -l < "$CALLS/psql" | tr -d ' '; }
check() {   # check 이름 조건...
  local name=$1; shift
  if "$@"; then PASS=$((PASS + 1)); echo "통과  $name"; else FAIL=$((FAIL + 1)); echo "실패  $name"; sed 's/^/      | /' "$W/last.txt"; fi
}
eq() { [ "$1" = "$2" ]; }

# 1 정상: 앞 단계 0 → 판정 0 → rc 0·rc 파일 0·판정 기록
new_case; echo 0 > "$OUT/0.rc"
run 1 STUB_OUT='{"ok":"stub"}'
check "T01 정상 STEP1 rc0·1.rc=0·판정 기록" eval '[ $RC -eq 0 ] && eq "$(cat $OUT/1.rc)" 0 && grep -q stub "$OUT/1-judge.txt" && [ $(pycalls) -eq 1 ]'
# 2 같은 OUT 재실행 → 5, 아무것도 부르지 않음, 기존 기록 그대로
before=$(sha256sum "$OUT/1-judge.txt" "$OUT/1.rc")
run 1 STUB_OUT='{"ok":"second"}'
check "T02 재실행 덮어쓰기 거절 rc5·호출 0·기록 불변" eval '[ $RC -eq 5 ] && [ $(pycalls) -eq 1 ] && [ $(psqlcalls) -eq 1 ] && eq "$(sha256sum "$OUT/1-judge.txt" "$OUT/1.rc")" "$before" && grep -q "덮어쓰기 거절" "$OUT/steps.log"'
# 3 출력 폴더(ab-create)만 미리 있어도 거절
new_case; echo 0 > "$OUT/1.rc"; mkdir "$OUT/ab-create"
run 2
check "T03 ab-create 폴더 있음 → rc5·도구 호출 0" eval '[ $RC -eq 5 ] && [ $(pycalls) -eq 0 ] && [ ! -e "$OUT/2.rc" ]'
# 4 앞 단계 rc 기록 없음 → 6
new_case
run 2
check "T04 앞 단계 기록 없음 → rc6·호출 0" eval '[ $RC -eq 6 ] && [ $(pycalls) -eq 0 ]'
# 5 앞 단계 rc 2 → 6
new_case; echo 2 > "$OUT/1.rc"
run 2
check "T05 앞 단계 rc2 → rc6·호출 0" eval '[ $RC -eq 6 ] && [ $(pycalls) -eq 0 ]'
# 6 도구 rc3 이 tee 뒤에 가려지지 않음
new_case; echo 0 > "$OUT/1.rc"
run 2 STUB_RC=3 STUB_OUT=partial
check "T06 도구 rc3 → 종료 3·2.rc=3·로그 rc=3" eval '[ $RC -eq 3 ] && eq "$(cat $OUT/2.rc)" 3 && grep -q "ab_create rc=3" "$OUT/steps.log" && grep -q "끝 rc=3" "$OUT/steps.log"'
# 7 tee 기록 실패 + 도구 0 → 8 (성공으로 보이지 않음)
new_case; echo 0 > "$OUT/1.rc"
run 2 STUB_RC=0 FAIL_TEE=2-ab-create.txt
check "T07 기록 실패·도구 0 → 종료 8·2.rc=8" eval '[ $RC -eq 8 ] && eq "$(cat $OUT/2.rc)" 8 && grep -q "기록(tee) 실패 rc=1 · 도구 rc=0" "$OUT/steps.log"'
# 8 tee 기록 실패 + 도구 3 → 3 유지, 둘 다 기록
new_case; echo 0 > "$OUT/1.rc"
run 2 STUB_RC=3 FAIL_TEE=2-ab-create.txt
check "T08 기록 실패·도구 3 → 종료 3·둘 다 기록" eval '[ $RC -eq 3 ] && eq "$(cat $OUT/2.rc)" 3 && grep -q "기록(tee) 실패 rc=1 · 도구 rc=3" "$OUT/steps.log"'
# 9 steps.log 기록 불가(폴더로 막음) + 도구 0 → 8
new_case; echo 0 > "$OUT/1.rc"; mkdir "$OUT/steps.log"
run 2 STUB_RC=0
check "T09 steps.log 기록 실패·도구 0 → 종료 8" eval '[ $RC -eq 8 ] && grep -q "steps.log 기록 실패" "$W/last.txt"'
# 10 steps.log 기록 불가 + 도구 3 → 3 유지
new_case; echo 0 > "$OUT/1.rc"; mkdir "$OUT/steps.log"
run 2 STUB_RC=3
check "T10 steps.log 기록 실패·도구 3 → 종료 3" eval '[ $RC -eq 3 ]'
# 11 STEP1 steps.log 기록 불가 → 판정기 부르기 전에 8
new_case; echo 0 > "$OUT/0.rc"; mkdir "$OUT/steps.log"
run 1
check "T11 STEP1 로그 실패 → 판정 전 종료 8" eval '[ $RC -eq 8 ] && [ $(pycalls) -eq 0 ]'
# 12 판정기 rc2 → 2 그대로
new_case; echo 0 > "$OUT/0.rc"
run 1 STUB_RC=2
check "T12 판정 rc2 → 종료 2·1.rc=2" eval '[ $RC -eq 2 ] && eq "$(cat $OUT/1.rc)" 2'
# 13 psql rc1 → 2, 판정기 부르지 않음
new_case; echo 0 > "$OUT/0.rc"
run 1 STUB_PSQL_RC=1
check "T13 psql 실패 → 종료 2·판정 호출 0" eval '[ $RC -eq 2 ] && [ $(pycalls) -eq 0 ] && eq "$(cat $OUT/1.rc)" 2'
# 14 토큰 문자열이 출력에 섞이면 9
new_case; echo 0 > "$OUT/1.rc"
run 2 STUB_RC=0 STUB_OUT="$(cat "$TOKEN")"
check "T14 토큰 노출 → 종료 9" eval '[ $RC -eq 9 ] && eq "$(cat $OUT/2.rc)" 9'
# 15 STEP3 초안 없음 → 2, psql·완성기 호출 0
new_case; echo 0 > "$OUT/2.rc"
run 3
check "T15 초안 없음 → 종료 2·psql 0·완성 0" eval '[ $RC -eq 2 ] && [ $(psqlcalls) -eq 0 ] && [ $(pycalls) -eq 0 ]'
# 16 STEP3 정상 초안 → psql 1·완성기 1·rc0
new_case; echo 0 > "$OUT/2.rc"; mkdir -p "$OUT/ab-create"
printf '{"t0":"2026-11-18T00:15:00+00:00","sessions":[{"session_id":"601"},{"session_id":"602"}]}' > "$OUT/ab-create/creation_log.draft.json"
run 3 STUB_RC=0
check "T16 STEP3 정상 → psql 1·완성 1·rc0" eval '[ $RC -eq 0 ] && [ $(psqlcalls) -eq 1 ] && [ $(pycalls) -eq 1 ] && grep -q "ids=601,602" "$CALLS/psql"'
# 17 STEP5 완성기 rc3 → 3
new_case; echo 0 > "$OUT/4.rc"; mkdir -p "$OUT/sma20-create"
printf '{"t0":"2026-11-19T00:00:00+00:00","session_id":610}' > "$OUT/sma20-create/sma20_creation.draft.json"
run 5 STUB_RC=3
check "T17 STEP5 완성 rc3 → 종료 3·5.rc=3" eval '[ $RC -eq 3 ] && eq "$(cat $OUT/5.rc)" 3'
# 18 STEP4 창 값 없음 → 도구 호출 없이 0 이 아닌 종료
new_case; echo 0 > "$OUT/3.rc"
run 4
check "T18 STEP4 창 값 없음 → 0 아님·호출 0" eval '[ $RC -ne 0 ] && [ $(pycalls) -eq 0 ]'
# 19 잘못된 STEP → 2
new_case
run 7
check "T19 STEP=7 → 종료 2" eval '[ $RC -eq 2 ]'

check "T01b 정상 뒤 임시 표지 없음" eval '[ ! -e "$W/out1/1.rc.tmp" ]'

# ── v4.2.2 (11-17 보충 지시): 기록 실패·확정 전 중단 뒤 성공 표지가 남지 않고, 그 상태로 다음 단계를 직접 불러도 도구 호출 0 ──
keep() {   # keep 이름 첫RC 다음RC — KEEP_DIR 이 있으면 종료값·남은 파일·steps.log·화면 출력을 보존
  [ -n "${KEEP_DIR:-}" ] || return 0
  local f="$KEEP_DIR/$1.txt"
  { echo "사례 $1"; echo "첫 호출 종료값=$2"; echo "다음 단계 직접 호출 종료값=$3"; echo "도구 호출(python 대역) 누계=$(pycalls) · psql 대역 누계=$(psqlcalls)"
    echo "--- 남은 파일(OUT)"; (cd "$OUT" && ls -la)
    for x in "$OUT"/*.rc "$OUT"/*.rc.tmp; do [ -f "$x" ] && echo "--- $(basename "$x"): $(cat "$x")"; done
    echo "--- steps.log"; if [ -f "$OUT/steps.log" ]; then cat "$OUT/steps.log"; else echo "(파일 아님 — 기록 실패 주입으로 폴더)"; fi
    echo "--- 다음 단계 화면 출력"; cat "$W/last.txt"; echo "--- 첫 호출 화면 출력"; cat "$W/first.txt"; } > "$f"
}
next_after() {   # next_after 다음STEP — 첫 결과를 보관하고 같은 OUT 에서 다음 단계를 직접 부른다(호출 수 변화 확인)
  cp "$W/last.txt" "$W/first.txt"; R1=$RC; P0=$(pycalls); Q0=$(psqlcalls)
  run "$1"; R2=$RC; DP=$(( $(pycalls) - P0 )); DQ=$(( $(psqlcalls) - Q0 ))
}
# 정상 STEP2 의 log 줄 수(=date 호출 수)를 먼저 세어 마지막 줄 번호를 얻는다
new_case; echo 0 > "$OUT/1.rc"; : > "$CALLS/date"; run 2 STUB_RC=0; LAST2=$(cat "$CALLS/date")
check "T20a 기준: STEP2 정상 rc0 · log 줄 수=$LAST2" eval '[ $RC -eq 0 ] && eq "$(cat $OUT/2.rc)" 0 && [ ! -e "$OUT/2.rc.tmp" ] && [ "$LAST2" -ge 2 ]'
new_case; echo 0 > "$OUT/0.rc"; : > "$CALLS/date"; run 1 STUB_OUT='{"ok":"stub"}'; LAST1=$(cat "$CALLS/date")

# T20 도구0 + 마지막 로그 실패 → 8, 2.rc 없음 → STEP3 직접 호출 6·호출 0
new_case; echo 0 > "$OUT/1.rc"; : > "$CALLS/date"
run 2 STUB_RC=0 FAIL_LOG_AT=$LAST2; next_after 3; keep T20 $R1 $R2
check "T20 도구0+마지막 로그 실패 → 8·2.rc 없음 → 다음 거절(6·8)·호출 0" eval '[ $R1 -eq 8 ] && [ ! -e "$OUT/2.rc" ] && [ -d "$OUT/steps.log" ] && { [ $R2 -eq 6 ] || [ $R2 -eq 8 ]; } && [ $DP -eq 0 ] && [ $DQ -eq 0 ]'
# T21 도구0 + rc 파일만 쓰기(확정) 실패 → 8, 2.rc 없음·2.rc.tmp 흔적 → 다음 6·호출 0
new_case; echo 0 > "$OUT/1.rc"
run 2 STUB_RC=0 MV_MODE=fail; next_after 3; keep T21 $R1 $R2
check "T21 도구0+rc 확정 실패 → 8·2.rc 없음 → 다음 거절(6·8)·호출 0" eval '[ $R1 -eq 8 ] && [ ! -e "$OUT/2.rc" ] && [ -e "$OUT/2.rc.tmp" ] && grep -q "rc 파일 확정 실패(원래 rc=0)" "$OUT/steps.log" && { [ $R2 -eq 6 ] || [ $R2 -eq 8 ]; } && [ $DP -eq 0 ] && [ $DQ -eq 0 ]'
# T22 도구3 + 마지막 로그 실패 → 3 유지, 2.rc 없음 → 다음 6·호출 0
new_case; echo 0 > "$OUT/1.rc"; : > "$CALLS/date"
run 2 STUB_RC=3 FAIL_LOG_AT=$LAST2; next_after 3; keep T22 $R1 $R2
check "T22 도구3+마지막 로그 실패 → 3·2.rc 없음 → 다음 거절(6·8)·호출 0" eval '[ $R1 -eq 3 ] && [ ! -e "$OUT/2.rc" ] && { [ $R2 -eq 6 ] || [ $R2 -eq 8 ]; } && [ $DP -eq 0 ] && [ $DQ -eq 0 ]'
# T23 도구3 + tee 기록 실패 → 3·2.rc=3 → 다음 6·호출 0
new_case; echo 0 > "$OUT/1.rc"
run 2 STUB_RC=3 FAIL_TEE=2-ab-create.txt; next_after 3; keep T23 $R1 $R2
check "T23 도구3+tee 기록 실패 → 3·2.rc=3 → 다음 거절(6·8)·호출 0" eval '[ $R1 -eq 3 ] && eq "$(cat $OUT/2.rc)" 3 && { [ $R2 -eq 6 ] || [ $R2 -eq 8 ]; } && [ $DP -eq 0 ] && [ $DQ -eq 0 ]'
# T24 성공 표지 확정 전 중단(확정 직전 강제 종료) → 0 아님·2.rc 없음 → 다음 6·호출 0
new_case; echo 0 > "$OUT/1.rc"
run 2 STUB_RC=0 MV_MODE=kill; next_after 3; keep T24 $R1 $R2
check "T24 확정 전 중단 → rc$R1(0 아님)·2.rc 없음 → 다음 거절(6·8)·호출 0" eval '[ $R1 -ne 0 ] && [ ! -e "$OUT/2.rc" ] && { [ $R2 -eq 6 ] || [ $R2 -eq 8 ]; } && [ $DP -eq 0 ] && [ $DQ -eq 0 ]'
# T25 STEP1(판정0) 마지막 로그 실패 → 8 → STEP2(생성기, POST 경로) 직접 호출 6·생성기 호출 0
new_case; echo 0 > "$OUT/0.rc"; : > "$CALLS/date"
run 1 STUB_OUT='{"ok":"stub"}' FAIL_LOG_AT=$LAST1; next_after 2; keep T25 $R1 $R2
check "T25 STEP1 마지막 로그 실패 → 8 → STEP2 거절(6·8)·생성기 호출 0" eval '[ $R1 -eq 8 ] && [ ! -e "$OUT/1.rc" ] && { [ $R2 -eq 6 ] || [ $R2 -eq 8 ]; } && [ $DP -eq 0 ] && [ ! -e "$OUT/ab-create" ]'
# T26 확정 실패 흔적(.tmp)이 있으면 같은 단계 재실행도 덮어쓰기 거절 5·호출 0
P0=$(pycalls); run 1; check "T26 기록 실패 상태에서 같은 단계 재실행 → 0 아님($RC)·판정 호출 0" eval '[ $RC -ne 0 ] && [ $(pycalls) -eq $P0 ]'
new_case; echo 0 > "$OUT/1.rc"; run 2 STUB_RC=0 MV_MODE=fail; rm -rf "$OUT/2-ab-create.txt" "$OUT/ab-create"; P0=$(pycalls)
run 2; check "T26b 2.rc.tmp 흔적만 남겨도 같은 단계 재실행 5·호출 0" eval '[ $RC -eq 5 ] && [ $(pycalls) -eq $P0 ] && grep -q "이미 있음: 2.rc.tmp" "$OUT/steps.log"'

echo "통과 $PASS/$((PASS + FAIL))"
[ $FAIL -eq 0 ]
