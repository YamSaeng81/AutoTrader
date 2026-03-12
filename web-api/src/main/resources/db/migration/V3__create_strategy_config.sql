CREATE TABLE strategy_config (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    strategy_type   VARCHAR(50) NOT NULL,
    coin_pair       VARCHAR(20) NOT NULL,
    timeframe       VARCHAR(10) NOT NULL,
    config_json     JSONB NOT NULL,
    is_active       BOOLEAN DEFAULT TRUE,
    max_investment  NUMERIC(20,2),
    stop_loss_pct   NUMERIC(5,2),
    reinvest_pct    NUMERIC(5,2) DEFAULT 0,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);
