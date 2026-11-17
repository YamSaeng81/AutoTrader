#!/usr/bin/env bash
# SMA20 단건 실제 HTTP·PG 연결(총괄 v1.178 §4-2). 시험 Sma20ChainPgTest(시험 브랜치), 격리 v152-pg: 빈 DB sm20a(Flyway V96) + 다른 DB sm20o(v41meta 사본).
# 생성 창 규칙상 실제 시각 01:01~23:24 UTC 에만 POST 가 나간다(그 밖이면 도구 rc2 → 시험 실패로 남는다).
set -u
EV=$(cd "$(dirname "$0")" && pwd)
WT=/d/tmp/race-b987d7a7
SHA=0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d
O="$EV/runs/sma20chain/${1:-run1}"
mkdir -p "$O"
P() { docker exec -i v152-pg psql -U trader -X -q -v ON_ERROR_STOP=1 "$@"; }
P -d postgres -c "DROP DATABASE IF EXISTS sm20a" -c "CREATE DATABASE sm20a" -c "DROP DATABASE IF EXISTS sm20o" -c "CREATE DATABASE sm20o TEMPLATE v41meta" > "$O/createdb.txt" 2>&1
date -u +%FT%TZ > "$O/started_utc.txt"
git -C "$WT" rev-parse HEAD > "$O/worktree_head.txt"; git -C "$WT" status --short >> "$O/worktree_head.txt"
( cd "$WT" && env SMA20_CHAIN_PG_URL="jdbc:postgresql://127.0.0.1:15437/sm20a" SMA20_CHAIN_DRIVER="$EV/sma20/sma20_chain_driver.py" \
    SMA20_CHAIN_CODE_SHA=$SHA SMA20_CHAIN_OTHER_DB=sm20o SMA20_CHAIN_OUT="$O" \
    ./gradlew :web-api:cleanTest :web-api:test --tests com.cryptoautotrader.api.service.Sma20ChainPgTest --no-daemon -q > "$O/gradle.log.txt" 2>&1
  echo $? > "$O/gradle.rc" )
cp "$WT/web-api/build/test-results/test/TEST-com.cryptoautotrader.api.service.Sma20ChainPgTest.xml" "$O/" 2>/dev/null
