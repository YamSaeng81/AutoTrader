-- V41에서 SMALLINT로 생성된 컬럼을 INTEGER로 변경 (Hibernate Integer 타입과 일치)
ALTER TABLE nightly_scheduler_config
    ALTER COLUMN run_hour    TYPE INTEGER,
    ALTER COLUMN run_minute  TYPE INTEGER,
    ALTER COLUMN window_count TYPE INTEGER;
