#!/bin/bash
# W3 시계 주입: F5 조회의 now() 만 앞당긴다(RT_SHIFT). 기록 목적 — 운영에서 쓰지 않는다
r="(now() + interval '$RT_SHIFT')"
a=(); for x in "$@"; do a+=("${x//now()/$r}"); done
exec docker exec -i cat-rt-pg psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader "${a[@]}"
