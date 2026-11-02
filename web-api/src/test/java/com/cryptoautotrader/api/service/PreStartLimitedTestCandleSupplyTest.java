package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.UpbitCandleResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N08 P0 (2026-11-02) — 제한시험 최초 시작 전({@code CREATED}·{@code started_at} 없음) BTC H1 수집이 다른 매매 세션에 기대지 않는다.
 *
 * <p>운영 재현(11-02 11:20 KST): H1 LIVE/DYNAMIC 실행 세션 0·직접 PAPER BTC/H1 0 이 되자 KRW-BTC H1 완료 봉이 09:00 에 멈췄다.
 * SID 203 은 12-01 까지 {@code CREATED} 라 {@link MarketDataSyncService} 의 수집 대상(RUNNING 만)에 들어가지 않았다.</p>
 *
 * <p>대역은 거래소 캔들 응답 하나다. 수집·저장은 실제 코드다. 이 시험 동안 다른 실행 중 세션(LIVE·DYNAMIC 전체, 직접 PAPER BTC/H1)은
 * 멈췄다가 끝에 되돌린다 — "모든 H1 매매 세션이 정지되고 SID 203 만 남은" 운영 상태를 만든다.</p>
 */
@DisplayName("N08 — 제한시험 시작 전 BTC H1 수집: CREATED 유지·수집만, 일반 CREATED 제외, 시작 뒤 중복 없음·종료 뒤 제거")
class PreStartLimitedTestCandleSupplyTest extends IntegrationTestBase {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String BTC_H1 = "KRW-BTC:H1";

    @Autowired private MarketDataSyncService marketDataSyncService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private MarketDataCacheRepository cacheRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private TelegramNotificationService telegramService;

    private final List<Long> liveIds = new ArrayList<>();
    private final List<Long> dynIds = new ArrayList<>();
    private List<Long> pausedLive;
    private List<Long> pausedDyn;
    private List<Long> pausedPaper;
    /** 거래소 대역의 마지막 완료 봉 시작 — 바꾸면 다음 수집에서 봉이 전진한다. */
    private volatile Instant lastOpen;

