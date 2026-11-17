#!/usr/bin/env bash
# v4.2 생성기로 8팔 실제 HTTP·PG 연결 재실행(총괄 v1.178 R1 영향 확인). 시험 코드 AbChainV4PgTest(0e1d2a02) 그대로, 격리 v152-pg 새 빈 DB.
# 작업 사본 /d/tmp/race-b987d7a7 은 시험 브랜치(0e1d2a02 + 시험 파일만). 실제 시각이 M15 경계 +6~+10분일 때 시작한다(구동기 규칙 C+6).
set -u
EV=$(cd "$(dirname "$0")" && pwd)
WT=/d/tmp/race-b987d7a7
SHA=0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d
P() { docker exec -i v152-pg psql -U trader -X -q -v ON_ERROR_STOP=1 "$@"; }
one() {   # 역할 DB 다른DB 출력이름
  local role=$1 db=$2 other=$3 o="$EV/runs/pgchain42/$4"
  mkdir -p "$o"
  P -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db" > "$o/createdb.txt" 2>&1
  while :; do s=$((10#$(date -u +%M) % 15)); [ "$s" -ge 6 ] && [ "$s" -le 10 ] && break; sleep 20; done
  date -u +%FT%TZ > "$o/started_utc.txt"
  git -C "$WT" rev-parse HEAD > "$o/worktree_head.txt"; git -C "$WT" status --short >> "$o/worktree_head.txt"
  ( cd "$WT" && env AB_CHAIN_PG_URL="jdbc:postgresql://127.0.0.1:15437/$db" AB_CHAIN_DRIVER="$EV/javaharness/chain_driver_v4.py" \
      AB_CHAIN_CODE_SHA=$SHA AB_CHAIN_ROLE=$role ${other:+AB_CHAIN_OTHER_DB=$other} AB_CHAIN_OUT="$o" \
      ./gradlew :web-api:cleanTest :web-api:test --tests com.cryptoautotrader.api.service.AbChainV4PgTest --no-daemon -q > "$o/gradle.log.txt" 2>&1
    echo $? > "$o/gradle.rc" )
  cp "$WT/web-api/build/test-results/test/TEST-com.cryptoautotrader.api.service.AbChainV4PgTest.xml" "$o/" 2>/dev/null
}
one seed-other ab42a "" run-a-seed
one main ab42b ab42a run-b-main
