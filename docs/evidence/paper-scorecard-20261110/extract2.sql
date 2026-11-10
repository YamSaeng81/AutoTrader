-- 보충 추출(2026-11-10): 포지션별 규칙 해시(ruleset_hash). 읽기 전용, 사전 등록 522~587 제외.
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
\echo @@meta2
COPY (SELECT to_char(transaction_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS snapshot_ts) TO STDOUT WITH (FORMAT csv, HEADER);
\echo @@ruleset
COPY (SELECT 'PAPER' AS src, id, session_id, coalesce(ruleset_hash, '') AS ruleset_hash FROM paper_trading.position WHERE session_id IS NULL OR session_id NOT BETWEEN 522 AND 587
      UNION ALL SELECT session_kind, id, session_id, coalesce(ruleset_hash, '') FROM public.position ORDER BY 1, 2) TO STDOUT WITH (FORMAT csv, HEADER);
ROLLBACK;
