-- 새 모의 9세션 생성 직전 읽기(읽기 전용 트랜잭션). 사전 등록 522~587 은 개수만 세고 성과·포지션·신호·파라미터 열을 읽지 않는다.
-- 출처: exit-ab-protocol-20261110/prod-read/meta.sql(11-10 운영 읽기, 같은 질의) + 중복 서명 확인(@@dup_candidates, 522~587 제외·열 제한).
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
\echo @@now
SELECT now() AT TIME ZONE 'Asia/Seoul' AS kst_now;
\echo @@flyway_after_v95
COPY (SELECT installed_rank, version, success, checksum FROM flyway_schema_history WHERE installed_rank > 94 ORDER BY 1) TO STDOUT WITH CSV HEADER;
\echo @@strategy_type_enabled
COPY (SELECT strategy_name, is_active FROM strategy_type_enabled WHERE strategy_name IN ('COMPOSITE_MEANREV_BB','COMPOSITE_MTF_CONFIRMED','DAILY_SMA20') ORDER BY 1) TO STDOUT WITH CSV HEADER;
\echo @@strategy_timeframe_enabled
COPY (SELECT strategy_name, timeframe, is_active, disabled_at FROM strategy_timeframe_enabled WHERE strategy_name IN ('COMPOSITE_MEANREV_BB','COMPOSITE_MTF_CONFIRMED','DAILY_SMA20') ORDER BY 1,2) TO STDOUT WITH CSV HEADER;
\echo @@paper_status_counts
COPY (SELECT status, (id BETWEEN 522 AND 587) AS prereg, count(*) FROM paper_trading.virtual_balance GROUP BY 1,2 ORDER BY 1,2) TO STDOUT WITH CSV HEADER;
\echo @@paper_running_coin_tf
COPY (SELECT coin_pair, timeframe, (id BETWEEN 522 AND 587) AS prereg, count(*) FROM paper_trading.virtual_balance WHERE status='RUNNING' GROUP BY 1,2,3 ORDER BY 1,2,3) TO STDOUT WITH CSV HEADER;
\echo @@dup_candidates
COPY (SELECT id, status, strategy_name, coin_pair, timeframe, initial_capital, strategy_params::text FROM paper_trading.virtual_balance
      WHERE status='RUNNING' AND id NOT BETWEEN 522 AND 587
        AND strategy_name IN ('COMPOSITE_MEANREV_BB','COMPOSITE_MTF_CONFIRMED','DAILY_SMA20')
        AND strategy_params ? 'paperExecutionMode' ORDER BY id) TO STDOUT WITH CSV HEADER;
\echo @@paper_max_id
SELECT max(id) AS max_id FROM paper_trading.virtual_balance;
\echo @@live_running
COPY (SELECT id, status, coin_pair, timeframe FROM live_trading_session WHERE status IN ('RUNNING','CREATED') ORDER BY id) TO STDOUT WITH CSV HEADER;
\echo @@dynamic_running
COPY (SELECT trading_mode, timeframe, count(*) FROM dynamic_session WHERE status='RUNNING' GROUP BY 1,2 ORDER BY 1,2) TO STDOUT WITH CSV HEADER;
\echo @@btc_h1_candles
COPY (SELECT count(*) AS n, min(time) AS first_t, max(time) AS last_t FROM market_data_cache WHERE coin_pair='KRW-BTC' AND timeframe='H1' AND time > now() - interval '30 days') TO STDOUT WITH CSV HEADER;
ROLLBACK;
