-- 동적 멀티코인 세션에 REAL/PAPER 구분을 추가한다.
--
-- 배경(2026-08-06): 운영 주력은 동적(DYNAMIC) 7세션인데, 전날 정렬한 페이퍼(PaperTradingService)는
-- LIVE(단일코인) 기준이라 동적 엔진은 페이퍼로 전혀 검증할 수 없었다. 별도 서비스를 만들지 않고
-- DynamicTradingService 자체에 모드를 추가한다 — 전략 평가·게이트 5종·SL/TP·time stop·워치리스트
-- 스캔은 100% 공유하고, 체결(주문 제출)만 REAL(실거래소)/PAPER(슬리피지 시뮬레이션)로 분기한다.
--
-- session_kind 로 실거래와 완전히 분리한다 — position/"order" 의 session_kind='DYNAMIC'을
-- 그대로 두고 PAPER는 'DYN_PAPER'(컬럼 길이 10자 제약)를 쓰므로, 기존 reconcile 4종
-- (ghost position/orphan buy/balance/closing)이 자동으로 PAPER를 무시한다 — 07-31/08-03 P0가
-- 났던 그 경로를 페이퍼가 절대 타지 않는다.

ALTER TABLE dynamic_session
    ADD COLUMN trading_mode VARCHAR(10) NOT NULL DEFAULT 'REAL';

COMMENT ON COLUMN dynamic_session.trading_mode IS
    'REAL(실거래) | PAPER(모의). PAPER는 전략/게이트/SL·TP/time stop을 REAL과 100% 공유하되, '
    '체결은 OrderExecutionEngine(실거래소)을 거치지 않고 슬리피지·수수료를 반영해 동기적으로 '
    '시뮬레이션한다. position/order.session_kind는 REAL="DYNAMIC", PAPER="DYN_PAPER"로 분리되어 '
    '기존 실거래 reconcile 스케줄러 4종이 PAPER 데이터를 건드리지 않는다.';
