-- V24: 서킷 브레이커 (Auto Kill-Switch) 지원
-- risk_config: MDD 임계값·연속 손실 한도·활성화 여부 추가
ALTER TABLE risk_config
    ADD COLUMN IF NOT EXISTS mdd_threshold_pct      NUMERIC(5,2)  DEFAULT 20.0,
    ADD COLUMN IF NOT EXISTS consecutive_loss_limit  INTEGER       DEFAULT 5,
    ADD COLUMN IF NOT EXISTS circuit_breaker_enabled BOOLEAN       DEFAULT TRUE;

-- live_trading_session: MDD 피크 자본·트리거 시각·트리거 사유 추가
ALTER TABLE live_trading_session
    ADD COLUMN IF NOT EXISTS mdd_peak_capital               NUMERIC(20,2),
    ADD COLUMN IF NOT EXISTS circuit_breaker_triggered_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS circuit_breaker_reason         VARCHAR(255);
