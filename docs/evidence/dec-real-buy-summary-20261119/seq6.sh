#!/usr/bin/env bash
# 매수 요약 보완(69c81c3d) 통합 영향 — 직접 호출 H2 8클래스 1회 + 매수 체결 경로 PG 9클래스 각각 새 DB. 전체 회귀 아님
set -u; R=/d/tmp/abc-ev/runs/rbs-impact-69c81c3d; [ -e "$R" ] && { echo 있음; exit 1; }; mkdir -p $R
unset SR_PG_URL OB_PG_URL RACE_PG_URL CR_PG_URL LW_PG_URL PP_PG_URL PH_PG_URL RL_PG_URL AB_CHAIN_PG_URL
dir=/d/tmp/rbs-60df3437; ( cd $dir && { echo "HEAD $(git rev-parse HEAD)"; git status --short; } > $R/head.txt )
one() { local name=$1; shift; local out=$R/$name; mkdir -p $out/xml
  ( cd $dir && env "$@" COSTB_OUT=$out ./gradlew :web-api:cleanTest :web-api:test $FILT > $out/gradle.log 2>&1; echo "rc=$?" > $out/rc.txt )
  cp $dir/web-api/build/test-results/test/TEST-*.xml $out/xml/ 2>/dev/null; echo "$name $(cat $out/rc.txt) xml=$(ls $out/xml | wc -l)"; }
FILT=""; for c in AlignedV147FixesTest AlignedV148FixesTest BuySettlementEdgeTest EngineParityTest PositionLimitTest TelegramMinimalAlertTest TelegramTestIsolationTest SubmitPollRaceTest; do FILT="$FILT --tests *$c"; done
one h2-8
for pair in AbcCrossPgTest:SR SettlementRoundingPgTest:SR CostBasisBoundaryPgTest:SR CostBasisFollowupPgTest:SR CostBasisRiskPgTest:SR ClosingIntentAtomicityPgTest:CR LiveLossWindowPgTest:LW PartialSellRealizedPnlPgTest:PP RealLossThreeFormulaPgTest:RL; do
  c=${pair%%:*}; e=${pair##*:}; db=rbsi_$(echo $c | tr 'A-Z' 'a-z' | cut -c1-24)
  docker exec cat-rt-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db" >/dev/null 2>&1
  FILT="--tests *$c"; one pg-$c ${e}_PG_URL=jdbc:postgresql://127.0.0.1:15432/$db
done
