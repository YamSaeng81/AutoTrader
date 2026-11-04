-- N13 bounded planning read; source 6995c52. EXPLAIN only, never EXPLAIN ANALYZE.
-- One run. If a statement fails or times out, stop; do not raise limits or retry.
\set ON_ERROR_STOP on
BEGIN TRANSACTION READ ONLY;
SET LOCAL application_name = 'n13-plan-readonly';
SET LOCAL search_path = public;
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '5s';
SET LOCAL idle_in_transaction_session_timeout = '30s';
\echo '== observed / estimated table rows (not COUNT)'
SELECT to_char(now() AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') AS observed_kst,
       c.reltuples AS estimated_rows, c.relpages AS estimated_pages,
       s.last_analyze, s.last_autoanalyze
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
WHERE n.nspname = 'public' AND c.relname = 'strategy_log';
\echo '== installed index definitions and validity'
SELECT ci.relname AS index_name, i.indisvalid, i.indisready,
       pg_get_indexdef(i.indexrelid) AS index_definition
FROM pg_index i
JOIN pg_class ci ON ci.oid = i.indexrelid
WHERE i.indrelid = 'public.strategy_log'::regclass
ORDER BY ci.relname;
\echo '== findPendingHoldFor4hEval / LIMIT 100 / planning only'
EXPLAIN (FORMAT JSON, COSTS TRUE)
SELECT DISTINCT ON (l.coin_pair, date_trunc('hour', l.created_at)) l.*
FROM strategy_log l
WHERE l.signal = 'HOLD'
  AND l.created_at < now() - interval '4 hours'
  AND l.signal_price IS NOT NULL
  AND l.price_after_4h IS NULL
  AND NOT EXISTS (
        SELECT 1 FROM strategy_log e
        WHERE e.signal = 'HOLD'
          AND e.coin_pair = l.coin_pair
          AND e.created_at >= date_trunc('hour', l.created_at)
          AND e.created_at <  date_trunc('hour', l.created_at) + interval '1 hour'
          AND e.price_after_4h IS NOT NULL)
ORDER BY l.coin_pair, date_trunc('hour', l.created_at), l.created_at
LIMIT 100;
\echo '== findPendingHoldFor24hEval / LIMIT 100 / planning only'
EXPLAIN (FORMAT JSON, COSTS TRUE)
SELECT DISTINCT ON (l.coin_pair, date_trunc('hour', l.created_at)) l.*
FROM strategy_log l
WHERE l.signal = 'HOLD'
  AND l.created_at < now() - interval '24 hours'
  AND l.signal_price IS NOT NULL
  AND l.price_after_24h IS NULL
  AND NOT EXISTS (
        SELECT 1 FROM strategy_log e
        WHERE e.signal = 'HOLD'
          AND e.coin_pair = l.coin_pair
          AND e.created_at >= date_trunc('hour', l.created_at)
          AND e.created_at <  date_trunc('hour', l.created_at) + interval '1 hour'
          AND e.price_after_24h IS NOT NULL)
ORDER BY l.coin_pair, date_trunc('hour', l.created_at), l.created_at
LIMIT 100;
ROLLBACK;
\echo '== end'
