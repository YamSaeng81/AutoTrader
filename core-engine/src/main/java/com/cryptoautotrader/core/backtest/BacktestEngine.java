package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.MetricsCalculator;
import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.core.selector.Ema200RegimeGate;
import com.cryptoautotrader.core.selector.RangeRegimeGate;
import com.cryptoautotrader.core.risk.ExitRuleChecker;
import com.cryptoautotrader.core.risk.ExitRuleChecker.ExitCheck;
import com.cryptoautotrader.core.risk.ExitRuleChecker.StopLevels;
import com.cryptoautotrader.core.risk.ExitRuleFormula;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
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
        Instant entryTime = null; // 최소보유시간 게이트(L-2)용 — 최초 진입 시각
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

        // BTC_MARKET_GUARD — 두 캔들 시리즈(대상 코인, BTC) 모두 시간 오름차순이므로
        // 투 포인터로 O(n+m)에 정렬 위치를 추적한다. btcCandles가 null이면 게이트 비활성.
        List<Candle> btcCandles = config.getBtcCandles();
        int btcPtr = -1;

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
                // 트레일링 스탑 갱신 (다음 캔들 기준) — TP 만 래칫 상향한다.
                // 2026-09-08 이전에는 저가가 진입가 아래일 때 SL 도 조여졌다. 백테스트가
                // 운영 엔진과 같은 함수를 쓰는 덕에 그 결함도 그대로 재현하고 있었다 —
                // 즉 **이 날짜 이전의 백테스트·Walk Forward 결과는 0.3% 손절 기준이다.**
                StopLevels updatedLevels = exitChecker.updateTrailingStops(
                        nextCandle.getHigh(), entryPrice, stopLossPrice, takeProfitPrice);
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
                    entryTime = null;
                    entryFee = BigDecimal.ZERO;
                    stopLossPrice = BigDecimal.ZERO;
                    takeProfitPrice = BigDecimal.ZERO;
                    pendingQuantity = BigDecimal.ZERO;
                    pendingSide = null;
                    continue; // SL/TP 청산 후 이번 캔들에서 재진입하지 않음
                }

                // ── 시간 초과 청산 (time stop) — 2026-09-08 추가 ──────────────
                //
                // 검사 순서는 실전과 같다: SL/TP → time stop → 전략 SELL
                // (DynamicTradingService.evaluatePosition · LiveTradingService 와 동일).
                // 가격 기반 SL/TP 만 있으면 저변동 종목은 어느 쪽에도 도달하지 않아 자본이
                // 무기한 묶인다. 운영 동적 세션 청산 83건 중 TIME_STOP 이 39건(47%)인데
                // 백테스트에는 이 경로가 아예 없어 거래 모집단 자체가 달랐다.
                if (ExitRuleFormula.shouldTimeStop(config.getMaxHoldHours(), entryTime, nextCandle.getTime())) {
                    long heldHours = Duration.between(entryTime, nextCandle.getTime()).toHours();

                    BigDecimal additionalSlippage = BigDecimal.ZERO;
                    if (fillSimulator != null) {
                        additionalSlippage = fillSimulator.calculateMarketImpact(position, nextCandle.getVolume());
                    }
                    BigDecimal totalSlippage = config.getSlippagePct().add(additionalSlippage);
                    BigDecimal executionPrice = applySlippage(nextCandle.getOpen(), OrderSide.SELL, totalSlippage);

                    BigDecimal fee = executionPrice.multiply(position)
                            .multiply(config.getFeePct())
                            .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
                    BigDecimal pnl = executionPrice.subtract(entryPrice)
                            .multiply(position).subtract(fee).subtract(entryFee);
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
                            .signalReason(String.format("시간 초과 청산 — 보유 %d시간 ≥ %d시간",
                                    heldHours, config.getMaxHoldHours()))
                            .marketRegime(regimeDetector.detect(window).name())
                            .executedAt(nextCandle.getTime())
                            .build());

                    position = BigDecimal.ZERO;
                    entryPrice = BigDecimal.ZERO;
                    entryTime = null;
                    entryFee = BigDecimal.ZERO;
                    stopLossPrice = BigDecimal.ZERO;
                    takeProfitPrice = BigDecimal.ZERO;
                    pendingQuantity = BigDecimal.ZERO;
                    pendingSide = null;
                    continue;
                }
            }

            // 전략 신호 생성 (현재 캔들) — coinPair를 params에 주입해 코인별 전략 기본값 적용
            Map<String, Object> evalParams = new HashMap<>(config.getStrategyParams());
            if (config.getCoinPair() != null) {
                evalParams.put("coinPair", config.getCoinPair());
            }
            StrategySignal signal = strategy.evaluate(window, evalParams);
            if (config.isInvertSignals()) {
                signal = invert(signal);
            }
            MarketRegime regime = regimeDetector.detect(window);

            // BTC_MARKET_GUARD — nextCandle 시점까지의 BTC 캔들 윈도우로 판정한다(look-ahead 방지,
            // 실전매매와 동일하게 "이 시점에 알 수 있었던" BTC 데이터만 사용). 최근 200개로 윈도우를
            // 고정해 매 캔들마다 전체 구간을 재필터링하지 않는다.
            boolean btcGatePass = true;
            if (btcCandles != null && !btcCandles.isEmpty()) {
                while (btcPtr + 1 < btcCandles.size()
                        && !btcCandles.get(btcPtr + 1).getTime().isAfter(nextCandle.getTime())) {
                    btcPtr++;
                }
                if (btcPtr >= 0) {
                    int btcWindowStart = Math.max(0, btcPtr + 1 - 200);
                    List<Candle> btcWindow = btcCandles.subList(btcWindowStart, btcPtr + 1);
                    btcGatePass = !com.cryptoautotrader.core.selector.BtcMarketGuard.check(btcWindow).triggered();
                }
            }

            // 다음 캔들 open에서 체결 (Look-Ahead Bias 방지)
            // BUY: 포지션 없고 pending 이월도 없을 때만 진입
            boolean rangeGatePass = !(regime == MarketRegime.RANGE && RangeRegimeGate.isBlocked(config.getStrategyName()));
            if (signal.getAction() == StrategySignal.Action.BUY
                    && position.compareTo(BigDecimal.ZERO) == 0
                    && pendingQuantity.compareTo(BigDecimal.ZERO) == 0
                    && Ema200RegimeGate.allowsBuy(window, config.getCoinPair())
                    && rangeGatePass
                    && btcGatePass) {

                // 손절폭(%) — 실전 세 엔진과 **같은 공식**을 쓴다(ExitRuleFormula, 2026-09-08).
                //
                // 그 전에는 여기서 ExitRuleChecker.resolveStopLossPct 를 썼는데, 이름만 비슷할 뿐
                // 다른 함수였다: atrStopLossEnabled 가 ExitRuleConfig 기본값 false 이고
                // RiskManagementService.toExitRuleConfig() 가 그 필드를 설정하지 않아 DB 로 켤 수단도
                // 없었다 — 그래서 백테스트는 ATR 을 넘겨받고도 쓰지 않고 **항상 5% 고정**으로 떨어졌다.
                // 실전은 clamp(ATR/가격 × 1.5, floor, 8%) 라 워치리스트 같은 고변동 알트에서 8% 까지
                // 넓어진다. 즉 **백테스트가 실전보다 훨씬 자주 손절**됐다.
                //
                // window 는 현재 캔들 i 까지만 담으므로 look-ahead 가 아니다(체결은 i+1 open).
                BigDecimal estimatedEntry = nextCandle.getOpen();
                BigDecimal slDistancePct = ExitRuleFormula.resolveStopLossPct(
                        config.getExitRuleConfig().getStopLossPct(), window, estimatedEntry, null);

                // 포지션 사이징: 가용 자금 × 투자 비율(기본) 또는 손절 거리 기반 리스크 사이징(옵트인)
                BigDecimal investAmount = exitChecker.calculateInvestAmount(capital, capital, slDistancePct);
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
                entryTime = nextCandle.getTime();
                entryFee = fee;
                capital = capital.subtract(executionPrice.multiply(orderQuantity)).subtract(fee);

                // SL/TP 초기값 — 실전 세 엔진과 동일한 규칙 (2026-09-08)
                //
                // 이전에는 ExitRuleChecker.calculateStopLevels 가 (1) SL 을 5% 고정으로 잡고
                // (2) TP 를 SL × 2 = 10% 로 **상한 없이** 키웠으며 (3) 전략 제안값을 그대로 채택했다.
                // TP 10% 는 실전이 결코 설정하지 않는 값이다 — ExitRuleFormula.TP_PCT_MAX 주석 그대로
                // "넓은 SL 은 반드시 맞고 넓은 TP 는 사실상 안 맞는다"(07-31 개편 후 5일 익절 0건/손절 3건).
                //
                // 전략 제안 SL 은 존중하되 **더 넓은 쪽**을 채택한다 — 제안값이 ATR 기준보다 타이트하면
                // 휩쏘로 이어진다. LiveTradingService 의 진입 블록과 같은 규칙이다.
                BigDecimal atrStopLossPrice = executionPrice.multiply(BigDecimal.ONE.subtract(
                                slDistancePct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                        .setScale(SCALE, RoundingMode.HALF_DOWN);
                stopLossPrice = (signal != null && signal.getSuggestedStopLoss() != null)
                        ? signal.getSuggestedStopLoss().min(atrStopLossPrice)
                        : atrStopLossPrice;
                takeProfitPrice = ExitRuleFormula.resolveTakeProfitPrice(executionPrice, stopLossPrice,
                        signal != null ? signal.getSuggestedTakeProfit() : null, null);

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
                // 전략 SELL 게이트(L-2) — 실전매매와 동일하게 최소보유시간·본전청산차단 적용.
                // SL/TP는 위에서 이미 별도 경로로 처리되므로 이 게이트와 무관하게 항상 동작한다.
                long heldMinutes = entryTime != null
                        ? Duration.between(entryTime, nextCandle.getTime()).toMinutes() : Long.MAX_VALUE;
                BigDecimal pnlPct = entryPrice.compareTo(BigDecimal.ZERO) > 0
                        ? currentCandle.getClose().subtract(entryPrice)
                                .divide(entryPrice, 6, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                        : BigDecimal.ZERO;
                if (!exitChecker.allowsSignalExit(heldMinutes, pnlPct)) {
                    continue; // 게이트 차단 — HOLD 취급
                }

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
                entryTime = null;
                entryFee = BigDecimal.ZERO;
                stopLossPrice = BigDecimal.ZERO;
                takeProfitPrice = BigDecimal.ZERO;
                // SELL 시 대기 중인 Partial Fill BUY 이월 취소
                pendingQuantity = BigDecimal.ZERO;
                pendingSide = null;
            }
        }

        PerformanceReport metrics = MetricsCalculator.calculate(trades, config.getInitialCapital());

        // 미청산 포지션 mark-to-market — 마지막 종가로 평가한다(강제청산하지 않음).
        // 실현 성과(metrics)는 청산된 거래만 반영하므로, 종료 시점에 열려 있던 포지션은
        // 별도 필드로 노출해 "청산 성과"와 "미청산 유지 성과"를 모두 볼 수 있게 한다.
        BigDecimal unrealizedPnl = BigDecimal.ZERO;
        BigDecimal openPositionValue = BigDecimal.ZERO;
        if (position.compareTo(BigDecimal.ZERO) > 0 && !candles.isEmpty()) {
            BigDecimal lastClose = candles.get(candles.size() - 1).getClose();
            openPositionValue = position.multiply(lastClose).setScale(SCALE, RoundingMode.HALF_UP);
            unrealizedPnl = lastClose.subtract(entryPrice).multiply(position)
                    .subtract(entryFee).setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal finalEquity = capital.add(openPositionValue).setScale(SCALE, RoundingMode.HALF_UP);

        return BacktestResult.builder()
                .config(config)
                .trades(trades)
                .metrics(metrics)
                .unrealizedPnl(unrealizedPnl)
                .openPositionValue(openPositionValue)
                .finalEquity(finalEquity)
                .build();
    }

    /**
     * 전략 신호의 방향만 뒤집는다 (BUY↔SELL, HOLD는 유지) — {@link BacktestConfig#isInvertSignals()} 전용.
     *
     * <p>reason에 접두어를 붙여 거래 로그에서 반전 실험임을 식별할 수 있게 한다.
     * confidence 등 나머지 필드는 그대로 보존한다.
     */
    private StrategySignal invert(StrategySignal signal) {
        StrategySignal.Action inverted = switch (signal.getAction()) {
            case BUY -> StrategySignal.Action.SELL;
            case SELL -> StrategySignal.Action.BUY;
            default -> signal.getAction();
        };
        if (inverted == signal.getAction()) {
            return signal;
        }
        return signal.toBuilder()
                .action(inverted)
                .reason("[반전] " + signal.getReason())
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
