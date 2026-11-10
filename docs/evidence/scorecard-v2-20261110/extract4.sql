-- 성적표 v2 보충 추출(2026-11-10, 총괄 v1.142 §110). 읽기 전용 스냅숏 하나, 쓰기 문장 없음, ROLLBACK 으로 끝.
-- (1) ruleset_snapshot 전체: 지문별 파라미터·처음 기록 시각 — 손절 조임 제거(exit.slTightenOnLoss) 전후를 실행 기록으로 나누기 위함. 성과 열 없음.
-- (2) 보유 비교용 마감된 H1 종가: 봉 시작+1h <= 경계인 마지막 봉. 사전 등록 522~587 제외.
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '300s';
\echo @@meta4
COPY (SELECT to_char(transaction_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS snapshot_ts) TO STDOUT WITH (FORMAT csv, HEADER);
\echo @@ruleset_snapshot
COPY (SELECT ruleset_hash, engine, params_text, first_seen_at AT TIME ZONE 'UTC' AS first_seen_at FROM ruleset_snapshot ORDER BY first_seen_at, ruleset_hash) TO STDOUT WITH (FORMAT csv, HEADER);
\echo @@bh_closed
COPY (WITH s AS (
   SELECT 'PAPER' AS engine, id, coin_pair, started_at, coalesce(stopped_at, transaction_timestamp()) AS ended_at FROM paper_trading.virtual_balance WHERE id NOT BETWEEN 522 AND 587 AND started_at IS NOT NULL
   UNION ALL SELECT CASE WHEN trading_mode = 'PAPER' THEN 'DYN_PAPER' ELSE 'DYN_REAL' END, id, 'KRW-BTC', started_at, coalesce(stopped_at, transaction_timestamp()) FROM public.dynamic_session WHERE started_at IS NOT NULL
   UNION ALL SELECT 'LIVE', id, coin_pair, started_at, coalesce(stopped_at, transaction_timestamp()) FROM public.live_trading_session WHERE started_at IS NOT NULL)
 SELECT s.engine, s.id, s.coin_pair, s.started_at AT TIME ZONE 'UTC' AS started_at, s.ended_at AT TIME ZONE 'UTC' AS ended_at,
   a.close AS close_start, a.time AT TIME ZONE 'UTC' AS bar_start_open, b.close AS close_end, b.time AT TIME ZONE 'UTC' AS bar_end_open
 FROM s
 LEFT JOIN LATERAL (SELECT c.close, c.time FROM market_data_cache c WHERE c.coin_pair = s.coin_pair AND c.timeframe = 'H1' AND c.time + interval '1 hour' <= s.started_at ORDER BY c.time DESC LIMIT 1) a ON true
 LEFT JOIN LATERAL (SELECT c.close, c.time FROM market_data_cache c WHERE c.coin_pair = s.coin_pair AND c.timeframe = 'H1' AND c.time + interval '1 hour' <= s.ended_at ORDER BY c.time DESC LIMIT 1) b ON true
 ORDER BY 1, 2) TO STDOUT WITH (FORMAT csv, HEADER);
ROLLBACK;
