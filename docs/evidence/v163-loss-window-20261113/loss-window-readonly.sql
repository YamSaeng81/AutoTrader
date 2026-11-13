-- v1.163 / 406. Read-only diagnosis of the DYNAMIC REAL 30d gate.
-- Source formula: 88f06345 OrderRepository.sumDynamicRealSessionRealizedLossSince.
-- Eligibility after this read is still decided by the approved U0 preflight.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
WITH
clock AS (SELECT now() AS asof),
losses AS (
    SELECT o.id AS order_id, o.session_id, o.position_id, o.sell_applied_at,
           o.applied_executed_funds - COALESCE(o.applied_paid_fee, 0)
               - o.filled_quantity * p.avg_price AS pnl
    FROM "order" o
    JOIN position p ON p.id = o.position_id
    CROSS JOIN clock c
    WHERE o.side = 'SELL'
      AND o.session_id IS NOT NULL
      AND o.session_kind = 'DYNAMIC'
      AND o.sell_applied_at IS NOT NULL
      AND o.sell_applied_at >= c.asof - interval '720 hours'
      AND o.applied_executed_funds IS NOT NULL
),
negative AS (SELECT * FROM losses WHERE pnl < 0),
capital AS (
    SELECT count(*) AS running_count,
           COALESCE(SUM(initial_capital) FILTER (WHERE initial_capital > 0), 0) AS running_capital
    FROM dynamic_session
    WHERE status = 'RUNNING' AND trading_mode <> 'PAPER'
),
summary AS (
    SELECT count(*) AS loss_rows, COALESCE(SUM(pnl), 0) AS loss_krw,
           max(sell_applied_at + interval '720 hours') AS last_expiry
    FROM negative
)
SELECT c.asof AS asof_utc,
       c.asof AT TIME ZONE 'Asia/Seoul' AS asof_kst,
       cap.running_count AS running_dynamic_real_count,
       cap.running_capital AS running_dynamic_real_capital,
       s.loss_rows, s.loss_krw,
       s.last_expiry AS last_expiry_utc,
       s.last_expiry AT TIME ZONE 'Asia/Seoul' AS last_expiry_kst,
       COALESCE((
           SELECT jsonb_agg(jsonb_build_object(
               'order_id', n.order_id,
               'session_id', n.session_id,
               'position_id', n.position_id,
               'loss_krw', n.pnl,
               'sell_applied_at_utc', n.sell_applied_at,
               'sell_applied_at_kst', n.sell_applied_at AT TIME ZONE 'Asia/Seoul',
               'expiry_utc', n.sell_applied_at + interval '720 hours',
               'expiry_kst', (n.sell_applied_at + interval '720 hours') AT TIME ZONE 'Asia/Seoul',
               'future_timestamp', n.sell_applied_at > c.asof
           ) ORDER BY n.sell_applied_at, n.order_id)
           FROM negative n
       ), '[]'::jsonb) AS contributors
FROM clock c CROSS JOIN capital cap CROSS JOIN summary s;
ROLLBACK;
