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
 * <b>비용 기준 판정 불가 경계</b> (총괄 v1.192 §2) — b861bdf9 에서 먼저 실패로 고정하고, 보완 후보에서 통과시킨다.
 *
 * <p>{@link CostBasisRiskPgTest}(48단언)와 별도 클래스다 — 같은 반례를 기존 통과로 덮지 않는다. 헬퍼는 그 클래스에서 그대로 옮겼다.</p>
 * <pre>
 * N1 주문 원천 없는 REAL 청산(저장 손익 +1, BUY/SELL 행 없음) → 전역 관문·세션 연속 손실·진입이 판정 불가, 다시 조회해도 그대로(원천 없음)
 *    LIVE 는 제품 고아 정리(reconcileOrphanBuyPositions 의 주문 없는 경로)로 닫힌 청산도 같은 판정
 * N2 체결 없음이 확정된 매수 취소(실제 매수 경로 → 거래소 취소·체결 0 → 제품 고아 정리) → 판정 불가 아님
 * N3 매도 수수료 NULL(미보고) → 판정 불가 → 확정 0 이면 해제·손실 아님 → 확정 3.75 이면 해제·비용 뒤 −2.75 손실
 * N4 매수 원가 미상 → 복구 뒤 다음 조회에서 자동 해제(이미 기점 동작 — 해제 경로 확인)
 * N5 미세 손실: −0.5 한 건은 합에서 0 으로 버리지 않음(비율 0.00% — 기존 0.01% 해상도 유지), 두 건 합 −1.0 은 0.01% 로 거절
 * </pre>
 * 행 직접 쓰기는 미보고/원천 없음 상태 재현과 그 복구에만 쓰고 라벨에 적는다.
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
@DisplayName("비용 기준 경계 — 주문 원천 없는 청산·매도 수수료 NULL·자동 해제·미세 손실 (격리 PG)")
class CostBasisBoundaryPgTest extends IntegrationTestBase {

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

    /**
     * 앞 단계 행을 일/주/월 창 밖(40일 전)으로 옮기고 앞 단계 세션을 STOPPED 로 둔다 — 삭제하지 않는다. 세션을 멈추는 것은 손실률 분모
     * (RUNNING 초기자본 합)가 단계마다 늘어 손실률이 0.01% 해상도(비율 소수 넷째 자리 반올림, 제품 그대로) 아래로 내려가지 않게 하려는 것이다.
     */
    private void ageAll() {
        jdbc.update("UPDATE live_trading_session SET status = 'STOPPED' WHERE status = 'RUNNING'");
        jdbc.update("UPDATE dynamic_session SET status = 'STOPPED' WHERE status = 'RUNNING'");
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

    private Long noSourceClosed(String kind, Long sid) {
        // 행 직접 — 주문 행이 연결되지 않은 REAL 청산(총괄 v1.192 반례: 저장 손익 +1, BUY/SELL 없음)
        return positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-RND").side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(BigDecimal.ZERO).realizedPnl(BigDecimal.ONE).status("CLOSED")
                .sessionKind(kind).sessionId(sid).openedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .closedAt(Instant.now().minusSeconds(5)).build()).getId();
    }

    private RiskCheckResult gate(String kind) {
        return "LIVE".equals(kind) ? risk.checkRisk() : risk.checkRealLossGuard();
    }

    private Long newSession(String kind) {
        return "LIVE".equals(kind) ? newLive() : newDynamic("REAL");
    }

    private String entryBlock(String kind, Long sid) {
        return "LIVE".equals(kind) ? risk.costBasisEntryBlock(liveRepo.findById(sid).orElseThrow())
                : risk.costBasisEntryBlock(dynamicRepo.findById(sid).orElseThrow());
    }

    private BigDecimal loss(String kind) {
        Instant far = Instant.now().minus(30, ChronoUnit.DAYS);
        return "LIVE".equals(kind) ? liveLoss(far) : dynLoss(far);
    }

