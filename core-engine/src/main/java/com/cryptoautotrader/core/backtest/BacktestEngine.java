package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.MetricsCalculator;
import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.core.risk.ExitRuleChecker;
import com.cryptoautotrader.core.risk.ExitRuleChecker.ExitCheck;
import com.cryptoautotrader.core.risk.ExitRuleChecker.StopLevels;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 백테스팅 엔진
 * - Look-Ahead Bias 방지: 현재 캔들 close에서 신호, 다음 캔들 open에서 체결
 * - Fill Simulation 지원 (Market Impact + Partial Fill)
 * - 통합 리스크 관리: SL/TP 체크, 트레일링 스탑, 포지션 사이징
 *   (ExitRuleChecker — 실전매매와 동일 규칙)
 */
public class BacktestEngine {

    private static final int SCALE = 8;

    /**
     * 외부에서 생성한 Strategy를 직접 전달하는 오버로드.
     * CompositeStrategy, MultiTimeframeFilter 등 동적으로 조합한 전략에 사용한다.
     */
    public BacktestResult run(BacktestConfig config, List<Candle> candles, Strategy strategy) {
        return runWithStrategy(config, candles, strategy);
    }

    public BacktestResult run(BacktestConfig config, List<Candle> candles) {
        Strategy strategy = StrategyRegistry.get(config.getStrategyName());
        return runWithStrategy(config, candles, strategy);
    }

    private BacktestResult runWithStrategy(BacktestConfig config, List<Candle> candles, Strategy strategy) {
        MarketRegimeDetector regimeDetector = new MarketRegimeDetector();
        ExitRuleChecker exitChecker = new ExitRuleChecker(config.getExitRuleConfig());
        FillSimulator fillSimulator = config.isFillSimulationEnabled()
                ? new FillSimulator(config.getImpactFactor(), config.getFillRatio())
                : null;

        List<TradeRecord> trades = new ArrayList<>();
        BigDecimal capital = config.getInitialCapital();
        BigDecimal position = BigDecimal.ZERO; // 보유 수량
        BigDecimal entryPrice = BigDecimal.ZERO;
        BigDecimal entryFee = BigDecimal.ZERO; // 매수 시 지불한 수수료 (SELL PnL 계산에 반영)
        BigDecimal cumulativePnl = BigDecimal.ZERO;
        BigDecimal pendingQuantity = BigDecimal.ZERO; // Partial Fill 이월 수량
        OrderSide pendingSide = null;

        // 리스크 관리 상태
        BigDecimal stopLossPrice = BigDecimal.ZERO;
        BigDecimal takeProfitPrice = BigDecimal.ZERO;

        int minCandles = strategy.getMinimumCandleCount();
        // 지표 수렴에 충분한 lookback. 전체 이력을 전달하면 O(n²) 가 되므로 최근 N개로 고정한다.
        // Wilder ATR/ADX/RSI의 EMA는 200개 이후 수렴 오차 < 0.001% — 500은 충분한 여유.
        final int MAX_LOOKBACK = 500;

        for (int i = minCandles; i < candles.size() - 1; i++) {
            int windowStart = Math.max(0, i + 1 - MAX_LOOKBACK);
            List<Candle> window = candles.subList(windowStart, i + 1);
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
                    // 가중평균 진입가 재계산 — 단순 add 는 평균 매수단가 왜곡
                    BigDecimal newPosition = position.add(fillQty);
                    if (newPosition.compareTo(BigDecimal.ZERO) > 0) {
                        entryPrice = position.multiply(entryPrice)
                                .add(fillQty.multiply(executionPrice))
                                .divide(newPosition, SCALE, RoundingMode.HALF_UP);
                    }
                    position = newPosition;
                    entryFee = entryFee.add(trade.getFee());
                    capital = capital.subtract(executionPrice.multiply(fillQty)).subtract(trade.getFee());
                } else {
                    position = position.subtract(fillQty);
                    capital = capital.add(executionPrice.multiply(fillQty)).subtract(trade.getFee());
                }
                cumulativePnl = trade.getCumulativePnl();
            }

