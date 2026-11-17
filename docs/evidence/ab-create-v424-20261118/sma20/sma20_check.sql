-- SMA20 단건 생성 뒤 확인 (총괄 v1.177 D3·D4) — 읽기 전용, 끝에 ROLLBACK. T0 전에 실행한다(db_now < t0 를 완성기가 본다).
--   psql -X -q --csv -v ON_ERROR_STOP=1 -v id=<새 id> -v t0='<초안 t0 ISO>' -f sma20_check.sql > sma20.db_check.out
-- 열 제한: 세션 설정·시작 시각과 T0 전 주문/포지션 개수만. 성과·잔고 열은 읽지 않는다. 522~587 은 조건으로 제외한다.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
\echo @@db_now
SELECT now() AS db_now, now() < :'t0'::timestamptz AS before_t0_now;
\echo @@stored
SELECT id, strategy_name, coin_pair, timeframe, status, initial_capital, invest_ratio, stop_loss_pct, max_hold_hours,
       strategy_params::text AS params_json, started_at, started_at < :'t0'::timestamptz AS before_t0
FROM paper_trading.virtual_balance WHERE id = :id AND id NOT BETWEEN 522 AND 587;
\echo @@trades
-- 기대: 0, 0, 0, 0. 생성 뒤 T0 전에는 주문·포지션이 없어야 한다(전체 개수도 본다 — T0 전에 확인하므로 같아야 한다).
SELECT (SELECT count(*) FROM paper_trading."order" WHERE session_id = :id AND id IS NOT NULL) AS orders_all,
       (SELECT count(*) FROM paper_trading."order" WHERE session_id = :id AND filled_at < :'t0'::timestamptz) AS orders_before_t0,
       (SELECT count(*) FROM paper_trading.position WHERE session_id = :id) AS positions_all,
       (SELECT count(*) FROM paper_trading.position WHERE session_id = :id AND opened_at < :'t0'::timestamptz) AS positions_before_t0;
\echo @@end
ROLLBACK;
