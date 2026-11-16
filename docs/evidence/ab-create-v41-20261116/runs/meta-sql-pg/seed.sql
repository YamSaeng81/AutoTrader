-- v4.1 메타 SQL 확인용 시드(격리 로컬 PG v152-pg 의 새 DB v41meta = s5 사본, 운영 아님). 행 1 을 복사해 ID·상태·전략·파라미터만 바꾼다.
CREATE TEMP TABLE t AS SELECT * FROM paper_trading.virtual_balance WHERE id = 1;
INSERT INTO paper_trading.virtual_balance SELECT (x).* FROM (
  SELECT t #= hstore(ARRAY['id','status','strategy_name','coin_pair','timeframe','initial_capital','strategy_params','started_at'],
                     ARRAY[v.id::text, v.st, v.sn, v.cp, 'M15', '100000', v.sp, v.sa]) AS x
  FROM t, (VALUES
    (530, 'RUNNING', 'COMPOSITE_MEANREV_BB', 'KRW-BTC', '{"paperMaxInvestment":20000,"paperExecutionMode":"LIVE_ALIGNED"}', '2026-11-10 00:00:00+00'),
    (587, 'RUNNING', 'COMPOSITE_MTF_CONFIRMED', 'KRW-ETH', '{"paperMaxInvestment":20000,"paperExecutionMode":"LIVE_ALIGNED"}', '2026-11-10 00:00:00+00'),
    (588, 'RUNNING', 'COMPOSITE_MEANREV_BB', 'KRW-BTC', '{"paperMaxInvestment":20000,"paperExecutionMode":"LIVE_ALIGNED"}', '2026-11-16 08:25:52.815926+00'),
    (600, 'RUNNING', 'COMPOSITE_MTF_MOMENTUM', 'KRW-XRP', NULL, '2026-10-01 00:00:00+00'),
    (601, 'STOPPED', 'COMPOSITE_MEANREV_BB', 'KRW-ETH', '{"paperMaxInvestment":20000,"paperExecutionMode":"LIVE_ALIGNED"}', '2026-11-16 08:25:53.000001+00')
  ) AS v(id, st, sn, cp, sp, sa)) q;
SELECT id, status, strategy_name, coin_pair, timeframe FROM paper_trading.virtual_balance ORDER BY id;
