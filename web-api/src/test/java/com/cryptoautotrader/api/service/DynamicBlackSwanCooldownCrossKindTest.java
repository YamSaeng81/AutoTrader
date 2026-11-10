package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DYNAMIC PAPER ↔ REAL 상태 간섭 최소 재현 (2026-11-10, 총괄 v1.144 D) — 코인별 BLACK_SWAN 쿨다운 기록
 * ({@code blackSwanBlockedAt}·{@code blackSwanBlockedPrice})이 세션 종류를 가리지 않는 서비스 전역 지도다.
 *
 * <p>운영 호출부 {@link DynamicTradingService#processTick}(스케줄러가 세션마다 부르는 틱)을 그대로 부른다. 전략은 매번 BUY 를 내는
 * 시험 전략 하나(두 세션 같음)라 관문 효과만 남는다. 거래소 주문·시세·텔레그램·공용 유니버스는 목이다(외부 호출 0).</p>
 *
 * <p>기대(실행 전 기록): PAPER 세션이 급락 마감 봉에서 BUY 를 평가하면 쿨다운이 기록되고, 회복된 캔들에서 REAL 세션의 BUY 가
 * "BLACK_SWAN 쿨다운"으로 막힌다. PAPER 틱이 없으면(대조) 같은 REAL 틱은 그 사유로 막히지 않는다 — 즉 PAPER 활동이 REAL 판단을 바꾼다.
 * 이 시험은 현재 동작을 기록할 뿐이며 고치지 않는다(운영 계약 변경은 총괄 판단).</p>
 */
@DisplayName("DYNAMIC BLACK_SWAN 쿨다운 — PAPER 평가가 REAL 진입을 막는가(세션 종류 간 공유 상태)")
@org.springframework.test.context.TestPropertySource(properties = "test.fixture.entry-rounds-context=DynamicBlackSwanCooldownCrossKindTest")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DynamicBlackSwanCooldownCrossKindTest extends IntegrationTestBase {

    private static final String COIN = "KRW-XRP";
    private static final String BTC = "KRW-BTC";
    private static final String STRATEGY = "TEST_DYN_ALWAYS_BUY";

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private DynamicSessionRepository sessionRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private StrategyLogRepository strategyLogRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository cacheRepository;

    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private PortfolioSyncService portfolioSyncService;
    @MockBean private SharedUniverseService sharedUniverseService;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient upbitWebSocketClient;

    private final List<Long> ids = new ArrayList<>();
    private Instant lastOpen;

    @BeforeEach
    void setUp() {
        StrategyRegistry.register(new Strategy() {
            @Override public String getName() { return STRATEGY; }
            @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
                return StrategySignal.buy(new BigDecimal("80"), "시험 — 항상 BUY");
            }
            @Override public int getMinimumCandleCount() { return 210; }
        });
        lastOpen = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        deleteCache();
        globalMaps().forEach(Map::clear);
    }

    @AfterEach
    void tearDown() {
        for (Long id : ids) {
            String kind = DynamicTradingService.sessionKind(sessionRepo.findById(id).orElseThrow());
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, id).forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId(kind, id).forEach(positionRepository::delete);
            strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc(kind, id).forEach(strategyLogRepository::delete);
            sessionRepo.deleteById(id);
        }
        ids.clear();
        deleteCache();
        globalMaps().forEach(Map::clear);
    }

    private List<Map<String, ?>> globalMaps() {
        DynamicTradingService t = AopTestUtils.getUltimateTargetObject(dynamicTradingService);
        return List.of(t.blackSwanBlockedAt, t.blackSwanBlockedPrice);
    }

    private void deleteCache() {
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair IN (?, ?) AND timeframe = 'H1'", COIN, BTC);
    }

    /**
     * 닫힌 H1 500봉 상승(시간당 +0.2% — EMA200 이 충분히 아래라 −10% 급락 봉도 EMA200 관문을 지난다. 2회차는 +0.05% 라 EMA200 관문이 먼저 막았다). crash 면 마지막 봉 종가가 앞 봉 고가보다 10% 낮다.
     * DYNAMIC 은 캔들을 {@code MarketDataSyncService.fetchWithCache}(캐시 + 부족분 거래소 수집)로 읽는다 — 목이 그 응답을 정한다.
     */
    private void insert(String coin, long base, boolean crash) {
        List<Candle> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            BigDecimal c = BigDecimal.valueOf(base + base / 500 * i);
            BigDecimal o = BigDecimal.valueOf(base + base / 500 * Math.max(0, i - 1));
            BigDecimal h = c.max(o).add(BigDecimal.valueOf(base / 10000));
            BigDecimal l = c.min(o).subtract(BigDecimal.valueOf(base / 10000));
            if (crash && i == 499) {
                c = o.multiply(new BigDecimal("0.90"));
                l = c;
                h = o;
            }
            rows.add(Candle.builder().time(lastOpen.minus(499 - i, ChronoUnit.HOURS))
                    .open(o).high(h).low(l).close(c).volume(new BigDecimal("1000")).build());
        }
        org.mockito.Mockito.when(marketDataSyncService.fetchWithCache(
                org.mockito.ArgumentMatchers.eq(coin), org.mockito.ArgumentMatchers.eq("H1"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(rows);
    }

    private Long session(String mode) {
        Long id = sessionRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType(STRATEGY).timeframe("H1")
                .initialCapital(new BigDecimal("100000")).availableKrw(new BigDecimal("100000")).totalAssetKrw(new BigDecimal("100000"))
                .investRatio(new BigDecimal("0.2000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode(mode)
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(600).watchlistJson("[\"" + COIN + "\"]").watchlistRefreshedAt(Instant.now())
                .startedAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .build()).getId();
        ids.add(id);
        return id;
    }

    private void tick(Long id) {
        dynamicTradingService.processTick(sessionRepo.findById(id).orElseThrow());
    }

    private String lastLog(Long id) {
        String kind = DynamicTradingService.sessionKind(sessionRepo.findById(id).orElseThrow());
        List<StrategyLogEntity> logs = strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc(kind, id);
        return logs.isEmpty() ? "로그 없음" : logs.get(0).getSignal() + " / " + logs.get(0).getReason()
                + (logs.get(0).getBlockedReason() != null ? " / 차단=" + logs.get(0).getBlockedReason() : "");
    }

    @Test
    @DisplayName("간섭 — PAPER 가 급락 봉을 평가해 쿨다운을 기록하면, 회복 뒤 REAL 의 BUY 가 'BLACK_SWAN 쿨다운'으로 막힌다")
    void paperCooldownBlocksReal() {
        insert(BTC, 100_000_000L, false);
        insert(COIN, 1_000_000L, true);
        Long paper = session("PAPER");
        tick(paper);
        String paperLog = lastLog(paper);
        System.out.println("[간섭] PAPER 틱: " + paperLog);
        DynamicTradingService t = AopTestUtils.getUltimateTargetObject(dynamicTradingService);
        assertThat(t.blackSwanBlockedAt).as("PAPER 평가가 전역 쿨다운을 기록").containsKey(COIN);

        deleteCache();
        insert(BTC, 100_000_000L, false);
        insert(COIN, 1_000_000L, false);
        Long real = session(DynamicSessionEntity.DEFAULT_TRADING_MODE);
        tick(real);
        String realLog = lastLog(real);
        System.out.println("[간섭] REAL 틱(회복 캔들): " + realLog);
        assertThat(realLog).contains("BLACK_SWAN 쿨다운");
    }

    @Test
    @DisplayName("대조 — PAPER 틱 없이 같은 회복 캔들의 REAL 틱은 BLACK_SWAN 쿨다운으로 막히지 않는다")
    void realWithoutPaperIsNotBlocked() {
        insert(BTC, 100_000_000L, false);
        insert(COIN, 1_000_000L, false);
        Long real = session(DynamicSessionEntity.DEFAULT_TRADING_MODE);
        tick(real);
        String realLog = lastLog(real);
        System.out.println("[대조] REAL 틱(회복 캔들): " + realLog);
        assertThat(realLog).doesNotContain("BLACK_SWAN");
        assertThat(realLog).isNotEqualTo("로그 없음");
    }

    /** 재기동 흉내 — 서비스 전역 지도를 비우고 운영 기동 처리기 restoreBlackSwanCooldown 을 그대로 부른다. */
    private Map<String, java.time.Instant> restartAndRestore() {
        DynamicTradingService t = AopTestUtils.getUltimateTargetObject(dynamicTradingService);
        globalMaps().forEach(Map::clear);
        t.restoreBlackSwanCooldown();
        return t.blackSwanBlockedAt;
    }

    @Test
    @DisplayName("기동 복원 비대칭 — PAPER 가 만든 쿨다운은 재기동 뒤 복원되지 않는다(재기동 전에는 REAL 을 막았다)")
    void paperOriginatedCooldownNotRestored() {
        insert(BTC, 100_000_000L, false);
        insert(COIN, 1_000_000L, true);
        Long paper = session("PAPER");
        tick(paper);
        DynamicTradingService t = AopTestUtils.getUltimateTargetObject(dynamicTradingService);
        assertThat(t.blackSwanBlockedAt).as("재기동 전 — PAPER 평가가 기록").containsKey(COIN);
        System.out.println("[복원] PAPER 차단 로그: " + lastLog(paper));
        assertThat(restartAndRestore()).as("재기동 뒤 — PAPER 기록은 복원 대상(DYNAMIC 로그) 밖").doesNotContainKey(COIN);
    }

    @Test
    @DisplayName("기동 복원 대조 — REAL 이 만든 쿨다운은 재기동 뒤 복원된다")
    void realOriginatedCooldownRestored() {
        insert(BTC, 100_000_000L, false);
        insert(COIN, 1_000_000L, true);
        Long real = session(DynamicSessionEntity.DEFAULT_TRADING_MODE);
        tick(real);
        System.out.println("[복원] REAL 차단 로그: " + lastLog(real));
        assertThat(restartAndRestore()).as("재기동 뒤 — REAL 기록 복원").containsKey(COIN);
    }
}
