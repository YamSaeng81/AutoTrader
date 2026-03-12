-- 캔들 데이터 (TimescaleDB hypertable)
CREATE TABLE candle_data (
    time        TIMESTAMPTZ NOT NULL,
    coin_pair   VARCHAR(20) NOT NULL,
    timeframe   VARCHAR(10) NOT NULL,
    open        NUMERIC(20,8) NOT NULL,
    high        NUMERIC(20,8) NOT NULL,
    low         NUMERIC(20,8) NOT NULL,
    close       NUMERIC(20,8) NOT NULL,
    volume      NUMERIC(20,8) NOT NULL
);

SELECT create_hypertable('candle_data', 'time');

-- 동일 캔들 중복 삽입 방지 (UPSERT용)
CREATE UNIQUE INDEX idx_candle_unique ON candle_data (coin_pair, timeframe, time);

-- 복합 인덱스: 코인+타임프레임+시간 조회 최적화
CREATE INDEX idx_candle_lookup ON candle_data (coin_pair, timeframe, time DESC);

-- 90일 이상 된 분봉 데이터 압축
ALTER TABLE candle_data SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'coin_pair, timeframe'
);
SELECT add_compression_policy('candle_data', INTERVAL '90 days');
