-- V12: 실전매매 다중 세션 테이블
CREATE TABLE live_trading_session (
    id                  BIGSERIAL PRIMARY KEY,
    strategy_type       VARCHAR(50) NOT NULL,
    coin_pair           VARCHAR(20) NOT NULL,
    timeframe           VARCHAR(10) NOT NULL,
    initial_capital     NUMERIC(20,2) NOT NULL,
    available_krw       NUMERIC(20,2) NOT NULL,
    total_asset_krw     NUMERIC(20,2) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'STOPPED',  -- RUNNING, STOPPED, EMERGENCY_STOPPED
    strategy_params     JSONB DEFAULT '{}',
    max_investment      NUMERIC(20,2),
    stop_loss_pct       NUMERIC(5,2) DEFAULT 5.0,
    started_at          TIMESTAMPTZ,
    stopped_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);

-- position, order 테이블에 session_id 컬럼 추가
ALTER TABLE position ADD COLUMN session_id BIGINT REFERENCES live_trading_session(id);
ALTER TABLE "order" ADD COLUMN session_id BIGINT REFERENCES live_trading_session(id);

CREATE INDEX idx_live_session_status ON live_trading_session(status);
CREATE INDEX idx_position_session ON position(session_id) WHERE session_id IS NOT NULL;
CREATE INDEX idx_order_session ON "order"(session_id) WHERE session_id IS NOT NULL;
