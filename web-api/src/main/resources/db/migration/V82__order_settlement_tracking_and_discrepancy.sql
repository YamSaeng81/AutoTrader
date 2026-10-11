-- 정산 추적 정책과 불일치 보존 (2026-10-12)
--
-- V81 은 "보고 원본 vs 회계 반영값" 을 분리했다. 여기서는 그 위에 두 가지를 더한다:
-- ① 어떤 주문이 **새 회계 경로에 참여하는지**, ② 그 주문을 **언제 다시 조회할지**.
--
-- ══ ① 신규 주문과 기존 주문의 경계 ══════════════════════════════════════════
--
-- 🔴 V81 의 컬럼은 모두 NULL 로 시작한다. 그래서 **신규 주문도 그대로 두면 "전환 이전 데이터" 와
--    구분되지 않는다.** 전환 규칙이 "fee_source IS NULL 이면 손대지 않는다" 이므로, 신규 주문이
--    NULL 이면 새 회계 경로에서 영원히 빠진다.
--
-- 그래서 fee_source 에 값을 하나 더 둔다:
--
--   NULL        전환 이전 데이터. 🔴 자동 재정산에서 제외한다. 과거의 계산 수수료를 실제
--               수수료였던 것처럼 재해석하지 않는다.
--   'PENDING'   새 회계 경로에 참여한다. 아직 아무것도 반영하지 않았다. **주문 생성 시 명시한다.**
--   'ESTIMATED' 추정값을 임시 반영했다. 실제값이 오면 차액만 정산하고 REPORTED 로 올린다.
--   'REPORTED'  거래소 보고값을 반영했다.
--
-- 🔴 **아직 미체결인 기존 주문의 전환은 이 마이그레이션이 하지 않는다.** 그 주문들은 NULL 로
--    남으므로 새 경로에 참여하지 않고, 체결이 와도 자동 재정산되지 않는다. 그 전환(어느 시점의
--    미체결 주문을 PENDING 으로 올릴지, 이미 일부 반영된 금액을 어떻게 볼지)은 **별도 작업**이며
--    여기서 섞으면 과거 데이터를 재해석하는 셈이 된다.
--
-- ══ ② 재조회 대상은 '보고값 ≠ 반영값' 보다 넓다 ═════════════════════════════
--
-- 🔴 V81 에서는 (state, fee_source) 인덱스로 "보고값과 반영값이 다른 주문" 을 찾으려 했다.
--    그것으로는 부족하다 — **보고값과 반영값이 같거나 둘 다 NULL 이어도 나중에 체결금액·수수료가
--    도착할 수 있다**(시장가 매도의 trades 가 뒤늦게 채워지는 사례). 즉 "지금 다르다" 가 아니라
--    **"정산이 아직 확정되지 않았다"** 가 대상 조건이다.
--
-- 그래서 세 컬럼으로 정책을 표현한다:
--
--   settlement_final        정산 확정 여부. false 인 동안만 재조회 대상이다.
--   settlement_next_check_at 다음 조회 시각(TIMESTAMPTZ — 2026-09-08 규약). 백오프를 이 값으로 표현한다 — 조회 실패나 변화 없음이
--                           반복되면 뒤로 밀어 레이트리밋을 피한다(5초 폴러를 그대로 때리지 않는다).
--   settlement_attempts     조회 횟수. 백오프 계산과 **조회 종료 조건**에 쓴다.
--
-- 🔴 조회 종료 조건은 코드에서 정한다. 횟수만으로 종료하면 "모르는데 끝났다" 가 되므로,
--    종료는 ⓐ 거래소 응답으로 확정되거나 ⓑ 사람이 판정해 불일치로 보존할 때만 성립한다.
--    settlement_attempts 는 그 판정을 **사람에게 올리는 기준**이지 자동 종결 근거가 아니다.
--
-- ══ ③ 감소 응답의 불일치 보존 ═══════════════════════════════════════════════
--
-- 🔴 실측값끼리 감소하는 응답(이전 보고값보다 작은 수수료·체결금액)은 자동 반영하지 않고, 오래된
--    응답으로 단정해 폐기하지도 않는다. **덮어쓰기 전에 이전 값과 수신값을 함께 보존**해 사람이
--    확인한다. 그래서 전용 테이블을 둔다 — 컬럼으로는 "여러 번 어긋난 이력" 을 담을 수 없다.

ALTER TABLE "order"
    ADD COLUMN IF NOT EXISTS settlement_final         BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS settlement_next_check_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS settlement_attempts      INTEGER   NOT NULL DEFAULT 0;

COMMENT ON COLUMN "order".settlement_final IS
    '정산 확정 여부. FALSE 인 동안만 재조회 대상이다. 횟수 소진으로 TRUE 가 되지 않는다.';
COMMENT ON COLUMN "order".settlement_next_check_at IS
    '다음 정산 조회 시각(백오프). NULL 이면 즉시 대상.';
COMMENT ON COLUMN "order".settlement_attempts IS
    '정산 조회 시도 횟수. 백오프 계산과 사람 판정 상향 기준이며 자동 종결 근거가 아니다.';

-- 🔴 V81 의 인덱스는 "보고값 ≠ 반영값" 전제였다. 대상 조건이 '정산 미확정' 으로 바뀌었으므로
--    인덱스도 그 정책을 지원하도록 바꾼다. 인덱스는 정책을 정하지 않고 **정책을 받친다.**
DROP INDEX IF EXISTS idx_order_settlement_pending;

CREATE INDEX IF NOT EXISTS idx_order_settlement_due
    ON "order" (settlement_next_check_at)
    WHERE settlement_final = FALSE AND fee_source IS NOT NULL;

-- 정산 불일치 이력 — 덮어쓰기 전에 이전 값과 수신값을 함께 남긴다.
CREATE TABLE IF NOT EXISTS order_settlement_discrepancy (
    id                  BIGSERIAL       PRIMARY KEY,
    order_id            BIGINT          NOT NULL,
    detected_at         TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 어떤 종류의 불일치인가: 'FEE_DECREASE' | 'FUNDS_DECREASE' | 'VOLUME_DECREASE' | 'STATE_REGRESS'
    kind                VARCHAR(20)     NOT NULL,
    -- 덮어쓰기 전의 값과 수신한 값. 둘 다 남겨야 사람이 판단할 수 있다.
    previous_value      NUMERIC(20, 8),
    received_value      NUMERIC(20, 8),
    previous_state      VARCHAR(20),
    received_state      VARCHAR(20),
    -- 사람이 확인했는가. 확인 전까지 미해결로 남는다.
    resolved_at         TIMESTAMPTZ,
    resolved_by         VARCHAR(100),
    resolution_note     TEXT
);

COMMENT ON TABLE order_settlement_discrepancy IS
    '정산 불일치 이력. 감소 응답 등을 자동 반영하거나 폐기하지 않고 이전 값과 수신값을 함께 보존한다.';

CREATE INDEX IF NOT EXISTS idx_settlement_discrepancy_unresolved
    ON order_settlement_discrepancy (order_id)
    WHERE resolved_at IS NULL;
