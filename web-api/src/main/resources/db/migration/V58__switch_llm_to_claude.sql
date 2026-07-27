-- V58: LLM 프로바이더를 OpenAI → Anthropic Claude 로 전환.
--
-- 배경: OpenAI 크레딧 소진(HTTP 429 insufficient_quota)으로 모닝 브리핑의
--       AI 시황 분석·뉴스 요약이 며칠째 전량 실패(오류 폴백 문자열) 상태였음.
--
-- 주의: Anthropic API 키는 .env(ANTHROPIC_API_KEY)로 관리한다.
--       ClaudeProvider가 env 값을 DB(api_key)보다 우선 사용하므로,
--       이 마이그레이션에서는 키를 넣지 않고 활성화/라우팅만 전환한다.
--       모델 컬럼이 비어 있으면 ClaudeProvider가 default_model(haiku)로 폴백한다.

UPDATE llm_provider_config
   SET is_enabled = TRUE
 WHERE provider_name = 'CLAUDE';

UPDATE llm_task_config
   SET provider_name = 'CLAUDE'
 WHERE task_name IN ('LOG_SUMMARY', 'SIGNAL_ANALYSIS', 'NEWS_SUMMARY', 'REPORT_NARRATION');
