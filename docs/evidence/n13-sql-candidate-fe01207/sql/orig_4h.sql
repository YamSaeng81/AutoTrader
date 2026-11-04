SELECT DISTINCT ON (l.coin_pair, date_trunc('hour', l.created_at)) l.*
FROM strategy_log l
WHERE l.signal = 'HOLD'
  AND l.created_at < :cutoff
  AND l.signal_price IS NOT NULL
  AND l.price_after_4h IS NULL
  AND NOT EXISTS (
        SELECT 1 FROM strategy_log e
        WHERE e.signal = 'HOLD'
          AND e.coin_pair = l.coin_pair
          AND e.created_at >= date_trunc('hour', l.created_at)
          AND e.created_at <  date_trunc('hour', l.created_at) + interval '1 hour'
          AND e.price_after_4h IS NOT NULL)
ORDER BY l.coin_pair, date_trunc('hour', l.created_at), l.created_at
LIMIT :limit
