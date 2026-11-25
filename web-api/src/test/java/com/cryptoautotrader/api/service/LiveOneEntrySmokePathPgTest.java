package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 총괄 v1.243 §3-2 — 1회 제한 B 후보(c43e1f1a)의 <b>실제 LIVE 서비스 경로</b>와 종료·복구 게이트 v3 합성 데이터 (격리 PG).
 *
 * <p>① 생성·시작·정지는 HTTP 컨트롤러(MockMvc, API 토큰)로 부른다. 매수·매도는 제품의 {@code executeSessionBuy}·
 * {@code executeSessionSell} 을 그대로 부르고(신호 평가는 건너뛴다), 거래소 응답만 대역이다. 직접 예약 메서드만 부른 기존
 * {@code LiveOneEntryLimitPgTest} 와 다르다. ② 게이트 합성 세션을 만들어 목록 파일에 적는다 — 판정은 실행 틀이 실제
 * {@code recovery-gate-v3.sql}·{@code gate_check.py} 로 한다. 시험 데이터는 지우지 않는다(회차마다 새 DB).</p>
 */
@TestPropertySource(properties = {
        "spring.datasource.url=${OE2_PG_URL}",
        "spring.datasource.username=${OE2_PG_USER:trader}",
        "spring.datasource.password=${OE2_PG_PASSWORD:}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never"
})
@EnabledIfEnvironmentVariable(named = "OE2_PG_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):\\d+/.+")
@DisplayName("1회 제한 B — 실제 LIVE 경로(생성·시작·매수·매도·재진입 거절·정지·재시작 거절) + 게이트 v3 합성 (격리 PG)")
class LiveOneEntrySmokePathPgTest extends IntegrationTestBase {

