#!/usr/bin/env bash
set -u; R=/d/tmp/abc-ev/runs; unset SR_PG_URL OB_PG_URL RACE_PG_URL
out=$R/sqlpar3-60df3437; [ -e "$out" ] && { echo 있음; exit 1; }; mkdir -p $out
docker exec cat-rt-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS sqlpar3" -c "CREATE DATABASE sqlpar3" >/dev/null
( cd /d/tmp/sqlpar-60df3437 && { echo "HEAD $(git rev-parse HEAD)"; git status --short; } > $out/head.txt
  SR_PG_URL=jdbc:postgresql://127.0.0.1:15432/sqlpar3 PRE_SQL="/d/Claude Code/projects/crypto-auto-trader/docs/evidence/dec-costbasis-followup-20261119/predeploy-v3/predeploy_read.v3.sql" COSTB_OUT=$out \
    ./gradlew :web-api:cleanTest :web-api:test --tests '*PredeploySqlParityPgTest' > $out/gradle.log 2>&1; echo "rc=$?" > $out/rc.txt )
mkdir -p $out/xml; cp /d/tmp/sqlpar-60df3437/web-api/build/test-results/test/TEST-*.xml $out/xml/ 2>/dev/null; echo "$out $(cat $out/rc.txt)"

