#!/usr/bin/env bash
# 회귀 뒤 최소 영향 확인 + N3 구판 + 매수 요약 기점 실패 (순차, 출력 덮어쓰기 금지)
set -u
R=/d/tmp/abc-ev/runs
unset RACE_PG_URL SR_PG_URL CR_PG_URL LW_PG_URL PP_PG_URL PH_PG_URL RL_PG_URL OB_PG_URL
run() { # name dir gradle-filter [env...]
  local name=$1 dir=$2 filt=$3; shift 3
  local out=$R/$name; [ -e "$out" ] && { echo "있음 $out"; return; }; mkdir -p "$out"
  ( cd "$dir" && { echo "HEAD $(git rev-parse HEAD)"; git status --short; } > "$out/head.txt"
    env "$@" COSTB_OUT="$out" ./gradlew :web-api:cleanTest :web-api:test --tests "$filt" > "$out/gradle.log" 2>&1; echo "rc=$?" > "$out/rc.txt" )
  mkdir -p "$out/xml"; cp "$dir"/web-api/build/test-results/test/TEST-*.xml "$out/xml/" 2>/dev/null
  echo "$name $(cat $out/rc.txt) xml=$(ls $out/xml | wc -l)"
}
pg() { docker exec cat-rt-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS $1" -c "CREATE DATABASE $1" >/dev/null; }
run sma20-A-aff357e2 /d/tmp/mcab-0e1d2a02 '*DailySma20LiveOrderPathTest'
run sma20-C-b408a223 /d/tmp/costbasis-0e1d2a02 '*DailySma20LiveOrderPathTest'
pg n3old_0e1d2a02; run n3old-0e1d2a02 /d/tmp/n3old-0e1d2a02 '*OldBinaryNewEnumPgTest' OB_PG_URL=jdbc:postgresql://127.0.0.1:15432/n3old_0e1d2a02
pg n3old_b5cd1d0d; run n3old-b5cd1d0d /d/tmp/n3old-b5cd1d0d '*OldBinaryNewEnumPgTest' OB_PG_URL=jdbc:postgresql://127.0.0.1:15432/n3old_b5cd1d0d
pg rbs_base; run rbs-base-9a297069 /d/tmp/rbs-60df3437 '*RealBuySummaryPgTest' SR_PG_URL=jdbc:postgresql://127.0.0.1:15432/rbs_base
