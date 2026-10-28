-- 전향 검증 건수 전용 추출 (2026-10-28, docs/LIVE_READINESS.md §4) — aggregate.py count 가 읽는 열만 내보낸다.
-- 🔴 운영 실행은 아직 승인되지 않았다. 지금은 준비안이다(리뷰 2026-10-28: 운영 조회·설치 하지 않음).
-- 금액·가격·수량·손익·잔액 열은 SELECT 하지 않는다 — extract.sql 과 달리 성과를 계산할 재료가 CSV 에 없다.
-- 하나의 REPEATABLE READ READ ONLY 스냅샷 안에서 CSV 4개(meta·sessions·positions·orders)를 현재 폴더에 쓰고 ROLLBACK 으로 끝낸다. 쓰기 문장 없음.
-- 실행(빈 폴더에서): psql -X -v ON_ERROR_STOP=1 -U <user> -d <db> -f count_extract.sql
-- 이어서: python aggregate.py count <그 폴더>   (팔별 청산 왕복 수·100/200 도달 여부·스냅샷 시각만 출력)
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '120s';

\copy (SELECT to_char(transaction_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS snapshot_ts) TO 'meta.csv' WITH (FORMAT csv, HEADER)

\copy (SELECT id, coin_pair, strategy_name, timeframe FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587 ORDER BY id) TO 'sessions.csv' WITH (FORMAT csv, HEADER)

\copy (SELECT id, session_id FROM paper_trading.position WHERE session_id BETWEEN 522 AND 587 ORDER BY id) TO 'positions.csv' WITH (FORMAT csv, HEADER)

\copy (SELECT id, session_id, position_id, side, state, to_char(filled_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS filled_at FROM paper_trading."order" WHERE session_id BETWEEN 522 AND 587 ORDER BY id) TO 'orders.csv' WITH (FORMAT csv, HEADER)

ROLLBACK;
