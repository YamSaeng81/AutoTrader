-- 포지션 진입 시점 시장 레짐 저장 (V32)
-- 레짐별 성과 분리 분석을 위해 position 테이블에 market_regime 컬럼 추가
ALTER TABLE position
    ADD COLUMN IF NOT EXISTS market_regime VARCHAR(20);
