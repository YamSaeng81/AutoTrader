#!/usr/bin/env bash
# 사용: run.sh <출력이름> [게이트ms] — 18클래스(옛 대기 조건 사용) 실행, 결과 XML·gradle 원문·종료값 보존
set -u
name=$1; ms=${2:-}
out=/d/tmp/w20-ev/runs/$name
[ -e "$out" ] && { echo "이미 있음: $out"; exit 9; }
mkdir -p "$out"
cd /d/tmp/w20-88f06345 || exit 3
CLASSES_ALL="CancelConfirmAndLateFillCleanupTest ClaimAndUnknownTrackingTest DailySma20LiveOrderPathTest ExchangeRecoveryRestartTest IntentPrecommitAndOnceOnlyTest LateOrderSubmissionAfterStopTest LimitedTestExchangeRecoveryTest LiveEmergencyStopUnderConcurrentWriteTest LiveStopUnderConcurrentWriteTest N15PgLockWaitExpiryTest N16InvalidPriceReproTest N17RestInvalidPriceTest N19RestFallbackItemDefenseTest O1O3LogContractTest SlWatchdogFreshnessTest StaleRealtimeExitTest StopVersusInFlightExitRaceTest SubmitResponseUnknownTest ${EXTRA_CLASSES:-}"
CLASSES=${ONLY_CLASSES:-$CLASSES_ALL}
args=(); for c in $CLASSES; do args+=(--tests "com.cryptoautotrader.api.service.$c"); done
{ echo "HEAD $(git rev-parse HEAD)"; git status --short; echo "gate_ms=${ms:-off} beans=${DRAIN_GAP_GATE_BEANS:-default}"; echo "classes=$CLASSES"; echo "start $(date '+%F %T %Z')"; } > "$out/meta.txt"
rm -rf web-api/build/test-results/test
if [ -n "$ms" ]; then export DRAIN_GAP_GATE_MS=$ms; else unset DRAIN_GAP_GATE_MS; fi
./gradlew :web-api:test --offline --continue "${args[@]}" > "$out/gradle.log" 2>&1
rc=$?
echo "end $(date '+%F %T %Z') rc=$rc" >> "$out/meta.txt"
cp -r web-api/build/test-results/test "$out/xml" 2>/dev/null
python -I - "$out/xml" > "$out/summary.txt" <<'PY'
import sys,glob,xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding="utf-8")
T=F=E=S=0; fails=[]
for f in sorted(glob.glob(sys.argv[1]+"/*.xml")):
    r=ET.parse(f).getroot(); n=r.get("name").split(".")[-1]
    t,fa,er,sk=(int(r.get(k)) for k in("tests","failures","errors","skipped")); T+=t;F+=fa;E+=er;S+=sk
    print(f"{n}\t{t}\t실패{fa}\t오류{er}\t건너뜀{sk}")
    for tc in r.iter("testcase"):
        for x in list(tc):
            if x.tag in("failure","error"): fails.append(f"{n} :: {tc.get('name')} :: {(x.get('message') or '')[:300]}")
print(f"합계\t{T}\t실패{F}\t오류{E}\t건너뜀{S}")
for l in fails: print("FAIL",l)
PY
echo "rc=$rc"; cat "$out/summary.txt" | tail -40
