#!/usr/bin/env bash
# v1.243 §3-2 — 격리 PG 새 1묶음: B 실제 LIVE 경로 + 게이트 v3 합성. 운영·거래소 접속 없음(거래소 응답 대역).
# 사용: run.sh <회차>  결과 = 이 폴더/runs/<회차>. 이미 있으면 거절. 회차마다 새 DB.
set -u
dir=/d/tmp/one-entry-d81c3f2f; want=7fd723b4f087bf6030cc61adddfab60d46e66d84; prod=c43e1f1af1daab9c23e737101ed297826a70e4a5
ev="$(cd "$(dirname "$0")" && pwd)"; G="$ev/../one-entry-gate-v3-20261125"; R=$ev/runs/${1:?회차}
[ -e "$R" ] && { echo "있음 $R"; exit 1; }
[ "$(git -C $dir rev-parse HEAD)" = "$want" ] || { echo "HEAD 불일치"; exit 1; }
[ -z "$(git -C $dir status --porcelain)" ] || { echo "작업 트리 변경"; exit 1; }
[ "$(git -C $dir diff --name-only $prod HEAD)" = "web-api/src/test/java/com/cryptoautotrader/api/service/LiveOneEntrySmokePathPgTest.java" ] || { echo "제품/시험 차이 범위 다름"; exit 1; }
mkdir -p "$R/gate"
unset SR_PG_URL OB_PG_URL RACE_PG_URL CR_PG_URL LW_PG_URL PP_PG_URL PH_PG_URL RL_PG_URL AB_CHAIN_PG_URL N15_PG_URL N15_PG_USER N15_PG_PASSWORD V96_PG_URL N13_PG_URL OE_PG_URL
{ echo "HEAD $(git -C $dir rev-parse HEAD)"; echo "product $prod (diff = 시험 1파일)"; date -Iseconds
  sha256sum "$G/recovery-gate-v3.sql" "$G/gate_check.py"; } > "$R/head.txt"
db=oe2_$(echo "$1" | tr 'A-Z-' 'a-z_' | cut -c1-40)
P() { docker exec -i one-entry-pg psql -X -q -U trader "$@"; }
P -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db" > "$R/db.txt" 2>&1; echo "db=$db rc=$?" >> "$R/db.txt"
P -At -d postgres -c "select version()" >> "$R/db.txt" 2>&1
: > "$R/manifest.txt"
( cd $dir && env OE2_PG_URL=jdbc:postgresql://127.0.0.1:15441/$db OE2_MANIFEST="$R/manifest.txt" ./gradlew --offline :web-api:cleanTest :web-api:test --tests '*.LiveOneEntrySmokePathPgTest' > "$R/gradle.log" 2>&1; echo "rc=$?" > "$R/rc.txt" )
mkdir -p "$R/xml"; cp $dir/web-api/build/test-results/test/TEST-*.xml "$R/xml/" 2>/dev/null
grep -h '\[OE2\]' "$R"/xml/*.xml > "$R/marks.txt" 2>/dev/null
P -At -d $db -c "select max(version) from flyway_schema_history where success" > "$R/flyway.txt" 2>&1
reg() { printf 'sid=%s\nsession_type=REAL\nstrategy_type=DAILY_SMA20\ncoin_pair=KRW-BTC\ntimeframe=H1\ninitial_capital=10000\ninvest_ratio=0.995\nmax_investment=9950\nmax_entries=1\n' "$1"; }
gate() {  # 이름 sid 기대 [등록 파일]
  local n=$1 s=$2 x=$3 rg=${4:-$R/gate/$1.reg}; [ -n "${4:-}" ] || reg "$s" > "$rg"
  P -d $db -v ON_ERROR_STOP=1 -A -F '|' -P footer=off -v sid="$s" -f - < "$G/recovery-gate-v3.sql" > "$R/gate/$n.out" 2> "$R/gate/$n.err"; echo $? > "$R/gate/$n.rc"
  python -I "$G/gate_check.py" "$R/gate/$n.out" "$R/gate/$n.rc" "$rg" > "$R/gate/$n.verdict"; local got; got=$(sed -n '1s/^VERDICT //p' "$R/gate/$n.verdict")
  printf '%s %s 기대=%s 판정=%s %s\n' "$([ "$got" = "$x" ] && echo ok || echo NG)" "$n" "$x" "$got" "$(sed -n 2p "$R/gate/$n.verdict")" >> "$R/gate-summary.txt"
}
: > "$R/gate-summary.txt"
while read -r n s x; do [ -n "$n" ] && gate "$n" "$s" "$x"; done < "$R/manifest.txt"
norm=$(awk '$1=="g_normal_done_cleanup"{print $2}' "$R/manifest.txt")
gate x_not_found 999999999 FAIL
gate x_bad_sid abc FAIL
gate x_empty_sid '' FAIL
reg "$norm" | sed 's/^initial_capital=.*/initial_capital=20000/' > "$R/gate/x_reg_mismatch.reg"; gate x_reg_mismatch "$norm" FAIL "$R/gate/x_reg_mismatch.reg"
reg "$norm" | sed 's/^max_entries=.*/max_entries=/' > "$R/gate/x_reg_blank.reg"; gate x_reg_blank "$norm" FAIL "$R/gate/x_reg_blank.reg"
gate x_reg_missing "$norm" FAIL "$R/gate/does-not-exist.reg"
head -c -6 "$R/gate/g_normal_done_cleanup.out" > "$R/gate/x_partial.out"; cp "$R/gate/g_normal_done_cleanup.rc" "$R/gate/x_partial.rc"
python -I "$G/gate_check.py" "$R/gate/x_partial.out" "$R/gate/x_partial.rc" "$R/gate/g_normal_done_cleanup.reg" > "$R/gate/x_partial.verdict"
got=$(sed -n '1s/^VERDICT //p' "$R/gate/x_partial.verdict"); printf '%s x_partial_output 기대=FAIL 판정=%s %s\n' "$([ "$got" = FAIL ] && echo ok || echo NG)" "$got" "$(sed -n 2p "$R/gate/x_partial.verdict")" >> "$R/gate-summary.txt"
python -I - "$R" > "$R/summary.txt" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding="utf-8")
for f in sorted(glob.glob(sys.argv[1] + "/xml/TEST-*.xml")):
    r = ET.parse(f).getroot()
    print(r.get("name").split(".")[-1], "tests", r.get("tests"), "fail", r.get("failures"), "err", r.get("errors"), "skip", r.get("skipped"))
    for tc in r.iter("testcase"):
        bad = [x for x in tc if x.tag in ("failure", "error")]; sk = [x for x in tc if x.tag == "skipped"]
        print("  %s %s" % ("FAIL" if bad else ("SKIP" if sk else "ok  "), tc.get("name")), ("— " + (bad[0].get("message") or "")[:600].replace("\n", " ")) if bad else "")
PY
cat "$R/rc.txt" "$R/flyway.txt"; cat "$R/summary.txt"; echo "게이트 ok $(grep -c '^ok' "$R/gate-summary.txt") / $(wc -l < "$R/gate-summary.txt")"; grep '^NG' "$R/gate-summary.txt"
