#!/usr/bin/env bash
# v1.241 §3 — 1회 제한 B안 직접 시험: 순수 LiveOneEntryParamsTest(H2 없음) + 격리 PG LiveOneEntryLimitPgTest(회차마다 새 DB). 기존 출력 덮어쓰기 금지.
# 사용: run.sh <회차 이름>   (작업 사본 /d/tmp/one-entry-d81c3f2f 의 현재 HEAD·작업 트리로 돈다. 결과 = 이 폴더/runs/<회차>)
set -u
dir=/d/tmp/one-entry-d81c3f2f; ev="$(cd "$(dirname "$0")" && pwd)"; R=$ev/runs/${1:?회차}
[ -e "$R" ] && { echo "있음 $R"; exit 1; }; mkdir -p "$R"
unset SR_PG_URL OB_PG_URL RACE_PG_URL CR_PG_URL LW_PG_URL PP_PG_URL PH_PG_URL RL_PG_URL AB_CHAIN_PG_URL N15_PG_URL N15_PG_USER N15_PG_PASSWORD V96_PG_URL N13_PG_URL OE_PG_URL
( cd $dir && { echo "HEAD $(git rev-parse HEAD)"; git status --short; git diff --stat; } > "$R/head.txt"; git -C $dir diff > "$R/worktree.diff" )
db=oe_$(echo "$1" | tr 'A-Z-' 'a-z_' | cut -c1-40)
docker exec one-entry-pg psql -q -U trader -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db" > "$R/db.txt" 2>&1; echo "db=$db rc=$?" >> "$R/db.txt"
docker exec one-entry-pg psql -At -U trader -d postgres -c "select version()" >> "$R/db.txt" 2>&1
( cd $dir && env OE_PG_URL=jdbc:postgresql://127.0.0.1:15441/$db ./gradlew --offline :web-api:cleanTest :web-api:test --tests '*LiveOneEntryParamsTest' --tests '*LiveOneEntryLimitPgTest' > "$R/gradle.log" 2>&1; echo "rc=$?" > "$R/rc.txt" )
mkdir -p "$R/xml"; cp $dir/web-api/build/test-results/test/TEST-*.xml "$R/xml/" 2>/dev/null
grep -h '\[OE' "$R"/xml/*.xml > "$R/marks.txt" 2>/dev/null
python -I - "$R" > "$R/summary.txt" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding="utf-8")
for f in sorted(glob.glob(sys.argv[1] + "/xml/TEST-*.xml")):
    r = ET.parse(f).getroot()
    print(r.get("name").split(".")[-1], "tests", r.get("tests"), "fail", r.get("failures"), "err", r.get("errors"), "skip", r.get("skipped"))
    for tc in r.iter("testcase"):
        bad = [x for x in tc if x.tag in ("failure", "error")]
        sk = [x for x in tc if x.tag == "skipped"]
        print("  %s %s" % ("FAIL" if bad else ("SKIP" if sk else "ok  "), tc.get("name")), ("— " + (bad[0].get("message") or "")[:400].replace("\n", " ")) if bad else "")
PY
echo "$(cat "$R/rc.txt")"; cat "$R/summary.txt"
