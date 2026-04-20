-- 20260415_analy.md 신호품질 개선 — 포지션 수 → 자본 사용률 기반 리스크 차단
-- max_positions 는 안전망으로 유지 (기본값 3 → 20 으로 상향)
-- max_capital_utilization_pct : 투입자본/전체자본×100 이 이 값 초과 시 신규 매수 차단 (기본 80%)

ALTER TABLE risk_config
    ADD COLUMN IF NOT EXISTS max_capital_utilization_pct NUMERIC(5,2) DEFAULT 80.0;

-- 기존 max_positions=3 레코드를 20으로 상향 (포지션 수 한도를 실질적 안전망으로 격하)
UPDATE risk_config SET max_positions = 20 WHERE max_positions = 3;
