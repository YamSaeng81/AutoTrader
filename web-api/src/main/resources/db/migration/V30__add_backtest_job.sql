-- 백테스트 비동기 작업 상태 관리 테이블
-- 대용량 캔들 데이터(수십만 건) 백그라운드 처리 이력 보존 및 텔레그램 알림용

CREATE TABLE backtest_job (
    id               BIGSERIAL       PRIMARY KEY,
    job_type         VARCHAR(20)     NOT NULL,                      -- SINGLE / BULK / MULTI_STRATEGY
    status           VARCHAR(20)     NOT NULL DEFAULT 'PENDING',    -- PENDING / RUNNING / COMPLETED / FAILED
    coin_pair        VARCHAR(20),
    strategy_name    VARCHAR(200),
    timeframe        VARCHAR(10),
    request_json     TEXT,                                          -- 요청 파라미터 직렬화 (디버깅용)
    total_candles    INTEGER,                                       -- 처리 대상 총 캔들 수
    total_chunks     INTEGER,                                       -- 100,000건 단위 분할 청크 수
    completed_chunks INTEGER         NOT NULL DEFAULT 0,           -- 진행률 추적
    backtest_run_id  BIGINT,                                        -- 완료 후 연결된 backtest_run.id
    error_message    TEXT,                                          -- FAILED 시 오류 메시지
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_backtest_job_status     ON backtest_job (status);
CREATE INDEX idx_backtest_job_created_at ON backtest_job (created_at DESC);
