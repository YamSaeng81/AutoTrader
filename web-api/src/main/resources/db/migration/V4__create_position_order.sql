CREATE TABLE position (
    id                  BIGSERIAL PRIMARY KEY,
    coin_pair           VARCHAR(20) NOT NULL,
    side                VARCHAR(4) NOT NULL,
    entry_price         NUMERIC(20,8) NOT NULL,
    avg_price           NUMERIC(20,8) NOT NULL,
    size                NUMERIC(20,8) NOT NULL,
    unrealized_pnl      NUMERIC(20,8) DEFAULT 0,
    realized_pnl        NUMERIC(20,8) DEFAULT 0,
    strategy_config_id  BIGINT REFERENCES strategy_config(id),
    status              VARCHAR(10) DEFAULT 'OPEN',
    opened_at           TIMESTAMPTZ DEFAULT NOW(),
    closed_at           TIMESTAMPTZ
);

CREATE INDEX idx_position_open ON position(status, coin_pair) WHERE status = 'OPEN';

CREATE TABLE "order" (
    id                  BIGSERIAL PRIMARY KEY,
    position_id         BIGINT REFERENCES position(id),
    coin_pair           VARCHAR(20) NOT NULL,
    side                VARCHAR(4) NOT NULL,
    order_type          VARCHAR(10) NOT NULL,
    price               NUMERIC(20,8),
    quantity            NUMERIC(20,8) NOT NULL,
    state               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    exchange_order_id   VARCHAR(100),
    filled_quantity     NUMERIC(20,8) DEFAULT 0,
    signal_reason       TEXT,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    submitted_at        TIMESTAMPTZ,
    filled_at           TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    failed_reason       TEXT
);

CREATE INDEX idx_order_state ON "order"(state) WHERE state IN ('PENDING', 'SUBMITTED', 'PARTIAL_FILLED');
CREATE INDEX idx_order_position ON "order"(position_id);
