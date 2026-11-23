-- A3 관측 수집·월중 원천 부록 공용 읽기 SQL a3-pre-3 (총괄 v1.230 §2 R6-1). **검토용 원문 — 미실행, 별도 승인 전 실행 금지.**
-- a3-pre-2(r5, 69e1f277) 대비: 범위 관문에 **소유 연결 관문**(LINK_OWNER_MISMATCH)을 SESSION_NOT_REAL 뒤·금액 절 앞에 추가했다.
--   대상 포지션(LIVE#203 OPEN/CLOSING)을 position_id 로 가리키는 주문 중 소유가 LIVE#203 이 아닌 행(다른 세션·다른 종류·소유 없음)이
--   하나라도 있으면 같은 스냅숏에서 범위 절 전체를 실행하지 않는다. 그 행을 빼고 정상 원가로 확정하지 않으며, 금액 없는 사유와
--   개수(LINKX)만 남긴다. 소유 없는 옛 행을 임의 귀속하지 않는다. 그 밖의 절·열은 a3-pre-2 와 같다.
-- a3-pre-1(r4, d505d08f) 대비: ① 승인 대상 LIVE#203 한 개로 고정, 같은 트랜잭션에서 세션 존재·정확한 REAL 확인을 **금액 SELECT 전에** 하고
--   불통과면 금액·주문·포지션 절을 실행하지 않는다(SCOPE 표지만) ② 대상 밖은 안전 개수(XCNT)만 ③ POS 원가를 매수 총수량·총원가·
--   미확인 원천 건수·현재 잔량으로 나누고, 남은 보유 원가는 d81 수량 배분 규칙을 그대로 쓸 수 있을 때만 낸다.
-- 실행은 read/a3-remote.sh 가 서버 안에서 1회: docker compose exec -T -e PGOPTIONS='-c statement_timeout=15s -c lock_timeout=2s' db psql -X -q -At -F <TAB>
--   -v ON_ERROR_STOP=1 -v sid=<SID> -v with_orders=<true|false> -f -   (표준 입력). 결과 원문은 서버 출력 폴더에만 둔다.
-- 열은 배포 후보 d81c3f2f(= 운영 0e1d2a02, V96) 엔티티의 실제 열 이름이다. 쓰기·임시 객체 없음. REPEATABLE READ READ ONLY + ROLLBACK.
-- 출력 줄(탭 구분, 첫 칸이 표지). 모든 시각은 UTC 마이크로초 'YYYY-MM-DDTHH:MM:SS.ffffffZ'(DB 시계).
--   META    a3-pre-3  db_clock_start  db_txn_now  flyway_max  sid  with_orders
--   SCOPE   OK|SID_NOT_APPROVED|SESSION_NOT_FOUND|SESSION_NOT_REAL|LINK_OWNER_MISMATCH  LIVE  sid
--   LINKX   foreign_linked_orders   (SCOPE 가 LINK_OWNER_MISMATCH 일 때만. 대상 포지션에 연결된 소유 불일치 주문 개수. 금액·행 없음)
--           → OK 가 아니면 아래 범위 절(TSES~MORD) 0행. 금액·주문/포지션 행을 내지 않는다.
--   ── 범위 절(SCOPE OK 일 때만, 모두 LIVE#203 소유 행만) ──
--   TSES    LIVE  id  session_type  status  initial_capital  available_krw  started_at  stopped_at
--   OBUY    order_id  state  reserved_krw  quantity  filled_quantity  created_at      (열린 상태 6종 BUY)
--   CNT     active_orders  cleanup_pending  open_positions                          (대상 세션의 F1 개수)
--   XCNT    other_real_sessions_not_stopped  other_real_active_orders  other_real_open_positions  other_cleanup_pending  ownerless_open_orders
--           → **대상 밖 개수만.** 행·금액을 읽어 내지 않는다. 소유 없는 열린 주문 개수가 0 이 아니면 보류(예외 없음).
--   POS     position_id  status  coin_pair  size  buy_n  buy_qty  buy_cost_total  buy_cost_unknown_n  buy_unsettled_n
--           sell_confirmed_n  sell_confirmed_qty  sell_unknown_n  sell_inflight_n  discrepancy_open_n  cost_state  remaining_cost_d81
--           → buy_cost_total 은 **과거 매수 총원가**이며 남은 보유 원가가 아니다. remaining_cost_d81 은 cost_state=ALLOCATED 일 때만
--             buy_cost_total − round(sell_confirmed_qty × buy_cost_total ÷ buy_qty, 8) (d81 RiskManagementService.costBasisClosedPnl 의
--             매도분 원가 식과 같은 배분), 그 밖에는 '-'(미확정). cost_state 는 아래 우선순위의 첫 해당:
--             NO_BUY_SOURCE(체결 BUY 0) · BUY_COST_UNKNOWN(applied 대금/수수료 NULL) · BUY_SETTLEMENT_PENDING(settlement_final=f 또는
--             fill_accounting_pending=t) · SELL_UNKNOWN(sell_applied_at 있는 매도의 필수값 NULL) · SELL_INFLIGHT(체결>0 인데 반영 전 매도,
--             또는 열린 상태 매도) · DISCREPANCY_OPEN(미해결 정산 불일치) · CLOSING(청산 중, v5 미확정) · QTY_MISMATCH(buy_qty−sell_qty≠size)
--             · ALLOCATED.
--   MAXTS   max_created  max_submitted  max_filled  max_cancelled  max_cancel_confirmed  max_sell_applied   (대상 세션 주문, 참고만)
--   MORD(with_orders=true 일 때만) order_id  position_id  side  state  quantity  reserved_krw  filled_quantity  applied_executed_funds  applied_paid_fee
--           reported_executed_funds  reported_paid_fee  settlement_final  fill_accounting_pending  settlement_version
--           filled_at  sell_applied_at  cancel_confirmed_at  created_at  open_discrepancy
--   END     db_clock_end(clock_timestamp)
\set ON_ERROR_STOP on
\pset null '-'
\set approved_sid 203
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'META', 'a3-pre-3',
       to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       (SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'),
       :'sid', :'with_orders';
-- R6-1 소유 연결 개수: 승인 대상(:approved_sid)의 POS 범위 포지션에만 묻는다. 소유 없는 행(NULL)도 불일치로 센다(IS DISTINCT FROM).
SELECT count(*) AS link_foreign_n
  FROM "order" o JOIN position p ON p.id = o.position_id
 WHERE p.session_kind = 'LIVE' AND p.session_id = :approved_sid AND p.status IN ('OPEN','CLOSING')
   AND (o.session_kind IS DISTINCT FROM 'LIVE' OR o.session_id IS DISTINCT FROM :approved_sid) \gset
-- R1 범위 관문: 금액을 읽기 전에, 같은 트랜잭션 스냅숏에서. 인자는 숫자로 캐스팅하지 않고 문자열로 비교한다.
SELECT CASE WHEN :'sid' <> :'approved_sid' THEN 'SID_NOT_APPROVED'
            WHEN NOT EXISTS (SELECT 1 FROM live_trading_session WHERE id::text = :'sid') THEN 'SESSION_NOT_FOUND'
            WHEN NOT EXISTS (SELECT 1 FROM live_trading_session WHERE id::text = :'sid' AND session_type = 'REAL') THEN 'SESSION_NOT_REAL'
            WHEN :link_foreign_n <> 0 THEN 'LINK_OWNER_MISMATCH'
            ELSE 'OK' END AS scope_reason \gset
SELECT (:'scope_reason' = 'OK') AS scope_ok, (:'scope_reason' = 'LINK_OWNER_MISMATCH') AS link_denied \gset
SELECT 'SCOPE', :'scope_reason', 'LIVE', :'sid';
\if :scope_ok
SELECT 'TSES', 'LIVE', l.id, l.session_type, l.status, l.initial_capital, l.available_krw,
       to_char(l.started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(l.stopped_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
  FROM live_trading_session l WHERE l.id = :approved_sid AND l.session_type = 'REAL';
SELECT 'OBUY', o.id, o.state, o.reserved_krw, o.quantity, o.filled_quantity,
       to_char(o.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
  FROM "order" o
 WHERE o.session_kind = 'LIVE' AND o.session_id = :approved_sid AND o.side = 'BUY'
   AND o.state IN ('PENDING', 'INTENT_READY', 'SUBMITTING', 'UNKNOWN', 'SUBMITTED', 'PARTIAL_FILLED')
 ORDER BY o.id;
SELECT 'CNT',
  (SELECT count(*) FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = :approved_sid
      AND o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')),
  (SELECT count(*) FROM position_cleanup c WHERE c.session_kind = 'LIVE' AND c.session_id = :approved_sid AND c.done_at IS NULL),
  (SELECT count(*) FROM position p WHERE p.session_kind = 'LIVE' AND p.session_id = :approved_sid AND p.status IN ('OPEN','CLOSING'));
SELECT 'XCNT',
  (SELECT count(*) FROM live_trading_session l WHERE l.session_type = 'REAL' AND l.id <> :approved_sid AND l.status NOT IN ('STOPPED','EMERGENCY_STOPPED'))
  + (SELECT count(*) FROM dynamic_session d WHERE d.trading_mode = 'REAL' AND d.status NOT IN ('STOPPED','EMERGENCY_STOPPED')),
  (SELECT count(*) FROM "order" o
     LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
     LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id
    WHERE o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')
      AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL') AND NOT (o.session_kind = 'LIVE' AND o.session_id = :approved_sid)),
  (SELECT count(*) FROM position p
     LEFT JOIN live_trading_session l ON p.session_kind = 'LIVE' AND l.id = p.session_id
     LEFT JOIN dynamic_session d ON p.session_kind = 'DYNAMIC' AND d.id = p.session_id
    WHERE p.status IN ('OPEN','CLOSING') AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL')
      AND NOT (p.session_kind = 'LIVE' AND p.session_id = :approved_sid)),
  (SELECT count(*) FROM position_cleanup c WHERE c.done_at IS NULL AND NOT (c.session_kind = 'LIVE' AND c.session_id = :approved_sid)),
  (SELECT count(*) FROM "order" o WHERE o.session_id IS NULL
      AND o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED'));
-- R3 원천 열: 집계는 포지션별 부분합만. 새 손익 계산(평가·실현 손익)은 하지 않는다.
SELECT 'POS', q.id, q.status, q.coin_pair, q.size, q.buy_n, q.buy_qty, q.buy_cost_total, q.buy_cost_unknown_n, q.buy_unsettled_n,
       q.sell_confirmed_n, q.sell_confirmed_qty, q.sell_unknown_n, q.sell_inflight_n, q.discrepancy_open_n, q.cost_state,
       CASE WHEN q.cost_state = 'ALLOCATED'
            THEN q.buy_cost_total - round(COALESCE(q.sell_confirmed_qty, 0) * q.buy_cost_total / q.buy_qty, 8) END
  FROM (
    SELECT s.*,
           CASE WHEN s.buy_n = 0 THEN 'NO_BUY_SOURCE'
                WHEN s.buy_cost_unknown_n > 0 OR s.buy_qty IS NULL OR s.buy_qty <= 0 THEN 'BUY_COST_UNKNOWN'
                WHEN s.buy_unsettled_n > 0 THEN 'BUY_SETTLEMENT_PENDING'
                WHEN s.sell_unknown_n > 0 THEN 'SELL_UNKNOWN'
                WHEN s.sell_inflight_n > 0 THEN 'SELL_INFLIGHT'
                WHEN s.discrepancy_open_n > 0 THEN 'DISCREPANCY_OPEN'
                WHEN s.status <> 'OPEN' THEN 'CLOSING'
                WHEN s.buy_qty - COALESCE(s.sell_confirmed_qty, 0) <> s.size THEN 'QTY_MISMATCH'
                ELSE 'ALLOCATED' END AS cost_state
      FROM (
        SELECT p.id, p.status, p.coin_pair, p.size,
          (SELECT count(*) FROM "order" b WHERE b.position_id = p.id AND b.side = 'BUY' AND b.filled_quantity > 0) AS buy_n,
          (SELECT sum(b.filled_quantity) FROM "order" b WHERE b.position_id = p.id AND b.side = 'BUY' AND b.filled_quantity > 0) AS buy_qty,
          (SELECT CASE WHEN count(*) = count(b.applied_executed_funds) AND count(*) = count(b.applied_paid_fee) AND count(*) > 0
                       THEN sum(b.applied_executed_funds + b.applied_paid_fee) END
             FROM "order" b WHERE b.position_id = p.id AND b.side = 'BUY' AND b.filled_quantity > 0) AS buy_cost_total,
          (SELECT count(*) FROM "order" b WHERE b.position_id = p.id AND b.side = 'BUY' AND b.filled_quantity > 0
              AND (b.applied_executed_funds IS NULL OR b.applied_paid_fee IS NULL)) AS buy_cost_unknown_n,
          (SELECT count(*) FROM "order" b WHERE b.position_id = p.id AND b.side = 'BUY' AND b.filled_quantity > 0
              AND (NOT b.settlement_final OR b.fill_accounting_pending)) AS buy_unsettled_n,
          (SELECT count(*) FROM "order" s WHERE s.position_id = p.id AND s.side = 'SELL' AND s.sell_applied_at IS NOT NULL
              AND s.applied_executed_funds IS NOT NULL AND s.applied_paid_fee IS NOT NULL AND s.filled_quantity > 0) AS sell_confirmed_n,
          (SELECT sum(s.filled_quantity) FROM "order" s WHERE s.position_id = p.id AND s.side = 'SELL' AND s.sell_applied_at IS NOT NULL
              AND s.applied_executed_funds IS NOT NULL AND s.applied_paid_fee IS NOT NULL AND s.filled_quantity > 0) AS sell_confirmed_qty,
          (SELECT count(*) FROM "order" s WHERE s.position_id = p.id AND s.side = 'SELL' AND s.sell_applied_at IS NOT NULL
              AND NOT (s.applied_executed_funds IS NOT NULL AND s.applied_paid_fee IS NOT NULL
                       AND s.filled_quantity IS NOT NULL AND s.filled_quantity > 0)) AS sell_unknown_n,
          (SELECT count(*) FROM "order" s WHERE s.position_id = p.id AND s.side = 'SELL' AND s.sell_applied_at IS NULL
              AND (COALESCE(s.filled_quantity, 0) > 0
                   OR s.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED'))) AS sell_inflight_n,
          (SELECT count(*) FROM "order" o JOIN order_settlement_discrepancy x ON x.order_id = o.id AND x.resolved_at IS NULL
            WHERE o.position_id = p.id) AS discrepancy_open_n
          FROM position p
         WHERE p.session_kind = 'LIVE' AND p.session_id = :approved_sid AND p.status IN ('OPEN','CLOSING')
      ) s
  ) q
 ORDER BY q.id;
SELECT 'MAXTS',
       to_char(max(o.created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(max(o.submitted_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       to_char(max(o.filled_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(max(o.cancelled_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       to_char(max(o.cancel_confirmed_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(max(o.sell_applied_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
  FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = :approved_sid;
\if :with_orders
SELECT 'MORD', o.id, o.position_id, o.side, o.state, o.quantity, o.reserved_krw, o.filled_quantity,
       o.applied_executed_funds, o.applied_paid_fee, o.reported_executed_funds, o.reported_paid_fee,
       o.settlement_final, o.fill_accounting_pending, o.settlement_version,
       to_char(o.filled_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(o.sell_applied_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       to_char(o.cancel_confirmed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(o.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       EXISTS (SELECT 1 FROM order_settlement_discrepancy x WHERE x.order_id = o.id AND x.resolved_at IS NULL)
  FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = :approved_sid
 ORDER BY o.id;
\endif
\elif :link_denied
SELECT 'LINKX', :link_foreign_n;
\endif
SELECT 'END', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
ROLLBACK;
