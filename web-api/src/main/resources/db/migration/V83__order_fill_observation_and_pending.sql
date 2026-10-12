-- 체결 관측과 회계 반영 대기 (2026-10-12)
--
-- ■ 결정: 로컬 회계의 부분 커밋을 없앤다 (ⓑ)
--
-- 종전에는 같은 조회 안에서 ① 체결 반영(포지션·평균단가·수수료)이 먼저 커밋되고 ② 정산(주문
-- 반영값·잔액)이 따로 돌았다. ②가 실패하면 **"포지션은 바뀌었고 잔액 정산은 남은" 중간 상태**가
-- 남는다. 이제 주문 반영값·잔액·포지션을 **한 트랜잭션**으로 묶는다.
--
-- 🔴 그런데 거래소 체결과 DB 커밋 자체를 원자적으로 만들 수는 없다. 그 간극은 없앨 수 없으므로
--    **명시적으로 추적한다** — 거래소에서 관측한 체결 사실을 먼저 보존하고 "회계 반영 대기" 로
--    표시한 뒤, 별도 트랜잭션에서 회계를 반영한다. 반영이 실패해도 **재처리할 근거가 남는다.**
--
--   ① 거래소 응답을 저장하고 회계 반영 대기로 표시
--   ② 별도 트랜잭션에서 최신성·중복 검사 후 주문·잔액·포지션을 함께 반영
--   ③ 성공하면 대기를 해제하고, 실패하면 예약을 유지한 채 재처리
--
-- ■ 예약·진입 가드의 공백을 함께 메운다
--
-- 🔴 ⓑ에서 "평가 루프가 포지션 없음으로 보고 재진입" 하는 것은 원자성의 단점이 아니라 **가드의
--    공백**이다. 미반영 체결이 있는 주문도 자금·슬롯을 점유해야 하고 그 세션의 신규 매수를 막아야
--    한다. 자금은 제출 시 이미 차감돼 점유가 유지되지만, 슬롯은 포지션 수량이 0 이라 보이지 않는다
--    — 그래서 `fill_accounting_pending` 으로 신규 매수를 차단한다.
--    🔴 **기존 주문 추적과 위험 축소(청산·손절) 처리는 끊지 않는다.**

ALTER TABLE "order"
    -- 거래소에서 관측한 체결 사실. 회계 반영과 **독립적으로** 보존한다.
    ADD COLUMN IF NOT EXISTS reported_executed_volume NUMERIC(20, 8),
    ADD COLUMN IF NOT EXISTS reported_state           VARCHAR(20),
    ADD COLUMN IF NOT EXISTS reported_observed_at     TIMESTAMPTZ,
    -- 관측은 했고 회계 반영은 아직 못 한 상태. 🔴 이 주문의 세션은 신규 매수를 막는다.
    ADD COLUMN IF NOT EXISTS fill_accounting_pending  BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN "order".reported_executed_volume IS
    '거래소가 보고한 누적 체결수량. 회계 반영과 독립적으로 보존한다(재처리 근거).';
COMMENT ON COLUMN "order".reported_state IS
    '거래소가 보고한 상태 원문(wait/done/cancel). 로컬 state 와 구분한다.';
COMMENT ON COLUMN "order".reported_observed_at IS
    '그 관측을 저장한 시각.';
COMMENT ON COLUMN "order".fill_accounting_pending IS
    '체결을 관측했으나 회계를 반영하지 못한 상태. TRUE 인 주문이 있는 세션은 신규 매수를 막는다. 추적·위험 축소는 막지 않는다.';

-- 미반영 체결을 세션 단위로 빠르게 찾는다 — 신규 매수 가드가 매 제출마다 본다.
CREATE INDEX IF NOT EXISTS idx_order_fill_accounting_pending
    ON "order" (session_kind, session_id)
    WHERE fill_accounting_pending = TRUE;
