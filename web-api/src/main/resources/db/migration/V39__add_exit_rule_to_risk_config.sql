-- V39: risk_config에 포지션 수준 리스크 규칙 컬럼 추가
-- ExitRuleConfig(SL%, TP배수, 트레일링, 투자비율)를 DB에서 관리

ALTER TABLE risk_config
    ADD COLUMN IF NOT EXISTS stop_loss_pct          DECIMAL(5,2)  DEFAULT 5.0,
    ADD COLUMN IF NOT EXISTS take_profit_multiplier DECIMAL(5,2)  DEFAULT 2.0,
    ADD COLUMN IF NOT EXISTS trailing_enabled       BOOLEAN       DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS trailing_tp_margin_pct DECIMAL(5,3)  DEFAULT 0.5,
    ADD COLUMN IF NOT EXISTS trailing_sl_margin_pct DECIMAL(5,3)  DEFAULT 0.3,
    ADD COLUMN IF NOT EXISTS invest_ratio_pct       DECIMAL(5,2)  DEFAULT 80.0;

COMMENT ON COLUMN risk_config.stop_loss_pct          IS '기본 손절 비율 (%)';
COMMENT ON COLUMN risk_config.take_profit_multiplier IS '익절 배수 (TP = SL × 배수)';
COMMENT ON COLUMN risk_config.trailing_enabled       IS '트레일링 스탑 활성화';
COMMENT ON COLUMN risk_config.trailing_tp_margin_pct IS '트레일링 TP 마진 (%), 고점 대비';
COMMENT ON COLUMN risk_config.trailing_sl_margin_pct IS '트레일링 SL 조임 마진 (%), 저점 대비';
COMMENT ON COLUMN risk_config.invest_ratio_pct       IS '가용자금 대비 투자 비율 (%)';
