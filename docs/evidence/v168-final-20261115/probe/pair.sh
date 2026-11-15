#!/usr/bin/env bash
# 회차 키 충돌 재현(총괄 v1.168 §3-3): 보완 전 탐침 사본에서 B01RealNextSellBarTest → BtcGateUnknownEntryTest 를 N회 반복(시각 창 무관).
set -u; N=${1:-12}; cd /d/tmp/probe-wt || exit 3
for i in $(seq 1 $N); do
  out=/d/tmp/v168-ev/probe/runs/k-$(date +%m%d-%H%M%S); mkdir -p "$out"
  { echo "HEAD $(git rev-parse HEAD)"; git status --short; echo "worktree_diff_sha256 $(git diff | sha256sum | cut -c1-64)"; echo "start $(date '+%F %T %Z')"; } > "$out/meta.txt"
  rm -rf web-api/build/test-results/test web-api/build/test-results/probe-times.tsv
  ./gradlew :web-api:test --offline --continue --tests "*.B01RealNextSellBarTest" --tests "*.BtcGateUnknownEntryTest" > "$out/gradle.log" 2>&1; rc=$?
  echo "end $(date '+%F %T %Z') rc=$rc" >> "$out/meta.txt"
  cp -r web-api/build/test-results/test "$out/xml" 2>/dev/null; cp web-api/build/test-results/probe-times.tsv "$out/" 2>/dev/null
  echo "$(date '+%T') $out rc=$rc fail=$(grep -c $'\tFAIL' "$out/probe-times.tsv" 2>/dev/null)" >> /d/tmp/v168-ev/probe/index-pair.txt
done
