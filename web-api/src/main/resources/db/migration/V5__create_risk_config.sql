CREATE TABLE risk_config (
    id                  BIGSERIAL PRIMARY KEY,
    max_daily_loss_pct  NUMERIC(5,2) DEFAULT 3.0,
    max_weekly_loss_pct NUMERIC(5,2) DEFAULT 7.0,
    max_monthly_loss_pct NUMERIC(5,2) DEFAULT 15.0,
    max_positions       INTEGER DEFAULT 3,
    cooldown_minutes    INTEGER DEFAULT 60,
    portfolio_limit_krw NUMERIC(20,2),
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);
