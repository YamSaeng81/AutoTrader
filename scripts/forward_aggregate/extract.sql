-- 전향 검증 집계기 — 원자료 추출 (2026-10-27, 설계 docs/FORWARD_AGGREGATOR_DESIGN.md §3-4·§8 ②)
-- 🔴 운영 실행은 아직 승인되지 않았다(리뷰 2026-10-27: 운영 추출 보류). 지금은 로컬 PostgreSQL 검증용이다.
-- 하나의 REPEATABLE READ READ ONLY 스냅샷 안에서 CSV 6개를 현재 폴더에 쓰고 ROLLBACK 으로 끝낸다. 쓰기 문장 없음.
-- 실행(빈 폴더에서): psql -X -v ON_ERROR_STOP=1 -U <user> -d <db> -f extract.sql
-- 시각은 전부 UTC 'YYYY-MM-DDTHH:MM:SS.ffffffZ'. 세션 범위 522~587 은 넓게 읽고, 정확히 66개인지는 계산기가 설정과 대조한다.
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '120s';

\copy (SELECT to_char(transaction_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS snapshot_ts) TO 'meta.csv' WITH (FORMAT csv, HEADER)

\copy (SELECT id, coin_pair, strategy_name, timeframe, status, initial_capital, available_krw, realized_pnl, total_fee, to_char(started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS started_at, to_char(stopped_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS stopped_at FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587 ORDER BY id) TO 'sessions.csv' WITH (FORMAT csv, HEADER)

\copy (SELECT id, session_id, coin_pair, side, invested_krw, size, entry_price, avg_price, realized_pnl, position_fee, status, to_char(opened_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS opened_at, to_char(closed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS closed_at FROM paper_trading.position WHERE session_id BETWEEN 522 AND 587 ORDER BY id) TO 'positions.csv' WITH (FORMAT csv, HEADER)

\copy (SELECT id, session_id, position_id, coin_pair, side, order_type, price, quantity, filled_quantity, state, to_char(filled_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS filled_at FROM paper_trading."order" WHERE session_id BETWEEN 522 AND 587 ORDER BY id) TO 'orders.csv' WITH (FORMAT csv, HEADER)

\copy (SELECT c.coin_pair, to_char(c.time AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS time, c.close FROM market_data_cache c WHERE c.timeframe = 'H1' AND c.time >= TIMESTAMPTZ '2026-09-29 00:00:00+00' AND c.coin_pair IN (SELECT DISTINCT coin_pair FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587) ORDER BY c.coin_pair, c.time) TO 'candles.csv' WITH (FORMAT csv, HEADER)

\copy (SELECT session_id, to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day, count(*) AS n FROM strategy_log WHERE session_type = 'PAPER' AND session_id BETWEEN 522 AND 587 AND created_at >= TIMESTAMPTZ '2026-09-30 00:00:00+00' GROUP BY 1, 2 ORDER BY 1, 2) TO 'signal_counts.csv' WITH (FORMAT csv, HEADER)

ROLLBACK;
