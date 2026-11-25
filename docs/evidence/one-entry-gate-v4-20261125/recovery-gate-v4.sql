-- 1회 제한 시험 LIVE 세션 종료·복구 게이트 v4 (총괄 v1.245 §2). 읽기 전용. 운영 미실행.
-- v3(one-entry-gate-v3-20261125)에서 보유 판정 1줄만 바꿨다. v3·v2 원본은 바꾸지 않는다. 판정은 이 출력만으로 하지 않고 gate_check.py 가 한다.
-- 사용: psql -X -v ON_ERROR_STOP=1 -A -F '|' -P footer=off -v sid=<대상 ID> -f recovery-gate-v4.sql
-- 대상이 없어도 한 행이 나온다(found=f). 숫자가 아닌 sid 는 형 변환 오류로 멈춘다.
-- 제품 기준(c43e1f1a = d81c3f2f 같은 경로):
--   활성 6상태 TradingConstants.ACTIVE_ORDER_STATES · 알려진 주문 상태 9 = 활성 6 + FILLED·CANCELLED·FAILED
--   미입증 종결 = OrderExecutionEngine.isTerminalWithoutNoFillProof — FAILED 는 failure_code 가 NOT_EXECUTED_CODES 6개 밖(NULL 포함),
--                CANCELLED 는 cancel_confirmed_at NULL
--   회계 반영 대기 = V83 fill_accounting_pending · 반영 안 된 체결 매도 = V87 sell_applied_at NULL
--   정리 대기 = V87 position_cleanup.done_at NULL (PositionCleanupRepository.countPending)
--   정산 불일치 = V82 order_settlement_discrepancy.resolved_at NULL
--   보유 판정(v4) = position.status IN ('OPEN','CLOSING') — 수량이 0이어도 센다. CLOSED 의 size 는 보지 않는다:
--                제품 LIVE 전량 매도(LiveTradingService.finalizeSellPosition)는 CLOSED 로 닫되 size 를 체결 수량으로 남긴다.
--                열 이름 open_or_residual_positions 는 호환용이며 뜻은 '제품이 보유/청산 중으로 관리하는 포지션 수'다.
--                거래소 실제 잔량을 확정하지 않는다(A3·계좌 대조 범위).
--   🔴 settlement_final 은 제품이 TRUE 로 세우지 않는다(OrderSettlementService 223행) — 차단 근거로 쓰지 않고 참고만 한다.
WITH t AS (SELECT :'sid'::bigint AS sid),
o AS (SELECT o.* FROM "order" o, t WHERE o.session_kind = 'LIVE' AND o.session_id = t.sid),
p AS (SELECT p.* FROM position p, t WHERE p.session_kind = 'LIVE' AND p.session_id = t.sid)
SELECT
  (s.id IS NOT NULL)                                   AS found,
  t.sid                                                AS target_sid,
  s.status, s.session_type, s.strategy_type, s.coin_pair, s.timeframe,
  s.initial_capital, s.invest_ratio, s.max_investment,
  jsonb_typeof(s.strategy_params -> 'maxEntries')      AS max_entries_type,
  s.strategy_params ->> 'maxEntries'                   AS max_entries_text,
  (s.started_at IS NOT NULL)                           AS started,
  (s.stopped_at IS NOT NULL)                           AS stopped_at_set,
  -- 차단(block): 0 이 아니면 FAIL
  (SELECT count(*) FROM o WHERE o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')) AS active_orders,
  (SELECT count(*) FROM o WHERE o.state IS NULL OR o.state NOT IN
     ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED','FILLED','CANCELLED','FAILED')) AS unknown_order_states,
  (SELECT count(*) FROM o WHERE o.fill_accounting_pending)                                   AS fill_accounting_pending,
  (SELECT count(*) FROM o WHERE UPPER(o.side) = 'SELL' AND o.state = 'FILLED' AND o.sell_applied_at IS NULL) AS sell_unapplied,
  (SELECT count(*) FROM p WHERE p.status IN ('OPEN','CLOSING'))                             AS open_or_residual_positions,
  (SELECT count(*) FROM p WHERE p.status IS NULL OR p.status NOT IN ('OPEN','CLOSING','CLOSED')) AS unknown_position_status,
  (SELECT count(*) FROM position_cleanup c, t WHERE c.session_kind = 'LIVE' AND c.session_id = t.sid AND c.done_at IS NULL) AS cleanup_pending,
  (SELECT count(*) FROM o WHERE UPPER(o.side) = 'BUY')                                       AS buy_rows,
  -- 보류(hold): 0 이 아니면 사람 확인 — 자동 통과하지 않는다
  (SELECT count(*) FROM o WHERE (o.state = 'FAILED' AND (o.failure_code IS NULL OR o.failure_code NOT IN
       ('WITHDRAWN_STOPPED','WITHDRAWN_OWNER_LOST','WITHDRAWN_DISPATCH_LOST','RISK_REJECTED','CLIENT_UNAVAILABLE','EXCHANGE_REJECTED')))
     OR (o.state = 'CANCELLED' AND o.cancel_confirmed_at IS NULL))                            AS unproven_terminal,
  (SELECT count(*) FROM order_settlement_discrepancy d JOIN o ON o.id = d.order_id WHERE d.resolved_at IS NULL) AS discrepancy_open,
  -- 참고(info): 판정에 쓰지 않는다
  (SELECT count(*) FROM o WHERE UPPER(o.side) = 'SELL')                                      AS sell_rows,
  (SELECT count(*) FROM position_cleanup c, t WHERE c.session_kind = 'LIVE' AND c.session_id = t.sid AND c.done_at IS NOT NULL) AS cleanup_done,
  (SELECT count(*) FROM o WHERE o.settlement_final = false AND o.fee_source IS NOT NULL AND o.exchange_order_id IS NOT NULL) AS settlement_recheck,
  (SELECT count(*) FROM o WHERE o.state IN ('INTENT_READY','SUBMITTING','UNKNOWN'))          AS unresolved_submissions,
  'END'                                                AS end_mark
FROM t LEFT JOIN live_trading_session s ON s.id = t.sid;
