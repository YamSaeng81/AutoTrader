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
 * <b>공유 풀 REAL 매수 요약 적재</b> (총괄 v1.194 §3) — 원자적 매수 정산({@code observeAndApplyBuyFill})이 거래 요약 버퍼를 부르지 않아
 * LIVE·DYNAMIC REAL 매수가 일별 요약에서 빠지던 공백. 기점 60df3437 의 교차 시험 헬퍼를 그대로 옮겼다.
 * 알림은 실제 전송하지 않는다 — 시험 설정의 알림 꺼짐을 그대로 두고 {@code bufferTradeEvent} 호출 인자만 본다(SpyBean).
 * <pre>
 * R1·R2 LIVE·DYNAMIC 완전 체결 → 매수 1건(종류#ID, 25@300, 수수료 3.75, 손익 null), 같은 응답 반복·수수료만 정정 → 추가 0,
 *       뒤 매도 → 매도 1건·매수 0.
 * R3 부분 체결 10 → 취소 확정 19.99: 증가분 10·9.99 두 건, 합 = 확정 수량, 같은 취소 응답 반복 → 추가 0.
 * R4 미체결 취소 → 0.  R5 포지션 갱신 실패(시험 DB 일시 트리거)로 회계 롤백 → 0, 트리거 제거 뒤 재처리 → 1.
 * R6 버퍼가 예외를 던져도 정산(FILLED·수량·대기 해제·포지션)은 그대로.
 * </pre>
 * 행 직접 쓰기는 하지 않는다. 시험 DB 트리거는 R5 안에서만 걸고 지운다.
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
@DisplayName("공유 풀 REAL 매수 요약 적재 (격리 PG)")
class RealBuySummaryPgTest extends IntegrationTestBase {

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
    @org.springframework.boot.test.mock.mockito.SpyBean private TelegramNotificationService telegram;

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


    /** 금액/BTC 제한 DYNAMIC REAL 세션 — 생성 정규화(DynamicEntryLimits.normalizeForCreate)를 거친 값과 같은 형태로 저장. */
    private Long newLimitedDynamic() {
        Long id = newDynamic("REAL");
        DynamicSessionEntity s = dynamicRepo.findById(id).orElseThrow();
        Map<String, Object> params = new LinkedHashMap<>();
        if (s.getStrategyParams() != null) params.putAll(s.getStrategyParams());
        params.put(DynamicEntryLimits.MAX_ORDER_KRW, 6000);
        params.put(DynamicEntryLimits.EXCLUDE_COINS, List.of("KRW-BTC"));
        s.setStrategyParams(params);
        dynamicRepo.saveAndFlush(s);
        return id;
    }

    private String dynBuy(Long sid, String coin) {
        return new TransactionTemplate(txManager).execute(st ->
                dynamicTradingService.executeBuy(dynamicRepo.findById(sid).orElseThrow(), coin, candles(),
                        com.cryptoautotrader.strategy.StrategySignal.buy(new BigDecimal("80"), "교차 시험 매수"), BigDecimal.ONE));
    }

    private List<List<Object>> alertEvents() {
        List<List<Object>> out2 = new ArrayList<>();
        for (var inv : Mockito.mockingDetails(telegram).getInvocations()) {
            if ("bufferTradeEvent".equals(inv.getMethod().getName())) out2.add(List.of(inv.getArguments()));
        }
        return out2;
    }

    private String residue(Long sid) {
        long pos = positionRepository.findBySessionKindAndSessionId("DYNAMIC", sid).size();
        long ord = jdbc.queryForObject("SELECT COUNT(*) FROM \"order\" WHERE session_kind = 'DYNAMIC' AND session_id = ?", Long.class, sid);
        BigDecimal reserved = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_krw), 0) FROM \"order\" WHERE session_kind = 'DYNAMIC' AND session_id = ?",
                BigDecimal.class, sid);
        BigDecimal avail = dynamicRepo.findById(sid).orElseThrow().getAvailableKrw();
        return "pos=" + pos + " ord=" + ord + " reserved=" + reserved.stripTrailingZeros().toPlainString() + " avail=" + avail.toPlainString();
    }


    private List<List<Object>> events(String side) {
        List<List<Object>> r = new ArrayList<>();
        for (List<Object> a : alertEvents()) if (side.equals(a.get(2))) r.add(a);
        return r;
    }

    private static BigDecimal sumQty(List<List<Object>> ev) {
        BigDecimal s = BigDecimal.ZERO;
        for (List<Object> a : ev) s = s.add((BigDecimal) a.get(4));
        return s;
    }

    /** 매수 경로로 예약·제출까지(체결 전). 반환: 매수 주문 id. */
    private Long submitBuy(String kind, Long sid) throws Exception {
        markPriceBook.record("KRW-RND", new BigDecimal("300"), Instant.now());
        String r = buyPath(kind, sid);
        assertThat(r).as("%s#%d 매수 경로", kind, sid).isNull();
        Long id = orders(kind, sid, "BUY").get(0).getId();
        awaitSubmitted(id);
        return id;
    }

    private String ord(Long id) {
        OrderEntity o = orderRepository.findById(id).orElseThrow();
        return o.getState() + " qty=" + (o.getFilledQuantity() == null ? "null" : o.getFilledQuantity().stripTrailingZeros().toPlainString())
                + " funds=" + (o.getAppliedExecutedFunds() == null ? "null" : o.getAppliedExecutedFunds().stripTrailingZeros().toPlainString())
                + " fee=" + (o.getAppliedPaidFee() == null ? "null" : o.getAppliedPaidFee().stripTrailingZeros().toPlainString())
                + " pending=" + o.getFillAccountingPending();
    }

    @Test
    @DisplayName("공유 풀 REAL 매수 요약 적재 — 커밋된 체결 증가분만, 종류#ID, 반복·수수료 정정·미체결 취소·롤백·버퍼 실패")
    void realBuySummary() throws Exception {
        String stage = limits.getStage();
        BigDecimal total = limits.getTotalCapKrw();
        BigDecimal per = limits.getPerPositionCapKrw();
        Integer slots = limits.getMaxConcurrentPositions();
        limits.setStage("RBS_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 30);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("10000000")));
        poolLossGuard.setEnabledSetting(true);
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("rbs-" + UUID.randomUUID());
                    r.setSide(inv.getArgument(1));
                    r.setOrdType(inv.getArgument(4));
                    r.setState("wait");
                    r.setMarket(inv.getArgument(0));
                    return new UpbitOrderClient.ExchangeResult(r, "{}");
                });
        try {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"order\"", Long.class)).as("새 DB").isZero();
            check("시험 알림 꺼짐(실제 전송 없음)", Boolean.FALSE.equals(ReflectionTestUtils.getField(telegram, "enabled")),
                    ReflectionTestUtils.getField(telegram, "enabled"));
            setRisk("50", 5);

            // ── R1·R2 두 종류의 완전 체결 → 매수 1건, 종류#ID, 수량·가격·수수료 = 확정 증가분. 반복 응답은 추가 없음. 매도 요약 1건 ──
            for (String kind : List.of("LIVE", "DYNAMIC")) {
                Long sid = "LIVE".equals(kind) ? newLive() : newDynamic("REAL");
                Long b = submitBuy(kind, sid);
                Mockito.clearInvocations(telegram);
                poll(b, resp("bid", "done", "25", "7500", "3.75"));
                List<List<Object>> ev = events("BUY");
                boolean one = ev.size() == 1 && (kind + "#" + sid).equals(ev.get(0).get(0)) && "KRW-RND".equals(ev.get(0).get(1))
                        && eq((BigDecimal) ev.get(0).get(3), "300") && eq((BigDecimal) ev.get(0).get(4), "25")
                        && eq((BigDecimal) ev.get(0).get(5), "3.75") && ev.get(0).get(6) == null;
                check(kind + " 완전 체결 → 매수 요약 1건(" + kind + "#" + sid + "·25@300·수수료 3.75)", one, ev);
                check(kind + " 적재 수량 = 확정 체결 수량", eq(sumQty(ev), orderRepository.findById(b).orElseThrow().getFilledQuantity().toPlainString()),
                        ord(b));
                settlement.observeAndApplyBuyFill(b, withUuid(b, resp("bid", "done", "25", "7500", "3.75")));
                check(kind + " 같은 응답 반복 → 추가 0", events("BUY").size() == 1, events("BUY"));
                settlement.observeAndApplyBuyFill(b, withUuid(b, resp("bid", "done", "25", "7500", "3.80")));
                check(kind + " 수수료만 정정(3.75→3.80, 회계 반영) → 매수 이벤트 추가 0",
                        events("BUY").size() == 1 && eq(orderRepository.findById(b).orElseThrow().getAppliedPaidFee(), "3.80"), ord(b) + " " + events("BUY"));
                Mockito.clearInvocations(telegram);
                Long s = sellSubmit(kind, sid);
                poll(s, resp("ask", "done", "25", "7504.75", "3.75"));
                reconcile(kind);
                check(kind + " 매도 요약 1건·매수 이벤트 0(매도가 매수를 다시 싣지 않음)",
                        events("SELL").size() == 1 && events("BUY").isEmpty(), alertEvents());
                ageAll();
            }

            // ── R3 부분 체결 → 취소 확정: 증가분만, 합 = 확정 수량, 반복 취소 응답 추가 0 ──
            for (String kind : List.of("LIVE", "DYNAMIC")) {
                Long sid = "LIVE".equals(kind) ? newLive() : newDynamic("REAL");
                Long b = submitBuy(kind, sid);
                Mockito.clearInvocations(telegram);
                settlement.observeAndApplyBuyFill(b, withUuid(b, resp("bid", "wait", "10", "3000", "1.5")));
                check(kind + " 부분 체결 10 → 증가분 10 적재", events("BUY").size() == 1 && eq((BigDecimal) events("BUY").get(0).get(4), "10"), events("BUY"));
                // 정산 미확정 재조회 백오프가 지난 시점(제품 checkOrderStatus 의 needSettlement 조건) — 시각만 당긴다
                jdbc.update("UPDATE \"order\" SET settlement_next_check_at = now() - interval '1 second' WHERE id = ?", b);
                poll(b, resp("bid", "cancel", "19.99", "5997", "2.9985"));
                List<List<Object>> ev = events("BUY");
                check(kind + " 취소 확정 19.99 → 둘째 증가분 9.99만(앞 10 다시 더하지 않음)",
                        ev.size() == 2 && eq((BigDecimal) ev.get(1).get(4), "9.99") && eq((BigDecimal) ev.get(1).get(5), "1.4985"), ev);
                check(kind + " 적재 합 = 확정 체결 수량 19.99",
                        eq(sumQty(ev), "19.99") && eq(orderRepository.findById(b).orElseThrow().getFilledQuantity(), "19.99"), ord(b));
                settlement.observeAndApplyBuyFill(b, withUuid(b, resp("bid", "cancel", "19.99", "5997", "2.9985")));
                check(kind + " 같은 취소 응답 반복 → 추가 0", events("BUY").size() == 2, events("BUY"));
                ageAll();
            }

            // ── R4 미체결 취소 → 매수 이벤트 0 ──
            {
                Long sid = newDynamic("REAL");
                Long b = submitBuy("DYNAMIC", sid);
                Mockito.clearInvocations(telegram);
                poll(b, resp("bid", "cancel", "0", "0", "0"));
                check("미체결 취소 → 매수 이벤트 0", events("BUY").isEmpty(), ord(b) + " " + alertEvents());
                ageAll();
            }

            // ── R5 회계 롤백 → 이벤트 0, 재처리 성공 → 1건 ──
            {
                Long sid = newLive();
                Long b = submitBuy("LIVE", sid);
                Long posId = orderRepository.findById(b).orElseThrow().getPositionId();
                jdbc.execute("CREATE OR REPLACE FUNCTION rbs_fail() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'rbs 시험 롤백'; END $$ LANGUAGE plpgsql");
                jdbc.execute("CREATE TRIGGER rbs_fail_pos BEFORE UPDATE ON position FOR EACH ROW WHEN (OLD.id = " + posId + ") EXECUTE FUNCTION rbs_fail()");
                Mockito.clearInvocations(telegram);
                try {
                    poll(b, resp("bid", "done", "25", "7500", "3.75"));
                } catch (Exception ignored) {
                    // 엔진이 삼키지 않으면 여기로 온다 — 결과는 아래 상태로 본다
                }
                check("회계 롤백(포지션 갱신 실패) → 수량 미반영·매수 이벤트 0",
                        events("BUY").isEmpty() && orderRepository.findById(b).orElseThrow().getFilledQuantity().signum() == 0, ord(b) + " " + events("BUY"));
                jdbc.execute("DROP TRIGGER rbs_fail_pos ON position");
                poll(b, resp("bid", "done", "25", "7500", "3.75"));
                check("트리거 제거 뒤 재처리 → 매수 이벤트 1건(25)", events("BUY").size() == 1 && eq((BigDecimal) events("BUY").get(0).get(4), "25"),
                        ord(b) + " " + events("BUY"));
                ageAll();
            }

            // ── R6 버퍼 실패 → 정산은 커밋 그대로 ──
            {
                Long sid = newDynamic("REAL");
                Long b = submitBuy("DYNAMIC", sid);
                Mockito.clearInvocations(telegram);
                Mockito.doThrow(new IllegalStateException("rbs 버퍼 실패")).when(telegram).bufferTradeEvent(
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
                try {
                    poll(b, resp("bid", "done", "25", "7500", "3.75"));
                } finally {
                    Mockito.doCallRealMethod().when(telegram).bufferTradeEvent(
                            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
                }
                OrderEntity o = orderRepository.findById(b).orElseThrow();
                check("버퍼 실패 → 주문 FILLED·수량 25·대기 해제(정산 유지), 버퍼 호출 1회",
                        "FILLED".equals(o.getState()) && eq(o.getFilledQuantity(), "25") && !Boolean.TRUE.equals(o.getFillAccountingPending())
                                && events("BUY").size() == 1 && eq(openPos("DYNAMIC", sid).getSize(), "25"), ord(b) + " " + events("BUY"));
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
                Files.writeString(Path.of(dir, "real_buy_summary_checks.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
            }
            System.out.println("[REAL 매수 요약] " + objectMapper.writeValueAsString(out));
        }
        assertThat(failures).as("REAL 매수 요약 기대와 다른 항목").isEmpty();
    }

    private OrderResponse withUuid(Long orderId, OrderResponse r) {
        r.setUuid(orderRepository.findById(orderId).orElseThrow().getExchangeOrderId());
        return r;
    }
}
