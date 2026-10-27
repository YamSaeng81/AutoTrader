-- TIME_STOP 텔레그램 전송 실패(10-27 02:00 KST = 10-26 17:00Z) 읽기 전용 대조 (2026-10-27). 조사 결과는 LIVE_RISK_IMPLEMENTATION_REVIEW.md §6.16.
-- 실행: git show <SHA>:scripts/ops/time_stop_readonly_20261027.sql | docker compose -f docker-compose.prod.yml exec -T db psql -X -U trader -d crypto_auto_trader -f -
-- 전후 15분 모든 유형 전송 이력 + 그 구간 TIME_STOP 실패 세션의 포지션·매도(session_kind 로 엔진 구분 — LIVE·DYNAMIC id 가 겹칠 수 있다). ROLLBACK 으로 끝난다.
\set ON_ERROR_STOP on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '15s';

SELECT id, type, session_label, success,
       sent_at AT TIME ZONE 'Asia/Seoul' AS sent_kst,
       char_length(message_text) AS chars,
       left(message_text, 240) AS message_head
  FROM telegram_notification_log
 WHERE sent_at >= TIMESTAMPTZ '2026-10-26 16:45:00+00'
   AND sent_at <  TIMESTAMPTZ '2026-10-26 17:15:00+00'
 ORDER BY sent_at, id;

WITH target_sessions AS (
  SELECT DISTINCT substring(session_label FROM '([0-9]+)$')::bigint AS session_id
    FROM telegram_notification_log
   WHERE type = 'TIME_STOP' AND NOT success
     AND sent_at >= TIMESTAMPTZ '2026-10-26 16:45:00+00'
     AND sent_at <  TIMESTAMPTZ '2026-10-26 17:15:00+00'
)
SELECT p.session_kind, p.session_id, p.id AS position_id, p.coin_pair,
       p.status AS position_status, p.exit_reason,
       p.closed_at AT TIME ZONE 'Asia/Seoul' AS closed_kst,
       o.id AS sell_order_id, o.state AS order_state, o.filled_quantity,
       o.executed_funds,
       o.sell_applied_at AT TIME ZONE 'Asia/Seoul' AS sell_applied_kst
  FROM position p
  JOIN target_sessions t ON t.session_id = p.session_id
  LEFT JOIN "order" o ON o.position_id = p.id AND o.side = 'SELL'
 WHERE p.opened_at < TIMESTAMPTZ '2026-10-26 17:15:00+00'
   AND (p.closed_at IS NULL OR p.closed_at >= TIMESTAMPTZ '2026-10-26 16:45:00+00')
 ORDER BY p.session_kind, p.session_id, p.id, o.id;

ROLLBACK;
