package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 청산 A/B 팔 생성 계약 (2026-11-10, 총괄 v1.144 E) — 요청 초안의 8개 본문을 운영 HTTP 생성 경로로 만들어 저장값을 확인한다.
 *
 * <p>확인: A 의 {@code stopLossPct} 는 저장값 null(진입 시 하한 0 — DTO 주석의 "미지정 시 5.0" 은 이 경로에서 사실이 아니다),
 * B 는 5.00, 나머지(초기 100,000·비율 0.8·상한 20,000·시간 청산 24·전략·주기·코인·파라미터)는 쌍 안에서 같다. 규칙 지문은 A≠B.
 * 같은 입력의 진입에서 매수금은 같고 손절·익절 거리만 갈린다(익절은 손절 폭에서 파생 — 가설에 포함).
 * 진입 비교의 전략은 대역(DAILY_SMA20, 일봉 경계만 옮긴 인스턴스)이다 — 팔 계약만 보며 A/B 전략 성과와 무관하다.</p>
 */
@DisplayName("청산 A/B 팔 생성 계약 — 8개 요청 본문·저장값·지문·같은 입력 진입의 손절/익절 차이")
class ExitAbArmContractTest extends IntegrationTestBase {

    @Autowired private PaperTradingService paperTradingService;
    @Autowired private VirtualBalanceRepository balanceRepo;
    @Autowired private PaperPositionRepository positionRepo;
    @Autowired private PaperOrderRepository orderRepo;
    @Autowired private StrategyLogRepository strategyLogRepository;
    @Autowired private RulesetRegistry rulesetRegistry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository cacheRepository;

    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private TelegramNotificationService telegramService;

    private final List<Long> ids = new ArrayList<>();

    @AfterEach
    void tearDown() {
        StrategyRegistry.register(new DailySmaTrendStrategy());
        TransactionTemplate tx = new TransactionTemplate(txManager);
        for (Long id : ids) {
            tx.executeWithoutResult(st -> orderRepo.deleteBySessionId(id));
            positionRepo.findBySessionId(id).forEach(positionRepo::delete);
            strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("PAPER", id).forEach(strategyLogRepository::delete);
            balanceRepo.findById(id).ifPresent(balanceRepo::delete);
        }
        ids.clear();
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = 'KRW-BTC' AND timeframe = 'H1'");
    }

    /** 요청 초안과 같은 본문(문서 REQUEST_DRAFT.json). A 는 stopLossPct 키 자체가 없다. */
    static String body(String strategy, String coin, String arm) {
        return "{\"strategyType\":\"" + strategy + "\",\"coinPair\":\"" + coin + "\",\"timeframe\":\"M15\","
                + "\"initialCapital\":100000,\"investRatio\":0.8,\"maxHoldHours\":24,"
                + ("B".equals(arm) ? "\"stopLossPct\":5," : "")
                + "\"strategyParams\":{\"paperMaxInvestment\":20000}}";
    }

