-- 실시간 시장 데이터 캐시 테이블
-- MarketDataSyncService 가 RUNNING 세션 대상으로 자동 싱크하는 데이터를 저장.
-- candle_data(수동 수집, 백테스트용)와 완전히 분리.
CREATE TABLE market_data_cache (
    time        TIMESTAMPTZ   NOT NULL,
    coin_pair   VARCHAR(20)   NOT NULL,
    timeframe   VARCHAR(10)   NOT NULL,
    open        NUMERIC(20,8) NOT NULL,
    high        NUMERIC(20,8) NOT NULL,
    low         NUMERIC(20,8) NOT NULL,
    close       NUMERIC(20,8) NOT NULL,
    volume      NUMERIC(20,8) NOT NULL,
    PRIMARY KEY (time, coin_pair, timeframe)
);

CREATE INDEX idx_market_data_cache_pair_tf_time
    ON market_data_cache (coin_pair, timeframe, time DESC);
