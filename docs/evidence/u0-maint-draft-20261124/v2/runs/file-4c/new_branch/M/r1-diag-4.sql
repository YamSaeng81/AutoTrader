-- R1 만료·전체 R1·실자금 실행 상태 최소 진단 r1-diag-4 (총괄 v1.239 §3-1). **운영 실행 승인 아님. 총괄이 창·폴더·횟수를 확정하기 전 실행 금지.**
-- 생성: build_sql.py 가 고정 r1-diag-2.sql(4134c631)에서 ORIG 69~70 · bc 78~82 · sa FROM/WHERE 96~100 · pc q_any 106~108 · n_any 109~118 · pc FROM/WHERE 133~148 줄을 그대로 잘라 붙였다.
--   R1 식(ORIG)·A/P 선택 조건(sa/pc WHERE)·④ 판정식(q_any/n_any)은 r1-diag-2 와 글자 그대로 같다. 보호값 DO 블록·격리 수준·ROLLBACK·META 열 구성도 같고 식별자만 r1-diag-4 다.
-- 뺀 것(v1.239 §3-1): strategy_log·E/D/O/S 연결·원인 태그·AM/PM/AX/AF/PX/PF/PE/UQ/AGE/LEG/REF. 금액·수량·가격·손익·행 ID·원시 사유 문자열은 출력하지 않는다.
-- 고정 상수(직전 진단 r1-diag-read-20261124-1339 의 META, r1.tsv 원문 그대로):
--   PRIOR_NOW = 2026-11-24T04:49:14.512077Z (직전 db_now) · KNOWN_LO = 2026-10-25T04:49:14.512077Z (직전 since) · KNOWN_HI = KNOWN_LO + 7일 (직전 AGE D00_06 상한)
--   KNOWN 부류 = 직전 진단에서 본 9건의 부류: DYNAMIC · P 분기 · ④ NO_SOURCE(n_any AND NOT q_any) · 세션 trading_mode REAL · KNOWN_LO ≤ closed_at < KNOWN_HI · 같은 포지션이 A 분기에 없음.
--   **부류 판정일 뿐 같은 행이라는 증거가 아니다**(행 ID를 읽지 않는다). A 분기 행은 전부 OTHER 다. KNOWN 행 수가 9 이하인지는 검사기·판정자가 본다.
-- 시각: 기준은 트랜잭션 시각 now(). 만료 경계 = t + 30일(A: sell_applied_at, P: closed_at). 제품 조건이 t ≥ 기준−30일 이므로 기준 시각이 경계와 같아도 남고, **경계보다 엄격히 뒤**일 때 빠진다.
--   NEXT_MINUTE = date_trunc('minute', 마지막 경계 UTC) + 1분 — 경계가 정각 분이어도 그보다 엄격히 뒤다. 제품은 앱 시계(Instant.now())라 DB 시계와 수 초 차이가 날 수 있다(여유는 판정자가 더한다).
-- NEW = t > PRIOR_NOW 인 R1 행 수. sell_applied_at/closed_at 값 기준일 뿐 그 뒤의 모든 삽입·수정·삭제 이력을 증명하지 않는다(값을 과거로 고친 행·지워진 행은 보이지 않는다).
-- 실자금 상태: 세션 행의 모드로 소유를 가른다(LIVE → live_trading_session.session_type, DYNAMIC → dynamic_session.trading_mode). 소유 불명은 REAL 로도 0 으로도 메우지 않고 따로 낸다.
--   주문 미해결 제출 = INTENT_READY·SUBMITTING·UNKNOWN (OrderRepository.findUnresolvedSubmissions) · 그 밖 활성 = PENDING·SUBMITTED·PARTIAL_FILLED (TradingConstants.ACTIVE_ORDER_STATES 의 나머지)
--   포지션 OPEN·CLOSING · 정리 대기 = position_cleanup.done_at IS NULL. 옛 고아(session_id NULL) 행은 ORPH 로 따로 센다(정상으로 재분류하지 않는다).
-- 보호값: PGOPTIONS 로 statement_timeout=15s·lock_timeout=2s. 첫 DO 블록이 확인하고 다르면 예외(psql rc 3). READ ONLY·REPEATABLE READ·ROLLBACK.
-- 읽는 표: "order", position, position_cleanup, dynamic_session, live_trading_session, flyway_schema_history. paper_trading 스키마·522~587·strategy_log 는 읽지 않는다.
-- 출력(탭 구분, NULL = \N, 정수는 전부 개수). 열 수 고정: META 10 · PRIOR 4 · ORIG 3 · END 2 · 그 밖(본문 행) tag+c1~c6 = 7열(쓰지 않는 칸 \N):
--   META   r1-diag-4 db_now db_clock since flyway_max isolation timezone statement_timeout lock_timeout
--   PRIOR  prior_now known_lo known_hi
--   ORIG   kind r1_original                                   (고정 SQL R1 식 그대로)
--   SPLIT  kind a_rows p_rows a_plus_p                         (a_plus_p = ORIG)
--   EXP    kind class rows first_boundary last_boundary next_minute   class: KNOWN | OTHER | ALL (rows 0 이면 시각 세 칸 \N)
--   NEW    kind branch rows                                   branch: A | P  (t > PRIOR_NOW)
--   RS     kind status sessions                               REAL 세션 상태: CREATED RUNNING PAUSED STOPPED EMERGENCY_STOPPED DELETED OTHER
--   RM     kind mode sessions                                 전체 세션 모드: REAL PAPER OTHER(NULL 포함)
--   RX     kind item rows                                     REAL 소유 item: POS_OPEN POS_CLOSING ORD_UNRESOLVED_SUBMIT ORD_ACTIVE_OTHER CLEANUP_PENDING
--   OW     owner item rows                                    REAL 밖 owner: PAPER DYN_PAPER NO_SESSION_ID KIND_OTHER NO_ROW MODE_OTHER (뒤 넷 = 소유 불명)
--   TOT    item rows                                          item 전체(= RX 두 종류 + OW 여섯 owner 합)
--   UST    table rows                                         ORDER(state 가 알려진 9개 밖·NULL) · POSITION(status 가 OPEN/CLOSING/CLOSED 밖·NULL) — 소유 무관
--   ORPH   table class rows                                   session_id NULL 행: ORDER ACTIVE/TERMINAL/STATE_OTHER · POSITION ACTIVE/CLOSED/STATUS_OTHER
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
SELECT 'META', 'r1-diag-4',
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       to_char((now() - interval '30 days') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       (SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'),
       current_setting('transaction_isolation'),
       current_setting('TimeZone'),
       current_setting('statement_timeout'),
       current_setting('lock_timeout');

SELECT 'PRIOR', '2026-11-24T04:49:14.512077+00:00', '2026-10-25T04:49:14.512077+00:00', '2026-11-01T04:49:14.512077+00:00';

-- ORIG: 고정 predeploy_read.v3.sql R1 식 그대로(r1-diag-2 69~70행 복사).
SELECT 'ORIG', k.kind, (SELECT (SELECT COUNT(*) FROM "order" o   WHERE o.side = 'SELL' AND o.session_id IS NOT NULL AND o.session_kind = k.kind   AND o.sell_applied_at IS NOT NULL AND o.sell_applied_at >= (now() - interval '30 days')   AND (NOT (o.applied_executed_funds IS NOT NULL AND o.applied_paid_fee IS NOT NULL AND o.filled_quantity IS NOT NULL AND o.filled_quantity > 0)        OR NOT EXISTS (SELECT 1 FROM (SELECT bo.position_id, SUM(bo.applied_executed_funds + bo.applied_paid_fee) AS cost, SUM(bo.filled_quantity) AS qty FROM "order" bo WHERE bo.side = 'BUY' AND bo.position_id IS NOT NULL AND bo.filled_quantity > 0 GROUP BY bo.position_id HAVING COUNT(*) = COUNT(bo.applied_executed_funds) AND COUNT(*) = COUNT(bo.applied_paid_fee)) b WHERE b.position_id = o.position_id))) + (SELECT COUNT(*) FROM position p   WHERE p.session_kind = k.kind AND p.session_id IS NOT NULL AND p.status = 'CLOSED'   AND p.closed_at IS NOT NULL AND p.closed_at >= (now() - interval '30 days')   AND ((SELECT COALESCE(SUM(bq.filled_quantity), 0) FROM "order" bq        WHERE bq.position_id = p.id AND bq.side = 'BUY' AND bq.filled_quantity > 0)     > (SELECT COALESCE(SUM(sq.filled_quantity), 0) FROM "order" sq        WHERE sq.position_id = p.id AND sq.side = 'SELL' AND sq.sell_applied_at IS NOT NULL        AND sq.applied_executed_funds IS NOT NULL)     OR (NOT EXISTS (SELECT 1 FROM "order" x WHERE x.position_id = p.id           AND ((x.side = 'BUY' AND x.filled_quantity > 0) OR (x.side = 'SELL' AND x.sell_applied_at IS NOT NULL)))         AND NOT (((COALESCE(p.exit_reason, '') = 'ENTRY_REJECTED' AND COALESCE(p.size, -1) = 0   AND NOT EXISTS (SELECT 1 FROM "order" nx WHERE nx.position_id = p.id)) OR (EXISTS (SELECT 1 FROM "order" nb WHERE nb.position_id = p.id AND nb.side = 'BUY') AND NOT EXISTS (SELECT 1 FROM "order" nb WHERE nb.position_id = p.id AND nb.side = 'BUY'   AND NOT (nb.filled_quantity IS NOT NULL AND nb.filled_quantity = 0 AND (nb.state = 'FAILED'     OR (nb.state = 'CANCELLED' AND (nb.fee_source IS NULL OR nb.cancel_confirmed_at IS NOT NULL))))) AND NOT EXISTS (SELECT 1 FROM "order" ns WHERE ns.position_id = p.id AND ns.side = 'SELL'   AND ns.sell_applied_at IS NOT NULL)))))))) AS undetermined_30d
FROM (VALUES ('LIVE'), ('DYNAMIC')) k(kind);

WITH
kinds(kind) AS (VALUES ('LIVE'), ('DYNAMIC')),
cst AS (SELECT timestamptz '2026-11-24 04:49:14.512077+00' AS prior_now,
               timestamptz '2026-10-25 04:49:14.512077+00' AS known_lo,
               timestamptz '2026-11-01 04:49:14.512077+00' AS known_hi),
bc AS (SELECT bo.position_id, SUM(bo.applied_executed_funds + bo.applied_paid_fee) AS cost,
              SUM(bo.filled_quantity) AS qty FROM "order" bo
       WHERE bo.side = 'BUY' AND bo.position_id IS NOT NULL AND bo.filled_quantity > 0
       GROUP BY bo.position_id
       HAVING COUNT(*) = COUNT(bo.applied_executed_funds) AND COUNT(*) = COUNT(bo.applied_paid_fee)),
-- (A) 제품 첫 COUNT 조건 그대로(r1-diag-2 96~100행 복사)
sa AS (
  SELECT o.session_kind AS kind, o.session_id AS sid, o.position_id AS pid, o.sell_applied_at AS t
  FROM "order" o
  WHERE o.side = 'SELL' AND o.session_id IS NOT NULL AND o.session_kind IN ('LIVE', 'DYNAMIC')
    AND o.sell_applied_at IS NOT NULL AND o.sell_applied_at >= (now() - interval '30 days')
    AND (NOT (o.applied_executed_funds IS NOT NULL AND o.applied_paid_fee IS NOT NULL AND o.filled_quantity IS NOT NULL AND o.filled_quantity > 0)
         OR NOT EXISTS (SELECT 1 FROM bc b WHERE b.position_id = o.position_id))),
-- (P) 제품 둘째 COUNT 조건 그대로(q_any 106~108 · n_any 109~118 · FROM/WHERE 133~148행 복사)
pc AS (
  SELECT p.session_kind AS kind, p.session_id AS sid, p.id AS pid, p.closed_at AS t,
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
                         AND ns.sell_applied_at IS NOT NULL)))) AS n_any
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
tg AS (
  SELECT 'A' AS br, s.kind, s.t, false AS known FROM sa s
  UNION ALL
  SELECT 'P', c.kind, c.t,
         COALESCE(c.kind = 'DYNAMIC' AND c.n_any AND NOT c.q_any
                  AND (SELECT d.trading_mode FROM dynamic_session d WHERE d.id = c.sid) = 'REAL'
                  AND c.t >= x.known_lo AND c.t < x.known_hi
                  AND NOT EXISTS (SELECT 1 FROM sa WHERE sa.pid = c.pid), false)
  FROM pc c CROSS JOIN cst x),
