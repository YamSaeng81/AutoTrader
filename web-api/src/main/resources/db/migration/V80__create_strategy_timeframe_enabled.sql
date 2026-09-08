-- (전략 × 타임프레임) 차단 목록 (2026-09-08)
--
-- ■ 왜 필요한가 — 폐기 판정이 재생성을 막지 못한다
--
-- kill criteria 의 판정 단위는 **세션(= 전략 × 타임프레임)** 인데, 차단에 쓰는
-- strategy_type_enabled 는 **전략명만** 키로 쓴다. 비활성화가 판정보다 한 단계 거칠다.
--
-- 그래서 StrategyKillCriteriaService.disableFullyKilledStrategies 는 보수적으로 우회했다 —
-- "그 전략의 **모든** 변형이 폐기일 때만" 비활성화한다. MEANREV_BB@M15 하나가 죽었다고 끄면
-- 멀쩡한 MEANREV_BB@H1 까지 막히기 때문이다. 이 판단 자체는 옳다.
--
-- 문제는 그 우회의 **결과**다:
--
--   MEANREV_BB@M15 KILL  →  세션 정지                         ✅
--                        →  MEANREV_BB@M15 새 세션 생성 차단?  ❌ 아무도 안 막는다
--
-- KILL_CRITERIA.md §5 가 전략 비활성화를 두는 이유가 정확히 "세션만 정지하면 같은 전략으로
-- 새 세션을 만들어 그대로 재개할 수 있다" 인데, 타임프레임 단위 폐기에서는 그 목적이
-- 달성되지 않는다. **자동정지를 켜는 순간 이 구멍이 실제 동작이 된다.**
-- (kill-criteria.auto-stop 은 현재 OFF — 켜기 전에 고쳐 둔다.)
--
-- ■ 왜 새 테이블인가
--
-- strategy_type_enabled 의 PK 가 strategy_name 이라 컬럼을 더해 복합키로 바꾸면 기존 21행과
-- 조회 경로가 전부 영향을 받는다. 두 층은 의미도 다르다:
--
--   strategy_type_enabled       — 전략 전체 차단 (모든 변형이 죽었을 때)
--   strategy_timeframe_enabled  — 그 전략의 특정 타임프레임만 차단  ← 이 테이블
--
-- ■ 부재 = 활성
--
-- strategy_type_enabled 과 같은 규칙이다. 행이 없으면 허용 — 이 테이블은 **차단 목록**이다.
-- 기본값을 막는 쪽으로 두면 등재되지 않은 조합이 전부 즉시 막힌다.

CREATE TABLE IF NOT EXISTS strategy_timeframe_enabled (
    strategy_name VARCHAR(100) NOT NULL,
    timeframe     VARCHAR(10)  NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 왜 껐는지 — 판정 코드(CAPITAL_LOSS / NO_EDGE / NO_SIGNAL 등)와 사유를 남긴다.
    -- 부활 판단(Walk Forward 재검증)이 근거 없이 이뤄지지 않도록.
    disabled_reason TEXT,
    disabled_at   TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (strategy_name, timeframe)
);

COMMENT ON TABLE strategy_timeframe_enabled IS
    '(전략 × 타임프레임) 차단 목록 — 행이 없으면 활성. kill criteria 가 타임프레임 단위 폐기 시 '
    '여기에 기록하고, StrategyEnablementGate 가 세 세션 생성 경로에서 확인한다. '
    '전략 전체 차단은 strategy_type_enabled 가 담당한다.';
