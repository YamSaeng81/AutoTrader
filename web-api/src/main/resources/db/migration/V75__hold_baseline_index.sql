-- HOLD 사후수익 백필용 인덱스 (2026-09-07)
--
-- 왜 필요한가: strategy_log 의 사후수익 백필이 BUY/SELL 에만 돌아서 HOLD 74,462건(DYN_PAPER)
-- 전량이 price_after_* NULL 이었다. 대조군이 없으니 "BUY 신호 사후 -1.25%" 같은 수치를
-- 시장 하락과 구분할 수 없었다 — 실제로 코인·시각을 통제하니 -1.25% 가 -0.16% 로 바뀌었다.
--
-- 백필은 (코인, 시각) 단위 1건만 평가한다. 같은 코인·같은 1시간의 HOLD 는 signal_price 가
-- 사실상 같아 사후수익도 동일하므로, 74,462건을 다 부르면 Upbit 호출만 10배 낭비된다
-- (distinct coin-hour = 7,658건).
CREATE INDEX IF NOT EXISTS idx_hold_baseline_eval
    ON strategy_log (coin_pair, created_at)
    WHERE signal = 'HOLD' AND signal_price IS NOT NULL;
