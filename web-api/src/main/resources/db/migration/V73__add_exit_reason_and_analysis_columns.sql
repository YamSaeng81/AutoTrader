-- 분석 파이프라인 보강 (2026-08-19)
--
-- 세션을 전부 지우고 새로 기록을 시작하기 직전에 스키마를 확정한다. 데이터가 쌓인 뒤
-- 이 컬럼들을 추가하면 그 기간 데이터에는 값이 없어 분석 대상에서 빠지기 때문이다.

-- ── P1: 청산 사유 구조화 ────────────────────────────────────────────────────
-- 지금까지 청산 사유는 order.signal_reason 자유 텍스트에만 있었고 손익률이 문자열 안에
-- 박혀 있어("시간 초과 청산 — 보유 259시간 ≥ 24시간 (pnl -1.87%)") 값이 전부 유일했다.
-- GROUP BY 가 불가능해 "손절 대 익절 대 시간초과 비율" 이라는 기본 질문에 답할 수 없었다.
-- ExitRuleChecker.ExitType 은 이미 STOP_LOSS/TAKE_PROFIT 을 구분하고 있었는데 호출부에서
-- 버려지고 있었다 — 그 정보를 여기에 보존한다.
ALTER TABLE position               ADD COLUMN IF NOT EXISTS exit_reason VARCHAR(20);
ALTER TABLE paper_trading.position ADD COLUMN IF NOT EXISTS exit_reason VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_position_exit_reason
    ON position (exit_reason) WHERE exit_reason IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_paper_position_exit_reason
    ON paper_trading.position (exit_reason) WHERE exit_reason IS NOT NULL;

-- ── P2/P3: paper_trading.position 을 public.position 과 정렬 ────────────────
-- 페이퍼 함대와 동적 세션 성과를 같은 쿼리로 볼 수 없던 비대칭을 없앤다.
-- closing_at 은 넣지 않는다 — PaperTradingService.closePosition 은 동기라 CLOSING
-- 중간 상태가 없고, 넣어봐야 항상 NULL 인 컬럼이 된다.
ALTER TABLE paper_trading.position ADD COLUMN IF NOT EXISTS market_regime VARCHAR(20);
ALTER TABLE paper_trading.position ADD COLUMN IF NOT EXISTS invested_krw  DECIMAL(20,8);
ALTER TABLE paper_trading.position ADD COLUMN IF NOT EXISTS session_kind  VARCHAR(20);

-- ── P5: 폐기 판정에 규칙 지문 ───────────────────────────────────────────────
-- 판정은 engine/strategy@timeframe#rulesetHash 그룹 단위로 내리는데 기록에는 지문이
-- 없어 "어느 규칙이 폐기됐나" 를 역참조할 수 없었다. 아직 0행이라 지금이 무비용이다.
ALTER TABLE kill_criteria_judgment ADD COLUMN IF NOT EXISTS ruleset_hash VARCHAR(16);
CREATE INDEX IF NOT EXISTS idx_kill_judgment_ruleset
    ON kill_criteria_judgment (ruleset_hash);

-- ── P8: 체결 드리프트의 세션 출처 ───────────────────────────────────────────
-- dynamic_session 과 live_trading_session 은 별도 시퀀스라 session_id 만으로는
-- 어느 엔진의 세션인지 알 수 없다.
ALTER TABLE execution_drift_log ADD COLUMN IF NOT EXISTS session_kind VARCHAR(20);

-- ── P7: 고아 테이블 정리 ────────────────────────────────────────────────────
-- 매핑된 엔티티가 없고 단 한 행도 쓰인 적이 없다. 페이퍼 함대는 로그를
-- public.strategy_log 에 session_type='PAPER' 로 쓰고 포지션만 paper_trading 에 쓴다.
-- 빈 테이블을 남겨두면 나중에 "여기에 있어야 하는 것 아닌가" 하는 오해를 부른다.
-- (regime_change_log 는 MarketRegimeAwareScheduler 가 실제로 쓰는 경로가 있으므로 남긴다.)
DROP TABLE IF EXISTS paper_trading.trade_log;
DROP TABLE IF EXISTS paper_trading.strategy_log;
