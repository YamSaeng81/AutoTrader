-- 장기 UNKNOWN 주문의 수동 조정 기록 (2026-10-15, 선행 조건 5-i · UNKNOWN_ORDER_STATE_DESIGN.md §1.1·§7-16)
--
-- 사람의 판단은 "거래소 거절" 의 증거가 아니다 — 주문 상태는 UNKNOWN 으로 두고(FAILED 로 바꾸지 않는다), 회계 조정만
-- 이 표에 근거·금액·책임자·시각·미해결 위험과 함께 남긴다. 주문당 한 번. 조정 뒤 거래소가 그 주문을 보고하면 자동 정산하지
-- 않고 그 관측을 여기에 덧붙인다(사람이 다시 본다).
CREATE TABLE order_manual_adjustment (
    order_id            BIGINT          PRIMARY KEY,
    session_kind        VARCHAR(20)     NOT NULL,
    session_id          BIGINT          NOT NULL,
    order_state_at      VARCHAR(20)     NOT NULL,
    adjusted_by         VARCHAR(100)    NOT NULL,
    reason              TEXT            NOT NULL,
    evidence            TEXT,
    released_krw        NUMERIC(20, 8)  NOT NULL,
    unresolved_risk     TEXT            NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL,
    later_observation   TEXT,
    later_observed_at   TIMESTAMPTZ
);
CREATE INDEX idx_order_manual_adjustment_session ON order_manual_adjustment (session_kind, session_id);
