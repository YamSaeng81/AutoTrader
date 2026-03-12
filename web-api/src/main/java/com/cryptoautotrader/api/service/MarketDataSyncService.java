package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 모의투자/실거래 공통 시장 데이터 동기화 서비스.
 * RUNNING 세션에서 고유 (coinPair, timeframe) 조합만 추출해
 * Upbit API를 1회 호출하고 candle_data 테이블에 upsert한다.
 * PaperTradingService 는 이 데이터를 DB에서 읽기만 한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataSyncService {

    /** 전략 계산에 필요한 캔들 수보다 넉넉하게 확보 */
    private static final int SYNC_CANDLE_COUNT = 120;

    private final VirtualBalanceRepository balanceRepo;
    private final MarketDataCacheRepository marketDataCacheRepo;

    /**
     * 60초마다 실행. PaperTradingService.runStrategy() 보다
     * initialDelay 만큼 먼저 실행되어 데이터를 준비한다.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void syncMarketData() {
        // RUNNING 세션에서 고유 (coinPair:timeframe) 추출
        List<String[]> pairs = balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING").stream()
                .map(s -> new String[]{s.getCoinPair(), s.getTimeframe()})
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(() -> new java.util.ArrayList<>()),
                        list -> {
                            Set<String> seen = new java.util.HashSet<>();
                            list.removeIf(p -> !seen.add(p[0] + ":" + p[1]));
                            return list;
                        }
                ));

        if (pairs.isEmpty()) return;

        log.debug("시장 데이터 동기화 시작: {} 종목", pairs.size());
        for (String[] pair : pairs) {
            try {
                syncPair(pair[0], pair[1]);
            } catch (Exception e) {
                log.error("시장 데이터 동기화 실패: {} {} - {}", pair[0], pair[1], e.getMessage());
            }
        }
    }

    private void syncPair(String coinPair, String timeframe) {
        Instant to = Instant.now();
        Instant from = to.minus(SYNC_CANDLE_COUNT * timeframeMinutes(timeframe), ChronoUnit.MINUTES);

        UpbitRestClient restClient = new UpbitRestClient();
        UpbitCandleCollector collector = new UpbitCandleCollector(restClient);
        List<Candle> candles = collector.fetchCandles(coinPair, timeframe, from, to);

        if (candles.isEmpty()) {
            log.warn("캔들 수신 없음: {} {}", coinPair, timeframe);
            return;
        }

        List<MarketDataCacheEntity> entities = candles.stream()
                .map(c -> MarketDataCacheEntity.builder()
                        .time(c.getTime())
                        .coinPair(coinPair)
                        .timeframe(timeframe)
                        .open(c.getOpen())
                        .high(c.getHigh())
                        .low(c.getLow())
                        .close(c.getClose())
                        .volume(c.getVolume())
                        .build())
                .toList();

        // JPA merge: 동일 PK(time+coinPair+timeframe) 존재 시 UPDATE, 없으면 INSERT
        marketDataCacheRepo.saveAll(entities);
        log.debug("시장 데이터 동기화 완료: {} {} {}건", coinPair, timeframe, entities.size());
    }

    private long timeframeMinutes(String timeframe) {
        return switch (timeframe) {
            case "M1" -> 1;
            case "M5" -> 5;
            case "H1" -> 60;
            case "D1" -> 1440;
            default -> 60;
        };
    }
}
