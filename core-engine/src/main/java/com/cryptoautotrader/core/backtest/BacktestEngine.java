package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.MetricsCalculator;
import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 백테스팅 엔진
 * - Look-Ahead Bias 방지: 현재 캔들 close에서 신호, 다음 캔들 open에서 체결
 * - Fill Simulation 지원 (Market Impact + Partial Fill)
 */
public class BacktestEngine {

    private static final int SCALE = 8;

    public BacktestResult run(BacktestConfig config, List<Candle> candles) {
        Strategy strategy = StrategyRegistry.get(config.getStrategyName());
        MarketRegimeDetector regimeDetector = new MarketRegimeDetector();
        FillSimulator fillSimulator = config.isFillSimulationEnabled()
                ? new FillSimulator(config.getImpactFactor(), config.getFillRatio())
                : null;

        List<TradeRecord> trades = new ArrayList<>();
        BigDecimal capital = config.getInitialCapital();
        BigDecimal position = BigDecimal.ZERO; // 보유 수량
        BigDecimal entryPrice = BigDecimal.ZERO;
        BigDecimal cumulativePnl = BigDecimal.ZERO;
        BigDecimal pendingQuantity = BigDecimal.ZERO; // Partial Fill 이월 수량
        OrderSide pendingSide = null;

        int minCandles = strategy.getMinimumCandleCount();

        for (int i = minCandles; i < candles.size() - 1; i++) {
            List<Candle> window = candles.subList(0, i + 1);
            Candle currentCandle = candles.get(i);
            Candle nextCandle = candles.get(i + 1);

            // Partial Fill 이월 처리
            if (pendingQuantity.compareTo(BigDecimal.ZERO) > 0 && fillSimulator != null) {
                BigDecimal maxFill = fillSimulator.calculateMaxFillQuantity(nextCandle.getVolume());
                BigDecimal fillQty = pendingQuantity.min(maxFill);
                BigDecimal impact = fillSimulator.calculateMarketImpact(fillQty, nextCandle.getVolume());
                BigDecimal executionPrice = applySlippage(nextCandle.getOpen(), pendingSide, config.getSlippagePct().add(impact));

                TradeRecord trade = executeTrade(pendingSide, executionPrice, fillQty,
                        config.getFeePct(), config.getSlippagePct().add(impact),
                        "Partial Fill 이월", null, nextCandle, cumulativePnl, entryPrice);

                trades.add(trade);
                pendingQuantity = pendingQuantity.subtract(fillQty);

                if (pendingSide == OrderSide.BUY) {
                    position = position.add(fillQty);
                    capital = capital.subtract(executionPrice.multiply(fillQty));
                } else {
                    position = position.subtract(fillQty);
                    capital = capital.add(executionPrice.multiply(fillQty));
                }
                cumulativePnl = trade.getCumulativePnl();
                continue;
            }

            // 전략 신호 생성 (현재 캔들)
            StrategySignal signal = strategy.evaluate(window, config.getStrategyParams());
            MarketRegime regime = regimeDetector.detect(window);

            // 다음 캔들 open에서 체결 (Look-Ahead Bias 방지)
            if (signal.getAction() == StrategySignal.Action.BUY && position.compareTo(BigDecimal.ZERO) == 0) {
                BigDecimal orderQuantity = capital.divide(nextCandle.getOpen(), SCALE, RoundingMode.HALF_UP);

                BigDecimal additionalSlippage = BigDecimal.ZERO;
                if (fillSimulator != null) {
                    additionalSlippage = fillSimulator.calculateMarketImpact(orderQuantity, nextCandle.getVolume());
                    if (fillSimulator.isPartialFill(orderQuantity, nextCandle.getVolume())) {
                        BigDecimal maxFill = fillSimulator.calculateMaxFillQuantity(nextCandle.getVolume());
                        pendingQuantity = orderQuantity.subtract(maxFill);
                        pendingSide = OrderSide.BUY;
                        orderQuantity = maxFill;
                    }
                }

                BigDecimal totalSlippage = config.getSlippagePct().add(additionalSlippage);
                BigDecimal executionPrice = applySlippage(nextCandle.getOpen(), OrderSide.BUY, totalSlippage);
                BigDecimal fee = executionPrice.multiply(orderQuantity).multiply(config.getFeePct()).divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);

                position = orderQuantity;
                entryPrice = executionPrice;
                capital = capital.subtract(executionPrice.multiply(orderQuantity)).subtract(fee);

                trades.add(TradeRecord.builder()
                        .side(OrderSide.BUY)
                        .price(executionPrice)
                        .quantity(orderQuantity)
                        .fee(fee)
                        .slippage(totalSlippage)
                        .pnl(BigDecimal.ZERO)
                        .cumulativePnl(cumulativePnl)
                        .signalReason(signal.getReason())
                        .marketRegime(regime.name())
                        .executedAt(nextCandle.getTime())
                        .build());

            } else if (signal.getAction() == StrategySignal.Action.SELL && position.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal additionalSlippage = BigDecimal.ZERO;
                if (fillSimulator != null) {
                    additionalSlippage = fillSimulator.calculateMarketImpact(position, nextCandle.getVolume());
                }

                BigDecimal totalSlippage = config.getSlippagePct().add(additionalSlippage);
                BigDecimal executionPrice = applySlippage(nextCandle.getOpen(), OrderSide.SELL, totalSlippage);
                BigDecimal fee = executionPrice.multiply(position).multiply(config.getFeePct()).divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
                BigDecimal pnl = executionPrice.subtract(entryPrice).multiply(position).subtract(fee);
                cumulativePnl = cumulativePnl.add(pnl);

                capital = capital.add(executionPrice.multiply(position)).subtract(fee);

                trades.add(TradeRecord.builder()
                        .side(OrderSide.SELL)
                        .price(executionPrice)
                        .quantity(position)
                        .fee(fee)
                        .slippage(totalSlippage)
                        .pnl(pnl)
                        .cumulativePnl(cumulativePnl)
                        .signalReason(signal.getReason())
                        .marketRegime(regime.name())
                        .executedAt(nextCandle.getTime())
                        .build());

                position = BigDecimal.ZERO;
                entryPrice = BigDecimal.ZERO;
            }
        }

