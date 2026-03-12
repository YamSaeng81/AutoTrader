package com.cryptoautotrader.core.model;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Builder
public class TradeRecord {
    private final OrderSide side;
    private final BigDecimal price;
    private final BigDecimal quantity;
    private final BigDecimal fee;
    private final BigDecimal slippage;
    private final BigDecimal pnl;
    private final BigDecimal cumulativePnl;
    private final String signalReason;
    private final String marketRegime;
    private final Instant executedAt;
}
