-- V58: LLM 프로바이더를 OpenAI → Anthropic Claude 로 전환.
--
-- 배경: OpenAI 크레딧 소진(HTTP 429 insufficient_quota)으로 모닝 브리핑의
--       AI 시황 분석·뉴스 요약이 며칠째 전량 실패(오류 폴백 문자열) 상태였음.
--
-- 주의: Anthropic API 키는 .env(ANTHROPIC_API_KEY)로 관리한다.
--       ClaudeProvider가 env 값을 DB(api_key)보다 우선 사용하므로,
--       이 마이그레이션에서는 키를 넣지 않고 활성화/라우팅/모델만 설정한다.
--
-- 모델: 4개 작업 전부 claude-sonnet-5 (사용자 결정). 요약·분석 품질 상향,
--       브리핑 규모(하루 수 회·소량 토큰)에선 비용도 미미. 토큰당 단가가
--       훨씬 높은 Opus는 일일 브리핑에 과잉이라 미채택.

UPDATE llm_provider_config
   SET is_enabled = TRUE
 WHERE provider_name = 'CLAUDE';

UPDATE llm_task_config
   SET provider_name = 'CLAUDE',
       model         = 'claude-sonnet-5'
 WHERE task_name IN ('LOG_SUMMARY', 'SIGNAL_ANALYSIS', 'NEWS_SUMMARY', 'REPORT_NARRATION');
