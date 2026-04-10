package com.cryptoautotrader.api.llm;

import lombok.Builder;
import lombok.Getter;

/**
 * LLM 요청 모델.
 * 특정 provider에 종속되지 않는 공통 요청 형식.
 */
@Getter
@Builder
public class LlmRequest {

    /** 시스템 프롬프트 (역할 정의, 출력 형식 지시) */
    private final String systemPrompt;

    /** 사용자 입력 (분석할 로그, 뉴스 등) */
    private final String userPrompt;

    /**
     * 사용할 모델명 (NULL이면 provider 기본값 사용).
     * task_config.model → provider.default_model 순으로 fallback.
     */
    private final String model;

    /** 0.0 ~ 1.0, NULL이면 task_config 값 사용 */
    private final Double temperature;

    /** 최대 출력 토큰, NULL이면 task_config 값 사용 */
    private final Integer maxTokens;
}
