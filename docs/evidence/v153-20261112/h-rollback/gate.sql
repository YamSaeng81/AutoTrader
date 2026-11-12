-- 총괄 v1.153 H — 선택 모드(LIVE_ALIGNED) 세션 생성 전/후 구판 복구 관문: 읽기 전용 집계 한 줄 (2026-11-12)
-- 쓰기 없음: 읽기 전용 트랜잭션 안에서 SELECT 만 하고 ROLLBACK 한다. 운영 실행은 승인 뒤에만(이 파일은 초안).
-- 새 모드 세션 = paper_trading.virtual_balance.strategy_params 의 paperExecutionMode = 'LIVE_ALIGNED'. 키 없는 세션(사전 등록 66·종전 PAPER)은 세지 않는다.
-- V96 열(position.exit_decided_at)이 없으면 그 열을 읽지 않고 v96=0 으로 알린다(판정은 rollback_gate.sh).
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
