-- v4.1 생성 뒤 메타 조회(총괄 v1.177 D1) — v4 의 사후 GET 전체 목록을 대신한다. 읽기 전용, 끝에 ROLLBACK.
-- 실행: <--db-cmd 의 psql> -X -q --csv -v ON_ERROR_STOP=1 -v max_id=<사전 조회 max_id> -f - < meta_after.sql
-- 사전 조회 뒤 새로 생긴 ID(> max_id)만 읽는다 — 이 도구의 생성과 같은 때 다른 생성(동시 다른 생성)도 여기서 보인다.
-- 열 제한은 사전 조회와 같다. 522~587 은 max_id 보다 작아 들어오지 않지만 조건으로도 뺀다.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
\echo @@db_now
SELECT now() AS db_now;
\echo @@counts
SELECT count(*) FILTER (WHERE status = 'RUNNING') AS running_total, coalesce(max(id), 0) AS max_id
FROM paper_trading.virtual_balance;
\echo @@rows
SELECT id, status, strategy_name, coin_pair, timeframe, initial_capital, strategy_params::text AS params_json, started_at
FROM paper_trading.virtual_balance
WHERE id > :max_id AND id NOT BETWEEN 522 AND 587
ORDER BY id;
\echo @@end
ROLLBACK;
