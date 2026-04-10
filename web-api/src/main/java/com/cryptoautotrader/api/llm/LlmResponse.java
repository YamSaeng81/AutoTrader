package com.cryptoautotrader.api.llm;

import lombok.Builder;
import lombok.Getter;

/**
 * LLM 응답 모델.
 */
@Getter
@Builder
public class LlmResponse {

    private final String content;
    private final String providerName;
    private final String modelUsed;

    /** 프롬프트 토큰 수 (지원하는 provider만) */
    private final int promptTokens;

    /** 완성 토큰 수 */
    private final int completionTokens;

    /** 성공 여부 */
    private final boolean success;

    /** 실패 시 오류 메시지 */
    private final String errorMessage;

    public static LlmResponse error(String providerName, String errorMessage) {
        return LlmResponse.builder()
                .success(false)
                .providerName(providerName)
                .errorMessage(errorMessage)
                .content("")
                .build();
    }
}
