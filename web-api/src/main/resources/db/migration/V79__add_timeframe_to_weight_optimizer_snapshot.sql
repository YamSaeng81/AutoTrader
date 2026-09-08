-- weight_optimizer_snapshot 에 timeframe 추가 (2026-09-08)
--
-- ■ 왜 필요한가 — 가중치 최적화가 H1 과 M15 를 한 값으로 합치고 있었다
--
-- StrategyWeightOptimizer 는 청산 포지션을 (레짐) 또는 (레짐:코인) 으로 묶어 전략별 가중치를
-- 낸다 — **타임프레임이 키에 없다.** 그래서 같은 전략·코인·레짐이면 H1 과 M15 성적이
-- 한 가중치로 합쳐진다.
--
-- 그런데 두 타임프레임은 부호가 반대인 경우가 실제로 있다 (2026-08-01~ BUY 신호 사후 4h):
--
--   전략                             H1        M15
--   COMPOSITE_MTF_BTC              -1.428    +0.046   ← 부호가 반대
--   COMPOSITE_MTF_BTC_STRICT       -0.509    +0.518
--   COMPOSITE_MOMENTUM_ICHIMOKU_V2 -0.367    +0.142
--
-- 합치면 서로 상쇄돼 **최적화가 아무 방향도 잡지 못한다.** 게다가 지수 가중(반감기 14일)까지
-- 걸려 있어, 어느 타임프레임의 거래가 최근에 많았는지에 따라 값이 흔들린다.
--
-- ■ 같은 축 누락이 이번이 세 번째다
--
--   08-24  WF 게이트가 전략 → 전략×코인 으로 좁히며 타임프레임을 놓침        (09-08 수정)
--   09-08  strategy_log 소비자 전체가 타임프레임 없이 집계                   (V76)
--   09-08  weight_optimizer_snapshot / WeightOverrideStore 키에 없음         (이 마이그레이션)
--
-- ■ 집계 원천은 이미 조인돼 있었다
--
-- ENGINE_PARITY.md 는 "position 에 timeframe 이 없어 session_id 조인이 필요하다" 를 착수를
-- 미루는 근거로 적었지만, PositionRepository 의 두 가중치 쿼리는 **이미 live_trading_session 을
-- 조인**하고 있어 s.timeframe 을 한 컬럼 더 고르면 된다. 실제 비용은 추정보다 훨씬 작았다.
--
-- ■ 기존 행
--
-- NULL 로 남긴다 = "타임프레임 무관 가중치". WeightOverrideStore 의 폴백
-- (regime:coin@tf → regime:coin → regime@tf → regime → 코드 기본값) 에서 그대로 2·4 단계로
-- 쓰이므로, 타임프레임별 표본이 최소치에 못 미치는 동안에는 종전 동작이 유지된다.

ALTER TABLE weight_optimizer_snapshot
    ADD COLUMN IF NOT EXISTS timeframe VARCHAR(10);

COMMENT ON COLUMN weight_optimizer_snapshot.timeframe IS
    '가중치를 산출한 세션의 타임프레임(H1/M15 등). NULL = 타임프레임 무관 가중치 '
    '(2026-09-08 이전 행 또는 표본 부족으로 레짐 레벨만 산출된 경우).';

-- 복원 쿼리(findLatestPerKey)가 (regime, coin_pair, timeframe, strategy_name) 별 최신 행을 찾는다.
CREATE INDEX IF NOT EXISTS idx_weight_snapshot_key
    ON weight_optimizer_snapshot (regime, coin_pair, timeframe, strategy_name, created_at DESC);
