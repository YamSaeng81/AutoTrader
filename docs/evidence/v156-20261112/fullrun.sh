#!/bin/bash
# 최종 SHA 전체 회귀 — 모든 기록(시각·종료값·요약)을 쓴 다음 마지막에 해시. 해시 뒤 이 폴더에 쓰지 않는다.
W=/d/tmp/int155; O=/d/tmp/v153-runs/v156/final-full; mkdir -p $O; cd $W
S="full test start $(date '+%F %T')"; H=$(git rev-parse HEAD)
for m in core-engine exchange-adapter strategy-lib web-api; do rm -rf $m/build/test-results/test; done
./gradlew test --rerun --continue --console=plain > $O/gradle.log 2>&1; rc=$?
E="rc=$rc end $(date '+%F %T')"
for m in core-engine exchange-adapter strategy-lib web-api; do mkdir -p $O/xml/$m; cp $m/build/test-results/test/*.xml $O/xml/$m/ 2>/dev/null; done
python -X utf8 -I "C:/Users/SANGJO~1/AppData/Local/Temp/claude/e30ef13592d895eb/ffde7d53-35d4-47fc-b44d-5d3a2ac28ccc/scratchpad/junitsum2.py" $O/xml > $O/summary.txt
printf '%s\n%s\n%s\n' "$S" "$H" "$E" > $O/time.txt
cd $O && find . -type f ! -name hashes.sha256 | sort | xargs sha256sum > hashes.sha256
