#!/bin/bash
r="(now() + interval '$RT_SHIFT')"
a=(); for x in "$@"; do a+=("${x//now()/$r}"); done
exec docker exec -i cat-rt-pg psql -X -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader "${a[@]}"
