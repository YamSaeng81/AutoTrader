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
 * <b>A+B+C 교차 경계</b> (총괄 v1.193 §3-3) — 통합 가지(aff357e2 + dfe3b9f7 + 비용 후속 b408a223)에서만 성립하는 순서·잔여물을 실제 경로로 확인한다.
 *
 * <p>헬퍼는 CostBasisBoundaryPgTest 에서 그대로 옮겼고, DYNAMIC 세션에 금액/BTC 제한(strategy_params)을 줄 수 있게만 넓혔다.
 * 알림은 실제 전송하지 않는다 — 시험 설정의 알림 꺼짐을 그대로 두고 {@code bufferTradeEvent} 호출 인자만 본다(SpyBean).</p>
 * <pre>
 * X1 비용 판정 불가(다른 DYNAMIC 세션의 매도 수수료 NULL) 상태에서, 제한 세션(maxOrderKrw 6000·excludeCoins [KRW-BTC])의
 *    KRW-BTC 매수 → A 제외 사유(C 보다 먼저) / KRW-RND 매수 → C 판정 불가 거절. 두 거절 모두 포지션·주문·예약·잔액 변화 0, 알림 이벤트 0.
 * X2 확정 비용 복구(수수료 3.75 반영) → 다음 매수는 진행, 요청액 = A 상한 6000 → 청산 알림 표시 = DYNAMIC#id(B), 거절 사유 문구 없음.
 *    공유 풀 REAL 매수 체결 알림 이벤트 수는 관측값으로만 남긴다(원자적 정산 경로가 버퍼를 부르지 않는 기존 동작 — 별도 발견).
 * X3 제한 세션의 공유 풀 예약 거절 → ENTRY_REJECTED 빈 슬롯: 주문 0·예약 0·잔액 그대로, 관문/연속 손실/진입 판정 불가 아님,
 *    킬 기준 거래 0, 알림 이벤트 0.
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
@DisplayName("A+B+C 교차 — 금액/BTC·비용 판정 불가·최소 알림·예약 거절 (격리 PG)")
class AbcCrossPgTest extends IntegrationTestBase {

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
            if ("bufferTradeEvent".equals(inv.getMethod().getName())) out2.add(java.util.Arrays.asList(inv.getArguments()));   // 매수 요약 이벤트는 손익 인자 null — List.of 는 null 을 거부한다
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

