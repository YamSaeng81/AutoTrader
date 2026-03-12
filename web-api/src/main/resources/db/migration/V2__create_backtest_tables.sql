-- 백테스트 실행 기록
CREATE TABLE backtest_run (
    id              BIGSERIAL PRIMARY KEY,
    strategy_name   VARCHAR(50) NOT NULL,
    coin_pair       VARCHAR(20) NOT NULL,
    timeframe       VARCHAR(10) NOT NULL,
    start_date      TIMESTAMPTZ NOT NULL,
    end_date        TIMESTAMPTZ NOT NULL,
    initial_capital NUMERIC(20,2) NOT NULL DEFAULT 10000000,
    slippage_pct    NUMERIC(5,3) DEFAULT 0.1,
    fee_pct         NUMERIC(5,3) DEFAULT 0.05,
    config_json     JSONB NOT NULL,
    fill_simulation_json JSONB,
    is_walk_forward BOOLEAN DEFAULT FALSE,
    wf_in_sample    TIMESTAMPTZ,
    wf_out_sample   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- 백테스트 성과 지표
CREATE TABLE backtest_metrics (
    id                  BIGSERIAL PRIMARY KEY,
    backtest_run_id     BIGINT NOT NULL REFERENCES backtest_run(id),
    total_return_pct    NUMERIC(10,4),
    win_rate_pct        NUMERIC(5,2),
    mdd_pct             NUMERIC(10,4),
    sharpe_ratio        NUMERIC(8,4),
    sortino_ratio       NUMERIC(8,4),
    calmar_ratio        NUMERIC(8,4),
    win_loss_ratio      NUMERIC(8,4),
    recovery_factor     NUMERIC(8,4),
    total_trades        INTEGER,
    winning_trades      INTEGER,
    losing_trades       INTEGER,
    avg_profit_pct      NUMERIC(8,4),
    avg_loss_pct        NUMERIC(8,4),
    max_consecutive_loss INTEGER,
    monthly_returns_json JSONB,
    segment             VARCHAR(20) DEFAULT 'FULL'
);

CREATE INDEX idx_metrics_run ON backtest_metrics(backtest_run_id);

-- 백테스트 매매 기록
CREATE TABLE backtest_trade (
    id              BIGSERIAL PRIMARY KEY,
    backtest_run_id BIGINT NOT NULL REFERENCES backtest_run(id),
    side            VARCHAR(4) NOT NULL,
    price           NUMERIC(20,8) NOT NULL,
    quantity        NUMERIC(20,8) NOT NULL,
    fee             NUMERIC(20,8),
    slippage        NUMERIC(20,8),
    pnl             NUMERIC(20,8),
    cumulative_pnl  NUMERIC(20,8),
    signal_reason   TEXT,
    market_regime   VARCHAR(10),
    executed_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_bt_trade_run ON backtest_trade(backtest_run_id, executed_at);
