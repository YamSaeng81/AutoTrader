package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.BacktestRequest;
import com.cryptoautotrader.api.dto.BatchBacktestRequest;
import com.cryptoautotrader.api.dto.BulkBacktestRequest;
import com.cryptoautotrader.api.dto.MultiStrategyBacktestRequest;
import com.cryptoautotrader.api.dto.WalkForwardBatchRequest;
import com.cryptoautotrader.api.entity.BacktestJobEntity;
import com.cryptoautotrader.api.repository.BacktestJobRepository;
import com.cryptoautotrader.api.repository.CandleDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 백테스트 비동기 작업 제출 및 실행 서비스.
 *
 * <p>사용 흐름:
 * <ol>
 *   <li>컨트롤러 → {@code submitSingleJob()} 호출 → Job 레코드(PENDING) 생성 후 jobId 즉시 반환</li>
 *   <li>백그라운드 {@code backtestExecutor} 스레드풀에서 {@code executeAsync()} 실행</li>
 *   <li>캔들 데이터를 {@value CHUNK_SIZE}건씩 청크 로딩 → BacktestService에 전달</li>
 *   <li>완료/실패 시 텔레그램 알림 전송 + Job 상태 업데이트</li>
 * </ol>
 *
 * <p><b>@Async self-invocation 해결:</b> {@code @Lazy} 자기 참조를 통해 Spring 프록시를 경유한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestJobService {

    /** 청크당 캔들 수 — DB 쿼리 페이지 크기 및 JPA Entity GC 단위 */
    static final int CHUNK_SIZE = 100_000;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    /** 취소 요청된 jobId 집합. RUNNING 중인 배치/WF 루프가 매 iteration마다 확인한다. */
    private final ConcurrentHashMap<Long, Boolean> cancelRequested = new ConcurrentHashMap<>();

    private final BacktestJobRepository jobRepository;
    private final BacktestService backtestService;
    private final TelegramNotificationService telegramService;
    private final CandleDataRepository candleDataRepository;
    private final ObjectMapper objectMapper;

    /**
     * @Async self-invocation 문제 해결: @Lazy로 자신의 Spring 프록시를 주입받아
     * executeAsync()가 @Async 어노테이션을 통해 실제로 비동기 실행되도록 한다.
     */
    @Lazy
    @Autowired
    private BacktestJobService self;

    // ── 단일 백테스트 ─────────────────────────────────────────────────────────────

    /**
     * 단일 백테스트 작업을 제출한다. Job 레코드를 저장 후 즉시 jobId를 반환하고
     * 백그라운드에서 실행한다.
     */
    public Long submitSingleJob(BacktestRequest req) {
        BacktestJobEntity job = createJob("SINGLE", req.getCoinPair(), req.getStrategyType(),
                req.getTimeframe(), req, null);
        Long jobId = job.getId();
        log.info("백테스트 Job 제출: jobId={}, strategy={}, coin={}", jobId, req.getStrategyType(), req.getCoinPair());
        self.executeSingleAsync(jobId, req);
        return jobId;
    }

    @Async("backtestExecutor")
    public void executeSingleAsync(Long jobId, BacktestRequest req) {
        BacktestJobEntity job = jobRepository.findById(jobId).orElseThrow();
        try {
            // 캔들 수 집계 (청크 단위 로딩 정보 기록)
            Instant start = req.getStartDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
            Instant end   = req.getEndDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
            int totalCandles = countCandles(req.getCoinPair(), req.getTimeframe(), start, end);
            int totalChunks  = Math.max(1, (int) Math.ceil((double) totalCandles / CHUNK_SIZE));

            job.setStatus("RUNNING");
            job.setTotalCandles(totalCandles);
            job.setTotalChunks(totalChunks);
            jobRepository.save(job);
            log.info("백테스트 시작: jobId={}, 캔들 {}건, {}청크 단위", jobId, totalCandles, totalChunks);

            boolean fillSimEnabled = req.getFillSimulation() != null && req.getFillSimulation().isEnabled();
            Map<String, Object> result = backtestService.runBacktest(
                    req.getStrategyType(), req.getCoinPair(), req.getTimeframe(),
                    req.getStartDate(), req.getEndDate(),
                    req.getInitialCapital(), req.getSlippagePct(), req.getFeePct(),
                    req.getConfig(),
                    fillSimEnabled,
                    fillSimEnabled ? req.getFillSimulation().getImpactFactor() : null,
                    fillSimEnabled ? req.getFillSimulation().getFillRatio() : null
            );

            job.setStatus("COMPLETED");
            job.setCompletedChunks(totalChunks);
            job.setBacktestRunId((Long) result.get("id"));
            jobRepository.save(job);
            log.info("백테스트 완료: jobId={}, runId={}", jobId, result.get("id"));

            String s = fmt(req.getStartDate()), e2 = fmt(req.getEndDate());
            telegramService.notifyBacktestCompleted(jobId, req.getCoinPair(), req.getStrategyType(),
                    s, e2, req.getTimeframe(), result);

        } catch (Exception e) {
            log.error("백테스트 실패: jobId={}, error={}", jobId, e.getMessage(), e);
            job.setStatus("FAILED");
            job.setErrorMessage(truncate(e.getMessage(), 1000));
            jobRepository.save(job);
            telegramService.notifyBacktestFailed(jobId, req.getCoinPair(), req.getStrategyType(),
                    fmt(req.getStartDate()), fmt(req.getEndDate()), req.getTimeframe(), e);
        }
    }

    // ── 다중 전략 백테스트 ────────────────────────────────────────────────────────

    /**
     * 다중 전략 × 단일 코인 백테스트 작업을 제출한다.
     */
    public Long submitMultiStrategyJob(MultiStrategyBacktestRequest req) {
        String strategies = String.join(", ", req.getStrategyTypes());
        String stratLabel = truncate(strategies, 200);
        BacktestJobEntity job = createJob("MULTI_STRATEGY", req.getCoinPair(), stratLabel,
                req.getTimeframe(), req, null);
        Long jobId = job.getId();
        log.info("다중전략 백테스트 Job 제출: jobId={}, 전략 {}개, coin={}", jobId, req.getStrategyTypes().size(), req.getCoinPair());
        self.executeMultiStrategyAsync(jobId, req);
        return jobId;
    }

    @Async("backtestExecutor")
    public void executeMultiStrategyAsync(Long jobId, MultiStrategyBacktestRequest req) {
        BacktestJobEntity job = jobRepository.findById(jobId).orElseThrow();
        try {
            job.setStatus("RUNNING");
            job.setTotalChunks(req.getStrategyTypes().size());
            jobRepository.save(job);

            List<Map<String, Object>> results = backtestService.runMultiStrategyBacktest(
                    req.getStrategyTypes(), req.getCoinPair(), req.getTimeframe(),
                    req.getStartDate(), req.getEndDate(),
                    req.getInitialCapital(), req.getSlippagePct(), req.getFeePct()
            );

            long failCount = results.stream().filter(r -> r.containsKey("error")).count();
            job.setStatus(failCount == results.size() ? "FAILED" : "COMPLETED");
            job.setCompletedChunks(req.getStrategyTypes().size());
            if (failCount > 0 && failCount < results.size()) {
                job.setErrorMessage(failCount + "개 전략 실패 (나머지 완료)");
            }
            jobRepository.save(job);
            log.info("다중전략 백테스트 완료: jobId={}, 총 {}개 중 실패 {}개", jobId, results.size(), failCount);

            // 대표 요약 결과로 텔레그램 알림
            Map<String, Object> summary = buildMultiStrategySummary(results, req.getCoinPair());
            telegramService.notifyBacktestCompleted(jobId, req.getCoinPair(),
                    req.getStrategyTypes().size() + "개 전략 비교",
                    fmt(req.getStartDate()), fmt(req.getEndDate()), req.getTimeframe(), summary);

        } catch (Exception e) {
            log.error("다중전략 백테스트 실패: jobId={}, error={}", jobId, e.getMessage(), e);
            job.setStatus("FAILED");
            job.setErrorMessage(truncate(e.getMessage(), 1000));
            jobRepository.save(job);
            telegramService.notifyBacktestFailed(jobId, req.getCoinPair(),
                    req.getStrategyTypes().size() + "개 전략 비교",
                    fmt(req.getStartDate()), fmt(req.getEndDate()), req.getTimeframe(), e);
        }
    }

    // ── 벌크 백테스트 ─────────────────────────────────────────────────────────────

    /**
     * 전략 10종 × N개 코인 벌크 백테스트 작업을 제출한다.
     */
    public Long submitBulkJob(BulkBacktestRequest req) {
        BacktestJobEntity job = createJob("BULK", req.getCoins().size() + "개 코인", "전략 10종",
                req.getTimeframe(), req, null);
        Long jobId = job.getId();
        log.info("벌크 백테스트 Job 제출: jobId={}, 코인 {}개", jobId, req.getCoins().size());
        self.executeBulkAsync(jobId, req);
        return jobId;
    }

    @Async("backtestExecutor")
    public void executeBulkAsync(Long jobId, BulkBacktestRequest req) {
        BacktestJobEntity job = jobRepository.findById(jobId).orElseThrow();
        try {
            job.setStatus("RUNNING");
            jobRepository.save(job);

            List<Map<String, Object>> results = backtestService.runBulkBacktest(
                    req.getCoins(), req.getTimeframe(),
                    req.getStartDate(), req.getEndDate(),
                    req.getInitialCapital(), req.getSlippagePct(), req.getFeePct()
            );

            long failCount = results.stream().filter(r -> r.containsKey("error")).count();
            job.setStatus(failCount == results.size() ? "FAILED" : "COMPLETED");
            if (failCount > 0 && failCount < results.size()) {
                job.setErrorMessage(failCount + "개 조합 실패 (나머지 완료)");
            }
            jobRepository.save(job);
            log.info("벌크 백테스트 완료: jobId={}, 총 {}개 중 실패 {}개", jobId, results.size(), failCount);

            String coinLabel = req.getCoins().size() + "개 코인";
            Map<String, Object> summary = buildMultiStrategySummary(results, coinLabel);
            telegramService.notifyBacktestCompleted(jobId, coinLabel,
                    "전략 10종 × " + coinLabel,
                    fmt(req.getStartDate()), fmt(req.getEndDate()), req.getTimeframe(), summary);

        } catch (Exception e) {
            log.error("벌크 백테스트 실패: jobId={}, error={}", jobId, e.getMessage(), e);
            job.setStatus("FAILED");
            job.setErrorMessage(truncate(e.getMessage(), 1000));
            jobRepository.save(job);
            telegramService.notifyBacktestFailed(jobId, req.getCoins().size() + "개 코인",
                    "전략 10종 벌크",
                    fmt(req.getStartDate()), fmt(req.getEndDate()), req.getTimeframe(), e);
        }
    }

    // ── 배치 백테스트 (코인 N × 전략 M) ─────────────────────────────────────────────

    /**
     * 선택 코인 × 선택 전략 배치 백테스트 작업을 제출한다.
     */
    public Long submitBatchJob(BatchBacktestRequest req) {
        int total = req.getCoinPairs().size() * req.getStrategyTypes().size();
        BacktestJobEntity job = createJob("BATCH",
                req.getCoinPairs().size() + "개 코인",
                req.getStrategyTypes().size() + "개 전략",
                req.getTimeframe(), req, total);
        Long jobId = job.getId();
        log.info("배치 백테스트 Job 제출: jobId={}, 코인 {}개 × 전략 {}개 = {}개 조합",
                jobId, req.getCoinPairs().size(), req.getStrategyTypes().size(), total);
        self.executeBatchAsync(jobId, req);
        return jobId;
    }

    @Async("backtestExecutor")
    public void executeBatchAsync(Long jobId, BatchBacktestRequest req) {
        BacktestJobEntity job = jobRepository.findById(jobId).orElseThrow();
        // 시작 전 취소 확인
        if (cancelRequested.remove(jobId) != null || "CANCELLED".equals(job.getStatus())) {
            job.setStatus("CANCELLED");
            jobRepository.save(job);
            return;
        }
        try {
            job.setStatus("RUNNING");
            jobRepository.save(job);

            List<Map<String, Object>> results = backtestService.runBatchBacktest(
                    req.getCoinPairs(), req.getStrategyTypes(), req.getTimeframe(),
                    req.getStartDate(), req.getEndDate(),
                    req.getInitialCapital(), req.getSlippagePct(), req.getFeePct()
            );

            if (cancelRequested.remove(jobId) != null) {
                job.setStatus("CANCELLED");
                jobRepository.save(job);
                cancelRequested.remove(jobId);
                return;
            }
            long failCount = results.stream().filter(r -> r.containsKey("error")).count();
            job.setStatus(failCount == results.size() ? "FAILED" : "COMPLETED");
            job.setCompletedChunks(results.size());
            if (failCount > 0 && failCount < results.size()) {
                job.setErrorMessage(failCount + "개 조합 실패 (나머지 완료)");
            }
            jobRepository.save(job);
            log.info("배치 백테스트 완료: jobId={}, 총 {}개 중 실패 {}개", jobId, results.size(), failCount);

            String coinLabel = req.getCoinPairs().size() + "개 코인";
            Map<String, Object> summary = buildMultiStrategySummary(results, coinLabel);
            telegramService.notifyBacktestCompleted(jobId, coinLabel,
                    req.getStrategyTypes().size() + "개 전략 × " + coinLabel,
                    fmt(req.getStartDate()), fmt(req.getEndDate()), req.getTimeframe(), summary);

        } catch (Exception e) {
            log.error("배치 백테스트 실패: jobId={}, error={}", jobId, e.getMessage(), e);
            job.setStatus("FAILED");
            job.setErrorMessage(truncate(e.getMessage(), 1000));
            jobRepository.save(job);
            telegramService.notifyBacktestFailed(jobId,
                    req.getCoinPairs().size() + "개 코인",
                    req.getStrategyTypes().size() + "개 전략 배치",
                    fmt(req.getStartDate()), fmt(req.getEndDate()), req.getTimeframe(), e);
        }
    }

    // ── Walk-Forward 배치 (코인 N × 전략 M) ──────────────────────────────────────

    /**
     * Walk-Forward 배치 작업을 비동기로 제출한다.
     * 모든 (coinPair × strategyType) 조합을 순차 실행하고 결과를 텔레그램으로 알린다.
     */
    public Long submitWalkForwardBatchJob(WalkForwardBatchRequest req) {
        int total = req.getCoinPairs().size() * req.getStrategyTypes().size();
        BacktestJobEntity job = createJob("WALK_FORWARD_BATCH",
                req.getCoinPairs().size() + "개 코인",
                req.getStrategyTypes().size() + "개 전략",
                req.getTimeframe(), req, total);
        Long jobId = job.getId();
        log.info("Walk-Forward 배치 Job 제출: jobId={}, 코인 {}개 × 전략 {}개 = {}개 조합",
                jobId, req.getCoinPairs().size(), req.getStrategyTypes().size(), total);
        self.executeWalkForwardBatchAsync(jobId, req);
        return jobId;
    }

    @Async("backtestExecutor")
    public void executeWalkForwardBatchAsync(Long jobId, WalkForwardBatchRequest req) {
        BacktestJobEntity job = jobRepository.findById(jobId).orElseThrow();
        // 시작 전 취소 확인
        if (cancelRequested.remove(jobId) != null || "CANCELLED".equals(job.getStatus())) {
            job.setStatus("CANCELLED");
            jobRepository.save(job);
            return;
        }
        try {
            job.setStatus("RUNNING");
            jobRepository.save(job);

            List<Map<String, Object>> results = new ArrayList<>();
            int completed = 0;
            outer:
            for (String coin : req.getCoinPairs()) {
                for (String strategy : req.getStrategyTypes()) {
                    // 매 조합마다 취소 확인
                    if (cancelRequested.containsKey(jobId)) {
                        log.info("Walk-Forward 배치 취소 감지: jobId={}, 완료 {}개", jobId, completed);
                        cancelRequested.remove(jobId);
                        break outer;
                    }
                    try {
                        Map<String, Object> result = backtestService.runWalkForward(
                                strategy, coin, req.getTimeframe(),
                                req.getStartDate(), req.getEndDate(),
                                req.getInSampleRatio(), req.getWindowCount(),
                                req.getInitialCapital(), req.getSlippagePct(), req.getFeePct(),
                                null);
                        result.put("coin", coin);
                        result.put("strategy", strategy);
                        results.add(result);
                        log.info("Walk-Forward 완료: jobId={} {} {} verdict={}",
                                jobId, coin, strategy, result.get("verdict"));
                    } catch (Exception e) {
                        log.error("Walk-Forward 실패: jobId={} {} {} error={}",
                                jobId, coin, strategy, e.getMessage());
                        results.add(Map.of("coin", coin, "strategy", strategy, "error", e.getMessage()));
                    }
                    ++completed;
                    // 10개마다 진행률 저장 (매 조합마다 저장 대비 DB 부하 감소)
                    if (completed % 10 == 0) {
                        job.setCompletedChunks(completed);
                        jobRepository.save(job);
                    }
                }
            }

            // CANCELLED 상태면 부분 완료로 종료
            if ("CANCELLED".equals(job.getStatus())) {
                job.setCompletedChunks(completed);
                jobRepository.save(job);
                return;
            }
            long failCount = results.stream().filter(r -> r.containsKey("error")).count();
            job.setStatus(failCount == results.size() ? "FAILED" : "COMPLETED");
            job.setCompletedChunks(completed);
            jobRepository.save(job);
            log.info("Walk-Forward 배치 완료: jobId={}, 총 {}개 중 실패 {}개", jobId, results.size(), failCount);

            // Walk-Forward 전용 텔레그램 알림
            String period = fmt(req.getStartDate()) + " ~ " + fmt(req.getEndDate()) + " / " + req.getTimeframe();
            telegramService.notifyWalkForwardBatchCompleted(jobId, results, period);

        } catch (Exception e) {
            log.error("Walk-Forward 배치 실패: jobId={}, error={}", jobId, e.getMessage(), e);
            job.setStatus("FAILED");
            job.setErrorMessage(truncate(e.getMessage(), 1000));
            jobRepository.save(job);
            telegramService.notifyBacktestFailed(jobId,
                    req.getCoinPairs().size() + "개 코인",
                    "Walk-Forward 배치",
                    fmt(req.getStartDate()), fmt(req.getEndDate()), req.getTimeframe(), e);
        }
    }

    private Map<String, Object> buildWalkForwardSummary(List<Map<String, Object>> results) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalCount", results.size());
        summary.put("failCount", results.stream().filter(r -> r.containsKey("error")).count());
        long robustCount = results.stream()
                .filter(r -> !r.containsKey("error") && "ROBUST".equals(r.get("verdict")))
                .count();
        long cautionCount = results.stream()
                .filter(r -> !r.containsKey("error") && "CAUTION".equals(r.get("verdict")))
                .count();
        summary.put("robustCount", robustCount);
        summary.put("cautionCount", cautionCount);
        summary.put("overfittingCount", results.size() - robustCount - cautionCount
                - results.stream().filter(r -> r.containsKey("error")).count());
        return summary;
    }

    // ── Job 조회 ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getJob(Long jobId) {
        BacktestJobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("백테스트 Job 없음: id=" + jobId));
        return jobToMap(job);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listJobs() {
        return jobRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::jobToMap)
                .toList();
    }

    /**
     * Job 취소.
     * <ul>
     *   <li>PENDING: DB 상태를 즉시 CANCELLED로 변경한다.</li>
     *   <li>RUNNING: cancelRequested 플래그를 세팅한다. 배치·WF 루프가 다음 iteration에서 감지하여
     *       현재 진행 중인 조합까지 완료 후 중단한다. SINGLE 작업은 백테스트 엔진 호출이 완료되기
     *       전에는 중단되지 않는다 (다음 chunk 시작 전에 감지).</li>
     * </ul>
     * @return 취소된 Job의 상태 맵
     */
    @Transactional
    public Map<String, Object> cancelJob(Long jobId) {
        BacktestJobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("백테스트 Job 없음: id=" + jobId));

        String status = job.getStatus();
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
            throw new IllegalStateException("이미 종료된 Job은 취소할 수 없습니다: status=" + status);
        }

        if ("PENDING".equals(status)) {
            job.setStatus("CANCELLED");
            job.setErrorMessage("사용자 취소");
            jobRepository.save(job);
            log.info("Job 취소 (PENDING): jobId={}", jobId);
        } else {
            // RUNNING: 플래그 세팅 + DB 상태 선반영
            cancelRequested.put(jobId, Boolean.TRUE);
            job.setStatus("CANCELLED");
            job.setErrorMessage("사용자 취소 (진행 중 작업은 현재 조합 완료 후 중단)");
            jobRepository.save(job);
            log.info("Job 취소 요청 (RUNNING): jobId={}", jobId);
        }
        return jobToMap(job);
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────────

    /** Job 엔티티를 PENDING 상태로 생성·저장한다. totalChunks가 null이면 미설정. */
    private BacktestJobEntity createJob(String jobType, String coinPair, String strategyName,
                                        String timeframe, Object request, Integer totalChunks) {
        BacktestJobEntity job = BacktestJobEntity.builder()
                .jobType(jobType)
                .status("PENDING")
                .coinPair(coinPair)
                .strategyName(strategyName)
                .timeframe(timeframe)
                .requestJson(toJson(request))
                .totalChunks(totalChunks)
                .build();
        return jobRepository.save(job);
    }

    /**
     * 캔들 총 건수를 청크 한 페이지로 조회하여 추정한다.
     * count 쿼리보다 빠르게 대략적인 건수를 파악할 수 있다.
     */
    private int countCandles(String coinPair, String timeframe, Instant start, Instant end) {
        Page<?> firstPage = candleDataRepository.findCandlesPage(
                coinPair, timeframe, start, end, PageRequest.of(0, 1));
        return (int) firstPage.getTotalElements();
    }

    private Map<String, Object> buildMultiStrategySummary(List<Map<String, Object>> results, String coinPair) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalCount", results.size());
        summary.put("failCount", results.stream().filter(r -> r.containsKey("error")).count());

        // 최고 수익률 전략 찾기
        results.stream()
                .filter(r -> !r.containsKey("error") && r.get("totalReturn") != null)
                .max((a, b) -> {
                    java.math.BigDecimal ra = (java.math.BigDecimal) a.get("totalReturn");
                    java.math.BigDecimal rb = (java.math.BigDecimal) b.get("totalReturn");
                    return ra.compareTo(rb);
                })
                .ifPresent(best -> {
                    Map<String, Object> metrics = new HashMap<>();
                    metrics.put("totalReturn", best.get("totalReturn"));
                    metrics.put("winRate",     best.get("winRate"));
                    metrics.put("maxDrawdown", best.get("maxDrawdown"));
                    metrics.put("sharpeRatio", best.get("sharpe"));
                    metrics.put("totalTrades", best.get("totalTrades"));
                    summary.put("bestStrategy", best.get("strategy"));
                    summary.put("metrics", metrics);
                });

        return summary;
    }

    private Map<String, Object> jobToMap(BacktestJobEntity job) {
        Map<String, Object> map = new HashMap<>();
        map.put("id",              job.getId());
        map.put("jobType",         job.getJobType());
        map.put("status",          job.getStatus());
        map.put("coinPair",        job.getCoinPair());
        map.put("strategyName",    job.getStrategyName());
        map.put("timeframe",       job.getTimeframe());
        map.put("totalCandles",    job.getTotalCandles());
        map.put("totalChunks",     job.getTotalChunks());
        map.put("completedChunks", job.getCompletedChunks());
        map.put("backtestRunId",   job.getBacktestRunId());
        map.put("errorMessage",    job.getErrorMessage());
        map.put("createdAt",       job.getCreatedAt());
        map.put("updatedAt",       job.getUpdatedAt());
        // 진행률 (%)
        if (job.getTotalChunks() != null && job.getTotalChunks() > 0) {
            int pct = (int) (100.0 * job.getCompletedChunks() / job.getTotalChunks());
            map.put("progressPct", pct);
        }
        return map;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private String fmt(LocalDate date) {
        return date != null ? date.format(DATE_FMT) : "N/A";
    }
}
