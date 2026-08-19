-- 동적 세션 매도 정산 멱등 표식 (2026-08-19 P0)
--
-- 문제: finalizeDynamicSell 은 매도대금을 DynamicSessionBalanceUpdater(REQUIRES_NEW)로
--   **선커밋**하는데, 포지션을 CLOSED 로 바꾸는 일은 바깥 트랜잭션에 있다. 바깥이 롤백되면
--   포지션은 OPEN 으로 되돌아오고 대금만 남는다. 다음 시도에서 또 지급된다 —
--   운영 세션 49 는 이 루프로 available_krw 가 10,000 → 174,752 까지 불어났다.
--
-- 해법: "이 매도는 이미 정산했다" 는 표식을 **대금 반영과 같은 트랜잭션**에 쓴다.
--   둘이 함께 커밋되므로 바깥이 롤백돼도 표식이 남고, 재시도는 대금을 건너뛴다.
--
-- 키는 주문의 exchange_order_id 다. 페이퍼는 "PAPER-DYNAMIC-SELL-{positionId}" 로
--   포지션에서 결정되므로 롤백 후 재시도해도 **같은 값**이 나온다 (주문 행 자체는 롤백에
--   휩쓸려 사라지므로 order.id 를 키로 쓰면 재시도마다 새 키가 생겨 막지 못한다).
--   실거래는 거래소 주문 ID 라 부분체결 건마다 달라 각각 한 번씩 정산된다.
CREATE TABLE IF NOT EXISTS dynamic_sell_settlement (
    order_ref     VARCHAR(120)  PRIMARY KEY,
    position_id   BIGINT        NOT NULL,
    session_id    BIGINT        NOT NULL,
    session_kind  VARCHAR(20)   NOT NULL,
    sold_qty      DECIMAL(30,8) NOT NULL,
    net_proceeds  DECIMAL(20,8) NOT NULL,
    realized_pnl  DECIMAL(20,8) NOT NULL,
    settled_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dyn_sell_settlement_position
    ON dynamic_sell_settlement (position_id);
CREATE INDEX IF NOT EXISTS idx_dyn_sell_settlement_session
    ON dynamic_sell_settlement (session_id, settled_at DESC);
