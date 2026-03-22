-- CLOSING 포지션 타임아웃 롤백 지원 — closing_at 기록 후 5분 초과 시 OPEN 롤백
-- 관련 로직: LiveTradingService.reconcileClosingPositions()
ALTER TABLE position
    ADD COLUMN IF NOT EXISTS closing_at TIMESTAMPTZ;
