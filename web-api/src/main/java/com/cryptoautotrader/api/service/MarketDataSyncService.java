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

    /**
     * 갱신 갭 조회 시 안전하게 겹쳐서 다시 받는 캔들 수 — 거래소 쪽 늦은 정정(체결 재계산 등)을
     * 놓치지 않기 위한 여유분. upsert(PK: time+coinPair+timeframe)라 겹쳐도 중복이 안 생긴다.
     */
    private static final int GAP_SYNC_OVERLAP_CANDLES = 5;

    private void syncPair(String coinPair, String timeframe) {
        if (upbitRestClient == null) {
            log.warn("UpbitRestClient Bean 미등록 — 시장 데이터 동기화 건너뜀: {} {}", coinPair, timeframe);
            return;
        }

        Instant to = Instant.now();
        Instant fullFrom = to.minus(SYNC_CANDLE_COUNT * TimeframeUtils.toMinutes(timeframe), ChronoUnit.MINUTES);

        // ── 2026-08-25: 매 실행마다 520개 전량 재수집하던 것을 "마지막 저장 이후 갭만" 으로 축소.
        // 이 서비스가 60초마다 도는데, H1은 1시간에 1개, M15는 15분에 1개만 늘어난다 — 그런데도
        // 매번 코인당 최대 3페이지(520/200)를 REST로 재요청했다. 앱 전체가 Upbit 호출을
        // 하나의 공유 스로틀(초당 ~9회)로 직렬화하는 구조라(UpbitRestClient.throttle), 이 낭비가
        // DynamicTradingService.tick() 등 다른 소비자의 API 호출을 대기열 뒤로 밀어내
        // 사이클이 수 분씩 지연되는 원인이었다. 이미 데이터가 있으면 마지막 저장 시각에서
        // 살짝(GAP_SYNC_OVERLAP_CANDLES) 겹쳐서만 받는다 — 데이터가 없거나(첫 동기화) 공백이
        // SYNC_CANDLE_COUNT 범위를 넘겨 벌어졌으면(장기 다운타임 등) 기존처럼 전체를 받는다.
        Instant lastStored = marketDataCacheRepo.findMaxTime(coinPair, timeframe);
        Instant from = fullFrom;
        if (lastStored != null) {
            Instant gapFrom = lastStored.minus(
                    GAP_SYNC_OVERLAP_CANDLES * TimeframeUtils.toMinutes(timeframe), ChronoUnit.MINUTES);
            if (gapFrom.isAfter(fullFrom)) {
                from = gapFrom;
            }
        }

        UpbitCandleCollector collector = new UpbitCandleCollector(upbitRestClient);
        List<Candle> candles = collector.fetchCandles(coinPair, timeframe, from, to);

        if (candles.isEmpty()) {
            log.warn("캔들 수신 없음: {} {}", coinPair, timeframe);
            return;
        }

        // JPA merge: 동일 PK(time+coinPair+timeframe) 존재 시 UPDATE, 없으면 INSERT
        marketDataCacheRepo.saveAll(toEntities(candles, coinPair, timeframe));
        log.debug("시장 데이터 동기화 완료: {} {} {}건", coinPair, timeframe, candles.size());
    }

    /**
     * DYNAMIC 스캔 루프 전용 — {@code lookbackCandles}개 구간을 캐시 우선으로 채워 반환한다.
     *
     * <p>2026-08-26: {@code DynamicTradingService.fetchCandles}가 워치리스트 코인마다 매 틱
     * {@link UpbitCandleCollector#fetchCandles}를 직접 호출해 항상 500개 전량을 Upbit REST로
     * 받아오고 있었다 — {@link #syncPair}가 갭만 받도록 최적화된 것과 별개로, DYNAMIC은 애초에
     * market_data_cache를 전혀 참조하지 않는 경로였다. 스로틀 경합의 실제 대부분은 여기였다.
     * {@link #syncPair}와 같은 갭 조회 로직으로 캐시를 채우고, 캐시에 이미 있는 구간은 REST를
     * 다시 부르지 않는다.</p>
     */
    @Transactional
    public List<Candle> fetchWithCache(String coinPair, String timeframe, int lookbackCandles) {
        if (upbitRestClient == null) return List.of();

        Instant to = Instant.now();
        Instant fullFrom = to.minus((long) lookbackCandles * TimeframeUtils.toMinutes(timeframe), ChronoUnit.MINUTES);

        Instant lastStored = marketDataCacheRepo.findMaxTime(coinPair, timeframe);
        Instant from = fullFrom;
        if (lastStored != null) {
            Instant gapFrom = lastStored.minus(
                    GAP_SYNC_OVERLAP_CANDLES * TimeframeUtils.toMinutes(timeframe), ChronoUnit.MINUTES);
            if (gapFrom.isAfter(fullFrom)) {
                from = gapFrom;
            }
        }

        UpbitCandleCollector collector = new UpbitCandleCollector(upbitRestClient);
        List<Candle> fetched = collector.fetchCandles(coinPair, timeframe, from, to);
        if (!fetched.isEmpty()) {
            marketDataCacheRepo.saveAll(toEntities(fetched, coinPair, timeframe));
        }

        // 캐시가 아예 없었거나 갭이 lookback 범위를 넘어 전체를 재수집한 경우, 방금 받은 것이
        // 곧 전체 구간이다 — 캐시를 다시 읽을 필요 없다.
        if (from.equals(fullFrom)) {
            return fetched;
        }

        return marketDataCacheRepo.findCandles(coinPair, timeframe, fullFrom, to).stream()
                .map(e -> Candle.builder()
                        .time(e.getTime()).open(e.getOpen()).high(e.getHigh())
                        .low(e.getLow()).close(e.getClose()).volume(e.getVolume())
                        .build())
                .toList();
    }

    private List<MarketDataCacheEntity> toEntities(List<Candle> candles, String coinPair, String timeframe) {
        return candles.stream()
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
    }

}
