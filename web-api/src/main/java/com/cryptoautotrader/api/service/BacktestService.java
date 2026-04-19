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
import com.cryptoautotrader.strategy.rsi.RsiStrategy;
import com.cryptoautotrader.strategy.volumedelta.VolumeDeltaStrategy;
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
import java.util.Comparator;
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
    private final RiskManagementService riskManagementService;

    private final BacktestEngine backtestEngine = new BacktestEngine();
    private final WalkForwardTestRunner walkForwardRunner = new WalkForwardTestRunner();

    private static final BigDecimal DEFAULT_CAPITAL  = new BigDecimal("10000000");
    private static final BigDecimal DEFAULT_SLIPPAGE = new BigDecimal("0.1");
    private static final BigDecimal DEFAULT_FEE      = new BigDecimal("0.05");

    private static final Comparator<Map<String, Object>> BY_TOTAL_RETURN = (a, b) -> {
        BigDecimal ra = (BigDecimal) a.getOrDefault("totalReturn", null);
        BigDecimal rb = (BigDecimal) b.getOrDefault("totalReturn", null);
        if (ra == null && rb == null) return 0;
        if (ra == null) return 1;
        if (rb == null) return -1;
        return rb.compareTo(ra);
    };

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

        List<Candle> candles = toCandles(entities);

        BacktestConfig config = BacktestConfig.builder()
                .strategyName(strategyType)
                .coinPair(coinPair)
                .timeframe(timeframe)
                .startDate(start)
                .endDate(end)
                .initialCapital(initialCapital != null ? initialCapital : DEFAULT_CAPITAL)
                .slippagePct(slippagePct != null ? slippagePct : DEFAULT_SLIPPAGE)
                .feePct(feePct != null ? feePct : DEFAULT_FEE)
                .strategyParams(strategyParams != null ? strategyParams : Map.of())
                .fillSimulationEnabled(fillSimEnabled)
                .impactFactor(impactFactor != null ? impactFactor : new BigDecimal("0.1"))
                .fillRatio(fillRatio != null ? fillRatio : new BigDecimal("0.3"))
                .exitRuleConfig(riskManagementService.getExitRuleConfig())
                .build();

        BacktestResult result = runStrategy(config, candles, strategyType);

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

        List<Candle> candles = toCandles(entities);

        BacktestConfig config = BacktestConfig.builder()
                .strategyName(strategyType)
                .coinPair(coinPair)
                .timeframe(timeframe)
                .startDate(start)
                .endDate(end)
                .initialCapital(initialCapital != null ? initialCapital : DEFAULT_CAPITAL)
                .slippagePct(slippagePct != null ? slippagePct : DEFAULT_SLIPPAGE)
                .feePct(feePct != null ? feePct : DEFAULT_FEE)
                .strategyParams(strategyParams != null ? strategyParams : Map.of())
                .exitRuleConfig(riskManagementService.getExitRuleConfig())
                .build();

        WalkForwardTestRunner.WalkForwardResult wfResult = walkForwardRunner.run(config, candles, inSampleRatio, windowCount);

        // 응답 구성 (날짜 포함)
        List<Map<String, Object>> windowMaps = wfResult.getWindows().stream().map(w -> {
            Map<String, Object> windowMap = new HashMap<>();
            windowMap.put("windowIndex", w.getWindowIndex());
            Map<String, Object> inMap = metricsToMap(w.getInSampleMetrics());
            inMap.put("start", w.getInSampleStart() != null ? w.getInSampleStart().toString() : null);
            inMap.put("end",   w.getInSampleEnd()   != null ? w.getInSampleEnd().toString()   : null);
            Map<String, Object> outMap = metricsToMap(w.getOutSampleMetrics());
            outMap.put("start", w.getOutSampleStart() != null ? w.getOutSampleStart().toString() : null);
            outMap.put("end",   w.getOutSampleEnd()   != null ? w.getOutSampleEnd().toString()   : null);
            windowMap.put("inSample",  inMap);
            windowMap.put("outSample", outMap);
            return windowMap;
        }).toList();

        Map<String, Object> response = new HashMap<>();
        response.put("windows", windowMaps);
        response.put("overfittingScore", wfResult.getOverfittingScore());
        response.put("verdict", wfResult.getVerdict());
        response.put("mode", wfResult.getMode() != null ? wfResult.getMode().name() : "ROLLING");
        response.put("aggregatedOutSample", wfResult.getAggregatedOutSampleMetrics() != null
                ? metricsToMap(wfResult.getAggregatedOutSampleMetrics()) : null);
        response.put("strategyType", strategyType);
        response.put("coinPair", coinPair);
        response.put("timeframe", timeframe);
        response.put("inSampleRatio", inSampleRatio);
        response.put("windowCount", windowCount);

        // DB 저장 — 전체 결과 JSON 포함
        BacktestRunEntity runEntity = saveRun(config, true);
        runEntity.setWfResultJson(response);
        backtestRunRepository.save(runEntity);
        response.put("id", runEntity.getId());
        response.put("createdAt", runEntity.getCreatedAt() != null ? runEntity.getCreatedAt().toString() : null);

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
                .filter(run -> !Boolean.TRUE.equals(run.getIsWalkForward()))
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

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listWalkForwardHistory() {
        return backtestRunRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(run -> Boolean.TRUE.equals(run.getIsWalkForward()))
                .map(run -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", run.getId());
                    map.put("strategyType", run.getStrategyName());
                    map.put("coinPair", run.getCoinPair());
                    map.put("timeframe", run.getTimeframe());
                    map.put("startDate", run.getStartDate());
                    map.put("endDate", run.getEndDate());
                    map.put("createdAt", run.getCreatedAt());
                    if (run.getWfResultJson() != null) {
                        map.put("overfittingScore", run.getWfResultJson().get("overfittingScore"));
                        map.put("verdict", run.getWfResultJson().get("verdict"));
                        map.put("windowCount", run.getWfResultJson().get("windowCount"));
                        map.put("inSampleRatio", run.getWfResultJson().get("inSampleRatio"));
                        map.put("windows", run.getWfResultJson().get("windows"));
                    }
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
                    map.put("id",           run.getId());
                    map.put("strategyType", run.getStrategyName());
                    map.put("coinPair",     run.getCoinPair());
                    map.put("timeframe",    run.getTimeframe());
                    map.put("startDate",    run.getStartDate());
                    map.put("endDate",      run.getEndDate());
                    map.put("status",       "COMPLETED");
                    backtestMetricsRepository
                            .findByBacktestRunIdAndSegment(run.getId(), "FULL")
                            .ifPresent(m -> map.put("metrics", entityToMetricsMap(m)));
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

        BigDecimal capital  = initialCapital != null ? initialCapital : DEFAULT_CAPITAL;
        BigDecimal slippage = slippagePct    != null ? slippagePct    : DEFAULT_SLIPPAGE;
        BigDecimal fee      = feePct         != null ? feePct         : DEFAULT_FEE;

        List<CandleDataEntity> entities = candleDataRepository.findCandles(coinPair, timeframe, start, end);
        if (entities.size() < 30) {
            throw new IllegalArgumentException("데이터 부족: " + entities.size() + "건 (최소 30건 필요)");
        }

        List<Candle> candles = toCandles(entities);

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
                        .exitRuleConfig(riskManagementService.getExitRuleConfig())
                        .build();

                BacktestResult result = runStrategy(config, candles, strategyName);
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

        results.sort(BY_TOTAL_RETURN);

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

        BigDecimal capital   = initialCapital != null ? initialCapital : DEFAULT_CAPITAL;
        BigDecimal slippage  = slippagePct    != null ? slippagePct    : DEFAULT_SLIPPAGE;
        BigDecimal fee       = feePct         != null ? feePct         : DEFAULT_FEE;

        List<String> strategyNames = new java.util.ArrayList<>(StrategyRegistry.getAll().keySet());
        List<Map<String, Object>> results = new java.util.ArrayList<>();

        for (String coin : coins) {
            List<CandleDataEntity> entities = candleDataRepository.findCandles(coin, timeframe, start, end);
            if (entities.size() < 30) {
                log.warn("벌크 백테스트 건너뜀: {} {} 데이터 부족 ({}건)", coin, timeframe, entities.size());
                continue;
            }

            List<Candle> candles = toCandles(entities);

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
                            .exitRuleConfig(riskManagementService.getExitRuleConfig())
                            .build();

                    BacktestResult result = runStrategy(config, candles, strategyName);
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
        results.sort(BY_TOTAL_RETURN);

        return results;
    }

    /**
     * 선택 코인 × 선택 전략 배치 백테스트.
     * 모든 (coinPair × strategyType) 조합에 대해 독립 실행 후 totalReturn 내림차순으로 반환한다.
     */
    public List<Map<String, Object>> runBatchBacktest(
            List<String> coinPairs, List<String> strategyTypes, String timeframe,
            LocalDate startDate, LocalDate endDate,
            BigDecimal initialCapital, BigDecimal slippagePct, BigDecimal feePct) {

        Instant start = startDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant end   = endDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        BigDecimal capital  = initialCapital != null ? initialCapital : DEFAULT_CAPITAL;
        BigDecimal slippage = slippagePct    != null ? slippagePct    : DEFAULT_SLIPPAGE;
        BigDecimal fee      = feePct         != null ? feePct         : DEFAULT_FEE;

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<Map<String, Object>> results = new java.util.ArrayList<>();

        for (String coinPair : coinPairs) {
            List<CandleDataEntity> entities = candleDataRepository.findCandles(coinPair, timeframe, start, end);
            if (entities.size() < 30) {
                log.warn("배치 백테스트 건너뜀: {} {} 데이터 부족 ({}건)", coinPair, timeframe, entities.size());
                continue;
            }

            List<Candle> candles = toCandles(entities);

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
                            .exitRuleConfig(riskManagementService.getExitRuleConfig())
                            .build();

                    BacktestResult result = runStrategy(config, candles, strategyName);
                    PerformanceReport metrics = result.getMetrics();

                    final BacktestResult finalResult = result;
                    Long savedId = tx.execute(status -> {
                        BacktestRunEntity runEntity = saveRun(config, false);
                        saveMetrics(runEntity.getId(), metrics);
                        saveTrades(runEntity.getId(), finalResult.getTrades());
                        return runEntity.getId();
                    });

                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id",          savedId);
                    row.put("coinPair",    coinPair);
                    row.put("strategy",    strategyName);
                    row.put("totalReturn", metrics.getTotalReturnPct());
                    row.put("winRate",     metrics.getWinRatePct());
                    row.put("maxDrawdown", metrics.getMddPct());
                    row.put("sharpe",      metrics.getSharpeRatio());
                    row.put("sortino",     metrics.getSortinoRatio());
                    row.put("totalTrades", metrics.getTotalTrades());
                    row.put("winLossRatio",metrics.getWinLossRatio());
                    results.add(row);

                    log.info("배치 백테스트 완료: {} {} → 수익률={}, 승률={}, MDD={}",
                            strategyName, coinPair,
                            metrics.getTotalReturnPct(), metrics.getWinRatePct(), metrics.getMddPct());

                } catch (Exception e) {
                    log.error("배치 백테스트 실패: {} {} — {}", strategyName, coinPair, e.getMessage());
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("coinPair",  coinPair);
                    row.put("strategy",  strategyName);
                    row.put("error",     e.getMessage());
                    results.add(row);
                }
            }
        }

        results.sort(BY_TOTAL_RETURN);

        return results;
    }

    /**
     * MACD 파라미터 그리드 서치 (고속 O(n) 버전).
     * fastPeriod × slowPeriod 조합을 전수 탐색하여 성과 비교표를 반환한다.
     * BacktestEngine(O(n²)) 대신 MACD 시리즈를 한 번만 계산하는 경량 루프를 사용한다.
     * 결과는 DB에 저장하지 않고 메모리에서 Sharpe Ratio 내림차순으로 반환한다.
     */
    public List<Map<String, Object>> runMacdGridSearch(
            List<String> coins, String timeframe,
            LocalDate startDate, LocalDate endDate,
            int fastMin, int fastMax, int slowMin, int slowMax, int signalPeriod,
            BigDecimal initialCapital, BigDecimal slippagePct, BigDecimal feePct) {

        Instant start = startDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant end   = endDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        double capital  = (initialCapital != null ? initialCapital : new BigDecimal("10000000")).doubleValue();
        double slippage = (slippagePct    != null ? slippagePct    : new BigDecimal("0.1")).doubleValue() / 100.0;
        double fee      = (feePct         != null ? feePct         : new BigDecimal("0.05")).doubleValue() / 100.0;

        List<Map<String, Object>> results = new java.util.ArrayList<>();

        for (String coin : coins) {
            List<CandleDataEntity> entities = candleDataRepository.findCandles(coin, timeframe, start, end);
            if (entities.size() < 50) {
                log.warn("MACD 그리드 서치 건너뜀: {} {} 데이터 부족 ({}건)", coin, timeframe, entities.size());
                continue;
            }

            // close, open 배열로 변환 (primitive double = BigDecimal보다 수십 배 빠름)
            int n = entities.size();
            double[] closes = new double[n];
            double[] opens  = new double[n];
            for (int i = 0; i < n; i++) {
                closes[i] = entities.get(i).getClose().doubleValue();
                opens[i]  = entities.get(i).getOpen().doubleValue();
            }

            int comboCnt = 0;
            for (int fast = fastMin; fast <= fastMax; fast++) {
                for (int slow = slowMin; slow <= slowMax; slow++) {
                    if (fast >= slow) continue;

                    // ── MACD 시리즈 한 번만 계산 (O(n)) ──────────────────────────
                    int warmup = slow + signalPeriod + 1;
                    if (n < warmup) continue;

                    double[] macdLine   = new double[n];
                    double[] signalLine = new double[n];

                    double kFast = 2.0 / (fast + 1);
                    double kSlow = 2.0 / (slow + 1);
                    double kSig  = 2.0 / (signalPeriod + 1);

                    // 초기 EMA = SMA(첫 period개)
                    double emaFast = 0, emaSlow = 0;
                    for (int i = 0; i < fast; i++) emaFast += closes[i];
                    emaFast /= fast;
                    for (int i = 0; i < slow; i++) emaSlow += closes[i];
                    emaSlow /= slow;
                    for (int i = fast; i < slow; i++) emaFast = closes[i] * kFast + emaFast * (1 - kFast);
                    macdLine[slow - 1] = emaFast - emaSlow;

                    for (int i = slow; i < n; i++) {
                        emaFast = closes[i] * kFast + emaFast * (1 - kFast);
                        emaSlow = closes[i] * kSlow + emaSlow * (1 - kSlow);
                        macdLine[i] = emaFast - emaSlow;
                    }

                    // Signal EMA (slow-1 ~ n-1 구간의 macdLine으로 계산)
                    double sigEma = 0;
                    for (int i = slow - 1; i < slow - 1 + signalPeriod; i++) sigEma += macdLine[i];
                    sigEma /= signalPeriod;
                    signalLine[slow - 1 + signalPeriod - 1] = sigEma;
                    for (int i = slow - 1 + signalPeriod; i < n; i++) {
                        sigEma = macdLine[i] * kSig + sigEma * (1 - kSig);
                        signalLine[i] = sigEma;
                    }

                    // ── 경량 백테스트 루프 (O(n)) ──────────────────────────────────
                    double cash = capital;
                    double qty  = 0.0;
                    double entryPrice = 0.0;
                    int wins = 0, losses = 0, trades = 0;
                    double peakCapital = capital;
                    double maxDd = 0.0;
                    List<Double> dailyReturns = new java.util.ArrayList<>();
                    double prevPortfolio = capital;

                    for (int i = warmup; i < n - 1; i++) {
                        boolean prevAbove = macdLine[i - 1] > signalLine[i - 1];
                        boolean currAbove = macdLine[i]     > signalLine[i];

                        // 골든크로스: BUY
                        if (currAbove && !prevAbove && macdLine[i] > 0 && qty == 0.0) {
                            double execPrice = opens[i + 1] * (1 + slippage);
                            qty = cash / execPrice * (1 - fee);
                            entryPrice = execPrice;
                            cash = 0.0;
                            trades++;
                        }
                        // 데드크로스: SELL
                        else if (!currAbove && prevAbove && macdLine[i] < 0 && qty > 0.0) {
                            double execPrice = opens[i + 1] * (1 - slippage);
                            double proceeds = qty * execPrice * (1 - fee);
                            if (proceeds > entryPrice * (qty / (1 - fee))) wins++; else losses++;
                            cash = proceeds;
                            qty = 0.0;
                        }

                        double portfolio = cash + qty * closes[i];
                        double ret = prevPortfolio > 0 ? (portfolio - prevPortfolio) / prevPortfolio : 0;
                        dailyReturns.add(ret);
                        prevPortfolio = portfolio;
                        if (portfolio > peakCapital) peakCapital = portfolio;
                        double dd = peakCapital > 0 ? (peakCapital - portfolio) / peakCapital : 0;
                        if (dd > maxDd) maxDd = dd;
                    }

                    // 미청산 포지션 강제 청산
                    if (qty > 0.0) {
                        cash = qty * closes[n - 1] * (1 - fee);
                        qty = 0.0;
                    }

                    double finalCapital = cash;
                    double totalReturn  = (finalCapital - capital) / capital * 100.0;
                    int winRate = trades > 0 ? (int) Math.round((double) wins / trades * 100) : 0;

                    // Sharpe (연율화: H1 기준 × √8760)
                    double meanRet = dailyReturns.stream().mapToDouble(d -> d).average().orElse(0);
                    double stdRet  = Math.sqrt(dailyReturns.stream()
                            .mapToDouble(d -> (d - meanRet) * (d - meanRet)).average().orElse(0));
                    double sharpe = stdRet > 0 ? meanRet / stdRet * Math.sqrt(8760) : 0;

                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("coin",         coin);
                    row.put("fastPeriod",   fast);
                    row.put("slowPeriod",   slow);
                    row.put("signalPeriod", signalPeriod);
                    row.put("totalReturn",  Math.round(totalReturn * 100.0) / 100.0);
                    row.put("winRate",      winRate);
                    row.put("maxDrawdown",  Math.round(maxDd * 10000.0) / 100.0);
                    row.put("sharpe",       Math.round(sharpe * 100.0) / 100.0);
                    row.put("totalTrades",  trades);
                    row.put("wins",         wins);
                    row.put("losses",       losses);
                    results.add(row);
                    comboCnt++;
                }
            }
            log.info("MACD 그리드 서치 완료: {} — {}건 조합 처리", coin, comboCnt);
        }

        // Sharpe 내림차순 정렬
        results.sort((a, b) -> {
            Double sa = (Double) a.getOrDefault("sharpe", null);
            Double sb = (Double) b.getOrDefault("sharpe", null);
            if (sa == null && sb == null) return 0;
            if (sa == null) return 1;
            if (sb == null) return -1;
            return Double.compare(sb, sa);
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

    /**
     * 백테스트 전용 COMPOSITE_BREAKOUT 인스턴스.
     * Live/BT 동일 구성: ATR(0.4) + VD(0.3) + RSI(0.2) + EMA(0.1)
     * RSI는 캔들 데이터만으로 계산되므로 BT/Live 간 보정 불필요.
     */
    private CompositeStrategy compositeBreakoutBt() {
        return new CompositeStrategy("COMPOSITE_BREAKOUT", List.of(
                new WeightedStrategy(new AtrBreakoutStrategy(), 0.4),
                new WeightedStrategy(new VolumeDeltaStrategy(),  0.3),
                new WeightedStrategy(new RsiStrategy(),          0.2),
                new WeightedStrategy(new EmaCrossStrategy(),     0.1)
        ), true, true);
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
        return map; // HashMap — caller may add more fields
    }

    private List<Candle> toCandles(List<CandleDataEntity> entities) {
        return entities.stream()
                .map(e -> Candle.builder()
                        .time(e.getTime())
                        .open(e.getOpen()).high(e.getHigh())
                        .low(e.getLow()).close(e.getClose())
                        .volume(e.getVolume())
                        .build())
                .toList();
    }

    private BacktestResult runStrategy(BacktestConfig config, List<Candle> candles, String strategyName) {
        if ("COMPOSITE".equals(strategyName)) {
            MarketRegimeDetector detector = new MarketRegimeDetector();
            List<WeightedStrategy> weighted = StrategySelector.select(detector.detect(candles));
            return backtestEngine.run(config, candles, new CompositeStrategy(weighted));
        } else if ("COMPOSITE_ETH".equals(strategyName)) {
            return backtestEngine.run(config, candles, compositeEthBt());
        } else if ("COMPOSITE_BREAKOUT".equals(strategyName)) {
            return backtestEngine.run(config, candles, compositeBreakoutBt());
        } else {
            return backtestEngine.run(config, candles);
        }
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
