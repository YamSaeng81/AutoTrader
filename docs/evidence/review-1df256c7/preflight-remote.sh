#!/usr/bin/env bash
# v1.217 PRE0: connection-local settings only; no table data or server files.
set -euo pipefail
cd ~/crypto_trader/AutoTrader
docker compose -f docker-compose.prod.yml exec -T \
  -e 'PGOPTIONS=-c statement_timeout=15s -c lock_timeout=2s' \
  db psql -X -q -A -t -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader \
  -c 'SHOW statement_timeout; SHOW lock_timeout;'
