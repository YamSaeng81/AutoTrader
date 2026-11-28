SELECT COALESCE(json_agg(x ORDER BY x.created_at DESC),'[]'::json)
FROM (
  SELECT l.created_at, l.signal, left(l.reason,600) AS reason,
         l.was_executed, left(l.blocked_reason,300) AS blocked_reason,
         l.ruleset_hash, s.params_text
  FROM strategy_log l
  LEFT JOIN ruleset_snapshot s ON s.ruleset_hash=l.ruleset_hash
  WHERE l.session_type='LIVE' AND l.session_id=204
  ORDER BY l.id DESC LIMIT 3
) x;
