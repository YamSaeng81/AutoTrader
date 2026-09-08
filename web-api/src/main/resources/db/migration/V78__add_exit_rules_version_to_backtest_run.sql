-- backtest_run 에 청산 규칙 버전 추가 (2026-09-08)
--
-- ■ 왜 필요한가 — 게이트는 "최신" 만 보므로 구버전이 영구히 남는다
--
-- WalkForwardValidationGate 는 (전략 × 코인 × 타임프레임) 조합별로 **가장 최근 실행 하나**를
-- 근거로 실자본 세션 생성을 승인한다. 그래서 조합이 재실행되면 옛 결과는 자연히 밀려나지만,
-- **재실행되지 않은 조합은 수정 전 판정을 영원히 유지한다.** 조용히 낡은 근거로 승인이 난다.
--
-- 2026-09-08 에 백테스트 청산 규칙이 실제로 바뀌었다:
--
--   구분          이전(v1)                     현재(v2)
--   SL 폭         항상 5.0% 고정                clamp(ATR/가격 × 1.5, floor, 8%)
--   TP 폭         SL × 2 = 항상 10%             min(SL × 2, 8%)
--   time stop     없음                          maxHoldHours (24h)
--   트레일링 SL   손실 구간에서 조임(실질 0.3% 손절)  조이지 않음
--
-- 이 차이는 판정을 **양방향으로** 왜곡했다 — 백테스트가 실전보다 훨씬 자주 손절되고(SL 5% 고정),
-- 실전이 결코 설정하지 않는 TP 10% 를 노렸으며, 운영 청산의 47%(83건 중 39건)를 차지하는
-- TIME_STOP 경로가 아예 없었다. 즉 v1 결과는 실전 거동의 근거가 되지 못한다.
--
-- ■ 어떻게 쓰나
--
-- ExitRuleFormula.EXIT_RULES_VERSION 이 현재 버전을 들고 있고, 게이트는 그보다 낮은 실행을
-- **근거로 인정하지 않는다**(= 검증 이력 없음과 동일 취급 → 재실행을 요구한다).
-- 공식이나 상수를 바꾸면 그 상수를 올릴 것. ExitRuleFormulaVersionTest 가 이 규약을 지킨다.
--
-- ■ 기존 행
--
-- NULL 로 남긴다 = "v1 이전 또는 버전 미상". 게이트가 자동으로 거른다.
-- 소급 백필은 하지 않는다 — 어떤 규칙으로 돌았는지 행 자체로는 알 수 없고,
-- 안전한 방향(= 인정하지 않음)이 NULL 이다.

ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS exit_rules_version INT;

COMMENT ON COLUMN backtest_run.exit_rules_version IS
    'ExitRuleFormula.EXIT_RULES_VERSION — 이 실행이 어떤 청산 규칙으로 돌았는지. '
    'NULL = 2026-09-08 이전(SL 5% 고정 · TP 10% · time stop 없음). '
    'WalkForwardValidationGate 는 현재 버전 미만을 근거로 인정하지 않는다.';

-- 게이트는 (전략, 코인, 타임프레임) 으로 좁힌 뒤 created_at desc 로 최신 하나를 집는다.
CREATE INDEX IF NOT EXISTS idx_backtest_run_wf_lookup
    ON backtest_run (strategy_name, coin_pair, timeframe, created_at DESC)
    WHERE is_walk_forward;