own AS (
  SELECT o.session_kind AS kind, o.session_id AS sid,
         CASE WHEN o.state IN ('INTENT_READY', 'SUBMITTING', 'UNKNOWN') THEN 'ORD_UNRESOLVED_SUBMIT' ELSE 'ORD_ACTIVE_OTHER' END AS item
  FROM "order" o WHERE o.state IN ('PENDING', 'INTENT_READY', 'SUBMITTING', 'UNKNOWN', 'SUBMITTED', 'PARTIAL_FILLED')
  UNION ALL
  SELECT p.session_kind, p.session_id, CASE WHEN p.status = 'OPEN' THEN 'POS_OPEN' ELSE 'POS_CLOSING' END
  FROM position p WHERE p.status IN ('OPEN', 'CLOSING')
  UNION ALL
  SELECT c.session_kind, c.session_id, 'CLEANUP_PENDING' FROM position_cleanup c WHERE c.done_at IS NULL),
oc AS (
  SELECT own.kind, own.item,
         CASE WHEN own.sid IS NULL THEN 'NO_SESSION_ID'
              WHEN own.kind = 'DYN_PAPER' THEN 'DYN_PAPER'
              WHEN own.kind = 'LIVE' THEN COALESCE((SELECT CASE WHEN l.session_type IN ('REAL', 'PAPER') THEN l.session_type ELSE 'MODE_OTHER' END
                                                    FROM live_trading_session l WHERE l.id = own.sid), 'NO_ROW')
              WHEN own.kind = 'DYNAMIC' THEN COALESCE((SELECT CASE WHEN d.trading_mode IN ('REAL', 'PAPER') THEN d.trading_mode ELSE 'MODE_OTHER' END
                                                       FROM dynamic_session d WHERE d.id = own.sid), 'NO_ROW')
              ELSE 'KIND_OTHER' END AS owner
  FROM own),
