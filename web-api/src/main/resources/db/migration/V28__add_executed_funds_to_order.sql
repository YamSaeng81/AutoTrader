-- price-type 시장가 매수 부분체결 후 취소 시 실제 사용 KRW 추적
-- (order.quantity = 원래 투자 KRW, executed_funds = 실제 사용 KRW, 차액을 session.available_krw 복원에 사용)
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS executed_funds NUMERIC(20, 8);
