#!/bin/bash
# 최종 SHA 실제 PG — 클래스마다 새 DB, 실행 전 결과 폴더 비움
W=/d/tmp/int155; O=/d/tmp/v153-runs/v156/final-pg; mkdir -p $O; cd $W
run() { c=$1; v=$2; db=p156_$(echo $c | tr 'A-Z' 'a-z' | cut -c1-20)_$RANDOM
  docker exec v152-pg psql -U trader -d postgres -qc "CREATE DATABASE $db" || { echo "$c DB생성실패"; return; }
  rm -rf web-api/build/test-results/test
  env $v=jdbc:postgresql://127.0.0.1:15437/$db ./gradlew :web-api:test --rerun --console=plain --tests "*.$c" > $O/$c.log 2>&1
  rc=$?; mkdir -p $O/$c-xml; cp web-api/build/test-results/test/*.xml $O/$c-xml/
  echo "$c db=$db rc=$rc $(grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $O/$c-xml/*.xml | head -1)"; }
echo "start $(date '+%T') $(git rev-parse --short HEAD)"
for p in AlignedV96PgBehaviorTest:V96_PG_URL BalanceListSnapshotPgTest:V96_PG_URL PerformanceSnapshotS5PgTest:V96_PG_URL StartedAtPrecisionPgTest:V96_PG_URL LiveLossWindowPgTest:LW_PG_URL N15PgLockWaitExpiryTest:N15_PG_URL SettlementRoundingPgTest:SR_PG_URL PartialSellRealizedPnlPgTest:PP_PG_URL; do run ${p%%:*} ${p##*:}; done
echo "end $(date '+%T')"
