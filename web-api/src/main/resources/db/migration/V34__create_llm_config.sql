-- V34: LLM 프로바이더 설정 및 작업별 라우팅 테이블

-- LLM 프로바이더 설정
CREATE TABLE IF NOT EXISTS llm_provider_config (
    id              BIGSERIAL       PRIMARY KEY,
    provider_name   VARCHAR(30)     NOT NULL UNIQUE,  -- OPENAI / OLLAMA / CLAUDE / MOCK
    display_name    VARCHAR(100)    NOT NULL,
    base_url        VARCHAR(500),                      -- Ollama: http://localhost:11434
    api_key         VARCHAR(500),                      -- OpenAI / Claude API key
    default_model   VARCHAR(100)    NOT NULL,          -- gpt-4o-mini / llama3.2 / claude-haiku-4-5-20251001
    timeout_seconds INTEGER         NOT NULL DEFAULT 60,
    is_enabled      BOOLEAN         NOT NULL DEFAULT FALSE,
    config_json     TEXT,                              -- 추가 설정 (temperature 등 기본값)
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 작업별 LLM 라우팅 설정
CREATE TABLE IF NOT EXISTS llm_task_config (
    id            BIGSERIAL       PRIMARY KEY,
    task_name     VARCHAR(50)     NOT NULL UNIQUE,  -- LOG_SUMMARY / SIGNAL_ANALYSIS / NEWS_SUMMARY / REPORT_NARRATION
    provider_name VARCHAR(30)     NOT NULL,          -- llm_provider_config.provider_name 참조
    model         VARCHAR(100),                      -- NULL이면 provider default_model 사용
    temperature   DECIMAL(3, 2)   DEFAULT 0.3,
    max_tokens    INTEGER         DEFAULT 2000,
    is_enabled    BOOLEAN         NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 기본 프로바이더 삽입
INSERT INTO llm_provider_config (provider_name, display_name, base_url, default_model, is_enabled, config_json) VALUES
    ('MOCK',   'Mock (테스트용)',        NULL,                          'mock-model',                    TRUE,  '{"temperature": 0.0}'),
    ('OPENAI', 'OpenAI',                'https://api.openai.com/v1',   'gpt-4o-mini',                   FALSE, '{"temperature": 0.3}'),
    ('OLLAMA', 'Ollama (로컬)',          'http://localhost:11434',      'llama3.2',                      FALSE, '{"temperature": 0.3}'),
    ('CLAUDE', 'Anthropic Claude',      'https://api.anthropic.com',   'claude-haiku-4-5-20251001',     FALSE, '{"temperature": 0.3}')
ON CONFLICT (provider_name) DO NOTHING;

-- 기본 작업 라우팅 삽입 (모두 MOCK으로 초기화 — 실제 사용 전 관리 페이지에서 변경)
INSERT INTO llm_task_config (task_name, provider_name, model, temperature, max_tokens) VALUES
    ('LOG_SUMMARY',       'MOCK', NULL, 0.3, 3000),
    ('SIGNAL_ANALYSIS',   'MOCK', NULL, 0.4, 2000),
    ('NEWS_SUMMARY',      'MOCK', NULL, 0.5, 1500),
    ('REPORT_NARRATION',  'MOCK', NULL, 0.5, 2000)
ON CONFLICT (task_name) DO NOTHING;
