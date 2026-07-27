-- V59: 브리핑 LLM 작업 모델을 claude-sonnet-5로 설정.
--
-- 배경: V58에서 4개 작업을 CLAUDE로 라우팅했으나 model 컬럼은 비어 있어
--       default_model(haiku)로 폴백됐음. 사용자 결정으로 요약·분석 품질을 위해
--       전부 Sonnet 5로 지정한다(브리핑 규모에선 비용 미미, Opus는 과잉이라 미채택).
--
-- 주의: V58은 이미 운영에 적용됐으므로 수정하지 않고 별도 마이그레이션으로 분리.

UPDATE llm_task_config
   SET model = 'claude-sonnet-5'
 WHERE task_name IN ('LOG_SUMMARY', 'SIGNAL_ANALYSIS', 'NEWS_SUMMARY', 'REPORT_NARRATION');
