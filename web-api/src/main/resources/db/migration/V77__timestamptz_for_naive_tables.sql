-- naive UTC 컬럼을 TIMESTAMPTZ 로 통일 (2026-09-08)
--
-- ■ 왜 — "조용히 틀린 답" 을 주는 함정이다
--
-- 이 저장소는 timestamptz 를 기본으로 쓰는데 9개 테이블 17개 컬럼만 naive `timestamp` 였다.
-- 섞여 있으면 `WHERE created_at > now() - interval '90 minutes'` 같은 흔한 조회가 조용히
-- 어긋난다 — 에러가 아니라 **빈 결과나 잘못된 행**이 나와서 알아채기 어렵다. 실제로 두 번 걸렸다:
--
--   2026-08-18  flyway_schema_history.installed_on 에서 오판
--   2026-08-19  폐기 판정 확인 중 discord_send_log 조회가 0건 — 알림은 정상 발송된 상태였다
--
-- ENGINE_PARITY.md §4 에 "신규 테이블은 예외 없이 TIMESTAMPTZ" 라는 규칙과 함께 경고 목록으로
-- 남겨 두고 미뤄 왔다. 미룬 사유는 "운영 조회 코드를 동시에 고쳐야 해서" 였는데, 실제로 확인해
-- 보니 그렇지 않다 (아래).
--
-- ■ 안전한 이유 — 자바 쪽은 이미 맞다
--
-- 1) 해당 엔티티가 전부 `Instant` 를 쓴다 (DiscordSendLog · RegimeChangeLog · NewsItemCache ·
--    NotionReportLog …). LocalDateTime 이었다면 변환이 값을 이동시켰겠지만 Instant 는 절대시각이라
--    timestamptz 가 오히려 자연스러운 매핑이다.
-- 2) 저장된 값이 실제로 UTC 임을 운영 DB 에서 확인했다 (2026-09-08 06:13 UTC 기준):
--       now()                          2026-09-08 06:13:37+00
--       news_item_cache.fetched_at max  2026-09-08 06:08:41     ← 5분 전, UTC 로 일치
--       discord_send_log.created_at max 2026-09-08 00:00:00     ← 일일 다이제스트(09:00 KST)
--    따라서 `AT TIME ZONE 'UTC'` 변환이 무손실이다.
-- 3) `hibernate.jdbc.time_zone` 이 설정돼 있지 않아 지금은 naive 컬럼 해석이 **JVM 기본 타임존에
--    의존**한다. timestamptz 로 바꾸면 그 의존이 사라진다 — 컨테이너 TZ 를 바꿔도 값이 안 흔들린다.
--
-- ■ 범위
--
-- flyway_schema_history 는 Flyway 가 소유하므로 건드리지 않는다.
-- 나머지 9개 테이블 17개 컬럼만 변환한다. 전부 설정·로그·뉴스 캐시라 매매 경로가 아니다
-- (매매 테이블은 이미 timestamptz).

ALTER TABLE discord_channel_config
    ALTER COLUMN created_at     TYPE TIMESTAMPTZ USING created_at     AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at     TYPE TIMESTAMPTZ USING updated_at     AT TIME ZONE 'UTC';

ALTER TABLE discord_send_log
    ALTER COLUMN created_at     TYPE TIMESTAMPTZ USING created_at     AT TIME ZONE 'UTC';

ALTER TABLE llm_provider_config
    ALTER COLUMN created_at     TYPE TIMESTAMPTZ USING created_at     AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at     TYPE TIMESTAMPTZ USING updated_at     AT TIME ZONE 'UTC';

ALTER TABLE llm_task_config
    ALTER COLUMN updated_at     TYPE TIMESTAMPTZ USING updated_at     AT TIME ZONE 'UTC';

ALTER TABLE news_item_cache
    ALTER COLUMN fetched_at     TYPE TIMESTAMPTZ USING fetched_at     AT TIME ZONE 'UTC',
    ALTER COLUMN published_at   TYPE TIMESTAMPTZ USING published_at   AT TIME ZONE 'UTC';

ALTER TABLE news_source_config
    ALTER COLUMN created_at     TYPE TIMESTAMPTZ USING created_at     AT TIME ZONE 'UTC',
    ALTER COLUMN last_fetched_at TYPE TIMESTAMPTZ USING last_fetched_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at     TYPE TIMESTAMPTZ USING updated_at     AT TIME ZONE 'UTC';

ALTER TABLE notion_report_config
    ALTER COLUMN updated_at     TYPE TIMESTAMPTZ USING updated_at     AT TIME ZONE 'UTC';

ALTER TABLE notion_report_log
    ALTER COLUMN created_at     TYPE TIMESTAMPTZ USING created_at     AT TIME ZONE 'UTC',
    ALTER COLUMN completed_at   TYPE TIMESTAMPTZ USING completed_at   AT TIME ZONE 'UTC',
    ALTER COLUMN period_start   TYPE TIMESTAMPTZ USING period_start   AT TIME ZONE 'UTC',
    ALTER COLUMN period_end     TYPE TIMESTAMPTZ USING period_end     AT TIME ZONE 'UTC';

ALTER TABLE regime_change_log
    ALTER COLUMN detected_at    TYPE TIMESTAMPTZ USING detected_at    AT TIME ZONE 'UTC';

-- 배포 후 확인: flyway_schema_history 를 뺀 naive 컬럼이 0건이어야 한다.
--
--   SELECT table_name, column_name FROM information_schema.columns
--   WHERE table_schema = 'public' AND data_type = 'timestamp without time zone'
--     AND table_name <> 'flyway_schema_history';
