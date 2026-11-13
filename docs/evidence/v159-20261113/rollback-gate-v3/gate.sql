-- 총괄 v1.159 §5 — 구판(b5cd1d0) 복구 전 상태 기반 관문 3판: 읽기 전용 집계 (2026-11-13). 초안 — 운영 실행은 승인 뒤에만.
-- 2판(v153 h-rollback/v2/gate.sql, 원문 보존)의 새 모드 집계에 실계좌 진행 상태·미정산·정리 대기·수량/잔액 대조를 더했다.
-- 미정산 정의는 RUNBOOK F3 의 개수식과 같다(미종결·회계 반영 대기·반영값/보고값 없음·보고≠반영·정리 대기·미해결 정산 불일치).
-- 수량 대조: RUNNING LIVE·DYNAMIC REAL 세션마다 주문 순체결량(매수 − 매도) = OPEN·CLOSING 포지션 수량 합.
-- 잔액 대조: RUNNING LIVE 세션마다 저장 잔액 = 초기자본 − Σ매수(요청액 − round(요청액 − 반영 대금 − 반영 수수료, 2)) + Σ매도 round(반영 대금 − 반영 수수료, 2)
--            (F3 잔액 기대식 v1.33 을 세션 초기자본으로 일반화). DYNAMIC REAL 잔액은 이 식의 범위 밖이라 세지 않고 real_running 으로만 알린다.
-- 쓰기 없음: 읽기 전용 트랜잭션 안에서 SELECT 만 하고 ROLLBACK 한다.
\set ON_ERROR_STOP 1
BEGIN TRANSACTION READ ONLY;
SELECT (EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'paper_trading' AND table_name = 'position' AND column_name = 'exit_decided_at'))::int AS v96
\gset
WITH nm AS (
    SELECT id, status FROM paper_trading.virtual_balance
    WHERE strategy_params IS NOT NULL AND strategy_params ->> 'paperExecutionMode' = 'LIVE_ALIGNED'
), pos AS (
    SELECT p.* FROM paper_trading.position p JOIN nm ON nm.id = p.session_id
), rs AS (   -- 실행 중 실계좌 세션
    SELECT 'LIVE' AS kind, id, initial_capital, available_krw FROM live_trading_session WHERE status = 'RUNNING'
    UNION ALL
    SELECT 'DYNAMIC', id, initial_capital, available_krw FROM dynamic_session WHERE status = 'RUNNING' AND trading_mode <> 'PAPER'
), qty AS (
    SELECT rs.kind, rs.id,
        (SELECT coalesce(sum(o.filled_quantity) FILTER (WHERE o.side = 'BUY'), 0) - coalesce(sum(o.filled_quantity) FILTER (WHERE o.side = 'SELL'), 0)
                  FROM "order" o WHERE o.session_kind = rs.kind AND o.session_id = rs.id) AS net,
        coalesce((SELECT sum(p.size) FROM position p WHERE p.session_kind = rs.kind AND p.session_id = rs.id AND p.status IN ('OPEN', 'CLOSING')), 0) AS held
    FROM rs
), bal AS (
    SELECT rs.id, rs.available_krw,
        rs.initial_capital
          - coalesce((SELECT sum(o.quantity - round(o.quantity - o.applied_executed_funds - o.applied_paid_fee, 2)) FROM "order" o
                      WHERE o.session_kind = 'LIVE' AND o.session_id = rs.id AND o.side = 'BUY' AND o.filled_quantity > 0), 0)
          + coalesce((SELECT sum(round(o.applied_executed_funds - o.applied_paid_fee, 2)) FROM "order" o
                      WHERE o.session_kind = 'LIVE' AND o.session_id = rs.id AND o.side = 'SELL' AND o.filled_quantity > 0), 0) AS expect
    FROM rs WHERE rs.kind = 'LIVE'
)
SELECT 'GATE'
    || '|v96=' || :v96
    || '|created=' || (SELECT count(*) FROM nm)
    || '|running=' || (SELECT count(*) FROM nm WHERE status = 'RUNNING')
    || '|odd_session=' || (SELECT count(*) FROM nm WHERE status IS NULL OR status NOT IN ('RUNNING', 'STOPPED'))
    || '|open=' || (SELECT count(*) FROM pos WHERE status = 'OPEN')
    || '|closing=' || (SELECT count(*) FROM pos WHERE status = 'CLOSING')
    || '|odd_position=' || (SELECT count(*) FROM pos WHERE status IS NULL OR status NOT IN ('OPEN', 'CLOSING', 'CLOSED'))
    || '|stopped_with_open=' || (SELECT count(*) FROM pos JOIN nm ON nm.id = pos.session_id WHERE nm.status <> 'RUNNING' AND pos.status = 'OPEN')
    || '|unresolved_orders=' || (SELECT count(*) FROM "order" WHERE state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED'))
    || '|acct_pending=' || (SELECT count(*) FROM "order" WHERE fill_accounting_pending)
    || '|applied_missing=' || (SELECT count(*) FROM "order" WHERE coalesce(filled_quantity, 0) > 0
                                 AND (applied_executed_funds IS NULL OR applied_paid_fee IS NULL OR reported_executed_funds IS NULL OR reported_paid_fee IS NULL))
    || '|reported_ne_applied=' || (SELECT count(*) FROM "order" WHERE coalesce(filled_quantity, 0) > 0
                                 AND (reported_executed_funds <> applied_executed_funds OR reported_paid_fee <> applied_paid_fee))
    || '|cleanup=' || (SELECT count(*) FROM position_cleanup WHERE done_at IS NULL)
    || '|discrepancy=' || (SELECT count(*) FROM order_settlement_discrepancy WHERE resolved_at IS NULL)
    || '|real_closing=' || (SELECT count(*) FROM position WHERE status = 'CLOSING' AND session_kind IN ('LIVE', 'DYNAMIC'))
    || '|real_running=' || (SELECT count(*) FROM rs)
    || '|real_held=' || (SELECT count(*) FROM position WHERE status IN ('OPEN', 'CLOSING') AND session_kind IN ('LIVE', 'DYNAMIC') AND size > 0)
    || '|qty_mismatch=' || (SELECT count(*) FROM qty WHERE net <> held)
    || '|balance_mismatch=' || (SELECT count(*) FROM bal WHERE available_krw IS DISTINCT FROM expect)
    AS gate;
\if :v96
SELECT 'DECIDED|closing_without_decided_at='
    || (SELECT count(*) FROM paper_trading.position p
          JOIN paper_trading.virtual_balance v ON v.id = p.session_id
         WHERE v.strategy_params ->> 'paperExecutionMode' = 'LIVE_ALIGNED' AND p.status = 'CLOSING' AND p.exit_decided_at IS NULL)
    AS decided;
\else
SELECT 'DECIDED|closing_without_decided_at=unknown' AS decided;
\endif
ROLLBACK;
