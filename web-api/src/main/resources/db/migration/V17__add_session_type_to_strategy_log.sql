-- 전략 로그에 모의/실전 구분 및 세션 ID 추가
ALTER TABLE strategy_log
    ADD COLUMN IF NOT EXISTS session_type VARCHAR(10) DEFAULT 'PAPER',
    ADD COLUMN IF NOT EXISTS session_id   BIGINT;

CREATE INDEX IF NOT EXISTS idx_strategy_log_session_type ON strategy_log (session_type);
CREATE INDEX IF NOT EXISTS idx_strategy_log_session_id   ON strategy_log (session_id);
