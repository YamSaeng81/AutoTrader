-- 전략 타입별 활성화 여부 테이블
-- 모의투자/실전매매에서 사용할 전략을 선택적으로 활성화/비활성화한다.
CREATE TABLE strategy_type_enabled (
    strategy_name VARCHAR(50) PRIMARY KEY,
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 현재 등록된 10개 전략 기본값(모두 활성)으로 초기 삽입
INSERT INTO strategy_type_enabled (strategy_name) VALUES
    ('VWAP'),
    ('EMA_CROSS'),
    ('BOLLINGER'),
    ('GRID'),
    ('RSI'),
    ('MACD'),
    ('SUPERTREND'),
    ('ATR_BREAKOUT'),
    ('ORDERBOOK_IMBALANCE'),
    ('STOCHASTIC_RSI');