items(item) AS (VALUES ('POS_OPEN'), ('POS_CLOSING'), ('ORD_UNRESOLVED_SUBMIT'), ('ORD_ACTIVE_OTHER'), ('CLEANUP_PENDING')),
owners(owner) AS (VALUES ('PAPER'), ('DYN_PAPER'), ('NO_SESSION_ID'), ('KIND_OTHER'), ('NO_ROW'), ('MODE_OTHER')),
sts(st) AS (VALUES ('CREATED'), ('RUNNING'), ('PAUSED'), ('STOPPED'), ('EMERGENCY_STOPPED'), ('DELETED'), ('OTHER')),
ses AS (
  SELECT 'LIVE' AS kind, CASE WHEN l.session_type IN ('REAL', 'PAPER') THEN l.session_type ELSE 'OTHER' END AS mode,
         CASE WHEN l.status IN ('CREATED', 'RUNNING', 'PAUSED', 'STOPPED', 'EMERGENCY_STOPPED', 'DELETED') THEN l.status ELSE 'OTHER' END AS st
  FROM live_trading_session l
  UNION ALL
  SELECT 'DYNAMIC', CASE WHEN d.trading_mode IN ('REAL', 'PAPER') THEN d.trading_mode ELSE 'OTHER' END,
         CASE WHEN d.status IN ('CREATED', 'RUNNING', 'PAUSED', 'STOPPED', 'EMERGENCY_STOPPED', 'DELETED') THEN d.status ELSE 'OTHER' END
  FROM dynamic_session d),
