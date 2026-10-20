-- 매도 격리 해제 기록 (2026-10-19, LIVE_RISK_IMPLEMENTATION_REVIEW.md §6.8-③ · §6.11-⑥ D)
--
-- 격리: 열린 실자금 포지션에 "체결됐지만 반영 표시(sell_applied_at)가 없는 매도" 가 있으면 새 매도(손절 포함)를 내지 않는다.
-- V87 이전(반영 표시가 없던 시절)의 매도는 반영됐는지 판별할 수 없어서 막는다. 청산 확정은 반영 표시가 없는 체결 매도를
-- "아직 반영 안 됨" 으로 보고 대금을 넣으므로, 이미 반영된 매도를 표시 없이 두면 대금이 두 번 들어갈 수 있다.
--
-- 해제는 "이 매도는 이미 포지션·잔액에 반영돼 있다" 는 사람의 판정을 반영 표시로 남기는 것이다 — 잔액·포지션 수량은 바꾸지 않는다.
-- 반영되지 않은 매도(포지션 수량에 빠지지 않은 매도)는 해제 대상이 아니다. 주문당 한 번.
CREATE TABLE sell_quarantine_release (
    order_id                BIGINT          PRIMARY KEY,
    position_id             BIGINT          NOT NULL,
    session_kind            VARCHAR(20)     NOT NULL,
    session_id              BIGINT          NOT NULL,
    order_state_at          VARCHAR(20)     NOT NULL,
    order_filled_qty        NUMERIC(30, 8)  NOT NULL,
    position_status_at      VARCHAR(20)     NOT NULL,
    position_size_at        NUMERIC(30, 8)  NOT NULL,
    buy_filled_qty          NUMERIC(30, 8)  NOT NULL,
    sell_filled_qty         NUMERIC(30, 8)  NOT NULL,
    released_by             VARCHAR(100)    NOT NULL,
    reason                  TEXT            NOT NULL,
    evidence                TEXT            NOT NULL,
    released_at             TIMESTAMPTZ     NOT NULL
);
CREATE INDEX idx_sell_quarantine_release_position ON sell_quarantine_release (position_id);
