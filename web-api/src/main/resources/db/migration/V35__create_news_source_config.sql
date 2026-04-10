-- V35: 뉴스 소스 설정 및 뉴스 캐시 테이블

-- 뉴스 소스 설정
CREATE TABLE IF NOT EXISTS news_source_config (
    id                  BIGSERIAL       PRIMARY KEY,
    source_id           VARCHAR(50)     NOT NULL UNIQUE,  -- cryptopanic / bloomberg_rss / coindesk_rss 등
    display_name        VARCHAR(100)    NOT NULL,
    source_type         VARCHAR(20)     NOT NULL,          -- API / RSS / CRAWLER
    category            VARCHAR(30)     NOT NULL,          -- CRYPTO / ECONOMY / STOCK / GENERAL
    url                 VARCHAR(1000)   NOT NULL,
    api_key             VARCHAR(500),
    is_enabled          BOOLEAN         NOT NULL DEFAULT FALSE,
    fetch_interval_min  INTEGER         NOT NULL DEFAULT 60,
    last_fetched_at     TIMESTAMP,
    config_json         TEXT,                              -- 소스별 추가 설정 (CSS 셀렉터 등)
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 수집된 뉴스 캐시 (중복 제거, 요약 저장)
CREATE TABLE IF NOT EXISTS news_item_cache (
    id              BIGSERIAL       PRIMARY KEY,
    source_id       VARCHAR(50)     NOT NULL,
    external_id     VARCHAR(200),                          -- 소스별 고유 ID (중복 방지)
    title           TEXT            NOT NULL,
    url             VARCHAR(1000),
    summary         TEXT,                                  -- LLM 요약 (채워지면)
    category        VARCHAR(30),
    published_at    TIMESTAMP,
    fetched_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    UNIQUE (source_id, external_id)
);

CREATE INDEX IF NOT EXISTS idx_news_item_cache_fetched_at
    ON news_item_cache (fetched_at DESC);
CREATE INDEX IF NOT EXISTS idx_news_item_cache_category
    ON news_item_cache (category, fetched_at DESC);

-- 기본 소스 삽입
INSERT INTO news_source_config (source_id, display_name, source_type, category, url, is_enabled, fetch_interval_min, config_json) VALUES
    ('cryptopanic',
     'CryptoPanic (코인 뉴스)',
     'API',
     'CRYPTO',
     'https://cryptopanic.com/api/v1/posts/',
     FALSE,
     30,
     '{"filter": "hot", "currencies": "BTC,ETH", "kind": "news"}'),
    ('coindesk_rss',
     'CoinDesk RSS',
     'RSS',
     'CRYPTO',
     'https://www.coindesk.com/arc/outboundfeeds/rss/',
     FALSE,
     60,
     NULL),
    ('coingecko_trending',
     'CoinGecko 트렌딩',
     'API',
     'CRYPTO',
     'https://api.coingecko.com/api/v3/search/trending',
     FALSE,
     60,
     NULL),
    ('bloomberg_rss',
     'Bloomberg Markets RSS',
     'RSS',
     'ECONOMY',
     'https://feeds.bloomberg.com/markets/news.rss',
     FALSE,
     60,
     NULL)
ON CONFLICT (source_id) DO NOTHING;
