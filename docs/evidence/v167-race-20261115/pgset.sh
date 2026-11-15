#!/usr/bin/env bash
# 사용: pgset.sh <접두이름> <클래스:환경변수>... — 클래스마다 v152-pg 새 DB, run.sh 로 실행·보존
set -u
pre=$1; shift
for p in "$@"; do c=${p%%:*}; v=${p##*:}
  db=r167_$(echo "$c" | tr 'A-Z' 'a-z' | cut -c1-24)_$RANDOM
  docker exec v152-pg psql -U trader -d postgres -qc "CREATE DATABASE $db" || { echo "$c DB 생성 실패"; continue; }
  ( export $v=jdbc:postgresql://127.0.0.1:15437/$db; bash "$(dirname "$0")/run.sh" "$pre-$c" off "$c" > /dev/null 2>&1 )
  echo "pg_db=$db env=$v" >> /d/tmp/v167-ev/runs/$pre-$c/meta.txt
  echo "$c $(tail -1 /d/tmp/v167-ev/runs/$pre-$c/meta.txt | head -1) $(grep 합계 /d/tmp/v167-ev/runs/$pre-$c/summary.txt)"
done
