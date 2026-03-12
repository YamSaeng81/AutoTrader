-- Paper Trading 세션 관리를 위한 virtual_balance 컬럼 추가
ALTER TABLE paper_trading.virtual_balance
    ADD COLUMN initial_capital  NUMERIC(20,2),
    ADD COLUMN strategy_name    VARCHAR(50),
    ADD COLUMN coin_pair        VARCHAR(20),
    ADD COLUMN timeframe        VARCHAR(10),
    ADD COLUMN status           VARCHAR(10) NOT NULL DEFAULT 'STOPPED',
    ADD COLUMN started_at       TIMESTAMPTZ,
    ADD COLUMN stopped_at       TIMESTAMPTZ;

-- 초기 잔고 레코드 (싱글톤 - 항상 id=1 사용)
INSERT INTO paper_trading.virtual_balance
    (total_krw, available_krw, initial_capital, status)
VALUES
    (10000000, 10000000, 10000000, 'STOPPED');
