-- 동적 워치리스트 품질 큐레이션 파라미터 (2026-07-24)
-- 배경: 동적 멀티코인 세션이 2주 넘게 실거래 0건. 운영 DB 분석 결과 "거래대금 상위" 원시
-- 유니버스가 펌프-덤프 잡코인으로 채워져, BUY 신호를 낸 코인이 거의 전부 급락 중이라
-- BLACK_SWAN_GUARD(79%)·EMA200 게이트(17%)가 신호를 전량 사후 차단했다. 유니버스 자체를
-- 앞단에서 큐레이션해 신호↔진입게이트 상쇄 구조를 해소한다. WatchlistQualityGate 참조.
-- NULL이면 DynamicTradingService 코드 기본값 사용(재빌드 없이 SQL/API로 조정 가능).
ALTER TABLE risk_config ADD COLUMN scan_min_trade_value_krw NUMERIC(20, 2);
ALTER TABLE risk_config ADD COLUMN scan_max_atr_pct          NUMERIC(6, 4);
ALTER TABLE risk_config ADD COLUMN scan_require_uptrend      BOOLEAN;
ALTER TABLE risk_config ADD COLUMN scan_exclude_crashing     BOOLEAN;
