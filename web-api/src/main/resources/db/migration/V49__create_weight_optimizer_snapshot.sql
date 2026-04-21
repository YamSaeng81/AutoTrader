-- V49: 전략 가중치 최적화 결과 스냅샷 테이블 (analy.md Tier1 §6 미완 서브항목)
-- StrategyWeightOptimizer 실행 후 WeightOverrideStore 내용을 영속화.
-- 서버 재시작 시 이 테이블에서 최신 가중치를 복원해 WeightOverrideStore 를 초기화한다.

CREATE TABLE IF NOT EXISTS weight_optimizer_snapshot (
    id            BIGSERIAL       PRIMARY KEY,
    regime        VARCHAR(50)     NOT NULL,
    coin_pair     VARCHAR(30),                   -- NULL = 레짐 레벨, 값 있음 = 코인 레벨
    strategy_name VARCHAR(100)    NOT NULL,
    weight        NUMERIC(8, 6)   NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wos_key_created
    ON weight_optimizer_snapshot (regime, coin_pair, strategy_name, created_at DESC);
