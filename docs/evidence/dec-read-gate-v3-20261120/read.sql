-- 등록 세션 읽기 검토안 v3(총괄 v1.205 §2·§4-2, R3 등록 밖 종결 행 기본 STOP). v2 = ../dec-read-gate-v2-20261120/read.sql 보존.
-- 원 v2 머리말: 등록 세션 읽기 검토안 v2(총괄 v1.204 §3-1, R1~R3 보완) — 운영 실행 금지. 승인 전에는 운영 DB 에 돌리지 않는다.
-- 앞 판 ../dec-read-plan-20261120/read.sql 은 보존한다. 대상 스키마: d81c3f2f / 운영 0e1d2a02(관련 엔티티 열 이름 동일).
-- 호출 예: psql -X -q -v ON_ERROR_STOP=1 -v live_ids='{203}' -v dyn_ids='{}' -v reg_sha256='<등록표 sha256>' -f read.sql
--   live_ids·dyn_ids = 해시 고정 등록표의 kind 별 id 배열, reg_sha256 = 그 등록표 파일 sha256(파서가 다시 대조).
--   window_from 은 v2 에서 없앴다. 시간 필터로 대상 범위를 정하지 않는다(R3).
-- 바뀐 점(v1 → v2):
--   R1 소유 분류를 CASE 로 먼저 확정(NULL 이 비교식에 남지 않음). NO_SESSION_ID·NO_SESSION_ROW·UNKNOWN_KIND·UNCLASSIFIED 는
--      시각·상태와 관계없이 게이트 STOP. 게이트 값은 count(*)=0 으로만 만들고 NULL 가능 조건을 쓰지 않는다.
--   R2 등록 (kind,id) 마다 실제 존재·모드를 같은 스냅숏에서 확인(REG_OK / REG_MISSING / REG_MODE). 등록표가 비면 STOP.
--      DYNAMIC 은 trading_mode='REAL' 과 정확히 같아야 하며 PAPER·NULL·기타는 REG_MODE 로 STOP. 2단계 전에 끝난다.
--   R3 2단계는 등록 세션의 주문·포지션 원천 전체(허용 열)를 시간 필터 없이 읽는다. 1단계 등록 밖 판정도 created_at 을 쓰지 않는다.
-- 바뀐 점(v2 → v3, 총괄 v1.205 §2):
--   R3 등록 밖(UNREG) 주문·포지션은 종결·정산 확정·CLOSED 여도 STOP 이다. 입력 계약에 '기준 이전부터 있었고 바뀌지 않았다'는
--      목록/버전 근거가 없으므로 종결 상태만으로 과거 거래로 면제하지 않는다(최근 완료된 미등록 거래와 구별 불가).
--      과거 종결 행도 근거가 없으면 STOP 이다(v2 A 의 주문21·포지션20 GO 기대를 STOP 으로 바꿈). 예외는 별도 검토 출처로만 정한다.
--      등록 밖 STOPPED 세션 행 자체(주문·포지션 없음)는 v2 그대로 금액 없이 목록만 남긴다.
\set ON_ERROR_STOP 1
\pset format unaligned
\pset tuples_only on
\pset fieldsep '\t'
\pset null '\\N'

BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
-- 첫 문장에서 스냅숏이 고정된다. now() = 트랜잭션 시작(db 축), clock_timestamp() = 실제 벽시계(db 축). 월말 정확 시각 보증 아님.
SELECT 'META', 'v3', now(), clock_timestamp(), pg_current_snapshot()::text,
       (SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'), current_setting('transaction_isolation'),
       :'reg_sha256', :'live_ids', :'dyn_ids';

-- 분류식은 READ ONLY 트랜잭션이라 임시 뷰를 만들지 않고 각 문장에 반복한다(게이트 문장과 ID 목록이 같은 식이어야 파서 재계산과 맞는다).
-- 주문 소유 분류: 먼저 소유 이상, 다음 DYNAMIC PAPER, 다음 등록 여부. CASE 는 NULL 비교가 거짓으로 떨어지므로 ELSE 를 이상으로 둔다.
-- 터미널 상태 = FILLED·CANCELLED·FAILED(제품 mapExchangeState). 그 밖(NULL·미지 문자열 포함)은 진행 중으로 본다.

-- ── 1단계-가: 등록 대조(금액 없음) ──────────────────────────────────────
SELECT 'ID_R', r.kind, r.id,
       CASE WHEN r.kind = 'LIVE' AND l.id IS NULL THEN 'REG_MISSING'
            WHEN r.kind = 'LIVE' THEN 'REG_OK'
            WHEN d.id IS NULL THEN 'REG_MISSING'
            WHEN d.trading_mode IS NOT DISTINCT FROM 'REAL' THEN 'REG_OK'
            ELSE 'REG_MODE' END,
       CASE WHEN r.kind = 'LIVE' THEN CASE WHEN l.id IS NULL THEN NULL ELSE 'REAL' END ELSE d.trading_mode END,
       coalesce(l.status, d.status)
  FROM (SELECT 'LIVE' kind, unnest(:'live_ids'::bigint[]) id UNION ALL SELECT 'DYNAMIC', unnest(:'dyn_ids'::bigint[])) r
  LEFT JOIN live_trading_session l ON r.kind = 'LIVE' AND l.id = r.id
  LEFT JOIN dynamic_session d ON r.kind = 'DYNAMIC' AND d.id = r.id
 ORDER BY 2, 3;

-- ── 1단계-나: 등록 밖 실자금 세션·주문·포지션 식별 목록(금액·가격·대금 열 없음) ─────────────
-- 세션: 등록 밖이며 DYNAMIC PAPER 가 아닌 것 전부. STOPPED 가 아니면 STOP 사유.
SELECT 'ID_S', 'LIVE', id, status, (status IS DISTINCT FROM 'STOPPED')
  FROM live_trading_session WHERE NOT (id = ANY(:'live_ids'::bigint[]))
UNION ALL
SELECT 'ID_S', 'DYNAMIC', id, status, (status IS DISTINCT FROM 'STOPPED')
  FROM dynamic_session WHERE trading_mode IS DISTINCT FROM 'PAPER' AND NOT (id = ANY(:'dyn_ids'::bigint[]))
ORDER BY 2, 3;

-- 주문: 등록 세션 소유가 아닌 모든 비 DYNAMIC-PAPER 주문. 생성 시각 필터 없음.
WITH c AS (
  SELECT o.id, o.session_kind, o.session_id, o.state, o.position_id,
         CASE WHEN o.session_id IS NULL THEN 'NO_SESSION_ID'
              WHEN o.session_kind IS NULL OR o.session_kind NOT IN ('LIVE','DYNAMIC') THEN 'UNKNOWN_KIND'
              WHEN o.session_kind = 'LIVE' AND l.id IS NULL THEN 'NO_SESSION_ROW'
              WHEN o.session_kind = 'DYNAMIC' AND d.id IS NULL THEN 'NO_SESSION_ROW'
              WHEN o.session_kind = 'DYNAMIC' AND d.trading_mode = 'PAPER' THEN 'DYN_PAPER'
              WHEN o.session_kind = 'LIVE' AND o.session_id = ANY(:'live_ids'::bigint[]) THEN 'REG'
              WHEN o.session_kind = 'DYNAMIC' AND o.session_id = ANY(:'dyn_ids'::bigint[]) THEN 'REG'
              WHEN o.session_kind IN ('LIVE','DYNAMIC') THEN 'UNREG'
              ELSE 'UNCLASSIFIED' END AS owner,
         (o.state IS NULL OR o.state NOT IN ('FILLED','CANCELLED','FAILED')) AS active,
         (o.fill_accounting_pending OR (NOT o.settlement_final AND o.fee_source IS NOT NULL)) IS NOT FALSE AS unsettled,
         EXISTS (SELECT 1 FROM order_settlement_discrepancy x WHERE x.order_id = o.id AND x.resolved_at IS NULL) AS open_disc,
         EXISTS (SELECT 1 FROM position p WHERE p.id = o.position_id AND p.status IS DISTINCT FROM 'CLOSED') AS open_pos
    FROM "order" o
    LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
    LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id)
SELECT 'ID_O', id, session_kind, session_id, state, owner,
       (owner IN ('NO_SESSION_ID','NO_SESSION_ROW','UNKNOWN_KIND','UNCLASSIFIED','UNREG')),
       -- 참고 표시(판정에 쓰지 않음): 진행·미정산·미해결 차이·비CLOSED 포지션 연결
       (active OR unsettled OR open_disc OR open_pos)
  FROM c WHERE owner NOT IN ('REG','DYN_PAPER') ORDER BY id;

-- 포지션: 같은 분류. 소유 이상은 상태와 관계없이 STOP, 등록 밖은 CLOSED 가 아니면 STOP.
WITH c AS (
  SELECT p.id, p.session_kind, p.session_id, p.status,
         CASE WHEN p.session_id IS NULL THEN 'NO_SESSION_ID'
              WHEN p.session_kind IS NULL OR p.session_kind NOT IN ('LIVE','DYNAMIC') THEN 'UNKNOWN_KIND'
              WHEN p.session_kind = 'LIVE' AND l.id IS NULL THEN 'NO_SESSION_ROW'
              WHEN p.session_kind = 'DYNAMIC' AND d.id IS NULL THEN 'NO_SESSION_ROW'
              WHEN p.session_kind = 'DYNAMIC' AND d.trading_mode = 'PAPER' THEN 'DYN_PAPER'
              WHEN p.session_kind = 'LIVE' AND p.session_id = ANY(:'live_ids'::bigint[]) THEN 'REG'
              WHEN p.session_kind = 'DYNAMIC' AND p.session_id = ANY(:'dyn_ids'::bigint[]) THEN 'REG'
              WHEN p.session_kind IN ('LIVE','DYNAMIC') THEN 'UNREG'
              ELSE 'UNCLASSIFIED' END AS owner
    FROM position p
    LEFT JOIN live_trading_session l ON p.session_kind = 'LIVE' AND l.id = p.session_id
    LEFT JOIN dynamic_session d ON p.session_kind = 'DYNAMIC' AND d.id = p.session_id)
SELECT 'ID_P', id, session_kind, session_id, status, owner,
       (owner IN ('NO_SESSION_ID','NO_SESSION_ROW','UNKNOWN_KIND','UNCLASSIFIED','UNREG'))
  FROM c WHERE owner NOT IN ('REG','DYN_PAPER') ORDER BY id;

-- ── 게이트: 위와 같은 분류식을 한 문장으로 다시 세어 0 일 때만 GO. 결과는 항상 t/f(count 는 NULL 이 아님) ─────
SELECT (count(*) = 0) AS gate_go, count(*) AS gate_bad FROM (
  -- 등록표 비어 있음
  SELECT 'REG_EMPTY' WHERE coalesce(cardinality(:'live_ids'::bigint[]), 0) + coalesce(cardinality(:'dyn_ids'::bigint[]), 0) = 0
  -- 등록 대조 실패
  UNION ALL SELECT 'REG' FROM (SELECT 'LIVE' kind, unnest(:'live_ids'::bigint[]) id UNION ALL SELECT 'DYNAMIC', unnest(:'dyn_ids'::bigint[])) r
    LEFT JOIN live_trading_session l ON r.kind = 'LIVE' AND l.id = r.id
    LEFT JOIN dynamic_session d ON r.kind = 'DYNAMIC' AND d.id = r.id
   WHERE NOT coalesce(CASE WHEN r.kind = 'LIVE' THEN l.id IS NOT NULL ELSE d.trading_mode IS NOT DISTINCT FROM 'REAL' AND d.id IS NOT NULL END, false)
  -- 등록 밖 진행 세션
  UNION ALL SELECT 'S' FROM live_trading_session WHERE NOT (id = ANY(:'live_ids'::bigint[])) AND status IS DISTINCT FROM 'STOPPED'
  UNION ALL SELECT 'S' FROM dynamic_session WHERE trading_mode IS DISTINCT FROM 'PAPER' AND NOT (id = ANY(:'dyn_ids'::bigint[]))
                                             AND status IS DISTINCT FROM 'STOPPED'
  -- 주문
  UNION ALL SELECT 'O' FROM (
    SELECT CASE WHEN o.session_id IS NULL THEN 'X'
                WHEN o.session_kind IS NULL OR o.session_kind NOT IN ('LIVE','DYNAMIC') THEN 'X'
                WHEN o.session_kind = 'LIVE' AND l.id IS NULL THEN 'X'
                WHEN o.session_kind = 'DYNAMIC' AND d.id IS NULL THEN 'X'
                WHEN o.session_kind = 'DYNAMIC' AND d.trading_mode = 'PAPER' THEN 'P'
                WHEN o.session_kind = 'LIVE' AND o.session_id = ANY(:'live_ids'::bigint[]) THEN 'R'
                WHEN o.session_kind = 'DYNAMIC' AND o.session_id = ANY(:'dyn_ids'::bigint[]) THEN 'R'
                WHEN o.session_kind IN ('LIVE','DYNAMIC') THEN 'U'
                ELSE 'X' END AS owner,
           ((o.state IS NULL OR o.state NOT IN ('FILLED','CANCELLED','FAILED'))
            OR (o.fill_accounting_pending OR (NOT o.settlement_final AND o.fee_source IS NOT NULL)) IS NOT FALSE
            OR EXISTS (SELECT 1 FROM order_settlement_discrepancy x WHERE x.order_id = o.id AND x.resolved_at IS NULL)
            OR EXISTS (SELECT 1 FROM position p WHERE p.id = o.position_id AND p.status IS DISTINCT FROM 'CLOSED')) AS live_relevant
      FROM "order" o
      LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
      LEFT JOIN dynamic_session d ON o.session_kind = 'DYNAMIC' AND d.id = o.session_id) c
   WHERE owner IN ('X','U')   -- v3: 등록 밖은 종결이어도 STOP(live_relevant 는 판정에 쓰지 않음)
  -- 포지션
  UNION ALL SELECT 'P' FROM (
    SELECT p.status,
           CASE WHEN p.session_id IS NULL THEN 'X'
                WHEN p.session_kind IS NULL OR p.session_kind NOT IN ('LIVE','DYNAMIC') THEN 'X'
                WHEN p.session_kind = 'LIVE' AND l.id IS NULL THEN 'X'
                WHEN p.session_kind = 'DYNAMIC' AND d.id IS NULL THEN 'X'
                WHEN p.session_kind = 'DYNAMIC' AND d.trading_mode = 'PAPER' THEN 'P'
                WHEN p.session_kind = 'LIVE' AND p.session_id = ANY(:'live_ids'::bigint[]) THEN 'R'
                WHEN p.session_kind = 'DYNAMIC' AND p.session_id = ANY(:'dyn_ids'::bigint[]) THEN 'R'
                WHEN p.session_kind IN ('LIVE','DYNAMIC') THEN 'U'
                ELSE 'X' END AS owner
      FROM position p
      LEFT JOIN live_trading_session l ON p.session_kind = 'LIVE' AND l.id = p.session_id
      LEFT JOIN dynamic_session d ON p.session_kind = 'DYNAMIC' AND d.id = p.session_id) c
   WHERE owner IN ('X','U')   -- v3: 등록 밖 포지션은 CLOSED 여도 STOP
) x \gset
SELECT 'GATE_N', :'gate_bad';
\if :gate_go
SELECT 'GATE', 'GO';
-- ── 2단계: 등록 세션(모드 확인 끝남)의 허용 열 원천 전체(같은 트랜잭션·같은 스냅숏, 시간 필터 없음) ──────
SELECT 'S', 'LIVE', id, 'REAL', strategy_type, timeframe, coin_pair, initial_capital, available_krw, status, created_at, stopped_at
  FROM live_trading_session WHERE id = ANY(:'live_ids'::bigint[])
UNION ALL
SELECT 'S', 'DYNAMIC', id, trading_mode, strategy_type, timeframe, 'DYNAMIC', initial_capital, available_krw, status, created_at, stopped_at
  FROM dynamic_session WHERE id = ANY(:'dyn_ids'::bigint[]) AND trading_mode = 'REAL'
ORDER BY 2, 3;
SELECT 'O', o.id, o.session_kind, o.session_id, o.position_id, o.coin_pair, o.side, o.order_type, o.quantity, o.reserved_krw,
       o.client_order_id, o.state, o.filled_quantity, o.exchange_order_id, o.created_at, o.filled_at, o.sell_applied_at,
       o.reported_executed_funds, o.reported_paid_fee, o.applied_executed_funds, o.applied_paid_fee,
       o.settlement_final, o.fill_accounting_pending, o.reported_observed_at
  FROM "order" o
 WHERE (o.session_kind = 'LIVE' AND o.session_id = ANY(:'live_ids'::bigint[]))
    OR (o.session_kind = 'DYNAMIC' AND o.session_id = ANY(:'dyn_ids'::bigint[]))
 ORDER BY o.id;
SELECT 'P', p.id, p.session_kind, p.session_id, p.coin_pair, p.size, p.status, p.invested_krw, p.position_fee, p.ruleset_hash,
       p.opened_at, p.closing_at, p.closed_at
  FROM position p
 WHERE (p.session_kind = 'LIVE' AND p.session_id = ANY(:'live_ids'::bigint[]))
    OR (p.session_kind = 'DYNAMIC' AND p.session_id = ANY(:'dyn_ids'::bigint[]))
 ORDER BY p.id;
SELECT 'D', d.id, d.order_id, d.kind, d.detected_at
  FROM order_settlement_discrepancy d JOIN "order" o ON o.id = d.order_id
 WHERE d.resolved_at IS NULL
   AND ((o.session_kind = 'LIVE' AND o.session_id = ANY(:'live_ids'::bigint[]))
     OR (o.session_kind = 'DYNAMIC' AND o.session_id = ANY(:'dyn_ids'::bigint[])))
 ORDER BY d.id;
-- 행 수 대조(파서가 실제 행 수와 맞춰 본다)
SELECT 'CNT',
       (SELECT count(*) FROM "order" o WHERE (o.session_kind = 'LIVE' AND o.session_id = ANY(:'live_ids'::bigint[]))
                                          OR (o.session_kind = 'DYNAMIC' AND o.session_id = ANY(:'dyn_ids'::bigint[]))),
       (SELECT count(*) FROM position p WHERE (p.session_kind = 'LIVE' AND p.session_id = ANY(:'live_ids'::bigint[]))
                                         OR (p.session_kind = 'DYNAMIC' AND p.session_id = ANY(:'dyn_ids'::bigint[])));
\else
SELECT 'GATE', 'STOP';
\endif
SELECT 'END', clock_timestamp();
ROLLBACK;