    private static final String API = "/api/v1/trading/sessions";
    private static final String BODY = "{\"strategyType\":\"DAILY_SMA20\",\"coinPair\":\"KRW-BTC\",\"timeframe\":\"H1\","
            + "\"initialCapital\":10000,\"investRatio\":0.995,\"maxInvestment\":9950,\"stopLossPct\":5.0,"
            + "\"maxHoldHours\":0,\"strategyParams\":{\"maxEntries\":1}}";

    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private RulesetRegistry rulesetRegistry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private PoolLossGuard poolLossGuard;
    @Autowired private MarkPriceBook markPriceBook;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("orderExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor orderExecutor;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitRestClient upbitRestClient;
    @MockBean private com.cryptoautotrader.exchange.upbit.UpbitWebSocketClient upbitWebSocketClient;
    @MockBean private PortfolioSyncService portfolioSyncService;
    @MockBean private TelegramNotificationService telegramService;

    private String savedStage;
    private BigDecimal savedTotal;
    private BigDecimal savedPer;
    private Integer savedSlots;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).as("실제 PostgreSQL").startsWith("PostgreSQL");
        savedStage = limits.getStage();
        savedTotal = limits.getTotalCapKrw();
        savedPer = limits.getPerPositionCapKrw();
        savedSlots = limits.getMaxConcurrentPositions();
        limits.setStage("OE2_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions(3);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(new BigDecimal("100000"));
        poolLossGuard.setEnabledSetting(false);   // 손실 한도는 이 시험의 대상이 아니다
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("oe2-" + UUID.randomUUID());
                    r.setSide(inv.getArgument(1));
                    r.setOrdType(inv.getArgument(4));
                    r.setState("wait");
                    r.setMarket(inv.getArgument(0));
                    return new UpbitOrderClient.ExchangeResult(r, "{}");
                });
    }

    @AfterEach
    void tearDown() {
        limits.setStage(savedStage);
        limits.setTotalCapKrw(savedTotal);
        limits.setPerPositionCapKrw(savedPer);
        limits.setMaxConcurrentPositions(savedSlots);
    }

    // -- 공통 ------------------------------------------------------------

    private static void mark(String line) {
        System.out.println("[OE2] " + line);
    }

    private static void manifest(String name, Long sid, String expected) throws Exception {
        String path = System.getenv("OE2_MANIFEST");
        assertThat(path).as("OE2_MANIFEST").isNotBlank();
        Files.writeString(Path.of(path), name + " " + sid + " " + expected + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private JsonNode call(String url) throws Exception {
        MvcResult r = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        mark("POST " + url + " → HTTP " + r.getResponse().getStatus() + " " + body);
        return objectMapper.readTree(body.isEmpty() ? "{}" : body);
    }

    private OrderResponse resp(String side, String state, String vol, String funds, String fee) {
        OrderResponse r = new OrderResponse();
        r.setSide(side);
        r.setOrdType("bid".equals(side) ? "price" : "market");
        r.setState(state);
        r.setMarket("KRW-BTC");
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

    private static List<com.cryptoautotrader.strategy.Candle> candles(BigDecimal base) {
        List<com.cryptoautotrader.strategy.Candle> c = new ArrayList<>();
        Instant t0 = Instant.now().minus(300, ChronoUnit.HOURS);
        for (int i = 0; i < 300; i++) {
            BigDecimal p = base.add(new BigDecimal(i % 7 * 1000));
            c.add(com.cryptoautotrader.strategy.Candle.builder().time(t0.plus(i, ChronoUnit.HOURS))
                    .open(p).high(p.add(new BigDecimal("5000"))).low(p.subtract(new BigDecimal("5000"))).close(p)
                    .volume(new BigDecimal("10")).build());
        }
        return c;
    }

    private String buy(Long sid, BigDecimal price) {
        markPriceBook.record("KRW-BTC", price, Instant.now());
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        return ReflectionTestUtils.invokeMethod(target, "executeSessionBuy",
                liveRepo.findById(sid).orElseThrow(), "KRW-BTC", price, "1회 제한 실제 경로 시험 매수",
                null, null, candles(price), null);
    }

    private List<OrderEntity> orders(Long sid, String side) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", sid).stream()
                .filter(x -> side.equalsIgnoreCase(x.getSide())).toList();
    }

    private long bidSubmissions() {
        return Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("createOrder") && "bid".equals(i.getArgument(1))).count();
    }

    private BigDecimal available(Long sid) {
        return jdbc.queryForObject("SELECT available_krw FROM live_trading_session WHERE id = ?", BigDecimal.class, sid);
    }

    // -- ① 실제 LIVE 경로 -------------------------------------------------

    @Test
    @DisplayName("① 생성(HTTP)·저장 재조회·시작 → 매수 의도·체결 → 매도·청산 → 다음 매수 ENTRY_LIMIT_REACHED → 정지 → 재시작 거절")
    void productPath() throws Exception {
        // 생성 — 응답 형태·저장값
        MvcResult cr = mockMvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn();
        String cbody = cr.getResponse().getContentAsString(StandardCharsets.UTF_8);
        mark("create HTTP " + cr.getResponse().getStatus() + " " + cbody);
        assertThat(cr.getResponse().getStatus()).as("생성 HTTP").isEqualTo(200);
        JsonNode c = objectMapper.readTree(cbody);
        assertThat(c.has("success") && c.has("data")).as("ApiResponse 형태").isTrue();
        assertThat(c.get("success").asBoolean()).isTrue();
        JsonNode d = c.get("data");
        Long sid = d.get("id").asLong();
        assertThat(d.get("status").asText()).isEqualTo("CREATED");
        assertThat(d.get("sessionType").asText()).isEqualTo("REAL");
        JsonNode me = d.get("strategyParams").get("maxEntries");
        assertThat(me.isIntegralNumber() && me.asInt() == 1).as("응답 maxEntries 정수 1: " + me).isTrue();

        // 새 트랜잭션 재조회(JdbcTemplate 자동 커밋 — 앱 재기동 증거가 아니다)
        Map<String, Object> row = jdbc.queryForMap("SELECT status, session_type, jsonb_typeof(strategy_params->'maxEntries') AS t, "
                + "strategy_params->>'maxEntries' AS v, initial_capital, invest_ratio, max_investment, started_at FROM live_trading_session WHERE id = ?", sid);
        mark("db " + row);
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(row.get("session_type")).isEqualTo("REAL");
        assertThat(row.get("t")).isEqualTo("number");
        assertThat(row.get("v")).isEqualTo("1");
        assertThat((BigDecimal) row.get("initial_capital")).isEqualByComparingTo("10000");
        assertThat((BigDecimal) row.get("invest_ratio")).isEqualByComparingTo("0.995");
        assertThat((BigDecimal) row.get("max_investment")).isEqualByComparingTo("9950");
        assertThat(row.get("started_at")).isNull();
        String h1 = rulesetRegistry.hashFor(liveRepo.findById(sid).orElseThrow());
        String h2 = rulesetRegistry.hashFor(liveRepo.findById(sid).orElseThrow());
        Map<String, Object> noOpt = new HashMap<>(liveRepo.findById(sid).orElseThrow().getStrategyParams());
        noOpt.remove("maxEntries");
        mark("hash " + h1 + " 재조회 " + h2 + " canonical(opt)=" + RulesetRegistry.canonicalParams(liveRepo.findById(sid).orElseThrow().getStrategyParams())
                + " canonical(noOpt)=" + RulesetRegistry.canonicalParams(noOpt));
        assertThat(h2).as("지문 재조회 일치").isEqualTo(h1);

        // 시작(HTTP)
        JsonNode s = call(API + "/" + sid + "/start");
        assertThat(s.path("success").asBoolean()).as("시작 성공").isTrue();
        assertThat(liveRepo.findById(sid).orElseThrow().getStatus()).isEqualTo("RUNNING");
        Instant startedAt = liveRepo.findById(sid).orElseThrow().getStartedAt();

        // 매수 — 제품 executeSessionBuy, 거래소 응답만 대역
        BigDecimal p1 = new BigDecimal("50000000");
        String r1 = buy(sid, p1);
        mark("buy1 result=" + r1);
        assertThat(r1).as("첫 매수 경로").isNull();
        List<OrderEntity> buys = orders(sid, "BUY");
        assertThat(buys).hasSize(1);
        Long buyId = buys.get(0).getId();
        awaitSubmitted(buyId);
        assertThat(bidSubmissions()).as("외부 매수 제출").isEqualTo(1);
        poll(buyId, resp("bid", "done", "0.000199", "9950", "4.975"));
        OrderEntity b = orderRepository.findById(buyId).orElseThrow();
        PositionEntity pos = positionRepository.findById(b.getPositionId()).orElseThrow();
        BigDecimal avAfterBuy = available(sid);
        mark("buy1 state=" + b.getState() + " pos=" + pos.getStatus() + "/" + pos.getSize() + " available=" + avAfterBuy
                + " applied=" + b.getAppliedExecutedFunds() + "/" + b.getAppliedPaidFee() + " fillPending=" + b.getFillAccountingPending());
        assertThat(b.getState()).isEqualTo("FILLED");
        assertThat(pos.getSize()).isEqualByComparingTo("0.000199");
        // 원시 대금·수수료는 8자리 그대로 보존된다(두 자리로 낮추지 않는다).
        assertThat(b.getAppliedExecutedFunds()).as("매수 반영 원시 대금").isEqualByComparingTo("9950");
        assertThat(b.getAppliedPaidFee()).as("매수 반영 원시 수수료").isEqualByComparingTo("4.975");
        // 잔액은 주문별로 두 자리 HALF_UP 반영: 미사용액 9950 − 9950 − 4.975 = −4.975 → −4.98, 10000 − 9950 − 4.98 = 45.02 (독립 계산)
        assertThat(avAfterBuy).as("가용액 = 10000 − 요청 9950 + 두 자리 반영 미사용액(−4.975→−4.98) = 45.02").isEqualByComparingTo("45.02");

        // 매도 — 제품 executeSessionSell → 체결 → 청산 확정
        Object target = AopTestUtils.getUltimateTargetObject(liveTradingService);
        Boolean sold = ReflectionTestUtils.invokeMethod(target, "executeSessionSell",
                liveRepo.findById(sid).orElseThrow(), positionRepository.findById(pos.getId()).orElseThrow(),
                new BigDecimal("50500000"), "1회 제한 실제 경로 시험 매도", ExitReason.TAKE_PROFIT);
        assertThat(sold).as("매도 경로").isTrue();
        Long sellId = orders(sid, "SELL").get(0).getId();
        awaitSubmitted(sellId);
        poll(sellId, resp("ask", "done", "0.000199", "10049.5", "5.02475"));
        liveTradingService.reconcileClosingPositions();
        PositionEntity closed = positionRepository.findById(pos.getId()).orElseThrow();
        OrderEntity sell = orderRepository.findById(sellId).orElseThrow();
        BigDecimal avAfterSell = available(sid);
        mark("sell state=" + sell.getState() + " applied=" + sell.getSellAppliedAt() + " pos=" + closed.getStatus() + "/"
                + closed.getSize() + " exit=" + closed.getExitReason() + " available=" + avAfterSell);
        assertThat(sell.getState()).isEqualTo("FILLED");
        assertThat(sell.getSellAppliedAt()).as("매도 반영 표시").isNotNull();
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        // 매도 순입금도 주문별 두 자리 HALF_UP 반영: 10049.5 − 5.02475 = 10044.47525 → 10044.48, 45.02 + 10044.48 = 10089.50 (독립 계산)
        assertThat(avAfterSell).as("매도 뒤 가용액 = 45.02 + 두 자리 반영 순입금(10044.47525→10044.48) = 10089.50").isEqualByComparingTo("10089.50");

        // 다음 매수 시도 — 제품 경로에서 거절, 추가 차감·외부 제출 0
        String r2 = buy(sid, new BigDecimal("49000000"));
        mark("buy2 result=" + r2);
        assertThat(r2).as("재진입 거절").contains("ENTRY_LIMIT_REACHED");
        assertThat(orders(sid, "BUY")).as("BUY 의도 1").hasSize(1);
        assertThat(bidSubmissions()).as("외부 매수 제출 그대로 1").isEqualTo(1);
        assertThat(available(sid)).as("추가 차감 0").isEqualByComparingTo(avAfterSell);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM position WHERE session_kind='LIVE' AND session_id=? AND status <> 'CLOSED'",
                Long.class, sid)).as("거절 슬롯 롤백").isZero();

        // 정지(HTTP) → 재시작 거절(제한시험 전략은 최초 시작만)
        JsonNode st = call(API + "/" + sid + "/stop");
        assertThat(st.path("success").asBoolean()).as("정지 성공").isTrue();
        assertThat(liveRepo.findById(sid).orElseThrow().getStatus()).isEqualTo("STOPPED");
        JsonNode again = call(API + "/" + sid + "/start");
        LiveTradingSessionEntity after = liveRepo.findById(sid).orElseThrow();
        mark("restart success=" + again.path("success").asText() + " status=" + after.getStatus() + " startedAt=" + after.getStartedAt());
        assertThat(again.path("success").asBoolean()).as("정지 뒤 재시작 거절").isFalse();
        assertThat(after.getStatus()).isEqualTo("STOPPED");
        assertThat(after.getStartedAt()).as("기산점 불변").isEqualTo(startedAt);
        manifest("p_product_path", sid, "PASS");
    }

    // -- ② 게이트 v3 합성 ----------------------------------------------------

    private Long session(String status, String type, Object maxEntries, boolean withKey) {
        Map<String, Object> params = new HashMap<>();
        if (withKey) params.put("maxEntries", maxEntries);
        BigDecimal cap = new BigDecimal("10000");
        return liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType(type).strategyType("DAILY_SMA20").coinPair("KRW-BTC").timeframe("H1")
                .initialCapital(cap).availableKrw(cap).totalAssetKrw(cap)
                .investRatio(new BigDecimal("0.995")).maxInvestment(new BigDecimal("9950"))
                .stopLossPct(new BigDecimal("5.00")).maxHoldHours(0).strategyParams(params)
                .status(status).startedAt(Instant.now().minusSeconds(3600)).stoppedAt(Instant.now().minusSeconds(60)).build()).getId();
    }

    private Long stopped() {
        return session("STOPPED", "REAL", 1, true);
    }

    private Long position(Long sid, String status, String size) {
        return positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY").entryPrice(new BigDecimal("50000000")).avgPrice(new BigDecimal("50000000"))
                .size(new BigDecimal(size)).realizedPnl(BigDecimal.ZERO).status(status)
                .sessionKind("LIVE").sessionId(sid).openedAt(Instant.now().minusSeconds(3000))
                .closedAt("CLOSED".equals(status) ? Instant.now().minusSeconds(120) : null).build()).getId();
    }

    private Long order(Long sid, Long posId, String side, String state) {
        OrderEntity o = new OrderEntity();
        o.setCoinPair("KRW-BTC"); o.setSide(side); o.setOrderType("MARKET"); o.setQuantity(new BigDecimal("9950"));
        o.setState(state); o.setSessionKind("LIVE"); o.setSessionId(sid); o.setPositionId(posId);
        Long id = orderRepository.saveAndFlush(o).getId();
        jdbc.update("UPDATE \"order\" SET exchange_order_id = ?, fee_source = 'REPORTED' WHERE id = ?", "g-" + UUID.randomUUID(), id);
        return id;
    }

    /** 정상 한 왕복: 매수 FILLED·매도 FILLED(반영 표시)·포지션 CLOSED·정리 완료 이력. settlement_final 은 제품처럼 FALSE 그대로. */
    private Long[] normalTrip(Long sid) {
        Long pos = position(sid, "CLOSED", "0");
        Long b = order(sid, pos, "BUY", "FILLED");
        Long s = order(sid, pos, "SELL", "FILLED");
        jdbc.update("UPDATE \"order\" SET sell_applied_at = now() WHERE id = ?", s);
        jdbc.update("INSERT INTO position_cleanup (position_id, session_kind, session_id, reason, pending_at, done_at, attempts, last_note) "
                + "VALUES (?, 'LIVE', ?, 'STOP', now() - interval '100 seconds', now() - interval '90 seconds', 1, '시험 — CLOSED 확인')", pos, sid);
        return new Long[]{pos, b, s};
    }

    @Test
    @DisplayName("② 게이트 v3 합성 세션 — 정상/완료 이력·무거래 근거는 PASS, 미완료·활성·회계 대기·잔량·미입증·비상정지·대상 불일치는 FAIL/HOLD")
    void gateFixtures() throws Exception {
        Long id;
        id = stopped(); normalTrip(id); manifest("g_normal_done_cleanup", id, "PASS");

        id = stopped(); { Long pos = position(id, "CLOSED", "0"); order(id, pos, "BUY", "FILLED");
            Long s = order(id, pos, "SELL", "FILLED"); jdbc.update("UPDATE \"order\" SET sell_applied_at = now() WHERE id = ?", s);
            jdbc.update("INSERT INTO position_cleanup (position_id, session_kind, session_id, reason, pending_at, attempts) "
                    + "VALUES (?, 'LIVE', ?, 'STOP', now(), 0)", pos, id); }
        manifest("g_cleanup_pending", id, "FAIL");

        id = stopped(); { Long[] t = normalTrip(id); order(id, t[0], "SELL", "SUBMITTED"); } manifest("g_active_order", id, "FAIL");

        id = stopped(); { Long[] t = normalTrip(id); jdbc.update("UPDATE \"order\" SET fill_accounting_pending = TRUE WHERE id = ?", t[1]); }
        manifest("g_fill_accounting_pending", id, "FAIL");

        id = stopped(); { Long[] t = normalTrip(id); jdbc.update("UPDATE \"order\" SET sell_applied_at = NULL WHERE id = ?", t[2]); }
        manifest("g_sell_unapplied", id, "FAIL");

        id = stopped(); { Long[] t = normalTrip(id); jdbc.update("UPDATE position SET size = 0.00001 WHERE id = ?", t[0]); }
        manifest("g_residual_size", id, "FAIL");

        id = stopped(); { Long pos = position(id, "OPEN", "0.000199"); order(id, pos, "BUY", "FILLED"); } manifest("g_open_position", id, "FAIL");

        id = stopped(); { Long pos = position(id, "CLOSED", "0"); order(id, pos, "BUY", "CANCELLED"); }
        manifest("g_cancel_unconfirmed", id, "HOLD");

        id = stopped(); { Long pos = position(id, "CLOSED", "0"); order(id, pos, "BUY", "FAILED"); }
        manifest("g_failed_code_null", id, "HOLD");

        id = stopped(); { Long pos = position(id, "CLOSED", "0"); Long o = order(id, pos, "BUY", "FAILED");
            jdbc.update("UPDATE \"order\" SET failure_code = 'SUBMIT_TIMEOUT_X' WHERE id = ?", o); }
        manifest("g_failed_code_unlisted", id, "HOLD");

        id = stopped(); { Long pos = position(id, "CLOSED", "0"); Long f = order(id, pos, "BUY", "FAILED");
            jdbc.update("UPDATE \"order\" SET failure_code = ? WHERE id = ?", OrderExecutionEngine.WITHDRAWN_STOPPED, f);
            Long c = order(id, pos, "SELL", "CANCELLED");
            jdbc.update("UPDATE \"order\" SET cancel_confirmed_at = now() WHERE id = ?", c); }
        manifest("g_proven_no_fill", id, "PASS");

        id = stopped(); { Long[] t = normalTrip(id); jdbc.update("INSERT INTO order_settlement_discrepancy (order_id, kind) VALUES (?, 'FEE_DECREASE')", t[1]); }
        manifest("g_discrepancy_open", id, "HOLD");

        id = stopped(); { Long[] t = normalTrip(id); jdbc.update("INSERT INTO order_settlement_discrepancy (order_id, kind, resolved_at, resolved_by) "
                + "VALUES (?, 'FEE_DECREASE', now(), 'test')", t[1]); }
        manifest("g_discrepancy_resolved", id, "PASS");

        id = session("EMERGENCY_STOPPED", "REAL", 1, true); normalTrip(id); manifest("g_emergency_stopped", id, "HOLD");
        id = session("RUNNING", "REAL", 1, true); normalTrip(id); manifest("g_running", id, "FAIL");
        id = session("STOPPED", "REAL", null, false); normalTrip(id); manifest("g_no_max_entries", id, "FAIL");
        id = session("STOPPED", "REAL", "1", true); normalTrip(id); manifest("g_max_entries_string", id, "FAIL");
        id = session("STOPPED", "REAL", 1.0d, true); normalTrip(id); manifest("g_max_entries_float", id, "FAIL");
        id = session("STOPPED", "PAPER", 1, true); normalTrip(id); manifest("g_not_real", id, "FAIL");

        id = stopped(); { Long[] t = normalTrip(id); Long pos2 = position(id, "CLOSED", "0"); order(id, pos2, "BUY", "FILLED");
            Long s2 = order(id, pos2, "SELL", "FILLED"); jdbc.update("UPDATE \"order\" SET sell_applied_at = now() WHERE id = ?", s2); }
        manifest("g_two_buys", id, "FAIL");

        id = stopped(); { Long[] t = normalTrip(id); jdbc.update("UPDATE \"order\" SET state = 'WEIRD' WHERE id = ?", t[1]); }
        manifest("g_unknown_order_state", id, "FAIL");

        id = stopped(); { Long[] t = normalTrip(id); jdbc.update("UPDATE position SET status = 'ODD' WHERE id = ?", t[0]); }
        manifest("g_unknown_position_status", id, "FAIL");
        mark("gate fixtures written");
    }
}
