CREATE TABLE strategy_signal (
    id              BIGSERIAL PRIMARY KEY,
    strategy_config_id BIGINT REFERENCES strategy_config(id),
    coin_pair       VARCHAR(20) NOT NULL,
    timeframe       VARCHAR(10) NOT NULL,
    signal          VARCHAR(10) NOT NULL,
    strength        NUMERIC(5,2),
    reason          TEXT NOT NULL,
    indicators_json JSONB,
    market_regime   VARCHAR(10),
    candle_time     TIMESTAMPTZ NOT NULL,
    was_executed    BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_signal_lookup ON strategy_signal(coin_pair, created_at DESC);
CREATE INDEX idx_signal_strategy ON strategy_signal(strategy_config_id, created_at DESC);
