-- 야간 자동 백테스트 스케줄러 설정 (싱글톤 행: id=1 고정)
CREATE TABLE nightly_scheduler_config (
    id                  BIGINT         PRIMARY KEY DEFAULT 1,
    enabled             BOOLEAN        NOT NULL DEFAULT FALSE,

    -- 실행 시각 (KST 기준)
    run_hour            SMALLINT       NOT NULL DEFAULT 0  CHECK (run_hour   BETWEEN 0 AND 23),
    run_minute          SMALLINT       NOT NULL DEFAULT 0  CHECK (run_minute BETWEEN 0 AND 59),

    -- 분석 설정
    timeframe           VARCHAR(10)    NOT NULL DEFAULT 'H1',
    start_date          DATE           NOT NULL DEFAULT '2023-01-01',
    end_date            DATE           NOT NULL DEFAULT '2025-12-31',

    -- 코인·전략 (콤마 구분 문자열)
    coin_pairs          TEXT           NOT NULL DEFAULT 'KRW-BTC,KRW-ETH,KRW-SOL,KRW-XRP,KRW-DOGE',
    strategy_types      TEXT           NOT NULL DEFAULT 'COMPOSITE_BREAKOUT,COMPOSITE_MOMENTUM,COMPOSITE_MOMENTUM_ICHIMOKU,COMPOSITE_MOMENTUM_ICHIMOKU_V2',

    -- 실행 옵션
    include_backtest    BOOLEAN        NOT NULL DEFAULT TRUE,
    include_walk_forward BOOLEAN       NOT NULL DEFAULT TRUE,
    in_sample_ratio     DOUBLE PRECISION NOT NULL DEFAULT 0.7,
    window_count        SMALLINT       NOT NULL DEFAULT 5,
    initial_capital     DECIMAL(18,0)  NOT NULL DEFAULT 1000000,
    slippage_pct        DECIMAL(5,2)   NOT NULL DEFAULT 0.05,
    fee_pct             DECIMAL(5,2)   NOT NULL DEFAULT 0.05,

    -- 실행 이력
    last_triggered_at   TIMESTAMPTZ,
    last_batch_job_id   BIGINT,
    last_wf_job_id      BIGINT,
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- 기본값 행 삽입 (비활성 상태로 시작)
INSERT INTO nightly_scheduler_config (id) VALUES (1);
