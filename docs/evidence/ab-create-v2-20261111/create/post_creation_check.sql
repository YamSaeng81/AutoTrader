-- 청산 A/B 8세션 생성 직후 확인 — 초안, 실행 금지(생성 승인 전). 생성 뒤 읽기 전용으로만 쓴다.
-- :ids = ab_create.py 가 쓴 creation_log.draft.json 의 8개 session_id, :t0 = 그 파일의 t0. 522~587 은 넣지 않는다(성과 비열람 계약).
-- API 응답에는 invest_ratio·stop_loss_pct·max_hold_hours 가 없고 같은 쌍의 A·B 가 목록으로 구분되지 않는다 — 팔별 저장값은 여기서만 확인한다.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT now() AS db_now;
\echo @@stored
-- 기대: 8행, 모두 RUNNING·M15·100000·0.8·24, strategy_params = {"paperMaxInvestment":20000,"paperExecutionMode":"LIVE_ALIGNED"},
--       A 의 stop_loss_pct NULL, B 의 stop_loss_pct 5.00, started_at < :t0.
SELECT id, strategy_name, coin_pair, timeframe, status, initial_capital, invest_ratio, stop_loss_pct, max_hold_hours,
       strategy_params::text AS params_json, started_at, started_at < :t0 AS before_t0
FROM paper_trading.virtual_balance WHERE id IN (:ids) ORDER BY id;
\echo @@fingerprint
-- 규칙 지문은 세션 행에 없고 첫 평가의 신호 로그에 실린다(평가 주기 60초). 세션마다 하나여야 한다.
SELECT session_id, count(DISTINCT ruleset_hash) AS hashes, min(ruleset_hash) AS ruleset_hash, min(created_at) AS first_log
FROM strategy_log WHERE session_type = 'PAPER' AND session_id IN (:ids) GROUP BY session_id ORDER BY session_id;
\echo @@before_t0
-- 기대: 0, 0. 하나라도 있으면 동시 시작이 성립하지 않는다(집계기가 거절한다). 신호 로그(판단)는 T0 전에 있을 수 있다 — 거래만 본다.
SELECT (SELECT count(*) FROM paper_trading."order" WHERE session_id IN (:ids) AND filled_at < :t0) AS orders_before_t0,
       (SELECT count(*) FROM paper_trading.position WHERE session_id IN (:ids) AND opened_at < :t0) AS positions_before_t0;
\echo @@running_total
SELECT count(*) AS paper_running FROM paper_trading.virtual_balance WHERE status = 'RUNNING';
ROLLBACK;
