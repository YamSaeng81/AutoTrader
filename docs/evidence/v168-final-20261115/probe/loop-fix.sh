#!/usr/bin/env bash
# 비정시 자료 9개 시험의 정시 경계 탐침(총괄 v1.168 §3-3): 매시 :50~:02 KST 창에서 같은 9클래스를 실제 벽시계로 반복 실행한다.
# 보완 사본 탐침(21:08): 작업 사본 /d/tmp/probe-fix(0e1d2a02(22:25부터, 그 전 ac1ef49d) + 시각 기록 확장, 미커밋), 클래스는 classes-fix.txt. 출력 runs/f-*, 색인 index-fix.txt
set -u
END=${END:-"2026-11-16 08:05"}
endts=$(date -d "$END" +%s)
cd /d/tmp/probe-fix || exit 3
unset RACE_PG_URL SR_PG_URL N15_PG_URL DRAIN_GAP_GATE_MS TEST_NET_TRACE
while [ "$(date +%s)" -lt "$endts" ]; do
  m=$((10#$(date +%M)))
  if [ "$m" -ge 50 ] || [ "$m" -le 2 ] || [ "${FORCE:-0}" = 1 ]; then
    CLASSES=$(cat /d/tmp/v168-ev/probe/classes-fix.txt); args=(); for c in $CLASSES; do args+=(--tests "*.$c"); done
    out=/d/tmp/v168-ev/probe/runs/f-$(date +%m%d-%H%M%S); mkdir -p "$out"
    { echo "HEAD $(git rev-parse HEAD)"; git status --short; echo "worktree_diff_sha256 $(git diff | sha256sum | cut -c1-64)"; echo "classes $CLASSES"; echo "start $(date '+%F %T %Z')"; } > "$out/meta.txt"
    rm -rf web-api/build/test-results/test web-api/build/test-results/probe-times.tsv
    ./gradlew :web-api:test --offline --continue "${args[@]}" > "$out/gradle.log" 2>&1; rc=$?
    echo "end $(date '+%F %T %Z') rc=$rc" >> "$out/meta.txt"
    cp -r web-api/build/test-results/test "$out/xml" 2>/dev/null; cp web-api/build/test-results/probe-times.tsv "$out/" 2>/dev/null
    echo "$(date '+%T') $out rc=$rc cases=$(wc -l < "$out/probe-times.tsv" 2>/dev/null) fail=$(grep -c $'\tFAIL' "$out/probe-times.tsv" 2>/dev/null)" >> /d/tmp/v168-ev/probe/index-fix.txt
    [ "${FORCE:-0}" = 1 ] && exit 0
  else
    sleep 20
  fi
done
