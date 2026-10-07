-- 주문 정산: 거래소 보고 원본과 회계 반영값을 분리한다 (2026-10-07)
--
-- ■ 왜 필요한가 — 수수료·체결금액을 회계에 쓰려면 "보고값" 과 "이미 반영한 값" 이 따로 있어야 한다
--
-- 실측(`SettlementIdempotencyTest` 시험 19): 수량은 같고 체결금액·수수료만 늘어난 응답이 도착해도
-- 아무것도 반영되지 않는다. 원인이 두 겹인데 그중 하나가 **보존할 자리가 없다** 는 것이다:
--
--   · 응답의 executed_funds 는 order 에 컬럼이 있는데도 **정상 전량 체결에서는 채우지 않는다**
--     (OrderExecutionEngine 이 "부분 체결 후 취소" 분기에서만 set 한다).
--   · 수수료는 order 에 컬럼이 아예 없고, position.position_fee 에 남는 값은 거래소가 보고한
--     paid_fee 가 아니라 우리가 계산한 추정값(체결금액 × 0.0005)이다.
--
-- ■ 회계 기준 변경 (2026-10-07 승인)
--
-- LIVE·DYNAMIC 실거래 회계는 **거래소가 보고한 실제 수수료**를 쓴다. 자체 계산값은 추정·대조용으로
-- 남긴다. PAPER 의 고정 수수료 가정은 그대로 둔다(모의는 거래소 보고값이 없다).
--
-- ■ 이 마이그레이션이 지키는 경계
--
-- 1) 원본과 반영값 분리 — reported_* 는 거래소가 말한 값, applied_* 는 회계에 이미 반영한 누적값.
--    둘을 비교해 차분만 반영한다. 한 컬럼에 섞으면 재반영을 구분할 수 없다.
--
-- 2) 누락과 0 의 구분 — 🔴 **전부 NULL 로 둔다. 0 으로 초기화하지 않는다.**
--    수수료 미보고를 0원으로 처리하면 "수수료가 없었다" 가 되고, applied_* 를 0 으로 채우면
--    기존 주문의 이미 반영된 금액을 **다시 차감**할 수 있다. NULL = "아직 모른다" 다.
--
-- 3) 과거 데이터 보존 — 기존 행의 새 컬럼은 NULL 로 남는다. 과거의 계산 수수료를 실제 수수료였던
--    것처럼 재해석하지 않는다. fee_source 가 NULL 인 행은 **전환 이전 데이터**라는 뜻이며,
--    회계 재계산 대상이 아니다. 전환 규칙은 코드에서 "fee_source IS NULL 이면 손대지 않는다" 다.
--
-- 4) 추정값의 출처 표시 — fee_source 로 ESTIMATED(자체 계산) / REPORTED(거래소 보고) 를 구분한다.
--    추정값을 임시 반영한 뒤 실제값이 오면 **차액만** 정산하고 출처를 REPORTED 로 올린다.
--
-- 5) 동시 반영 제어 — settlement_version 으로 조건부 UPDATE 한다. 🔴 이것은 동시 쓰기만 막고
--    응답의 최신성은 판단하지 못한다. 최신성 규칙(누적 단조성·종결 역행 금지)은 코드에서 따로 건다.
--    기존 행은 0 으로 시작해도 안전하다 — 버전은 금액이 아니라 카운터다.

ALTER TABLE "order"
    -- 거래소가 보고한 원본. NULL = 보고받지 못했다(0 원이 아니다).
    ADD COLUMN IF NOT EXISTS reported_executed_funds NUMERIC(20, 8),
    ADD COLUMN IF NOT EXISTS reported_paid_fee       NUMERIC(20, 8),
    -- 회계에 이미 반영한 누적값. NULL = 아직 반영한 적이 없다.
    ADD COLUMN IF NOT EXISTS applied_executed_funds  NUMERIC(20, 8),
    ADD COLUMN IF NOT EXISTS applied_paid_fee        NUMERIC(20, 8),
    -- 'ESTIMATED' | 'REPORTED'. NULL = 전환 이전 데이터이거나 아직 반영 전.
    ADD COLUMN IF NOT EXISTS fee_source              VARCHAR(10),
    -- 동시 반영 제어용 카운터. 금액이 아니므로 0 으로 시작해도 무해하다.
    ADD COLUMN IF NOT EXISTS settlement_version      BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN "order".reported_executed_funds IS
    '거래소가 보고한 누적 체결금액. NULL 은 미보고이며 0 원이 아니다.';
COMMENT ON COLUMN "order".reported_paid_fee IS
    '거래소가 보고한 누적 수수료. NULL 은 미보고이며 0 원이 아니다.';
COMMENT ON COLUMN "order".applied_executed_funds IS
    '회계에 이미 반영한 누적 체결금액. 보고값과의 차분만 반영한다.';
COMMENT ON COLUMN "order".applied_paid_fee IS
    '회계에 이미 반영한 누적 수수료. 보고값과의 차분만 반영한다.';
COMMENT ON COLUMN "order".fee_source IS
    'ESTIMATED=자체 계산 추정값 / REPORTED=거래소 보고값. NULL=전환 이전 데이터(회계 재계산 대상 아님).';
COMMENT ON COLUMN "order".settlement_version IS
    '정산 반영의 동시 쓰기 제어 카운터. 응답의 최신성은 이 값으로 판단하지 않는다.';

-- 🔴 종결 주문의 후속 정보를 다시 조회할 대상을 찾기 위한 인덱스.
--    필드와 가드만 추가하고 종결 주문을 더 조회하지 않으면 시험 19 의 첫 번째 원인이 그대로 남는다.
--    "보고값과 반영값이 다른 주문" 을 찾는 질의가 그 재조회의 출발점이다.
CREATE INDEX IF NOT EXISTS idx_order_settlement_pending
    ON "order" (state, fee_source)
    WHERE fee_source IS NOT NULL;
