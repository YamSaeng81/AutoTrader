-- V47: strategy_log 에 confidence_score 컬럼 추가
-- CompositeStrategy 의 buyScore/sellScore 정규화값 (0.0~1.0), HOLD 는 null
ALTER TABLE strategy_log
    ADD COLUMN IF NOT EXISTS confidence_score NUMERIC(5,4);
