-- V36: Notion 보고서 설정 및 발송 이력 테이블

-- Notion 연동 설정
CREATE TABLE IF NOT EXISTS notion_report_config (
    id              BIGSERIAL       PRIMARY KEY,
    config_key      VARCHAR(50)     NOT NULL UNIQUE,
    config_value    TEXT,
    description     VARCHAR(200),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 기본 설정값 삽입
INSERT INTO notion_report_config (config_key, config_value, description) VALUES
    ('notion_token',       NULL,    'Notion Integration Token (secret_xxx)'),
    ('database_id',        NULL,    '보고서를 저장할 Notion 데이터베이스 ID'),
    ('report_enabled',     'false', '보고서 자동 발송 활성화 여부'),
    ('report_schedule',    '0,12',  '보고서 발송 시각 (쉼표 구분, 24h)'),
    ('report_title_prefix','[매매분석]', '보고서 페이지 제목 접두사')
ON CONFLICT (config_key) DO NOTHING;

-- 보고서 발송 이력
CREATE TABLE IF NOT EXISTS notion_report_log (
    id              BIGSERIAL       PRIMARY KEY,
    report_type     VARCHAR(30)     NOT NULL DEFAULT 'ANALYSIS',  -- ANALYSIS / MORNING
    period_start    TIMESTAMP       NOT NULL,
    period_end      TIMESTAMP       NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',   -- PENDING / SUCCESS / FAILED
    notion_page_id  VARCHAR(100),
    notion_page_url VARCHAR(500),
    llm_summary     TEXT,
    llm_analysis    TEXT,
    error_message   TEXT,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notion_report_log_created_at
    ON notion_report_log (created_at DESC);
