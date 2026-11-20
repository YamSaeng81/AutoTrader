-- 등록 세션 읽기 검토안(총괄 v1.203 §3-3) — 실행 금지. 승인 전에는 어떤 DB 에도 돌리지 않는다.
-- 대상 스키마: d81c3f2f / 운영 0e1d2a02 (두 커밋의 관련 엔티티 열 이름이 같음 — README §2 대조표).
-- 호출 예(검토용): psql -X -q -v ON_ERROR_STOP=1 -v live_ids='{203}' -v dyn_ids='{}' -v window_from='2026-11-20T00:00:00Z' -f read.sql
--   live_ids·dyn_ids = 등록표 v3 의 kind 별 id 배열(해시 고정 파일에서 생성), window_from = 기준 관측의 거래소 앞 읽기 시작(db 축으로 해석).
-- 출력: 탭 구분 한 줄 한 레코드, 첫 칸이 레코드 종류. 형식은 README §3. paper_trading 스키마(PAPER 세션 522~587 포함)는 읽지 않는다.
\set ON_ERROR_STOP 1
\pset format unaligned
\pset tuples_only on
\pset fieldsep '\t'
\pset null '\\N'

BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
-- 첫 문장에서 스냅숏이 고정된다. now() = 트랜잭션 시작(db 축), clock_timestamp() = 실제 벽시계(db 축).
SELECT 'META', 'v1', now(), clock_timestamp(), pg_current_snapshot()::text,
       (SELECT max(version)::int FROM flyway_schema_history WHERE success), current_setting('transaction_isolation');

-- ── 1단계: 실자금 최소 식별 목록(금액·가격·대금 열 없음) ──────────────────
-- 실자금 세션: LIVE 전체(모드 열 없음 — LIVE 는 REAL 만) + DYNAMIC 중 trading_mode 가 정확히 'PAPER' 가 아닌 것(제품 규칙과 같음).
SELECT 'ID_S', 'LIVE', id, status FROM live_trading_session WHERE status <> 'STOPPED' OR id = ANY(:'live_ids'::bigint[])
UNION ALL
SELECT 'ID_S', 'DYNAMIC', id, status FROM dynamic_session
 WHERE trading_mode IS DISTINCT FROM 'PAPER' AND (status <> 'STOPPED' OR id = ANY(:'dyn_ids'::bigint[]))
ORDER BY 2, 3;
-- 실자금 주문: 진행 중 또는 창 안 생성. 소유 세션이 없거나(session_id NULL·행 없음) DYNAMIC PAPER 가 아닌 모든 주문.
SELECT 'ID_O', o.id, o.session_kind, o.session_id, o.state,
       CASE WHEN o.session_id IS NULL THEN 'NO_SESSION_ID'
            WHEN o.session_kind = 'LIVE' AND NOT EXISTS (SELECT 1 FROM live_trading_session s WHERE s.id = o.session_id) THEN 'NO_SESSION_ROW'
            WHEN o.session_kind = 'DYNAMIC' AND NOT EXISTS (SELECT 1 FROM dynamic_session s WHERE s.id = o.session_id) THEN 'NO_SESSION_ROW'
            WHEN o.session_kind NOT IN ('LIVE', 'DYNAMIC') THEN 'UNKNOWN_KIND'
            ELSE 'OK' END
  FROM "order" o
 WHERE (o.state IN ('PENDING','INTENT_READY','SUBMITTING','SUBMITTED','PARTIAL_FILLED','UNKNOWN') OR o.created_at >= :'window_from'::timestamptz)
   AND NOT (o.session_kind = 'DYNAMIC' AND EXISTS (SELECT 1 FROM dynamic_session s WHERE s.id = o.session_id AND s.trading_mode = 'PAPER'))
 ORDER BY o.id;
-- 실자금 포지션: OPEN/CLOSING(그 밖 상태 문자열도 CLOSED 가 아니면 포함).
SELECT 'ID_P', p.id, p.session_kind, p.session_id, p.status
  FROM position p
 WHERE p.status <> 'CLOSED'
   AND NOT (p.session_kind = 'DYNAMIC' AND EXISTS (SELECT 1 FROM dynamic_session s WHERE s.id = p.session_id AND s.trading_mode = 'PAPER'))
 ORDER BY p.id;

