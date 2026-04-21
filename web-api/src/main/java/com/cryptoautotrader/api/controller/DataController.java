package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.dto.ApiResponse;
import com.cryptoautotrader.api.dto.DataBatchCollectRequest;
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

    @PostMapping("/collect/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> collectBatch(@Valid @RequestBody DataBatchCollectRequest req) {
        dataCollectionService.collectBatch(req.getCoinPairs(), req.getTimeframe(),
                req.getStartDate(), req.getEndDate());
        return ApiResponse.ok(Map.of(
                "status", "STARTED",
                "coinCount", req.getCoinPairs().size(),
                "coinPairs", req.getCoinPairs(),
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

    /**
     * KRW 마켓 코인 목록 (거래대금 상위 20개) — 코드·한글명·영문명 포함.
     * 프론트엔드 코인 선택 UI에서 이름 표시용으로 사용한다.
     */
    @GetMapping("/markets")
    public ApiResponse<List<Map<String, String>>> getMarkets() {
        UpbitRestClient restClient = new UpbitRestClient();
        UpbitCandleCollector collector = new UpbitCandleCollector(restClient);
        List<String> topCoins = collector.getSupportedCoins();

        List<Map<String, Object>> allMarkets;
        try {
            allMarkets = restClient.getMarkets();
        } catch (Exception e) {
            // 마켓 정보 조회 실패 시 코드만 반환
            List<Map<String, String>> fallback = topCoins.stream()
                    .map(m -> Map.of("market", m, "koreanName", m.replace("KRW-", ""), "englishName", ""))
                    .toList();
            return ApiResponse.ok(fallback);
        }

        Map<String, Map<String, Object>> marketInfoMap = new HashMap<>();
        for (Map<String, Object> info : allMarkets) {
            String market = (String) info.get("market");
            if (market != null) marketInfoMap.put(market, info);
        }

        List<Map<String, String>> result = topCoins.stream()
                .map(market -> {
                    Map<String, Object> info = marketInfoMap.get(market);
                    String korean  = info != null ? (String) info.getOrDefault("korean_name",  "") : "";
                    String english = info != null ? (String) info.getOrDefault("english_name", "") : "";
                    return Map.of("market", market, "koreanName", korean, "englishName", english);
                })
                .toList();
        return ApiResponse.ok(result);
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
