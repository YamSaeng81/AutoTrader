-- 1단계 체결 비용 집계의 입력 추출 (읽기 전용) — aggregate.py 가 읽는 CSV (2026-10-19, 선행 조건 2)
--
-- 운영자가 운영 DB 에서 실행한다(쓰기 없음). 🔴 **대상 포지션을 먼저 보존**한다(리뷰 2026-10-19): 포지션 기준 LEFT JOIN 이고 주문 쪽 조건을 두지 않는다 —
-- 주문이 없는 포지션도 빈 주문 열의 한 행으로 나오고, 체결량이 비었거나 미결 상태인 주문도 나온다. 측정 불가 판정은 aggregate.py 가 한다
-- (주문 없음·한쪽 체결 없음·체결량·신호가·대금 누락·미결 상태의 체결·매수/매도 수량 불일치 → 측정 불가로 따로 센다).
--
-- 대상: 실자금 세션(LIVE session_type = 'REAL', DYNAMIC trading_mode = 'REAL')의 닫힌 포지션. 기간 판정(시작 이후 진입·기간 끝 이전 청산)은
-- aggregate.py 가 --start·--end·--as-of 로 한다(여기서는 넉넉히 거른다).
--
-- 실행 예(psql):
--   \copy (<아래 SELECT 를 그대로>) TO 'stage1_orders.csv' WITH CSV HEADER
--   python scripts/stage1_cost/aggregate.py stage1_orders.csv --start 2026-11-01T00:00:00+09:00 --size 20000
--
-- funds: 반영값(applied) → 거래소 보고값(reported) → 주문 행 값 순. fill_price_source 가 있으면 페이퍼 가정 체결이다(집계가 제외).
SELECT p.id                     AS position_id,
       p.session_kind,
       p.session_id,
       p.coin_pair,
       p.entry_allocation_krw,
       p.opened_at,
       p.closed_at,
       o.id                     AS order_id,
       o.side,
       o.state,
       o.signal_price,
       o.filled_quantity,
       COALESCE(o.applied_executed_funds, o.reported_executed_funds, o.executed_funds) AS funds,
       o.fill_price_source,
       o.filled_at
  FROM position p
  LEFT JOIN "order" o ON o.position_id = p.id
 WHERE p.session_kind IN ('LIVE', 'DYNAMIC')
   AND p.status = 'CLOSED'
   AND p.closed_at >= TIMESTAMPTZ '2026-10-01 00:00:00+09'      -- 1단계 시작 전으로 넉넉히(집계가 다시 거른다)
   AND (   (p.session_kind = 'LIVE'    AND EXISTS (SELECT 1 FROM live_trading_session s WHERE s.id = p.session_id AND s.session_type = 'REAL'))
        OR (p.session_kind = 'DYNAMIC' AND EXISTS (SELECT 1 FROM dynamic_session s      WHERE s.id = p.session_id AND s.trading_mode = 'REAL')))
 ORDER BY p.id, o.id