            // ── 포지션 보유 중: SL/TP 체크 (전략 신호보다 우선) ──────
            if (position.compareTo(BigDecimal.ZERO) > 0) {
                // 트레일링 스탑 갱신 (다음 캔들 open 기준)
                StopLevels updatedLevels = exitChecker.updateTrailingStops(
                        nextCandle.getHigh(), nextCandle.getLow(),
                        entryPrice, stopLossPrice, takeProfitPrice);
                stopLossPrice = updatedLevels.getStopLossPrice();
                takeProfitPrice = updatedLevels.getTakeProfitPrice();

                // OHLC 경로 재구성으로 SL/TP 도달 순서 판정 (intra-H1 정확도 향상)
                ExitCheck exitCheck = exitChecker.checkCandleExitWithPath(
                        nextCandle.getOpen(), nextCandle.getHigh(), nextCandle.getLow(),
                        nextCandle.getClose(), stopLossPrice, takeProfitPrice);

                if (exitCheck.isShouldExit()) {
                    // ── 현실적 체결가 산출 ─────────────────────────
                    // 1) Gap 감지: 오픈이 이미 SL/TP 를 넘어섰다면 오픈가로 체결(갭 손실/이익).
                    // 2) 아니면 트리거 가격에 체결.
                    // 3) 추가로 시장 충격 + 기본 슬리피지를 SELL 방향으로 적용.
                    BigDecimal rawPrice = exitCheck.getExitPrice();
                    if (exitCheck.getType() == ExitRuleChecker.ExitType.STOP_LOSS
                            && nextCandle.getOpen().compareTo(stopLossPrice) < 0) {
                        rawPrice = nextCandle.getOpen(); // gap-down — SL 아래에서 오픈 → 오픈가 체결
                    } else if (exitCheck.getType() == ExitRuleChecker.ExitType.TAKE_PROFIT
                            && nextCandle.getOpen().compareTo(takeProfitPrice) > 0) {
                        rawPrice = nextCandle.getOpen(); // gap-up — TP 위에서 오픈 → 오픈가 체결
                    }

                    BigDecimal additionalSlippage = BigDecimal.ZERO;
                    if (fillSimulator != null) {
                        additionalSlippage = fillSimulator.calculateMarketImpact(position, nextCandle.getVolume());
                    }
                    BigDecimal totalSlippage = config.getSlippagePct().add(additionalSlippage);
                    BigDecimal executionPrice = applySlippage(rawPrice, OrderSide.SELL, totalSlippage);

                    BigDecimal fee = executionPrice.multiply(position)
                            .multiply(config.getFeePct())
                            .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
                    BigDecimal pnl = executionPrice.subtract(entryPrice)
                            .multiply(position).subtract(fee).subtract(entryFee);
                    cumulativePnl = cumulativePnl.add(pnl);

                    capital = capital.add(executionPrice.multiply(position)).subtract(fee);
                    MarketRegime regime = regimeDetector.detect(window);

                    String exitReason = exitCheck.getReason();
                    if (exitCheck.isAmbiguous()) {
                        exitReason = "[AMBIGUOUS:SL+TP] " + exitReason;
                    }

                    trades.add(TradeRecord.builder()
                            .side(OrderSide.SELL)
                            .price(executionPrice)
                            .quantity(position)
                            .fee(fee)
                            .slippage(totalSlippage)
                            .pnl(pnl)
                            .cumulativePnl(cumulativePnl)
                            .signalReason(exitReason)
                            .marketRegime(regime.name())
                            .executedAt(nextCandle.getTime())
                            .build());

                    position = BigDecimal.ZERO;
                    entryPrice = BigDecimal.ZERO;
                    entryFee = BigDecimal.ZERO;
                    stopLossPrice = BigDecimal.ZERO;
                    takeProfitPrice = BigDecimal.ZERO;
                    pendingQuantity = BigDecimal.ZERO;
                    pendingSide = null;
                    continue; // SL/TP 청산 후 이번 캔들에서 재진입하지 않음
                }
            }

            // 전략 신호 생성 (현재 캔들) — coinPair를 params에 주입해 코인별 전략 기본값 적용
            Map<String, Object> evalParams = new HashMap<>(config.getStrategyParams());
            if (config.getCoinPair() != null) {
                evalParams.put("coinPair", config.getCoinPair());
            }
            StrategySignal signal = strategy.evaluate(window, evalParams);
            MarketRegime regime = regimeDetector.detect(window);

            // 다음 캔들 open에서 체결 (Look-Ahead Bias 방지)
            // BUY: 포지션 없고 pending 이월도 없을 때만 진입
            if (signal.getAction() == StrategySignal.Action.BUY
                    && position.compareTo(BigDecimal.ZERO) == 0
                    && pendingQuantity.compareTo(BigDecimal.ZERO) == 0) {

                // 포지션 사이징: 가용 자금 × 투자 비율 (실전매매와 동일)
                BigDecimal investAmount = exitChecker.calculateInvestAmount(capital);
                if (investAmount.compareTo(BigDecimal.ZERO) == 0) {
                    continue; // 최소 투자 금액 미달
                }

                BigDecimal orderQuantity = investAmount.divide(nextCandle.getOpen(), SCALE, RoundingMode.HALF_UP);

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
                entryFee = fee;
                capital = capital.subtract(executionPrice.multiply(orderQuantity)).subtract(fee);

                // SL/TP 초기값 설정 (전략 제안값 우선, 없으면 기본값)
                StopLevels levels = exitChecker.calculateStopLevels(executionPrice, signal);
                stopLossPrice = levels.getStopLossPrice();
                takeProfitPrice = levels.getTakeProfitPrice();

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
                BigDecimal pnl = executionPrice.subtract(entryPrice).multiply(position).subtract(fee).subtract(entryFee);
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
                entryFee = BigDecimal.ZERO;
                stopLossPrice = BigDecimal.ZERO;
                takeProfitPrice = BigDecimal.ZERO;
                // SELL 시 대기 중인 Partial Fill BUY 이월 취소
                pendingQuantity = BigDecimal.ZERO;
                pendingSide = null;
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
