#!/bin/bash
# 사용: runtest.sh <실행이름> <테스트 패턴...> — 실행 전 소스·해시 보존, 실행별 XML·로그·종료값 분리
set -u; R=/d/tmp/n19/runs/$1; shift
[ ! -e "$R" ] || { echo "이미 있는 실행 폴더 $R"; exit 2; }
W="/d/Claude Code/projects/cat-n19"; mkdir -p "$R/src"
cd "$W"
git rev-parse HEAD > "$R/head.txt"; git status --porcelain > "$R/status.txt"; git diff > "$R/worktree.diff"
for f in $(git status --porcelain | awk '{print $2}') $(git diff --name-only); do [ -f "$f" ] && mkdir -p "$R/src/$(dirname "$f")" && cp "$f" "$R/src/$f"; done
( cd "$R/src" && find . -type f -print0 | sort -z | xargs -0 sha256sum ) > "$R/src.sha256"
args=(); for t in "$@"; do args+=(--tests "$t"); done
date '+시작 %F %T' > "$R/time.txt"
./gradlew :web-api:cleanTest :web-api:test "${args[@]}" --offline -i > "$R/gradle.log" 2>&1; echo $? > "$R/exit.txt"
date '+끝 %F %T' >> "$R/time.txt"
mkdir -p "$R/xml"; cp web-api/build/test-results/test/*.xml "$R/xml/" 2>/dev/null
echo "exit=$(cat $R/exit.txt)"; grep -h -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$R"/xml/*.xml
