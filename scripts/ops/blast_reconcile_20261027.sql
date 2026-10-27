-- KRW-BLAST 거래지원 종료 — 검증 세션 576·577·578 정지 전후 대조 (2026-10-27, 읽기 전용). SUPERTREND_VALIDATION_PREREG 📌 KRW-BLAST
-- 리뷰 2026-10-27: 지금 무보유라는 것만으로 종료(10-26 03:00Z = 12:00 KST) 당시에도 현금이었다고 확정하지 않는다 — 주문·포지션·잔고를 대조한다.
-- 정지 전에 한 번, 정지 뒤에 한 번 실행해 결과를 나란히 둔다(정지 뒤에는 status·stopped_at 만 바뀌어야 한다).
\set ON_ERROR_STOP on
BEGIN TRANSACTION READ ONLY;
\set ended '2026-10-26 03:00:00+00'
\echo '== ① 세 세션 — 검증 범위(522~587)·코인·상태·잔고 (cash_only = 가용 = 총액)'
SELECT id, status, strategy_name, coin_pair, timeframe, started_at, stopped_at, initial_capital, total_krw, available_krw,
       (total_krw = available_krw) AS cash_only, realized_pnl, total_fee, updated_at, (id BETWEEN 522 AND 587) AS in_66
  FROM paper_trading.virtual_balance WHERE id IN (576,577,578) ORDER BY id;
\echo '== ② 주문 상태별 건수 — 끝나지 않은 주문(FILLED·CANCELLED·FAILED 밖)이 있는가'
SELECT session_id, state, count(*) FROM paper_trading."order" WHERE session_id IN (576,577,578) GROUP BY 1, 2 ORDER BY 1, 2;
\echo '== ③ 세션별 마지막 주문 3건 — 마지막 거래가 거래지원 종료 전인가'
SELECT * FROM (
  SELECT session_id, id, side, state, price, quantity, filled_quantity, created_at, filled_at, cancelled_at, failed_reason,
         row_number() OVER (PARTITION BY session_id ORDER BY created_at DESC, id DESC) AS rn
    FROM paper_trading."order" WHERE session_id IN (576,577,578)) t
 WHERE rn <= 3 ORDER BY session_id, rn;
\echo '== ④ 거래지원 종료 전후(10-25 00:00Z 이후) 주문 — 없어야 기대'
SELECT session_id, id, side, state, price, quantity, created_at, failed_reason
  FROM paper_trading."order" WHERE session_id IN (576,577,578) AND created_at >= '2026-10-25 00:00:00+00' ORDER BY created_at;
\echo '== ⑤ 포지션 상태별 건수 · 마지막 청산 시각'
SELECT session_id, status, count(*), max(opened_at) AS last_opened, max(closed_at) AS last_closed
  FROM paper_trading.position WHERE session_id IN (576,577,578) GROUP BY 1, 2 ORDER BY 1, 2;
\echo '== ⑥ 거래지원 종료 시각에 열려 있던 포지션 — 없어야 기대(있으면 종료 당시 보유)'
SELECT session_id, id, status, size, avg_price, opened_at, closed_at, exit_reason, realized_pnl
  FROM paper_trading.position
 WHERE session_id IN (576,577,578) AND opened_at < :'ended'::timestamptz AND (closed_at IS NULL OR closed_at > :'ended'::timestamptz)
 ORDER BY session_id, id;
\echo '== ⑦ 잔고 대조(참고) — 포지션 실현 손익 합 · 세션 기록 실현 손익 · 총액-초기자본'
SELECT v.id, v.initial_capital, v.total_krw, v.total_krw - v.initial_capital AS total_minus_initial, v.realized_pnl AS session_realized,
       (SELECT coalesce(sum(p.realized_pnl), 0) FROM paper_trading.position p WHERE p.session_id = v.id) AS position_realized_sum,
       (SELECT count(*) FROM paper_trading."order" o WHERE o.session_id = v.id) AS orders
  FROM paper_trading.virtual_balance v WHERE v.id IN (576,577,578) ORDER BY v.id;
\echo '== ⑧ 검증 세션 — RUNNING 아닌 세션(정지 전 기대: 없음 · 정지 뒤 기대: 576·577·578 STOPPED 만)'
SELECT id, status, coin_pair, stopped_at FROM paper_trading.virtual_balance
 WHERE id BETWEEN 522 AND 587 AND status IS DISTINCT FROM 'RUNNING' ORDER BY id;
SELECT count(*) FILTER (WHERE status = 'RUNNING') AS s66_running, count(*) AS s66_total
  FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587;
ROLLBACK;
