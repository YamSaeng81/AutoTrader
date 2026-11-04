SELECT DISTINCT ON (l.coin_pair, date_trunc('hour', l.created_at)) l.*
FROM strategy_log l
WHERE l.signal = 'HOLD'
  AND l.created_at < :cutoff
  AND l.signal_price IS NOT NULL
  AND l.price_after_4h IS NULL
  AND NOT EXISTS (
        SELECT 1
        FROM (SELECT DISTINCT e.coin_pair, date_trunc('hour', e.created_at) AS hour_start
              FROM strategy_log e
              WHERE e.signal = 'HOLD'
                AND e.price_after_4h IS NOT NULL) d
        WHERE d.coin_pair = l.coin_pair
          AND d.hour_start = date_trunc('hour', l.created_at))
ORDER BY l.coin_pair, date_trunc('hour', l.created_at), l.created_at
LIMIT :limit
