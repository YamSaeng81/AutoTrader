package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.BacktestRequest;
import com.cryptoautotrader.api.dto.BatchBacktestRequest;
import com.cryptoautotrader.api.dto.BulkBacktestRequest;
import com.cryptoautotrader.api.dto.MultiStrategyBacktestRequest;
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
        String reqJson = toJson(req);
        BacktestJobEntity job = BacktestJobEntity.builder()
                .jobType("SINGLE")
                .status("PENDING")
                .coinPair(req.getCoinPair())
                .strategyName(req.getStrategyType())
                .timeframe(req.getTimeframe())
                .requestJson(reqJson)
                .build();
        job = jobRepository.save(job);
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
        BacktestJobEntity job = BacktestJobEntity.builder()
                .jobType("MULTI_STRATEGY")
                .status("PENDING")
                .coinPair(req.getCoinPair())
                .strategyName(strategies.length() > 200 ? strategies.substring(0, 197) + "..." : strategies)
                .timeframe(req.getTimeframe())
                .requestJson(toJson(req))
                .build();
        job = jobRepository.save(job);
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
        BacktestJobEntity job = BacktestJobEntity.builder()
                .jobType("BULK")
                .status("PENDING")
                .coinPair(req.getCoins().size() + "개 코인")
                .strategyName("전략 10종")
                .timeframe(req.getTimeframe())
                .requestJson(toJson(req))
                .build();
        job = jobRepository.save(job);
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
        String coinLabel = req.getCoinPairs().size() + "개 코인";
        String stratLabel = req.getStrategyTypes().size() + "개 전략";
        BacktestJobEntity job = BacktestJobEntity.builder()
                .jobType("BATCH")
                .status("PENDING")
                .coinPair(coinLabel)
                .strategyName(stratLabel)
                .timeframe(req.getTimeframe())
                .requestJson(toJson(req))
                .totalChunks(total)
                .build();
        job = jobRepository.save(job);
        Long jobId = job.getId();
        log.info("배치 백테스트 Job 제출: jobId={}, 코인 {}개 × 전략 {}개 = {}개 조합",
                jobId, req.getCoinPairs().size(), req.getStrategyTypes().size(), total);
        self.executeBatchAsync(jobId, req);
        return jobId;
    }

    @Async("backtestExecutor")
    public void executeBatchAsync(Long jobId, BatchBacktestRequest req) {
        BacktestJobEntity job = jobRepository.findById(jobId).orElseThrow();
        try {
            job.setStatus("RUNNING");
            jobRepository.save(job);

            List<Map<String, Object>> results = backtestService.runBatchBacktest(
                    req.getCoinPairs(), req.getStrategyTypes(), req.getTimeframe(),
                    req.getStartDate(), req.getEndDate(),
                    req.getInitialCapital(), req.getSlippagePct(), req.getFeePct()
            );

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

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────────

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
