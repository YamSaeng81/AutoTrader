-- V64: 실전매매(LIVE) 세션에 최대 보유시간(time stop) 추가
--
-- 배경 (2026-08-06, LIVE/DYNAMIC 청산 엔진 통합 P1):
--   dynamic_session은 V62/V63으로 time stop(max_hold_hours)을 이미 갖고 있지만
--   live_trading_session에는 이 컬럼 자체가 없었다. LIVE는 가격 기반 SL/TP만 있고
--   시간 기반 탈출구가 없어, 세션 194의 BTC 포지션이 136시간(5.7일) 동안 청산되지
--   못하고 물려 있었다 — DYNAMIC 세션 38의 KRW-RLUSD 42시간 고착(V62 배경)과 동일한
--   구조적 원인이다.
--
-- 기본값 0(비활성): dynamic_session도 V63에서 기본값을 0으로 내린 뒤 유지 중이다
--   (V62 배포 직후 매도 후처리 롤백 P0가 터진 전례가 있어, 자동으로 전 세션에
--   노출시키지 않고 필요한 세션에만 명시적으로 켜는 정책). LIVE도 같은 정책을 따른다.

ALTER TABLE live_trading_session
    ADD COLUMN max_hold_hours INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN live_trading_session.max_hold_hours IS
    '최대 보유시간(시). 초과 시 손익과 무관하게 시장가 청산 — 저변동 종목 고착 방지. 0 이하면 비활성(기본값).';
