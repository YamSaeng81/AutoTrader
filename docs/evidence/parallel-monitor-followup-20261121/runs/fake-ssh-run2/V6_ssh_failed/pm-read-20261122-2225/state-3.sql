-- 병행 감시 종류+ID 상태 읽기 초안 pm-state-3 (총괄 v1.216 §3-1 G1 최소 수정 사본). **운영 실행 승인 아님.**
-- 원본 pm-state-2(parallel-monitor-check-20261121/sql/state-2.sql 4c04efff)·pm-state-1(3c0b656f)은 보존한다. 바뀐 점은 모의 제외 조건 한 가지다:
--   pm-state-2 는 session_kind IN ('DYNAMIC','DYN_PAPER') 이면서 현재 세션 모드가 PAPER 인 행을 모두 모의로 빼서, 저장된 DYNAMIC(실자금 종류)
--   주문·포지션이 세션 모드 변경만으로 목록·U·CNT 에서 사라졌다(pg-run2 B3, 총괄 v1.216 G1).
--   pm-state-3 은 저장된 종류를 실자금 증거로 유지한다:
--     · 모의 제외는 session_kind='DYN_PAPER' 이고 세션 trading_mode='PAPER' 로 **일치하는 경우만**(owner DYN_PAPER, 개수만 CNT 에 남김).
--     · session_kind='DYNAMIC' 인데 세션 모드가 REAL 이 아님(PAPER·NULL·그 밖) → 행을 남기고 owner KIND_MODE_MISMATCH(수집 불통과).
--     · session_kind='DYN_PAPER' 인데 세션 모드가 PAPER 가 아님 → 행을 남기고 KIND_MODE_MISMATCH(pm-state-2 와 같음).
--     · U·CNT 도 같은 제외 조건(DYN_PAPER 종류 + PAPER 모드 + 세션 행 있음)만 쓴다. 시간 필터·과거 종결 면제 없음(그대로).
--   그 밖의 열·출력 형식·트랜잭션은 pm-state-2 와 같다. META 판 표지만 pm-state-3.
-- 실행(실제 읽기 1회)·감시 설치는 각각 별도 승인 항목이다. 이 파일은 설치본 c669ccdc·read.sh·RUNBOOK 을 대체하지 않는다.
-- 대상 스키마: d81c3f2f (Git 원문으로 확인한 열)
--   live_trading_session: id, session_type(V46, REAL/PAPER, 기본 REAL), status, strategy_type, timeframe, coin_pair, created_at
--   dynamic_session:      id, trading_mode(REAL/PAPER), status, strategy_type, timeframe, created_at (코인은 세션 열 아님 → NULL)
--   "order":  id, session_kind, session_id, side, coin_pair, state, fill_accounting_pending
--   position: id, session_kind, session_id, coin_pair, status
--   order_settlement_discrepancy: order_id, resolved_at
-- 읽지 않는 것: 금액·수량·가격·잔액·대금·수수료·성과 열, strategy_params·invest_ratio 등 전략 파라미터, 인증/키,
--   paper_trading 스키마(522~587 모의 구역 포함). 66 개수/상태는 N03 원천(pm-aux-2)으로만 연결한다.
-- 시각은 db 축 텍스트(UTC)로 내며, 계좌·거래소 시계와의 관계를 선언하지 않는다.
-- 호출 예(승인 후에만): PGOPTIONS='-c statement_timeout=15s -c lock_timeout=2s' psql -X -q -v ON_ERROR_STOP=1 -f - < state-3.sql > state.tsv 2> state.stderr
-- 출력 행(탭 구분, NULL = \N):
--   META  pm-state-3  db_now  db_clock  flyway_max  isolation
--   S     kind  id  mode  status  strategy_type  timeframe  coin_pair  created_at
--   O     order_id  session_kind  session_id  side  coin_pair  state  owner
--   P     position_id  session_kind  session_id  coin_pair  status  owner
--   U     fill_accounting_pending_count  open_discrepancy_count      (DYN_PAPER 종류 + PAPER 모드 일치분만 제외)
--   CNT   sessions  orders  positions  dyn_paper_orders  dyn_paper_positions
--   END   db_clock
-- owner: OK · NO_SESSION_ID · UNKNOWN_KIND · NO_SESSION_ROW · KIND_MODE_MISMATCH (OK 가 아니면 변환기가 수집 불통과로 남긴다)
\set ON_ERROR_STOP 1
\pset format unaligned
\pset tuples_only on
\pset fieldsep '\t'
\pset null '\\N'

BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'META', 'pm-state-3',
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       (SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'),
       current_setting('transaction_isolation');

-- 세션: 두 표의 모든 행(상태·모드 필터 없음)
SELECT 'S', 'LIVE', id, session_type, status, strategy_type, timeframe, coin_pair,
       to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"')
  FROM live_trading_session
UNION ALL
SELECT 'S', 'DYNAMIC', id, trading_mode, status, strategy_type, timeframe, NULL,
       to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"')
  FROM dynamic_session
ORDER BY 2, 3;

-- 주문: 소유 분류를 CASE 로 먼저 확정(NULL 이 비교식에 남지 않음). DYN_PAPER 종류 + PAPER 모드 일치분만 행에서 빼고 개수로 남긴다.
WITH c AS (
  SELECT o.id, o.session_kind, o.session_id, o.side, o.coin_pair, o.state,
         CASE WHEN o.session_id IS NULL THEN 'NO_SESSION_ID'
              WHEN o.session_kind IS NULL OR o.session_kind NOT IN ('LIVE','DYNAMIC','DYN_PAPER') THEN 'UNKNOWN_KIND'
              WHEN o.session_kind = 'LIVE' AND l.id IS NULL THEN 'NO_SESSION_ROW'
              WHEN o.session_kind IN ('DYNAMIC','DYN_PAPER') AND d.id IS NULL THEN 'NO_SESSION_ROW'
              WHEN o.session_kind = 'DYN_PAPER' AND d.trading_mode = 'PAPER' THEN 'DYN_PAPER'
              WHEN o.session_kind = 'DYN_PAPER' THEN 'KIND_MODE_MISMATCH'
              WHEN o.session_kind = 'DYNAMIC' AND d.trading_mode IS DISTINCT FROM 'REAL' THEN 'KIND_MODE_MISMATCH'
              ELSE 'OK' END AS owner
    FROM "order" o
    LEFT JOIN live_trading_session l ON o.session_kind = 'LIVE' AND l.id = o.session_id
    LEFT JOIN dynamic_session d ON o.session_kind IN ('DYNAMIC','DYN_PAPER') AND d.id = o.session_id)
SELECT 'O', id, session_kind, session_id, side, coin_pair, state, owner FROM c WHERE owner <> 'DYN_PAPER' ORDER BY id;

-- 포지션: 같은 분류
WITH c AS (
  SELECT p.id, p.session_kind, p.session_id, p.coin_pair, p.status,
         CASE WHEN p.session_id IS NULL THEN 'NO_SESSION_ID'
              WHEN p.session_kind IS NULL OR p.session_kind NOT IN ('LIVE','DYNAMIC','DYN_PAPER') THEN 'UNKNOWN_KIND'
              WHEN p.session_kind = 'LIVE' AND l.id IS NULL THEN 'NO_SESSION_ROW'
              WHEN p.session_kind IN ('DYNAMIC','DYN_PAPER') AND d.id IS NULL THEN 'NO_SESSION_ROW'
              WHEN p.session_kind = 'DYN_PAPER' AND d.trading_mode = 'PAPER' THEN 'DYN_PAPER'
              WHEN p.session_kind = 'DYN_PAPER' THEN 'KIND_MODE_MISMATCH'
              WHEN p.session_kind = 'DYNAMIC' AND d.trading_mode IS DISTINCT FROM 'REAL' THEN 'KIND_MODE_MISMATCH'
              ELSE 'OK' END AS owner
    FROM position p
    LEFT JOIN live_trading_session l ON p.session_kind = 'LIVE' AND l.id = p.session_id
    LEFT JOIN dynamic_session d ON p.session_kind IN ('DYNAMIC','DYN_PAPER') AND d.id = p.session_id)
SELECT 'P', id, session_kind, session_id, coin_pair, status, owner FROM c WHERE owner <> 'DYN_PAPER' ORDER BY id;

-- 정산 미확정(표지만): 반영 대기 주문 수, 미해결 정산 불일치가 있는 주문 수. DYN_PAPER 종류 + PAPER 모드 일치분만 제외.
WITH o AS (
  SELECT o.id, o.fill_accounting_pending
    FROM "order" o LEFT JOIN dynamic_session d ON o.session_kind IN ('DYNAMIC','DYN_PAPER') AND d.id = o.session_id
   WHERE NOT (o.session_kind = 'DYN_PAPER' AND d.id IS NOT NULL AND d.trading_mode IS NOT DISTINCT FROM 'PAPER'))
SELECT 'U',
       (SELECT count(*) FROM o WHERE o.fill_accounting_pending IS NOT FALSE),
       (SELECT count(*) FROM o WHERE EXISTS (SELECT 1 FROM order_settlement_discrepancy x WHERE x.order_id = o.id AND x.resolved_at IS NULL));

-- 행 수 대조(같은 스냅숏). 변환기가 실제 행 수와 맞춰 본다.
SELECT 'CNT',
       (SELECT count(*) FROM live_trading_session) + (SELECT count(*) FROM dynamic_session),
       (SELECT count(*) FROM "order" o LEFT JOIN dynamic_session d ON o.session_kind IN ('DYNAMIC','DYN_PAPER') AND d.id = o.session_id
         WHERE NOT (o.session_kind = 'DYN_PAPER' AND d.id IS NOT NULL AND d.trading_mode IS NOT DISTINCT FROM 'PAPER')),
       (SELECT count(*) FROM position p LEFT JOIN dynamic_session d ON p.session_kind IN ('DYNAMIC','DYN_PAPER') AND d.id = p.session_id
         WHERE NOT (p.session_kind = 'DYN_PAPER' AND d.id IS NOT NULL AND d.trading_mode IS NOT DISTINCT FROM 'PAPER')),
       (SELECT count(*) FROM "order" o JOIN dynamic_session d ON o.session_kind = 'DYN_PAPER' AND d.id = o.session_id
         WHERE d.trading_mode = 'PAPER'),
       (SELECT count(*) FROM position p JOIN dynamic_session d ON p.session_kind = 'DYN_PAPER' AND d.id = p.session_id
         WHERE d.trading_mode = 'PAPER');
SELECT 'END', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"');
ROLLBACK;
