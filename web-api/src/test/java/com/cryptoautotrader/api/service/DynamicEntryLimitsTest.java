package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * 금액/BTC 독립 후보(총괄 v1.187 §3-4·v1.189 §3-1) — 새 DYNAMIC 세션 전용 {@code strategy_params.maxOrderKrw}·{@code excludeCoins}.
 *
 * <p>계약: 두 키는 함께 명시해야 하고 저장 전에 형식·범위를 거절한다. 매회 요청액 = min(기존 요청액, maxOrderKrw), 그 뒤 기존 최소 주문 보정,
 * 예약 서비스의 포지션당 상한 검사(최종 보호선)는 그대로다. 제외 코인은 감시 목록·후보 선택·회차 승인·최종 진입에서 신규 매수만 막고,
 * BTC 급락 관문 입력과 기존 보유 매도는 막지 않는다. 두 키가 없는 기존 세션은 계산·저장값·규칙 지문이 그대로다.</p>
 *
 * <p>금액은 시험용 가상 값이다 — 실제 총원금·월 손실·매회 상한 승인이 아니다. 대역 구성은 {@code BtcGateUnknownEntryTest} 와 같다
 * (전역 위험 검사·실자금 손실 관문은 H2 에서 돌지 않아 승인으로 고정, 외부 주문/구독 대역).</p>
 */
@DisplayName("금액/BTC 후보 — maxOrderKrw·excludeCoins 생성 계약, 매회 상한, BTC 신규 매수 제외, 기존 세션·지문 불변")
class DynamicEntryLimitsTest extends IntegrationTestBase {

    private static final String STRATEGY = "AMT_BTC_ALWAYS_BUY";
    /** 0e1d2a02(제품 변경 전)에서 {@link #fixedNoKeySession()} 의 지문 — 두 키가 없는 세션은 이 값이 그대로여야 한다. */
    private static final String BASE_HASH_NO_KEYS = "ea1ed76bc345";   // 0e1d2a02 실행(실패 고정 회차)에서 받은 값

    @Autowired private EntryPriorityRounds rounds;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private RulesetRegistry rulesetRegistry;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager txManager;

    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient upbitWebSocketClient;
    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private PortfolioSyncService portfolioSyncService;
    @SpyBean private RiskManagementService riskManagementService;

    private String savedStage;
    private BigDecimal savedTotal, savedPer;
    private Integer savedSlots;
    private final List<Long> ids = new ArrayList<>();

