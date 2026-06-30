-- 동적 멀티코인 세션 테이블
-- 종목을 고정하지 않고 watchlist를 실시간 필터링해 매매하는 세션
CREATE TABLE dynamic_session (
    id                          BIGSERIAL PRIMARY KEY,
    strategy_type               VARCHAR(50)     NOT NULL,
    timeframe                   VARCHAR(10)     NOT NULL,
    initial_capital             NUMERIC(20, 2)  NOT NULL,
    available_krw               NUMERIC(20, 2)  NOT NULL,
    total_asset_krw             NUMERIC(20, 2)  NOT NULL,
    invest_ratio                NUMERIC(5, 4)   NOT NULL DEFAULT 0.8000,
    stop_loss_pct               NUMERIC(5, 2)   NOT NULL DEFAULT 5.00,
    status                      VARCHAR(20)     NOT NULL DEFAULT 'CREATED',
    -- SCANNING: 10개 코인 감시 중 / POSITION_MONITORING: 포지션 보유 중
    scan_state                  VARCHAR(25)     NOT NULL DEFAULT 'SCANNING',
    -- POSITION_MONITORING 상태일 때 현재 보유 종목
    current_coin_pair           VARCHAR(20),
    current_position_id         BIGINT,
    -- 워치리스트 필터 설정
    max_candidate_size          INT             NOT NULL DEFAULT 30,
    target_watch_size           INT             NOT NULL DEFAULT 10,
    min_atr_pct                 NUMERIC(6, 4)   NOT NULL DEFAULT 0.5000,
    max_spread_pct              NUMERIC(6, 4)   NOT NULL DEFAULT 0.1000,
    watchlist_refresh_min       INT             NOT NULL DEFAULT 60,
    -- 캐시된 워치리스트 (JSON 배열: ["KRW-BTC","KRW-ETH",...])
    watchlist_json              TEXT,
    watchlist_refreshed_at      TIMESTAMP WITH TIME ZONE,
    -- 낙관적 락
    version                     BIGINT          NOT NULL DEFAULT 0,
    mdd_peak_capital            NUMERIC(20, 2),
    started_at                  TIMESTAMP WITH TIME ZONE,
    stopped_at                  TIMESTAMP WITH TIME ZONE,
    created_at                  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at                  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_dynamic_session_status ON dynamic_session (status);
CREATE INDEX idx_dynamic_session_scan_state ON dynamic_session (scan_state);
