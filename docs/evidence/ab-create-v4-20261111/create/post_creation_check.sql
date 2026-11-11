-- 청산 A/B 8세션 생성 직후 확인 v3 — 초안, 실행 금지(생성 승인 전). 생성 뒤 읽기 전용으로만 쓴다. (총괄 v1.149 §4-2)
--
-- 실행 형식(ab_complete.py 가 이 출력만 읽는다 — 손으로 고친 파일을 확인 근거로 쓰지 않는다):
--   psql -X -q --csv -v ON_ERROR_STOP=1 -v ids='<8개 id, 쉼표>' -v t0='<draft 의 t0 ISO>' -f post_creation_check.sql > db_check.out
-- v2 → v3: 구획 표지(@@)를 모든 결과 앞에 두고, t0 를 문자열 변수(:'t0')로 넘겨 timestamptz 로 바꾼다(v2 의 :t0 는 따옴표가 없어 오류였다).
--          max_investment 를 strategy_params 에서 뽑는다. 522~587 은 넣지 않는다(성과 비열람 계약).
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
\echo @@db_now
SELECT now() AS db_now;
\echo @@stored
-- 기대: 8행, 모두 RUNNING·M15·100000·0.8·24, strategy_params = {"paperMaxInvestment":20000,"paperExecutionMode":"LIVE_ALIGNED"},
--       A 의 stop_loss_pct NULL, B 의 stop_loss_pct 5.00, started_at < t0.
SELECT id, strategy_name, coin_pair, timeframe, status, initial_capital, invest_ratio, stop_loss_pct, max_hold_hours,
       strategy_params::jsonb ->> 'paperMaxInvestment' AS max_investment,
       strategy_params::text AS params_json, started_at, started_at < :'t0'::timestamptz AS before_t0
FROM paper_trading.virtual_balance WHERE id IN (:ids) ORDER BY id;
\echo @@fingerprint
-- 규칙 지문은 세션 행에 없고 첫 평가의 신호 로그에 실린다(평가 주기 60초). 세션마다 정확히 하나여야 한다(없음·복수는 거절).
SELECT session_id, count(DISTINCT ruleset_hash) AS hashes, min(ruleset_hash) AS ruleset_hash, min(created_at) AS first_log
FROM strategy_log WHERE session_type = 'PAPER' AND session_id IN (:ids) GROUP BY session_id ORDER BY session_id;
\echo @@before_t0
-- 기대: 0, 0. 하나라도 있으면 동시 시작이 성립하지 않는다. 신호 로그(판단)는 T0 전에 있을 수 있다 — 거래만 본다.
SELECT (SELECT count(*) FROM paper_trading."order" WHERE session_id IN (:ids) AND filled_at < :'t0'::timestamptz) AS orders_before_t0,
       (SELECT count(*) FROM paper_trading.position WHERE session_id IN (:ids) AND opened_at < :'t0'::timestamptz) AS positions_before_t0;
\echo @@end
ROLLBACK;
