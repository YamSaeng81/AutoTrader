package com.cryptoautotrader.strategy;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class StrategySignal {

    public enum Action {
        BUY, SELL, HOLD
    }

    private final Action action;
    private final BigDecimal strength; // 0~100
    private final String reason;

    public static StrategySignal hold(String reason) {
        return StrategySignal.builder()
                .action(Action.HOLD)
                .strength(BigDecimal.ZERO)
                .reason(reason)
                .build();
    }

    public static StrategySignal buy(BigDecimal strength, String reason) {
        return StrategySignal.builder()
                .action(Action.BUY)
                .strength(strength)
                .reason(reason)
                .build();
    }

    public static StrategySignal sell(BigDecimal strength, String reason) {
        return StrategySignal.builder()
                .action(Action.SELL)
                .strength(strength)
                .reason(reason)
                .build();
    }
}
