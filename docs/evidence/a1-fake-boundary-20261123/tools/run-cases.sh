#!/usr/bin/env bash
# A1 가짜 경계 시험 구동기 (v1.225 §3). 로컬 파일만 쓴다. 원격 접속·컨테이너·DB 를 쓰지 않는다.
# 사용: bash run-cases.sh <회차 이름 r1·r2…>   → ../runs/<회차>/ (이미 있으면 거절: 새 고유 보존 경로만)
# 순서: 생성(시험 사본·차이) → 감지용 대체 파일·PATH 확인 → 가짜 식별 → 가드 거절 사례 → 합성 응답 사례 → 결과표·해시
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; B="$(dirname "$HERE")"; EV="$(dirname "$B")"
R=${1:?회차 이름}; RUN="$B/runs/$R"
case "$RUN" in /*) ;; *) echo "보존 경로가 POSIX 절대 경로 아님"; exit 1;; esac
case "$RUN" in *'\'*) echo "보존 경로에 역슬래시"; exit 1;; esac
[ ! -e "$RUN" ] || { echo "보존 경로 이미 있음: $RUN — 새 회차 이름 사용"; exit 1; }
mkdir -p "$RUN/cases" "$RUN/diff" "$RUN/tripwire"
echo "pc-start $(date -u +%FT%TZ)" > "$RUN/run-time.txt"
NA_OK=2099-01-01T00:00:00Z; NA_LATE=2000-01-01T00:00:00Z
SQL_SHA=b84a89e7fec21a95dd80ba8f9491b39a7e65697e3ea05b12ae21a8de49feefcf
PROD="$B/prod/approval/a1-read.sh"; OLD="$EV/a1-time-20261123/approval/a1-read.sh"

# 0) 가짜 정적 검사: 원격/컨테이너/HTTP 도구 이름·운영 별칭이 없어야 한다
if grep -nwE 'ssh|scp|sftp|docker|psql|curl|wget|nc|telnet|cat-prod' "$HERE/fake-remote.sh" > "$RUN/fake-static-check.txt"; then
  echo "🔴 가짜에 금지 낱말 — 시험 시작 안 함"; exit 1; fi
echo "fake-remote.sh 금지 낱말(ssh|scp|sftp|docker|psql|curl|wget|nc|telnet|운영 별칭) 0건" > "$RUN/fake-static-check.txt"
# 1) 키트 2개: 새 초안(v2) 시험 사본 · 구 초안(15e3e412) 시험 사본(결함 재현 1건에만 사용)
mkkit() { # $1 키트 이름 $2 원본 초안
  local k="$RUN/$1"; mkdir -p "$k/approval" "$k/sql" "$k/fake/fixtures"
  cp "$B/prod/sql/a1-time-1.sql" "$k/sql/" && cp "$HERE/fake-remote.sh" "$k/fake/" && chmod +x "$k/fake/fake-remote.sh" \
    && cp "$HERE/fixtures/"*.txt "$HERE/fixtures/"*.tsv "$k/fake/fixtures/" \
    && bash "$HERE/make-test-copy.sh" "$2" "$k/fake/fake-remote.sh" "$k/approval/a1-read-test.sh" >> "$RUN/make.txt" 2>&1
}
mkkit kit-new "$PROD" || { echo "kit-new 생성 실패"; exit 1; }
mkkit kit-old "$OLD"  || { echo "kit-old 생성 실패"; exit 1; }
diff -u "$OLD" "$PROD" > "$RUN/diff/old15e3-vs-prod-v2.diff"
diff -u "$PROD" "$RUN/kit-new/approval/a1-read-test.sh" > "$RUN/diff/prod-v2-vs-test-copy.diff"
diff -u "$OLD" "$RUN/kit-old/approval/a1-read-test.sh" > "$RUN/diff/old15e3-vs-old-test-copy.diff"
{ sha256sum "$OLD" "$PROD" "$B/prod/sql/a1-time-1.sql" "$HERE/fake-remote.sh" "$HERE/test-guard.inc" "$HERE/make-test-copy.sh" "$0" \
    "$RUN/kit-new/approval/a1-read-test.sh" "$RUN/kit-old/approval/a1-read-test.sh" | sed "s|  *\*\?$B/|  |;s|$EV/|../|"; } > "$RUN/inputs-SHA256.txt"

# 2) 감지용 대체 파일: PATH 로 원격/컨테이너 도구가 불리면 기록만 하고 97 로 끝난다(실제 도구를 부르지 않음)
for t in ssh scp sftp docker psql curl wget; do
  printf '#!/bin/sh\nprintf "%%s %%s\\n" "$0" "$*" >> "%s/TRIPWIRE-HIT.log"\nexit 97\n' "$RUN" > "$RUN/tripwire/$t"; chmod +x "$RUN/tripwire/$t"
done
BASEPATH="$RUN/tripwire:/usr/bin:/bin"
SR=${SYSTEMROOT:-'C:\Windows'}
PRE=$(env -i PATH="$BASEPATH" /usr/bin/bash --noprofile --norc -c 'for t in ssh docker psql curl; do printf "%s=%s\n" "$t" "$(command -v $t)"; done; printf "functions=%s\n" "$(declare -F | wc -l)"')
printf '%s\n' "PATH=$BASEPATH" "$PRE" > "$RUN/preflight.txt"
for t in ssh docker psql curl; do grep -qxF "$t=$RUN/tripwire/$t" "$RUN/preflight.txt" || { echo "🔴 PATH 선택 확인 실패($t) — 시험 시작 안 함"; exit 1; }; done

# 3) 가짜 식별(구동기가 시작 전 1회, 사례 기록과 분리)
FK="$RUN/kit-new/fake/fake-remote.sh"
IDR=$(env -i PATH="$BASEPATH" SYSTEMROOT="$SR" "$FK" --identify); IDRC=$?
printf 'path=%s\nsha256=%s\nresponse=%s\nrc=%s\n' "${FK#$B/}" "$(sha256sum < "$FK" | cut -d' ' -f1)" "$IDR" "$IDRC" > "$RUN/fake-identity.txt"
[ "$IDR" = "A1-FAKE-REMOTE v1 file-io-only 5f0c2a61" ] && [ "$IDRC" = 0 ] || { echo "🔴 가짜 식별 실패 — 시험 시작 안 함"; exit 1; }

# 4) 사례 실행
RES="$RUN/results.tsv"
printf 'case\tkit\tmode\texp_rc\trc\texp_status\tstatus\texp_calls\tcalls\tcall_kinds\texp_incomplete\tincomplete\texp_out\tout\tsql_stdin\ttrip\tverdict\n' > "$RES"
PASS=0; FAIL=0
trip_count() { [ -f "$RUN/TRIPWIRE-HIT.log" ] && wc -l < "$RUN/TRIPWIRE-HIT.log" || echo 0; }
# run_case 이름 키트 모드 exp_rc exp_status exp_calls exp_incomplete(y/n) exp_out(y/n) [출력폴더이름] [NOT_AFTER]
run_case() {
  local n=$1 kit=$2 mode=$3 erc=$4 est=$5 ecl=$6 einc=$7 eout=$8 ln=${9:-a1-read-20261124-1000} na=${10:-$NA_OK}
  local c="$RUN/cases/$n"; [ -d "$c/kit" ] || { mkdir -p "$c"; cp -r "$RUN/$kit" "$c/kit"; }
  local L="$c/$ln" path="$BASEPATH" fake="$c/kit/fake/fake-remote.sh" host=a1-test.invalid
  local -a extra=()
  case "$n" in   # 가드 사례 준비(키트 사본만 바꾼다)
    g01_fake_missing)      rm -f "$c/kit/fake/fake-remote.sh" ;;
    g02_fake_unset)        fake=__UNSET__ ;;
    g03_fake_relative)     fake="kit/fake/fake-remote.sh" ;;
    g04_fake_other_path)   mkdir -p "$c/other/fake"; cp -p "$c/kit/fake/fake-remote.sh" "$c/other/fake/"; fake="$c/other/fake/fake-remote.sh" ;;
    g05_fake_hash_mismatch) echo "# 변경" >> "$c/kit/fake/fake-remote.sh" ;;
    g06_win_path_backslash) path="$(cygpath -w "$RUN/tripwire"):$BASEPATH" ;;
    g07_win_path_drive)    path="$(cygpath -m "$RUN/tripwire"):$BASEPATH" ;;
    g08_path_empty_entry)  path="$BASEPATH:" ;;
    g09_prod_alias_host)   host="cat""-prod" ;;
    g10_other_host)        host="example.com" ;;
    g11_prod_alias_in_copy) printf '# %s\n' "cat""-prod" >> "$c/kit/approval/a1-read-test.sh" ;;
    g12_exported_function) extra=('BASH_FUNC_tee%%=() { /usr/bin/tee "$@"; }') ;;
    g13_bash_env)          : > "$c/empty.rc"; extra=("BASH_ENV=$c/empty.rc") ;;
  esac
  local t0; t0=$(trip_count)
  local -a envs=(PATH="$path" HOME="$c" SYSTEMROOT="$SR" A1_TEST_HOST="$host" A1_FAKE_MODE="$mode" A1_FAKE_LOG="$c/fake-calls.log" A1_FAKE_OUT="$L")
  [ "$fake" = __UNSET__ ] || envs+=(A1_FAKE_BIN="$fake")
  local tag=${ln##*-}
  (cd "$c" && env -i "${envs[@]}" "${extra[@]}" /usr/bin/bash --noprofile --norc "$c/kit/approval/a1-read-test.sh" "$L" "$na" \
      > "$c/stdout-$tag.txt" 2> "$c/stderr-$tag.txt"); local rc=$?
  local st=- calls=0 kinds=- inc=n out=n sq=-
  [ -e "$L" ] && out=y
  [ -f "$L/read-status.txt" ] && st=$(cut -d' ' -f1 "$L/read-status.txt")
  [ -e "$L/INCOMPLETE.txt" ] && inc=y
  if [ -f "$c/fake-calls.log" ]; then
    calls=$(wc -l < "$c/fake-calls.log"); kinds=$(sed 's/.*kind=\([a-z]*\).*/\1/' "$c/fake-calls.log" | paste -sd, -)
    local sn; sn=$(grep -n 'kind=sql' "$c/fake-calls.log" | head -n1 | cut -d: -f1)
    [ -n "$sn" ] && { [ "$(sha256sum < "$c/fake-calls.log.stdin.$sn" | cut -d' ' -f1)" = "$SQL_SHA" ] && sq=b84a89e7 || sq=MISMATCH; }
  fi
  local dt=$(( $(trip_count) - t0 ))
  local v=PASS
  [ "$rc" = "$erc" ] && [ "$st" = "$est" ] && [ "$calls" = "$ecl" ] && [ "$inc" = "$einc" ] && [ "$out" = "$eout" ] && [ "$dt" = 0 ] \
    && [ "$sq" != MISMATCH ] || v=FAIL
  [ $v = PASS ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$n/$tag" "$kit" "$mode" "$erc" "$rc" "$est" "$st" "$ecl" "$calls" "$kinds" "$einc" "$inc" "$eout" "$out" "$sq" "$dt" "$v" >> "$RES"
}
# 가드 거절: 모두 종료2·가짜 호출0·보존폴더 미생성·감지0
for g in g01_fake_missing g02_fake_unset g03_fake_relative g04_fake_other_path g05_fake_hash_mismatch g06_win_path_backslash \
         g07_win_path_drive g08_path_empty_entry g09_prod_alias_host g10_other_host g11_prod_alias_in_copy g12_exported_function g13_bash_env; do
  run_case "$g" kit-new ok 2 - 0 n n
