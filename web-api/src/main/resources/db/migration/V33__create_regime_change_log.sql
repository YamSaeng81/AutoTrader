-- V33: 시장 레짐 전환 이력 테이블 생성

CREATE TABLE IF NOT EXISTS regime_change_log (
    id                      BIGSERIAL       PRIMARY KEY,
    coin_pair               VARCHAR(20)     NOT NULL,
    timeframe               VARCHAR(10)     NOT NULL,
    from_regime             VARCHAR(20),
    to_regime               VARCHAR(20)     NOT NULL,
    strategy_changes_json   TEXT,
    detected_at             TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_regime_change_log_detected_at
    ON regime_change_log (detected_at DESC);

CREATE INDEX IF NOT EXISTS idx_regime_change_log_coin_pair
    ON regime_change_log (coin_pair, timeframe, detected_at DESC);
