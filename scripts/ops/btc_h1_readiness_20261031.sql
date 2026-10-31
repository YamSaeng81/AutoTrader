-- SID 203 입력 자료 준비 — 운영 KRW-BTC H1 읽기 전용 확인 (리뷰 2026-10-31 "실제 BTC 입력 자료"). LIMITED_LIVE_TEST_PLAN.md §5-9 10.
-- 실행: git show <SHA>:scripts/ops/btc_h1_readiness_20261031.sql | docker compose -f docker-compose.prod.yml exec -T db psql -X -U trader -d crypto_auto_trader -f -
-- 무엇을 보나(코드 기준):
--   · 전략 평가는 market_data_cache 의 최근 500시간(CANDLE_LOOKBACK)을 읽는다 — LiveTradingService.loadRecentCandles.
--   · DAILY_SMA20 은 봉 시작 23:00 UTC(= 08:00 KST 시작, 09:00 KST 마감) H1 봉을 일봉 마감 봉으로 쓰고, 최근 20일 중 하루라도 없으면 HOLD.
--   · 수집은 MarketDataSyncService(60초)가 RUNNING 세션의 (코인, 봉)과 위험 관문 BTC 봉(RUNNING LIVE·DYNAMIC 의 봉 단위마다 KRW-BTC)만 한다.
--     CREATED 인 SID 203 자체는 수집을 유지하지 않는다 — B4 가 지금 KRW-BTC H1 을 유지하는 세션을 센다.
-- 한 번 조회는 그 시각의 상태다. "계속 갱신"은 1시간 넘게 사이를 두고 두 번 돌려 B1 의 최신 완료 봉이 앞으로 갔는지로 본다. ROLLBACK 으로 끝난다.
\set ON_ERROR_STOP on
\pset null '-'
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15s';

\echo '== B1 최신 봉 — 최신 완료 봉(마감 ≤ 지금)이 직전 정시에 시작한 봉인지 · 형성 중 봉 · 지연(분 = 지금 − 최신 완료 봉 마감)'
SELECT to_char(now() AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI:SS') AS now_kst,
       to_char(max(time) FILTER (WHERE time + interval '1 hour' <= now()) AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI') AS last_done_open_kst,
       to_char((date_trunc('hour', now()) - interval '1 hour') AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI') AS expect_open_kst,
       max(time) FILTER (WHERE time + interval '1 hour' <= now()) = date_trunc('hour', now()) - interval '1 hour' AS up_to_date,
       floor(extract(epoch FROM now() - (max(time) FILTER (WHERE time + interval '1 hour' <= now()) + interval '1 hour')) / 60)::int AS lag_min,
       to_char(max(time) AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI') AS last_any_open_kst,
       count(*) FILTER (WHERE time >= now() - interval '24 hours') AS rows_24h
  FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1';

\echo '== B2 운영 조회 범위(최근 500시간)의 완료 봉 — 기대 499 · 있음 · 빠짐 · 값 이상(종가 ≤ 0·저가 > 고가) · 빠진 봉 시작 시각(KST, 앞 30개)'
WITH e AS (SELECT g AS t FROM generate_series(date_trunc('hour', now()) - interval '499 hours', date_trunc('hour', now()) - interval '1 hour', interval '1 hour') g),
c AS (SELECT time, open, high, low, close FROM market_data_cache
       WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1' AND time >= date_trunc('hour', now()) - interval '499 hours' AND time + interval '1 hour' <= now())
SELECT (SELECT count(*) FROM e) AS expected, (SELECT count(*) FROM e JOIN c ON c.time = e.t) AS present,
       (SELECT count(*) FROM e LEFT JOIN c ON c.time = e.t WHERE c.time IS NULL) AS missing,
       (SELECT count(*) FROM c WHERE close <= 0 OR low > high OR close > high OR close < low) AS bad_values,
       (SELECT count(*) FROM c WHERE time <> date_trunc('hour', time)) AS off_hour,
       coalesce((SELECT string_agg(to_char(t AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI'), ' ' ORDER BY t)
                   FROM (SELECT e.t FROM e LEFT JOIN c ON c.time = e.t WHERE c.time IS NULL ORDER BY e.t LIMIT 30) m), '-') AS missing_open_kst;

\echo '== B3 최근 20일 일봉 마감 봉(시작 23:00 UTC = 08:00 KST, 마감 09:00 KST) — 날마다 있음·종가 · 끝 줄 = 있음 개수/20'
WITH last_close AS (
  -- 마감이 지난 가장 최근 23:00 UTC 시작 봉 시각
  SELECT CASE WHEN date_trunc('day', now() AT TIME ZONE 'UTC') + interval '23 hours' + interval '1 hour' <= (now() AT TIME ZONE 'UTC')
              THEN date_trunc('day', now() AT TIME ZONE 'UTC') + interval '23 hours'
              ELSE date_trunc('day', now() AT TIME ZONE 'UTC') - interval '1 hour' END AS t_utc),
d AS (SELECT k, (l.t_utc - make_interval(days => k)) AT TIME ZONE 'UTC' AS t FROM last_close l, generate_series(0, 19) k)
SELECT close_kst, present, close FROM (
  SELECT d.k AS ord, to_char((d.t + interval '1 hour') AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') AS close_kst,
         (c.close IS NOT NULL)::text AS present, c.close::text AS close
    FROM d LEFT JOIN market_data_cache c ON c.coin_pair = 'KRW-BTC' AND c.timeframe = 'H1' AND c.time = d.t
  UNION ALL
  SELECT 99, '합계', count(c.close) || '/20', '-'
    FROM d LEFT JOIN market_data_cache c ON c.coin_pair = 'KRW-BTC' AND c.timeframe = 'H1' AND c.time = d.t) z
 ORDER BY ord;

\echo '== B4 지금 KRW-BTC H1 수집을 유지하는 RUNNING 세션 — LIVE·DYNAMIC 은 봉이 H1 이면 코인과 무관하게(BTC 관문 봉), 모의는 KRW-BTC·H1 일 때만'
SELECT 'LIVE' AS kind, id, coin_pair, timeframe, session_type AS detail FROM live_trading_session WHERE status = 'RUNNING' AND timeframe = 'H1'
UNION ALL
SELECT 'DYNAMIC', id, current_coin_pair, timeframe, strategy_type FROM dynamic_session WHERE status = 'RUNNING' AND timeframe = 'H1'
UNION ALL
SELECT 'PAPER', id, coin_pair, timeframe, '-' FROM paper_trading.virtual_balance WHERE status = 'RUNNING' AND coin_pair = 'KRW-BTC' AND timeframe = 'H1'
 ORDER BY 1, 2;
SELECT count(*) AS btc_h1_maintainers FROM (
  SELECT id FROM live_trading_session WHERE status = 'RUNNING' AND timeframe = 'H1'
  UNION ALL SELECT id FROM dynamic_session WHERE status = 'RUNNING' AND timeframe = 'H1'
  UNION ALL SELECT id FROM paper_trading.virtual_balance WHERE status = 'RUNNING' AND coin_pair = 'KRW-BTC' AND timeframe = 'H1') x;
SELECT id, status, coin_pair, timeframe, strategy_type FROM live_trading_session WHERE id = 203;

ROLLBACK;
\echo '== 끝'
