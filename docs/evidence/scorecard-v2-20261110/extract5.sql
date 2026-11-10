-- 성적표 v2 보충 추출 5(2026-11-10, 총괄 v1.142 §110 보완 3). 읽기 전용 스냅숏 하나, 쓰기 문장 없음, ROLLBACK 으로 끝.
-- PAPER·LIVE 는 진입 관문에 걸린 BUY 를 기록 전에 HOLD 로 바꾼다(사유는 reason 앞머리). 그 HOLD 를 관문별로 센다.
-- 사전 등록 522~587(PAPER) 제외. 성과 열 없음. 사유 원문은 앞머리 분류만 내보낸다.
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '300s';
\echo @@meta5
COPY (SELECT to_char(transaction_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS snapshot_ts) TO STDOUT WITH (FORMAT csv, HEADER);
\echo @@gate_hold
COPY (SELECT session_type, session_id, strategy_name,
   CASE WHEN reason LIKE 'EMA200 레짐 필터%' THEN 'EMA200'
        WHEN reason LIKE 'RANGE 레짐%' THEN 'RANGE'
        WHEN reason LIKE 'BLACK_SWAN_GUARD%' THEN 'BLACK_SWAN'
        WHEN reason LIKE 'BTC_MARKET_GUARD%' THEN 'BTC_GUARD'
        WHEN reason LIKE '전략 요구 캔들 미달%' THEN 'CANDLES_SHORT' END AS gate,
   count(*) AS n, min(created_at) AT TIME ZONE 'UTC' AS first_at, max(created_at) AT TIME ZONE 'UTC' AS last_at
 FROM strategy_log
 WHERE session_type IN ('PAPER', 'LIVE') AND signal = 'HOLD'
   AND NOT (session_type = 'PAPER' AND session_id BETWEEN 522 AND 587)
   AND (reason LIKE 'EMA200 레짐 필터%' OR reason LIKE 'RANGE 레짐%' OR reason LIKE 'BLACK_SWAN_GUARD%' OR reason LIKE 'BTC_MARKET_GUARD%' OR reason LIKE '전략 요구 캔들 미달%')
 GROUP BY 1, 2, 3, 4 ORDER BY 1, 2, 4) TO STDOUT WITH (FORMAT csv, HEADER);
ROLLBACK;
