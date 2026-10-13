-- 취소 확인과 정지 후 정리 (2026-10-13)
--
-- ■ 고치는 문제 1 — 취소 요청이 성공하면 곧바로 CANCELLED 로 기록했다
--
-- `cancelOrder` 는 거래소 취소 요청이 예외 없이 돌아오면 체결량을 보지 않고 CANCELLED 로 전이했다.
-- 업비트 취소는 접수일 뿐이고, 그 사이 시장가 매수가 일부 또는 전부 체결됐을 수 있다. 고아 정리는
-- CANCELLED + 수량 0 포지션을 보고 **예약 전액을 복원**하므로, 코인을 샀는데 돈도 돌아오는 경로가
-- 열려 있었다. 원자 경로 밖에서 CANCELLED 가 된 매수는 체결 여부를 확인한 적이 없다.
--
-- 이제 CANCELLED 는 **거래소가 체결량을 명시적으로 0 으로 보고한 취소**만 뜻한다
-- (`cancel_confirmed_at`). 체결이 있으면 원자 체결 회계로 보내고, 체결량을 모르면 활성으로 남긴다.
-- 전액 복원은 확인된 취소에만 한다. V87 이전 CANCELLED 중 정산 경로에 참여한 매수(fee_source 있음)는
-- 확인 근거가 없으므로 포지션이 아직 열려 있으면 재조회로 확인한다.
--
--   cancel_requested_at  취소를 요청했다(요청은 확정이 아니다). 재요청 간격의 기준.
--   cancel_confirmed_at  거래소가 체결량 0 으로 취소를 확인했다. 전액 복원의 근거.
--   cancel_checked_at    확인되지 않은 CANCELLED 를 마지막으로 재조회한 시각(재조회 간격).
--
-- ■ 고치는 문제 2 — 정지 후 늦은 체결로 생긴 포지션을 맡는 곳이 없었다
--
-- 정지는 그 시점에 열린 포지션만 청산한다. 그 뒤 진행 중이던 매수가 체결되면 수량이 생긴 포지션이
-- 정지된 세션에 남고, 평가 루프도 정지 청산도 다시 보지 않는다. 정지 청산의 매도가 실패해 OPEN 으로
-- 되돌아간 포지션도 같다.
--
-- `position_cleanup` 은 정지가 남긴 포지션의 **정리 대기**다. 포지션 행에 열을 두지 않은 이유는
-- 포지션을 전 열로 저장하는 경로가 여럿이라, 그 저장이 대기 표시를 이전 값으로 되돌릴 수 있어서다.
-- 별도 행은 그 저장과 겹치지 않는다. `done_at` 은 포지션이 CLOSED 로 확인될 때만 쓴다 — 매도를
-- 제출했다는 사실로 완료 처리하지 않는다.

ALTER TABLE "order"
    ADD COLUMN IF NOT EXISTS cancel_requested_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancel_confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancel_checked_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sell_applied_at     TIMESTAMPTZ;

COMMENT ON COLUMN "order".cancel_requested_at IS
    '취소를 요청한 시각. 요청은 확정이 아니다 — 상태는 거래소 확인 전까지 활성으로 남는다.';
COMMENT ON COLUMN "order".cancel_confirmed_at IS
    '거래소가 체결량 0 으로 취소를 확인한 시각. CANCELLED 매수의 예약 전액 복원은 이 값이 있을 때만 한다(fee_source 가 NULL 인 V82 이전 주문은 종전 규칙).';
COMMENT ON COLUMN "order".sell_applied_at IS
    '체결된 매도를 포지션·잔액에 반영한 시각. 부분 체결 후 남은 수량을 다시 팔 때, 이미 반영한 매도를 청산 확정이 다시 집지 않게 한다(NULL = 미반영 또는 V87 이전).';
COMMENT ON COLUMN "order".cancel_checked_at IS
    '확인되지 않은 CANCELLED 매수를 마지막으로 재조회한 시각.';

CREATE TABLE IF NOT EXISTS position_cleanup (
    position_id     BIGINT       PRIMARY KEY REFERENCES position(id),
    session_kind    VARCHAR(10)  NOT NULL,
    session_id      BIGINT       NOT NULL,
    reason          VARCHAR(40)  NOT NULL,
    pending_at      TIMESTAMPTZ  NOT NULL,
    done_at         TIMESTAMPTZ,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    last_note       VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_position_cleanup_open
    ON position_cleanup (session_kind, session_id) WHERE done_at IS NULL;

COMMENT ON TABLE position_cleanup IS
    '정지가 남긴 포지션의 정리 대기. 정지 시점에 열려 있던 포지션과 정지 후 늦은 체결이 반영된 포지션을 담는다. done_at 은 포지션 CLOSED 확인 시에만 쓴다.';
