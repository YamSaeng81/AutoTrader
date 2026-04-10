-- 신호 품질 추적 컬럼 추가 (V31)
-- 신호 발생 시점 가격, 실행 여부, 차단 사유, 사후 가격 평가
ALTER TABLE strategy_log
    ADD COLUMN IF NOT EXISTS signal_price    NUMERIC(20,8),
    ADD COLUMN IF NOT EXISTS was_executed    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS blocked_reason  TEXT,
    ADD COLUMN IF NOT EXISTS price_after_4h  NUMERIC(20,8),
    ADD COLUMN IF NOT EXISTS price_after_24h NUMERIC(20,8),
    ADD COLUMN IF NOT EXISTS return_4h_pct   NUMERIC(8,4),
    ADD COLUMN IF NOT EXISTS return_24h_pct  NUMERIC(8,4);

-- BUY/SELL 신호 중 사후 평가가 필요한 것을 빠르게 조회하기 위한 인덱스
CREATE INDEX IF NOT EXISTS idx_signal_quality_eval
    ON strategy_log(signal, created_at)
    WHERE signal IN ('BUY', 'SELL');
