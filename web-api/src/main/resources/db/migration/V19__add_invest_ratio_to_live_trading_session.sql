-- 실전매매 세션별 투자 비율 설정 (기본 80%)
-- 매수 시 investAmount = availableKrw × investRatio
ALTER TABLE live_trading_session
    ADD COLUMN IF NOT EXISTS invest_ratio NUMERIC(5, 4) NOT NULL DEFAULT 0.8000;
