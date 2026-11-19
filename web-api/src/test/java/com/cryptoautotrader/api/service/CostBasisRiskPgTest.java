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
import com.cryptoautotrader.core.risk.RiskCheckResult;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

/**
 * <b>비용 기준 손실 보호 회귀</b> (총괄 v1.191 §2) — 0e1d2a02 기점에서 먼저 실패로 고정하고, 후보에서 통과시킨다.
 *
 * <p>LIVE·DYNAMIC REAL 의 <b>실제 매수→매도 경로</b>(거래소 응답만 대역, 주문·포지션·잔액 행은 엔진이 씀)와 격리 PG 로 손실 합 SQL·
 * 진입 손실 관문({@code checkRisk}·{@code checkRealLossGuard})·LIVE 회차 승인 관문({@code approvalTimeGate})·DYNAMIC 진입
 * ({@code executeBuy})·세션 서킷 브레이커를 부른다. 기대값은 <b>양쪽 수수료를 포함한 비용 기준</b>이다
 * (매수 대금+매수 수수료를 매수 수량으로 나눈 단가 × 매도 체결 수량을 매도 순대금에서 뺀다).</p>
 *
 * <p>기점에서 컴파일되도록 기존 API(결과·사유 문자열·매수 경로 반환값)만으로 단언한다. 단계 사이에는 앞 단계 행의 확정·청산 시각을
 * 40일 앞으로 옮겨 일/주/월 창에서 뺀다(창 밖 이동, 행 삭제 아님). 롤백 단계 하나만 행 직접 쓰기로 표시한다.</p>
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
@DisplayName("비용 기준 손실 보호 — 부호·크기·0 경계·연속 손실·창 경계·부분 매도·정정·반복·롤백·판정 불가 (격리 PG)")
class CostBasisRiskPgTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private RiskManagementService risk;
    @Autowired private OrderSettlementService settlement;
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
    private final List<String> failures = new ArrayList<>();

    // ── 대역 응답·엔진 호출 ─────────────────────────────────────────
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

    private String settle(Long orderId, OrderResponse r) {
        r.setUuid(orderRepository.findById(orderId).orElseThrow().getExchangeOrderId());
        return settlement.settle(orderId, r).name();
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
        Instant t0 = Instant.now().minus(300, ChronoUnit.HOURS);
        for (int i = 0; i < 300; i++) {
            BigDecimal p = new BigDecimal(300 + i % 7);
            c.add(com.cryptoautotrader.strategy.Candle.builder().time(t0.plus(i, ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.ONE)).low(p.subtract(BigDecimal.ONE)).close(p)
                    .volume(new BigDecimal("1000")).build());
        }
        return c;
    }

    /** 이 세션의 아직 닫히지 않은 포지션(한 세션 여러 왕복). */
    private PositionEntity openPos(String kind, Long sid) {
        return positionRepository.findBySessionKindAndSessionId(kind, sid).stream()
                .filter(p -> !"CLOSED".equals(p.getStatus())).findFirst().orElseThrow();
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
                .investRatio(new BigDecimal("0.995")).maxInvestment(new BigDecimal("7500"))
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

    /** 실제 매수 경로. 반환: 경로 결과(null = 예약·제출). */
    private String buyPath(String kind, Long sid) {
        if ("LIVE".equals(kind)) {
            Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
            return ReflectionTestUtils.invokeMethod(target, "executeSessionBuy",
                    liveRepo.findById(sid).orElseThrow(), "KRW-RND", new BigDecimal("300"), "비용 기준 시험 매수",
                    null, null, candles(), null);
        }
        return new TransactionTemplate(txManager).execute(st ->
                dynamicTradingService.executeBuy(dynamicRepo.findById(sid).orElseThrow(), "KRW-RND", candles(),
                        com.cryptoautotrader.strategy.StrategySignal.buy(new BigDecimal("80"), "비용 기준 시험 매수"), BigDecimal.ONE));
    }

    /** 매수 25 = 7,500 + buyFee 체결까지. 반환: 매수 주문 id. */
    private Long hold(String kind, Long sid, String buyFee) throws Exception {
        markPriceBook.record("KRW-RND", new BigDecimal("300"), Instant.now());
        assertThat(buyPath(kind, sid)).as("%s#%d 매수 경로", kind, sid).isNull();
        Long buyId = orders(kind, sid, "BUY").get(0).getId();
        awaitSubmitted(buyId);
        poll(buyId, resp("bid", "done", "25", "7500", buyFee));
        assertThat(openPos(kind, sid).getSize()).isEqualByComparingTo("25");
        return buyId;
    }

    private Long sellSubmit(String kind, Long sid) throws Exception {
        PositionEntity pos = openPos(kind, sid);
        int before = orders(kind, sid, "SELL").size();
        boolean ok;
        if ("LIVE".equals(kind)) {
            Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
            ok = Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(target, "executeSessionSell",
                    liveRepo.findById(sid).orElseThrow(), pos, new BigDecimal("300.19"), "비용 기준 시험 매도", ExitReason.TAKE_PROFIT));
        } else {
            ok = dynamicTradingService.executeSell(dynamicRepo.findById(sid).orElseThrow(), pos, new BigDecimal("300.19"),
                    "비용 기준 시험 매도", ExitReason.TAKE_PROFIT);
        }
        assertThat(ok).as("%s#%d 실제 매도 경로", kind, sid).isTrue();
        List<OrderEntity> sells = orders(kind, sid, "SELL");
        assertThat(sells).hasSize(before + 1);
        Long id = sells.get(0).getId();
        awaitSubmitted(id);
        return id;
    }

    private void reconcile(String kind) {
        if ("LIVE".equals(kind)) liveTradingService.reconcileClosingPositions();
        else dynamicTradingService.reconcileDynamicClosingPositions();
    }

    /** 한 왕복(같은 세션): 매수 25 = 7,500+3.75 → 매도 25 전량. 반환: [매수 id, 매도 id]. */
    private Long[] trip(String kind, Long sid, String sellFunds, String sellFee) throws Exception {
        Long b = hold(kind, sid, "3.75");
        Long s = sellSubmit(kind, sid);
        poll(s, resp("ask", "done", "25", sellFunds, sellFee));
        reconcile(kind);
        PositionEntity p = positionRepository.findById(orderRepository.findById(s).orElseThrow().getPositionId()).orElseThrow();
        assertThat(p.getStatus()).as("%s#%d 청산 확정", kind, sid).isEqualTo("CLOSED");
        return new Long[]{b, s};
    }

    private void setRisk(String d, String w, String m, int consecutive) {
        int n = jdbc.update("UPDATE risk_config SET max_daily_loss_pct = ?::numeric, max_weekly_loss_pct = ?::numeric, "
                + "max_monthly_loss_pct = ?::numeric, consecutive_loss_limit = ?, circuit_breaker_enabled = TRUE, mdd_threshold_pct = 99",
                d, w, m, consecutive);
        assertThat(n).as("risk_config 행(V88)").isGreaterThan(0);
    }

    private void setRisk(String pct, int consecutive) {
        setRisk(pct, pct, pct, consecutive);
    }

    /** 앞 단계 행을 일/주/월 창 밖(40일 전)으로 옮긴다 — 삭제하지 않는다. */
    private void ageAll() {
        jdbc.update("UPDATE \"order\" SET sell_applied_at = sell_applied_at - interval '40 days' WHERE sell_applied_at IS NOT NULL");
        jdbc.update("UPDATE position SET closed_at = closed_at - interval '40 days' WHERE closed_at IS NOT NULL");
    }

    private void ageSell(Long sellId, String interval) {
        jdbc.update("UPDATE \"order\" SET sell_applied_at = now() - ?::interval WHERE id = ?", interval, sellId);
        jdbc.update("UPDATE position SET closed_at = now() - ?::interval WHERE id = (SELECT position_id FROM \"order\" WHERE id = ?)",
                interval, sellId);
    }

    private BigDecimal liveLoss(Instant since) {
        return orderRepository.sumLiveSessionRealizedLossSince(since);
    }

    private BigDecimal dynLoss(Instant since) {
        return orderRepository.sumDynamicRealSessionRealizedLossSince(since);
    }

    private Map<String, Object> verdict(RiskCheckResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approved", r.isApproved());
        m.put("reason", String.valueOf(r.getReason()));
        return m;
    }

    private Map<String, Object> breaker(String kind, Long sid) {
        var r = "LIVE".equals(kind) ? risk.checkCircuitBreaker(liveRepo.findById(sid).orElseThrow())
                : risk.checkCircuitBreaker(dynamicRepo.findById(sid).orElseThrow());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session", kind + "#" + sid);
        m.put("triggered", r.isTriggered());
        m.put("reason", String.valueOf(r.getReason()));
        return m;
    }

    /** LIVE 회차 승인 관문(서킷 브레이커 → checkRisk → 최신 캔들·블랙스완·BTC) — 실제 제품 함수. */
    private String liveApprovalGate(Long sid) {
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        return ReflectionTestUtils.invokeMethod(target, "approvalTimeGate", liveRepo.findById(sid).orElseThrow(), "KRW-RND", candles());
    }

    private void check(String label, boolean ok, Object actual) {
        out.computeIfAbsent("checks", k -> new ArrayList<Map<String, Object>>());
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("checks");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", label);
        m.put("pass", ok);
        m.put("actual", String.valueOf(actual));
        list.add(m);
        if (!ok) failures.add(label + " → " + actual);
    }

    private static boolean eq(BigDecimal a, String b) {
        return a != null && a.compareTo(new BigDecimal(b)) == 0;
    }

    @Test
    @DisplayName("비용 기준 손실 보호 — 실제 매수→매도 경로·격리 PG에서 관문·연속 손실·판정 불가")
    void costBasisGuards() throws Exception {
        String stage = limits.getStage();
        BigDecimal total = limits.getTotalCapKrw();
        BigDecimal per = limits.getPerPositionCapKrw();
        Integer slots = limits.getMaxConcurrentPositions();
        limits.setStage("COSTB_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 30);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("10000000")));
        poolLossGuard.setEnabledSetting(true);
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("cb-" + UUID.randomUUID());
                    r.setSide(inv.getArgument(1));
                    r.setOrdType(inv.getArgument(4));
                    r.setState("wait");
                    r.setMarket(inv.getArgument(0));
                    return new UpbitOrderClient.ExchangeResult(r, "{}");
                });
        try {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"order\"", Long.class)).as("새 DB").isZero();
            Instant far = Instant.now().minus(30, ChronoUnit.DAYS);

            // ── S1 부호: 매수 7,500+3.75 → 매도 7,504.75−3.75. 비용 뒤 −2.75 ──
            setRisk("50", 5);
            Long l1 = newLive();
            Long d1 = newDynamic("REAL");
            trip("LIVE", l1, "7504.75", "3.75");
            trip("DYNAMIC", d1, "7504.75", "3.75");
            setRisk("0.01", 1);
            check("S1 LIVE 손실 합 = −2.75", eq(liveLoss(far), "-2.75"), liveLoss(far));
            check("S1 DYNAMIC REAL 손실 합 = −2.75", eq(dynLoss(far), "-2.75"), dynLoss(far));
            RiskCheckResult s1Risk = risk.checkRisk();
            RiskCheckResult s1Guard = risk.checkRealLossGuard();
            check("S1 checkRisk 거절(일 0.01%)", !s1Risk.isApproved(), verdict(s1Risk));
            check("S1 checkRealLossGuard 거절", !s1Guard.isApproved(), verdict(s1Guard));
            Map<String, Object> s1bl = breaker("LIVE", l1);
            Map<String, Object> s1bd = breaker("DYNAMIC", d1);
            check("S1 LIVE 연속 손실 한도1 발동", Boolean.TRUE.equals(s1bl.get("triggered")), s1bl);
            check("S1 DYNAMIC 연속 손실 한도1 발동", Boolean.TRUE.equals(s1bd.get("triggered")), s1bd);
            Long lFresh = newLive();
            String gate = liveApprovalGate(lFresh);
            check("S1 LIVE 회차 승인 관문이 손실 한도로 거절", gate != null && gate.startsWith("리스크 한도"), gate);
            Long dFresh = newDynamic("REAL");
            String dynEntry = buyPath("DYNAMIC", dFresh);
            check("S1 DYNAMIC 진입(executeBuy) 실자금 손실 보호식 거절", dynEntry != null && dynEntry.startsWith("실자금 손실 보호식"), dynEntry);
            out.put("S1_stored_live_pnl", positionRepository.findBySessionKindAndSessionId("LIVE", l1).get(0).getRealizedPnl().toPlainString());
            out.put("S1_stored_dynamic_pnl", positionRepository.findBySessionKindAndSessionId("DYNAMIC", d1).get(0).getRealizedPnl().toPlainString());
            ageAll();

            // ── S2 크기: 매도 7,400−3.70 → 저장 −103.70, 비용 뒤 −107.45 ──
            setRisk("50", 5);
            trip("LIVE", newLive(), "7400", "3.70");
            trip("DYNAMIC", newDynamic("REAL"), "7400", "3.70");
            check("S2 LIVE 손실 합 = −107.45", eq(liveLoss(far), "-107.45"), liveLoss(far));
            check("S2 DYNAMIC REAL 손실 합 = −107.45", eq(dynLoss(far), "-107.45"), dynLoss(far));
            ageAll();

            // ── S3 실제 손익 0 경계: 매도 7,507.50−3.75 = 7,503.75 = 원가 → 손실 아님 ──
            setRisk("50", 5);
            Long l3 = newLive();
            trip("LIVE", l3, "7507.50", "3.75");
            setRisk("0.01", 1);
            check("S3 LIVE 손실 합 = 0", eq(liveLoss(far), "0"), liveLoss(far));
            RiskCheckResult s3 = risk.checkRisk();
            check("S3 checkRisk 승인(0 은 손실 아님)", s3.isApproved(), verdict(s3));
            Map<String, Object> s3b = breaker("LIVE", l3);
            check("S3 연속 손실 미발동", Boolean.FALSE.equals(s3b.get("triggered")), s3b);
            ageAll();

            // ── S4 연속 손실: 한도 2 — 1회 미발동, 2회 발동, 이익이 끊음 ──
            for (String kind : List.of("LIVE", "DYNAMIC")) {
                setRisk("50", 2);
                Long sid = "LIVE".equals(kind) ? newLive() : newDynamic("REAL");
                trip(kind, sid, "7504.75", "3.75");
                Map<String, Object> one = breaker(kind, sid);
                check("S4 " + kind + " 비용 뒤 손실 1회 — 한도2 미발동", Boolean.FALSE.equals(one.get("triggered")), one);
                trip(kind, sid, "7504.75", "3.75");
                Map<String, Object> two = breaker(kind, sid);
                check("S4 " + kind + " 비용 뒤 손실 2회 — 한도2 발동", Boolean.TRUE.equals(two.get("triggered")), two);

                Long sid2 = "LIVE".equals(kind) ? newLive() : newDynamic("REAL");
                trip(kind, sid2, "7504.75", "3.75");          // 손실
                trip(kind, sid2, "7520", "3.76");             // 이익 +12.49
                trip(kind, sid2, "7504.75", "3.75");          // 손실
                Map<String, Object> brk = breaker(kind, sid2);
                check("S4 " + kind + " 손실→이익→손실(최근부터 1회) — 한도2 미발동", Boolean.FALSE.equals(brk.get("triggered")), brk);
                ageAll();
            }

            // ── S5 일/주/월 창 경계: 비용 뒤 −2.75 한 건을 25시간·8일·31일 앞으로 ──
            setRisk("50", 5);
            Long[] w = trip("LIVE", newLive(), "7504.75", "3.75");
            Instant now = Instant.now();
            ageSell(w[1], "25 hours");
            setRisk("0.01", "50", "50", 5);
            RiskCheckResult d25 = risk.checkRisk();
            check("S5 25시간 전 — 일 창 밖(일 0.01% 승인)", d25.isApproved(), verdict(d25));
            setRisk("50", "0.01", "50", 5);
            RiskCheckResult w25 = risk.checkRisk();
            check("S5 25시간 전 — 주 창 안(주 0.01% 거절)", !w25.isApproved() && String.valueOf(w25.getReason()).startsWith("주간"), verdict(w25));
            ageSell(w[1], "8 days");
            RiskCheckResult w8 = risk.checkRisk();
            check("S5 8일 전 — 주 창 밖(주 0.01% 승인)", w8.isApproved(), verdict(w8));
            setRisk("50", "50", "0.01", 5);
            RiskCheckResult m8 = risk.checkRisk();
            check("S5 8일 전 — 월 창 안(월 0.01% 거절)", !m8.isApproved() && String.valueOf(m8.getReason()).startsWith("월간"), verdict(m8));
            ageSell(w[1], "31 days");
            RiskCheckResult m31 = risk.checkRisk();
            check("S5 31일 전 — 월 창 밖(월 0.01% 승인)", m31.isApproved(), verdict(m31));
            check("S5 창별 손실 합(1일/7일/30일) = 0/0/0(31일 전)", eq(liveLoss(now.minus(30, ChronoUnit.DAYS)), "0"),
                    liveLoss(now.minus(30, ChronoUnit.DAYS)));
            ageAll();

            // ── S6 부분 매도 + 잔여 매도 → 매수 수수료 정정 → 매도 수수료 정정 → 같은 응답 반복 → 롤백 ──
            for (String kind : List.of("LIVE", "DYNAMIC")) {
                setRisk("50", 5);
                Long sid = "LIVE".equals(kind) ? newLive() : newDynamic("REAL");
                Long buyId = hold(kind, sid, "3.75");
                Long s1 = sellSubmit(kind, sid);
                poll(s1, resp("ask", "cancel", "10", "3001", "1.5005"));   // 10개 체결 뒤 취소 — 잔량 15 유지
                reconcile(kind);
                check("S6 " + kind + " 부분 매도 뒤 잔량 15 OPEN", openPos(kind, sid).getSize().compareTo(new BigDecimal("15")) == 0,
                        openPos(kind, sid).getSize());
                // 10 × 300.15 = 3,001.50 → 2,999.4995 − 3,001.50 = −2.0005
                BigDecimal after1 = "LIVE".equals(kind) ? liveLoss(far) : dynLoss(far);
                check("S6 " + kind + " 부분 매도 1건 = −2.0005(배분 원가 10개분)", eq(after1, "-2.0005"), after1);
                Long s2 = sellSubmit(kind, sid);
                poll(s2, resp("ask", "done", "15", "4504", "2.252"));      // 4,501.748 − 4,502.25 = −0.502
                reconcile(kind);
                BigDecimal after2 = "LIVE".equals(kind) ? liveLoss(far) : dynLoss(far);
                check("S6 " + kind + " 잔여 매도 뒤 합 = −2.5025(두 매도 각각, 매수 원가를 두 번 빼지 않음)", eq(after2, "-2.5025"), after2);

                String bc = settle(buyId, resp("bid", "done", "25", "7500", "3.80"));   // 늦은 매수 수수료 정정 +0.05
                BigDecimal after3 = "LIVE".equals(kind) ? liveLoss(far) : dynLoss(far);
                // 단가 7,503.80/25 = 300.152 → −2.0205 + −0.532 = −2.5525
                check("S6 " + kind + " 늦은 매수 수수료 정정 반영 = −2.5525 (" + bc + ")", eq(after3, "-2.5525"), after3);
                String sc = settle(s2, resp("ask", "done", "15", "4504", "2.255"));   // 늦은 매도 수수료 정정 +0.003
                BigDecimal after4 = "LIVE".equals(kind) ? liveLoss(far) : dynLoss(far);
                check("S6 " + kind + " 늦은 매도 수수료 정정 반영 = −2.5555 (" + sc + ")", eq(after4, "-2.5555"), after4);
                String again = settle(s2, resp("ask", "done", "15", "4504", "2.255"));
                BigDecimal after5 = "LIVE".equals(kind) ? liveLoss(far) : dynLoss(far);
                check("S6 " + kind + " 같은 응답 반복 — 변화 없음 (" + again + ")", eq(after5, "-2.5555"), after5);
                // 롤백(행 직접 쓰기 — 제품 정산 UPDATE 와 같은 열을 쓴 뒤 롤백): 커밋 안 된 원가 변경은 읽히지 않는다
                new TransactionTemplate(txManager).executeWithoutResult(st -> {
                    jdbc.update("UPDATE \"order\" SET applied_paid_fee = applied_paid_fee + 100 WHERE id = ?", buyId);
                    st.setRollbackOnly();
                });
                BigDecimal after6 = "LIVE".equals(kind) ? liveLoss(far) : dynLoss(far);
                check("S6 " + kind + " 롤백된 매수 원가 변경 — 변화 없음", eq(after6, "-2.5555"), after6);
                ageAll();
            }

            // ── S7 판정 불가: 확정 매수 원가 미상(반영 수수료 NULL)·팬텀(추정 청산) → 신규 진입 판정 불가, 정지·강제 청산 없음 ──
            setRisk("50", 5);
            Long l7 = newLive();
            Long[] t7 = trip("LIVE", l7, "7504.75", "3.75");
            jdbc.update("UPDATE \"order\" SET applied_paid_fee = NULL WHERE id = ?", t7[0]);   // 원가 미상(행 직접 — 미보고 상태 재현)
            RiskCheckResult s7r = risk.checkRisk();
            check("S7 매수 원가 미상 LIVE 매도 → checkRisk 판정 불가 거절", !s7r.isApproved() && String.valueOf(s7r.getReason()).contains("판정 불가"),
                    verdict(s7r));
            Map<String, Object> s7b = breaker("LIVE", l7);
            check("S7 연속 손실 판정 불가 — 발동(비상 정지) 아님", Boolean.FALSE.equals(s7b.get("triggered"))
                    && String.valueOf(s7b.get("reason")).contains("판정 불가"), s7b);
            String s7buy = buyPath("LIVE", l7);
            check("S7 같은 세션 신규 매수 거절(판정 불가)", s7buy != null && s7buy.contains("판정 불가"), s7buy);
            check("S7 세션은 RUNNING 그대로", "RUNNING".equals(liveRepo.findById(l7).orElseThrow().getStatus()),
                    liveRepo.findById(l7).orElseThrow().getStatus());
            ageAll();

            Long d7 = newDynamic("REAL");
            Long[] u7 = trip("DYNAMIC", d7, "7504.75", "3.75");
            jdbc.update("UPDATE \"order\" SET applied_paid_fee = NULL WHERE id = ?", u7[0]);
            RiskCheckResult s7d = risk.checkRealLossGuard();
            check("S7 DYNAMIC 매수 원가 미상 → checkRealLossGuard 판정 불가 거절",
                    !s7d.isApproved() && String.valueOf(s7d.getReason()).contains("판정 불가"), verdict(s7d));
            String s7dbuy = buyPath("DYNAMIC", d7);
            check("S7 DYNAMIC 같은 세션 신규 매수 거절", s7dbuy != null && s7dbuy.contains("판정 불가"), s7dbuy);
            ageAll();

            // 팬텀: 보유 중 거래소 잔고 0 → 추정 청산(주문 행 없음) — 실제 제품 함수 reconcilePhantomPosition
            Long l8 = newLive();
            hold("LIVE", l8, "3.75");
            PositionEntity ph = openPos("LIVE", l8);
            Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
            new TransactionTemplate(txManager).executeWithoutResult(st ->
                    ReflectionTestUtils.invokeMethod(target, "reconcilePhantomPosition", ph, BigDecimal.ZERO));
            check("S7 팬텀 추정 청산 CLOSED", "CLOSED".equals(positionRepository.findById(ph.getId()).orElseThrow().getStatus()),
                    positionRepository.findById(ph.getId()).orElseThrow().getStatus());
            RiskCheckResult s8r = risk.checkRisk();
            check("S7 팬텀 미확정 잔량 → checkRisk 판정 불가 거절", !s8r.isApproved() && String.valueOf(s8r.getReason()).contains("판정 불가"),
                    verdict(s8r));
            Map<String, Object> s8b = breaker("LIVE", l8);
            check("S7 팬텀 — 연속 손실 판정 불가(발동 아님)", Boolean.FALSE.equals(s8b.get("triggered"))
                    && String.valueOf(s8b.get("reason")).contains("판정 불가"), s8b);
        } finally {
            limits.setStage(stage);
            limits.setTotalCapKrw(total);
            limits.setPerPositionCapKrw(per);
            limits.setMaxConcurrentPositions(slots);
            out.put("failures", failures);
            String dir = System.getenv("COSTB_OUT");
            if (dir != null) {
                Files.createDirectories(Path.of(dir));
                Files.writeString(Path.of(dir, "cost_basis_checks.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
            }
            System.out.println("[비용 기준] " + objectMapper.writeValueAsString(out));
        }
        assertThat(failures).as("비용 기준 기대와 다른 항목").isEmpty();
    }
}
