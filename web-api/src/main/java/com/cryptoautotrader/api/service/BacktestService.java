package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.BacktestMetricsEntity;
import com.cryptoautotrader.api.entity.BacktestRunEntity;
import com.cryptoautotrader.api.entity.BacktestTradeEntity;
import com.cryptoautotrader.api.entity.CandleDataEntity;
import com.cryptoautotrader.api.repository.BacktestMetricsRepository;
import com.cryptoautotrader.api.repository.BacktestRunRepository;
import com.cryptoautotrader.api.repository.BacktestTradeRepository;
import com.cryptoautotrader.api.repository.CandleDataRepository;
import com.cryptoautotrader.core.backtest.BacktestConfig;
import com.cryptoautotrader.core.backtest.BacktestEngine;
import com.cryptoautotrader.core.backtest.BacktestResult;
import com.cryptoautotrader.core.backtest.WalkForwardTestRunner;
import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.core.selector.CompositeStrategy;
import com.cryptoautotrader.core.selector.StrategySelector;
import com.cryptoautotrader.core.selector.WeightedStrategy;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.atrbreakout.AtrBreakoutStrategy;
import com.cryptoautotrader.strategy.ema.EmaCrossStrategy;
import com.cryptoautotrader.strategy.orderbook.OrderbookImbalanceStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestService {

    private final CandleDataRepository candleDataRepository;
    private final BacktestRunRepository backtestRunRepository;
    private final BacktestMetricsRepository backtestMetricsRepository;
    private final BacktestTradeRepository backtestTradeRepository;
    private final PlatformTransactionManager transactionManager;

    private final BacktestEngine backtestEngine = new BacktestEngine();
    private final WalkForwardTestRunner walkForwardRunner = new WalkForwardTestRunner();

    @Transactional
    public Map<String, Object> runBacktest(String strategyType, String coinPair, String timeframe,
                                            LocalDate startDate, LocalDate endDate,
                                            BigDecimal initialCapital, BigDecimal slippagePct,
                                            BigDecimal feePct, Map<String, Object> strategyParams,
                                            boolean fillSimEnabled, BigDecimal impactFactor, BigDecimal fillRatio) {

        Instant start = startDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant end = endDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        // DB에서 캔들 조회
        List<CandleDataEntity> entities = candleDataRepository.findCandles(coinPair, timeframe, start, end);
        if (entities.size() < 30) {
            throw new IllegalArgumentException("데이터 부족: " + entities.size() + "건 (최소 30건 필요)");
        }

        List<Candle> candles = entities.stream()
                .map(e -> Candle.builder()
                        .time(e.getTime())
                        .open(e.getOpen()).high(e.getHigh())
                        .low(e.getLow()).close(e.getClose())
                        .volume(e.getVolume())
                        .build())
                .toList();

        BacktestConfig config = BacktestConfig.builder()
                .strategyName(strategyType)
                .coinPair(coinPair)
                .timeframe(timeframe)
                .startDate(start)
                .endDate(end)
                .initialCapital(initialCapital != null ? initialCapital : new BigDecimal("10000000"))
                .slippagePct(slippagePct != null ? slippagePct : new BigDecimal("0.1"))
                .feePct(feePct != null ? feePct : new BigDecimal("0.05"))
                .strategyParams(strategyParams != null ? strategyParams : Map.of())
                .fillSimulationEnabled(fillSimEnabled)
                .impactFactor(impactFactor != null ? impactFactor : new BigDecimal("0.1"))
                .fillRatio(fillRatio != null ? fillRatio : new BigDecimal("0.3"))
                .build();

        BacktestResult result;
        if ("COMPOSITE".equals(strategyType)) {
            MarketRegimeDetector detector = new MarketRegimeDetector();
            List<WeightedStrategy> weighted = StrategySelector.select(detector.detect(candles));
            result = backtestEngine.run(config, candles, new CompositeStrategy(weighted));
        } else if ("COMPOSITE_ETH".equals(strategyType)) {
            // 백테스트에서 ORDERBOOK_IMBALANCE는 캔들 근사값을 사용하므로 가중치를 축소
            // Live: ATR(0.5) + OB(0.3) + EMA(0.2) → BT: ATR(0.7) + OB(0.1) + EMA(0.2)
            result = backtestEngine.run(config, candles, compositeEthBt());
        } else {
            result = backtestEngine.run(config, candles);
        }

        // DB 저장
        BacktestRunEntity runEntity = saveRun(config, false);
        saveMetrics(runEntity.getId(), result.getMetrics());
        saveTrades(runEntity.getId(), result.getTrades());

        return buildResponse(runEntity.getId(), result.getMetrics());
    }

    @Transactional
    public Map<String, Object> runWalkForward(String strategyType, String coinPair, String timeframe,
                                               LocalDate startDate, LocalDate endDate,
                                               double inSampleRatio, int windowCount,
                                               BigDecimal initialCapital, BigDecimal slippagePct,
                                               BigDecimal feePct, Map<String, Object> strategyParams) {

        Instant start = startDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant end = endDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        List<CandleDataEntity> entities = candleDataRepository.findCandles(coinPair, timeframe, start, end);
        if (entities.size() < 100) {
            throw new IllegalArgumentException("Walk Forward에 데이터 부족: " + entities.size() + "건");
        }

        List<Candle> candles = entities.stream()
                .map(e -> Candle.builder()
                        .time(e.getTime())
                        .open(e.getOpen()).high(e.getHigh())
                        .low(e.getLow()).close(e.getClose())
                        .volume(e.getVolume())
                        .build())
                .toList();

        BacktestConfig config = BacktestConfig.builder()
                .strategyName(strategyType)
                .coinPair(coinPair)
                .timeframe(timeframe)
                .startDate(start)
                .endDate(end)
                .initialCapital(initialCapital != null ? initialCapital : new BigDecimal("10000000"))
                .slippagePct(slippagePct != null ? slippagePct : new BigDecimal("0.1"))
                .feePct(feePct != null ? feePct : new BigDecimal("0.05"))
                .strategyParams(strategyParams != null ? strategyParams : Map.of())
                .build();

        WalkForwardTestRunner.WalkForwardResult wfResult = walkForwardRunner.run(config, candles, inSampleRatio, windowCount);

        // DB 저장 (Walk Forward 결과)
        BacktestRunEntity runEntity = saveRun(config, true);

        Map<String, Object> response = new HashMap<>();
        response.put("id", runEntity.getId());
        response.put("windows", wfResult.getWindows().stream().map(w -> {
            Map<String, Object> windowMap = new HashMap<>();
            windowMap.put("windowIndex", w.getWindowIndex());
            windowMap.put("inSample", metricsToMap(w.getInSampleMetrics()));
            windowMap.put("outSample", metricsToMap(w.getOutSampleMetrics()));
            return windowMap;
        }).toList());
        response.put("overfittingScore", wfResult.getOverfittingScore());
        response.put("verdict", wfResult.getVerdict());

        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getBacktestResult(Long id) {
        BacktestRunEntity run = backtestRunRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("백테스트 결과 없음: id=" + id));
        BacktestMetricsEntity metrics = backtestMetricsRepository.findByBacktestRunIdAndSegment(id, "FULL")
                .orElse(null);

        Map<String, Object> result = new HashMap<>();
        result.put("id", run.getId());
        result.put("strategyType", run.getStrategyName());
        result.put("coinPair", run.getCoinPair());
        result.put("timeframe", run.getTimeframe());
        result.put("startDate", run.getStartDate());
        result.put("endDate", run.getEndDate());
        result.put("initialCapital", run.getInitialCapital());
        result.put("status", "COMPLETED");
        result.put("createdAt", run.getCreatedAt());
        if (metrics != null) {
            result.put("metrics", entityToMetricsMap(metrics));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listBacktests() {
        return backtestRunRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(run -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", run.getId());
                    map.put("strategyType", run.getStrategyName());
                    map.put("coinPair", run.getCoinPair());
                    map.put("timeframe", run.getTimeframe());
                    map.put("startDate", run.getStartDate());
                    map.put("endDate", run.getEndDate());
                    map.put("initialCapital", run.getInitialCapital());
                    map.put("status", "COMPLETED");
                    map.put("createdAt", run.getCreatedAt());
                    backtestMetricsRepository.findByBacktestRunIdAndSegment(run.getId(), "FULL")
                            .ifPresent(m -> map.put("metrics", entityToMetricsMap(m)));
                    return map;
                })
                .toList();
    }

    /**
     * 백테스트 단건 삭제. 연관된 metrics, trades 함께 삭제.
     * 존재하지 않는 id면 IllegalArgumentException 발생.
     */
    @Transactional
    public void deleteBacktest(Long id) {
        if (!backtestRunRepository.existsById(id)) {
            throw new IllegalArgumentException("백테스트 결과 없음: id=" + id);
        }
        backtestTradeRepository.deleteByBacktestRunId(id);
        backtestMetricsRepository.deleteByBacktestRunId(id);
        backtestRunRepository.deleteById(id);
        log.info("백테스트 삭제 완료: id={}", id);
    }

    /**
     * 백테스트 다건 삭제. 존재하지 않는 id는 무시.
     */
    @Transactional
    public void bulkDeleteBacktests(List<Long> ids) {
        backtestTradeRepository.deleteByBacktestRunIdIn(ids);
        backtestMetricsRepository.deleteByBacktestRunIdIn(ids);
        backtestRunRepository.deleteAllByIdInBatch(ids);
        log.info("백테스트 다건 삭제 완료: ids={}", ids);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> compareBacktests(List<Long> ids) {
        return backtestRunRepository.findByIdIn(ids).stream()
                .map(run -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", run.getId());
                    map.put("strategyType", run.getStrategyName());
                    BacktestMetricsEntity metrics = backtestMetricsRepository
                            .findByBacktestRunIdAndSegment(run.getId(), "FULL").orElse(null);
                    if (metrics != null) {
                        map.putAll(entityToMetricsMap(metrics));
                    }
                    return map;
                })
                .toList();
    }

    /**
     * 사용자가 선택한 전략 목록 × 단일 코인 백테스트 비교표를 반환한다.
     * 각 결과는 DB에 저장되며, totalReturn 내림차순으로 정렬된다.
     * 전략별로 독립 트랜잭션 사용 — 한 전략 저장 실패가 다른 전략 결과에 영향을 주지 않는다.
     */
    public List<Map<String, Object>> runMultiStrategyBacktest(
            List<String> strategyTypes, String coinPair, String timeframe,
            LocalDate startDate, LocalDate endDate,
            BigDecimal initialCapital, BigDecimal slippagePct, BigDecimal feePct) {

        Instant start = startDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant end   = endDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        BigDecimal capital  = initialCapital != null ? initialCapital : new BigDecimal("10000000");
        BigDecimal slippage = slippagePct    != null ? slippagePct    : new BigDecimal("0.1");
        BigDecimal fee      = feePct         != null ? feePct         : new BigDecimal("0.05");

        List<CandleDataEntity> entities = candleDataRepository.findCandles(coinPair, timeframe, start, end);
        if (entities.size() < 30) {
            throw new IllegalArgumentException("데이터 부족: " + entities.size() + "건 (최소 30건 필요)");
        }

        List<Candle> candles = entities.stream()
                .map(e -> Candle.builder()
                        .time(e.getTime())
                        .open(e.getOpen()).high(e.getHigh())
                        .low(e.getLow()).close(e.getClose())
                        .volume(e.getVolume())
                        .build())
                .toList();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<Map<String, Object>> results = new java.util.ArrayList<>();

        for (String strategyName : strategyTypes) {
            try {
                BacktestConfig config = BacktestConfig.builder()
                        .strategyName(strategyName)
                        .coinPair(coinPair)
                        .timeframe(timeframe)
                        .startDate(start)
                        .endDate(end)
                        .initialCapital(capital)
                        .slippagePct(slippage)
                        .feePct(fee)
                        .strategyParams(Map.of())
                        .build();

                BacktestResult result;
                if ("COMPOSITE".equals(strategyName)) {
                    MarketRegimeDetector detector = new MarketRegimeDetector();
                    List<WeightedStrategy> weighted = StrategySelector.select(detector.detect(candles));
                    result = backtestEngine.run(config, candles, new CompositeStrategy(weighted));
                } else if ("COMPOSITE_ETH".equals(strategyName)) {
                    result = backtestEngine.run(config, candles, compositeEthBt());
                } else {
                    result = backtestEngine.run(config, candles);
                }
                PerformanceReport metrics = result.getMetrics();

                // 전략별 독립 트랜잭션 — 저장 실패가 다른 전략 결과를 오염시키지 않음
                final BacktestResult finalResult = result;
                Long savedId = tx.execute(status -> {
                    BacktestRunEntity runEntity = saveRun(config, false);
                    saveMetrics(runEntity.getId(), metrics);
                    saveTrades(runEntity.getId(), finalResult.getTrades());
                    return runEntity.getId();
                });

                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("id",           savedId);
                row.put("strategy",     strategyName);
                row.put("coinPair",     coinPair);
                row.put("totalReturn",  metrics.getTotalReturnPct());
                row.put("winRate",      metrics.getWinRatePct());
                row.put("maxDrawdown",  metrics.getMddPct());
                row.put("sharpe",       metrics.getSharpeRatio());
                row.put("sortino",      metrics.getSortinoRatio());
                row.put("calmar",       metrics.getCalmarRatio());
                row.put("totalTrades",  metrics.getTotalTrades());
                row.put("winLossRatio", metrics.getWinLossRatio());
                results.add(row);

                log.info("다중전략 백테스트 완료: {} {} → 수익률={}, 승률={}, MDD={}",
                        strategyName, coinPair,
                        metrics.getTotalReturnPct(), metrics.getWinRatePct(), metrics.getMddPct());

            } catch (Exception e) {
                log.error("다중전략 백테스트 실패: {} {} — {}", strategyName, coinPair, e.getMessage());
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("strategy", strategyName);
                row.put("coinPair", coinPair);
                row.put("error",    e.getMessage());
                results.add(row);
            }
        }

        results.sort((a, b) -> {
            BigDecimal ra = (BigDecimal) a.getOrDefault("totalReturn", null);
            BigDecimal rb = (BigDecimal) b.getOrDefault("totalReturn", null);
            if (ra == null && rb == null) return 0;
            if (ra == null) return 1;
            if (rb == null) return -1;
            return rb.compareTo(ra);
        });

        return results;
    }

    /**
     * 전략 10종 × 지정 코인 목록을 일괄 백테스트하여 성과 비교표를 반환한다.
     * 각 결과는 DB에도 저장된다.
     * 정렬 기준: totalReturn 내림차순
     */
    public List<Map<String, Object>> runBulkBacktest(
            List<String> coins, String timeframe,
            LocalDate startDate, LocalDate endDate,
            BigDecimal initialCapital, BigDecimal slippagePct, BigDecimal feePct) {

        Instant start = startDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant end   = endDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        BigDecimal capital   = initialCapital != null ? initialCapital : new BigDecimal("10000000");
        BigDecimal slippage  = slippagePct    != null ? slippagePct    : new BigDecimal("0.1");
        BigDecimal fee       = feePct         != null ? feePct         : new BigDecimal("0.05");

        List<String> strategyNames = new java.util.ArrayList<>(StrategyRegistry.getAll().keySet());
        List<Map<String, Object>> results = new java.util.ArrayList<>();

        for (String coin : coins) {
            List<CandleDataEntity> entities = candleDataRepository.findCandles(coin, timeframe, start, end);
            if (entities.size() < 30) {
                log.warn("벌크 백테스트 건너뜀: {} {} 데이터 부족 ({}건)", coin, timeframe, entities.size());
                continue;
            }

            List<Candle> candles = entities.stream()
                    .map(e -> Candle.builder()
                            .time(e.getTime())
                            .open(e.getOpen()).high(e.getHigh())
                            .low(e.getLow()).close(e.getClose())
                            .volume(e.getVolume())
                            .build())
                    .toList();

            for (String strategyName : strategyNames) {
                try {
                    BacktestConfig config = BacktestConfig.builder()
                            .strategyName(strategyName)
                            .coinPair(coin)
                            .timeframe(timeframe)
                            .startDate(start)
                            .endDate(end)
                            .initialCapital(capital)
                            .slippagePct(slippage)
                            .feePct(fee)
                            .strategyParams(Map.of())
                            .build();

                    BacktestResult result;
                    if ("COMPOSITE".equals(strategyName)) {
                        MarketRegimeDetector detector = new MarketRegimeDetector();
                        List<WeightedStrategy> weighted = StrategySelector.select(detector.detect(candles));
                        result = backtestEngine.run(config, candles, new CompositeStrategy(weighted));
                    } else if ("COMPOSITE_ETH".equals(strategyName)) {
                        result = backtestEngine.run(config, candles, compositeEthBt());
                    } else {
                        result = backtestEngine.run(config, candles);
                    }
                    PerformanceReport metrics = result.getMetrics();

                    BacktestRunEntity runEntity = saveRun(config, false);
                    saveMetrics(runEntity.getId(), metrics);
                    saveTrades(runEntity.getId(), result.getTrades());

                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id",          runEntity.getId());
                    row.put("coin",        coin);
                    row.put("strategy",    strategyName);
                    row.put("totalReturn", metrics.getTotalReturnPct());
                    row.put("winRate",     metrics.getWinRatePct());
                    row.put("maxDrawdown", metrics.getMddPct());
                    row.put("sharpe",      metrics.getSharpeRatio());
                    row.put("sortino",     metrics.getSortinoRatio());
                    row.put("calmar",      metrics.getCalmarRatio());
                    row.put("totalTrades", metrics.getTotalTrades());
                    row.put("winLossRatio",metrics.getWinLossRatio());
                    results.add(row);

                    log.info("벌크 백테스트 완료: {} {} → 수익률={}, 승률={}, MDD={}",
                            coin, strategyName,
                            metrics.getTotalReturnPct(), metrics.getWinRatePct(), metrics.getMddPct());

                } catch (Exception e) {
                    log.error("벌크 백테스트 실패: {} {} — {}", coin, strategyName, e.getMessage());
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("coin",     coin);
                    row.put("strategy", strategyName);
                    row.put("error",    e.getMessage());
                    results.add(row);
                }
            }
        }

        // totalReturn 내림차순 정렬 (에러 행은 맨 뒤)
        results.sort((a, b) -> {
            BigDecimal ra = (BigDecimal) a.getOrDefault("totalReturn", null);
            BigDecimal rb = (BigDecimal) b.getOrDefault("totalReturn", null);
            if (ra == null && rb == null) return 0;
            if (ra == null) return 1;
            if (rb == null) return -1;
            return rb.compareTo(ra);
        });

        return results;
    }

    /**
     * 백테스트 전용 COMPOSITE_ETH 인스턴스.
     * ORDERBOOK_IMBALANCE는 실시간 호가창이 없어 캔들 근사값을 사용하므로 가중치를 축소한다.
     * Live: ATR(0.5) + OB(0.3) + EMA(0.2)  →  BT: ATR(0.7) + OB(0.1) + EMA(0.2)
     */
    private CompositeStrategy compositeEthBt() {
        return new CompositeStrategy("COMPOSITE_ETH", List.of(
                new WeightedStrategy(new AtrBreakoutStrategy(),        0.7),
                new WeightedStrategy(new OrderbookImbalanceStrategy(), 0.1),
                new WeightedStrategy(new EmaCrossStrategy(),           0.2)
        ));
    }

    private BacktestRunEntity saveRun(BacktestConfig config, boolean isWalkForward) {
        BacktestRunEntity entity = BacktestRunEntity.builder()
                .strategyName(config.getStrategyName())
                .coinPair(config.getCoinPair())
                .timeframe(config.getTimeframe())
                .startDate(config.getStartDate())
                .endDate(config.getEndDate())
                .initialCapital(config.getInitialCapital())
                .slippagePct(config.getSlippagePct())
                .feePct(config.getFeePct())
                .configJson(config.getStrategyParams())
                .isWalkForward(isWalkForward)
                .build();
        return backtestRunRepository.save(entity);
    }

    private void saveMetrics(Long runId, PerformanceReport report) {
        BacktestMetricsEntity entity = BacktestMetricsEntity.builder()
                .backtestRunId(runId)
                .totalReturnPct(report.getTotalReturnPct())
                .winRatePct(report.getWinRatePct())
                .mddPct(report.getMddPct())
                .sharpeRatio(report.getSharpeRatio())
                .sortinoRatio(report.getSortinoRatio())
                .calmarRatio(report.getCalmarRatio())
                .winLossRatio(report.getWinLossRatio())
                .recoveryFactor(report.getRecoveryFactor())
                .totalTrades(report.getTotalTrades())
                .winningTrades(report.getWinningTrades())
                .losingTrades(report.getLosingTrades())
                .avgProfitPct(report.getAvgProfitPct())
                .avgLossPct(report.getAvgLossPct())
                .maxConsecutiveLoss(report.getMaxConsecutiveLoss())
                .monthlyReturnsJson(report.getMonthlyReturns())
                .segment(report.getSegment())
                .build();
        backtestMetricsRepository.save(entity);
    }

    private void saveTrades(Long runId, List<TradeRecord> trades) {
        List<BacktestTradeEntity> entities = trades.stream()
                .map(t -> BacktestTradeEntity.builder()
                        .backtestRunId(runId)
                        .side(t.getSide().name())
                        .price(t.getPrice())
                        .quantity(t.getQuantity())
                        .fee(t.getFee())
                        .slippage(t.getSlippage())
                        .pnl(t.getPnl())
                        .cumulativePnl(t.getCumulativePnl())
                        .signalReason(t.getSignalReason())
                        .marketRegime(t.getMarketRegime())
                        .executedAt(t.getExecutedAt())
                        .build())
                .toList();
        backtestTradeRepository.saveAll(entities);
    }

    private Map<String, Object> buildResponse(Long id, PerformanceReport report) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", id);
        response.put("status", "COMPLETED");
        response.put("metrics", metricsToMap(report));
        return response;
    }

    private Map<String, Object> metricsToMap(PerformanceReport report) {
        Map<String, Object> map = new HashMap<>();
        map.put("totalReturn", report.getTotalReturnPct());
        map.put("winRate", report.getWinRatePct());
        map.put("maxDrawdown", report.getMddPct());
        map.put("sharpeRatio", report.getSharpeRatio());
        map.put("sortinoRatio", report.getSortinoRatio());
        map.put("calmarRatio", report.getCalmarRatio());
        map.put("winLossRatio", report.getWinLossRatio());
        map.put("recoveryFactor", report.getRecoveryFactor());
        map.put("totalTrades", report.getTotalTrades());
        map.put("maxConsecutiveLoss", report.getMaxConsecutiveLoss());
        map.put("monthlyReturns", report.getMonthlyReturns());
        return map;
    }

    private Map<String, Object> entityToMetricsMap(BacktestMetricsEntity m) {
        Map<String, Object> map = new HashMap<>();
        map.put("totalReturn", m.getTotalReturnPct());
        map.put("winRate", m.getWinRatePct());
        map.put("maxDrawdown", m.getMddPct());
        map.put("sharpeRatio", m.getSharpeRatio());
        map.put("sortinoRatio", m.getSortinoRatio());
        map.put("calmarRatio", m.getCalmarRatio());
        map.put("winLossRatio", m.getWinLossRatio());
        map.put("recoveryFactor", m.getRecoveryFactor());
        map.put("totalTrades", m.getTotalTrades());
        map.put("maxConsecutiveLoss", m.getMaxConsecutiveLoss());
        map.put("monthlyReturns", m.getMonthlyReturnsJson());
        return map;
    }
}
