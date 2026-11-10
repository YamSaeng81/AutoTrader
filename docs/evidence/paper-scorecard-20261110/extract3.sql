-- 보충 추출 3(2026-11-10): 비교용 보유 수익률. 세션 시작 직후·종료 직전(없으면 스냅숏 시각) H1 종가. 읽기 전용, 사전 등록 522~587 제외.
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '300s';
\echo @@meta3
COPY (SELECT to_char(transaction_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS snapshot_ts) TO STDOUT WITH (FORMAT csv, HEADER);
\echo @@bh
COPY (WITH s AS (
   SELECT 'PAPER' AS engine, id, coin_pair, started_at, coalesce(stopped_at, transaction_timestamp()) AS ended_at FROM paper_trading.virtual_balance WHERE id NOT BETWEEN 522 AND 587 AND started_at IS NOT NULL
   UNION ALL SELECT CASE WHEN trading_mode = 'PAPER' THEN 'DYN_PAPER' ELSE 'DYN_REAL' END, id, 'KRW-BTC', started_at, coalesce(stopped_at, transaction_timestamp()) FROM public.dynamic_session WHERE started_at IS NOT NULL
   UNION ALL SELECT 'LIVE', id, coin_pair, started_at, coalesce(stopped_at, transaction_timestamp()) FROM public.live_trading_session WHERE started_at IS NOT NULL)
 SELECT s.engine, s.id, s.coin_pair,
   (SELECT c.close FROM market_data_cache c WHERE c.coin_pair = s.coin_pair AND c.timeframe = 'H1' AND c.time >= s.started_at ORDER BY c.time LIMIT 1) AS close_start,
   (SELECT c.time AT TIME ZONE 'UTC' FROM market_data_cache c WHERE c.coin_pair = s.coin_pair AND c.timeframe = 'H1' AND c.time >= s.started_at ORDER BY c.time LIMIT 1) AS t_start,
   (SELECT c.close FROM market_data_cache c WHERE c.coin_pair = s.coin_pair AND c.timeframe = 'H1' AND c.time <= s.ended_at ORDER BY c.time DESC LIMIT 1) AS close_end,
   (SELECT c.time AT TIME ZONE 'UTC' FROM market_data_cache c WHERE c.coin_pair = s.coin_pair AND c.timeframe = 'H1' AND c.time <= s.ended_at ORDER BY c.time DESC LIMIT 1) AS t_end
 FROM s ORDER BY 1, 2) TO STDOUT WITH (FORMAT csv, HEADER);
ROLLBACK;
