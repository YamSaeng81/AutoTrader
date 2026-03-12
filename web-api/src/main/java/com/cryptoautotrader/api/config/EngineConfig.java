package com.cryptoautotrader.api.config;

import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * core-engine 컴포넌트를 Spring Bean으로 등록한다.
 * core-engine 모듈은 Spring 의존성 없이 순수 Java로 구현되므로
 * web-api 레이어에서 Bean 등록을 담당한다.
 */
@Configuration
public class EngineConfig {

    @Bean
    public MarketRegimeDetector marketRegimeDetector() {
        return new MarketRegimeDetector();
    }
}
