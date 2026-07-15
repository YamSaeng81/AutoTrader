-- 동적 세션 서킷 브레이커 발동 기록
-- 라이브 세션(live_trading_session)에만 있던 컬럼을 동적 세션에도 추가한다.
-- 2026-07-15 운영 DB 분석: 진입 완화(2차) 배포로 동적 세션이 실제 매매를 시작하는데,
-- 연속 손실 서킷 브레이커가 라이브 전용이라 동적 세션은 무제한 반복 손실이 가능했다.
ALTER TABLE dynamic_session ADD COLUMN circuit_breaker_triggered_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE dynamic_session ADD COLUMN circuit_breaker_reason VARCHAR(500);
