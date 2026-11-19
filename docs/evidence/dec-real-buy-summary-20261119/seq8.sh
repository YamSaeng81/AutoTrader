#!/usr/bin/env bash
# 영향 2차: AbcCross(도우미 보정 596ed4b0) cat-rt-pg 새 DB + 비밀번호 기본값 없는 4클래스는 종전처럼 v152-pg(15437) 새 DB
set -u; until [ -f /d/tmp/abc-ev/runs/sqlpar3-60df3437/rc.txt ]; do sleep 5; done
R=/d/tmp/abc-ev/runs/rbs-impact2-$(cd /d/tmp/rbs-60df3437 && git rev-parse --short=8 HEAD); [ -e "$R" ] && { echo 있음; exit 1; }; mkdir -p $R
unset SR_PG_URL OB_PG_URL RACE_PG_URL CR_PG_URL LW_PG_URL PP_PG_URL PH_PG_URL RL_PG_URL AB_CHAIN_PG_URL
dir=/d/tmp/rbs-60df3437; ( cd $dir && { echo "HEAD $(git rev-parse HEAD)"; git status --short; } > $R/head.txt )
one() { local name=$1; shift; local out=$R/$name; mkdir -p $out/xml
  ( cd $dir && env "$@" COSTB_OUT=$out ./gradlew :web-api:cleanTest :web-api:test $FILT > $out/gradle.log 2>&1; echo "rc=$?" > $out/rc.txt )
  cp $dir/web-api/build/test-results/test/TEST-*.xml $out/xml/ 2>/dev/null; echo "$name $(cat $out/rc.txt) xml=$(ls $out/xml | wc -l)"; }
docker exec cat-rt-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS rbsi2_abccross" -c "CREATE DATABASE rbsi2_abccross" >/dev/null 2>&1
FILT="--tests *AbcCrossPgTest"; one pg-AbcCrossPgTest SR_PG_URL=jdbc:postgresql://127.0.0.1:15432/rbsi2_abccross
for pair in ClosingIntentAtomicityPgTest:CR LiveLossWindowPgTest:LW PartialSellRealizedPnlPgTest:PP RealLossThreeFormulaPgTest:RL; do
  c=${pair%%:*}; e=${pair##*:}; db=rbsi2_$(echo $c | tr 'A-Z' 'a-z' | cut -c1-24)
  docker exec v152-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db" >/dev/null 2>&1
  FILT="--tests *$c"; one pg-$c ${e}_PG_URL=jdbc:postgresql://127.0.0.1:15437/$db
done
