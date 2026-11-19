#!/usr/bin/env bash
set -u; until [ -f /d/tmp/abc-ev/runs/sma20-fx-09fa2d1c/rc.txt ]; do sleep 5; done
R=/d/tmp/abc-ev/runs; unset SR_PG_URL LW_PG_URL
dir=/d/tmp/integ-abc; out=$R/lw-base-60df3437; [ -e "$out" ] && { echo 있음; exit 1; }; mkdir -p $out/xml
docker exec v152-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS lwbase_60df" -c "CREATE DATABASE lwbase_60df" >/dev/null 2>&1
( cd $dir && { echo "HEAD $(git rev-parse HEAD)"; git status --short; } > $out/head.txt
  LW_PG_URL=jdbc:postgresql://127.0.0.1:15437/lwbase_60df ./gradlew :web-api:cleanTest :web-api:test --tests '*LiveLossWindowPgTest' > $out/gradle.log 2>&1; echo "rc=$?" > $out/rc.txt )
cp $dir/web-api/build/test-results/test/TEST-*.xml $out/xml/; echo "$out $(cat $out/rc.txt)"
