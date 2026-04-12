-- LLM 호출 기록 테이블
-- 대화 전문, 토큰 사용량, 소요 시간 보존

CREATE TABLE llm_call_log (
    id                 BIGSERIAL PRIMARY KEY,
    task_name          VARCHAR(50)  NOT NULL,
    provider_name      VARCHAR(30)  NOT NULL,
    model_used         VARCHAR(100),
    system_prompt      TEXT,
    user_prompt        TEXT,
    response_content   TEXT,
    prompt_tokens      INTEGER      NOT NULL DEFAULT 0,
    completion_tokens  INTEGER      NOT NULL DEFAULT 0,
    success            BOOLEAN      NOT NULL DEFAULT FALSE,
    error_message      TEXT,
    duration_ms        BIGINT       NOT NULL DEFAULT 0,
    called_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_llm_call_log_task     ON llm_call_log (task_name);
CREATE INDEX idx_llm_call_log_called_at ON llm_call_log (called_at DESC);
