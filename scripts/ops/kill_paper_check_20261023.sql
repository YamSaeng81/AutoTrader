-- KILL 3차 집행 전후 대조 (2026-10-23, 읽기 전용) — PAPER #362·363·364·365·367·369·370·371. §6.16·SUPERTREND_VALIDATION_PREREG 개입 기록 #10
\set ON_ERROR_STOP on
BEGIN TRANSACTION READ ONLY;
\echo '== 대상 8세션 — 모두 PAPER(paper_trading.virtual_balance)이고 522~587 밖인지'
SELECT id, status, strategy_name, coin_pair, timeframe, started_at, stopped_at, initial_capital, total_krw,
       (id BETWEEN 522 AND 587) AS in_66
  FROM paper_trading.virtual_balance WHERE id IN (362,363,364,365,367,369,370,371) ORDER BY id;
\echo '== 같은 id 가 실자금 세션 표에 있는가(있어도 다른 세션이다 — 확인만)'
SELECT 'LIVE' AS kind, id, status, session_type FROM live_trading_session WHERE id IN (362,363,364,365,367,369,370,371)
UNION ALL SELECT 'DYNAMIC', id, status, trading_mode FROM dynamic_session WHERE id IN (362,363,364,365,367,369,370,371);
\echo '== 대상 세션의 열린 모의 포지션(정지하면 정상 매도 경로로 청산된다)'
SELECT session_id, count(*) FILTER (WHERE status = 'OPEN') AS open_pos, count(*) AS all_pos
  FROM paper_trading.position WHERE session_id IN (362,363,364,365,367,369,370,371) GROUP BY session_id ORDER BY session_id;
\echo '== 대상 세션의 주문 수(근거 보존 — 정지 뒤에도 같아야 한다, 청산 매도만 늘 수 있다)'
SELECT session_id, count(*) AS orders FROM paper_trading."order" WHERE session_id IN (362,363,364,365,367,369,370,371) GROUP BY session_id ORDER BY session_id;
\echo '== 최근 KILL 판정 기록'
SELECT evaluated_at, session_kind, session_id, verdict, code, trade_count, return_pct
  FROM kill_criteria_judgment WHERE session_kind = 'PAPER' AND session_id IN (362,363,364,365,367,369,370,371)
 ORDER BY evaluated_at DESC LIMIT 16;
\echo '== 검증 66세션·전체 RUNNING'
SELECT count(*) FILTER (WHERE id BETWEEN 522 AND 587 AND status = 'RUNNING') AS s66_running,
       count(*) FILTER (WHERE status = 'RUNNING') AS paper_running FROM paper_trading.virtual_balance;
ROLLBACK;
