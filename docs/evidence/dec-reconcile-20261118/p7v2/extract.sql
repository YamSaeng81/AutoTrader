-- P7 v2 병행 대조 — DB 한 스냅샷 추출(읽기 전용 초안, 총괄 v1.186 §3-2·§3-3). 운영 설치본·RUNBOOK 과 무관한 로컬 사본이다.
-- 실행: psql -X -At -v ON_ERROR_STOP=1 -f extract.sql  → JSON 한 줄. 운영 실행은 별도 승인 전이다.
-- 🔴 쓰기 문장 없음. 한 REPEATABLE READ READ ONLY 트랜잭션 안에서 세션·주문·포지션·정산 불일치·지문을 같은 스냅샷으로 읽는다.
--    거래소와 DB 는 원자적으로 읽을 수 없으므로 앞뒤 지문(fingerprint.sql)과 거래소 전후 관측으로 안정 구간인지 따로 확인한다.
-- 🔴 모드·종류로 미리 거르지 않는다 — 모의/실자금 구분과 등록 대조는 대조기가 한다(잘못된 모드·미등록·삭제된 세션 주문을 놓치지 않으려고).
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT json_build_object(
  'observed_at', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
  'snapshot', pg_current_snapshot()::text,
  'flyway_version', (SELECT max(version::int) FROM flyway_schema_history WHERE success),
  'fingerprint', (
    SELECT md5(coalesce((SELECT string_agg(concat_ws(':', id, state, filled_quantity, applied_executed_funds, applied_paid_fee,
                                                     reported_executed_funds, reported_paid_fee, fill_accounting_pending, settlement_version), ',' ORDER BY id) FROM "order"), '')
             || '|' || coalesce((SELECT string_agg(concat_ws(':', id, status, size), ',' ORDER BY id) FROM position WHERE status IN ('OPEN', 'CLOSING')), '')
             || '|' || coalesce((SELECT string_agg(concat_ws(':', id, status, available_krw), ',' ORDER BY id) FROM live_trading_session), '')
             || '|' || coalesce((SELECT string_agg(concat_ws(':', id, status, trading_mode, available_krw), ',' ORDER BY id) FROM dynamic_session), ''))),
  'sessions', (
    SELECT coalesce(json_agg(s ORDER BY s.kind, s.id), '[]'::json) FROM (
      SELECT 'LIVE' AS kind, id, session_type AS mode, strategy_type, coin_pair, timeframe, initial_capital::text, available_krw::text,
             status, created_at, started_at, stopped_at
        FROM live_trading_session
      UNION ALL
      SELECT 'DYNAMIC', id, trading_mode, strategy_type, NULL, timeframe, initial_capital::text, available_krw::text,
             status, created_at, started_at, stopped_at
        FROM dynamic_session) s),
  'orders', (
    SELECT coalesce(json_agg(json_build_object(
             'id', id, 'session_kind', session_kind, 'session_id', session_id, 'position_id', position_id,
             'coin_pair', coin_pair, 'side', side, 'order_type', order_type, 'quantity', quantity::text,
             'reserved_krw', reserved_krw::text, 'client_order_id', client_order_id, 'state', state,
             'filled_quantity', filled_quantity::text, 'exchange_order_id', exchange_order_id, 'created_at', created_at,
             'reported_executed_funds', reported_executed_funds::text, 'reported_paid_fee', reported_paid_fee::text,
             'applied_executed_funds', applied_executed_funds::text, 'applied_paid_fee', applied_paid_fee::text,
             'fill_accounting_pending', fill_accounting_pending) ORDER BY id), '[]'::json)
      FROM "order"),
  'positions', (
    SELECT coalesce(json_agg(json_build_object(
             'id', id, 'session_kind', session_kind, 'session_id', session_id, 'coin_pair', coin_pair,
             'status', status, 'size', size::text, 'opened_at', opened_at) ORDER BY id), '[]'::json)
      FROM position WHERE status IN ('OPEN', 'CLOSING')),
  'unresolved_discrepancies', (
    SELECT coalesce(json_agg(json_build_object('id', id, 'order_id', order_id, 'kind', kind) ORDER BY id), '[]'::json)
      FROM order_settlement_discrepancy WHERE resolved_at IS NULL)
);
COMMIT;
