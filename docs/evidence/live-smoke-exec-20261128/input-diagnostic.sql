SELECT json_build_object(
 'db_clock', clock_timestamp(),
 'required_closed_start', date_trunc('hour', now()-interval '2 minutes')-interval '1 hour',
 'stores', (
  SELECT json_agg(x) FROM (
   SELECT 'candle_data' AS source, count(*) AS total,
          max(time) AS last_time,
          max(time) FILTER (WHERE time+interval '1 hour'<=now()) AS last_closed,
          count(*) FILTER (WHERE time>=now()-interval '30 days' AND time<=now()) AS last30d,
          count(*) FILTER (WHERE time>=now()-interval '24 hours' AND time<=now()) AS last24h
   FROM candle_data WHERE coin_pair='KRW-BTC' AND timeframe='H1'
   UNION ALL
   SELECT 'market_data_cache', count(*), max(time),
          max(time) FILTER (WHERE time+interval '1 hour'<=now()),
          count(*) FILTER (WHERE time>=now()-interval '30 days' AND time<=now()),
          count(*) FILTER (WHERE time>=now()-interval '24 hours' AND time<=now())
   FROM market_data_cache WHERE coin_pair='KRW-BTC' AND timeframe='H1'
  ) x
 )
);
