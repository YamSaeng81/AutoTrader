#!/usr/bin/env bash
# v1.196 §2-3·§3-2 — 최종 SHA 직접 영향 확인(클래스마다 새 DB). 기존 출력 덮어쓰기 금지.
set -u
dir=/d/tmp/final-abc; sha=$(cd $dir && git rev-parse --short=8 HEAD); R=/d/tmp/abc-ev/final/direct-$sha
[ -e "$R" ] && { echo "있음 $R"; exit 1; }; mkdir -p $R
unset SR_PG_URL OB_PG_URL RACE_PG_URL CR_PG_URL LW_PG_URL PP_PG_URL PH_PG_URL RL_PG_URL AB_CHAIN_PG_URL N15_PG_URL N15_PG_USER N15_PG_PASSWORD V96_PG_URL N13_PG_URL
( cd $dir && { echo "HEAD $(git rev-parse HEAD)"; git status --short; } > $R/head.txt )
one() { local name=$1; shift; local out=$R/$name; mkdir -p $out/xml
  ( cd $dir && env "$@" ./gradlew :web-api:cleanTest :web-api:test $FILT > $out/gradle.log 2>&1; echo "rc=$?" > $out/rc.txt )
  cp $dir/web-api/build/test-results/test/TEST-*.xml $out/xml/ 2>/dev/null; echo "$name $(cat $out/rc.txt) xml=$(ls $out/xml | wc -l)"; }
FILT="--tests *DailySma20LiveOrderPathTest"; one h2-DailySma20LiveOrderPathTest X=1
docker exec cat-rt-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS fin_rbs" -c "CREATE DATABASE fin_rbs" >/dev/null 2>&1
FILT="--tests *RealBuySummaryPgTest"; one pg-RealBuySummaryPgTest SR_PG_URL=jdbc:postgresql://127.0.0.1:15432/fin_rbs
for pair in RealLossGlobalPgTest:RL PhantomKeepsConfirmedPnlPgTest:PH ClosingIntentRollbackPgTest:CR N15PgLockWaitExpiryTest:N15; do
  c=${pair%%:*}; e=${pair##*:}; db=fin_$(echo $c | tr 'A-Z' 'a-z' | cut -c1-24)
  docker exec v152-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db" >/dev/null 2>&1
  FILT="--tests *$c"; one pg-$c ${e}_PG_URL=jdbc:postgresql://127.0.0.1:15437/$db
done
python -I - "$R" > $R/summary.txt <<'PY'
import sys, glob, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding="utf-8")
for d in sorted(glob.glob(sys.argv[1] + "/*/")):
    for f in sorted(glob.glob(d + "xml/TEST-*.xml")):
        r = ET.parse(f).getroot()
        print(d.rstrip("/").split("/")[-1], r.get("name").split(".")[-1], "tests", r.get("tests"), "fail", r.get("failures"), "err", r.get("errors"), "skip", r.get("skipped"))
        for tc in r.iter("testcase"):
            for x in tc:
                if x.tag in ("failure", "error"): print("  FAIL", tc.get("name"), (x.get("message") or "")[:300])
PY
cat $R/summary.txt