    private VirtualBalanceEntity createViaHttp(String json) throws Exception {
        long maxBefore = balanceRepo.findAll().stream().mapToLong(VirtualBalanceEntity::getId).max().orElse(0);
        int status = mockMvc.perform(post("/api/v1/paper-trading/sessions").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getStatus();
        assertThat(status).as(json).isEqualTo(200);
        VirtualBalanceEntity s = balanceRepo.findAll().stream().filter(v -> v.getId() > maxBefore)
                .max(Comparator.comparingLong(VirtualBalanceEntity::getId)).orElseThrow();
        ids.add(s.getId());
        return s;
    }

    @Test
    @DisplayName("8개 본문 — 저장값은 stopLossPct(null/5.00)만 다르고 지문은 A≠B")
    void eightArmsStoredContract() throws Exception {
        for (String strategy : List.of("COMPOSITE_MEANREV_BB", "COMPOSITE_MTF_CONFIRMED")) {
            for (String coin : List.of("KRW-BTC", "KRW-ETH")) {
                VirtualBalanceEntity a = createViaHttp(body(strategy, coin, "A"));
                VirtualBalanceEntity b = createViaHttp(body(strategy, coin, "B"));
                String label = strategy + " " + coin;
                System.out.printf("[A/B 생성] %s A#%d sl=%s hash=%s / B#%d sl=%s hash=%s%n", label,
                        a.getId(), a.getStopLossPct(), rulesetRegistry.hashFor(a), b.getId(), b.getStopLossPct(), rulesetRegistry.hashFor(b));
                assertThat(a.getStopLossPct()).as(label + " A").isNull();
                assertThat(b.getStopLossPct()).as(label + " B").isEqualByComparingTo("5");
                for (VirtualBalanceEntity s : List.of(a, b)) {
                    assertThat(s.getStrategyName()).isEqualTo(strategy);
                    assertThat(s.getCoinPair()).isEqualTo(coin);
                    assertThat(s.getTimeframe()).isEqualTo("M15");
                    assertThat(s.getInitialCapital()).isEqualByComparingTo("100000");
                    assertThat(s.getInvestRatio()).isEqualByComparingTo("0.8");
                    assertThat(s.getMaxHoldHours()).isEqualTo(24);
                    assertThat(s.getStrategyParams()).containsEntry(PaperMaxInvestment.KEY, 20000).hasSize(1);
                    assertThat(s.getStatus()).isEqualTo("RUNNING");
                }
                assertThat(rulesetRegistry.hashFor(a)).as(label + " 지문").isNotEqualTo(rulesetRegistry.hashFor(b));
            }
        }
    }

    @Test
    @DisplayName("같은 입력 진입 — 매수금 같음(100,000×0.8 → 상한 20,000), A 손절 거리는 ATR 식·B 는 5%, 익절은 손절 폭에서 파생")
    void sameInputEntryDiffersOnlyInExitLevels() {
        Instant lastOpen = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        StrategyRegistry.register(new DailySmaTrendStrategy(lastOpen.atZone(ZoneOffset.UTC).getHour()));
        List<Candle> cs = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            BigDecimal c = BigDecimal.valueOf(10_000_000L + 5_000L * i);
            BigDecimal o = BigDecimal.valueOf(10_000_000L + 5_000L * Math.max(0, i - 1));
            cs.add(Candle.builder().time(lastOpen.minus(499 - i, ChronoUnit.HOURS)).open(o)
                    .high(c.max(o).add(BigDecimal.valueOf(30_000))).low(c.min(o).subtract(BigDecimal.valueOf(30_000)))
                    .close(c).volume(new BigDecimal("100")).build());
        }
        cacheRepository.saveAll(cs.stream().map(c -> com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                .time(c.getTime()).coinPair("KRW-BTC").timeframe("H1").open(c.getOpen()).high(c.getHigh())
                .low(c.getLow()).close(c.getClose()).volume(c.getVolume()).build()).toList());
        VirtualBalanceEntity a = start("A");
        VirtualBalanceEntity b = start("B");
        Object target = AopTestUtils.getUltimateTargetObject(paperTradingService);
        ReflectionTestUtils.invokeMethod(target, "runSessionStrategy", balanceRepo.findById(a.getId()).orElseThrow());
        ReflectionTestUtils.invokeMethod(target, "runSessionStrategy", balanceRepo.findById(b.getId()).orElseThrow());
        PaperPositionEntity pa = positionRepo.findBySessionId(a.getId()).get(0);
        PaperPositionEntity pb = positionRepo.findBySessionId(b.getId()).get(0);
        BigDecimal slA = pct(pa.getEntryPrice(), pa.getStopLossPrice());
        BigDecimal slB = pct(pb.getEntryPrice(), pb.getStopLossPrice());
        // 익절 거리 = (익절가 − 진입가)/진입가 × 100 (1회차는 부호·분모를 잘못 써서 실패 — 시험 산술 오류, 동작 아님)
        BigDecimal tpA = pct(pa.getEntryPrice(), pa.getTakeProfitPrice()).negate();
        BigDecimal tpB = pct(pb.getEntryPrice(), pb.getTakeProfitPrice()).negate();
        System.out.printf("[A/B 진입] 매수금 A=%s B=%s · 진입가 A=%s B=%s · 손절 거리 A=%s%% B=%s%% · 익절 거리 A=%s%% B=%s%%%n",
                pa.getInvestedKrw(), pb.getInvestedKrw(), pa.getEntryPrice(), pb.getEntryPrice(), slA, slB, tpA, tpB);
        assertThat(pa.getInvestedKrw()).isEqualByComparingTo("20000");
        assertThat(pb.getInvestedKrw()).isEqualByComparingTo("20000");
        assertThat(pa.getEntryPrice()).isEqualByComparingTo(pb.getEntryPrice());
        assertThat(slB).isEqualByComparingTo("5.0000");
        assertThat(slA).isLessThan(slB);
        assertThat(tpA).isLessThan(tpB);
    }

    private VirtualBalanceEntity start(String arm) {
        PaperTradingStartRequest req = new PaperTradingStartRequest();
        req.setStrategyType(DailySmaTrendStrategy.NAME);
        req.setCoinPair("KRW-BTC");
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("100000"));
        req.setInvestRatio(new BigDecimal("0.8"));
        req.setMaxHoldHours(24);
        if ("B".equals(arm)) req.setStopLossPct(new BigDecimal("5"));
        Map<String, Object> p = new HashMap<>();
        p.put(PaperMaxInvestment.KEY, 20000);
        req.setStrategyParams(p);
        VirtualBalanceEntity s = paperTradingService.start(req);
        ids.add(s.getId());
        return s;
    }

    /** (기준 − 수준)/기준 × 100 */
    private static BigDecimal pct(BigDecimal base, BigDecimal level) {
        return base.subtract(level).divide(base, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }
}
