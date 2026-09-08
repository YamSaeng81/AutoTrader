-- strategy_log 에 timeframe 추가 (2026-09-08)
--
-- ■ 왜 필요한가 — 신호 분석이 H1 과 M15 를 통째로 섞고 있었다
--
-- strategy_log 에는 timeframe 이 없어서, 이 테이블을 집계하는 모든 경로가
-- (전략, 코인) 으로만 그룹핑한다. 그런데 운영은 두 타임프레임을 동시에 돌린다 —
-- 08-01 이후 신호 로그를 세션 조인으로 갈라 보면 전 전략이 양쪽에 걸쳐 있고,
-- M15 가 3~5배 많아 사실상 M15 통계에 H1 이 잡음으로 섞이는 구조였다:
--
--   전략                          H1      M15
--   COMPOSITE_MTF_CONFIRMED     7,833   41,826
--   COMPOSITE_MTF_BTC          10,259   39,849
--   COMPOSITE_MOMENTUM_ICHIMOKU 7,182   39,114
--
-- ■ 섞으면 결론이 실제로 뒤집힌다 (BUY 신호 사후 4h 수익률, 08-01~)
--
--   전략                        H1        M15      합산(현재)
--   COMPOSITE_MTF_BTC         -1.428    +0.046    -0.319   ← H1/M15 부호가 반대
--   COMPOSITE_MTF_BTC_STRICT  -0.509    +0.518    +0.266   ← 합산은 양수, H1 은 음수
--   COMPOSITE_MOMENTUM_ICHIMOKU_V2 -0.367 +0.142  +0.052   ← 같은 패턴
--
-- MTF_BTC 는 H1 −1.43% / M15 +0.05% 로 방향이 반대인데, 합산 −0.32% 하나로 판정돼 왔다.
-- 09-04 "전략 검토" · 09-07 "전략 순위가 통제하면 무너진다" 분석 모두 코인·시각은 통제했지만
-- **타임프레임은 통제한 적이 없다.**
--
-- ■ 같은 결함이 이미 두 번 나왔다
--
--   08-24  WF 게이트를 전략 → 전략×코인 으로 좁히면서 타임프레임 축을 놓침 (09-08 수정)
--   09-08  strategy_log 소비자 전체가 타임프레임 없이 집계 (이 마이그레이션)
--
-- ■ 소급 백필은 하지 않는다
--
-- session_id 로 세션 테이블을 조인하면 과거 행의 타임프레임을 복원할 수 있지만,
-- 세 종류(dynamic_session / paper_trading.virtual_balance / live_trading_session)를
-- session_type 에 따라 갈라 조인해야 하고 삭제된 세션은 복원되지 않는다.
-- 신규 행부터 채우고, 과거 분석이 필요하면 아래 조인을 쓴다:
--
--   coalesce(d.timeframe, v.timeframe, ls.timeframe)
--     from strategy_log l
--     left join dynamic_session d              on l.session_type in ('DYN_PAPER','DYNAMIC') and d.id = l.session_id
--     left join paper_trading.virtual_balance v on l.session_type = 'PAPER' and v.id = l.session_id
--     left join live_trading_session ls        on l.session_type = 'LIVE'  and ls.id = l.session_id
--
-- NULL 은 "이 마이그레이션 이전 행" 을 뜻한다 — 집계 시 NULL 을 한 그룹으로 묶지 말 것.

ALTER TABLE strategy_log
    ADD COLUMN IF NOT EXISTS timeframe VARCHAR(10);

COMMENT ON COLUMN strategy_log.timeframe IS
    '신호를 낸 세션의 타임프레임(H1/M15 등). NULL = 2026-09-08 이전 행 — 집계 시 별도 그룹으로 둘 것.';

-- (전략, 코인, 타임프레임) 집계가 기본 경로다 — LogController.buildByStrategy,
-- StrategyDegradationWatchdog.groupByKey 가 이 순서로 훑는다.
CREATE INDEX IF NOT EXISTS idx_strategy_log_strategy_coin_tf
    ON strategy_log (strategy_name, coin_pair, timeframe, created_at);
