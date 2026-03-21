-- 실전매매 포지션 수수료 컬럼 추가 — /performance 수수료 대시보드 정확화
ALTER TABLE position
    ADD COLUMN IF NOT EXISTS position_fee NUMERIC(20, 2) NOT NULL DEFAULT 0;
