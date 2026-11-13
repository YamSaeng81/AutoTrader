#!/usr/bin/env bash
# 사용: run.sh <출력이름> <게이트ms|off> <클래스...> — 결과 XML·gradle 원문·종료값·작업 상태 보존, 기존 출력은 덮어쓰지 않는다
set -u
name=$1; ms=$2; shift 2
out=/d/tmp/g1-ev/runs/$name
[ -e "$out" ] && { echo "이미 있음: $out"; exit 9; }
mkdir -p "$out"
cd "${WT:-/d/tmp/g1-7d1d00ae}" || exit 3
args=(); for c in "$@"; do args+=(--tests "com.cryptoautotrader.api.service.$c"); done
{ echo "HEAD $(git rev-parse HEAD)"; git status --short; echo "worktree_diff_sha256 $(git diff HEAD | sha256sum | cut -c1-64)"; echo "gate_ms=$ms"; echo "classes=$*"; echo "start $(date '+%F %T %Z')"; } > "$out/meta.txt"
rm -rf web-api/build/test-results/test
if [ "$ms" != off ]; then export DRAIN_GAP_GATE_MS=$ms; else unset DRAIN_GAP_GATE_MS; fi
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
ws=$(cat "$out"/xml/*.xml 2>/dev/null | grep -c 'Upbit WebSocket 연결 성공\|구독 메시지 전송\|Upbit WebSocket 연결 시도')
echo "외부WS연결·구독줄=$ws" >> "$out/summary.txt"
echo "rc=$rc"; tail -12 "$out/summary.txt"
