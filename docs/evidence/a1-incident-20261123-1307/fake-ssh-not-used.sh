#!/usr/bin/env bash
cmd="${@: -1}"; m=${FAKE_MODE:-ok}
[ "$m" = ssh255 ] && { echo "ssh: connect failed" >&2; exit 255; }
if [[ "$cmd" == *server_utc* ]]; then
  echo "server_utc $(date -u +%FT%TZ)"; h=0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d; [ "$m" = pinbad ] && h=d81c3f2f00000000000000000000000000000000
  echo "HEAD $h"; echo "backend Image=sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f Status=running RestartCount=0 StartedAt=2026-11-16T05:16:14.1Z"; exit 0
fi
if [[ "$cmd" == *psql* ]]; then
  [ "$m" = empty ] && { cat >/dev/null; exit 0; }
  [ "$m" = stderr ] && { echo "WARNING: x" >&2; }
  if [ "$m" = noguard ]; then docker exec -i a1-pg psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f -; exit $?; fi
  docker exec -i -e PGOPTIONS='-c statement_timeout=15s -c lock_timeout=2s' a1-pg psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f -; exit $?
fi
echo "unexpected: $cmd" >&2; exit 99
