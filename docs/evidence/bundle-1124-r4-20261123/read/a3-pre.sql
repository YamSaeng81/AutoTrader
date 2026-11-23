-- A3 사전 조회·월중 원천 부록 공용 읽기 SQL a3-pre-1 (총괄 v1.228 §3-2·§3-3). **검토용 원문 — 미실행, 별도 승인 전 실행 금지.**
-- 실행은 read/a3-preread.sh 가 서버 안에서 1회: docker compose exec -T -e PGOPTIONS='-c statement_timeout=15s -c lock_timeout=2s' db psql -X -q -At -F <TAB>
--   -v ON_ERROR_STOP=1 -v sid=<등록 SID> -v with_orders=<true|false> -f -   (표준 입력). 결과 원문은 서버 출력 폴더에만 둔다.
-- 열은 배포 후보 d81c3f2f(= 운영 0e1d2a02, V96) 엔티티의 실제 열 이름이다. 쓰기·임시 객체 없음. READ ONLY + ROLLBACK.
-- 출력 줄(탭 구분, 첫 칸이 표지). 모든 시각은 UTC 마이크로초 'YYYY-MM-DDTHH:MM:SS.ffffffZ'(DB 시계).
--   META    a3-pre-1  db_clock_start(clock_timestamp)  db_txn_now(now())  flyway_max  sid  with_orders
--   SESS    kind  id  session_type/trading_mode  status  initial_capital  available_krw  started_at  stopped_at
--           → 이 DB 의 실자금 세션 **전 상태**(RUNNING·CREATED 외 STOPPED·EMERGENCY_STOPPED 포함). S 의 대상 근거 후보이지 배정 전부 포함의 증명은 아니다.
--   OBUY    order_id  kind  session_id  state  reserved_krw  quantity  filled_quantity  created_at
--           → 제품 sumOpenBuyCommitmentKrw 와 같은 상태 6종·LIVE/DYNAMIC 의 매수 중 **실자금 세션 소유**만. reserved_krw NULL 은 그대로 '-'.
--   CNT     active_real_orders  cleanup_pending  open_real_positions  ownerless_open_orders
--           → F1 과 같은 정의. ownerless_open_orders = session_id NULL 인 열린 상태 주문 **개수만**(행을 읽지 않는다, 예외를 두지 않는다 — 0 이 아니면 보류).
--   POS     position_id  kind  session_id  status  coin_pair  size  buy_cost_krw  buy_cost_complete
--           → 실자금 세션의 OPEN·CLOSING. buy_cost = 체결 매수의 Σ(applied_executed_funds + applied_paid_fee). 하나라도 NULL 이면 complete=f, 원가는 '-'.
--   MAXTS   max_created  max_submitted  max_filled  max_cancelled  max_cancel_confirmed  max_sell_applied   (실자금 주문, DB 시계)
--           → **참고만.** 정산 정정(applied_*·settlement_version 갱신)·환급·배정 변경은 이 시각들을 바꾸지 않는다. 변화 없음 근거로 쓰지 않는다.
--   M절(with_orders=true 일 때만, 등록 SID 의 LIVE 주문만):
--   MORD    order_id  position_id  side  state  quantity  reserved_krw  filled_quantity  applied_executed_funds  applied_paid_fee
--           reported_executed_funds  reported_paid_fee  settlement_final  fill_accounting_pending  settlement_version
--           filled_at  sell_applied_at  cancel_confirmed_at  created_at  open_discrepancy
--   MSES    id  status  initial_capital  available_krw  started_at  stopped_at
--   END     db_clock_end(clock_timestamp)
\set ON_ERROR_STOP on
\pset null '-'
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'META', 'a3-pre-1',
       to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       (SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'),
       :'sid', :'with_orders';
SELECT 'SESS', 'LIVE', l.id, l.session_type, l.status, l.initial_capital, l.available_krw,
       to_char(l.started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(l.stopped_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
  FROM live_trading_session l WHERE l.session_type = 'REAL'
UNION ALL
SELECT 'SESS', 'DYNAMIC', d.id, d.trading_mode, d.status, d.initial_capital, d.available_krw,
       to_char(d.started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(d.stopped_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
  FROM dynamic_session d WHERE d.trading_mode = 'REAL'
 ORDER BY 2, 3;
SELECT 'OBUY', o.id, o.session_kind, o.session_id, o.state, o.reserved_krw, o.quantity, o.filled_quantity,
       to_char(o.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
  FROM "order" o
  LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
  LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id
 WHERE o.side = 'BUY' AND o.session_kind IN ('LIVE', 'DYNAMIC')
   AND o.state IN ('PENDING', 'INTENT_READY', 'SUBMITTING', 'UNKNOWN', 'SUBMITTED', 'PARTIAL_FILLED')
   AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL')
 ORDER BY o.id;
SELECT 'CNT',
  (SELECT count(*) FROM "order" o
     LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
     LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id
    WHERE o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED') AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL')),
  (SELECT count(*) FROM position_cleanup WHERE done_at IS NULL),
  (SELECT count(*) FROM position p
     LEFT JOIN live_trading_session l ON p.session_kind = 'LIVE' AND l.id = p.session_id
     LEFT JOIN dynamic_session d ON p.session_kind = 'DYNAMIC' AND d.id = p.session_id
    WHERE p.status IN ('OPEN','CLOSING') AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL')),
  (SELECT count(*) FROM "order" o WHERE o.session_id IS NULL
      AND o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED'));
SELECT 'POS', p.id, p.session_kind, p.session_id, p.status, p.coin_pair, p.size,
       CASE WHEN count(b.id) = count(b.applied_executed_funds) AND count(b.id) = count(b.applied_paid_fee)
            THEN sum(b.applied_executed_funds + b.applied_paid_fee) END,
       (count(b.id) = count(b.applied_executed_funds) AND count(b.id) = count(b.applied_paid_fee))
  FROM position p
  LEFT JOIN live_trading_session l ON p.session_kind = 'LIVE' AND l.id = p.session_id
  LEFT JOIN dynamic_session d ON p.session_kind = 'DYNAMIC' AND d.id = p.session_id
  LEFT JOIN "order" b ON b.position_id = p.id AND b.side = 'BUY' AND b.filled_quantity > 0
 WHERE p.status IN ('OPEN','CLOSING') AND (l.session_type = 'REAL' OR d.trading_mode = 'REAL')
 GROUP BY p.id, p.session_kind, p.session_id, p.status, p.coin_pair, p.size
 ORDER BY p.id;
SELECT 'MAXTS',
       to_char(max(o.created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(max(o.submitted_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       to_char(max(o.filled_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(max(o.cancelled_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       to_char(max(o.cancel_confirmed_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(max(o.sell_applied_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
  FROM "order" o
  LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
  LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id
 WHERE (l.session_type = 'REAL' OR d.trading_mode = 'REAL');
\if :with_orders
SELECT 'MORD', o.id, o.position_id, o.side, o.state, o.quantity, o.reserved_krw, o.filled_quantity,
       o.applied_executed_funds, o.applied_paid_fee, o.reported_executed_funds, o.reported_paid_fee,
       o.settlement_final, o.fill_accounting_pending, o.settlement_version,
       to_char(o.filled_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(o.sell_applied_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       to_char(o.cancel_confirmed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(o.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
       EXISTS (SELECT 1 FROM order_settlement_discrepancy x WHERE x.order_id = o.id AND x.resolved_at IS NULL)
  FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = :sid
 ORDER BY o.id;
SELECT 'MSES', l.id, l.status, l.initial_capital, l.available_krw,
       to_char(l.started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(l.stopped_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
  FROM live_trading_session l WHERE l.id = :sid AND l.session_type = 'REAL';
\endif
SELECT 'END', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
ROLLBACK;
