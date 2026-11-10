BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
\echo @@now
SELECT now() AT TIME ZONE 'Asia/Seoul' AS kst_now;
\echo @@sid203
COPY (SELECT id, status, available_krw, started_at FROM live_trading_session WHERE id = 203) TO STDOUT WITH CSV HEADER;
\echo @@btc_h1
COPY (SELECT max(time) AT TIME ZONE 'Asia/Seoul' AS latest_bar_open_kst, count(*) FILTER (WHERE time >= now() - interval '20 days') AS bars_20d FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1') TO STDOUT WITH CSV HEADER;
\echo @@live_open
COPY (SELECT count(*) AS open_or_closing FROM position WHERE session_kind IN ('LIVE','DYNAMIC') AND status IN ('OPEN','CLOSING')) TO STDOUT WITH CSV HEADER;
\echo @@paper_status
COPY (SELECT status, (id BETWEEN 522 AND 587) AS prereg, count(*) FROM paper_trading.virtual_balance GROUP BY 1,2 ORDER BY 1,2) TO STDOUT WITH CSV HEADER;
ROLLBACK;
