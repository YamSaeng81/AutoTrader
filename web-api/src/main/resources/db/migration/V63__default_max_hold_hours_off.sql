-- V63: dynamic_session.max_hold_hours 컬럼 기본값 24 → 0 (time stop 기본 비활성)
--
-- 배경 (2026-07-31):
--   V62로 도입한 time stop이 배포 1분 만에 세션 38 KRW-RLUSD를 정확히 잡았으나,
--   매도 후처리 트랜잭션이 롤백되면서 "주문은 FILLED인데 포지션은 OPEN"인 유령 상태가
--   됐다. 그 결과 매 틱마다 매도가 재시도되며 이미 판 코인에 대해 업비트가 HTTP 400을
--   반환하는 실패 주문 루프가 발생했다(8611~8613, 69초 간격).
--
--   롤백 지점이 아직 규명되지 않았으므로(서버 로그 필요) 기본값을 0(비활성)으로 내려
--   신규 세션이 자동으로 이 경로에 노출되지 않게 한다. 운영 중인 세션은 이미 DB에서
--   0으로 조정 완료.
--
-- ⚠️ V62는 이미 적용됐으므로 절대 수정하지 않는다 (2026-07-27 V58 체크섬 사고 교훈).
--    기본값 변경은 이렇게 새 버전으로만 한다.
--
-- 되돌릴 때 (롤백 P0 수정 후):
--   1) DynamicSessionEntity.DEFAULT_MAX_HOLD_HOURS 를 24 로
--   2) 신규 마이그레이션으로 ALTER COLUMN max_hold_hours SET DEFAULT 24
--   저변동 종목 고착(RLUSD 42시간) 방어 자체는 여전히 필요한 기능이다.
--
-- 기존 행의 값은 건드리지 않는다 — 운영 중 세션의 설정을 마이그레이션이 덮어쓰면
-- 사용자가 의도적으로 넣은 값까지 사라진다.

ALTER TABLE dynamic_session
    ALTER COLUMN max_hold_hours SET DEFAULT 0;

COMMENT ON COLUMN dynamic_session.max_hold_hours IS
    '최대 보유시간(시). 초과 시 손익과 무관하게 시장가 청산 — 저변동 종목 고착 방지. 0 이하면 비활성(현재 기본값, 매도 후처리 롤백 P0 해결 전까지).';
