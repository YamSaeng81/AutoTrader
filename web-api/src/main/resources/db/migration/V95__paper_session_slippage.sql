-- 모의투자 세션별 체결 슬리피지 (2026-10-28, B_VALIDATION_PREREG.md §7 ⓐ — 리뷰 5차 로컬 개발 허용)
--
-- 새 후보 B 검증은 같은 T0 에서 MAIN(편도 0.1%)·STRESS(편도 0.2%) 두 팔을 각각 엔진으로 돌린다. 지금까지는 모든 PAPER 세션이
-- 상수 TradingConstants.PAPER_SLIPPAGE_PCT(0.001) 하나를 썼다.
--
-- 🔴 NULL 허용, 기본값 없음, 기존 행을 채우지 않는다. NULL 이면 코드가 종전 상수를 그대로 쓴다 — 기존 세션(전향 검증 522~587 포함)은
--    체결가가 종전과 같은 상수에서 나온다. 기존 행을 고치지 않으므로 운영 장부(66세션 행)에 쓰기도 생기지 않는다.
-- 단위: 비율(0.001 = 0.1%). 세션 생성 요청의 slippagePct(%) 를 100 으로 나눠 저장한다.
ALTER TABLE paper_trading.virtual_balance ADD COLUMN slippage_rate NUMERIC(7, 6);

ALTER TABLE paper_trading.virtual_balance ADD CONSTRAINT virtual_balance_slippage_rate_range
    CHECK (slippage_rate IS NULL OR (slippage_rate >= 0 AND slippage_rate <= 0.01));

COMMENT ON COLUMN paper_trading.virtual_balance.slippage_rate IS
    '편도 체결 슬리피지(비율, 0.001=0.1%). NULL 이면 TradingConstants.PAPER_SLIPPAGE_PCT — V95 이전 세션은 모두 NULL';
