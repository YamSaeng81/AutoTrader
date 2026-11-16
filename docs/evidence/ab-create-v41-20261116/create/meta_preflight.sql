-- v4.1 생성 직전 메타 조회(총괄 v1.177 D1) — v4 의 GET /api/v1/paper-trading/sessions 전체 목록을 대신한다. 읽기 전용, 끝에 ROLLBACK.
-- 실행: <--db-cmd 의 psql> -X -q --csv -v ON_ERROR_STOP=1 -f - < meta_preflight.sql   (생성기가 부른다. 비밀번호는 인자에 넣지 않는다)
-- 열 제한: 식별자·상태·전략·코인·주기·초기자본·실행 설정(strategy_params)·시작 시각만. 성과·잔고·포지션·신호 열은 읽지 않는다.
-- 사전 등록 522~587 은 행으로 읽지 않는다. 전체 RUNNING 수와 최대 ID 는 개수·최댓값 집계로만 센다(행 내용 없음).
-- 중복 후보 행은 RUNNING · 522~587 밖 · 실행 모드 키가 있는 세션만이다(이 도구가 만드는 세션은 모두 그 키를 가진다).
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
WHERE status = 'RUNNING' AND id NOT BETWEEN 522 AND 587 AND strategy_params ? 'paperExecutionMode'
ORDER BY id;
\echo @@end
ROLLBACK;
