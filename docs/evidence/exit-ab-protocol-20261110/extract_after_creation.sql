-- 청산 A/B 집계 입력 추출 — 초안, 실행 금지(세션이 아직 없다). 생성 뒤 읽기 전용으로만 쓴다.
-- :ids 는 기록부의 8개 세션 ID. 522~587 은 넣지 않는다(성과 비열람 계약).
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
\echo @@arms
COPY (SELECT id, strategy_name, coin_pair, timeframe, initial_capital, invest_ratio, stop_loss_pct, max_hold_hours,
             strategy_params::text AS params_json, started_at, status
      FROM paper_trading.virtual_balance WHERE id IN (:ids) ORDER BY id) TO STDOUT WITH CSV HEADER;
\echo @@positions
-- buy_cash_out = invested_krw(수수료 포함 차감액), sell_cash_in = 매도 순수입(체결가 × 수량 − 수수료)
COPY (SELECT p.session_id, p.id AS position_id, p.opened_at, p.closed_at, p.size, p.invested_krw AS buy_cash_out,
             CASE WHEN p.status = 'CLOSED' THEN o.price * o.quantity * (1 - 0.0005) END AS sell_cash_in, p.status
      FROM paper_trading.position p
      LEFT JOIN paper_trading."order" o ON o.position_id = p.id AND o.side = 'SELL' AND o.state = 'FILLED'
      WHERE p.session_id IN (:ids) ORDER BY p.session_id, p.id) TO STDOUT WITH CSV HEADER;
\echo @@marks
-- 일별 평가 시각(시작 시각 + n일)의 닫힌 M15 봉 종가. 그 시각에 끝난 봉이 없으면 행이 없다(평가 불가로 남김).
COPY (SELECT coin_pair AS coin, time + interval '15 minutes' AS ts, close AS price
      FROM market_data_cache WHERE timeframe = 'M15' AND coin_pair IN ('KRW-BTC','KRW-ETH')
        AND time + interval '15 minutes' IN (SELECT :start + n * interval '1 day' FROM generate_series(0, 42) n)
      ORDER BY 1, 2) TO STDOUT WITH CSV HEADER;
ROLLBACK;
