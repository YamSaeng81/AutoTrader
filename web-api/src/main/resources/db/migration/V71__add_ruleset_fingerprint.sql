-- V71 (2026-08-19) — 매매 규칙 지문.
--
-- 배경: 이 프로젝트의 전제는 "모의 데이터로 실전을 판단한다" 인데, 그 전제는 데이터가
-- 어떤 규칙 아래 만들어졌는지 알 수 있을 때만 성립한다. 지금까지는 알 수 없었다 —
-- position 314행 전부 strategy_config_id 가 NULL, strategy_log 에는 설정 컬럼이 아예 없었다.
--
-- 그래서 규칙이 바뀐 걸 나중에 알면 어디까지가 옛 규칙인지 구분할 수 없어 데이터를
-- 통째로 버려야 했다. 실제로 반복됐다:
--   · 07-09/07-31 생성 세션: 워치리스트 필터 완화 (ATR 0.30 / 스프레드 0.15 / 후보 50)
--   · 08-07 세션 재생성 시 코드 기본값으로 조용히 복귀 (0.50 / 0.10 / 30)
--   · 감시 코인 주당 62종 → 10종 붕괴. 기록 없음.
--
-- 지문을 찍으면 규칙 변경이 "폐기" 가 아니라 "분할" 이 된다.
-- 과거 데이터는 다른 조건의 관측으로 남고, 같은 지문끼리만 합산하면 표본이 오염되지 않는다.

-- ── 지문 → 실제 파라미터 역참조 ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ruleset_snapshot (
    ruleset_hash  VARCHAR(16)  PRIMARY KEY,
    engine        VARCHAR(20)  NOT NULL,
    params_text   TEXT         NOT NULL,
    first_seen_at TIMESTAMPTZ  NOT NULL
);

COMMENT ON TABLE ruleset_snapshot IS
    '규칙 지문의 실제 파라미터 (V71, 2026-08-19). 지문당 1행. docs/PROGRESS.md 참조';
COMMENT ON COLUMN ruleset_snapshot.params_text IS
    'key=value 개행 구분 정규 직렬화. RulesetFingerprint.toCanonicalString() 결과';

-- ── 데이터 행에 지문 스탬프 ───────────────────────────────────────────────────
-- 기존 행은 NULL 로 남긴다. "규칙 미상" 이 "특정 규칙" 으로 위장하는 것보다 낫다 —
-- 소급 추정은 근거가 없고, NULL 이면 집계에서 자연히 제외된다.
ALTER TABLE position               ADD COLUMN IF NOT EXISTS ruleset_hash VARCHAR(16);
ALTER TABLE paper_trading.position ADD COLUMN IF NOT EXISTS ruleset_hash VARCHAR(16);
ALTER TABLE strategy_log           ADD COLUMN IF NOT EXISTS ruleset_hash VARCHAR(16);

CREATE INDEX IF NOT EXISTS idx_position_ruleset       ON position (ruleset_hash);
CREATE INDEX IF NOT EXISTS idx_paper_position_ruleset ON paper_trading.position (ruleset_hash);
CREATE INDEX IF NOT EXISTS idx_strategy_log_ruleset   ON strategy_log (ruleset_hash);

COMMENT ON COLUMN position.ruleset_hash IS
    '이 거래를 만든 매매 규칙 지문 (V71). NULL = V71 이전 데이터로 규칙 미상';

-- ── 워치리스트 필터 기본값을 전역 설정으로 승격 ────────────────────────────────
-- 08-07 회귀의 직접 원인은 이 값들이 "세션 행에만" 살았다는 것이다. 세션을 재생성하면
-- 코드 하드코딩 기본값으로 돌아가고, 7월의 튜닝이 소리 없이 사라진다.
-- risk_config 에 두면 세션 재생성과 무관하게 살아남는다. NULL 이면 기존 코드 기본값 사용.
ALTER TABLE risk_config ADD COLUMN IF NOT EXISTS scan_min_atr_pct        NUMERIC(6, 4);
ALTER TABLE risk_config ADD COLUMN IF NOT EXISTS scan_max_spread_pct     NUMERIC(6, 4);
ALTER TABLE risk_config ADD COLUMN IF NOT EXISTS scan_max_candidate_size INTEGER;

COMMENT ON COLUMN risk_config.scan_min_atr_pct IS
    '신규 동적 세션의 min_atr_pct 기본값 (V71). NULL 이면 코드 기본값. 세션 재생성에도 살아남는다';
