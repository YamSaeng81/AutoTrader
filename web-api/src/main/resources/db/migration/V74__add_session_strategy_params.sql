-- 세션별 전략 파라미터 (2026-08-19) — A/B 실험을 가능하게 만든다.
--
-- 배경: 페이퍼 함대 112세션 중 93개가 12일간 한 번도 거래하지 않았다. 원인을 분해하니
--   2일치 HOLD 7,033건 중 88%가 "지표가 하나도 안 켜짐"(buy=0.00)이었고, 나머지 중
--   TRANSITIONAL 감쇠 45건 + EMA 하락추세 필터 21건이 임계값(0.3)을 넘겼을 매수 점수를
--   죽이고 있었다. 같은 2일간 실제 발생한 BUY 신호가 51건이니 감쇠로 죽은 쪽이 더 많다.
--
-- 문제: 이 감쇠 파라미터들(emaFilterDampenFactor 등)은 risk_config **전역값**이라
--   바꾸면 모든 세션이 한꺼번에 움직인다. 대조군이 없어 A/B 자체가 불가능하다.
--   시점을 나눠 비교하는 방식은 시장 국면이 교란해 결론을 낼 수 없다.
--
-- 해법: LIVE 세션에만 있던 strategy_params 를 동적·페이퍼 세션에도 준다. 같은 시간대에
--   같은 전략을 서로 다른 파라미터로 돌리고, 규칙 지문이 두 팔을 갈라준다.
--
-- 주의: 이 값들이 **지문에 실려야** 의미가 있다. 안 실으면 서로 다른 규칙의 거래가
--   한 표본에 섞여 A/B 가 오히려 데이터를 망친다. RulesetRegistry.hashFor 참조.
ALTER TABLE dynamic_session ADD COLUMN IF NOT EXISTS strategy_params jsonb;
ALTER TABLE paper_trading.virtual_balance ADD COLUMN IF NOT EXISTS strategy_params jsonb;
