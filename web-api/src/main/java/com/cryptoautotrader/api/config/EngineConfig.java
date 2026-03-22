package com.cryptoautotrader.api.config;

import com.cryptoautotrader.core.portfolio.PortfolioManager;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    /**
     * PortfolioManager — 초기 totalCapital은 0으로 시작하며,
     * PortfolioSyncService 스케줄러가 기동 직후 거래소 잔고로 동기화한다.
     */
    @Bean
    public PortfolioManager portfolioManager() {
        return new PortfolioManager(BigDecimal.ZERO);
    }

    @Bean
    public UpbitRestClient upbitRestClient() {
        return new UpbitRestClient();
    }

    /**
     * Upbit 주문 클라이언트 — upbit.access-key / upbit.secret-key 가 모두 설정된 경우에만 Bean 등록.
     * 키가 없으면 Bean 자체를 등록하지 않으므로 @Autowired(required=false) 주입처에서 null로 처리됨.
     */
    @Bean
    @ConditionalOnProperty(name = {"upbit.access-key", "upbit.secret-key"})
    public UpbitOrderClient upbitOrderClient(
            @Value("${upbit.access-key}") String accessKey,
            @Value("${upbit.secret-key}") String secretKey) {
        return new UpbitOrderClient(accessKey.toCharArray(), secretKey.toCharArray());
    }
}