    private static boolean undet(Object reason) {
        return String.valueOf(reason).contains("판정 불가");
    }

    private void orphanReconcile(String kind) {
        if ("LIVE".equals(kind)) liveTradingService.reconcileOrphanBuyPositions();
        else dynamicTradingService.reconcileDynamicOrphanBuyPositions();
    }

    @Test
    @DisplayName("비용 기준 경계 — 원천 없는 청산·매도 수수료 NULL 은 판정 불가, 확정 원천 복구 뒤 자동 해제")
    void costBasisBoundaries() throws Exception {
        String stage = limits.getStage();
        BigDecimal total = limits.getTotalCapKrw();
        BigDecimal per = limits.getPerPositionCapKrw();
        Integer slots = limits.getMaxConcurrentPositions();
        limits.setStage("COSTBB_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 30);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("10000000")));
        poolLossGuard.setEnabledSetting(true);
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("cbb-" + UUID.randomUUID());
                    r.setSide(inv.getArgument(1));
                    r.setOrdType(inv.getArgument(4));
                    r.setState("wait");
                    r.setMarket(inv.getArgument(0));
                    return new UpbitOrderClient.ExchangeResult(r, "{}");
                });
        try {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"order\"", Long.class)).as("새 DB").isZero();
            // 측정 한도 입력 0.001 → risk_config 소수 둘째 자리라 0.00% 저장: 0 이 아닌 손실률이면 거절, 0 이면 승인.
            for (String kind : List.of("LIVE", "DYNAMIC")) {
                // ── N1 주문 원천 없는 청산 ──
                setRisk("0.001", 1);
                Long s1 = newSession(kind);
                noSourceClosed(kind, s1);
                RiskCheckResult g1 = gate(kind);
                check("N1 " + kind + " 원천 없는 청산 → 전역 관문 판정 불가 거절", !g1.isApproved() && undet(g1.getReason()), verdict(g1));
                Map<String, Object> b1 = breaker(kind, s1);
                check("N1 " + kind + " 세션 연속 손실 판정 불가(발동 아님, 저장 손익 +1 을 쓰지 않음)",
                        Boolean.FALSE.equals(b1.get("triggered")) && undet(b1.get("reason")), b1);
                String e1 = entryBlock(kind, s1);
                check("N1 " + kind + " 신규 진입 차단 사유", e1 != null && undet(e1), e1);
                RiskCheckResult g1b = gate(kind);
                check("N1 " + kind + " 원천이 끝내 없음 — 다음 조회도 판정 불가", !g1b.isApproved() && undet(g1b.getReason()), verdict(g1b));
                String st1 = "LIVE".equals(kind) ? liveRepo.findById(s1).orElseThrow().getStatus()
                        : dynamicRepo.findById(s1).orElseThrow().getStatus();
                check("N1 " + kind + " 세션 RUNNING 그대로(정지 없음)", "RUNNING".equals(st1), st1);
                ageAll();

                if ("LIVE".equals(kind)) {
                    // 제품 고아 정리의 주문 없는 경로(포지션 생성 5분 뒤, 주문 엔티티 없음)로 닫힌 청산
                    Long s1p = newLive();
                    Long pid = positionRepository.saveAndFlush(PositionEntity.builder()
                            .coinPair("KRW-RND").side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                            .size(BigDecimal.ZERO).realizedPnl(BigDecimal.ZERO).status("OPEN")
                            .sessionKind("LIVE").sessionId(s1p).openedAt(Instant.now().minus(10, ChronoUnit.MINUTES)).build()).getId();
                    orphanReconcile("LIVE");
                    String pst = positionRepository.findById(pid).orElseThrow().getStatus();
                    check("N1 LIVE 제품 고아 정리(주문 없음) → CLOSED", "CLOSED".equals(pst), pst);
                    RiskCheckResult g1p = gate("LIVE");
                    check("N1 LIVE 주문 없는 고아 청산 → checkRisk 판정 불가 거절", !g1p.isApproved() && undet(g1p.getReason()), verdict(g1p));
                    ageAll();
                }

                // ── N2 체결 없음이 확정된 매수 취소 → 판정 불가 아님 ──
                setRisk("0.001", 1);
                Long s2 = newSession(kind);
                markPriceBook.record("KRW-RND", new BigDecimal("300"), Instant.now());
                assertThat(buyPath(kind, s2)).as("%s#%d 매수 경로", kind, s2).isNull();
                Long buy2 = orders(kind, s2, "BUY").get(0).getId();
                awaitSubmitted(buy2);
                poll(buy2, resp("bid", "cancel", "0", "0", "0"));
                OrderEntity c2 = orderRepository.findById(buy2).orElseThrow();
                orphanReconcile(kind);
                PositionEntity p2 = positionRepository.findById(c2.getPositionId()).orElseThrow();
                check("N2 " + kind + " 체결 0 취소 매수 → 고아 정리 CLOSED (state=" + c2.getState() + ")", "CLOSED".equals(p2.getStatus()),
                        p2.getStatus());
                RiskCheckResult g2 = gate(kind);
                check("N2 " + kind + " 확정된 무거래 청산 → 관문 승인(손실·판정 불가 아님)", g2.isApproved(), verdict(g2));
                Map<String, Object> b2 = breaker(kind, s2);
                check("N2 " + kind + " 연속 손실 판정 불가 아님", Boolean.FALSE.equals(b2.get("triggered")) && !undet(b2.get("reason")), b2);
                String e2 = entryBlock(kind, s2);
                check("N2 " + kind + " 진입 차단 없음", e2 == null, e2);
                ageAll();

                // ── N3 매도 수수료 NULL → 확정 0 → 확정 3.75 ──
                setRisk("50", 5);
                Long s3 = newSession(kind);
                Long[] t3 = trip(kind, s3, "7504.75", "3.75");
                setRisk("0.001", 1);
                jdbc.update("UPDATE \"order\" SET applied_paid_fee = NULL WHERE id = ?", t3[1]);   // 행 직접 — 매도 수수료 미보고 재현
                check("N3 " + kind + " 수수료 NULL 매도는 손실 합에 0 으로 넣지 않음(합 0)", eq(loss(kind), "0"), loss(kind));
                RiskCheckResult g3 = gate(kind);
                check("N3 " + kind + " 매도 수수료 NULL → 관문 판정 불가 거절", !g3.isApproved() && undet(g3.getReason()), verdict(g3));
                Map<String, Object> b3 = breaker(kind, s3);
                check("N3 " + kind + " 매도 수수료 NULL → 연속 손실 판정 불가(발동 아님)",
                        Boolean.FALSE.equals(b3.get("triggered")) && undet(b3.get("reason")), b3);
                String e3 = entryBlock(kind, s3);
                check("N3 " + kind + " 매도 수수료 NULL → 진입 차단", e3 != null && undet(e3), e3);

                jdbc.update("UPDATE \"order\" SET applied_paid_fee = 0 WHERE id = ?", t3[1]);      // 행 직접 — 확정 0 보고 반영 재현
                RiskCheckResult g3z = gate(kind);
                check("N3 " + kind + " 확정 0 → 다음 조회 자동 해제·손익 +1 이라 승인", g3z.isApproved(), verdict(g3z));
                Map<String, Object> b3z = breaker(kind, s3);
                check("N3 " + kind + " 확정 0 → 연속 손실 미발동·판정 불가 아님",
                        Boolean.FALSE.equals(b3z.get("triggered")) && !undet(b3z.get("reason")), b3z);
                check("N3 " + kind + " 확정 0 → 진입 차단 없음", entryBlock(kind, s3) == null, entryBlock(kind, s3));

                jdbc.update("UPDATE \"order\" SET applied_paid_fee = 3.75 WHERE id = ?", t3[1]);   // 행 직접 — 확정 3.75 보고 반영 재현
                check("N3 " + kind + " 확정 3.75 → 손실 합 −2.75", eq(loss(kind), "-2.75"), loss(kind));
                RiskCheckResult g3p = gate(kind);
                check("N3 " + kind + " 확정 3.75 → 손실로 거절(판정 불가 아님)", !g3p.isApproved() && !undet(g3p.getReason()), verdict(g3p));
                Map<String, Object> b3p = breaker(kind, s3);
                check("N3 " + kind + " 확정 3.75 → 연속 손실 한도1 발동", Boolean.TRUE.equals(b3p.get("triggered")), b3p);
                ageAll();

                // ── N4 매수 원가 미상 → 복구 뒤 자동 해제 ──
                setRisk("50", 5);
                Long s4 = newSession(kind);
                Long[] t4 = trip(kind, s4, "7504.75", "3.75");
                setRisk("0.001", 1);
                jdbc.update("UPDATE \"order\" SET applied_paid_fee = NULL WHERE id = ?", t4[0]);   // 행 직접 — 매수 수수료 미보고 재현
                RiskCheckResult g4 = gate(kind);
                check("N4 " + kind + " 매수 원가 미상 → 판정 불가", !g4.isApproved() && undet(g4.getReason()), verdict(g4));
                jdbc.update("UPDATE \"order\" SET applied_paid_fee = 3.75 WHERE id = ?", t4[0]);   // 행 직접 — 확정 보고 반영 재현
                RiskCheckResult g4r = gate(kind);
                check("N4 " + kind + " 복구 → 다음 조회 해제·비용 뒤 손실 거절", !g4r.isApproved() && !undet(g4r.getReason()), verdict(g4r));
                check("N4 " + kind + " 복구 → 진입 차단(판정 불가) 없음", entryBlock(kind, s4) == null, entryBlock(kind, s4));
                ageAll();

                // ── N5 미세 손실: 합 전 개별 손익을 0 으로 버리지 않음 ──
                setRisk("50", 5);
                Long s5 = newSession(kind);
                trip(kind, s5, "7507.00", "3.75");   // 7,507.00 − 3.75 − 7,503.75 = −0.50
                setRisk("0.001", 5);
                check("N5 " + kind + " 미세 손실 한 건 합 = −0.50(0 으로 버리지 않음)", eq(loss(kind), "-0.5"), loss(kind));
                RiskCheckResult g5 = gate(kind);
                check("N5 " + kind + " 미세 손실 한 건 — 비율 0.00%(기존 0.01% 해상도)라 승인", g5.isApproved(), verdict(g5));
                setRisk("50", 5);
                trip(kind, s5, "7507.00", "3.75");
                setRisk("0.001", 5);
                check("N5 " + kind + " 미세 손실 두 건 합 = −1.00", eq(loss(kind), "-1.0"), loss(kind));
                RiskCheckResult g5b = gate(kind);
                check("N5 " + kind + " 합 −1.00 → 0.01% 로 거절(손실, 판정 불가 아님)", !g5b.isApproved() && !undet(g5b.getReason()), verdict(g5b));
                ageAll();
            }
        } finally {
            limits.setStage(stage);
            limits.setTotalCapKrw(total);
            limits.setPerPositionCapKrw(per);
            limits.setMaxConcurrentPositions(slots);
            out.put("failures", failures);
            String dir = System.getenv("COSTB_OUT");
            if (dir != null) {
                Files.createDirectories(Path.of(dir));
                Files.writeString(Path.of(dir, "cost_basis_boundary_checks.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
            }
            System.out.println("[비용 기준 경계] " + objectMapper.writeValueAsString(out));
        }
        assertThat(failures).as("비용 기준 경계 기대와 다른 항목").isEmpty();
    }
}