    @BeforeEach
    void setUp() throws Exception {
        pausedLive = jdbc.queryForList("SELECT id FROM live_trading_session WHERE status = 'RUNNING'", Long.class);
        pausedDyn = jdbc.queryForList("SELECT id FROM dynamic_session WHERE status = 'RUNNING'", Long.class);
        pausedPaper = jdbc.queryForList("SELECT id FROM paper_trading.virtual_balance WHERE status = 'RUNNING'"
                + " AND coin_pair = 'KRW-BTC' AND timeframe = 'H1'", Long.class);
        pausedLive.forEach(id -> jdbc.update("UPDATE live_trading_session SET status = 'STOPPED' WHERE id = ?", id));
        pausedDyn.forEach(id -> jdbc.update("UPDATE dynamic_session SET status = 'STOPPED' WHERE id = ?", id));
        pausedPaper.forEach(id -> jdbc.update("UPDATE paper_trading.virtual_balance SET status = 'STOPPED' WHERE id = ?", id));
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1'");

        lastOpen = Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.HOURS);
        when(upbitRestClient.getCandles(anyString(), eq("minutes"), eq(60), any(Instant.class), anyInt()))
                .thenAnswer(inv -> candlesBefore(inv.getArgument(0), inv.getArgument(3), inv.getArgument(4)));
    }

    @AfterEach
    void tearDown() {
        for (Long id : liveIds) {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
        dynIds.forEach(id -> dynamicRepo.findById(id).ifPresent(dynamicRepo::delete));
        pausedLive.forEach(id -> jdbc.update("UPDATE live_trading_session SET status = 'RUNNING' WHERE id = ?", id));
        pausedDyn.forEach(id -> jdbc.update("UPDATE dynamic_session SET status = 'RUNNING' WHERE id = ?", id));
        pausedPaper.forEach(id -> jdbc.update("UPDATE paper_trading.virtual_balance SET status = 'RUNNING' WHERE id = ?", id));
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1'");
    }

    /** 업비트 캔들 API 흉내 — {@code to} 이전 봉을 최신순으로 {@code count} 개. */
    private List<UpbitCandleResponse> candlesBefore(String coin, Instant to, int count) {
        List<UpbitCandleResponse> out = new ArrayList<>();
        for (int i = 0; i < 520 && out.size() < count; i++) {
            Instant t = lastOpen.minus(i, ChronoUnit.HOURS);
            if (!t.isBefore(to)) continue;
            BigDecimal p = new BigDecimal(1000 + (519 - i));
            UpbitCandleResponse r = new UpbitCandleResponse();
            r.setMarket(coin);
            r.setCandleDateTimeUtc(UTC.format(t));
            r.setOpeningPrice(p);
            r.setHighPrice(p.add(BigDecimal.ONE));
            r.setLowPrice(p.subtract(BigDecimal.ONE));
            r.setTradePrice(p);
            r.setCandleAccTradeVolume(new BigDecimal("1000"));
            out.add(r);
        }
        return out;
    }

    private LiveTradingSessionEntity live(String sessionType, String strategy, String coin, String status, Instant startedAt) {
        LiveTradingSessionEntity s = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType(sessionType).strategyType(strategy).coinPair(coin).timeframe("H1")
                .initialCapital(new BigDecimal("20200")).availableKrw(new BigDecimal("20200"))
                .totalAssetKrw(new BigDecimal("20200")).status(status).startedAt(startedAt).build());
        liveIds.add(s.getId());
        return s;
    }

    /** SID 203 과 같은 모양 — REAL·DAILY_SMA20·KRW-BTC·H1·CREATED·started_at 없음. */
    private LiveTradingSessionEntity preStartLimitedTest() {
        return live("REAL", "DAILY_SMA20", "KRW-BTC", "CREATED", null);
    }

    private long btcH1Targets() {
        return marketDataSyncService.collectionTargets().stream().filter(p -> BTC_H1.equals(p[0] + ":" + p[1])).count();
    }

    @Test
    @DisplayName("모든 H1 매매 세션 정지·SID 203 만 CREATED: BTC H1 이 수집되고 다음 회차에 봉이 전진한다 — 세션은 CREATED·시각·잔액·주문 그대로")
    void preStartLimitedTestKeepsBtcH1Fresh() {
        LiveTradingSessionEntity sid = preStartLimitedTest();

        marketDataSyncService.syncMarketData();
        assertThat(cacheRepository.findMaxTime("KRW-BTC", "H1")).as("시작 전에도 BTC H1 이 수집된다").isEqualTo(lastOpen);

        lastOpen = lastOpen.plus(1, ChronoUnit.HOURS);   // 한 시간 뒤 — 새 완료 봉
        marketDataSyncService.syncMarketData();
        assertThat(cacheRepository.findMaxTime("KRW-BTC", "H1")).as("다음 회차에 완료 봉이 전진한다").isEqualTo(lastOpen);

        LiveTradingSessionEntity after = liveRepo.findById(sid.getId()).orElseThrow();
        assertThat(after.getStatus()).as("수집만 — 시작하지 않는다").isEqualTo("CREATED");
        assertThat(after.getStartedAt()).as("56일 기산점 불변").isNull();
        assertThat(after.getAvailableKrw()).isEqualByComparingTo("20200");
        assertThat(orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", sid.getId())).as("주문 없음").isEmpty();
        assertThat(positionRepository.findBySessionKindAndSessionId("LIVE", sid.getId())).as("포지션 없음").isEmpty();
    }

    @Test
    @DisplayName("일반 CREATED·모의·시작된 적 있는·다른 코인 SMA20 은 수집 대상이 아니다")
    void onlyPreStartLimitedTestIsCollected() throws Exception {
        live("REAL", "EMA_CROSS", "KRW-BTC", "CREATED", null);                       // 일반 전략
        live("PAPER", "DAILY_SMA20", "KRW-BTC", "CREATED", null);                     // 실자금 아님
        live("REAL", "DAILY_SMA20", "KRW-BTC", "CREATED", Instant.now());             // 최초 시작 전이 아님
        live("REAL", "DAILY_SMA20", "KRW-ETH", "CREATED", null);                      // 다른 코인
        live("REAL", "DAILY_SMA20", "KRW-BTC", "STOPPED", Instant.now().minus(1, ChronoUnit.DAYS)); // 종료된 시험

        assertThat(marketDataSyncService.collectionTargets()).as("수집 대상 없음").isEmpty();
        marketDataSyncService.syncMarketData();
        verify(upbitRestClient, never()).getCandles(anyString(), anyString(), anyInt(), any(Instant.class), anyInt());
    }

    @Test
    @DisplayName("CREATED→RUNNING 대상 1개(중복 없음), 시험 종료 뒤 제거, 다른 소비자가 있으면 유지")
    void transitionsKeepSingleTargetAndRemoveAfterEnd() {
        LiveTradingSessionEntity sid = preStartLimitedTest();
        assertThat(btcH1Targets()).as("시작 전").isEqualTo(1);

        LiveTradingSessionEntity running = liveRepo.findById(sid.getId()).orElseThrow();
        running.setStatus("RUNNING");
        running.setStartedAt(Instant.now());
        liveRepo.save(running);
        assertThat(btcH1Targets()).as("실행 중 — 세션 코인과 BTC 관문 요구가 겹쳐도 1개").isEqualTo(1);

        LiveTradingSessionEntity stopped = liveRepo.findById(sid.getId()).orElseThrow();
        stopped.setStatus("STOPPED");
        liveRepo.save(stopped);
        assertThat(btcH1Targets()).as("종료 뒤 — 다른 소비자가 없으면 제거").isZero();

        DynamicSessionEntity d = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("EMA_CROSS").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("10000")).totalAssetKrw(new BigDecimal("10000"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode("PAPER")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000")).watchlistRefreshMin(60)
                .watchlistJson("[]").watchlistRefreshedAt(Instant.now()).startedAt(Instant.now())
                .build());
        dynIds.add(d.getId());
        assertThat(btcH1Targets()).as("다른 소비자(H1 DYNAMIC)의 관문 요구는 유지").isEqualTo(1);
    }
}
