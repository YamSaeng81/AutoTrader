package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.dto.DataCollectRequest;
import com.cryptoautotrader.api.entity.CandleDataEntity;
import com.cryptoautotrader.api.repository.CandleDataRepository;
import com.cryptoautotrader.api.service.DataCollectionService;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/data")
@RequiredArgsConstructor
public class DataController {

    private final DataCollectionService dataCollectionService;
    private final CandleDataRepository candleDataRepository;

    @PostMapping("/collect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> collectData(@Valid @RequestBody DataCollectRequest req) {
        dataCollectionService.collectCandles(req.getCoinPair(), req.getTimeframe(),
                req.getStartDate(), req.getEndDate());
        return ApiResponse.ok(Map.of(
                "status", "STARTED",
                "coinPair", req.getCoinPair(),
                "timeframe", req.getTimeframe()
        ));
    }

    @GetMapping("/summary")
    public ApiResponse<List<Map<String, Object>>> getDataSummary() {
        List<Object[]> rows = candleDataRepository.findDataSummary();
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> item = new HashMap<>();
            item.put("coinPair", row[0]);
            item.put("timeframe", row[1]);
            item.put("from", row[2]);
            item.put("to", row[3]);
            item.put("count", row[4]);
            return item;
        }).toList();
        return ApiResponse.ok(result);
    }

    @GetMapping("/coins")
    public ApiResponse<List<String>> getSupportedCoins() {
        UpbitCandleCollector collector = new UpbitCandleCollector(new UpbitRestClient());
        return ApiResponse.ok(collector.getSupportedCoins());
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus() {
        List<Object[]> rows = candleDataRepository.findDataSummary();
        long totalCandles = rows.stream().mapToLong(r -> ((Number) r[4]).longValue()).sum();
        int pairCount = rows.size();
        return ApiResponse.ok(Map.of(
                "totalCandles", totalCandles,
                "pairCount", pairCount,
                "status", totalCandles > 0 ? "READY" : "EMPTY"
        ));
    }

    /** 특정 코인+타임프레임 데이터 삭제 */
    @Transactional
    @DeleteMapping("/candles")
    public ApiResponse<Map<String, Object>> deleteCandles(
            @RequestParam String coinPair,
            @RequestParam(required = false) String timeframe) {
        int deleted;
        if (timeframe != null && !timeframe.isBlank()) {
            deleted = candleDataRepository.deleteByPairAndTimeframe(coinPair, timeframe);
        } else {
            deleted = candleDataRepository.deleteByPair(coinPair);
        }
        return ApiResponse.ok(Map.of(
                "coinPair", coinPair,
                "timeframe", timeframe != null ? timeframe : "ALL",
                "deletedCount", deleted
        ));
    }

    @GetMapping("/candles")
    public ApiResponse<List<Map<String, Object>>> getCandles(
            @RequestParam String coinPair,
            @RequestParam String timeframe,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "200") int limit) {
        Instant startTime = start != null ? Instant.parse(start) : Instant.now().minusSeconds(86400L * 30);
        Instant endTime = end != null ? Instant.parse(end) : Instant.now();
        List<CandleDataEntity> candles = candleDataRepository.findCandles(coinPair, timeframe, startTime, endTime);
        List<CandleDataEntity> limited = candles.size() > limit
                ? candles.subList(candles.size() - limit, candles.size()) : candles;
        List<Map<String, Object>> result = limited.stream().map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("time", c.getTime().toEpochMilli());
            m.put("open", c.getOpen());
            m.put("high", c.getHigh());
            m.put("low", c.getLow());
            m.put("close", c.getClose());
            m.put("volume", c.getVolume());
            return m;
        }).toList();
        return ApiResponse.ok(result);
    }
}
