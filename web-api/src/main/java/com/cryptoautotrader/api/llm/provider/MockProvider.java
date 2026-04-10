package com.cryptoautotrader.api.llm.provider;

import com.cryptoautotrader.api.llm.LlmProvider;
import com.cryptoautotrader.api.llm.LlmRequest;
import com.cryptoautotrader.api.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 개발·테스트용 Mock LLM 프로바이더.
 * 실제 API 호출 없이 더미 텍스트를 반환한다.
 * 기본 provider_name이 MOCK인 경우 이 구현체가 사용된다.
 */
@Component
public class MockProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MockProvider.class);

    @Override
    public LlmResponse complete(LlmRequest request) {
        log.debug("[MockProvider] 요청 수신 — 더미 응답 반환");
        String dummy = "[Mock 응답] 분석 완료. 시스템 프롬프트: "
                + (request.getSystemPrompt() != null ? request.getSystemPrompt().substring(0, Math.min(50, request.getSystemPrompt().length())) : "없음")
                + "... 실제 LLM 프로바이더를 설정하면 여기에 AI 분석이 표시됩니다.";
        return LlmResponse.builder()
                .success(true)
                .providerName(getProviderName())
                .modelUsed("mock-model")
                .content(dummy)
                .promptTokens(0)
                .completionTokens(0)
                .build();
    }

    @Override
    public String getProviderName() {
        return "MOCK";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
