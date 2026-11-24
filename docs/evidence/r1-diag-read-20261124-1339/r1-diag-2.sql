-- R1 판정 불가 원인 분리 읽기 r1-diag-2 (총괄 v1.234 §3-1, r1-diag-1 7c26631d 의 출력 계약 보완 사본). **운영 실행 승인 아님. 총괄이 실제 읽기 1회 범위를 따로 확정하기 전 실행 금지.**
-- r1-diag-1 대비 바뀐 것: (1) 분해 행을 tag+c1~c8 9열로 넓혀 UQ union_items(c8)를 실제로 출력 (2) META 식별자 r1-diag-2
--   (3) PE exit_reason 그룹은 코드 형식(^[A-Z][A-Z0-9_]{0,39}$)만 그대로, 그 밖은 '(nonconforming)' 으로 묶어 원문을 내보내지 않는다.
--   ORIG 식·sa/pc 선택 조건·나머지 분류식은 바꾸지 않았다(diff/sql-1-vs-2.diff).
-- 목적: U0(11-24 10:30) 배포 전 읽기 v3 R1 = LIVE 0 / DYNAMIC 9 의 구성을 금액 없이 나눈다.
--   R1 = (A) 미확정 매도 주문 COUNT + (P) 미확정 CLOSED 포지션 COUNT. 같은 포지션이 A·P 양쪽에 들 수 있어 9 는 고유 포지션 수가 아닐 수 있다.
--   제품 원식: d81c3f2f OrderRepository.countRealSessionCostUndeterminedSince (= 고정 predeploy_read.v3.sql ca6e7c8d… R1).
-- 재현 원칙: 아래 sa/pc 의 WHERE 는 제품 원식의 두 COUNT 조건을 글자 그대로 옮겼다(:kind → IN ('LIVE','DYNAMIC') 로 두 종류를 한 번에, :since → now() - 30일).
--   ORIG 행은 고정 SQL R1 식을 그대로 다시 계산한다. 같은 스냅숏에서 ORIG = SPLIT(A+P) 가 아니면 재현 실패(형태 이상)다.
-- 읽는 열(분류용 NULL 여부·비교 결과로만, 값 출력 없음):
--   "order"  : id, side, state, position_id, session_id, session_kind, sell_applied_at, applied_executed_funds, applied_paid_fee,
--              filled_quantity, fee_source, cancel_confirmed_at, created_at
--   position : id, status, session_id, session_kind, closed_at, opened_at, exit_reason, size
--   dynamic_session : id, trading_mode   ·   live_trading_session : id, session_type   ·   flyway_schema_history : version, installed_on, success
-- 읽지 않는 것: 수량·대금·수수료·가격·손익(realized_pnl 포함)의 값, 잔액·자본, 전략 파라미터, 인증/키, 문자열 원문(failed_reason 등),
--   paper_trading 스키마와 522~587 모의 테이블 전부. 행 단위·ID 출력 없음(전부 집계). exit_reason 은 코드값 앞 40자 그룹 건수만.
-- 시간: 기준은 트랜잭션 시각 now(). since = now() - 30일. 만료 버킷 = 그 행이 30일 창에서 빠지기까지 남은 일수.
--   제품은 앱 시계 Instant.now() 기준이라 수 초 차이가 있을 수 있다. 이 읽기 합계가 9 와 달라도 옛 실행(10:30)을 재현했다고 주장하지 않는다.
-- 보호값: PGOPTIONS 로 statement_timeout=15s·lock_timeout=2s. 첫 DO 블록이 확인하고 다르면 예외(psql rc 3)로 멈춘다. READ ONLY·REPEATABLE READ·ROLLBACK.
-- 호출(승인 후, approval/r1-diag-read.sh 로만): ... exec -T -e PGOPTIONS='-c statement_timeout=15s -c lock_timeout=2s' db psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f -
-- 출력 행(탭 구분, NULL = \N). 정수 열은 전부 개수다. 열 수 고정: META 10 · REF 4 · ORIG 3 · END 2 · 그 밖(분해 행) tag+c1~c8 = 9열(쓰지 않는 칸은 \N):
--   META   r1-diag-2  db_now  db_clock  since  flyway_max  isolation  timezone  statement_timeout  lock_timeout
--   REF    version  installed_on_raw  ref_tz_utc                     (81·87·94 중 있는 것만)
--   ORIG   kind  r1_original                                         (고정 SQL R1 식 그대로)
--   SPLIT  kind  a_rows  p_rows  a_plus_p                           (a_plus_p = ORIG 여야 함)
--   AM     kind  mode  a_rows                                        (A 매도 행의 세션 모드: REAL|PAPER|NO_ROW|OTHER — 합 = a_rows)
--   PM     kind  mode  p_rows                                        (P 포지션의 세션 모드 — 합 = p_rows)
--   AX     kind  class  a_rows    (배타 분류, 합 = a_rows)  class: FIELDS_ONLY(③만) | COST_ONLY(①만) | FIELDS_AND_COST(③+①)
--   AF     kind  flag   a_rows    (중복 가능 표지 — 합을 내지 않는다) flag: F_FUNDS_NULL F_FEE_NULL F_QTY_NULL_OR_LE0 · C_NO_POSITION C_NO_FILLED_BUY C_BUY_COST_NULL C_BUY_QTY_NULL
--   PX     kind  class  p_rows    (배타 분류, 합 = p_rows)  class: QTY_SHORT(②) | NO_SOURCE(④) | BOTH(②+④, 논리상 0 이어야 함)
--   PF     kind  flag   p_rows    (중복 가능 표지) flag: Q_NO_SETTLED_SELL Q_PARTIAL_SETTLED Q_APPLIED_SELL_FUNDS_NULL Q_UNAPPLIED_SELL Q_NO_SELL_ORDER
--                                                        N_NO_ORDERS N_ER_SIZE_NOT_ZERO N_NO_BUY N_BUY_QTY_NULL N_BUY_NOT_FAILED_OR_CANCELLED N_BUY_CANCEL_UNCONFIRMED N_UNAPPLIED_SELL
--   PE     kind  exit_reason40  p_rows                               (P 포지션 exit_reason 코드 그룹 — 합 = p_rows. NULL='(null)', 코드 형식 밖='(nonconforming)')
--   UQ     kind  a_rows  a_distinct_pos  a_null_pos  p_rows  both_pos  union_pos  union_items
--          (union_pos = A·P 고유 포지션 수, union_items = union_pos + a_null_pos = 겹침 제거 후 대상 수)
--   AGE    kind  branch  bucket  rows                                (bucket: D00_06 D07_13 D14_20 D21_29 = 창에서 빠지기까지 남은 일수, D30P = 시각이 now() 이후(시계 차이) — 합 = 그 분기 행 수)
--   LEG    kind  branch  before_v81  before_v87  opened_null          (포지션 opened_at < V81/V87 설치 시각 — 보조 지표, REF 없으면 \N)
--   END    db_clock
\set ON_ERROR_STOP 1
\pset format unaligned
\pset tuples_only on
\pset fieldsep '\t'
\pset null '\\N'