res AS (
  SELECT 10 AS ord, 'SPLIT' AS tag, k.kind AS c1,
         (SELECT count(*) FROM sa WHERE sa.kind = k.kind)::text AS c2,
         (SELECT count(*) FROM pc WHERE pc.kind = k.kind)::text AS c3,
         ((SELECT count(*) FROM sa WHERE sa.kind = k.kind) + (SELECT count(*) FROM pc WHERE pc.kind = k.kind))::text AS c4,
         NULL::text AS c5, NULL::text AS c6
  FROM kinds k
  UNION ALL
  SELECT 20, 'EXP', k.kind, cl.cls, count(tg.t)::text,
         to_char(min(tg.t + interval '30 days') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
         to_char(max(tg.t + interval '30 days') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
         to_char(date_trunc('minute', max(tg.t + interval '30 days') AT TIME ZONE 'UTC') + interval '1 minute', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"')
  FROM kinds k CROSS JOIN (VALUES ('KNOWN'), ('OTHER'), ('ALL')) cl(cls)
  LEFT JOIN tg ON tg.kind = k.kind AND (cl.cls = 'ALL' OR (cl.cls = 'KNOWN') = tg.known)
  GROUP BY k.kind, cl.cls
  UNION ALL
  SELECT 30, 'NEW', k.kind, b.br,
         (SELECT count(*) FROM tg CROSS JOIN cst x WHERE tg.kind = k.kind AND tg.br = b.br AND tg.t > x.prior_now)::text,
         NULL, NULL, NULL
  FROM kinds k CROSS JOIN (VALUES ('A'), ('P')) b(br)
  UNION ALL
  SELECT 40, 'RS', k.kind, s.st, (SELECT count(*) FROM ses WHERE ses.kind = k.kind AND ses.mode = 'REAL' AND ses.st = s.st)::text,
         NULL, NULL, NULL
  FROM kinds k CROSS JOIN sts s
  UNION ALL
  SELECT 50, 'RM', k.kind, m.mode, (SELECT count(*) FROM ses WHERE ses.kind = k.kind AND ses.mode = m.mode)::text, NULL, NULL, NULL
  FROM kinds k CROSS JOIN (VALUES ('REAL'), ('PAPER'), ('OTHER')) m(mode)
  UNION ALL
  SELECT 60, 'RX', k.kind, i.item, (SELECT count(*) FROM oc WHERE oc.owner = 'REAL' AND oc.kind = k.kind AND oc.item = i.item)::text,
         NULL, NULL, NULL
  FROM kinds k CROSS JOIN items i
  UNION ALL
  SELECT 70, 'OW', w.owner, i.item, (SELECT count(*) FROM oc WHERE oc.owner = w.owner AND oc.item = i.item)::text, NULL, NULL, NULL
  FROM owners w CROSS JOIN items i
  UNION ALL
  SELECT 80, 'TOT', i.item, (SELECT count(*) FROM oc WHERE oc.item = i.item)::text, NULL, NULL, NULL, NULL
  FROM items i
  UNION ALL
  SELECT 90, 'UST', 'ORDER', (SELECT count(*) FROM "order" o WHERE o.state IS NULL OR o.state NOT IN
           ('PENDING', 'INTENT_READY', 'SUBMITTING', 'UNKNOWN', 'SUBMITTED', 'PARTIAL_FILLED', 'FILLED', 'CANCELLED', 'FAILED'))::text,
         NULL, NULL, NULL, NULL
  UNION ALL
  SELECT 90, 'UST', 'POSITION', (SELECT count(*) FROM position p WHERE p.status IS NULL OR p.status NOT IN ('OPEN', 'CLOSING', 'CLOSED'))::text,
         NULL, NULL, NULL, NULL
  UNION ALL
  SELECT 95, 'ORPH', 'ORDER', g.cls, (SELECT count(*) FROM "order" o WHERE o.session_id IS NULL AND
           CASE WHEN o.state IN ('PENDING', 'INTENT_READY', 'SUBMITTING', 'UNKNOWN', 'SUBMITTED', 'PARTIAL_FILLED') THEN 'ACTIVE'
                WHEN o.state IN ('FILLED', 'CANCELLED', 'FAILED') THEN 'TERMINAL' ELSE 'STATE_OTHER' END = g.cls)::text, NULL, NULL, NULL
  FROM (VALUES ('ACTIVE'), ('TERMINAL'), ('STATE_OTHER')) g(cls)
  UNION ALL
  SELECT 95, 'ORPH', 'POSITION', g.cls, (SELECT count(*) FROM position p WHERE p.session_id IS NULL AND
           CASE WHEN p.status IN ('OPEN', 'CLOSING') THEN 'ACTIVE' WHEN p.status = 'CLOSED' THEN 'CLOSED' ELSE 'STATUS_OTHER' END = g.cls)::text,
         NULL, NULL, NULL
  FROM (VALUES ('ACTIVE'), ('CLOSED'), ('STATUS_OTHER')) g(cls)
)
SELECT tag, c1, c2, c3, c4, c5, c6 FROM res ORDER BY ord, c1, c2, c3;
SELECT 'END', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"');
ROLLBACK;
