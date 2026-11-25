#!/usr/bin/env bash
# LF jar 1회 (총괄 v1.245 §4) — dec-final-abc-20261119 §6과 같은 명령. autocrlf=false 작업 사본 /d/tmp/final-lf-1b719645. d81 재빌드 없음.
set -u
W=/d/tmp/final-lf-1b719645; out=/d/tmp/oe-final/jar-1b719645; ev="$(cd "$(dirname "$0")" && pwd)"; E=$ev/jar-1b719645
J81=/d/tmp/final-lf-d81c3f2f/web-api/build/libs/web-api-0.1.0.jar; CMP=$ev/../dec-final-abc-20261119/jar-d81c3f2f/jarm_u1.py
[ -e "$out" ] && { echo "이미 있음 $out"; exit 9; }; [ -e "$E" ] && { echo "이미 있음 $E"; exit 9; }
cd $W || exit 3
[ "$(git rev-parse HEAD)" = 1b719645ef907cb1aa45ffa01438fcfda7566758 ] || { echo "HEAD 불일치"; exit 4; }
[ -z "$(git status --porcelain)" ] || { echo "작업 트리 변경"; exit 5; }
mkdir -p "$out" "$E"
{ echo "HEAD $(git rev-parse HEAD) dirty=$(git status --porcelain | wc -l)"
  n=0; c=0; m=0; for f in $(git ls-files '*/src/main/resources/*'); do n=$((n+1)); grep -q $'\r' "$f" && c=$((c+1)); [ "$(git hash-object --no-filters "$f")" = "$(git rev-parse HEAD:"$f")" ] || m=$((m+1)); done
  echo "resources $n crlf $c blob_mismatch $m"; echo "start $(date '+%F %T %Z')"; } > "$E/head.txt"
echo "cmd ./gradlew :web-api:clean :web-api:bootJar -x test --offline" > "$E/cmd.txt"
./gradlew :web-api:clean :web-api:bootJar -x test --offline > "$out/bootjar.log" 2>&1; rc=$?
echo "rc=$rc end $(date '+%F %T %Z')" >> "$E/head.txt"; cp "$out/bootjar.log" "$E/"
[ $rc = 0 ] || { echo "bootJar rc=$rc"; exit $rc; }
J=$W/web-api/build/libs/web-api-0.1.0.jar; cp "$J" "$out/"
sha256sum "$J" | sed 's|  .*/| *|' > "$E/jar-sha.txt"
python -I "$CMP" "$J" | tr -d '\r' > "$E/jar-manifest-1b719645.lf.txt"; echo "jarm rc=${PIPESTATUS[0]}" >> "$E/head.txt"
python -I "$CMP" "$J81" | tr -d '\r' > "$out/jar-manifest-d81-stored.lf.txt"
{ echo "d81 저장 jar $(sha256sum "$J81" | cut -c1-64)"; echo "d81 저장 jar 목록 sha256 $(sha256sum < "$out/jar-manifest-d81-stored.lf.txt" | cut -c1-64) (기대 33a06f0e…)"
  echo "JARM $(sha256sum < "$E/jar-manifest-1b719645.lf.txt" | cut -c1-64)"; echo "JARN $(wc -l < "$E/jar-manifest-1b719645.lf.txt")"; } > "$E/jarm.txt"
python -I - "$out/jar-manifest-d81-stored.lf.txt" "$E/jar-manifest-1b719645.lf.txt" > "$E/jar-diff-vs-d81c3f2f.txt" <<'PY'
import sys
def ld(p): return dict(l.rstrip("\n").split("\t") for l in open(p, encoding="utf-8") if l.strip())
a, b = ld(sys.argv[1]), ld(sys.argv[2])
ch = sorted(k for k in a.keys() & b.keys() if a[k] != b[k]); ad = sorted(b.keys() - a.keys()); de = sorted(a.keys() - b.keys())
print("기준 d81c3f2f 저장 jar %d -> 1b719645 %d | 같음 %d 변경 %d 추가 %d 삭제 %d" % (len(a), len(b), len(a.keys() & b.keys()) - len(ch), len(ch), len(ad), len(de)))
for t, L in (("변경", ch), ("추가", ad), ("삭제", de)):
    for k in L: print(t, k)
PY
git diff --name-only d81c3f2f91c0239aacb0e1176dde9b898f4d7466 HEAD -- . ':!*src/test*' > "$E/product-paths-d81c3f2f-1b719645.txt"
cat "$E/jarm.txt" "$E/head.txt"; cat "$E/jar-diff-vs-d81c3f2f.txt"
