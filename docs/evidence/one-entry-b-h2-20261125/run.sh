#!/usr/bin/env bash
# v1.242 §3-2 — B 후보 c43e1f1a에서 H2 예약 시험 2클래스만 1회(CapitalPoolLimitTest·ConcurrentEntryCapTest). PG·jar·전체 회귀 없음.
# 사용: run.sh <회차 이름>  결과 = 이 폴더/runs/<회차>. 이미 있으면 거절.
set -u
dir=/d/tmp/one-entry-d81c3f2f; want=c43e1f1af1daab9c23e737101ed297826a70e4a5
ev="$(cd "$(dirname "$0")" && pwd)"; R=$ev/runs/${1:?회차}
[ -e "$R" ] && { echo "있음 $R"; exit 1; }
[ "$(git -C $dir rev-parse HEAD)" = "$want" ] || { echo "HEAD 불일치"; exit 1; }
[ -z "$(git -C $dir status --porcelain)" ] || { echo "작업 트리 변경 있음"; exit 1; }
mkdir -p "$R"
unset SR_PG_URL OB_PG_URL RACE_PG_URL CR_PG_URL LW_PG_URL PP_PG_URL PH_PG_URL RL_PG_URL AB_CHAIN_PG_URL N15_PG_URL N15_PG_USER N15_PG_PASSWORD V96_PG_URL N13_PG_URL OE_PG_URL
echo "HEAD $(git -C $dir rev-parse HEAD)" > "$R/head.txt"; date -Iseconds >> "$R/head.txt"
( cd $dir && ./gradlew --offline :web-api:cleanTest :web-api:test --tests '*.CapitalPoolLimitTest' --tests '*.ConcurrentEntryCapTest' > "$R/gradle.log" 2>&1; echo "rc=$?" > "$R/rc.txt" )
mkdir -p "$R/xml"; cp $dir/web-api/build/test-results/test/TEST-*.xml "$R/xml/" 2>/dev/null
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
cat "$R/rc.txt"; cat "$R/summary.txt"