    @Test
    @DisplayName("A+B+C 교차 — 제외/판정 불가 거절 순서·잔여물 0, 확정 비용 복구 뒤 상한 매수·종류#ID 알림, 예약 거절 슬롯은 거래 아님")
    void abcCross() throws Exception {
        String stage = limits.getStage();
        BigDecimal total = limits.getTotalCapKrw();
        BigDecimal per = limits.getPerPositionCapKrw();
        Integer slots = limits.getMaxConcurrentPositions();
        limits.setStage("ABCX_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 30);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("10000000")));
        poolLossGuard.setEnabledSetting(true);
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("abcx-" + UUID.randomUUID());
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

            // ── X1 판정 불가 상태에서 제외 코인·일반 코인 거절 순서와 잔여물 ──
            setRisk("50", 5);
            Long s0 = newDynamic("REAL");
            Long[] t0 = trip("DYNAMIC", s0, "7504.75", "3.75");
            jdbc.update("UPDATE \"order\" SET applied_paid_fee = NULL WHERE id = ?", t0[1]);   // 행 직접 — 매도 수수료 미보고 재현
            RiskCheckResult g0 = gate("DYNAMIC");
            check("X1 기점: 다른 DYNAMIC 세션 수수료 NULL → 전역 판정 불가", !g0.isApproved() && undet(g0.getReason()), verdict(g0));
            Long sx = newLimitedDynamic();
            String before = residue(sx);
            Mockito.clearInvocations(telegram);
            markPriceBook.record("KRW-RND", new BigDecimal("300"), Instant.now());
            String rBtc = dynBuy(sx, "KRW-BTC");
            check("X1 제외 코인 KRW-BTC → A 제외 사유(판정 불가보다 먼저)", rBtc != null && !undet(rBtc) && rBtc.contains("KRW-BTC"), rBtc);
            String rRnd = dynBuy(sx, "KRW-RND");
            check("X1 일반 코인 → C 판정 불가 거절", rRnd != null && undet(rRnd), rRnd);
            String after = residue(sx);
            check("X1 두 거절 뒤 포지션·주문·예약·잔액 변화 0", before.equals(after) && after.startsWith("pos=0 ord=0 reserved=0"), before + " → " + after);
            check("X1 거절 알림 이벤트 0", alertEvents().isEmpty(), alertEvents());

            // ── X2 확정 비용 복구 → 상한 매수·종류#ID 알림 ──
            jdbc.update("UPDATE \"order\" SET applied_paid_fee = 3.75 WHERE id = ?", t0[1]);   // 행 직접 — 확정 수수료 반영 재현
            RiskCheckResult g0r = gate("DYNAMIC");
            check("X2 복구 → 판정 불가 해제(50% 한도라 승인)", g0r.isApproved(), verdict(g0r));
            Mockito.clearInvocations(telegram);
            String rOk = dynBuy(sx, "KRW-RND");
            check("X2 복구 뒤 매수 진행", rOk == null, rOk);
            List<OrderEntity> bx = orders("DYNAMIC", sx, "BUY");
            check("X2 요청액 = maxOrderKrw 6000(A 상한)", bx.size() == 1 && bx.get(0).getQuantity().compareTo(new BigDecimal("6000")) == 0,
                    bx.isEmpty() ? "없음" : bx.get(0).getQuantity());
            awaitSubmitted(bx.get(0).getId());
            poll(bx.get(0).getId(), resp("bid", "done", "19.99", "5997", "2.9985"));
            // 관측(단언 아님): 공유 풀 REAL 매수 체결은 원자적 정산 경로(observeAndApplyBuyFill)로 반영되며 processFilledOrder 를 거치지 않아
            // BUY 체결 알림 버퍼 호출이 없다 — V83(2026-10-12)부터의 기존 동작이며 0e1d2a02·B 단독에도 같다. 별도 발견으로 보고한다.
            out.put("obs_real_buy_fill_alert_events", alertEvents().size());
            PositionEntity px = openPos("DYNAMIC", sx);
            check("X2 상한 매수 체결 반영(포지션 19.99)", px.getSize().compareTo(new BigDecimal("19.99")) == 0, px.getSize());
            Mockito.clearInvocations(telegram);
            Long sxSell = sellSubmit("DYNAMIC", sx);
            poll(sxSell, resp("ask", "done", "19.99", "6010", "3.005"));
            reconcile("DYNAMIC");
            List<List<Object>> ev = alertEvents();
            boolean labelOk = ev.stream().anyMatch(a -> ("DYNAMIC#" + sx).equals(a.get(0)) && "KRW-RND".equals(a.get(1)) && "SELL".equals(a.get(2)));
            boolean noReason = ev.stream().noneMatch(a -> String.valueOf(a).contains("판정 불가") || String.valueOf(a).contains("제외"));
            check("X2 청산 알림 표시 DYNAMIC#" + sx + "(B)·거절 사유 문구 없음", labelOk && noReason, ev);
            ageAll();

            // ── X3 제한 세션의 예약 거절 슬롯 ──
            Long sy = newLimitedDynamic();
            String yBefore = residue(sy);
            setRisk("0.001", 1);   // 사전 등록과 다른 한도 → 공유 풀 예약 RISK_CONFIG_MISMATCH 거절(제품 경로)
            Mockito.clearInvocations(telegram);
            markPriceBook.record("KRW-RND", new BigDecimal("300"), Instant.now());
            String rRej = dynBuy(sy, "KRW-RND");
            check("X3 제한 세션 예약 거절", rRej != null && rRej.contains("예약 거절"), rRej);
            List<PositionEntity> py = positionRepository.findBySessionKindAndSessionId("DYNAMIC", sy);
            check("X3 슬롯 CLOSED·ENTRY_REJECTED", py.size() == 1 && "CLOSED".equals(py.get(0).getStatus())
                    && py.get(0).getExitReason() == ExitReason.ENTRY_REJECTED, py.isEmpty() ? "없음" : py.get(0).getStatus() + "/" + py.get(0).getExitReason());
            String yAfter = residue(sy);
            check("X3 주문 0·예약 0·잔액 그대로", yAfter.equals(yBefore.replace("pos=0", "pos=1")) && yAfter.contains("ord=0 reserved=0"),
                    yBefore + " → " + yAfter);
            RiskCheckResult gy = gate("DYNAMIC");
            check("X3 관문 판정 불가 아님", !undet(gy.getReason()), verdict(gy));
            Map<String, Object> by = breaker("DYNAMIC", sy);
            check("X3 연속 손실 판정 불가 아님·미발동", Boolean.FALSE.equals(by.get("triggered")) && !undet(by.get("reason")), by);
            check("X3 진입 차단(비용) 없음", entryBlock("DYNAMIC", sy) == null, entryBlock("DYNAMIC", sy));
            boolean killRow = false;
            for (Object[] row : positionRepository.aggregateClosedTradesPerSession()) {
                if ("DYNAMIC".equals(String.valueOf(row[0])) && String.valueOf(sy).equals(String.valueOf(row[1]))) killRow = true;
            }
            check("X3 킬 기준 거래 0(행 없음)", !killRow, killRow);
            check("X3 예약 거절 알림 이벤트 0", alertEvents().isEmpty(), alertEvents());
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
                Files.writeString(Path.of(dir, "abc_cross_checks.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
            }
            System.out.println("[A+B+C 교차] " + objectMapper.writeValueAsString(out));
        }
        assertThat(failures).as("A+B+C 교차 기대와 다른 항목").isEmpty();
    }
}
