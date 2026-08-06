package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.util.TimeframeUtils;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
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

    /**
     * pair당 동기화할 캔들 개수.
     *
     * <p><b>반드시 소비 측 lookback 이상이어야 한다</b> — {@code PaperTradingService.CANDLE_LOOKBACK}과
     * {@code LiveTradingService.CANDLE_LOOKBACK}이 500이고, EMA200 계열 전략은 닫힌 캔들 201개
     * 이상을 요구한다. 2026-08-06 이전 값 120은 이 둘 모두에 미달이라, <b>이력이 없는 신규 코인으로
     * 세션을 만들면 캔들이 120개만 쌓여 EMA200 전략이 구조적으로 신호를 낼 수 없었다</b>
     * (운영 실측: market_data_cache H1에 BTC·ETH만 2,305건이고 나머지는 수백 건에 stale).</p>
     *
     * <p>소비 측은 항상 "최근 500개 구간"만 조회하므로, 이 값이 500 이상이면 과거 데이터에 갭이
     * 있어도 평가 구간은 연속으로 채워진다. UpbitCandleCollector가 200개 단위로 페이지네이션하므로
     * pair당 3회 호출이 된다(10코인 = 30회/분, 업비트 공개 API 한도에 여유).</p>
     */
    private static final int SYNC_CANDLE_COUNT = 520;

    private final VirtualBalanceRepository balanceRepo;
    private final MarketDataCacheRepository marketDataCacheRepo;
    private final LiveTradingSessionRepository liveTradingSessionRepository;

    /** EngineConfig Bean — API 키 없이도 공개 캔들 API 사용 가능 */
    @Autowired(required = false)
    private UpbitRestClient upbitRestClient;

    /**
     * 60초마다 실행. PaperTradingService.runStrategy() 보다
     * initialDelay 만큼 먼저 실행되어 데이터를 준비한다.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void syncMarketData() {
        // RUNNING 세션에서 고유 (coinPair:timeframe) 추출 — 모의투자 + 실전매매 모두 포함
        List<String[]> rawPairs = new ArrayList<>();
        balanceRepo.findByStatusOrderByStartedAtAsc("RUNNING")
                .forEach(s -> rawPairs.add(new String[]{s.getCoinPair(), s.getTimeframe()}));
        liveTradingSessionRepository.findByStatus("RUNNING")
                .forEach(s -> rawPairs.add(new String[]{s.getCoinPair(), s.getTimeframe()}));

        Set<String> seen = new HashSet<>();
        List<String[]> pairs = rawPairs.stream()
                .filter(p -> seen.add(p[0] + ":" + p[1]))
                .collect(Collectors.toList());

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
        if (upbitRestClient == null) {
            log.warn("UpbitRestClient Bean 미등록 — 시장 데이터 동기화 건너뜀: {} {}", coinPair, timeframe);
            return;
        }

        Instant to = Instant.now();
        Instant from = to.minus(SYNC_CANDLE_COUNT * TimeframeUtils.toMinutes(timeframe), ChronoUnit.MINUTES);

        UpbitCandleCollector collector = new UpbitCandleCollector(upbitRestClient);
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

}
