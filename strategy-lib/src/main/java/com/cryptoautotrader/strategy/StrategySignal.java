package com.cryptoautotrader.strategy;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Getter
@Builder
public class StrategySignal {

    public enum Action {
        BUY, SELL, HOLD
    }

    private final Action action;
    private final BigDecimal strength; // 0~100
    private final String reason;
    /** 제안 손절가 (null이면 미설정) */
    private final BigDecimal suggestedStopLoss;
    /** 제안 익절가 (null이면 미설정) */
    private final BigDecimal suggestedTakeProfit;

    /** strength를 0.0~1.0 범위로 정규화한 신뢰도 (WeightedVoting에서 사용) */
    public BigDecimal getConfidence() {
        if (strength == null || strength.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return strength.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

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

    /** 손절/익절가 포함 BUY 신호 */
    public static StrategySignal buy(BigDecimal strength, String reason,
                                     BigDecimal stopLoss, BigDecimal takeProfit) {
        return StrategySignal.builder()
                .action(Action.BUY)
                .strength(strength)
                .reason(reason)
                .suggestedStopLoss(stopLoss)
                .suggestedTakeProfit(takeProfit)
                .build();
    }

    /** 손절/익절가 포함 SELL 신호 */
    public static StrategySignal sell(BigDecimal strength, String reason,
                                      BigDecimal stopLoss, BigDecimal takeProfit) {
        return StrategySignal.builder()
                .action(Action.SELL)
                .strength(strength)
                .reason(reason)
                .suggestedStopLoss(stopLoss)
                .suggestedTakeProfit(takeProfit)
                .build();
    }
}
