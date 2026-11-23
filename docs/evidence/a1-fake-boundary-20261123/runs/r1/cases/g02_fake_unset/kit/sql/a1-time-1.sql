-- A1 소유 세션 없는 행 시각 집계 a1-time-1 (총괄 v1.224 §3-2). **운영 실행 승인 아님 — 별도 승인 전 실행 금지.**
-- 목적: 10:36 pm-state-3 에서 수집 불통과로 남은 session_id IS NULL 행(주문 7,026·포지션 199, 전부 session_kind LIVE)의
--   상태별 건수·ID 범위·생성/개시 시각 min/max·NULL 시각 수·기준 시각(V12 설치) 전 건수·최근 30일 건수를 금액 없이 센다.
--   결과는 개수와 시각뿐이다. 정산 확정·소유 예외·과거 종결 면제의 근거로 자동 사용하지 않는다(v1.224 §3-2).
-- 대상 스키마: 운영 0e1d2a02 의 Git 마이그레이션 원문으로 확정한 열(d81c3f2f 와 이 열들은 같다)
--   "order"  : id, state, created_at(V4, TIMESTAMPTZ DEFAULT NOW()), session_id(V12, nullable), session_kind(V52, NOT NULL DEFAULT 'LIVE')
--              ※ "order" 에는 updated_at 열이 없다. A1 초안의 "created_at(또는 updated_at)" 은 created_at 으로 확정한다.
--   position : id, status, opened_at(V4, TIMESTAMPTZ DEFAULT NOW()), closed_at(V4), session_id(V12), session_kind(V51, NOT NULL DEFAULT 'LIVE')
--              ※ position 에는 created_at 이 없다. A1 초안의 "position created_at" 은 opened_at 으로 정정한다.
--   flyway_schema_history : version, installed_on(timestamp without time zone), success
--   live_trading_session / dynamic_session : created_at (세션 체계 최초 행 시각 비교용, 개수/시각만)
-- 읽지 않는 것: 금액·수량·가격·잔액·수수료·손익 열, signal_reason·failed_reason 등 문자열 원문, 전략 파라미터, 인증/키,
--   paper_trading 스키마 전부(522~587 포함). 행 단위 출력 없음(전부 집계).
-- 시간대: timestamptz 는 전부 UTC 텍스트로 낸다. flyway installed_on 은 시간대 없는 값이라 **원값 그대로**(raw) 내고,
--   비교에는 현재 세션 TimeZone 으로 해석한 값(ref_tz)을 쓴다. 설치 당시 세션 시간대가 달랐을 수 있으므로 before_v12 는
--   보조 지표이며 정확한 경계로 선언하지 않는다. 최근 30일은 now()(트랜잭션 시각) 기준이다.
-- 보호값: PGOPTIONS 로 statement_timeout=15s·lock_timeout=2s 를 받는다. 첫 DO 블록이 값을 확인하고 다르면 예외(rc 3)로 멈춘다.
-- 호출(승인 후 실행기 a1-read.sh 로만): ... exec -T -e PGOPTIONS='-c statement_timeout=15s -c lock_timeout=2s' db psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f -
-- 출력 행(탭 구분, NULL = \N):
--   META  a1-time-1  db_now  db_clock  flyway_max  isolation  timezone  statement_timeout  lock_timeout
--   REF   version  installed_on_raw  ref_tz_utc  success           (version 12·51·52, 없으면 해당 줄 없음)
--   SESS  LIVE|DYNAMIC  count  min_created_at  max_created_at
--   OT    session_kind  state  count  min_id  max_id  min_created_at  max_created_at  null_created  before_v12  recent30
--   PT    session_kind  status  count  min_id  max_id  min_opened_at  max_opened_at  null_opened  min_closed_at  max_closed_at  null_closed  before_v12  recent30
--   OWN   order|position  LIVE  count  min_id  min_time                (session_id IS NOT NULL 인 LIVE 행: 비교 기준)
--   CNT   ownerless_orders  ownerless_positions  ownerless_live_orders  ownerless_live_positions
--   END   db_clock
-- before_v12 = 시각 < ref_tz(V12), V12 행이 없으면 \N. recent30 = 시각 >= now() - 30일 (주문 created_at, 포지션 opened_at 또는 closed_at 중 하나).
\set ON_ERROR_STOP 1
\pset format unaligned
\pset tuples_only on
\pset fieldsep '\t'
\pset null '\\N'

