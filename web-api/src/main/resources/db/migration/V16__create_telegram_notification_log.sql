-- 텔레그램 알림 전송 이력 테이블
CREATE TABLE IF NOT EXISTS telegram_notification_log (
    id           BIGSERIAL PRIMARY KEY,
    type         VARCHAR(30)  NOT NULL,          -- TRADE_SUMMARY / SESSION_START / SESSION_STOP / STOP_LOSS / EXCHANGE_DOWN / RISK_LIMIT / TEST
    session_label VARCHAR(100),                  -- 세션 식별자 (없으면 NULL)
    message_text  TEXT         NOT NULL,
    success       BOOLEAN      NOT NULL DEFAULT true,
    sent_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_telegram_log_sent_at ON telegram_notification_log (sent_at DESC);
CREATE INDEX idx_telegram_log_type    ON telegram_notification_log (type);
