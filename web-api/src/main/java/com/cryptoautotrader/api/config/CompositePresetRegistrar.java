package com.cryptoautotrader.api.config;

import com.cryptoautotrader.core.selector.CompositePresets;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 복합 전략 프리셋을 애플리케이션 시작 시 StrategyRegistry에 등록한다.
 *
 * <p><b>구성 정의는 여기에 없다.</b> 프리셋의 단일 진실 원천은
 * {@link CompositePresets}(core-engine)이며, 이 클래스는 Spring 기동 시점에 그것을 호출하는
 * 역할만 한다.
 *
 * <p>이전에는 이 클래스가 구성을 직접 들고 있었고, {@code BacktestService}와
 * {@code StrategySelector} static 블록이 각자 다른 구성을 따로 만들었다. COMPOSITE_BREAKOUT은
 * 세 곳의 구성이 전부 달라, 백테스트와 단위 테스트가 운영이 실행하지 않는 전략을 검증하고 있었다.
 * 구성을 한 곳으로 모아 경로에 따라 갈릴 수 없게 했다.
 */
@Component
public class CompositePresetRegistrar {

    @PostConstruct
    public void registerPresets() {
        CompositePresets.registerAll();
    }
}
