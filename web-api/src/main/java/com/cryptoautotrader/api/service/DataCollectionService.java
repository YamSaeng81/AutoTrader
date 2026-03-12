package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.CandleDataEntity;
import com.cryptoautotrader.api.repository.CandleDataRepository;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataCollectionService {

    private final CandleDataRepository candleDataRepository;

    @Async("taskExecutor")
    public void collectCandles(String coinPair, String timeframe, LocalDate startDate, LocalDate endDate) {
        log.info("캔들 수집 시작: {} {} {} ~ {}", coinPair, timeframe, startDate, endDate);

        UpbitRestClient restClient = new UpbitRestClient();
        UpbitCandleCollector collector = new UpbitCandleCollector(restClient);

        Instant from = startDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        Instant to = endDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

        List<Candle> candles = collector.fetchCandles(coinPair, timeframe, from, to);

        List<CandleDataEntity> entities = candles.stream()
                .map(c -> CandleDataEntity.builder()
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

        candleDataRepository.saveAll(entities);
        log.info("캔들 수집 완료: {} {}건 저장", coinPair, entities.size());
    }
}
