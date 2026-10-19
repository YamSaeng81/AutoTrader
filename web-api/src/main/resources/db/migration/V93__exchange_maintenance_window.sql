-- 거래소 공식 점검 구간 (2026-10-19)
--
-- 업비트는 체결이 없는 구간에 봉을 만들지 않는다. 점검 중에는 전 마켓의 봉이 없다. 캔들 최신성 관문(CandleFreshnessGuard)은
-- 없는 봉을 모두 결측으로 세므로, 점검이 끝나도 그 구간이 판정 창에서 밀려날 때까지(M15 약 5일·H1 약 20일) 판정 불가가 된다.
-- 이 표는 **공식 점검으로 확인된 시장 공통 공백**만 기록한다. 관문은 이 구간에 걸리고 + 거래소 대조(결측 재수집 조회)에서도 없는 칸만
-- 결측에서 뺀다. 점검 밖의 결측·현재 적재 정지(최신 완료 봉 없음)는 그대로 판정 불가다.
--
--   starts_at / ends_at  실제로 거래가 멈춘 구간(예정 시각이 아니라 공지·거래소 대조로 확인한 시각). 끝난 구간만 쓴다.
--   scope                ALL_MARKETS 만 관문이 쓴다(시장 공통 공백)
--   source               근거(공지 번호·제목 등)
--   source_timezone      근거에 적힌 시각의 시간대(예: Asia/Seoul) — 이 표의 시각은 UTC 기준 시각으로 저장한다
--   confirmed_at / confirmed_by  누가 언제 확인해 넣었는가
CREATE TABLE IF NOT EXISTS exchange_maintenance_window (
    id               BIGSERIAL     PRIMARY KEY,
    exchange         VARCHAR(20)   NOT NULL DEFAULT 'UPBIT',
    scope            VARCHAR(20)   NOT NULL DEFAULT 'ALL_MARKETS',
    starts_at        TIMESTAMPTZ   NOT NULL,
    ends_at          TIMESTAMPTZ   NOT NULL,
    source           VARCHAR(300)  NOT NULL,
    source_timezone  VARCHAR(40)   NOT NULL,
    confirmed_at     TIMESTAMPTZ   NOT NULL,
    confirmed_by     VARCHAR(100)  NOT NULL,
    note             TEXT,
    CONSTRAINT exchange_maintenance_window_range CHECK (ends_at > starts_at)
);