-- 게이트: 등록 밖 ID 가 하나라도 있으면 2단계(금액 열람) 없이 끝낸다.
SELECT (count(*) = 0) AS gate_go FROM (
  SELECT 'LIVE' k, id FROM live_trading_session WHERE status <> 'STOPPED' AND NOT id = ANY(:'live_ids'::bigint[])
  UNION ALL SELECT 'DYNAMIC', id FROM dynamic_session
   WHERE trading_mode IS DISTINCT FROM 'PAPER' AND status <> 'STOPPED' AND NOT id = ANY(:'dyn_ids'::bigint[])
  UNION ALL SELECT 'O', o.id FROM "order" o
   WHERE (o.state IN ('PENDING','INTENT_READY','SUBMITTING','SUBMITTED','PARTIAL_FILLED','UNKNOWN') OR o.created_at >= :'window_from'::timestamptz)
     AND NOT (o.session_kind = 'DYNAMIC' AND EXISTS (SELECT 1 FROM dynamic_session s WHERE s.id = o.session_id AND s.trading_mode = 'PAPER'))
     AND NOT ((o.session_kind = 'LIVE' AND o.session_id = ANY(:'live_ids'::bigint[]))
           OR (o.session_kind = 'DYNAMIC' AND o.session_id = ANY(:'dyn_ids'::bigint[])))
  UNION ALL SELECT 'P', p.id FROM position p
   WHERE p.status <> 'CLOSED'
     AND NOT (p.session_kind = 'DYNAMIC' AND EXISTS (SELECT 1 FROM dynamic_session s WHERE s.id = p.session_id AND s.trading_mode = 'PAPER'))
     AND NOT ((p.session_kind = 'LIVE' AND p.session_id = ANY(:'live_ids'::bigint[]))
           OR (p.session_kind = 'DYNAMIC' AND p.session_id = ANY(:'dyn_ids'::bigint[])))
) x \gset
\if :gate_go
SELECT 'GATE', 'GO';
-- ── 2단계: 등록 세션의 금액 열(같은 트랜잭션·같은 스냅숏) ────────────────
SELECT 'S', 'LIVE', id, 'REAL', strategy_type, timeframe, coin_pair, initial_capital, available_krw, status, created_at, stopped_at
  FROM live_trading_session WHERE id = ANY(:'live_ids'::bigint[])
UNION ALL
SELECT 'S', 'DYNAMIC', id, trading_mode, strategy_type, timeframe, 'DYNAMIC', initial_capital, available_krw, status, created_at, stopped_at
  FROM dynamic_session WHERE id = ANY(:'dyn_ids'::bigint[])
ORDER BY 2, 3;
SELECT 'O', o.id, o.session_kind, o.session_id, o.position_id, o.coin_pair, o.side, o.order_type, o.quantity, o.reserved_krw,
       o.client_order_id, o.state, o.filled_quantity, o.exchange_order_id, o.created_at, o.filled_at, o.sell_applied_at,
       o.reported_executed_funds, o.reported_paid_fee, o.applied_executed_funds, o.applied_paid_fee,
       o.settlement_final, o.fill_accounting_pending, o.reported_observed_at
  FROM "order" o
 WHERE ((o.session_kind = 'LIVE' AND o.session_id = ANY(:'live_ids'::bigint[]))
     OR (o.session_kind = 'DYNAMIC' AND o.session_id = ANY(:'dyn_ids'::bigint[])))
   AND (o.state IN ('PENDING','INTENT_READY','SUBMITTING','SUBMITTED','PARTIAL_FILLED','UNKNOWN') OR o.created_at >= :'window_from'::timestamptz
        OR o.position_id IN (SELECT id FROM position WHERE status <> 'CLOSED'))
 ORDER BY o.id;
SELECT 'P', p.id, p.session_kind, p.session_id, p.coin_pair, p.size, p.status, p.invested_krw, p.position_fee, p.ruleset_hash,
       p.opened_at, p.closing_at, p.closed_at
  FROM position p
 WHERE ((p.session_kind = 'LIVE' AND p.session_id = ANY(:'live_ids'::bigint[]))
     OR (p.session_kind = 'DYNAMIC' AND p.session_id = ANY(:'dyn_ids'::bigint[])))
   AND p.status <> 'CLOSED'
 ORDER BY p.id;
SELECT 'D', d.id, d.order_id, d.kind, d.detected_at
  FROM order_settlement_discrepancy d JOIN "order" o ON o.id = d.order_id
 WHERE d.resolved_at IS NULL
   AND ((o.session_kind = 'LIVE' AND o.session_id = ANY(:'live_ids'::bigint[]))
     OR (o.session_kind = 'DYNAMIC' AND o.session_id = ANY(:'dyn_ids'::bigint[])))
 ORDER BY d.id;
\else
SELECT 'GATE', 'STOP';
\endif
SELECT 'END', clock_timestamp();
ROLLBACK;
