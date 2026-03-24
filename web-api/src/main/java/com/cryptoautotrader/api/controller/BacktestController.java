package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.dto.BacktestRequest;
import com.cryptoautotrader.api.dto.BulkBacktestRequest;
import com.cryptoautotrader.api.dto.BulkDeleteRequest;
import com.cryptoautotrader.api.dto.MacdGridSearchRequest;
import com.cryptoautotrader.api.dto.MultiStrategyBacktestRequest;
import com.cryptoautotrader.api.dto.WalkForwardRequest;
import com.cryptoautotrader.api.entity.BacktestTradeEntity;
import com.cryptoautotrader.api.repository.BacktestTradeRepository;
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
    private final BacktestTradeRepository backtestTradeRepository;

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
            @RequestParam(defaultValue = "50") int size) {
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
}
