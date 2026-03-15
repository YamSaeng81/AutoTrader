package com.cryptoautotrader.api.config;

import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * core-engine 컴포넌트를 Spring Bean으로 등록한다.
 * core-engine 모듈은 Spring 의존성 없이 순수 Java로 구현되므로
 * web-api 레이어에서 Bean 등록을 담당한다.
 */
@Configuration
public class EngineConfig {

    @Value("${upbit.access-key:}")
    private String upbitAccessKey;

    @Value("${upbit.secret-key:}")
    private String upbitSecretKey;

    @Bean
    public MarketRegimeDetector marketRegimeDetector() {
        return new MarketRegimeDetector();
    }

    @Bean
    public UpbitRestClient upbitRestClient() {
        return new UpbitRestClient();
    }

    /**
     * Upbit 주문 클라이언트 — API Key가 설정된 경우에만 등록.
     * 키가 없으면 null Bean을 등록하지 않고 해당 의존성은 @Autowired(required=false)로 처리.
     */
    @Bean
    public UpbitOrderClient upbitOrderClient() {
        if (upbitAccessKey == null || upbitAccessKey.isBlank()
                || upbitSecretKey == null || upbitSecretKey.isBlank()) {
            return null;
        }
        return new UpbitOrderClient(upbitAccessKey.toCharArray(), upbitSecretKey.toCharArray());
    }
}
