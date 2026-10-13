-- 위험 설정 기본 행 (2026-10-14)
--
-- ■ 고치는 문제 — 읽기 경로가 기본 행을 INSERT 했다
--
-- `RiskManagementService.getRiskConfig()` 는 행이 없으면 그 자리에서 기본 행을 저장했다. 호출자 대부분이 읽기 전용
-- 트랜잭션이라 PostgreSQL 은 "cannot execute INSERT in a read-only transaction" 으로 거부한다(실제 재시작 시험의 빈
-- DB 에서 확인, H2 는 읽기 전용을 강제하지 않아 시험에 드러나지 않았다). 운영 DB 에 행이 있다는 전제에 기대지 않도록
-- 기본 행을 마이그레이션으로 둔다. 읽기 경로는 이제 행을 만들지 않고, 행이 없으면 신규 진입을 막는다.
--
-- 🔴 값은 열 기본값이 아니라 **코드 기본값**(`RiskConfigEntity.prePersist`)이다 — 둘이 다르다(열 기본값은 일일 3.0%·
--    최대 포지션 3·투자 비율 80% 등, 코드는 1.5%·8·25%). 지금까지 빈 DB 에서 만들어지던 행과 같은 값을 넣는다.
-- 🔴 행이 이미 있으면 아무것도 하지 않는다 — 운영의 기존 설정을 바꾸지 않는다.

INSERT INTO risk_config (max_daily_loss_pct, max_weekly_loss_pct, max_monthly_loss_pct, max_positions,
                         max_capital_utilization_pct, cooldown_minutes, max_portfolio_drawdown_pct, mdd_threshold_pct,
                         consecutive_loss_limit, circuit_breaker_enabled, stop_loss_pct, take_profit_multiplier,
                         trailing_enabled, trailing_tp_margin_pct, trailing_sl_margin_pct, invest_ratio_pct, updated_at)
SELECT 1.5, 4.0, 8.0, 8, 50.0, 60, 15.0, 10.0, 5, TRUE, 5.0, 2.0, TRUE, 0.5, 0.3, 25.0, now()
WHERE NOT EXISTS (SELECT 1 FROM risk_config);
