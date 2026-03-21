-- 모의투자 세션 단위 누적 실현손익 및 수수료 추적 (손익계산식.md §4.1)
ALTER TABLE paper_trading.virtual_balance
    ADD COLUMN IF NOT EXISTS realized_pnl NUMERIC(20, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_fee     NUMERIC(20, 2) NOT NULL DEFAULT 0;
