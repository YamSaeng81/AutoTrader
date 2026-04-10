package com.cryptoautotrader.api.llm;

import com.cryptoautotrader.api.llm.provider.MockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LLM 프로바이더 레지스트리.
 * 스프링이 관리하는 모든 {@link LlmProvider} 구현체를 provider_name → 인스턴스로 매핑.
 * 새 프로바이더 추가 시 {@code @Component}만 붙이면 자동 등록된다.
 */
@Component
public class LlmProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRegistry.class);

    private final Map<String, LlmProvider> providers;
    private final MockProvider mockProvider;

    public LlmProviderRegistry(List<LlmProvider> providerList, MockProvider mockProvider) {
        this.providers    = providerList.stream()
                .collect(Collectors.toMap(LlmProvider::getProviderName, Function.identity()));
        this.mockProvider = mockProvider;
        log.info("[LlmProviderRegistry] 등록된 프로바이더: {}", this.providers.keySet());
    }

    /**
     * provider_name으로 구현체 조회.
     * 존재하지 않거나 미활성화 상태면 MockProvider 반환.
     */
    public LlmProvider get(String providerName) {
        LlmProvider provider = providers.get(providerName);
        if (provider == null) {
            log.warn("[LlmProviderRegistry] 알 수 없는 provider: {} → MockProvider 사용", providerName);
            return mockProvider;
        }
        return provider;
    }

    public Map<String, LlmProvider> getAll() {
        return providers;
    }
}