DO $$
BEGIN
  IF current_setting('statement_timeout') <> '15s' OR current_setting('lock_timeout') <> '2s' THEN
    RAISE EXCEPTION 'R1D_GUARD 보호값 불일치 statement_timeout=% lock_timeout=%',
      current_setting('statement_timeout'), current_setting('lock_timeout');
  END IF;
END $$;

BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'META', 'r1-diag-2',
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       to_char((now() - interval '30 days') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       (SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'),
       current_setting('transaction_isolation'),
       current_setting('TimeZone'),
       current_setting('statement_timeout'),
       current_setting('lock_timeout');

SELECT 'REF', version, installed_on::text,
       to_char(installed_on::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"+00:00"')
FROM flyway_schema_history WHERE success AND version IN ('81', '87', '94') ORDER BY version::int;

-- ORIG: 고정 predeploy_read.v3.sql R1 식 그대로(바꾸지 않는다).
SELECT 'ORIG', k.kind, (SELECT (SELECT COUNT(*) FROM "order" o   WHERE o.side = 'SELL' AND o.session_id IS NOT NULL AND o.session_kind = k.kind   AND o.sell_applied_at IS NOT NULL AND o.sell_applied_at >= (now() - interval '30 days')   AND (NOT (o.applied_executed_funds IS NOT NULL AND o.applied_paid_fee IS NOT NULL AND o.filled_quantity IS NOT NULL AND o.filled_quantity > 0)        OR NOT EXISTS (SELECT 1 FROM (SELECT bo.position_id, SUM(bo.applied_executed_funds + bo.applied_paid_fee) AS cost, SUM(bo.filled_quantity) AS qty FROM "order" bo WHERE bo.side = 'BUY' AND bo.position_id IS NOT NULL AND bo.filled_quantity > 0 GROUP BY bo.position_id HAVING COUNT(*) = COUNT(bo.applied_executed_funds) AND COUNT(*) = COUNT(bo.applied_paid_fee)) b WHERE b.position_id = o.position_id))) + (SELECT COUNT(*) FROM position p   WHERE p.session_kind = k.kind AND p.session_id IS NOT NULL AND p.status = 'CLOSED'   AND p.closed_at IS NOT NULL AND p.closed_at >= (now() - interval '30 days')   AND ((SELECT COALESCE(SUM(bq.filled_quantity), 0) FROM "order" bq        WHERE bq.position_id = p.id AND bq.side = 'BUY' AND bq.filled_quantity > 0)     > (SELECT COALESCE(SUM(sq.filled_quantity), 0) FROM "order" sq        WHERE sq.position_id = p.id AND sq.side = 'SELL' AND sq.sell_applied_at IS NOT NULL        AND sq.applied_executed_funds IS NOT NULL)     OR (NOT EXISTS (SELECT 1 FROM "order" x WHERE x.position_id = p.id           AND ((x.side = 'BUY' AND x.filled_quantity > 0) OR (x.side = 'SELL' AND x.sell_applied_at IS NOT NULL)))         AND NOT (((COALESCE(p.exit_reason, '') = 'ENTRY_REJECTED' AND COALESCE(p.size, -1) = 0   AND NOT EXISTS (SELECT 1 FROM "order" nx WHERE nx.position_id = p.id)) OR (EXISTS (SELECT 1 FROM "order" nb WHERE nb.position_id = p.id AND nb.side = 'BUY') AND NOT EXISTS (SELECT 1 FROM "order" nb WHERE nb.position_id = p.id AND nb.side = 'BUY'   AND NOT (nb.filled_quantity IS NOT NULL AND nb.filled_quantity = 0 AND (nb.state = 'FAILED'     OR (nb.state = 'CANCELLED' AND (nb.fee_source IS NULL OR nb.cancel_confirmed_at IS NOT NULL))))) AND NOT EXISTS (SELECT 1 FROM "order" ns WHERE ns.position_id = p.id AND ns.side = 'SELL'   AND ns.sell_applied_at IS NOT NULL)))))))) AS undetermined_30d
FROM (VALUES ('LIVE'), ('DYNAMIC')) k(kind);

-- 분해: 임시 테이블을 만들지 않는다(READ ONLY). 한 문장 안 CTE 로 만들고 태그 행만 낸다.
WITH
kinds(kind) AS (VALUES ('LIVE'), ('DYNAMIC')),
ref AS (SELECT
          (SELECT installed_on::timestamptz FROM flyway_schema_history WHERE success AND version = '81' ORDER BY installed_rank LIMIT 1) AS v81,
          (SELECT installed_on::timestamptz FROM flyway_schema_history WHERE success AND version = '87' ORDER BY installed_rank LIMIT 1) AS v87),
bc AS (SELECT bo.position_id, SUM(bo.applied_executed_funds + bo.applied_paid_fee) AS cost,
              SUM(bo.filled_quantity) AS qty FROM "order" bo
       WHERE bo.side = 'BUY' AND bo.position_id IS NOT NULL AND bo.filled_quantity > 0
       GROUP BY bo.position_id
       HAVING COUNT(*) = COUNT(bo.applied_executed_funds) AND COUNT(*) = COUNT(bo.applied_paid_fee)),
-- (A) 제품 첫 COUNT 조건 그대로
sa AS (
  SELECT o.session_kind AS kind, o.session_id AS sid, o.position_id AS pid, o.sell_applied_at AS t,
         NOT (o.applied_executed_funds IS NOT NULL AND o.applied_paid_fee IS NOT NULL AND o.filled_quantity IS NOT NULL AND o.filled_quantity > 0) AS f_any,
         NOT EXISTS (SELECT 1 FROM bc b WHERE b.position_id = o.position_id) AS c_any,
         o.applied_executed_funds IS NULL AS f_funds_null,
         o.applied_paid_fee IS NULL AS f_fee_null,
         (o.filled_quantity IS NULL OR o.filled_quantity <= 0) AS f_qty,
         o.position_id IS NULL AS c_no_pos,
         (o.position_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM "order" b WHERE b.position_id = o.position_id AND b.side = 'BUY' AND b.filled_quantity > 0)) AS c_no_filled_buy,
         EXISTS (SELECT 1 FROM "order" b WHERE b.position_id = o.position_id AND b.side = 'BUY' AND b.filled_quantity > 0
                 AND (b.applied_executed_funds IS NULL OR b.applied_paid_fee IS NULL)) AS c_buy_cost_null,
         EXISTS (SELECT 1 FROM "order" b WHERE b.position_id = o.position_id AND b.side = 'BUY' AND b.filled_quantity IS NULL) AS c_buy_qty_null
  FROM "order" o
  WHERE o.side = 'SELL' AND o.session_id IS NOT NULL AND o.session_kind IN ('LIVE', 'DYNAMIC')
    AND o.sell_applied_at IS NOT NULL AND o.sell_applied_at >= (now() - interval '30 days')
    AND (NOT (o.applied_executed_funds IS NOT NULL AND o.applied_paid_fee IS NOT NULL AND o.filled_quantity IS NOT NULL AND o.filled_quantity > 0)
         OR NOT EXISTS (SELECT 1 FROM bc b WHERE b.position_id = o.position_id))),
-- (P) 제품 둘째 COUNT 조건 그대로
pc AS (
  SELECT p.session_kind AS kind, p.session_id AS sid, p.id AS pid, p.closed_at AS t, p.opened_at,
         CASE WHEN p.exit_reason IS NULL THEN '(null)'
              WHEN p.exit_reason ~ '^[A-Z][A-Z0-9_]{0,39}$' THEN p.exit_reason ELSE '(nonconforming)' END AS er,
         ((SELECT COALESCE(SUM(bq.filled_quantity), 0) FROM "order" bq WHERE bq.position_id = p.id AND bq.side = 'BUY' AND bq.filled_quantity > 0)
          > (SELECT COALESCE(SUM(sq.filled_quantity), 0) FROM "order" sq WHERE sq.position_id = p.id AND sq.side = 'SELL' AND sq.sell_applied_at IS NOT NULL
             AND sq.applied_executed_funds IS NOT NULL)) AS q_any,
         (NOT EXISTS (SELECT 1 FROM "order" x WHERE x.position_id = p.id
              AND ((x.side = 'BUY' AND x.filled_quantity > 0) OR (x.side = 'SELL' AND x.sell_applied_at IS NOT NULL)))
          AND NOT ((COALESCE(p.exit_reason, '') = 'ENTRY_REJECTED' AND COALESCE(p.size, -1) = 0
                    AND NOT EXISTS (SELECT 1 FROM "order" nx WHERE nx.position_id = p.id))
                   OR (EXISTS (SELECT 1 FROM "order" nb WHERE nb.position_id = p.id AND nb.side = 'BUY')
                       AND NOT EXISTS (SELECT 1 FROM "order" nb WHERE nb.position_id = p.id AND nb.side = 'BUY'
                         AND NOT (nb.filled_quantity IS NOT NULL AND nb.filled_quantity = 0 AND (nb.state = 'FAILED'
                           OR (nb.state = 'CANCELLED' AND (nb.fee_source IS NULL OR nb.cancel_confirmed_at IS NOT NULL)))))
                       AND NOT EXISTS (SELECT 1 FROM "order" ns WHERE ns.position_id = p.id AND ns.side = 'SELL'
                         AND ns.sell_applied_at IS NOT NULL)))) AS n_any,
         NOT EXISTS (SELECT 1 FROM "order" s WHERE s.position_id = p.id AND s.side = 'SELL' AND s.sell_applied_at IS NOT NULL
                     AND s.applied_executed_funds IS NOT NULL) AS q_no_settled,
         EXISTS (SELECT 1 FROM "order" s WHERE s.position_id = p.id AND s.side = 'SELL' AND s.sell_applied_at IS NOT NULL
                 AND s.applied_executed_funds IS NULL) AS q_applied_funds_null,
         EXISTS (SELECT 1 FROM "order" s WHERE s.position_id = p.id AND s.side = 'SELL' AND s.sell_applied_at IS NULL) AS any_unapplied_sell,
         NOT EXISTS (SELECT 1 FROM "order" s WHERE s.position_id = p.id AND s.side = 'SELL') AS q_no_sell_order,
         NOT EXISTS (SELECT 1 FROM "order" a WHERE a.position_id = p.id) AS n_no_orders,
         (COALESCE(p.exit_reason, '') = 'ENTRY_REJECTED' AND COALESCE(p.size, -1) <> 0) AS n_er_size_ne0,
         NOT EXISTS (SELECT 1 FROM "order" b WHERE b.position_id = p.id AND b.side = 'BUY') AS n_no_buy,
         EXISTS (SELECT 1 FROM "order" b WHERE b.position_id = p.id AND b.side = 'BUY' AND b.filled_quantity IS NULL) AS n_buy_qty_null,
         EXISTS (SELECT 1 FROM "order" b WHERE b.position_id = p.id AND b.side = 'BUY'
                 AND COALESCE(b.state, '') NOT IN ('FAILED', 'CANCELLED')) AS n_buy_state_other,
         EXISTS (SELECT 1 FROM "order" b WHERE b.position_id = p.id AND b.side = 'BUY' AND b.state = 'CANCELLED'
                 AND b.fee_source IS NOT NULL AND b.cancel_confirmed_at IS NULL) AS n_buy_cancel_unconf
  FROM position p
  WHERE p.session_kind IN ('LIVE', 'DYNAMIC') AND p.session_id IS NOT NULL AND p.status = 'CLOSED'
    AND p.closed_at IS NOT NULL AND p.closed_at >= (now() - interval '30 days')
    AND ((SELECT COALESCE(SUM(bq.filled_quantity), 0) FROM "order" bq WHERE bq.position_id = p.id AND bq.side = 'BUY' AND bq.filled_quantity > 0)
         > (SELECT COALESCE(SUM(sq.filled_quantity), 0) FROM "order" sq WHERE sq.position_id = p.id AND sq.side = 'SELL' AND sq.sell_applied_at IS NOT NULL
            AND sq.applied_executed_funds IS NOT NULL)
         OR (NOT EXISTS (SELECT 1 FROM "order" x WHERE x.position_id = p.id
               AND ((x.side = 'BUY' AND x.filled_quantity > 0) OR (x.side = 'SELL' AND x.sell_applied_at IS NOT NULL)))
             AND NOT ((COALESCE(p.exit_reason, '') = 'ENTRY_REJECTED' AND COALESCE(p.size, -1) = 0
                       AND NOT EXISTS (SELECT 1 FROM "order" nx WHERE nx.position_id = p.id))
                      OR (EXISTS (SELECT 1 FROM "order" nb WHERE nb.position_id = p.id AND nb.side = 'BUY')
                          AND NOT EXISTS (SELECT 1 FROM "order" nb WHERE nb.position_id = p.id AND nb.side = 'BUY'
                            AND NOT (nb.filled_quantity IS NOT NULL AND nb.filled_quantity = 0 AND (nb.state = 'FAILED'
                              OR (nb.state = 'CANCELLED' AND (nb.fee_source IS NULL OR nb.cancel_confirmed_at IS NOT NULL)))))
                          AND NOT EXISTS (SELECT 1 FROM "order" ns WHERE ns.position_id = p.id AND ns.side = 'SELL'
                            AND ns.sell_applied_at IS NOT NULL)))))),
-- 세션 모드: DYNAMIC → dynamic_session.trading_mode, LIVE → live_trading_session.session_type. 행 없음 = NO_ROW.
md AS (
  SELECT 'A' AS br, s.kind, s.pid, s.t,
         CASE WHEN s.kind = 'DYNAMIC' THEN (SELECT CASE WHEN d.trading_mode IN ('REAL', 'PAPER') THEN d.trading_mode ELSE 'OTHER' END FROM dynamic_session d WHERE d.id = s.sid)
              ELSE (SELECT CASE WHEN l.session_type IN ('REAL', 'PAPER') THEN l.session_type ELSE 'OTHER' END FROM live_trading_session l WHERE l.id = s.sid) END AS mode
  FROM sa s
  UNION ALL
  SELECT 'P', c.kind, c.pid, c.t,
         CASE WHEN c.kind = 'DYNAMIC' THEN (SELECT CASE WHEN d.trading_mode IN ('REAL', 'PAPER') THEN d.trading_mode ELSE 'OTHER' END FROM dynamic_session d WHERE d.id = c.sid)
              ELSE (SELECT CASE WHEN l.session_type IN ('REAL', 'PAPER') THEN l.session_type ELSE 'OTHER' END FROM live_trading_session l WHERE l.id = c.sid) END
  FROM pc c),
modes(mode) AS (VALUES ('REAL'), ('PAPER'), ('NO_ROW'), ('OTHER')),
buckets(bucket, lo, hi) AS (VALUES ('D00_06', 0, 6), ('D07_13', 7, 13), ('D14_20', 14, 20), ('D21_29', 21, 29), ('D30P', 30, 2147483647)),
res AS (
  SELECT 10 AS ord, 'SPLIT' AS tag, k.kind AS c1,
         (SELECT count(*) FROM sa WHERE sa.kind = k.kind)::text AS c2,
         (SELECT count(*) FROM pc WHERE pc.kind = k.kind)::text AS c3,
         ((SELECT count(*) FROM sa WHERE sa.kind = k.kind) + (SELECT count(*) FROM pc WHERE pc.kind = k.kind))::text AS c4,
         NULL::text AS c5, NULL::text AS c6, NULL::text AS c7, NULL::text AS c8
  FROM kinds k
  UNION ALL
  SELECT 20, CASE WHEN br = 'A' THEN 'AM' ELSE 'PM' END, k.kind, m.mode,
         (SELECT count(*) FROM md WHERE md.br = b.br AND md.kind = k.kind AND COALESCE(md.mode, 'NO_ROW') = m.mode)::text,
         NULL, NULL, NULL, NULL, NULL
  FROM kinds k CROSS JOIN modes m CROSS JOIN (VALUES ('A'), ('P')) b(br)
  UNION ALL
  SELECT 30, 'AX', k.kind, x.cls,
         (SELECT count(*) FROM sa WHERE sa.kind = k.kind AND
            CASE x.cls WHEN 'FIELDS_ONLY' THEN f_any AND NOT c_any WHEN 'COST_ONLY' THEN c_any AND NOT f_any ELSE f_any AND c_any END)::text,
         NULL, NULL, NULL, NULL, NULL
  FROM kinds k CROSS JOIN (VALUES ('FIELDS_ONLY'), ('COST_ONLY'), ('FIELDS_AND_COST')) x(cls)
  UNION ALL
  SELECT 40, 'AF', k.kind, f.flag,
         (SELECT count(*) FROM sa WHERE sa.kind = k.kind AND
            CASE f.flag WHEN 'F_FUNDS_NULL' THEN f_funds_null WHEN 'F_FEE_NULL' THEN f_fee_null WHEN 'F_QTY_NULL_OR_LE0' THEN f_qty
                        WHEN 'C_NO_POSITION' THEN c_no_pos WHEN 'C_NO_FILLED_BUY' THEN c_no_filled_buy
                        WHEN 'C_BUY_COST_NULL' THEN c_buy_cost_null ELSE c_buy_qty_null END)::text,
         NULL, NULL, NULL, NULL, NULL
  FROM kinds k CROSS JOIN (VALUES ('F_FUNDS_NULL'), ('F_FEE_NULL'), ('F_QTY_NULL_OR_LE0'), ('C_NO_POSITION'),
                                  ('C_NO_FILLED_BUY'), ('C_BUY_COST_NULL'), ('C_BUY_QTY_NULL')) f(flag)
  UNION ALL
  SELECT 50, 'PX', k.kind, x.cls,
         (SELECT count(*) FROM pc WHERE pc.kind = k.kind AND
            CASE x.cls WHEN 'QTY_SHORT' THEN q_any AND NOT n_any WHEN 'NO_SOURCE' THEN n_any AND NOT q_any ELSE q_any AND n_any END)::text,
         NULL, NULL, NULL, NULL, NULL
  FROM kinds k CROSS JOIN (VALUES ('QTY_SHORT'), ('NO_SOURCE'), ('BOTH')) x(cls)
  UNION ALL
  SELECT 60, 'PF', k.kind, f.flag,
         (SELECT count(*) FROM pc WHERE pc.kind = k.kind AND
            CASE f.flag WHEN 'Q_NO_SETTLED_SELL' THEN q_any AND q_no_settled WHEN 'Q_PARTIAL_SETTLED' THEN q_any AND NOT q_no_settled
                        WHEN 'Q_APPLIED_SELL_FUNDS_NULL' THEN q_any AND q_applied_funds_null WHEN 'Q_UNAPPLIED_SELL' THEN q_any AND any_unapplied_sell
                        WHEN 'Q_NO_SELL_ORDER' THEN q_any AND q_no_sell_order
                        WHEN 'N_NO_ORDERS' THEN n_any AND n_no_orders WHEN 'N_ER_SIZE_NOT_ZERO' THEN n_any AND n_er_size_ne0
                        WHEN 'N_NO_BUY' THEN n_any AND n_no_buy WHEN 'N_BUY_QTY_NULL' THEN n_any AND n_buy_qty_null
                        WHEN 'N_BUY_NOT_FAILED_OR_CANCELLED' THEN n_any AND n_buy_state_other
                        WHEN 'N_BUY_CANCEL_UNCONFIRMED' THEN n_any AND n_buy_cancel_unconf
                        ELSE n_any AND any_unapplied_sell END)::text,
         NULL, NULL, NULL, NULL, NULL
  FROM kinds k CROSS JOIN (VALUES ('Q_NO_SETTLED_SELL'), ('Q_PARTIAL_SETTLED'), ('Q_APPLIED_SELL_FUNDS_NULL'), ('Q_UNAPPLIED_SELL'),
                                  ('Q_NO_SELL_ORDER'), ('N_NO_ORDERS'), ('N_ER_SIZE_NOT_ZERO'), ('N_NO_BUY'), ('N_BUY_QTY_NULL'),
                                  ('N_BUY_NOT_FAILED_OR_CANCELLED'), ('N_BUY_CANCEL_UNCONFIRMED'), ('N_UNAPPLIED_SELL')) f(flag)
  UNION ALL
  SELECT 70, 'PE', pc.kind, pc.er, count(*)::text, NULL, NULL, NULL, NULL, NULL FROM pc GROUP BY pc.kind, pc.er
  UNION ALL
  SELECT 80, 'UQ', k.kind,
         (SELECT count(*) FROM sa WHERE sa.kind = k.kind)::text,
         (SELECT count(DISTINCT pid) FROM sa WHERE sa.kind = k.kind)::text,
         (SELECT count(*) FROM sa WHERE sa.kind = k.kind AND sa.pid IS NULL)::text,
         (SELECT count(*) FROM pc WHERE pc.kind = k.kind)::text,
         (SELECT count(*) FROM (SELECT DISTINCT pid FROM sa WHERE sa.kind = k.kind AND pid IS NOT NULL
                                INTERSECT SELECT pid FROM pc WHERE pc.kind = k.kind) i)::text,
         (SELECT count(*) FROM (SELECT pid FROM sa WHERE sa.kind = k.kind AND pid IS NOT NULL
                                UNION SELECT pid FROM pc WHERE pc.kind = k.kind) u)::text,
         ((SELECT count(*) FROM (SELECT pid FROM sa WHERE sa.kind = k.kind AND pid IS NOT NULL
                                 UNION SELECT pid FROM pc WHERE pc.kind = k.kind) u)
          + (SELECT count(*) FROM sa WHERE sa.kind = k.kind AND sa.pid IS NULL))::text
  FROM kinds k
  UNION ALL
  SELECT 90, 'AGE', k.kind, b.br, bk.bucket,
         (SELECT count(*) FROM md WHERE md.br = b.br AND md.kind = k.kind
            AND floor(extract(epoch FROM (md.t - (now() - interval '30 days'))) / 86400) BETWEEN bk.lo AND bk.hi)::text,
         NULL, NULL, NULL, NULL
  FROM kinds k CROSS JOIN (VALUES ('A'), ('P')) b(br) CROSS JOIN buckets bk
  UNION ALL
  SELECT 95, 'LEG', k.kind, b.br,
         CASE WHEN r.v81 IS NULL THEN NULL ELSE (SELECT count(*) FROM md JOIN position pp ON pp.id = md.pid
            WHERE md.br = b.br AND md.kind = k.kind AND pp.opened_at < r.v81)::text END,
         CASE WHEN r.v87 IS NULL THEN NULL ELSE (SELECT count(*) FROM md JOIN position pp ON pp.id = md.pid
            WHERE md.br = b.br AND md.kind = k.kind AND pp.opened_at < r.v87)::text END,
         (SELECT count(*) FROM md JOIN position pp ON pp.id = md.pid WHERE md.br = b.br AND md.kind = k.kind AND pp.opened_at IS NULL)::text,
         NULL, NULL, NULL
  FROM kinds k CROSS JOIN (VALUES ('A'), ('P')) b(br) CROSS JOIN ref r
)
SELECT tag, c1, c2, c3, c4, c5, c6, c7, c8 FROM res ORDER BY ord, c1, c2, c3;
SELECT 'END', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"');
ROLLBACK;
