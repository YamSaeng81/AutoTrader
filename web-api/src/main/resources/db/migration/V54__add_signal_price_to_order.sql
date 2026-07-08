-- V54: 주문에 신호 시점 가격(signal_price) 보존 + 잘못 측정된 drift 레코드 정리
--
-- 배경 (2026-07-08): ExecutionDriftTracker의 SELL drift 기록이 signalPrice로 "매수 평균단가"를
-- 사용해 slippage가 실은 포지션 손익률 전체로 기록됐다 (예: 세션 186 BTC 손절 -0.8525%가
-- 슬리피지로 기록 → DriftAlert가 7일간 매시간 반복 발송). 체결 확정 시점에는 매도 트리거
-- 가격이 남아 있지 않으므로(order.price는 체결가로 덮임) 주문 생성 시점에 보존한다.

-- 신호/트리거 시점 가격 — 주문 생성 시 설정, 체결 후 drift 측정 기준가로 사용
ALTER TABLE "order" ADD COLUMN signal_price NUMERIC(20, 8);

COMMENT ON COLUMN "order".signal_price IS '신호/트리거 시점 가격 (drift 측정 기준가, 주문 생성 시 설정)';

-- 기존 SELL drift 레코드는 전부 매수 평균단가 기준으로 잘못 측정된 값이라 삭제한다.
-- (남겨두면 수정 배포 후에도 7일 롤링 평균에 계속 포함되어 오탐 알림이 지속됨)
DELETE FROM execution_drift_log WHERE side = 'SELL';
