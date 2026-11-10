BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
\echo @@now
SELECT now() AT TIME ZONE 'Asia/Seoul' AS kst_now;
\echo @@strategy_type_enabled
COPY (SELECT strategy_name, is_active FROM strategy_type_enabled WHERE strategy_name IN ('COMPOSITE_MEANREV_BB','COMPOSITE_MTF_CONFIRMED','DAILY_SMA20') ORDER BY 1) TO STDOUT WITH CSV HEADER;
\echo @@strategy_timeframe_enabled
COPY (SELECT strategy_name, timeframe, is_active, disabled_at FROM strategy_timeframe_enabled WHERE strategy_name IN ('COMPOSITE_MEANREV_BB','COMPOSITE_MTF_CONFIRMED','DAILY_SMA20') ORDER BY 1,2) TO STDOUT WITH CSV HEADER;
\echo @@paper_status_counts
COPY (SELECT status, (id BETWEEN 522 AND 587) AS prereg, count(*) FROM paper_trading.virtual_balance GROUP BY 1,2 ORDER BY 1,2) TO STDOUT WITH CSV HEADER;
\echo @@paper_running_coin_tf
COPY (SELECT coin_pair, timeframe, (id BETWEEN 522 AND 587) AS prereg, count(*) FROM paper_trading.virtual_balance WHERE status='RUNNING' GROUP BY 1,2,3 ORDER BY 1,2,3) TO STDOUT WITH CSV HEADER;
\echo @@live_running
COPY (SELECT id, status, coin_pair, timeframe FROM live_trading_session WHERE status IN ('RUNNING','CREATED') ORDER BY id) TO STDOUT WITH CSV HEADER;
\echo @@dynamic_running
COPY (SELECT trading_mode, timeframe, count(*) FROM dynamic_session WHERE status='RUNNING' GROUP BY 1,2 ORDER BY 1,2) TO STDOUT WITH CSV HEADER;
ROLLBACK;
