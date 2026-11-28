WITH have AS (
 SELECT time FROM candle_data
 WHERE coin_pair='KRW-BTC' AND timeframe='H1'
   AND time>=now()-interval '500 hours' AND time+interval '1 hour'<=now()
), slots AS (
 SELECT generate_series(min(time),max(time),interval '1 hour') AS time FROM have
)
SELECT COALESCE(json_agg(s.time ORDER BY s.time),'[]'::json)
FROM slots s LEFT JOIN have h ON h.time=s.time WHERE h.time IS NULL;
