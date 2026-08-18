-- V68: max_hold_hours 컬럼 기본값 0 → 24 (time stop 기본 활성) — dynamic/live/paper 3종 동시
--
-- V63이 남긴 "되돌릴 때" 절차를 그대로 수행한다:
--   1) DynamicSessionEntity.DEFAULT_MAX_HOLD_HOURS 를 24 로  ✅ (이 커밋)
--   2) 신규 마이그레이션으로 ALTER COLUMN max_hold_hours SET DEFAULT 24  ✅ (이 파일)
--
-- 되돌리는 근거 (2026-08-18):
--   V63이 기본값을 0으로 내린 사유는 "매도 후처리 트랜잭션 롤백 P0(주문은 FILLED인데
--   포지션은 OPEN → 매 틱 매도 재시도 루프)의 롤백 지점 미규명" 이었다. 이 P0는
--   08-03에 해소됐고, 이후 reattachRolledBackPosition()·CLOSING 타임아웃 8분 분리(D-5)
--   ·부분체결 SELL 승격(D-3)까지 보강됐다. 운영 DB daily_health_snapshot 기준
--   ghost_position_count 가 08-07~08-18 11일 연속 0 이라 재발 징후도 없다.
--
--   반대로 끄고 있는 동안 V62가 예고한 고착이 정확히 재발했다 — LIVE 198 / DYNAMIC 48 /
--   DYN_PAPER 49 가 같은 KRW-XRP 를 259시간(10.8일) 보유했고, SL(-5%)·TP(+10%) 어느 쪽도
--   닿지 않아 세션당 자본의 80%(8,000원)가 잠겼다. 실자금만 16,000원.
--
-- 24시간인 이유:
--   동적 세션은 워치리스트를 순회하며 기회를 찾는 구조라, 하루가 지나도 방향이 나오지 않은
--   포지션은 자본 회전을 막는 기회비용이 손실보다 크다. 08-07~08-18 실측 청산 8건의
--   보유시간 중앙값은 16시간으로 정상 매매는 24시간에 걸리지 않는다(초과 2건은 66h/70h로
--   둘 다 손실 마감). V62가 처음 의도했던 값과도 같다.
--
-- ⚠️ V62/V63/V64/V66 은 이미 적용됐으므로 절대 수정하지 않는다 (2026-07-27 V58 체크섬 사고 교훈).
--
-- 기존 행의 값은 건드리지 않는다 — V63과 동일한 정책. 운영 중 세션의 설정을 마이그레이션이
--   덮어쓰면 사용자가 의도적으로 넣은 값(예: 동적 세션 40의 36시간)까지 사라진다.
--   현재 RUNNING 세션에 대한 소급 적용은 운영자가 별도 UPDATE 로 수행한다.

ALTER TABLE dynamic_session
    ALTER COLUMN max_hold_hours SET DEFAULT 24;

ALTER TABLE live_trading_session
    ALTER COLUMN max_hold_hours SET DEFAULT 24;

ALTER TABLE paper_trading.virtual_balance
    ALTER COLUMN max_hold_hours SET DEFAULT 24;

COMMENT ON COLUMN dynamic_session.max_hold_hours IS
    '최대 보유시간(시). 초과 시 손익과 무관하게 시장가 청산 — 저변동 종목 고착 방지. 기본 24, 0 이하면 비활성.';

COMMENT ON COLUMN live_trading_session.max_hold_hours IS
    '최대 보유시간(시). 초과 시 손익과 무관하게 시장가 청산 — 저변동 종목 고착 방지. 기본 24, 0 이하면 비활성.';

COMMENT ON COLUMN paper_trading.virtual_balance.max_hold_hours IS
    '최대 보유시간(시). NULL이면 애플리케이션이 LIVE 기본값(24)으로 폴백. 0 이하면 비활성.';
