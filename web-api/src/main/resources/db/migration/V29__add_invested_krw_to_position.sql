-- 포지션 생성 시 차감된 KRW 금액 저장
-- 주문 엔티티가 생성되기 전 async 스레드 실패 시에도 KRW 복원 가능하도록 포지션에 직접 기록
ALTER TABLE position ADD COLUMN IF NOT EXISTS invested_krw NUMERIC(20, 8);
