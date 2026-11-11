-- 청산 A/B 집계 입력 positions.csv 추출 — 초안, 실행 금지(42일 구간 끝 T1 뒤, 읽기 전용). (총괄 v1.149 §4-2.4)
--
-- psql -X -q --csv -v ON_ERROR_STOP=1 -v ids='<완성 기록부의 8개 id>' -f extract_positions.sql > positions.csv
-- 🔴 상태를 거르지 않는다: OPEN·CLOSING·CLOSED 를 모두 뽑는다. CLOSING(청산 대기)은 아직 소유한 미정산 보유다 — closed_at·sell_cash_in 을
--    비워 두고(집계기가 열린 보유로 평가), CLOSED 현금흐름으로 만들지 않는다. T1 에 유효 평가가가 없으면 집계기가 판정 불가로 둔다.
-- 현금흐름(선택 모드 LIVE_ALIGNED, 후보 코드 기준):
--   buy_cash_out = invested_krw          (매수 체결 금액 + 매수 수수료. 선택 모드에서 avg_price 는 체결가, 원가는 invested_krw)
--   sell_cash_in = invested_krw + realized_pnl   (CLOSED 만. closePosition 의 realizedPnl = 매도 순수익 − invested_krw 이므로 매도 순수익과 같다)
-- 522~587 은 넣지 않는다(성과 비열람 계약). :ids 는 완성 기록부의 8개만.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT p.session_id,
       p.id                                   AS position_id,
       to_char(p.opened_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"') AS opened_at,
       CASE WHEN p.status = 'CLOSED'
            THEN to_char(p.closed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"') ELSE '' END AS closed_at,
       p.size,
       p.invested_krw                         AS buy_cash_out,
       CASE WHEN p.status = 'CLOSED' THEN (p.invested_krw + p.realized_pnl)::text ELSE '' END AS sell_cash_in,
       p.status
FROM paper_trading.position p
WHERE p.session_id IN (:ids)
ORDER BY p.session_id, p.opened_at, p.id;
ROLLBACK;
