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
 * <b>비용 기준 후속 경계</b> (총괄 v1.193 §2·§3) — 32d5dafd 에서 먼저 실패로 고정하고, 좁은 보완 후보에서 통과시킨다.
 *
 * <p>{@link CostBasisBoundaryPgTest}(59단언)·{@link CostBasisRiskPgTest}(48단언)와 별도 클래스다 — 기존 통과 결과를 다시 돌리거나 덮지 않는다.
 * 헬퍼는 CostBasisBoundaryPgTest 에서 그대로 옮겼다.</p>
 * <pre>
 * F1 BUY 체결 수량 NULL(미보고) — 실제 매수 경로 → 거래소 취소·체결 0 → 제품 고아 정리 CLOSED 뒤, 그 BUY 의 수량만 NULL 로 바꾼다.
 *    CANCELLED·cancel_confirmed_at 있음·수량 NULL, FAILED·수량 NULL 모두 무거래 확정이 아니다 → 전역 관문·세션 연속 손실·진입이 판정 불가.
 *    확정 0 을 다시 넣으면(정상 경로의 값) 다음 조회에서 해제(종전 확정 무거래 계약 그대로). LIVE·DYNAMIC 각각.
 * F2 확정 매도의 대금 NULL·수량 NULL — 매도 수수료 NULL(CostBasisBoundaryPgTest N3)과 같은 계약: 손실 합에 0 으로 넣지 않고 판정 불가,
 *    값 복구 뒤 다음 조회에서 해제·비용 뒤 손실 −2.75 로 판정. LIVE·DYNAMIC 각각.
 * F3 DYNAMIC 진입 예약 거절(ENTRY_REJECTED, 수량 0·주문 0) — 거래가 아니다: 킬 기준 세션 집계·보고서 청산 통계·LIVE 성과 요약(세션 id 가 겹칠 때)·
 *    전략 성과 표시에서 빠진다. 같은 DYNAMIC 의 정상 왕복 1건은 그대로 거래 1·승 1 로 남는다(기존 거래 계산 불변). 거절 행 자체는 남는다(감사).
 * </pre>
 * 행 직접 쓰기는 미보고 상태 재현과 그 복구에만 쓰고 라벨에 적는다.
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
@DisplayName("비용 기준 후속 경계 — BUY 수량 NULL·매도 대금/수량 NULL·예약 거절 성과 제외 (격리 PG)")
class CostBasisFollowupPgTest extends IntegrationTestBase {

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
    @Autowired private com.cryptoautotrader.api.report.LogAnalyzerService logAnalyzerService;

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

    /** 무거래 확정으로 고아 정리된 매수 취소(F1 기점). 반환: BUY 주문 id. 정상 경로 값(체결 0)으로 관문 승인까지 확인한다. */
    private Long confirmedCancelledBuy(String kind, Long sid) throws Exception {
        setRisk("50", 5);   // 매수 경로는 사전 등록 한도에서
        markPriceBook.record("KRW-RND", new BigDecimal("300"), Instant.now());
        assertThat(buyPath(kind, sid)).as("%s#%d 매수 경로", kind, sid).isNull();
        Long buy = orders(kind, sid, "BUY").get(0).getId();
        awaitSubmitted(buy);
        poll(buy, resp("bid", "cancel", "0", "0", "0"));
        orphanReconcile(kind);
        setRisk("0.001", 1);
        OrderEntity c = orderRepository.findById(buy).orElseThrow();
        PositionEntity p = positionRepository.findById(c.getPositionId()).orElseThrow();
        check("F1 " + kind + " 기점: 체결 0 취소 매수(state=" + c.getState() + ", 수량=" + c.getFilledQuantity()
                        + ", 취소 확정=" + (c.getCancelConfirmedAt() != null) + ") → 고아 정리 CLOSED",
                "CLOSED".equals(p.getStatus()) && c.getFilledQuantity() != null && c.getFilledQuantity().signum() == 0,
                p.getStatus());
        RiskCheckResult g = gate(kind);
        check("F1 " + kind + " 기점: 확정 0 무거래 → 관문 승인", g.isApproved(), verdict(g));
        return buy;
    }

