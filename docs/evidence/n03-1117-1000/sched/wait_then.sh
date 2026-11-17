#!/usr/bin/env bash
# $1 = 목표 UTC 시각(HH:MM:SS, 다음 도래), 나머지 = 실행할 명령
target=$1; shift
now=$(date -u +%s); t=$(date -u -d "$(date -u +%F) $target" +%s); [ $t -le $now ] && t=$((t+86400))
while [ $(date -u +%s) -lt $t ]; do sleep 20; done
"$@"
