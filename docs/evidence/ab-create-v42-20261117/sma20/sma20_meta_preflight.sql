-- SMA20 단건 생성 직전 메타 조회 (총괄 v1.177 D3·v1.178 §4-2) — meta_preflight.sql(v4.1)과 같은 구획·열. 읽기 전용, 끝에 ROLLBACK.
-- 바뀐 점: 행 조건에 **상태와 무관하게 forwardTag = SMA20-FWD-1** 인 세션을 더한다(생성 반복 금지 — 멈춘 옛 생성도 찾는다).
-- 열 제한: 식별자·상태·전략·코인·주기·초기자본·실행 설정(strategy_params)·시작 시각만. 성과·잔고·포지션·신호 열은 읽지 않는다.
-- 사전 등록 522~587 은 행으로 읽지 않는다. 전체 RUNNING 수와 최대 ID 는 집계로만 센다.
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
WHERE id NOT BETWEEN 522 AND 587
  AND ((status = 'RUNNING' AND strategy_params::jsonb ? 'paperExecutionMode')
       OR strategy_params::jsonb ->> 'forwardTag' = 'SMA20-FWD-1')
ORDER BY id;
\echo @@end
ROLLBACK;
