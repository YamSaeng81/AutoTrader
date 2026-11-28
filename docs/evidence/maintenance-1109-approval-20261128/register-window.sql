-- REVIEW ONLY. Not executed. Requires explicit approval for this one metadata row.
-- No candles, risk_config, schema, or software are changed by this statement.
BEGIN;
SET LOCAL statement_timeout='15s';
SET LOCAL lock_timeout='2s';
LOCK TABLE exchange_maintenance_window IN SHARE ROW EXCLUSIVE MODE;
DO $guard$
BEGIN
 IF (SELECT count(*) FROM flyway_schema_history WHERE version::integer>=95)<>1
    OR NOT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='96' AND success AND checksum=-424836241)
 THEN RAISE EXCEPTION 'WINDOW_GATE_SCHEMA_MISMATCH'; END IF;
 IF EXISTS(SELECT 1 FROM live_trading_session WHERE session_type='REAL' AND status IN ('RUNNING','PAUSED'))
    OR EXISTS(SELECT 1 FROM dynamic_session WHERE trading_mode='REAL' AND status IN ('RUNNING','PAUSED'))
 THEN RAISE EXCEPTION 'WINDOW_GATE_REAL_SESSION_ACTIVE'; END IF;
 IF EXISTS(SELECT 1 FROM exchange_maintenance_window
     WHERE exchange='UPBIT' AND scope='ALL_MARKETS'
       AND starts_at<TIMESTAMPTZ '2026-11-08 22:00:00+00'
       AND ends_at>TIMESTAMPTZ '2026-11-08 17:00:00+00')
 THEN RAISE EXCEPTION 'WINDOW_GATE_EXISTING_OVERLAP_NO_WRITE'; END IF;
END
$guard$;
INSERT INTO exchange_maintenance_window
 (exchange,scope,starts_at,ends_at,source,source_timezone,confirmed_at,confirmed_by,note)
VALUES
 ('UPBIT','ALL_MARKETS',TIMESTAMPTZ '2026-11-08 17:00:00+00',TIMESTAMPTZ '2026-11-08 22:00:00+00',
  'https://docs.upbit.com/kr/changelog/server_maintenance_1109','Asia/Seoul',clock_timestamp(),
  'Codex: user-approved operational maintenance record',
  'Official 2026-11-09 KST 02:00-07:00 whole-market maintenance; five H1 slots absent in public API and runtime backfill log. No synthetic candles; latest-completed-candle guard stays active.')
RETURNING id,exchange,scope,starts_at,ends_at,source,source_timezone,confirmed_at,confirmed_by;
COMMIT;
