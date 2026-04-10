package com.cryptoautotrader.api.llm;

/**
 * LLM 프로바이더 추상화 인터페이스.
 *
 * <p>구현체: {@link provider.OpenAiProvider}, {@link provider.OllamaProvider},
 * {@link provider.ClaudeProvider}, {@link provider.MockProvider}
 *
 * <p>새 LLM 연동 시 이 인터페이스만 구현하면 기존 파이프라인에 즉시 연결된다.
 */
public interface LlmProvider {

    /**
     * 텍스트 완성 요청.
     *
     * @param request 시스템·유저 프롬프트, 모델, 파라미터
     * @return 생성 결과 (실패 시 success=false + errorMessage)
     */
    LlmResponse complete(LlmRequest request);

    /**
     * 프로바이더 식별자 (DB의 provider_name과 일치).
     * 예: "OPENAI", "OLLAMA", "CLAUDE", "MOCK"
     */
    String getProviderName();

    /**
     * 현재 호출 가능한 상태인지 확인.
     * (API 키 설정 여부, 로컬 서버 기동 여부 등)
     */
    boolean isAvailable();
}
