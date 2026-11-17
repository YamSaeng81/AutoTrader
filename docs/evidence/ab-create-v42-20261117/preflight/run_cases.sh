#!/usr/bin/env bash
# preflight_v2.sql 격리 PG 사례 실행(v152-pg, 템플릿 v41meta 사본 DB). 운영 DB 아님. 출력은 runs-pg/<사례>.out/.err/.rc
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/runs-pg"
P() { docker exec -i v152-pg psql -U trader -X -q -v ON_ERROR_STOP=1 "$@"; }
mk() { P -d postgres -c "DROP DATABASE IF EXISTS pf2_$1" -c "CREATE DATABASE pf2_$1 TEMPLATE v41meta" >/dev/null && P -d "pf2_$1" -f - < "$HERE/seed_base.sql" >/dev/null; }
ex() { P -d "pf2_$1" -c "$2" >/dev/null || { echo "자료 변경 실패: $1" >&2; echo 99 > "$OUT/SEED_FAILED_$1"; return 1; }; }
run() {  # 사례 DB at 파일이름 — 자료 변경이 실패한 사례는 실행하지 않는다(attempt1 결함 보완)
  if [ -e "$OUT/SEED_FAILED_$1" ]; then echo 99 > "$OUT/$3.rc"; return; fi
  P -d "pf2_$1" --csv -v at="$2" -v n_new="${4:-9}" -v cap=120 -f - < "$HERE/preflight_v2.sql" > "$OUT/$3.out" 2> "$OUT/$3.err"; echo $? > "$OUT/$3.rc"
}
AT='2026-11-17 05:10:00+00'
mk normal;   run normal "$AT" normal
mk gap3;     ex gap3 "DELETE FROM market_data_cache WHERE coin_pair='KRW-BTC' AND timeframe='H1' AND time IN ('2026-11-16 10:00+00','2026-11-16 11:00+00','2026-11-16 12:00+00')"; run gap3 "$AT" missing_gap3
mk gap2;     ex gap2 "DELETE FROM market_data_cache WHERE coin_pair='KRW-BTC' AND timeframe='H1' AND time IN ('2026-11-16 10:00+00','2026-11-16 11:00+00')"; run gap2 "$AT" boundary_gap2
mk b3;       ex b3 "DELETE FROM market_data_cache WHERE coin_pair='KRW-BTC' AND timeframe='H1' AND time = '2026-11-10 23:00+00'"; run b3 "$AT" missing_b3
mk short;    ex short "DELETE FROM market_data_cache WHERE coin_pair='KRW-ETH' AND timeframe='M15' AND time < '2026-11-17 05:00+00'::timestamptz - interval '1335 minutes'"; run short "$AT" missing_history
mk stalem15; ex stalem15 "DELETE FROM market_data_cache WHERE coin_pair='KRW-BTC' AND timeframe='M15' AND time >= '2026-11-17 04:30+00'"; run stalem15 "$AT" stale_btc_m15
mk staleh1;  ex staleh1 "DELETE FROM market_data_cache WHERE coin_pair='KRW-BTC' AND timeframe='H1' AND time >= '2026-11-17 04:00+00'"
run staleh1 '2026-11-17 05:01:59+00' grace_h1_0501_59; run staleh1 '2026-11-17 05:02:00+00' stale_h1_0502_00
mk none;     ex none "DELETE FROM market_data_cache WHERE coin_pair='KRW-ETH' AND timeframe='M15'"; run none "$AT" none_eth_m15
mk enable;   ex enable "INSERT INTO strategy_type_enabled (strategy_name, is_active, updated_at) VALUES ('DAILY_SMA20', false, now())"
ex enable "INSERT INTO strategy_timeframe_enabled (strategy_name, timeframe, is_active, updated_at) VALUES ('COMPOSITE_MEANREV_BB','M15',false,now()), ('COMPOSITE_MTF_CONFIRMED','M15',true,now())"; run enable "$AT" enablement
mk capdup
ex capdup "CREATE TEMP TABLE x AS SELECT * FROM paper_trading.virtual_balance WHERE id = 600; ALTER TABLE x DROP COLUMN id;
  INSERT INTO paper_trading.virtual_balance SELECT nextval('paper_trading.virtual_balance_id_seq'), x.* FROM x, generate_series(1, 107);
  CREATE TEMP TABLE y AS SELECT * FROM paper_trading.virtual_balance WHERE id = 601; UPDATE y SET id = 9001, status = 'STOPPED', strategy_name = 'DAILY_SMA20', coin_pair = 'KRW-BTC', timeframe = 'H1',
    strategy_params = '{\"paperExecutionMode\":\"LIVE_ALIGNED\",\"paperMaxInvestment\":20000,\"forwardTag\":\"SMA20-FWD-1\"}'; INSERT INTO paper_trading.virtual_balance SELECT * FROM y;
  UPDATE paper_trading.virtual_balance SET strategy_params = '{\"forwardTag\":\"SMA20-FWD-1\",\"paperExecutionMode\":\"LIVE_ALIGNED\"}' WHERE id = 530"
run capdup "$AT" cap_dup
run capdup "$AT" cap_over_n10 10
P -d postgres -c "SELECT datname FROM pg_database WHERE datname LIKE 'pf2_%' ORDER BY 1" --csv > "$OUT/dbs.txt"
