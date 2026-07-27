-- V60: 뉴스 소스 확대 — 커버리지 강화 (특히 한국·업비트 이슈).
--
-- 배경: 기존 활성 소스가 CoinDesk·Bloomberg RSS(영미권) 2개뿐. 업비트(한국) 트레이더에게
--       중요한 국내 크립토 이슈·업비트 공지(상장/유의/거래중단)가 통째로 빠져 있었음.
--       또한 cryptopanic·coingecko는 source_type이 'API'로 잘못 설정돼 레지스트리
--       매칭(getSupportedType: CRYPTOPANIC/COINGECKO)에 실패 → 활성화해도 수집 불가 상태였음.

-- 1) 잘못된 source_type 교정 + 활성화 (구현체 등록명과 일치시킴)
UPDATE news_source_config
   SET source_type = 'CRYPTOPANIC', is_enabled = TRUE
 WHERE source_id = 'cryptopanic';

UPDATE news_source_config
   SET source_type = 'COINGECKO', is_enabled = TRUE
 WHERE source_id = 'coingecko_trending';

-- 2) 한국 크립토 RSS 추가 (RSS는 범용 타입 — URL만으로 동작).
--    URL이 유효하지 않으면 수집기가 0건으로 graceful 처리(크래시 없음). 운영에서 URL 검증 후 조정 가능.
INSERT INTO news_source_config (source_id, display_name, source_type, category, url, is_enabled, fetch_interval_min, config_json) VALUES
    ('tokenpost_rss',  '토큰포스트 (한국)',  'RSS', 'CRYPTO', 'https://www.tokenpost.kr/rss',        TRUE, 60, NULL),
    ('blockmedia_rss', '블록미디어 (한국)',  'RSS', 'CRYPTO', 'https://www.blockmedia.co.kr/feed',   TRUE, 60, NULL)
ON CONFLICT (source_id) DO NOTHING;

-- 3) 업비트 공지 소스 (신규 구현체 UpbitNoticeSource, source_type='UPBIT_NOTICE').
--    신규상장·유의종목·거래중단 등 업비트 트레이더 최대 이슈원. URL은 업비트 공지 API.
INSERT INTO news_source_config (source_id, display_name, source_type, category, url, is_enabled, fetch_interval_min, config_json) VALUES
    ('upbit_notice', '업비트 공지', 'UPBIT_NOTICE', 'CRYPTO',
     'https://api-manager.upbit.com/api/v1/announcements?os=web&page=1&per_page=30&category=all',
     TRUE, 30, NULL)
ON CONFLICT (source_id) DO NOTHING;