        PerformanceReport metrics = MetricsCalculator.calculate(trades, config.getInitialCapital());

        return BacktestResult.builder()
                .config(config)
                .trades(trades)
                .metrics(metrics)
                .build();
    }

    private BigDecimal applySlippage(BigDecimal price, OrderSide side, BigDecimal slippagePct) {
        BigDecimal slippageMult = slippagePct.divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        if (side == OrderSide.BUY) {
            return price.multiply(BigDecimal.ONE.add(slippageMult)).setScale(SCALE, RoundingMode.HALF_UP);
        } else {
            return price.multiply(BigDecimal.ONE.subtract(slippageMult)).setScale(SCALE, RoundingMode.HALF_UP);
        }
    }

    private TradeRecord executeTrade(OrderSide side, BigDecimal price, BigDecimal quantity,
                                     BigDecimal feePct, BigDecimal slippage, String reason,
                                     String regime, Candle candle, BigDecimal cumulativePnl,
                                     BigDecimal entryPrice) {
        BigDecimal fee = price.multiply(quantity).multiply(feePct).divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        BigDecimal pnl = side == OrderSide.SELL
                ? price.subtract(entryPrice).multiply(quantity).subtract(fee)
                : BigDecimal.ZERO;
        return TradeRecord.builder()
                .side(side)
                .price(price)
                .quantity(quantity)
                .fee(fee)
                .slippage(slippage)
                .pnl(pnl)
                .cumulativePnl(cumulativePnl.add(pnl))
                .signalReason(reason)
                .marketRegime(regime)
                .executedAt(candle.getTime())
                .build();
    }
}
