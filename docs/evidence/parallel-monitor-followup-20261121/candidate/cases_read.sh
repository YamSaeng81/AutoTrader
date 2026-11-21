#!/usr/bin/env bash
# pm-read-2 가짜 SSH 시험 5경계 8변형(총괄 v1.216 §3-2). 실제 네트워크·서버·DB 없음. 서버에서 실행하지 않는다.
#   bash cases_read.sh <고유 회차명>     결과: ../runs/<회차명>/ (이미 있으면 멈춤)
# 경계: 정상1 · HEAD/이미지/Flyway 불일치3 · SQL 실패/SSH 실패2 · 로그 조회 실패1 · 마지막 필수 기록 실패1
# 고정 입력: 상태 출력 = 이 폴더 pg-run1 G1b 의 실제 격리 PG psql 출력(pm-state-3) · 로그 = p0-run1 Q1 합성 backend.log
#            N03 값 = 이 회차 폴더의 합성 N03 원문 표지(운영 N03 아님)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; FU="$(dirname "$HERE")"; EVI="$(dirname "$FU")"
RUN="$FU/runs/${1:?회차명}"
[ ! -e "$RUN" ] || { echo "회차 폴더가 이미 있음"; exit 2; }
mkdir -p "$RUN/fake-bin"
cp "$HERE/fake_ssh.sh" "$RUN/fake-bin/ssh" && chmod +x "$RUN/fake-bin/ssh"
export PATH="$RUN/fake-bin:$PATH"
W=$(command -v ssh)
if [ "$W" != "$RUN/fake-bin/ssh" ]; then echo "가짜 ssh 가 실제 ssh 보다 앞이 아님($W) — 시험 시작 안 함" | tee "$RUN/ABORTED.txt"; exit 2; fi
echo "ssh 경로 확인: $W (실제 ssh 보다 앞)" > "$RUN/out.txt"
RUNNER="$FU/approval/pm-read-2.sh"
TSV="$FU/runs/pg-run1/G1b_matching_paper_mixed_real/in/state.tsv"
LOGSRC="$EVI/parallel-monitor-check-20261121/runs/p0-run1/Q1_valid_no_p0/in/backend.log"
for f in "$RUNNER" "$TSV" "$LOGSRC" "$HERE/fake_ssh.sh"; do echo "$(sha256sum < "$f" | cut -d' ' -f1)  ${f#$EVI/}" >> "$RUN/inputs-sha256.txt"; done
SQLSHA=$(sha256sum < "$FU/sql/state-3.sql" | cut -d' ' -f1)
printf '합성 N03 원문 표지(pm-read-2 가짜 SSH 시험) — 운영 N03 아님\n' > "$RUN/n03-synthetic.txt"
NSHA=$(sha256sum < "$RUN/n03-synthetic.txt" | cut -d' ' -f1)
NREL="parallel-monitor-followup-20261121/runs/$1/n03-synthetic.txt"
printf '{"source_file": "%s", "source_sha256": "%s", "error_logs": [], "paper_522_587": {"total": 66, "running": 63, "not_running": 3}, "observed_window": "합성 — 관측 구간 없음"}\n' "$NREL" "$NSHA" > "$RUN/n03-values.json"
SINCE=2026-11-22T13:00:00Z
PASS=0; TOTAL=0
# 이름|시나리오|read 종료|원격 호출 종류|read-status 첫 단어(없음=-)|미완료 표지 남음|post 종료
CASES='V1_normal|normal|0|install,sql,until,logs|READ_OK|no|0
V2_head_mismatch|head_mismatch|3|install|STOP_PIN_MISMATCH|no|2
V3_image_mismatch|image_mismatch|3|install|STOP_PIN_MISMATCH|no|2
V4_flyway_mismatch|flyway_mismatch|3|install,sql|STOP_PIN_MISMATCH|no|2
V5_sql_failed|sql_fail|1|install,sql|STOP_SQL_FAILED|no|2
V6_ssh_failed|ssh_fail|255|install|STOP_REMOTE_INSTALL_STATE|no|2
V7_log_failed|log_fail|6|install,sql,until,logs|READ_LOG_FAILED|no|20
V8_last_write_failed|last_write_fail|4|install,sql,until,logs|-|yes|2'
while IFS='|' read -r name scen xr xk xs xi xp; do
  D="$RUN/$name"; mkdir "$D"; L="$D/pm-read-20261122-2225"
  export FAKE_SCEN=$scen FAKE_DIR=$D FAKE_TSV=$TSV FAKE_LOGSRC=$LOGSRC FAKE_L=$L
  bash "$RUNNER" read "$L" "$SINCE" > "$D/read.out" 2> "$D/read.err"; rr=$?
  pr=none
  bash "$RUNNER" post "$L" "$RUN/n03-values.json" > "$D/post.out" 2> "$D/post.err"; pr=$?
  kinds=$(cut -f2 "$D/calls.txt" 2>/dev/null | paste -sd, -)
  st=$( [ -f "$L/read-status.txt" ] && cut -d' ' -f1 "$L/read-status.txt" || echo -)
  inc=$( [ -e "$L/INCOMPLETE.txt" ] && echo yes || echo no )
  bad=""
  [ "$rr" = "$xr" ] || bad="$bad read_rc=$rr"
  [ "$kinds" = "$xk" ] || bad="$bad calls=$kinds"
  [ "$st" = "$xs" ] || bad="$bad status=$st"
  [ "$inc" = "$xi" ] || bad="$bad incomplete=$inc"
  [ "$pr" = "$xp" ] || bad="$bad post_rc=$pr"
  if [ "$name" = V8_last_write_failed ]; then grep -q "Is a directory" "$D/read.err" || bad="$bad no_write_error"; else [ -s "$D/read.err" ] && bad="$bad read_stderr"; fi
  if [ "$xp" = 2 ]; then [ ! -e "$L/post" ] || bad="$bad post_dir_exists"; grep -q '거절(종료 2)' "$D/post.out" || bad="$bad post_not_denied"; fi
  if [ "$xr" != 0 ] && [ "$xr" != 6 ]; then ! grep -q '^read 끝 — READ_OK' "$D/read.out" || bad="$bad success_msg"; fi
  if [ "$name" = V8_last_write_failed ]; then grep -q '기록 실패' "$D/read.out" || bad="$bad no_record_fail_msg"; ! grep -q '^read 끝' "$D/read.out" || bad="$bad finish_msg"; fi
  if [ "$name" = V1_normal ] || [ "$name" = V7_log_failed ]; then
    sqlc=$(awk -F'\t' '$2=="sql"{print $3}' "$D/calls.txt")
    case "$sqlc" in *"exec -T -e PGOPTIONS='-c statement_timeout=15s -c lock_timeout=2s' db psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f -") ;; *) bad="$bad sql_cmd";; esac
    n=$(awk -F'\t' '$2=="sql"{print $1}' "$D/calls.txt"); [ "$(sha256sum < "$D/stdin.$n" | cut -d' ' -f1)" = "$SQLSHA" ] || bad="$bad sql_stdin"
    lc=$(awk -F'\t' '$2=="logs"{print $3}' "$D/calls.txt")
    case "$lc" in *"logs --no-color --since $SINCE --until 2026-11-22T13:25:05Z backend") ;; *) bad="$bad logs_cmd";; esac
    [ "$(cat "$L/.gitignore")" = "local-only/" ] || bad="$bad gitignore"
    grep -q 'backend.log' "$L/read-SHA256SUMS" && bad="$bad raw_log_in_public_sums"
    grep -qE ' [*]?backend.log$' "$L/local-only-SHA256SUMS" || bad="$bad local_only_sums"
    [ -f "$L/post-status.txt" ] && [ ! -e "$L/post-INCOMPLETE.txt" ] || bad="$bad post_records"
    p0=$(python -I -X utf8 -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['status'])" "$L/post/p0/p0.json" 2>/dev/null)
    cv=$(python -I -X utf8 -c "import json,sys;r=json.load(open(sys.argv[1],encoding='utf-8'));print(r['verdict'],','.join(sorted({f['code'] for f in r.get('collection_failures',[])})))" "$L/post/conv/result.json" 2>/dev/null)
    if [ "$name" = V1_normal ]; then
      [ "$p0" = P0_ABSENT_IN_PROVIDED_LOG ] || bad="$bad p0=$p0"; [ "$cv" = "CONVERTED " ] || bad="$bad conv=$cv"
      ru=$(python -I -X utf8 -c "import json,sys;r=json.load(open(sys.argv[1],encoding='utf-8'));print(r['since'],r['until'],r['axis'])" "$L/post/range.json" 2>/dev/null)
      [ "$ru" = "$SINCE 2026-11-22T13:25:05Z server_utc" ] || bad="$bad range=$ru"
    else
      [ "$p0" = P0_CHECK_INCOMPLETE ] || bad="$bad p0=$p0"; [ "$cv" = "COLLECTION_NOT_PASSED AUX_P0_CHECK_INCOMPLETE" ] || bad="$bad conv=$cv"
    fi
    echo "$name P0=$p0 변환=$cv" >> "$RUN/post-summary.txt"
  fi
  TOTAL=$((TOTAL+1))
  if [ -z "$bad" ]; then PASS=$((PASS+1)); r=PASS; else r=FAIL; fi
  echo "$r $name read=$rr 호출=[$kinds] 상태=$st 미완료표지=$inc post=$pr${bad:+ 불일치:$bad}" | tee -a "$RUN/out.txt"
done <<< "$CASES"
echo "$PASS/$TOTAL 기대대로 (가짜 SSH 5경계 8변형 — 실제 서버·DB·로그 아님. post 의 synthetic=false 표지는 실행안이 실제 읽기용이기 때문이며 이 결과는 합성이다)" | tee -a "$RUN/out.txt"
[ "$PASS" = "$TOTAL" ]
