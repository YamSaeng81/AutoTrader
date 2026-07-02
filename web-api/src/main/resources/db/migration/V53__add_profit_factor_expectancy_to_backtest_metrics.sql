-- expectancy(거래당 평균 손익%)·profit factor(총이익/|총손실|) 지표 추가.
-- 승률만으로는 전략 품질을 판단할 수 없다는 codex 분석(20260702) 반영.
ALTER TABLE backtest_metrics ADD COLUMN profit_factor NUMERIC(8, 4);
ALTER TABLE backtest_metrics ADD COLUMN expectancy_pct NUMERIC(10, 4);
