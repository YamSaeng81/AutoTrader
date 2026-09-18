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
import com.cryptoautotrader.core.selector.CompositePresets;
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

    /**
     * 이름으로 전략을 지정하는 표준 경로.
     *
     * <p>팩토리가 등록된 전략은 <b>실행마다 전체 트리를 새로 생성</b>한다. 이전에는
     * {@code StrategyRegistry.get()}으로 공유 인스턴스를 가져왔고 초기화도 하지 않아,
     * GRID의 activeLevels·MACD_STOCH_BB의 쿨다운·복합 전략 내부 레짐 감지기 상태가
     * 실행 사이에 남았다. 실행 순서에 따라 결과가 달라지고 병렬 백테스트가 서로 간섭했으며,
     * Walk-Forward에서는 IS에서 만들어진 상태가 OOS 시작점으로 넘어가 독립성을 훼손했다.
     *
     * <p>공유 객체에 resetState()만 부르는 방식으로는 병렬 실행 격리가 되지 않으므로
     * 새 인스턴스를 뽑는다.
     */
    public BacktestResult run(BacktestConfig config, List<Candle> candles) {
        // 이름으로 찾기 전에 복합 프리셋 등록을 보장한다 — 클래스 로딩 순서에 의존하지 않는다.
        CompositePresets.ensureRegistered();
        String name = config.getStrategyName();
        Strategy strategy = StrategyRegistry.hasFactory(name)
                ? StrategyRegistry.createNew(name)
                : StrategyRegistry.get(name);
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
            // timeframe도 LIVE/DYNAMIC/PAPER와 동일하게 주입한다 (Wave0-F).
            // 없으면 COMPOSITE 계열이 WeightOverrideStore를 coin/regime 수준으로만 조회해
            // H1/M15별 가중치 override가 백테스트에서만 무시된다 — 같은 전략·기간이라도
            // 운영과 다른 가중치로 평가되어 성과 비교의 전제가 깨진다.
            if (config.getTimeframe() != null) {
                evalParams.put("timeframe", config.getTimeframe());
            }
            StrategySignal signal = strategy.evaluate(window, evalParams);
            if (config.isInvertSignals()) {
                signal = invert(signal);
            }
            MarketRegime regime = regimeDetector.detect(window);

            // BTC_MARKET_GUARD — 체결 시점(nextCandle.open)에 **이미 종료된** BTC 캔들만 본다.
            //
            // 이전에는 시각이 nextCandle.time 이하인 BTC 캔들까지 포함했다. Candle.time 은 캔들
            // **시작** 시각이므로, nextCandle.time 에 시작하는 BTC 캔들이 들어갔다 — 그 봉의 종가·고가는
            // 체결 순간에 아직 정해지지 않았는데 Guard 는 그것으로 판정했다. 그 봉 후반의 BTC 급락을
            // 미리 알고 진입을 피하는 look-ahead bias 다.
            //
            // 종료 시각 = 시작 시각 + 캔들 간격. 간격은 대상 코인 캔들에서 직접 재므로(BTC 캔들은
            // 같은 timeframe 으로 조회된다) 별도 파싱이 필요 없고, 결측봉이 있으면 간격이 커져
            // 더 보수적으로(=미확정 데이터를 더 배제하는 쪽으로) 동작한다.
            boolean btcGatePass = true;
            if (btcCandles != null && !btcCandles.isEmpty()) {
                Duration candleInterval = Duration.between(currentCandle.getTime(), nextCandle.getTime());
                Instant lastClosedStart = nextCandle.getTime().minus(candleInterval);
                while (btcPtr + 1 < btcCandles.size()
                        && !btcCandles.get(btcPtr + 1).getTime().isAfter(lastClosedStart)) {
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

        // ── 기간 종료 시 미청산 포지션 강제청산 ──────────────────────────────
        //
        // 이전에는 청산된 SELL 거래만으로 metrics 를 계산하고, 열린 포지션은 unrealizedPnl /
        // finalEquity 에만 반영했다. 그 결과 **표시되는 finalEquity 와 전략 순위·Walk-Forward
        // 판정에 쓰는 totalReturn 이 서로 다른 손익 범위를 재고 있었다** — totalReturn 이 0 이나
        // 양수인데 finalEquity 는 초기자본보다 낮은 상태가 가능했다. 창마다 자본을 초기화하는
        // Walk-Forward 는 종료 시점의 미청산 손실을 매 창 버려, 오래 들고 버티는 전략에 유리하게
        // 편향된다.
        //
        // 마지막 종가로 청산하되 수수료·슬리피지를 실제 청산과 동일하게 적용한다. 청산을
        // '없던 일'로 하면 비용 없이 탈출하는 셈이 되어 또 다른 편향이 생긴다.
        BigDecimal unrealizedPnl = BigDecimal.ZERO;
        if (position.compareTo(BigDecimal.ZERO) > 0 && !candles.isEmpty()) {
            Candle lastCandle = candles.get(candles.size() - 1);
            BigDecimal exitPrice = applySlippage(lastCandle.getClose(), OrderSide.SELL, config.getSlippagePct());

            TradeRecord forcedExit = executeTrade(OrderSide.SELL, exitPrice, position,
                    config.getFeePct(), config.getSlippagePct(),
                    "기간 종료 강제청산 (mark-to-market)", null, lastCandle, cumulativePnl, entryPrice);

            trades.add(forcedExit);
            cumulativePnl = forcedExit.getCumulativePnl();
            capital = capital.add(exitPrice.multiply(position)).subtract(forcedExit.getFee());
            // 진입 수수료까지 반영한 순손익 — 보고용으로 남긴다.
            unrealizedPnl = forcedExit.getPnl().subtract(entryFee).setScale(SCALE, RoundingMode.HALF_UP);
            position = BigDecimal.ZERO;
        }

        // 강제청산 이후이므로 열린 포지션은 없다. 두 값이 같은 손익 범위를 재도록 맞춘다.
        PerformanceReport metrics = MetricsCalculator.calculate(trades, config.getInitialCapital());
        BigDecimal openPositionValue = BigDecimal.ZERO;
        BigDecimal finalEquity = capital.setScale(SCALE, RoundingMode.HALF_UP);

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
