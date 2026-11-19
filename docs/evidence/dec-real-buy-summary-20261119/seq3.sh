#!/usr/bin/env bash
set -u
R=/d/tmp/abc-ev/runs; unset SR_PG_URL OB_PG_URL
out=$R/rbs-fix2-$(cd /d/tmp/rbs-60df3437 && git rev-parse --short=8 HEAD)-dirty; [ -e "$out" ] && { echo 있음; exit 1; }; mkdir -p $out
docker exec cat-rt-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS rbs_fix2" -c "CREATE DATABASE rbs_fix2" >/dev/null
( cd /d/tmp/rbs-60df3437 && { echo "HEAD $(git rev-parse HEAD)"; git status --short; } > $out/head.txt
  SR_PG_URL=jdbc:postgresql://127.0.0.1:15432/rbs_fix2 COSTB_OUT=$out ./gradlew :web-api:cleanTest :web-api:test --tests '*RealBuySummaryPgTest' > $out/gradle.log 2>&1; echo "rc=$?" > $out/rc.txt )
mkdir -p $out/xml; cp /d/tmp/rbs-60df3437/web-api/build/test-results/test/TEST-*.xml $out/xml/; echo "$out $(cat $out/rc.txt)"
# 사전 SQL v3 대조 (60df3437 + 미추적 대조 시험 1개)
out=$R/sqlpar-60df3437; [ -e "$out" ] && { echo 있음; exit 1; }; mkdir -p $out
docker exec cat-rt-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS sqlpar1" -c "CREATE DATABASE sqlpar1" >/dev/null
( cd /d/tmp/sqlpar-60df3437 && { echo "HEAD $(git rev-parse HEAD)"; git status --short; } > $out/head.txt
  SR_PG_URL=jdbc:postgresql://127.0.0.1:15432/sqlpar1 PRE_SQL="/d/Claude Code/projects/crypto-auto-trader/docs/evidence/dec-costbasis-followup-20261119/predeploy-v3/predeploy_read.v3.sql" COSTB_OUT=$out \
    ./gradlew :web-api:cleanTest :web-api:test --tests '*PredeploySqlParityPgTest' > $out/gradle.log 2>&1; echo "rc=$?" > $out/rc.txt )
mkdir -p $out/xml; cp /d/tmp/sqlpar-60df3437/web-api/build/test-results/test/TEST-*.xml $out/xml/ 2>/dev/null; echo "$out $(cat $out/rc.txt)"