    static final class AlwaysBuy implements Strategy {
        @Override public String getName() { return STRATEGY; }
        @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            return StrategySignal.buy(new BigDecimal("100"), "금액/BTC 시험 신호");
        }
        @Override public int getMinimumCandleCount() { return 50; }
    }

    @BeforeEach
    void setUp() {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        StrategyRegistry.register(new AlwaysBuy());
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        limits.setStage("AMT_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        poolLossGuard.setEnabledSetting(true);
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRisk();
        doReturn(com.cryptoautotrader.core.risk.RiskCheckResult.approve()).when(riskManagementService).checkRealLossGuard();
        rounds.setForcedForTest(false);
    }

    @AfterEach
    void tearDown() {
        rounds.setForcedForTest(false);
        for (Long id : ids) {
            for (String kind : List.of("DYNAMIC", "DYN_PAPER")) {
                orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, id).forEach(orderRepository::delete);
                positionRepository.findBySessionKindAndSessionId(kind, id).forEach(positionRepository::delete);
            }
            dynamicRepo.findById(id).ifPresent(dynamicRepo::delete);
        }
        jdbc.update("DELETE FROM capital_stage_baseline WHERE stage = ?", limits.getStage());
        limits.setStage(savedStage);
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
    }

    // ── 준비 ─────────────────────────────────────────────────────

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private DynamicSessionRequest request(String mode, String capital, Map<String, Object> p) {
        DynamicSessionRequest r = new DynamicSessionRequest();
        r.setStrategyType("COMPOSITE_BREAKOUT");
        r.setTimeframe("H1");
        r.setInitialCapital(new BigDecimal(capital));
        r.setTradingMode(mode);
        r.setStrategyParams(p);
        return r;
    }

    private DynamicSessionEntity create(String mode, String capital, Map<String, Object> p) {
        DynamicSessionEntity s = dynamicTradingService.createSession(request(mode, capital, p));
        ids.add(s.getId());
        return s;
    }

    /** 실행 중 세션을 직접 만든다(매수 경로 시험용). 저장값은 이미 정규화된 형태로 넣는다. */
    private DynamicSessionEntity running(String mode, String available, String ratio, Map<String, Object> p, String... watch) {
        DynamicSessionEntity s = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType(STRATEGY).timeframe("H1")
                .initialCapital(new BigDecimal(available)).availableKrw(new BigDecimal(available)).totalAssetKrw(new BigDecimal(available))
                .investRatio(new BigDecimal(ratio)).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode(mode)
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000")).watchlistRefreshMin(60)
                .watchlistJson(watch.length == 0 ? null : "[\"" + String.join("\",\"", watch) + "\"]")
                .watchlistRefreshedAt(watch.length == 0 ? null : Instant.now())
                .strategyParams(p)
                .startedAt(Instant.now())
                .build());
        ids.add(s.getId());
        return s;
    }

    private static List<Candle> candles(Instant lastOpen) {
        List<Candle> out = new ArrayList<>();
        for (int i = 299; i >= 0; i--) {
            BigDecimal p = new BigDecimal(1000 + (299 - i));
            out.add(Candle.builder().time(lastOpen.minus(i, ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.ONE)).low(p.subtract(BigDecimal.ONE)).close(p)
                    .volume(new BigDecimal("1000")).build());
        }
        return out;
    }

    private static Instant freshLastOpen() {
        return Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    }

    private String buy(Long id, String coin, String multiplier) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(st -> dynamicTradingService.executeBuy(dynamicRepo.findById(id).orElseThrow(), coin,
                candles(freshLastOpen()), StrategySignal.buy(new BigDecimal("80"), "금액 시험"), new BigDecimal(multiplier)));
    }

    private List<OrderEntity> buys(DynamicSessionEntity s) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(DynamicTradingService.sessionKind(s), s.getId())
                .stream().filter(o -> "BUY".equals(o.getSide())).toList();
    }

    private DynamicSessionEntity reload(Long id) {
        return new TransactionTemplate(txManager).execute(st -> {
            entityManager.clear();
            return dynamicRepo.findById(id).orElseThrow();
        });
    }

    // ── 1. 생성 계약 ────────────────────────────────────────────────

    static Stream<Arguments> invalid() {
        List<String> btc = List.of("KRW-BTC");
        Map<String, Object> nullMax = new HashMap<>();
        nullMax.put("maxOrderKrw", null);
        nullMax.put("excludeCoins", btc);
        Map<String, Object> nullCoins = new HashMap<>();
        nullCoins.put("maxOrderKrw", 10000);
        nullCoins.put("excludeCoins", null);
        return Stream.of(
                Arguments.of("상한만", params("maxOrderKrw", 10000)),
                Arguments.of("제외 목록만", params("excludeCoins", btc)),
                Arguments.of("상한 null", nullMax),
                Arguments.of("제외 목록 null", nullCoins),
                Arguments.of("상한 문자열", params("maxOrderKrw", "10000", "excludeCoins", btc)),
                Arguments.of("상한 불리언", params("maxOrderKrw", true, "excludeCoins", btc)),
                Arguments.of("상한 4999(최소 주문 미만)", params("maxOrderKrw", 4999, "excludeCoins", btc)),
                Arguments.of("상한 소수", params("maxOrderKrw", new BigDecimal("10000.5"), "excludeCoins", btc)),
                Arguments.of("상한 NaN", params("maxOrderKrw", Double.NaN, "excludeCoins", btc)),
                Arguments.of("REAL 포지션당 상한 20000 초과", params("maxOrderKrw", 20001, "excludeCoins", btc)),
                Arguments.of("제외 목록 문자열", params("maxOrderKrw", 10000, "excludeCoins", "KRW-BTC")),
                Arguments.of("제외 코인 소문자", params("maxOrderKrw", 10000, "excludeCoins", List.of("krw-btc"))),
                Arguments.of("제외 코인 중복", params("maxOrderKrw", 10000, "excludeCoins", List.of("KRW-BTC", "KRW-BTC"))),
                Arguments.of("제외 코인 숫자", params("maxOrderKrw", 10000, "excludeCoins", List.of(1))));
    }

    @ParameterizedTest(name = "거절: {0}")
    @MethodSource("invalid")
    @DisplayName("생성: 두 키는 함께 명시, null·형식·범위 밖은 저장 전에 거절(행 없음)")
    void createRejectsInvalid(String name, Map<String, Object> p) {
        long before = dynamicRepo.count();
        assertThatThrownBy(() -> create("REAL", "30000", p)).as(name).isInstanceOf(IllegalArgumentException.class);
        assertThat(dynamicRepo.count()).as("저장 전 거절").isEqualTo(before);
    }

    @Test
    @DisplayName("생성: 유효한 값은 정규화(제외 목록 정렬·상한 정수)해 저장하고, 다시 읽어도 같은 값·같은 지문")
    void createNormalizesPersistsAndRestores() {
        DynamicSessionEntity a = create("REAL", "30000", params("maxOrderKrw", new BigDecimal("20000.00"),
                "excludeCoins", List.of("KRW-XRP", "KRW-BTC"), "slAtrMultiplier", 2));
        DynamicSessionEntity b = create("PAPER", "30000", params("excludeCoins", Arrays.asList("KRW-BTC", "KRW-XRP"),
                "maxOrderKrw", 20000, "slAtrMultiplier", 2));
        for (DynamicSessionEntity s : List.of(a, b)) {
            DynamicSessionEntity r = reload(s.getId());
            assertThat(r.getStrategyParams().get("excludeCoins")).as("정렬된 목록").isEqualTo(List.of("KRW-BTC", "KRW-XRP"));
            assertThat(new BigDecimal(r.getStrategyParams().get("maxOrderKrw").toString())).isEqualByComparingTo("20000");
            assertThat(r.getStrategyParams().get("slAtrMultiplier")).as("다른 키는 그대로").isEqualTo(2);
            assertThat(rulesetRegistry.hashFor(r)).as("재기동(다시 읽기) 뒤 지문 동일").isEqualTo(rulesetRegistry.hashFor(s));
        }
        // 입력 순서·숫자 표기가 달라도 같은 값이면 같은 지문(엔진 이름이 같은 REAL 둘로 비교)
        DynamicSessionEntity c = create("REAL", "30000", params("excludeCoins", List.of("KRW-BTC", "KRW-XRP"), "maxOrderKrw", 20000,
                "slAtrMultiplier", 2));
        assertThat(rulesetRegistry.hashFor(reload(c.getId()))).isEqualTo(rulesetRegistry.hashFor(reload(a.getId())));
    }

    @Test
    @DisplayName("생성: 두 키가 없으면 strategy_params 가 그대로(null·기존 키), 지문은 제품 변경 전과 같다")
    void createWithoutKeysUnchanged() {
        DynamicSessionEntity n = create("REAL", "30000", null);
        assertThat(reload(n.getId()).getStrategyParams()).isNull();
        Map<String, Object> only = params("slAtrMultiplier", 2, "tpRrMultiplier", new BigDecimal("1.5"));
        DynamicSessionEntity o = create("REAL", "30000", only);
        assertThat(reload(o.getId()).getStrategyParams()).isEqualTo(params("slAtrMultiplier", 2, "tpRrMultiplier", 1.5));

        String h = rulesetRegistry.hashFor(fixedNoKeySession());
        System.out.println("[금액/BTC] 두 키 없는 고정 세션 지문=" + h);
        assertThat(h).as("0e1d2a02 지문과 같음").isEqualTo(BASE_HASH_NO_KEYS);
        DynamicSessionEntity withKeys = fixedNoKeySession();
        withKeys.setStrategyParams(params("maxOrderKrw", 10000, "excludeCoins", List.of("KRW-BTC")));
        assertThat(rulesetRegistry.hashFor(withKeys)).as("값이 있으면 다른 규칙").isNotEqualTo(h);
    }

    /** 저장하지 않는 고정 세션 — 지문 입력만 쓴다. */
    private static DynamicSessionEntity fixedNoKeySession() {
        return DynamicSessionEntity.builder().strategyType("COMPOSITE_BREAKOUT").timeframe("H1")
                .initialCapital(new BigDecimal("30000")).availableKrw(new BigDecimal("30000")).totalAssetKrw(new BigDecimal("30000"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.0")).tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10).minAtrPct(new BigDecimal("0.5")).maxSpreadPct(new BigDecimal("0.1"))
                .watchlistRefreshMin(60).maxHoldHours(24).build();
    }

    // ── 2. 매회 상한 ────────────────────────────────────────────────

    @Test
    @DisplayName("상한: 요청 20,000.80 → 10,000 으로 줄여 예약·주문(같은 조건, 키 없는 세션은 종전대로 포지션당 상한 거절)")
    void capReducesRequest() {
        DynamicSessionEntity capped = running("REAL", "25001", "0.80", params("maxOrderKrw", 10000, "excludeCoins", List.of()));
        assertThat(buy(capped.getId(), "KRW-ETH", "1")).isNull();
        assertThat(buys(capped)).singleElement().satisfies(o -> assertThat(o.getQuantity()).isEqualByComparingTo("10000"));
        assertThat(reload(capped.getId()).getAvailableKrw()).isEqualByComparingTo("15001");

        DynamicSessionEntity plain = running("REAL", "25001", "0.80", null);
        assertThat(buy(plain.getId(), "KRW-ETH", "1")).contains(CapitalReservationService.PER_POSITION_CAP);
        assertThat(buys(plain)).isEmpty();
    }

    @Test
    @DisplayName("상한 뒤 최소 주문: 감액 4,000 은 기존 규칙대로 5,000 으로 올리고(상한 5,000 안), 정상 사이즈도 5,000 미만이면 차단")
    void minimumOrderAfterCap() {
        DynamicSessionEntity up = running("REAL", "10000", "0.80", params("maxOrderKrw", 5000, "excludeCoins", List.of()));
        assertThat(buy(up.getId(), "KRW-ETH", "0.5")).isNull();
        assertThat(buys(up)).singleElement().satisfies(o -> assertThat(o.getQuantity()).isEqualByComparingTo("5000"));

        DynamicSessionEntity low = running("REAL", "6000", "0.80", params("maxOrderKrw", 5000, "excludeCoins", List.of()));
        assertThat(buy(low.getId(), "KRW-ETH", "0.5")).contains("가용 KRW 부족");
        assertThat(buys(low)).isEmpty();
    }

    @Test
    @DisplayName("예약 보호 불변: 생성 뒤 포지션당 상한이 15,000 으로 내려가면 상한 20,000 요청도 예약 서비스가 거절")
    void reservationGuardKept() {
        DynamicSessionEntity s = running("REAL", "30000", "0.80", params("maxOrderKrw", 20000, "excludeCoins", List.of()));
        limits.setPerPositionCapKrw(new BigDecimal("15000"));
        assertThat(buy(s.getId(), "KRW-ETH", "1")).contains(CapitalReservationService.PER_POSITION_CAP);
        assertThat(buys(s)).isEmpty();
        assertThat(reload(s.getId()).getAvailableKrw()).as("차감 없음").isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("PAPER 도 같은 식: 요청 20,000.80 → 10,000 체결")
    void paperSameFormula() {
        DynamicSessionEntity p = running("PAPER", "25001", "0.80", params("maxOrderKrw", 10000, "excludeCoins", List.of()));
        assertThat(buy(p.getId(), "KRW-ETH", "1")).isNull();
        assertThat(buys(p)).singleElement().satisfies(o -> assertThat(o.getQuantity()).isEqualByComparingTo("10000"));
    }

    // ── 3. BTC 신규 매수 제외 ─────────────────────────────────────────

    @Test
    @DisplayName("제외: 최종 진입·감시 목록·회차 승인에서 KRW-BTC 신규 매수 0, 다른 코인은 그대로")
    void excludedCoinBlockedAtEveryEntryPoint() {
        Map<String, Object> ex = params("maxOrderKrw", 10000, "excludeCoins", List.of("KRW-BTC"));
        DynamicSessionEntity s = running("REAL", "20000", "0.80", ex, "KRW-BTC", "KRW-ETH");
        assertThat(dynamicTradingService.resolveWatchlist(reload(s.getId()))).as("감시 목록").containsExactly("KRW-ETH");
        assertThat(buy(s.getId(), "KRW-BTC", "1")).as("최종 진입").contains("excludeCoins");
        assertThat(buys(s)).isEmpty();

        String approved = dynamicTradingService.approveQueuedEntry(s.getId(), "KRW-BTC", candles(freshLastOpen()),
                StrategySignal.buy(new BigDecimal("80"), "회차 승인 시험"), BigDecimal.ONE, null, null);
        assertThat(approved).as("회차 승인").contains("excludeCoins");
        assertThat(buys(s)).isEmpty();

        assertThat(buy(s.getId(), "KRW-ETH", "1")).as("다른 코인").isNull();
        assertThat(buys(s)).singleElement().satisfies(o -> assertThat(o.getCoinPair()).isEqualTo("KRW-ETH"));
    }

    @Test
    @DisplayName("평가 회차: 감시 목록에 BTC 가 있어도 다른 코인을 산다. BTC 급락 관문 입력은 제외하지 않는다(BTC 봉이 없으면 판정 불가로 전체 차단)")
    void scanTickSkipsExcludedButKeepsBtcGate() {
        Map<String, Object> ex = params("maxOrderKrw", 10000, "excludeCoins", List.of("KRW-BTC"));
        List<Candle> fresh = candles(freshLastOpen());

        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(fresh);
        when(marketDataSyncService.fetchWithCache(eq("KRW-BTC"), anyString(), anyInt())).thenReturn(List.of());
        DynamicSessionEntity gate = running("REAL", "20000", "0.80", ex, "KRW-BTC", "KRW-IOTA");
        dynamicTradingService.processTick(dynamicRepo.findById(gate.getId()).orElseThrow());
        assertThat(buys(gate)).as("BTC 봉 없음 — 제외 설정과 무관하게 관문 판정 불가").isEmpty();

        when(marketDataSyncService.fetchWithCache(eq("KRW-BTC"), anyString(), anyInt())).thenReturn(fresh);
        DynamicSessionEntity ok = running("REAL", "20000", "0.80", ex, "KRW-BTC", "KRW-IOTA");
        dynamicTradingService.processTick(dynamicRepo.findById(ok.getId()).orElseThrow());
        assertThat(buys(ok)).as("대조 — BTC 봉이 최신이면 제외되지 않은 코인을 산다").singleElement()
                .satisfies(o -> assertThat(o.getCoinPair()).isEqualTo("KRW-IOTA"));
    }

    @Test
    @DisplayName("기존 보유 매도 유지: 제외 설정 세션의 KRW-BTC 보유 포지션은 그대로 청산된다(PAPER)")
    void existingBtcHoldingStillSells() {
        DynamicSessionEntity s = running("PAPER", "20000", "0.80", params("maxOrderKrw", 10000, "excludeCoins", List.of("KRW-BTC")));
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.0001")).investedKrw(new BigDecimal("9000"))
                .status("OPEN").sessionId(s.getId()).sessionKind("DYN_PAPER")
                .stopLossPrice(new BigDecimal("85000000")).takeProfitPrice(new BigDecimal("95000000"))
                .build());
        boolean closed = dynamicTradingService.executeSell(reload(s.getId()), pos, new BigDecimal("91000000"), "제외 코인 보유 매도 시험",
                com.cryptoautotrader.api.entity.ExitReason.TAKE_PROFIT);
        assertThat(closed).isTrue();
        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus()).isEqualTo("CLOSED");
    }
}
