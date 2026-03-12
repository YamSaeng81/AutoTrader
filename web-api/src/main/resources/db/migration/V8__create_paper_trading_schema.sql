CREATE SCHEMA paper_trading;

CREATE TABLE paper_trading.position (LIKE public.position INCLUDING ALL);
CREATE TABLE paper_trading."order" (LIKE public."order" INCLUDING ALL);
CREATE TABLE paper_trading.strategy_log (LIKE public.strategy_log INCLUDING ALL);
CREATE TABLE paper_trading.trade_log (LIKE public.trade_log INCLUDING ALL);

CREATE TABLE paper_trading.virtual_balance (
    id              BIGSERIAL PRIMARY KEY,
    total_krw       NUMERIC(20,2) NOT NULL,
    available_krw   NUMERIC(20,2) NOT NULL,
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);
