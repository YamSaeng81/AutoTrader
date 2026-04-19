-- 20260415_analy.md Tier 3 §14 — 실전/백테스트 drift 트래커
-- 실전 체결가 vs 신호 생성 시점 가정가의 편차를 거래별로 저장한다.
CREATE TABLE IF NOT EXISTS execution_drift_log (
    id               BIGSERIAL PRIMARY KEY,
    session_id       BIGINT       NOT NULL,
    coin_pair        VARCHAR(20)  NOT NULL,
    strategy_type    VARCHAR(80)  NOT NULL,
    side             VARCHAR(10)  NOT NULL,           -- BUY | SELL
    signal_price     NUMERIC(20,2) NOT NULL,          -- 신호 생성 시 가정 체결가
    fill_price       NUMERIC(20,2) NOT NULL,          -- 실제 체결가
    slippage_pct     NUMERIC(10,6) NOT NULL,          -- (fill - signal) / signal × 100
    executed_at      TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_drift_log_strategy  ON execution_drift_log (strategy_type, executed_at);
CREATE INDEX idx_drift_log_session   ON execution_drift_log (session_id);
