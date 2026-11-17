-- 격리 PG 시험 자료(시험 전용 DB 에만). 판단 기준 AT = 2026-11-17 05:10:00Z. 봉 값은 형식용(성과 아님).
DELETE FROM market_data_cache WHERE coin_pair IN ('KRW-BTC', 'KRW-ETH') AND timeframe IN ('H1', 'M15');
INSERT INTO market_data_cache (time, coin_pair, timeframe, open, high, low, close, volume)
SELECT g, 'KRW-BTC', 'H1', 100, 101, 99, 100, 1 FROM generate_series('2026-10-26 00:00+00'::timestamptz, '2026-11-17 05:00+00', interval '1 hour') g;
INSERT INTO market_data_cache (time, coin_pair, timeframe, open, high, low, close, volume)
SELECT g, c, 'M15', 100, 101, 99, 100, 1 FROM (VALUES ('KRW-BTC'), ('KRW-ETH')) v(c),
       generate_series('2026-11-11 21:00+00'::timestamptz, '2026-11-17 05:00+00', interval '15 minutes') g;
