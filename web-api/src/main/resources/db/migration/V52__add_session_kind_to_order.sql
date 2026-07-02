-- "order".session_id 는 live_trading_session 과 dynamic_session 양쪽에서 재사용되는
-- BIGSERIAL 이라 값이 겹친다 (V51의 position.session_kind 와 동일한 문제). 이 컬럼 없이는
-- OrderExecutionEngine의 부분체결 미사용 KRW 복원과 중복 주문 체크가 동적 세션 주문을
-- 라이브 세션 소속으로 오인해 엉뚱한 세션의 잔고를 건드릴 수 있었다 (2026-07-02 감사).
ALTER TABLE "order" ADD COLUMN session_kind VARCHAR(10) NOT NULL DEFAULT 'LIVE';

-- 과거 주문은 연결된 포지션의 session_kind 로 역산해 백필 (position_id 없는 비세션 주문은 LIVE 기본 유지)
UPDATE "order" o
SET session_kind = p.session_kind
FROM position p
WHERE o.position_id = p.id AND p.session_kind IS NOT NULL;

CREATE INDEX idx_order_session_kind_session_id ON "order" (session_kind, session_id);
