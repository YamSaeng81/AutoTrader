CREATE TABLE strategy_log (
    id              BIGSERIAL PRIMARY KEY,
    strategy_name   VARCHAR(50) NOT NULL,
    coin_pair       VARCHAR(20) NOT NULL,
    signal          VARCHAR(10),
    reason          TEXT NOT NULL,
    indicators_json JSONB,
    market_regime   VARCHAR(10),
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE trade_log (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT REFERENCES "order"(id),
    event_type      VARCHAR(30) NOT NULL,
    old_state       VARCHAR(20),
    new_state       VARCHAR(20),
    detail_json     JSONB,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_strategy_log_time ON strategy_log(created_at DESC);
CREATE INDEX idx_trade_log_order ON trade_log(order_id);
