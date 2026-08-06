CREATE TABLE daily_health_snapshot (
    id                          BIGSERIAL PRIMARY KEY,
    checked_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    balance_mismatch_count      INT NOT NULL DEFAULT 0,
    balance_mismatch_detail     JSONB,
    order_sequence_gap          INT NOT NULL DEFAULT 0,
    sequence_gap_checked        BOOLEAN NOT NULL DEFAULT TRUE,
    ghost_position_count        INT NOT NULL DEFAULT 0,
    ghost_position_detail       JSONB,
    stuck_position_count        INT NOT NULL DEFAULT 0,
    stuck_position_detail       JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE daily_health_snapshot IS
    '운영자가 매번 psycopg2로 수동 조회하던 4대 건전성 점검(세션 잔고 정합성 / 주문 시퀀스 갭 / '
    '유령 포지션 / time stop 없이 장기 고착된 포지션) 결과 이력. OperationalHealthCheckService가 '
    '매일 자동 기록하고, 이상 발견 시 Discord로 즉시 알림한다. sequence_gap_checked=false면 '
    '시퀀스 갭 조회 자체가 실패한 것(예: 운영 DB가 아닌 환경)이며 order_sequence_gap=0(정상)과 구분한다.';

COMMENT ON COLUMN daily_health_snapshot.balance_mismatch_detail IS
    '[{sessionKind, sessionId, availableKrw, totalAssetKrw}] — 포지션·활성주문 없이 available≠total인 세션';
COMMENT ON COLUMN daily_health_snapshot.ghost_position_detail IS
    '[{sessionKind, positionId, sessionId, coinPair}] — 매도 FILLED인데 OPEN으로 남은 포지션';
COMMENT ON COLUMN daily_health_snapshot.stuck_position_detail IS
    '[{sessionKind, positionId, sessionId, coinPair, heldHours}] — time stop 비활성(0 이하) + 24시간 이상 보유';
