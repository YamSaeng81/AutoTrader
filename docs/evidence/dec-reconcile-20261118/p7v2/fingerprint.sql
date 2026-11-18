-- P7 v2 — 추출 전후 DB 지문(읽기 전용). extract.sql 의 'fingerprint' 와 같은 식이다. 앞·스냅샷·뒤 세 값이 같아야 안정 구간이다.
\set ON_ERROR_STOP on
BEGIN TRANSACTION READ ONLY;
SELECT md5(coalesce((SELECT string_agg(concat_ws(':', id, state, filled_quantity, applied_executed_funds, applied_paid_fee,
                                                 reported_executed_funds, reported_paid_fee, fill_accounting_pending, settlement_version), ',' ORDER BY id) FROM "order"), '')
         || '|' || coalesce((SELECT string_agg(concat_ws(':', id, status, size), ',' ORDER BY id) FROM position WHERE status IN ('OPEN', 'CLOSING')), '')
         || '|' || coalesce((SELECT string_agg(concat_ws(':', id, status, available_krw), ',' ORDER BY id) FROM live_trading_session), '')
         || '|' || coalesce((SELECT string_agg(concat_ws(':', id, status, trading_mode, available_krw), ',' ORDER BY id) FROM dynamic_session), ''));
COMMIT;
