-- 모의투자 포지션별 누적 수수료 (매수 수수료 + 매도 수수료 합산)
ALTER TABLE paper_trading.position
    ADD COLUMN IF NOT EXISTS position_fee NUMERIC(20, 2) NOT NULL DEFAULT 0;
