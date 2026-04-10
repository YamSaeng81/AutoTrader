-- V37: Discord 채널 설정 및 발송 이력 테이블

-- Discord 채널 설정
CREATE TABLE IF NOT EXISTS discord_channel_config (
    id              BIGSERIAL       PRIMARY KEY,
    channel_type    VARCHAR(30)     NOT NULL UNIQUE,  -- TRADING_REPORT / CRYPTO_NEWS / ECONOMY_NEWS / ALERT
    display_name    VARCHAR(100)    NOT NULL,
    webhook_url     VARCHAR(500),
    is_enabled      BOOLEAN         NOT NULL DEFAULT FALSE,
    description     VARCHAR(200),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 발송 이력
CREATE TABLE IF NOT EXISTS discord_send_log (
    id              BIGSERIAL       PRIMARY KEY,
    channel_type    VARCHAR(30)     NOT NULL,
    message_type    VARCHAR(30)     NOT NULL DEFAULT 'MORNING_BRIEFING', -- MORNING_BRIEFING / ALERT / MANUAL
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',          -- PENDING / SUCCESS / FAILED
    message_preview TEXT,
    error_message   TEXT,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_discord_send_log_created_at
    ON discord_send_log (created_at DESC);

-- 기본 채널 삽입
INSERT INTO discord_channel_config (channel_type, display_name, is_enabled, description) VALUES
    ('TRADING_REPORT', '매매 분석 리포트', FALSE, '전날 성과·현재 레짐·추천 전략'),
    ('CRYPTO_NEWS',    '코인 뉴스',        FALSE, 'CryptoPanic·CoinGecko 트렌딩 뉴스 요약'),
    ('ECONOMY_NEWS',   '경제 뉴스',        FALSE, 'Bloomberg·연합뉴스 경제 RSS 요약'),
    ('ALERT',          '실시간 알림',      FALSE, '레짐 전환·서킷브레이커 등 즉시 알림')
ON CONFLICT (channel_type) DO NOTHING;
