-- H2 인메모리 DB용 테스트 스키마
-- PostgreSQL 전용 타입(jsonb, hypertable 등)을 H2 호환 타입으로 대체한다.

-- paper_trading 스키마 생성 (엔티티에서 schema = "paper_trading" 지정)
CREATE SCHEMA IF NOT EXISTS paper_trading;

-- 캔들 데이터 (TimescaleDB hypertable 대신 일반 테이블)
CREATE TABLE IF NOT EXISTS candle_data (
    time        TIMESTAMP       NOT NULL,
    coin_pair   VARCHAR(20)     NOT NULL,
    timeframe   VARCHAR(10)     NOT NULL,
    open        DECIMAL(20, 8)  NOT NULL,
    high        DECIMAL(20, 8)  NOT NULL,
    low         DECIMAL(20, 8)  NOT NULL,
    close       DECIMAL(20, 8)  NOT NULL,
    volume      DECIMAL(30, 8)  NOT NULL,
    PRIMARY KEY (time, coin_pair, timeframe)
);

-- 백테스트 실행 기록
CREATE TABLE IF NOT EXISTS backtest_run (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    strategy_name       VARCHAR(50)     NOT NULL,
    coin_pair           VARCHAR(20)     NOT NULL,
    timeframe           VARCHAR(10)     NOT NULL,
    start_date          TIMESTAMP       NOT NULL,
    end_date            TIMESTAMP       NOT NULL,
    initial_capital     DECIMAL(20, 2)  NOT NULL,
    slippage_pct        DECIMAL(10, 4),
    fee_pct             DECIMAL(10, 4),
    config_json         CLOB            NOT NULL DEFAULT '{}',
    fill_simulation_json CLOB,
    is_walk_forward     BOOLEAN,
    wf_in_sample        TIMESTAMP,
    wf_out_sample       TIMESTAMP,
    created_at          TIMESTAMP
);

-- 백테스트 성과 지표
CREATE TABLE IF NOT EXISTS backtest_metrics (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    backtest_run_id     BIGINT          NOT NULL,
    total_return_pct    DECIMAL(10, 4),
    win_rate_pct        DECIMAL(10, 4),
    mdd_pct             DECIMAL(10, 4),
    sharpe_ratio        DECIMAL(10, 4),
    sortino_ratio       DECIMAL(10, 4),
    calmar_ratio        DECIMAL(10, 4),
    win_loss_ratio      DECIMAL(10, 4),
    recovery_factor     DECIMAL(10, 4),
    total_trades        INTEGER,
    winning_trades      INTEGER,
    losing_trades       INTEGER,
    avg_profit_pct      DECIMAL(10, 4),
    avg_loss_pct        DECIMAL(10, 4),
    max_consecutive_loss INTEGER,
    monthly_returns_json CLOB,
    segment             VARCHAR(20)
);

-- 백테스트 개별 거래 내역
CREATE TABLE IF NOT EXISTS backtest_trade (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    backtest_run_id BIGINT          NOT NULL,
    side            VARCHAR(4)      NOT NULL,
    price           DECIMAL(20, 8)  NOT NULL,
    quantity        DECIMAL(20, 8)  NOT NULL,
    fee             DECIMAL(20, 8),
    slippage        DECIMAL(20, 8),
    pnl             DECIMAL(20, 8),
    cumulative_pnl  DECIMAL(20, 8),
    signal_reason   TEXT,
    market_regime   VARCHAR(20),
    executed_at     TIMESTAMP       NOT NULL
);

-- 전략 설정
-- config_json: jsonb 대신 CLOB 사용 (Hibernate @JdbcTypeCode(SqlTypes.JSON) 호환)
CREATE TABLE IF NOT EXISTS strategy_config (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL,
    strategy_type   VARCHAR(50)     NOT NULL,
    coin_pair       VARCHAR(20)     NOT NULL,
    timeframe       VARCHAR(10)     NOT NULL,
    config_json     CLOB            NOT NULL DEFAULT '{}',
    is_active       BOOLEAN,
    manual_override BOOLEAN,
    max_investment  DECIMAL(20, 2),
    stop_loss_pct   DECIMAL(10, 4),
    reinvest_pct    DECIMAL(10, 4),
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP
);

-- 전략 신호 로그
CREATE TABLE IF NOT EXISTS strategy_log (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    strategy_name   VARCHAR(50)     NOT NULL,
    coin_pair       VARCHAR(20)     NOT NULL,
    signal          VARCHAR(10),
    reason          TEXT            NOT NULL,
    indicators_json CLOB,
    market_regime   VARCHAR(20),
    created_at      TIMESTAMP
);

-- ── paper_trading 스키마 테이블 ──────────────────────────────────────

-- 모의투자 세션 (가상 잔고)
CREATE TABLE IF NOT EXISTS paper_trading.virtual_balance (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    total_krw       DECIMAL(20, 2)  NOT NULL,
    available_krw   DECIMAL(20, 2)  NOT NULL,
    initial_capital DECIMAL(20, 2),
    strategy_name   VARCHAR(50),
    coin_pair       VARCHAR(20),
    timeframe       VARCHAR(10),
    status          VARCHAR(10)     NOT NULL,
    started_at      TIMESTAMP,
    stopped_at      TIMESTAMP,
    updated_at      TIMESTAMP
);

-- 모의투자 포지션
CREATE TABLE IF NOT EXISTS paper_trading.position (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    coin_pair           VARCHAR(20)     NOT NULL,
    side                VARCHAR(4)      NOT NULL,
    entry_price         DECIMAL(20, 8)  NOT NULL,
    avg_price           DECIMAL(20, 8)  NOT NULL,
    size                DECIMAL(20, 8)  NOT NULL,
    unrealized_pnl      DECIMAL(20, 8),
    realized_pnl        DECIMAL(20, 8),
    strategy_config_id  BIGINT,
    session_id          BIGINT,
    status              VARCHAR(10),
    opened_at           TIMESTAMP,
    closed_at           TIMESTAMP
);

-- 모의투자 주문 (order는 H2 예약어이므로 따옴표 처리)
CREATE TABLE IF NOT EXISTS paper_trading."order" (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    session_id          BIGINT,
    position_id         BIGINT,
    coin_pair           VARCHAR(20)     NOT NULL,
    side                VARCHAR(4)      NOT NULL,
    order_type          VARCHAR(10)     NOT NULL,
    price               DECIMAL(20, 8),
    quantity            DECIMAL(20, 8)  NOT NULL,
    state               VARCHAR(20)     NOT NULL,
    exchange_order_id   VARCHAR(100),
    filled_quantity     DECIMAL(20, 8),
    signal_reason       TEXT,
    created_at          TIMESTAMP,
    submitted_at        TIMESTAMP,
    filled_at           TIMESTAMP,
    cancelled_at        TIMESTAMP,
    failed_reason       TEXT
);