done
# 합성 응답(새 초안 v2 시험 사본)
run_case s01_ok              kit-new ok              0   READ_OK                   2 n y
run_case s02_pinbad          kit-new pinbad          3   STOP_PIN_MISMATCH         1 n y
run_case s03_flywaybad       kit-new flywaybad       3   STOP_PIN_MISMATCH         2 n y
run_case s04_noguard         kit-new noguard         3   STOP_SQL_FAILED           2 n y
run_case s05_sql_stderr      kit-new sql_stderr      5   STOP_SQL_STDERR           2 n y
run_case s06_empty           kit-new empty           7   STOP_SQL_SHAPE            2 n y
run_case s07_sumbad          kit-new sumbad          7   STOP_SQL_SHAPE            2 n y
run_case s08_ssh255          kit-new ssh255          255 STOP_REMOTE_INSTALL_STATE 1 n y
run_case s09_install_stderr  kit-new install_stderr  5   STOP_INSTALL_STDERR       1 n y
run_case s10_install_recfail kit-new install_recfail 4   -                         1 y y
run_case s11_rerun           kit-new ok              0   READ_OK                   2 n y a1-read-20261124-1000
run_case s11_rerun           kit-new ok              2   -                         2 n n a1-read-20261124-1001
run_case s12_late            kit-new ok              2   -                         0 n n a1-read-20261124-1000 "$NA_LATE"
# 구 초안(15e3e412) 시험 사본: 설치 상태 표준오류가 있어도 SQL 호출로 넘어가는 결함 재현(기대=READ_OK·호출2)
run_case o01_old_install_stderr kit-old install_stderr 0 READ_OK 2 n y

echo "pc-end $(date -u +%FT%TZ)" >> "$RUN/run-time.txt"
TH=$(trip_count)
printf 'PASS=%s FAIL=%s TRIPWIRE_HITS=%s\n' "$PASS" "$FAIL" "$TH" | tee "$RUN/summary.txt"
(cd "$RUN" && find . -type f ! -name SHA256SUMS -print | sed 's|^\./||' | LC_ALL=C sort | xargs -d '\n' sha256sum > SHA256SUMS)
[ "$FAIL" = 0 ] && [ "$TH" = 0 ]
