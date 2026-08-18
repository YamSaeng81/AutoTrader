-- V69 (2026-08-18) — 서킷 브레이커 누적 발동 횟수.
--
-- 배경: kill criteria(docs/KILL_CRITERIA.md §4.A)의 CB_REPEAT 판정에 필요하다.
-- 기존에는 circuit_breaker_triggered_at 타임스탬프 한 칸만 있어 발동할 때마다 덮어써졌고,
-- 서킷 브레이커는 발동 즉시 세션을 EMERGENCY_STOPPED 로 내리므로 "몇 번 걸렸는지"는
-- 어디에도 남지 않았다. 재시작이 수동인 이상 누적 횟수가 곧 "구조적 결함의 반복 횟수"다.
--
-- 기존 행은 0 으로 시작한다. 과거 발동 이력은 복원할 수 없으므로 소급하지 않는다
-- (circuit_breaker_triggered_at 이 NOT NULL 인 세션이라도 횟수는 알 수 없다).

ALTER TABLE live_trading_session
    ADD COLUMN IF NOT EXISTS circuit_breaker_trip_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE dynamic_session
    ADD COLUMN IF NOT EXISTS circuit_breaker_trip_count INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN live_trading_session.circuit_breaker_trip_count IS
    '서킷 브레이커 누적 발동 횟수 — kill criteria CB_REPEAT 판정용 (V69, 2026-08-18)';

COMMENT ON COLUMN dynamic_session.circuit_breaker_trip_count IS
    '서킷 브레이커 누적 발동 횟수 — kill criteria CB_REPEAT 판정용 (V69, 2026-08-18)';
