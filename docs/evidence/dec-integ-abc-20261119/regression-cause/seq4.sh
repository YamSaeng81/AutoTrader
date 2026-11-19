#!/usr/bin/env bash
set -u; R=/d/tmp/abc-ev/runs; unset SR_PG_URL OB_PG_URL RACE_PG_URL
dir=/d/tmp/sma20fx-60df3437; out=$R/sma20-fx-$(cd $dir && git rev-parse --short=8 HEAD); [ -e "$out" ] && { echo 있음; exit 1; }; mkdir -p $out
( cd $dir && { echo "HEAD $(git rev-parse HEAD)"; git status --short; } > $out/head.txt
  COSTB_OUT=$out ./gradlew :web-api:cleanTest :web-api:test --tests '*DailySma20LiveOrderPathTest' > $out/gradle.log 2>&1; echo "rc=$?" > $out/rc.txt )
mkdir -p $out/xml; cp $dir/web-api/build/test-results/test/TEST-*.xml $out/xml/; echo "$out $(cat $out/rc.txt)"
