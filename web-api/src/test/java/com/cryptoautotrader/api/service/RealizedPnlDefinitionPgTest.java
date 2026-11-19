package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>실현손익 정의의 소비 경로 영향</b> (총괄 v1.190 §2) — 시험 전용, 제품 변경 없음. 0e1d2a02 기점.
 *
 * <p>같은 경제 입력(매수 대금 7,500·수수료 3.75 → 매도 대금 7,504.75·수수료 3.75, 순매도 7,501)을 LIVE·DYNAMIC REAL 의
 * <b>실제 매수/매도 경로</b>(대역 거래소 응답만, 주문·포지션·잔액 행은 엔진이 씀)와 DYN_PAPER 모의 경로에 넣고, 저장 손익을 읽는
 * 실제 소비 지점을 그대로 호출한다: 손실 합 SQL 2개({@code sumLiveSessionRealizedLossSince}·{@code sumDynamicRealSessionRealizedLossSince}),
 * 진입 손실 관문 {@code checkRealLossGuard}·{@code checkRisk}, 세션 서킷 브레이커(연속 손실) {@code checkCircuitBreaker},
 * 실시간 손절 {@code RealtimeProtectLines.stopHit}. 비용 기준 값은 비교용 산술이며 제품 실행 결과가 아니다.</p>
 *
 * <p>A 단계: 비용 뒤 −2.75 인데 REAL 저장 관례로 +1 이 되는 왕복(부호 반전). B 단계: 실제 손실 왕복(크기 과소).</p>
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
@DisplayName("실현손익 정의 — 위험 관문·연속 손실·손절 소비 경로 (격리 PG)")
class RealizedPnlDefinitionPgTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private RiskManagementService risk;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private DynamicSessionRepository dynamicRepo;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("orderExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor orderExecutor;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired private MarkPriceBook markPriceBook;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitRestClient upbitRestClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient upbitWebSocketClient;

    private final Map<String, Object> out = new LinkedHashMap<>();
    private static final BigDecimal FEE = TradingConstantsAccess.fee();

    /** 패키지 밖 상수 접근을 한곳에 — 제품 상수 그대로. */
    static final class TradingConstantsAccess {
        static BigDecimal fee() { return com.cryptoautotrader.api.util.TradingConstants.FEE_RATE; }
        static BigDecimal slip() { return com.cryptoautotrader.api.util.TradingConstants.PAPER_SLIPPAGE_PCT; }
    }

    // ── 대역 응답·엔진 호출(매도 PG 시험 dec-pg-sell 과 같은 방식) ─────────────────
    private static OrderResponse resp(String side, String state, String vol, String funds, String fee) {
        OrderResponse r = new OrderResponse();
        r.setSide(side);
        r.setOrdType("bid".equals(side) ? "price" : "market");
        r.setState(state);
        r.setMarket("KRW-RND");
        r.setExecutedVolume(new BigDecimal(vol));
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPaidFee(new BigDecimal(fee));
        return r;
    }

    private void poll(Long orderId, OrderResponse r) {
        String uuid = orderRepository.findById(orderId).orElseThrow().getExchangeOrderId();
        r.setUuid(uuid);
        Mockito.doReturn(r).when(upbitOrderClient).getOrder(uuid);
        orderExecutionEngine.checkOrderStatus(orderId);
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

    private static List<com.cryptoautotrader.strategy.Candle> candles() {
        List<com.cryptoautotrader.strategy.Candle> c = new ArrayList<>();
        Instant t0 = Instant.now().minus(300, java.time.temporal.ChronoUnit.HOURS);
        for (int i = 0; i < 300; i++) {
            BigDecimal p = new BigDecimal(300 + i % 7);
            c.add(com.cryptoautotrader.strategy.Candle.builder().time(t0.plus(i, java.time.temporal.ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.ONE)).low(p.subtract(BigDecimal.ONE)).close(p)
                    .volume(new BigDecimal("1000")).build());
        }
        return c;
    }

    private PositionEntity position(String kind, Long sid) {
        return positionRepository.findBySessionKindAndSessionId(kind, sid).stream().findFirst().orElseThrow();
    }

    private List<OrderEntity> orders(String kind, Long sid, String side) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, sid).stream()
                .filter(x -> side.equals(x.getSide())).toList();
    }

    private Long newLive() {
        BigDecimal init = new BigDecimal("16240.37");
        return liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair("KRW-RND").timeframe("H1")
                .initialCapital(init).availableKrw(init).totalAssetKrw(init)
                .investRatio(new BigDecimal("0.995")).maxInvestment(new BigDecimal("20000"))
                .stopLossPct(new BigDecimal("5.00")).maxHoldHours(0)
                .status("RUNNING").startedAt(Instant.now().minusSeconds(60)).build()).getId();
    }

    private Long newDynamic(String mode) {
        BigDecimal init = new BigDecimal("10000.37");
        return dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(init).availableKrw(init).totalAssetKrw(init)
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode(mode)
                .startedAt(Instant.now().minusSeconds(60))
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60).build()).getId();
    }

    private void buy(String kind, Long sid) {
        String result;
        if ("LIVE".equals(kind)) {
            Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(liveTradingService);
            result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(target, "executeSessionBuy",
                    liveRepo.findById(sid).orElseThrow(), "KRW-RND", new BigDecimal("300"), "손익 정의 시험 매수",
                    null, null, candles(), null);
        } else {
            result = new org.springframework.transaction.support.TransactionTemplate(txManager).execute(st ->
                    dynamicTradingService.executeBuy(dynamicRepo.findById(sid).orElseThrow(), "KRW-RND", candles(),
                            com.cryptoautotrader.strategy.StrategySignal.buy(new BigDecimal("80"), "손익 정의 시험 매수"), BigDecimal.ONE));
        }
        assertThat(result).as("%s#%d 매수 경로(null = 예약·제출/모의 체결)", kind, sid).isNull();
    }

    private void sellSubmit(String kind, Long sid, BigDecimal price) {
        sellSubmit(kind, kind, sid, price);
    }

    private void sellSubmit(String kind, String posKind, Long sid, BigDecimal price) {
        PositionEntity pos = position(posKind, sid);
        boolean ok;
        if ("LIVE".equals(kind)) {
            Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(liveTradingService);
            ok = Boolean.TRUE.equals(org.springframework.test.util.ReflectionTestUtils.invokeMethod(target, "executeSessionSell",
                    liveRepo.findById(sid).orElseThrow(), pos, price, "손익 정의 시험 매도", ExitReason.TAKE_PROFIT));
        } else {
            ok = dynamicTradingService.executeSell(dynamicRepo.findById(sid).orElseThrow(), pos, price, "손익 정의 시험 매도",
                    ExitReason.TAKE_PROFIT);
        }
        assertThat(ok).as("%s#%d 실제 매도 경로", kind, sid).isTrue();
    }

    /** 실자금 한 왕복: 매수 25 = 7,500+3.75 → 매도 25 = sellFunds−sellFee. 반환: 기록. */
    private Map<String, Object> realRoundTrip(String kind, String sellFunds, String sellFee) throws Exception {
        Long sid = "LIVE".equals(kind) ? newLive() : newDynamic("REAL");
        markPriceBook.record("KRW-RND", new BigDecimal("300"), Instant.now());
        buy(kind, sid);
        Long buyId = orders(kind, sid, "BUY").get(0).getId();
        awaitSubmitted(buyId);
        poll(buyId, resp("bid", "done", "25", "7500", "3.75"));
        sellSubmit(kind, sid, new BigDecimal("300.19"));
        Long sellId = orders(kind, sid, "SELL").get(0).getId();
        awaitSubmitted(sellId);
        poll(sellId, resp("ask", "done", "25", sellFunds, sellFee));
        if ("LIVE".equals(kind)) liveTradingService.reconcileClosingPositions();
        else dynamicTradingService.reconcileDynamicClosingPositions();
        PositionEntity p = position(kind, sid);
        assertThat(p.getStatus()).as("%s#%d 청산 확정", kind, sid).isEqualTo("CLOSED");
        BigDecimal net = new BigDecimal(sellFunds).subtract(new BigDecimal(sellFee));
        BigDecimal costBasis = net.subtract(new BigDecimal("7503.75"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("session_id", sid);
        m.put("avg_price", p.getAvgPrice().toPlainString());
        m.put("invested_krw", p.getInvestedKrw() == null ? null : p.getInvestedKrw().toPlainString());
        m.put("stored_realized_pnl", p.getRealizedPnl().toPlainString());
        m.put("net_sell_proceeds", net.toPlainString());
        m.put("cost_basis_realized_arith", costBasis.toPlainString());
        m.put("available_krw_after", ("LIVE".equals(kind) ? liveRepo.findById(sid).orElseThrow().getAvailableKrw()
                : dynamicRepo.findById(sid).orElseThrow().getAvailableKrw()).toPlainString());
        return m;
    }

    private void setRisk(String lossPct, int consecutive) {
        int n = jdbc.update("UPDATE risk_config SET max_daily_loss_pct = ?::numeric, max_weekly_loss_pct = ?::numeric, "
                + "max_monthly_loss_pct = ?::numeric, consecutive_loss_limit = ?, circuit_breaker_enabled = TRUE, mdd_threshold_pct = 99",
                lossPct, lossPct, lossPct, consecutive);
        assertThat(n).as("risk_config 행(V88)").isGreaterThan(0);
    }

    private Map<String, Object> guards(Instant since) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("sumLiveSessionRealizedLossSince", orderRepository.sumLiveSessionRealizedLossSince(since).toPlainString());
        g.put("sumDynamicRealSessionRealizedLossSince", orderRepository.sumDynamicRealSessionRealizedLossSince(since).toPlainString());
        var guard = risk.checkRealLossGuard();
        g.put("checkRealLossGuard", Map.of("approved", guard.isApproved(), "reason", String.valueOf(guard.getReason())));
        var all = risk.checkRisk();
        g.put("checkRisk", Map.of("approved", all.isApproved(), "reason", String.valueOf(all.getReason())));
        return g;
    }

    private Map<String, Object> breaker(String kind, Long sid) {
        var r = "LIVE".equals(kind) ? risk.checkCircuitBreaker(liveRepo.findById(sid).orElseThrow())
                : risk.checkCircuitBreaker(dynamicRepo.findById(sid).orElseThrow());
        return Map.of("session", kind + "#" + sid, "triggered", r.isTriggered(), "reason", String.valueOf(r.getReason()));
    }

    @Test
    @DisplayName("같은 경제 입력 — REAL 저장 손익(매수 수수료 제외)이 손실 관문·연속 손실·손절에 주는 영향")
    void realizedPnlConsumers() throws Exception {
        String stage = limits.getStage();
        BigDecimal total = limits.getTotalCapKrw();
        BigDecimal per = limits.getPerPositionCapKrw();
        Integer slots = limits.getMaxConcurrentPositions();
        limits.setStage("PNLDEF_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        poolLossGuard.setEnabledSetting(true);
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("pnl-" + UUID.randomUUID());
                    r.setSide(inv.getArgument(1));
                    r.setOrdType(inv.getArgument(4));
                    r.setState("wait");
                    r.setMarket(inv.getArgument(0));
                    return new UpbitOrderClient.ExchangeResult(r, "{}");
                });
        Instant t0 = Instant.now().minusSeconds(5);
        try {
            Long seeded = jdbc.queryForObject("SELECT COUNT(*) FROM \"order\"", Long.class);
            assertThat(seeded).as("새 DB(다른 시험 주문 없음)").isZero();
            setRisk("50", 5);   // 거래 중에는 관문이 시험 왕복을 막지 않게 넓힌다(측정 때만 좁힘)

            // ── A: 비용 뒤 −2.75, REAL 저장 관례 +1 (부호 반전) ──
            Map<String, Object> aLive = realRoundTrip("LIVE", "7504.75", "3.75");
            Map<String, Object> aDyn = realRoundTrip("DYNAMIC", "7504.75", "3.75");

            // DYN_PAPER — 제품 모의 경로(평균가 = 수수료 포함 투입/수량). 매도가는 같은 구간(비용 뒤 손실·수수료 제외 이익)에 들도록 계산.
            Long psid = newDynamic("PAPER");
            buy("DYN_PAPER", psid);
            PositionEntity pp = position("DYN_PAPER", psid);
            BigDecimal qty = pp.getSize();
            BigDecimal entry = pp.getEntryPrice();
            BigDecimal invest = pp.getAvgPrice().multiply(qty);
            BigDecimal buyFee = invest.subtract(qty.multiply(entry));
            BigDecimal targetNet = qty.multiply(entry).add(buyFee.multiply(new BigDecimal("0.4")));
            BigDecimal sellPrice = targetNet.divide(qty.multiply(BigDecimal.ONE.subtract(FEE)), 12, RoundingMode.HALF_UP)
                    .divide(BigDecimal.ONE.subtract(TradingConstantsAccess.slip()), 8, RoundingMode.HALF_UP);
            sellSubmit("DYNAMIC", "DYN_PAPER", psid, sellPrice);
            PositionEntity pp2 = position("DYN_PAPER", psid);
            assertThat(pp2.getStatus()).isEqualTo("CLOSED");
            OrderEntity psell = orders("DYN_PAPER", psid, "SELL").get(0);
            BigDecimal pNet = psell.getExecutedFunds().subtract(psell.getExecutedFunds().multiply(FEE));
            Map<String, Object> aPaper = new LinkedHashMap<>();
            aPaper.put("kind", "DYN_PAPER");
            aPaper.put("session_id", psid);
            aPaper.put("entry_fill_price", entry.toPlainString());
            aPaper.put("avg_price", pp2.getAvgPrice().toPlainString());
            aPaper.put("size", qty.toPlainString());
            aPaper.put("stored_realized_pnl", pp2.getRealizedPnl().toPlainString());
            aPaper.put("net_sell_proceeds_arith", pNet.toPlainString());
            aPaper.put("real_convention_arith", pNet.subtract(qty.multiply(entry)).toPlainString());

            assertThat(new BigDecimal((String) aLive.get("stored_realized_pnl"))).as("LIVE 저장 = +1").isEqualByComparingTo("1");
            assertThat(new BigDecimal((String) aDyn.get("stored_realized_pnl"))).as("DYNAMIC 저장 = +1").isEqualByComparingTo("1");
            assertThat(pp2.getRealizedPnl().signum()).as("DYN_PAPER 저장 손익은 음수(수수료 포함 평균가)").isNegative();
            assertThat(pNet.subtract(qty.multiply(entry)).signum()).as("같은 DYN_PAPER 왕복을 REAL 관례로 계산하면 양수").isPositive();

            setRisk("0.01", 1);   // 측정: 손실 0.01% 초과면 거절, 연속 손실 1회면 발동
            Map<String, Object> aGuards = guards(t0);
            List<Map<String, Object>> aBreakers = List.of(breaker("LIVE", ((Number) aLive.get("session_id")).longValue()),
                    breaker("DYNAMIC", ((Number) aDyn.get("session_id")).longValue()), breaker("DYNAMIC", psid));
            assertThat(aGuards.get("sumLiveSessionRealizedLossSince")).as("A: LIVE 손실 합에서 빠짐").isEqualTo("0");
            assertThat(aGuards.get("sumDynamicRealSessionRealizedLossSince")).as("A: DYNAMIC REAL 손실 합에서 빠짐").isEqualTo("0");

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("live", aLive);
            a.put("dynamic_real", aDyn);
            a.put("dyn_paper", aPaper);
            a.put("guards_at_0_01pct", aGuards);
            a.put("circuit_breakers_limit1", aBreakers);
            out.put("A_sign_flip", a);

            // ── B: 실제 손실 — 매도 7,400·수수료 3.70 → 순 7,396.30. 저장 −103.70, 비용 뒤 −107.45 (크기 과소) ──
            setRisk("50", 5);
            Instant tB = Instant.now().minusSeconds(1);
            Map<String, Object> bLive = realRoundTrip("LIVE", "7400", "3.70");
            Map<String, Object> bDyn = realRoundTrip("DYNAMIC", "7400", "3.70");
            setRisk("0.01", 1);
            Map<String, Object> bGuards = guards(tB);
            List<Map<String, Object>> bBreakers = List.of(breaker("LIVE", ((Number) bLive.get("session_id")).longValue()),
                    breaker("DYNAMIC", ((Number) bDyn.get("session_id")).longValue()));
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("live", bLive);
            b.put("dynamic_real", bDyn);
            b.put("guards_since_B_at_0_01pct", bGuards);
            b.put("circuit_breakers_limit1", bBreakers);
            out.put("B_true_loss", b);
            assertThat(new BigDecimal((String) bGuards.get("sumLiveSessionRealizedLossSince"))).isEqualByComparingTo("-103.70");
            assertThat(new BigDecimal((String) bGuards.get("sumDynamicRealSessionRealizedLossSince"))).isEqualByComparingTo("-103.70");

            // ── 실시간 손절(5%) — 같은 가격에서 평균가 정의에 따라 판정이 갈리는 구간 ──
            BigDecimal price = new BigDecimal("285.10");
            BigDecimal avgReal = new BigDecimal("300");
            BigDecimal avgCost = new BigDecimal("7503.75").divide(new BigDecimal("25"), 8, RoundingMode.HALF_UP);
            Map<String, Object> sl = new LinkedHashMap<>();
            sl.put("price", price.toPlainString());
            sl.put("avg_real_convention", avgReal.toPlainString());
            sl.put("avg_cost_basis", avgCost.toPlainString());
            sl.put("stopHit_real", RealtimeProtectLines.stopHit(price, avgReal, null, new BigDecimal("5")));
            sl.put("stopHit_cost", RealtimeProtectLines.stopHit(price, avgCost, null, new BigDecimal("5")));
            sl.put("pnlPct_real", RealtimeProtectLines.pnlPct(price, avgReal).toPlainString());
            sl.put("pnlPct_cost", RealtimeProtectLines.pnlPct(price, avgCost).toPlainString());
            out.put("C_stop_loss_line", sl);
            assertThat(sl.get("stopHit_real")).isEqualTo(false);
            assertThat(sl.get("stopHit_cost")).isEqualTo(true);
        } finally {
            limits.setStage(stage);
            limits.setTotalCapKrw(total);
            limits.setPerPositionCapKrw(per);
            limits.setMaxConcurrentPositions(slots);
            String dir = System.getenv("PNL_OUT");
            if (dir != null) {
                Files.createDirectories(Path.of(dir));
                Files.writeString(Path.of(dir, "pnl_consumers.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
            }
            System.out.println("[손익 정의] " + objectMapper.writeValueAsString(out));
        }
    }
}
