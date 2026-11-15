#!/usr/bin/env bash
# 전체 회귀 1회 (총괄 v1.167 §4-6) — 기본 게이트·진단 감시 끔, 원문 보존. 기존 출력 덮어쓰기 금지.
set -u
out=/d/tmp/v167-ev/runs/full-$1
[ -e "$out" ] && { echo "이미 있음: $out"; exit 9; }
mkdir -p "$out"
cd /d/tmp/race-b987d7a7 || exit 3
unset RACE_PG_URL SR_PG_URL CR_PG_URL LW_PG_URL PP_PG_URL PH_PG_URL RL_PG_URL DRAIN_GAP_GATE_MS N15_PG_URL N15_PG_USER N15_PG_PASSWORD G1_OUTBOUND_GUARD TEST_NET_GUARD
echo "cmd ./gradlew cleanTest test --continue" > "$out/cmd.txt"
git status --short > "$out/git-status.txt"
{ echo "HEAD $(git rev-parse HEAD) dirty=$(git status --short | wc -l)"; echo "worktree_diff_sha256 $(git diff HEAD | sha256sum | cut -c1-64)";
  echo "env DRAIN_GAP_GATE_MS=${DRAIN_GAP_GATE_MS-unset} N15_PG_URL=${N15_PG_URL-unset} G1_OUTBOUND_GUARD=${G1_OUTBOUND_GUARD-unset} TEST_NET_GUARD=${TEST_NET_GUARD-unset}";
  echo "start $(date '+%F %T %Z')"; } > "$out/time.txt"
./gradlew cleanTest test --continue > "$out/gradle.log" 2>&1
rc=$?
echo "rc=$rc end $(date '+%F %T %Z')" >> "$out/time.txt"
for m in core-engine exchange-adapter strategy-lib web-api; do
  mkdir -p "$out/xml/$m"; cp -r "$m/build/test-results/test/." "$out/xml/$m/" 2>/dev/null
done
python -I - "$out" > "$out/summary.txt" <<'PY'
import sys, glob, collections, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding="utf-8")
out = sys.argv[1]; fails = []; skips = collections.Counter()
for m in ["core-engine", "exchange-adapter", "strategy-lib", "web-api"]:
    T = F = E = S = 0
    for f in sorted(glob.glob(f"{out}/xml/{m}/TEST-*.xml")):
        r = ET.parse(f).getroot(); n = r.get("name").split(".")[-1]
        T += int(r.get("tests")); F += int(r.get("failures")); E += int(r.get("errors")); S += int(r.get("skipped"))
        for tc in r.iter("testcase"):
            for x in list(tc):
                if x.tag in ("failure", "error"): fails.append(f"{m} {n} :: {tc.get('name')} :: {(x.get('message') or '')[:300]}")
                if x.tag == "skipped": skips[f"{m} {n}"] += 1
    print(f"{m} tests {T} fail {F} err {E} skip {S} pass {T-F-E-S}")
for l in fails: print("FAIL", l)
print("--- 건너뜀(클래스별)")
for k, v in sorted(skips.items()): print(f"{v}\t{k}")
PY
{ echo "--- NET-GUARD (web-api)"; cat "$out/xml/web-api/NET-GUARD-summary.txt" 2>/dev/null || echo "요약 없음"; } >> "$out/summary.txt"
echo "rc=$rc"; head -6 "$out/summary.txt"
