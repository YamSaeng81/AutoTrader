-- Walk Forward 결과 전체를 JSON으로 저장 (이력 조회 및 재표시용)
ALTER TABLE backtest_run ADD COLUMN IF NOT EXISTS wf_result_json JSONB;
