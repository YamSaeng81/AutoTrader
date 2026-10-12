-- 예약 경계의 직렬화 (2026-10-12)
--
-- ■ 고치는 문제 (시험 11 로 재현됨)
--
-- `LiveTradingService.executeSessionBuy` 의 §8 한도 확인은 이렇게 생겼다:
--
--     BigDecimal total = sessionRepository.sumAvailableKrwByStatusIn(ACTIVE);
--     if (total.subtract(investAmount).signum() < 0) return;      -- 차단
--     ... 포지션 생성 · 주문 제출 ...
--     balanceUpdater.apply(sessionId, s -> s.setAvailableKrw(... .subtract(investAmount)));
--
-- 🔴 **읽고 → 판단하고 → 쓴다. 그 사이에 아무 직렬화도 없다.** 게다가 차감은 주문 제출
--    **뒤에** 일어난다. 두 세션이 동시에 들어오면 같은 합계를 읽고 둘 다 통과한다.
--
--    실측(`ConcurrentEntryCapTest`): 상한 5,000 에 각자 3,000 → **승인 2건 / 예약 합계 6,000.**
--
-- 🔴 **거래소의 잔고 부족 거절을 안전장치로 가정할 수 없다.** 거래소 계좌에 여유 자금이 있으면
--    로컬 상한을 넘은 주문도 그대로 접수된다. 그리고 접수된 주문의 응답이 유실되면 그 초과분이
--    **불명 상태로 남는다** — 선행 조건 3번과 5번이 맞물리는 지점이다.
--
-- ■ 구조
--
-- 계좌 단위 잠금 행 하나를 **직렬화 지점**으로 둔다. 매수 예약은 그 잠금을 쥔 채
--
--   ① 한도 확인(활성 세션 가용 KRW 합산)
--   ② 자금 예약(세션 가용 KRW 차감)
--   ③ 슬롯 예약(포지션 행)
--   ④ 주문 의도 저장(INTENT_READY + 식별자)
--
-- 를 **한 트랜잭션에서** 끝낸다. 네 가지가 함께 커밋되거나 함께 롤백된다.
--
-- 🔴 왜 세션 행 잠금으로는 부족한가: 한도는 **세션 사이에 공유된 풀**이다. 각자 자기 세션 행만
--    잠그면 서로를 막지 못한다. 공유 자원에는 공유 잠금이 필요하다.
--
-- 🔴 이것이 거래소와의 원자성을 주는 것은 아니다. 거래소 접수와 DB 커밋은 여전히 원자적이지
--    않다 — 그 간극은 V84 의 식별자 추적이 담당한다. 여기서 없애는 것은 **로컬 초과 승인**이다.

CREATE TABLE IF NOT EXISTS account_capital_lock (
    id          SMALLINT     PRIMARY KEY,
    -- 사람이 이 행의 용도를 알 수 있게 둔다. 값 자체는 쓰이지 않는다.
    purpose     VARCHAR(100) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE account_capital_lock IS
    '매수 예약의 직렬화 지점. 행 하나를 FOR UPDATE 로 잠가 한도 확인·자금·슬롯·주문 의도를 원자적으로 만든다. 세션 행 잠금으로는 부족하다 — 한도는 세션 사이에 공유된 풀이다.';

INSERT INTO account_capital_lock (id, purpose)
VALUES (1, '매수 예약 직렬화 (한도 확인 + 자금·슬롯 예약 + 주문 의도)')
ON CONFLICT (id) DO NOTHING;

-- 🔴 **예약액을 주문에 명시한다.**
--
--    종전 복원 경로는 `order.quantity`(요청 KRW)를 복원액으로 썼다. 그런데 예약이 실제로
--    일어났는지는 그 값으로 알 수 없다 — 한도 거절로 차감된 적이 없는 주문을 복원하면
--    **없던 돈이 생긴다.** 차감한 금액을 그 자리에 적어 두면 복원은 그 값만 되돌린다.
ALTER TABLE "order"
    ADD COLUMN IF NOT EXISTS reserved_krw NUMERIC(20, 8);

COMMENT ON COLUMN "order".reserved_krw IS
    '이 주문을 위해 실제로 차감한 KRW. NULL 이면 차감된 적이 없다 — 복원 경로는 이 값만 되돌린다.';
