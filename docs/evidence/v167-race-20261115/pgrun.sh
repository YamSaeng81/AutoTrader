#!/usr/bin/env bash
# 사용: pgrun.sh <출력이름> <클래스...> — v152-pg 에 실행별 새 DB 를 만들고 RACE_PG_URL 로 run.sh 를 부른다
set -u
name=$1; shift
db=r167_$(echo "$name" | tr 'A-Z-' 'a-z_' | cut -c1-30)_$RANDOM
docker exec v152-pg psql -U trader -d postgres -qc "CREATE DATABASE $db" || { echo "DB 생성 실패"; exit 4; }
export RACE_PG_URL=jdbc:postgresql://127.0.0.1:15437/$db
echo "db=$db"
bash "$(dirname "$0")/run.sh" "$name" off "$@"
echo "pg_db=$db" >> /d/tmp/v167-ev/runs/$name/meta.txt