    @Test
    @DisplayName("비용 기준 후속 경계 — BUY 수량 NULL 은 무거래 확정 아님, 매도 대금/수량 NULL 판정 불가, 예약 거절은 거래 아님")
    void costBasisFollowups() throws Exception {
        String stage = limits.getStage();
        BigDecimal total = limits.getTotalCapKrw();
        BigDecimal per = limits.getPerPositionCapKrw();
        Integer slots = limits.getMaxConcurrentPositions();
        limits.setStage("COSTBF_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 30);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("10000000")));
        poolLossGuard.setEnabledSetting(true);
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("cbf-" + UUID.randomUUID());
                    r.setSide(inv.getArgument(1));
                    r.setOrdType(inv.getArgument(4));
                    r.setState("wait");
                    r.setMarket(inv.getArgument(0));
                    return new UpbitOrderClient.ExchangeResult(r, "{}");
                });
        try {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"order\"", Long.class)).as("새 DB").isZero();
            for (String kind : List.of("LIVE", "DYNAMIC")) {
                // ── F1 BUY 체결 수량 NULL(미보고) → 무거래 확정 아님 ──
                Long s1 = newSession(kind);
                Long buy1 = confirmedCancelledBuy(kind, s1);
                jdbc.update("UPDATE \"order\" SET filled_quantity = NULL WHERE id = ?", buy1);   // 행 직접 — 매수 수량 미보고 재현(CANCELLED·취소 확정 유지)
                RiskCheckResult g1 = gate(kind);
                check("F1 " + kind + " CANCELLED·취소 확정·수량 NULL → 관문 판정 불가 거절(NULL 을 확정 0 으로 보지 않음)",
                        !g1.isApproved() && undet(g1.getReason()), verdict(g1));
                Map<String, Object> b1 = breaker(kind, s1);
                check("F1 " + kind + " 수량 NULL → 연속 손실 판정 불가(발동 아님)",
                        Boolean.FALSE.equals(b1.get("triggered")) && undet(b1.get("reason")), b1);
                String e1 = entryBlock(kind, s1);
                check("F1 " + kind + " 수량 NULL → 신규 진입 차단 사유", e1 != null && undet(e1), e1);
                jdbc.update("UPDATE \"order\" SET state = 'FAILED' WHERE id = ?", buy1);   // 행 직접 — FAILED·수량 NULL 재현
                RiskCheckResult g1f = gate(kind);
                check("F1 " + kind + " FAILED·수량 NULL → 관문 판정 불가 거절", !g1f.isApproved() && undet(g1f.getReason()), verdict(g1f));
                jdbc.update("UPDATE \"order\" SET filled_quantity = 0 WHERE id = ?", buy1);   // 행 직접 — 확정 0 보고 반영 재현
                RiskCheckResult g1z = gate(kind);
                check("F1 " + kind + " 확정 0 → 다음 조회 자동 해제·무거래 승인", g1z.isApproved(), verdict(g1z));
                Map<String, Object> b1z = breaker(kind, s1);
                check("F1 " + kind + " 확정 0 → 연속 손실 판정 불가 아님", Boolean.FALSE.equals(b1z.get("triggered")) && !undet(b1z.get("reason")), b1z);
                check("F1 " + kind + " 확정 0 → 진입 차단 없음", entryBlock(kind, s1) == null, entryBlock(kind, s1));
                ageAll();

                // ── F2 확정 매도 대금 NULL·수량 NULL → 수수료 NULL 과 같은 판정 불가 계약 ──
                setRisk("50", 5);
                Long s2 = newSession(kind);
                Long[] t2 = trip(kind, s2, "7504.75", "3.75");
                setRisk("0.001", 1);
                jdbc.update("UPDATE \"order\" SET applied_executed_funds = NULL WHERE id = ?", t2[1]);   // 행 직접 — 매도 대금 미보고 재현
                check("F2 " + kind + " 대금 NULL 매도는 손실 합에 0 으로 넣지 않음(합 0)", eq(loss(kind), "0"), loss(kind));
                RiskCheckResult g2 = gate(kind);
                check("F2 " + kind + " 매도 대금 NULL → 관문 판정 불가 거절", !g2.isApproved() && undet(g2.getReason()), verdict(g2));
                Map<String, Object> b2 = breaker(kind, s2);
                check("F2 " + kind + " 매도 대금 NULL → 연속 손실 판정 불가(발동 아님)",
                        Boolean.FALSE.equals(b2.get("triggered")) && undet(b2.get("reason")), b2);
                String e2 = entryBlock(kind, s2);
                check("F2 " + kind + " 매도 대금 NULL → 진입 차단", e2 != null && undet(e2), e2);
                jdbc.update("UPDATE \"order\" SET applied_executed_funds = 7504.75 WHERE id = ?", t2[1]);   // 행 직접 — 확정 대금 반영 재현
                check("F2 " + kind + " 대금 복구 → 손실 합 −2.75", eq(loss(kind), "-2.75"), loss(kind));
                RiskCheckResult g2r = gate(kind);
                check("F2 " + kind + " 대금 복구 → 손실로 거절(판정 불가 아님)", !g2r.isApproved() && !undet(g2r.getReason()), verdict(g2r));
                jdbc.update("UPDATE \"order\" SET filled_quantity = NULL WHERE id = ?", t2[1]);   // 행 직접 — 매도 수량 미보고 재현
                check("F2 " + kind + " 수량 NULL 매도는 손실 합에 0 으로 넣지 않음(합 0)", eq(loss(kind), "0"), loss(kind));
                RiskCheckResult g2q = gate(kind);
                check("F2 " + kind + " 매도 수량 NULL → 관문 판정 불가 거절", !g2q.isApproved() && undet(g2q.getReason()), verdict(g2q));
                jdbc.update("UPDATE \"order\" SET filled_quantity = 25 WHERE id = ?", t2[1]);   // 행 직접 — 확정 수량 반영 재현
                check("F2 " + kind + " 수량 복구 → 손실 합 −2.75", eq(loss(kind), "-2.75"), loss(kind));
                RiskCheckResult g2qr = gate(kind);
                check("F2 " + kind + " 수량 복구 → 손실로 거절(판정 불가 아님)", !g2qr.isApproved() && !undet(g2qr.getReason()), verdict(g2qr));
                ageAll();
            }

            // ── F3 DYNAMIC 진입 예약 거절은 거래가 아니다 ──
            setRisk("50", 5);
            Long sa = newDynamic("REAL");
            trip("DYNAMIC", sa, "7510.00", "3.75");   // 정상 왕복 — 비용 뒤 +2.50
            ageAll();
            Long sb = newDynamic("REAL");
            // LIVE 성과 요약은 세션 id 로만 포지션을 모은다 — DYNAMIC 과 id 가 겹치는 LIVE 세션을 둔다(경로 그대로, 겹침 자체는 기존 동작)
            for (int i = 0; i < 50 && liveRepo.findById(sb).isEmpty(); i++) newLive();
            boolean overlap = liveRepo.findById(sb).isPresent();
            check("F3 사례 구성: DYNAMIC#" + sb + " 과 같은 id 의 LIVE 세션", overlap, overlap);
            int liveTradesBefore = liveTradingService.getPerformanceSummary().getTotalTrades();
            setRisk("0.001", 1);   // 사전 등록과 다른 한도 → 공유 풀 예약 RISK_CONFIG_MISMATCH 거절(제품 경로)
            markPriceBook.record("KRW-RND", new BigDecimal("300"), Instant.now());
            String r3 = buyPath("DYNAMIC", sb);
            check("F3 DYNAMIC 매수 예약 거절", r3 != null && r3.contains("예약 거절"), r3);
            List<PositionEntity> ps = positionRepository.findBySessionKindAndSessionId("DYNAMIC", sb);
            boolean rejectedRow = ps.size() == 1 && "CLOSED".equals(ps.get(0).getStatus())
                    && ps.get(0).getExitReason() == ExitReason.ENTRY_REJECTED && orders("DYNAMIC", sb, "BUY").isEmpty();
            check("F3 거절 슬롯 CLOSED·ENTRY_REJECTED·주문 없음(행은 감사용으로 남음)", rejectedRow,
                    ps.isEmpty() ? "없음" : ps.get(0).getStatus() + "/" + ps.get(0).getSize() + "/" + ps.get(0).getExitReason()
                            + "/invested=" + ps.get(0).getInvestedKrw());
            check("F3 ENTRY_REJECTED 는 전략 성과 집계 대상 아님", !ExitReason.ENTRY_REJECTED.countsTowardStrategyPerformance(),
                    ExitReason.ENTRY_REJECTED.countsTowardStrategyPerformance());
            check("F3 기존 사유(TAKE_PROFIT·STOP_LOSS)는 성과 집계 대상 그대로",
                    ExitReason.TAKE_PROFIT.countsTowardStrategyPerformance() && ExitReason.STOP_LOSS.countsTowardStrategyPerformance()
                            && !ExitReason.FORCED_STOP.countsTowardStrategyPerformance(), "TP/SL/FORCED");

            Map<String, long[]> kill = new LinkedHashMap<>();
            for (Object[] row : positionRepository.aggregateClosedTradesPerSession()) {
                if ("DYNAMIC".equals(String.valueOf(row[0]))) {
                    kill.put(String.valueOf(row[1]), new long[]{((Number) row[2]).longValue(), ((Number) row[5]).longValue()});
                }
            }
            long[] ka = kill.get(String.valueOf(sa));
            check("F3 킬 기준 집계: 정상 왕복 DYNAMIC#" + sa + " 거래 1·승 1 그대로", ka != null && ka[0] == 1 && ka[1] == 1,
                    ka == null ? "없음" : ka[0] + "/" + ka[1]);
            long[] kb = kill.get(String.valueOf(sb));
            check("F3 킬 기준 집계: 예약 거절 DYNAMIC#" + sb + " 거래 0(행 없음)", kb == null || kb[0] == 0,
                    kb == null ? "없음" : kb[0] + "/" + kb[1]);

            Instant from = Instant.now().minus(60, ChronoUnit.DAYS);
            Instant to = Instant.now().plusSeconds(60);
            var report = logAnalyzerService.analyze(from, to, null);
            Long expectClosed = jdbc.queryForObject("SELECT COUNT(*) FROM position WHERE status = 'CLOSED'"
                    + " AND closed_at >= ? AND closed_at <= ? AND COALESCE(exit_reason, '') <> 'ENTRY_REJECTED'",
                    Long.class, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
            Long rejectedInWindow = jdbc.queryForObject("SELECT COUNT(*) FROM position WHERE status = 'CLOSED'"
                    + " AND closed_at >= ? AND closed_at <= ? AND exit_reason = 'ENTRY_REJECTED'",
                    Long.class, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
            check("F3 보고서 청산 수 = 예약 거절 제외 CLOSED 수(거절 " + rejectedInWindow + "건 제외)",
                    rejectedInWindow != null && rejectedInWindow >= 1 && expectClosed != null
                            && report.getClosedPositions() == expectClosed.intValue()
                            && report.getWinCount() + report.getLossCount() == expectClosed.intValue(),
                    report.getClosedPositions() + "/기대 " + expectClosed + "/승 " + report.getWinCount() + "/패 " + report.getLossCount());

            int liveTradesAfter = liveTradingService.getPerformanceSummary().getTotalTrades();
            check("F3 LIVE 성과 요약 거래 수 불변(겹치는 id 의 예약 거절 행 미포함)", liveTradesAfter == liveTradesBefore,
                    liveTradesBefore + "→" + liveTradesAfter);
            ageAll();
        } finally {
            limits.setStage(stage);
            limits.setTotalCapKrw(total);
            limits.setPerPositionCapKrw(per);
            limits.setMaxConcurrentPositions(slots);
            out.put("failures", failures);
            String dir = System.getenv("COSTB_OUT");
            if (dir != null) {
                Files.createDirectories(Path.of(dir));
                Files.writeString(Path.of(dir, "cost_basis_followup_checks.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
            }
            System.out.println("[비용 기준 후속 경계] " + objectMapper.writeValueAsString(out));
        }
        assertThat(failures).as("비용 기준 후속 경계 기대와 다른 항목").isEmpty();
    }
}
