-- 동적 세션 SCANNING 진입 완화 파라미터 설정화 (2026-07-15)
-- 기존에는 DynamicTradingService 코드 상수라 값 하나 조정에 재빌드+재배포가 필요했다.
-- NULL이면 코드 기본값(weak 0.20 / strong 0.40 / 감쇠 0.70 / EMA200 마진 3.0%) 사용.
ALTER TABLE risk_config ADD COLUMN scan_weak_threshold NUMERIC(4, 2);
ALTER TABLE risk_config ADD COLUMN scan_strong_threshold NUMERIC(4, 2);
ALTER TABLE risk_config ADD COLUMN scan_ema_dampen_factor NUMERIC(4, 2);
ALTER TABLE risk_config ADD COLUMN scan_ema200_buy_margin_pct NUMERIC(5, 2);
