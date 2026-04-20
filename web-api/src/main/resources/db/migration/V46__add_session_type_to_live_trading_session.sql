-- V46: live_trading_session 에 session_type 컬럼 추가 (REAL / PAPER 구분)
ALTER TABLE live_trading_session
    ADD COLUMN IF NOT EXISTS session_type VARCHAR(10) NOT NULL DEFAULT 'REAL';

CREATE INDEX IF NOT EXISTS idx_live_session_session_type ON live_trading_session (session_type);
