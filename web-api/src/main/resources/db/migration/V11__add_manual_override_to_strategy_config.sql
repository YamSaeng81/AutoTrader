-- V11: strategy_config 테이블에 manual_override 컬럼 추가
-- manual_override = true 인 전략은 MarketRegimeAwareScheduler 의 자동 활성/비활성 대상에서 제외된다.

ALTER TABLE strategy_config
    ADD COLUMN IF NOT EXISTS manual_override BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN strategy_config.manual_override IS
    '수동 오버라이드 플래그. true이면 시장 상태 기반 자동 스위칭에서 제외된다.';
