-- 포지션별 한도 (2026-10-15, 선행 조건 1번 1-g)
--
-- ■ 고치는 문제 — A군 자본 보호 기준의 분모가 등록 사양과 다르다
--
-- `LIVE_ENTRY_PREREG.md` §6 은 공유 풀에서 `CAPITAL_LOSS`(≤ −15%)의 분모를 **그 포지션의 진입 배정액**, `MAX_DRAWDOWN`(≤ −20%)의
-- 분모를 **그 포지션의 고점 평가액**으로 정했다. 구현(`StrategyKillCriteriaService`)은 세션 초기자본·세션 고점 기준이고 하루 한 번 본다.
--
-- 판정은 `PositionLimitGuard` 가 주문 반영값·시세로 한다. 여기에 두는 것은 판정이 재시작·정산·부분 청산을 넘어 유지해야 하는 세 값이다.
--
--   position.entry_allocation_krw  진입 배정액 — 매수 예약 때 한 번 쓰고 바꾸지 않는다. `invested_krw` 는 체결 정산이 실제 사용액으로
--                                  덮어쓰므로(추가 체결·수수료 정정) 분모로 쓸 수 없다.
--   position.peak_value_krw        포지션 고점 평가액 — 판정 작업만 조건부 UPDATE(더 클 때만)로 올린다. 엔티티 저장은 이 열을 쓰지 않는다.
--   position_limit_breach          한도 도달 기록 — 포지션당 한 행. 그 세션의 신규 진입 차단 근거이며 재시작 뒤에도 남는다.
--
-- 🔴 기존 행의 의미를 바꾸지 않는다. 열린 실자금 포지션에만 배정액을 채운다(첫 시장가 매수의 요청액, 없으면 투입금).
--    페이퍼(독립 자본, 검증 66세션)는 대상이 아니다.

ALTER TABLE position ADD COLUMN IF NOT EXISTS entry_allocation_krw NUMERIC(20,8);
ALTER TABLE position ADD COLUMN IF NOT EXISTS peak_value_krw       NUMERIC(24,8);
ALTER TABLE position ADD COLUMN IF NOT EXISTS peak_value_at        TIMESTAMPTZ;

COMMENT ON COLUMN position.entry_allocation_krw IS
    '진입 배정액 — CAPITAL_LOSS 의 분모. 매수 예약 때 한 번 쓰고 바꾸지 않는다 (V90)';
COMMENT ON COLUMN position.peak_value_krw IS
    '포지션 고점 평가액(매도 순대금 + 잔량 × 시세 × (1 − 수수료율)) — MAX_DRAWDOWN 의 분모. 더 클 때만 갱신 (V90)';

UPDATE position p
SET entry_allocation_krw = COALESCE(
        (SELECT o.quantity FROM "order" o
         WHERE o.position_id = p.id AND o.side = 'BUY' AND o.order_type = 'MARKET'
         ORDER BY o.id LIMIT 1),
        p.invested_krw)
WHERE p.session_kind IN ('LIVE', 'DYNAMIC')
  AND p.status IN ('OPEN', 'CLOSING')
  AND p.entry_allocation_krw IS NULL;

CREATE TABLE IF NOT EXISTS position_limit_breach (
    position_id     BIGINT        PRIMARY KEY,   -- 외래 키 없음: 실전매매 초기화(DbResetService)가 포지션 행을 지운다
    session_kind    VARCHAR(10)   NOT NULL,
    session_id      BIGINT        NOT NULL,
    code            VARCHAR(20)   NOT NULL,
    value_krw       NUMERIC(24,8) NOT NULL,
    cost_krw        NUMERIC(24,8) NOT NULL,
    allocation_krw  NUMERIC(24,8),
    peak_krw        NUMERIC(24,8),
    detail          TEXT          NOT NULL,
    breached_at     TIMESTAMPTZ   NOT NULL,
    stop_action     VARCHAR(20),
    stop_at         TIMESTAMPTZ,
    stop_note       TEXT
);

CREATE INDEX IF NOT EXISTS idx_position_limit_breach_session ON position_limit_breach (session_kind, session_id);

COMMENT ON TABLE position_limit_breach IS
    '포지션별 한도(CAPITAL_LOSS·MAX_DRAWDOWN) 도달 기록 — 그 세션의 실자금 신규 진입 차단 근거 (V90)';
