package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.dto.BacktestRequest;
import com.cryptoautotrader.api.dto.BatchBacktestRequest;
import com.cryptoautotrader.api.dto.BulkBacktestRequest;
import com.cryptoautotrader.api.dto.BulkDeleteRequest;
import com.cryptoautotrader.api.dto.MacdGridSearchRequest;
import com.cryptoautotrader.api.dto.MultiStrategyBacktestRequest;
import com.cryptoautotrader.api.dto.MultiTimeframeBatchRequest;
import com.cryptoautotrader.api.dto.WalkForwardBatchRequest;
import com.cryptoautotrader.api.dto.WalkForwardRequest;
import com.cryptoautotrader.api.entity.BacktestTradeEntity;
import com.cryptoautotrader.api.repository.BacktestTradeRepository;
import com.cryptoautotrader.api.service.BacktestJobService;
import com.cryptoautotrader.api.service.BacktestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;
    private final BacktestJobService backtestJobService;
    private final BacktestTradeRepository backtestTradeRepository;
    private final com.cryptoautotrader.api.repository.CandleDataRepository candleDataRepository;

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> runBacktest(@Valid @RequestBody BacktestRequest req) {
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
        return ApiResponse.ok(result);
    }

    @PostMapping("/walk-forward")
    public ApiResponse<Map<String, Object>> runWalkForward(@Valid @RequestBody WalkForwardRequest req) {
        Map<String, Object> result = backtestService.runWalkForward(
                req.getStrategyType(), req.getCoinPair(), req.getTimeframe(),
                req.getStartDate(), req.getEndDate(),
                req.getInSampleRatio(), req.getWindowCount(),
                req.getInitialCapital(), req.getSlippagePct(), req.getFeePct(),
                req.getConfig()
        );
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getResult(@PathVariable Long id) {
        return ApiResponse.ok(backtestService.getBacktestResult(id));
    }

    @GetMapping("/{id}/trades")
    public ApiResponse<Page<BacktestTradeEntity>> getTrades(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10000") int size) {
        return ApiResponse.ok(backtestTradeRepository.findByBacktestRunIdOrderByExecutedAtAsc(id, PageRequest.of(page, size)));
    }

    @GetMapping("/compare")
    public ApiResponse<List<Map<String, Object>>> compare(@RequestParam List<Long> ids) {
        return ApiResponse.ok(backtestService.compareBacktests(ids));
    }

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(backtestService.listBacktests());
    }

    /**
     * Walk Forward 실행 이력 목록 조회 (최신순)
     * GET /api/v1/backtest/walk-forward/history
     */
    @GetMapping("/walk-forward/history")
    public ApiResponse<List<Map<String, Object>>> walkForwardHistory() {
        return ApiResponse.ok(backtestService.listWalkForwardHistory());
    }

    /**
     * 백테스트 단건 삭제
     * DELETE /api/v1/backtest/{id}
     * 성공: 204 No Content
     * 존재하지 않는 ID: 404 Not Found
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBacktest(@PathVariable Long id) {
        try {
            backtestService.deleteBacktest(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * 전략 10종 × 지정 코인 일괄 백테스트
     * POST /api/v1/backtest/bulk-run
     * Body: { "coins": ["KRW-BTC","KRW-ETH"], "timeframe": "H1",
     *         "startDate": "2023-01-01", "endDate": "2025-12-31" }
     * 응답: totalReturn 내림차순 정렬된 비교표
     */
    @PostMapping("/bulk-run")
    public ApiResponse<List<Map<String, Object>>> runBulkBacktest(
            @RequestBody BulkBacktestRequest req) {
        List<Map<String, Object>> results = backtestService.runBulkBacktest(
                req.getCoins(), req.getTimeframe(),
                req.getStartDate(), req.getEndDate(),
                req.getInitialCapital(), req.getSlippagePct(), req.getFeePct());
        return ApiResponse.ok(results);
    }

    /**
     * 사용자 선택 전략 목록 × 단일 코인 백테스트 비교표
     * POST /api/v1/backtest/multi-strategy
     * Body: { "strategyTypes": ["RSI","EMA_CROSS","BOLLINGER"],
     *         "coinPair": "KRW-BTC", "timeframe": "M5",
     *         "startDate": "2024-01-01", "endDate": "2025-01-01" }
     * 응답: totalReturn 내림차순 정렬된 전략별 비교표
     */
    @PostMapping("/multi-strategy")
    public ApiResponse<List<Map<String, Object>>> runMultiStrategyBacktest(
            @Valid @RequestBody MultiStrategyBacktestRequest req) {
        List<Map<String, Object>> results = backtestService.runMultiStrategyBacktest(
                req.getStrategyTypes(), req.getCoinPair(), req.getTimeframe(),
                req.getStartDate(), req.getEndDate(),
                req.getInitialCapital(), req.getSlippagePct(), req.getFeePct());
        return ApiResponse.ok(results);
    }

    /**
     * 백테스트 다건 삭제
     * DELETE /api/v1/backtest/bulk
     * Body: { "ids": [1, 2, 3] }
     * 성공: 204 No Content
     */
    @DeleteMapping("/bulk")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bulkDeleteBacktests(@RequestBody BulkDeleteRequest request) {
        backtestService.bulkDeleteBacktests(request.getIds());
    }

    /**
     * MACD 파라미터 그리드 서치
     * POST /api/v1/backtest/macd-grid-search
     * Body: { "coins": ["KRW-BTC","KRW-ETH"], "timeframe": "H1",
     *         "startDate": "2024-01-01", "endDate": "2025-12-31",
     *         "fastMin": 8, "fastMax": 15, "slowMin": 20, "slowMax": 30, "signalPeriod": 9 }
     * 응답: Sharpe Ratio 내림차순 정렬된 파라미터 조합별 성과표 (DB 저장 없음)
     */
    @PostMapping("/macd-grid-search")
    public ApiResponse<List<Map<String, Object>>> runMacdGridSearch(
            @Valid @RequestBody MacdGridSearchRequest req) {
        List<Map<String, Object>> results = backtestService.runMacdGridSearch(
                req.getCoins(), req.getTimeframe(),
                req.getStartDate(), req.getEndDate(),
                req.getFastMin(), req.getFastMax(),
                req.getSlowMin(), req.getSlowMax(),
                req.getSignalPeriod(),
                req.getInitialCapital(), req.getSlippagePct(), req.getFeePct());
        return ApiResponse.ok(results);
    }

    /**
     * 특정 타임프레임에 캔들 데이터가 존재하는 코인 목록 조회
     * GET /api/v1/backtest/available-coins?timeframe=M15
     */
    @GetMapping("/available-coins")
    public ApiResponse<List<String>> availableCoins(@RequestParam String timeframe) {
        return ApiResponse.ok(candleDataRepository.findDistinctCoinsByTimeframe(timeframe));
    }

    // ── 비동기 백테스트 API ───────────────────────────────────────────────────────

    /**
     * 단일 백테스트 비동기 실행
     * POST /api/v1/backtest/run-async
     * 요청 즉시 jobId를 반환하고 백그라운드에서 실행한다.
     * 완료/실패 시 텔레그램으로 알림이 전송된다.
     * 응답: { "jobId": 42 }
     */
    @PostMapping("/run-async")
    public ApiResponse<Map<String, Object>> runBacktestAsync(@Valid @RequestBody BacktestRequest req) {
        Long jobId = backtestJobService.submitSingleJob(req);
        return ApiResponse.ok(Map.of("jobId", jobId,
                "message", "백테스트가 백그라운드에서 시작되었습니다. 완료 시 텔레그램으로 알림이 전송됩니다."));
    }

    /**
     * 다중 전략 비동기 실행
     * POST /api/v1/backtest/multi-strategy-async
     * 응답: { "jobId": 43 }
     */
    @PostMapping("/multi-strategy-async")
    public ApiResponse<Map<String, Object>> runMultiStrategyAsync(
            @Valid @RequestBody MultiStrategyBacktestRequest req) {
        Long jobId = backtestJobService.submitMultiStrategyJob(req);
        return ApiResponse.ok(Map.of("jobId", jobId,
                "message", "다중 전략 백테스트가 백그라운드에서 시작되었습니다. 완료 시 텔레그램으로 알림이 전송됩니다."));
    }

    /**
     * 벌크 백테스트 비동기 실행
     * POST /api/v1/backtest/bulk-run-async
     * 응답: { "jobId": 44 }
     */
    @PostMapping("/bulk-run-async")
    public ApiResponse<Map<String, Object>> runBulkAsync(@RequestBody BulkBacktestRequest req) {
        Long jobId = backtestJobService.submitBulkJob(req);
        return ApiResponse.ok(Map.of("jobId", jobId,
                "message", "벌크 백테스트가 백그라운드에서 시작되었습니다. 완료 시 텔레그램으로 알림이 전송됩니다."));
    }

    /**
     * 배치 백테스트 비동기 실행 (선택 코인 N × 선택 전략 M)
     * POST /api/v1/backtest/batch-async
     * Body: { "coinPairs": ["KRW-BTC","KRW-ETH"], "strategyTypes": ["RSI","EMA_CROSS"], ... }
     * 응답: { "jobId": 45, "total": 4 }
     */
    @PostMapping("/batch-async")
    public ApiResponse<Map<String, Object>> runBatchAsync(@Valid @RequestBody BatchBacktestRequest req) {
        Long jobId = backtestJobService.submitBatchJob(req);
        int total = req.getCoinPairs().size() * req.getStrategyTypes().size();
        return ApiResponse.ok(Map.of(
                "jobId", jobId,
                "total", total,
                "message", total + "개 조합 배치 백테스트가 백그라운드에서 시작되었습니다. 완료 시 텔레그램으로 알림이 전송됩니다."));
    }

    /**
     * 멀티타임프레임 배치 백테스트 비동기 실행 (전략 N × 코인 M × 타임프레임 K)
     * POST /api/v1/backtest/batch-multiframe-async
     * Body: {
     *   "strategyTypes": ["COMPOSITE_MOMENTUM_ICHIMOKU_V2", "COMPOSITE_BREAKOUT"],
     *   "coinPairs": ["KRW-BTC", "KRW-ETH", ...],
     *   "timeframes": ["H1", "M15"],
     *   "startDate": "2022-01-01",
     *   "endDate": "2025-12-31"
     * }
     * 응답: { "jobId": 46, "total": 80 }
     */
    @PostMapping("/batch-multiframe-async")
    public ApiResponse<Map<String, Object>> runMultiTimeframeBatchAsync(
            @Valid @RequestBody MultiTimeframeBatchRequest req) {
        Long jobId = backtestJobService.submitMultiTimeframeBatchJob(req);
        int total = req.getStrategyTypes().size() * req.getCoinPairs().size() * req.getTimeframes().size();
        return ApiResponse.ok(Map.of(
                "jobId", jobId,
                "total", total,
                "timeframes", String.join("+", req.getTimeframes()),
                "message", total + "개 조합 멀티타임프레임 배치 백테스트가 백그라운드에서 시작되었습니다. 완료 시 텔레그램으로 알림이 전송됩니다."));
    }

    /**
     * 백테스트 Job 상태 조회
     * GET /api/v1/backtest/job/{jobId}
     * 응답: { id, status, progressPct, totalCandles, errorMessage, ... }
     */
    @GetMapping("/job/{jobId}")
    public ApiResponse<Map<String, Object>> getJob(@PathVariable Long jobId) {
        try {
            return ApiResponse.ok(backtestJobService.getJob(jobId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * 백테스트 Job 전체 목록 조회 (최신순)
     * GET /api/v1/backtest/jobs
     */
    @GetMapping("/jobs")
    public ApiResponse<List<Map<String, Object>>> listJobs() {
        return ApiResponse.ok(backtestJobService.listJobs());
    }

    /**
     * 백테스트 Job 취소
     * POST /api/v1/backtest/job/{jobId}/cancel
     * PENDING → 즉시 CANCELLED. RUNNING → 취소 플래그 세팅 (현재 조합 완료 후 중단).
     */
    @PostMapping("/job/{jobId}/cancel")
    public ApiResponse<Map<String, Object>> cancelJob(@PathVariable Long jobId) {
        try {
            return ApiResponse.ok(backtestJobService.cancelJob(jobId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Walk-Forward 배치 비동기 실행 (코인 N × 전략 M)
     * POST /api/v1/backtest/walk-forward-batch-async
     * Body: { "coinPairs": ["KRW-BTC","KRW-ETH"], "strategyTypes": ["COMPOSITE_BREAKOUT","COMPOSITE_MOMENTUM"],
     *         "timeframe": "H1", "startDate": "2023-01-01", "endDate": "2025-12-31",
     *         "inSampleRatio": 0.7, "windowCount": 5 }
     * 응답: { "jobId": 50, "total": 4 }
     */
    @PostMapping("/walk-forward-batch-async")
    public ApiResponse<Map<String, Object>> runWalkForwardBatchAsync(
            @RequestBody WalkForwardBatchRequest req) {
        Long jobId = backtestJobService.submitWalkForwardBatchJob(req);
        int total = req.getCoinPairs().size() * req.getStrategyTypes().size();
        return ApiResponse.ok(Map.of(
                "jobId", jobId,
                "total", total,
                "message", total + "개 조합 Walk-Forward 배치가 백그라운드에서 시작되었습니다. 완료 시 텔레그램으로 알림이 전송됩니다."));
    }
}
