-- V48: risk_config 에 글로벌 포트폴리오 드로우다운 상한 컬럼 추가
-- 기간 gross 손실 체크와 달리, 전체 세션의 현재 자산이 초기 자본 대비
-- 얼마나 낮은지 실시간으로 추가 검사한다 (analy.md Tier1 §5 미완 서브항목).

ALTER TABLE risk_config
    ADD COLUMN IF NOT EXISTS max_portfolio_drawdown_pct DECIMAL(5, 2) DEFAULT 15.0;
