-- 모의투자 잔고 낙관적 락 — 동시 업데이트 시 덮어쓰기 방지
ALTER TABLE paper_trading.virtual_balance
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
