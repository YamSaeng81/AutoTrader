-- 포지션별 손절가/익절가 컬럼 추가 (글로벌 리스크 매니저)
-- 진입 시점에 계산된 절대 가격을 저장하여, 매 틱마다 % 재계산 없이 빠른 비교 가능
-- null 허용: 기존 포지션 및 SL/TP 미설정 포지션과 하위 호환

-- 실전매매 포지션
ALTER TABLE position ADD COLUMN IF NOT EXISTS stop_loss_price  NUMERIC(20, 8);
ALTER TABLE position ADD COLUMN IF NOT EXISTS take_profit_price NUMERIC(20, 8);

-- 모의투자 포지션
ALTER TABLE paper_trading.position ADD COLUMN IF NOT EXISTS stop_loss_price  NUMERIC(20, 8);
ALTER TABLE paper_trading.position ADD COLUMN IF NOT EXISTS take_profit_price NUMERIC(20, 8);
