-- 모의·실전 세션 성과표 원자료 추출(2026-11-10). 읽기 전용 스냅샷 하나, 쓰기 문장 없음, ROLLBACK 으로 끝.
-- 사전 등록 전향 검증 66세션(paper_trading 522~587)은 중간 성과 비열람 계약 때문에 성과 열·포지션·신호를 읽지 않는다.
\set ON_ERROR_STOP on
\pset footer off
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '300s';
\echo @@meta
COPY (SELECT to_char(transaction_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS snapshot_ts) TO STDOUT WITH (FORMAT csv, HEADER);
\echo @@paper_sessions
COPY (SELECT id, strategy_name, coin_pair, timeframe, status, version, initial_capital, invest_ratio, stop_loss_pct, max_hold_hours, strategy_params,
  started_at AT TIME ZONE 'UTC' AS started_at, stopped_at AT TIME ZONE 'UTC' AS stopped_at,
  CASE WHEN id BETWEEN 522 AND 587 THEN NULL ELSE total_krw END AS total_krw,
  CASE WHEN id BETWEEN 522 AND 587 THEN NULL ELSE available_krw END AS available_krw,
  CASE WHEN id BETWEEN 522 AND 587 THEN NULL ELSE realized_pnl END AS realized_pnl,
  CASE WHEN id BETWEEN 522 AND 587 THEN NULL ELSE total_fee END AS total_fee,
  (id BETWEEN 522 AND 587) AS prereg_blind
  FROM paper_trading.virtual_balance ORDER BY id) TO STDOUT WITH (FORMAT csv, HEADER);
\echo @@paper_positions
COPY (SELECT id, session_id, coin_pair, side, status, entry_price, avg_price, size, invested_krw, realized_pnl, unrealized_pnl, position_fee,
  stop_loss_price, take_profit_price, exit_reason, market_regime,
  opened_at AT TIME ZONE 'UTC' AS opened_at, closed_at AT TIME ZONE 'UTC' AS closed_at
  FROM paper_trading.position WHERE session_id IS NULL OR session_id NOT BETWEEN 522 AND 587 ORDER BY id) TO STDOUT WITH (FORMAT csv, HEADER);
\echo @@dynamic_sessions
COPY (SELECT id, trading_mode, strategy_type, timeframe, status, initial_capital, available_krw, total_asset_krw, invest_ratio, stop_loss_pct, max_hold_hours,
  max_candidate_size, target_watch_size, min_atr_pct, max_spread_pct, mdd_peak_capital, circuit_breaker_reason, circuit_breaker_trip_count, strategy_params,
  started_at AT TIME ZONE 'UTC' AS started_at, stopped_at AT TIME ZONE 'UTC' AS stopped_at, created_at AT TIME ZONE 'UTC' AS created_at
  FROM public.dynamic_session ORDER BY id) TO STDOUT WITH (FORMAT csv, HEADER);
\echo @@live_sessions
COPY (SELECT id, session_type, strategy_type, coin_pair, timeframe, status, initial_capital, available_krw, total_asset_krw, max_investment, invest_ratio, stop_loss_pct, max_hold_hours,
  mdd_peak_capital, circuit_breaker_reason, circuit_breaker_trip_count, strategy_params,
  started_at AT TIME ZONE 'UTC' AS started_at, stopped_at AT TIME ZONE 'UTC' AS stopped_at, created_at AT TIME ZONE 'UTC' AS created_at
  FROM public.live_trading_session ORDER BY id) TO STDOUT WITH (FORMAT csv, HEADER);
\echo @@public_positions
COPY (SELECT id, session_kind, session_id, coin_pair, side, status, entry_price, avg_price, size, invested_krw, entry_allocation_krw, realized_pnl, unrealized_pnl, position_fee,
  stop_loss_price, take_profit_price, exit_reason, market_regime, peak_value_krw,
  opened_at AT TIME ZONE 'UTC' AS opened_at, closed_at AT TIME ZONE 'UTC' AS closed_at
  FROM public.position ORDER BY id) TO STDOUT WITH (FORMAT csv, HEADER);
\echo @@signal_summary
COPY (SELECT session_type, session_id, strategy_name, signal, was_executed, coalesce(blocked_reason, '') AS blocked_reason, count(*) AS n,
  min(created_at) AT TIME ZONE 'UTC' AS first_at, max(created_at) AT TIME ZONE 'UTC' AS last_at
  FROM strategy_log WHERE NOT (session_type = 'PAPER' AND session_id BETWEEN 522 AND 587)
  GROUP BY 1,2,3,4,5,6 ORDER BY 1,2,4,5,6) TO STDOUT WITH (FORMAT csv, HEADER);
ROLLBACK;