DO $$
BEGIN
  IF current_setting('statement_timeout') <> '15s' OR current_setting('lock_timeout') <> '2s' THEN
    RAISE EXCEPTION 'A1_GUARD 보호값 불일치 statement_timeout=% lock_timeout=%',
      current_setting('statement_timeout'), current_setting('lock_timeout');
  END IF;
END $$;

BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT 'META', 'a1-time-1',
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       (SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'),
       current_setting('transaction_isolation'),
       current_setting('TimeZone'),
       current_setting('statement_timeout'),
       current_setting('lock_timeout');

-- 기준 시각: 세션 열(V12)·종류 열(V51/V52) 마이그레이션 설치 기록
SELECT 'REF', version,
       to_char(installed_on, 'YYYY-MM-DD"T"HH24:MI:SS.US'),
       to_char((installed_on AT TIME ZONE current_setting('TimeZone')) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       success
  FROM flyway_schema_history
 WHERE version IN ('12', '51', '52')
 ORDER BY version::int, installed_rank;

-- 세션 표 최초/최근 생성 시각(개수·시각만)
SELECT 'SESS', 'LIVE', count(*),
       to_char(min(created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       to_char(max(created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"')
  FROM live_trading_session
UNION ALL
SELECT 'SESS', 'DYNAMIC', count(*),
       to_char(min(created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       to_char(max(created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"')
  FROM dynamic_session;

-- 소유 세션 없는 주문: 종류·상태별
WITH r AS (
  SELECT (SELECT installed_on AT TIME ZONE current_setting('TimeZone') FROM flyway_schema_history
           WHERE version = '12' AND success ORDER BY installed_rank LIMIT 1) AS v12)
SELECT 'OT', o.session_kind, o.state, count(*), min(o.id), max(o.id),
       to_char(min(o.created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       to_char(max(o.created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       count(*) FILTER (WHERE o.created_at IS NULL),
       CASE WHEN (SELECT v12 FROM r) IS NULL THEN NULL
            ELSE count(*) FILTER (WHERE o.created_at < (SELECT v12 FROM r)) END,
       count(*) FILTER (WHERE o.created_at >= now() - interval '30 days')
  FROM "order" o
 WHERE o.session_id IS NULL
 GROUP BY o.session_kind, o.state
 ORDER BY o.session_kind, o.state;

-- 소유 세션 없는 포지션: 종류·상태별
WITH r AS (
  SELECT (SELECT installed_on AT TIME ZONE current_setting('TimeZone') FROM flyway_schema_history
           WHERE version = '12' AND success ORDER BY installed_rank LIMIT 1) AS v12)
SELECT 'PT', p.session_kind, p.status, count(*), min(p.id), max(p.id),
       to_char(min(p.opened_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       to_char(max(p.opened_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       count(*) FILTER (WHERE p.opened_at IS NULL),
       to_char(min(p.closed_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       to_char(max(p.closed_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
       count(*) FILTER (WHERE p.closed_at IS NULL),
       CASE WHEN (SELECT v12 FROM r) IS NULL THEN NULL
            ELSE count(*) FILTER (WHERE p.opened_at < (SELECT v12 FROM r)) END,
       count(*) FILTER (WHERE p.opened_at >= now() - interval '30 days' OR p.closed_at >= now() - interval '30 days')
  FROM position p
 WHERE p.session_id IS NULL
 GROUP BY p.session_kind, p.status
 ORDER BY p.session_kind, p.status;

-- 비교 기준: 세션이 연결된 LIVE 행의 최소 ID·최초 시각
SELECT 'OWN', 'order', 'LIVE', count(*), min(id),
       to_char(min(created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"')
  FROM "order" WHERE session_id IS NOT NULL AND session_kind = 'LIVE'
UNION ALL
SELECT 'OWN', 'position', 'LIVE', count(*), min(id),
       to_char(min(opened_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"')
  FROM position WHERE session_id IS NOT NULL AND session_kind = 'LIVE';

-- 같은 스냅숏 행 수(변환 시 OT/PT 합과 대조)
SELECT 'CNT',
       (SELECT count(*) FROM "order" WHERE session_id IS NULL),
       (SELECT count(*) FROM position WHERE session_id IS NULL),
       (SELECT count(*) FROM "order" WHERE session_id IS NULL AND session_kind = 'LIVE'),
       (SELECT count(*) FROM position WHERE session_id IS NULL AND session_kind = 'LIVE');
SELECT 'END', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"');
ROLLBACK;
