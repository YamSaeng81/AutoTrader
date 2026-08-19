-- V70 (2026-08-19) — 전략 폐기 판정 이력.
--
-- 배경: 08-19 09:00 첫 판정이 정상 발동했는데, 그 근거가 **Discord 메시지에만** 남았다.
-- discord_send_log.message_preview 는 102자에서 잘리므로 "PULLBACK_MTF@H1 이 언제 어떤
-- 수치로 걸렸는가" 를 나중에 조회할 방법이 없다. 폐기는 되돌리기 어려운 결정인데
-- 근거가 채팅에만 남는 것은 거버넌스로 성립하지 않는다 (docs/KILL_CRITERIA.md §5).
--
-- KEEP 은 저장하지 않는다 — 매일 116행이 쌓여 신호 대 잡음비만 떨어진다.
-- 조치가 필요한 판정(KILL/WARN)만 남긴다.
--
-- 타임스탬프는 TIMESTAMPTZ 를 쓴다. 이 DB 에는 naive UTC 컬럼이 18개(10테이블) 섞여 있어
-- 운영 조회가 조용히 틀린 답을 내는 사고가 반복됐다(flyway.installed_on, discord_send_log).
-- 신규 테이블은 예외 없이 TIMESTAMPTZ 로 통일한다.

CREATE TABLE IF NOT EXISTS kill_criteria_judgment (
    id                BIGSERIAL PRIMARY KEY,
    evaluated_at      TIMESTAMPTZ  NOT NULL,
    session_kind      VARCHAR(20)  NOT NULL,
    session_id        BIGINT       NOT NULL,
    strategy_type     VARCHAR(50),
    timeframe         VARCHAR(10),
    verdict           VARCHAR(10)  NOT NULL,
    code              VARCHAR(30)  NOT NULL,
    reason            TEXT         NOT NULL,
    trade_count       INTEGER      NOT NULL DEFAULT 0,
    return_pct        NUMERIC(10, 2),
    auto_stop_applied BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_kcj_evaluated_at ON kill_criteria_judgment (evaluated_at DESC);
CREATE INDEX IF NOT EXISTS idx_kcj_strategy     ON kill_criteria_judgment (strategy_type, timeframe);

COMMENT ON TABLE  kill_criteria_judgment IS
    '전략 폐기 기준 판정 이력 — KILL/WARN 만 저장 (V70, 2026-08-19). docs/KILL_CRITERIA.md';
COMMENT ON COLUMN kill_criteria_judgment.auto_stop_applied IS
    'kill-criteria.auto-stop 이 켜져 있어 실제로 세션을 정지시켰는지. false 면 경보만 나갔다.';
