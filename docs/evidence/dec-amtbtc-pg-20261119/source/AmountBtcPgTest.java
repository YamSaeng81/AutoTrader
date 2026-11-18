package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * <b>금액/BTC 통합 후보 aff357e2 의 좁은 실제 PG 확인</b> (총괄 v1.190 §4-1) — 시험 전용, 후보 제품 코드 그대로.
 *
 * <p>H2 59/59 를 반복하지 않는다. PG 에서만 달라질 수 있는 것만 본다: JSONB 저장 표현·다시 읽기·새 영속성 컨텍스트 복원과 지문,
 * 매회 상한의 예약/주문 행, BTC 신규 매수 제외와 BTC 급락 관문 입력 유지, 제외 코인 기존 보유의 실자금 매도 경로.
 * "다시 읽기"는 같은 앱(같은 JVM·스프링 문맥)에서 영속성 컨텍스트를 비운 DB 재조회다 — 앱 재기동 증거가 아니다.</p>
 */
@TestPropertySource(properties = {
        "spring.datasource.url=${SR_PG_URL}",
        "spring.datasource.username=${SR_PG_USER:trader}",
        "spring.datasource.password=${SR_PG_PASSWORD:rtpass}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never"
})
@EnabledIfEnvironmentVariable(named = "SR_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("금액/BTC 후보 — 격리 PG 좁은 확인(JSONB·복원·상한·BTC 제외·기존 보유 매도)")
class AmountBtcPgTest extends IntegrationTestBase {

    private static final String STRATEGY = "AMT_BTC_PG_ALWAYS_BUY";
    private static final String BASE_HASH_NO_KEYS = "ea1ed76bc345";   // DynamicEntryLimitsTest 와 같은 고정 세션·0e1d2a02 값

    @Autowired private EntryPriorityRounds rounds;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private RulesetRegistry rulesetRegistry;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("orderExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor orderExecutor;

    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient upbitWebSocketClient;
    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private TelegramNotificationService telegramService;
    @MockBean private PortfolioSyncService portfolioSyncService;

    private final Map<String, Object> out = new LinkedHashMap<>();

    static final class AlwaysBuy implements Strategy {
        @Override public String getName() { return STRATEGY; }
        @Override public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            return StrategySignal.buy(new BigDecimal("100"), "금액/BTC PG 시험 신호");
        }
        @Override public int getMinimumCandleCount() { return 50; }
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private DynamicSessionEntity create(String mode, Map<String, Object> p) {
        DynamicSessionRequest r = new DynamicSessionRequest();
        r.setStrategyType("COMPOSITE_BREAKOUT");
        r.setTimeframe("H1");
        r.setInitialCapital(new BigDecimal("30000"));
        r.setTradingMode(mode);
        r.setStrategyParams(p);
        return dynamicTradingService.createSession(r);
    }

    private DynamicSessionEntity running(String mode, String available, Map<String, Object> p, String... watch) {
        return dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType(STRATEGY).timeframe("H1")
                .initialCapital(new BigDecimal(available)).availableKrw(new BigDecimal(available)).totalAssetKrw(new BigDecimal(available))
                .investRatio(new BigDecimal("0.80")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode(mode)
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000")).watchlistRefreshMin(60)
                .watchlistJson(watch.length == 0 ? null : "[\"" + String.join("\",\"", watch) + "\"]")
                .watchlistRefreshedAt(watch.length == 0 ? null : Instant.now())
                .strategyParams(p)
                .startedAt(Instant.now())
                .build());
    }

    private static List<Candle> candles() {
        Instant last = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        List<Candle> c = new ArrayList<>();
        for (int i = 299; i >= 0; i--) {
            BigDecimal p = new BigDecimal(1000 + (299 - i));
            c.add(Candle.builder().time(last.minus(i, ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.ONE)).low(p.subtract(BigDecimal.ONE)).close(p)
                    .volume(new BigDecimal("1000")).build());
        }
        return c;
    }

    private String buy(Long id, String coin) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(st -> dynamicTradingService.executeBuy(dynamicRepo.findById(id).orElseThrow(), coin, candles(),
                StrategySignal.buy(new BigDecimal("80"), "금액 PG 시험"), BigDecimal.ONE));
    }

    private List<OrderEntity> orders(DynamicSessionEntity s, String side) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(DynamicTradingService.sessionKind(s), s.getId())
                .stream().filter(o -> side.equals(o.getSide())).toList();
    }

    private DynamicSessionEntity reload(Long id) {
        return new TransactionTemplate(txManager).execute(st -> {
            entityManager.clear();
            return dynamicRepo.findById(id).orElseThrow();
        });
    }

    private Map<String, Object> rawParams(Long id) {
        return jdbc.queryForMap("SELECT strategy_params::text AS text, strategy_params IS NULL AS is_null, "
                + "jsonb_typeof(strategy_params->'maxOrderKrw') AS max_type, jsonb_typeof(strategy_params->'excludeCoins') AS ex_type "
                + "FROM dynamic_session WHERE id = ?", id);
    }

    private void awaitSubmitted(Long orderId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            OrderEntity o = orderRepository.findById(orderId).orElseThrow();
            var pool = orderExecutor.getThreadPoolExecutor();
            if (o.getExchangeOrderId() != null && pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("주문 접수 기록 대기 시간 초과 (orderId=" + orderId + ")");
    }

    private static DynamicSessionEntity fixedNoKeySession() {
        return DynamicSessionEntity.builder().strategyType("COMPOSITE_BREAKOUT").timeframe("H1")
                .initialCapital(new BigDecimal("30000")).availableKrw(new BigDecimal("30000")).totalAssetKrw(new BigDecimal("30000"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.0")).tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10).minAtrPct(new BigDecimal("0.5")).maxSpreadPct(new BigDecimal("0.1"))
                .watchlistRefreshMin(60).maxHoldHours(24).build();
    }

    @Test
    @DisplayName("JSONB 저장·재조회·복원 지문 / 매회 상한 예약·주문 행 / BTC 제외·관문 입력 / 제외 코인 기존 보유 실자금 매도")
    void amountBtcOnPg() throws Exception {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        StrategyRegistry.register(new AlwaysBuy());
        String stage = limits.getStage();
        BigDecimal total = limits.getTotalCapKrw();
        BigDecimal per = limits.getPerPositionCapKrw();
        Integer slots = limits.getMaxConcurrentPositions();
        limits.setStage("AMTPG_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        poolLossGuard.setEnabledSetting(true);
        rounds.setForcedForTest(false);
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("amtpg-" + UUID.randomUUID());
                    r.setSide(inv.getArgument(1));
                    r.setOrdType(inv.getArgument(4));
                    r.setState("wait");
                    r.setMarket(inv.getArgument(0));
                    return new UpbitOrderClient.ExchangeResult(r, "{}");
                });
        try {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dynamic_session", Long.class)).as("새 DB").isZero();

            // ── 1. JSONB 저장 표현·재조회·새 영속성 컨텍스트 복원·지문 ──
            DynamicSessionEntity a = create("REAL", params("maxOrderKrw", new BigDecimal("20000.00"),
                    "excludeCoins", List.of("KRW-XRP", "KRW-BTC"), "slAtrMultiplier", 2));
            String hashAtCreate = rulesetRegistry.hashFor(a);
            DynamicSessionEntity c = create("REAL", params("excludeCoins", List.of("KRW-BTC", "KRW-XRP"), "maxOrderKrw", 20000,
                    "slAtrMultiplier", 2));
            DynamicSessionEntity n = create("REAL", null);
            Map<String, Object> rawA = rawParams(a.getId());
            Map<String, Object> rawC = rawParams(c.getId());
            Map<String, Object> rawN = rawParams(n.getId());
            DynamicSessionEntity ra = reload(a.getId());
            String hashReload = rulesetRegistry.hashFor(ra);
            String hashC = rulesetRegistry.hashFor(reload(c.getId()));
            String hashFixed = rulesetRegistry.hashFor(fixedNoKeySession());
            Map<String, Object> s1 = new LinkedHashMap<>();
            s1.put("jsonb_a", rawA);
            s1.put("jsonb_c_other_order", rawC);
            s1.put("jsonb_no_keys", rawN);
            s1.put("restored_params_a", ra.getStrategyParams());
            s1.put("hash_at_create", hashAtCreate);
            s1.put("hash_after_reload", hashReload);
            s1.put("hash_c", hashC);
            s1.put("hash_fixed_no_keys", hashFixed);
            out.put("1_jsonb_restore_fingerprint", s1);
            assertThat(rawA.get("max_type")).isEqualTo("number");
            assertThat(rawA.get("ex_type")).isEqualTo("array");
            assertThat((String) rawA.get("text")).contains("\"excludeCoins\": [\"KRW-BTC\", \"KRW-XRP\"]").contains("\"maxOrderKrw\": 20000");
            assertThat(rawN.get("is_null")).isEqualTo(true);
            assertThat(hashReload).as("다시 읽기 뒤 지문").isEqualTo(hashAtCreate);
            assertThat(hashC).as("순서·표기 다른 같은 값").isEqualTo(hashReload);
            assertThat(hashFixed).isEqualTo(BASE_HASH_NO_KEYS);

            // ── 2. 매회 상한 — 실제 예약·주문 행(PG) ──
            DynamicSessionEntity capped = running("REAL", "25001", params("maxOrderKrw", 10000, "excludeCoins", List.of()));
            String capRes = buy(capped.getId(), "KRW-ETH");
            OrderEntity capOrder = orders(capped, "BUY").get(0);
            awaitSubmitted(capOrder.getId());
            Map<String, Object> capRow = jdbc.queryForMap("SELECT quantity::text, reserved_krw::text, state, coin_pair FROM \"order\" WHERE id = ?",
                    capOrder.getId());
            DynamicSessionEntity plain = running("REAL", "25001", null);
            String plainRes = buy(plain.getId(), "KRW-ETH");
            Map<String, Object> s2 = new LinkedHashMap<>();
            s2.put("capped_result", String.valueOf(capRes));
            s2.put("capped_order_row", capRow);
            s2.put("capped_available_after", reload(capped.getId()).getAvailableKrw().toPlainString());
            s2.put("plain_result", String.valueOf(plainRes));
            s2.put("plain_buy_orders", orders(plain, "BUY").size());
            s2.put("plain_available_after", reload(plain.getId()).getAvailableKrw().toPlainString());
            out.put("2_cap_reservation", s2);
            assertThat(capRes).isNull();
            assertThat(new BigDecimal((String) capRow.get("quantity"))).isEqualByComparingTo("10000");
            assertThat(new BigDecimal((String) capRow.get("reserved_krw"))).isEqualByComparingTo("10000");
            assertThat(reload(capped.getId()).getAvailableKrw()).isEqualByComparingTo("15001");
            assertThat(plainRes).contains(CapitalReservationService.PER_POSITION_CAP);
            assertThat(orders(plain, "BUY")).isEmpty();

            // ── 3. BTC 신규 매수 제외 / 급락 관문 입력은 그대로 ──
            Map<String, Object> ex = params("maxOrderKrw", 10000, "excludeCoins", List.of("KRW-BTC"));
            DynamicSessionEntity s = running("REAL", "20000", ex, "KRW-BTC", "KRW-ETH");
            List<String> watch = dynamicTradingService.resolveWatchlist(reload(s.getId()));
            String btcBuy = buy(s.getId(), "KRW-BTC");
            String approved = dynamicTradingService.approveQueuedEntry(s.getId(), "KRW-BTC", candles(),
                    StrategySignal.buy(new BigDecimal("80"), "회차 승인 PG"), BigDecimal.ONE, null, null);
            int btcOrders = orders(s, "BUY").size();
            String ethBuy = buy(s.getId(), "KRW-ETH");

            List<Candle> fresh = candles();
            when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt())).thenReturn(fresh);
            when(marketDataSyncService.fetchWithCache(eq("KRW-BTC"), anyString(), anyInt())).thenReturn(List.of());
            DynamicSessionEntity gate = running("REAL", "20000", ex, "KRW-BTC", "KRW-IOTA");
            dynamicTradingService.processTick(dynamicRepo.findById(gate.getId()).orElseThrow());
            int gateBuys = orders(gate, "BUY").size();
            when(marketDataSyncService.fetchWithCache(eq("KRW-BTC"), anyString(), anyInt())).thenReturn(fresh);
            DynamicSessionEntity ok = running("REAL", "20000", ex, "KRW-BTC", "KRW-IOTA");
            dynamicTradingService.processTick(dynamicRepo.findById(ok.getId()).orElseThrow());
            List<String> okCoins = orders(ok, "BUY").stream().map(OrderEntity::getCoinPair).toList();
            Map<String, Object> s3 = new LinkedHashMap<>();
            s3.put("watchlist", watch);
            s3.put("btc_execute_buy", String.valueOf(btcBuy));
            s3.put("btc_queued_approve", String.valueOf(approved));
            s3.put("btc_buy_orders", btcOrders);
            s3.put("eth_execute_buy", String.valueOf(ethBuy));
            s3.put("eth_buy_coins", orders(s, "BUY").stream().map(OrderEntity::getCoinPair).toList());
            s3.put("tick_btc_candles_missing_buys", gateBuys);
            s3.put("tick_btc_candles_fresh_coins", okCoins);
            out.put("3_btc_exclusion_gate", s3);
            assertThat(watch).containsExactly("KRW-ETH");
            assertThat(btcBuy).contains("excludeCoins");
            assertThat(approved).contains("excludeCoins");
            assertThat(btcOrders).isZero();
            assertThat(ethBuy).isNull();
            assertThat(gateBuys).isZero();
            assertThat(okCoins).containsExactly("KRW-IOTA");

            // ── 4. 제외 코인 기존 보유 — 실자금 실제 매도 경로(보유 행은 시험이 넣음: 엔진 매수 체결 주장 아님) ──
            DynamicSessionEntity h = running("REAL", "20000", ex);
            PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                    .coinPair("KRW-BTC").side("BUY")
                    .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                    .size(new BigDecimal("0.0001")).investedKrw(new BigDecimal("9000"))
                    .status("OPEN").sessionId(h.getId()).sessionKind("DYNAMIC")
                    .stopLossPrice(new BigDecimal("85000000")).takeProfitPrice(new BigDecimal("95000000"))
                    .build());
            boolean submitted = dynamicTradingService.executeSell(reload(h.getId()), pos, new BigDecimal("91000000"),
                    "제외 코인 기존 보유 매도 PG", com.cryptoautotrader.api.entity.ExitReason.TAKE_PROFIT);
            OrderEntity sell = orders(h, "SELL").get(0);
            awaitSubmitted(sell.getId());
            OrderResponse done = new OrderResponse();
            done.setUuid(orderRepository.findById(sell.getId()).orElseThrow().getExchangeOrderId());
            done.setSide("ask");
            done.setOrdType("market");
            done.setState("done");
            done.setMarket("KRW-BTC");
            done.setExecutedVolume(new BigDecimal("0.0001"));
            done.setExecutedFunds(new BigDecimal("9100"));
            done.setPaidFee(new BigDecimal("4.55"));
            Mockito.doReturn(done).when(upbitOrderClient).getOrder(done.getUuid());
            orderExecutionEngine.checkOrderStatus(sell.getId());
            dynamicTradingService.reconcileDynamicClosingPositions();
            PositionEntity closed = positionRepository.findById(pos.getId()).orElseThrow();
            Map<String, Object> s4 = new LinkedHashMap<>();
            s4.put("sell_submitted", submitted);
            s4.put("position_status", closed.getStatus());
            s4.put("realized_pnl", closed.getRealizedPnl() == null ? null : closed.getRealizedPnl().toPlainString());
            s4.put("available_after", reload(h.getId()).getAvailableKrw().toPlainString());
            s4.put("seeded_holding_note", "보유 행은 시험이 직접 넣음 — 매도 경로만 엔진");
            out.put("4_existing_btc_holding_real_sell", s4);
            assertThat(submitted).isTrue();
            assertThat(closed.getStatus()).isEqualTo("CLOSED");
        } finally {
            limits.setStage(stage);
            limits.setTotalCapKrw(total);
            limits.setPerPositionCapKrw(per);
            limits.setMaxConcurrentPositions(slots);
            String dir = System.getenv("AMT_OUT");
            if (dir != null) {
                Files.createDirectories(Path.of(dir));
                Files.writeString(Path.of(dir, "amount_btc_pg.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
            }
            System.out.println("[금액/BTC PG] " + objectMapper.writeValueAsString(out));
        }
    }
}
