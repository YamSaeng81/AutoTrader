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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>P7 격리 PG 매도 현금흐름 보충</b> (총괄 v1.189 §3-3) — 시험 전용, 제품 변경 없음. 수용된 매수 시험(P7CashflowPgTest)은 다시 돌리지 않는다.
 *
 * <p>0e1d2a02 실제 엔진 경로: 보유를 만들기 위한 매수 전체 체결 1회(실제 매수 경로·폴러) 뒤, <b>실제 매도 경로</b>
 * (LIVE {@code executeSessionSell}, DYNAMIC {@code executeSell} → {@code markClosingWithSellIntent} → 제출)로 매도를 내고,
 * 폴러 {@code checkOrderStatus} 로 부분 체결·체결 있는 취소를, 청산 확정 {@code reconcileClosingPositions}·
 * {@code reconcileDynamicClosingPositions} 로 잔량 유지를 만든 뒤, 같은 매도 경로로 잔량을 재청산한다. 늦은 수수료 정정·같은 응답 반복은
 * 정산 {@code settle}. <b>거래소 응답만 대역</b>이고 주문·포지션·잔액 행은 모두 엔진이 쓴다(세션 행 생성만 시험이 한다).</p>
 *
 * <p>스냅샷은 P7 v2 SQL 원문 + v5 시계 메타데이터(단계 시작/끝 벽시계, 단계 앞뒤 DB 시계 왕복 표본과 단조 시계 왕복)다.
 * 보충 기록으로 포지션 평균가·투입액·실현손익·수수료와 DYNAMIC 매도 정산 행을 따로 남긴다(성적표 실현손익 정의 확인용).</p>
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
@DisplayName("P7 격리 PG 매도 현금흐름 — 대조기 v5 입력 스냅샷")
class P7SellCashflowPgTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private OrderSettlementService settlement;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private DynamicTradingService dynamicTradingService;
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

    // ── 대역 거래소 장부(주문별 마지막 응답) ─────────────────────────────────
    private record Rep(String side, String market, String state, BigDecimal vol, BigDecimal funds, BigDecimal fee) {}

    private final Map<String, Rep> last = new LinkedHashMap<>();
    private static final BigDecimal KRW0 = new BigDecimal("1000000.00");
    private static final Map<String, BigDecimal> HOLD0 = Map.of("XRP", new BigDecimal("5"));

    private void report(OrderResponse r) {
        last.put(r.getUuid(), new Rep(r.getSide(), r.getMarket(), r.getState(), nz(r.getExecutedVolume()),
                nz(r.getExecutedFunds()), nz(r.getPaidFee())));
    }

    private Map<String, Object> exchangeView() {
        BigDecimal krw = KRW0;
        Map<String, BigDecimal> coins = new TreeMap<>(HOLD0);
        List<String> open = new ArrayList<>();
        List<String> done = new ArrayList<>();
        for (var e : last.entrySet()) {
            Rep r = e.getValue();
            String cur = r.market().split("-", 2)[1];
            if ("bid".equals(r.side())) {
                krw = krw.subtract(r.funds()).subtract(r.fee());
                coins.merge(cur, r.vol(), BigDecimal::add);
            } else {
                krw = krw.add(r.funds()).subtract(r.fee());
                coins.merge(cur, r.vol().negate(), BigDecimal::add);
            }
            ("wait".equals(r.state()) ? open : done).add(e.getKey());
        }
        Map<String, Object> acc = new TreeMap<>();
        acc.put("KRW", Map.of("balance", krw.toPlainString(), "locked", "0"));
        for (var c : coins.entrySet()) {
            if (c.getValue().signum() != 0) acc.put(c.getKey(), Map.of("balance", c.getValue().toPlainString(), "locked", "0"));
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("accounts", acc);
        v.put("open_orders", open);
        v.put("done_order_ids", done);
        return v;
    }

    // ── 스냅샷(P7 v2 SQL 원문 + v5 시계 메타데이터) ─────────────────────────
    private static String sqlBody(String file) throws Exception {
        String text = Files.readString(Path.of(System.getenv("P7_SQL_DIR"), file), StandardCharsets.UTF_8);
        String body = text.lines()
                .filter(l -> !l.startsWith("\\") && !l.startsWith("BEGIN") && !l.startsWith("COMMIT"))
                .collect(Collectors.joining("\n")).trim();
        return body.endsWith(";") ? body.substring(0, body.length() - 1) : body;
    }

    private static final String CLOCK_SQL = "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')";
    private final List<String> labels = new ArrayList<>();

    /** 같은 연결의 DB 시계 왕복 표본: 벽시계 c0 → DB clock_timestamp d → 벽시계 c1, 단조 시계 왕복(System.nanoTime)을 함께. */
    private Map<String, Object> sample(String step) {
        long n0 = System.nanoTime();
        Instant c0 = Instant.now();
        String d = jdbc.queryForObject(CLOCK_SQL, String.class);
        Instant c1 = Instant.now();
        long n1 = System.nanoTime();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pair", List.of("db", "collector"));
        m.put("step", step);
        m.put("c0", c0.toString());
        m.put("d", d);
        m.put("c1", c1.toString());
        m.put("mono_rtt_ms", String.format("%.4f", (n1 - n0) / 1e6));
        return m;
    }

    /** 단계 사이 간격 — 1차 실행(attempt1)에서 메모리 대역 거래소 읽기가 ~0ms 라 DB 추출과의 선후가 시계 오차 범위(≈1~2ms) 안에 들어가
     *  v5 가 순서를 확정하지 못했다. 수집기는 단계 사이를 측정 오차보다 넓게 띄운다(매수 시험과 같은 5ms). 판정 규칙은 바꾸지 않는다. */
    private static final long STEP_GAP_MS = 5;

    private <T> T timed(String step, List<Map<String, Object>> steps, Supplier<T> action) {
        try {
            Thread.sleep(STEP_GAP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        Instant start = Instant.now();
        T v = action.get();
        Instant end = Instant.now();
        try {
            Thread.sleep(STEP_GAP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step);
        m.put("start", start.toString());
        m.put("end", end.toString());
        steps.add(m);
        return v;
    }

    private void snap(String label) throws Exception {
        String fpSql = sqlBody("fingerprint.sql");
        String exSql = sqlBody("extract.sql");
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> samples = new ArrayList<>();
        samples.add(sample("db_fp_pre"));
        String fp1 = timed("db_fp_pre", steps, () -> jdbc.queryForObject(fpSql, String.class));
        samples.add(sample("exchange_before"));
        Map<String, Object> ex1 = timed("exchange_before", steps, this::exchangeView);
        samples.add(sample("db_extract"));
        String db = timed("db_extract", steps, () -> jdbc.queryForObject(exSql, String.class));
        samples.add(sample("exchange_after"));
        Map<String, Object> ex2 = timed("exchange_after", steps, this::exchangeView);
        samples.add(sample("db_fp_post"));
        String fp2 = timed("db_fp_post", steps, () -> jdbc.queryForObject(fpSql, String.class));
        samples.add(sample("db_fp_post"));   // 마지막 단계 뒤 표본 — 끝 시각과 DB 시계를 함께 닫는다
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("steps", steps);
        out.put("clock_samples", samples);
        out.put("db_fp_pre", fp1);
        out.put("exchange_before", ex1);
        out.put("db", objectMapper.readTree(db));
        out.put("exchange_after", ex2);
        out.put("db_fp_post", fp2);
        out.put("supplement", supplement());
        Path dir = Path.of(System.getenv("P7_OUT"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(label + ".json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n",
                StandardCharsets.UTF_8);
        labels.add(label);
        System.out.println("[P7 매도 PG] 스냅샷 " + label + " fp=" + fp1 + "/" + fp2);
    }

    /** 대조기 입력 밖의 보충 기록 — 포지션 평균가·투입액·실현손익·수수료, DYNAMIC 매도 정산 행, 주문 반영값. 판정에는 쓰지 않는다. */
    private Map<String, Object> supplement() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("positions", jdbc.queryForList("SELECT id, session_kind, session_id, coin_pair, status, size::text, avg_price::text, "
                + "invested_krw::text, realized_pnl::text, position_fee::text FROM position ORDER BY id"));
        m.put("dynamic_sell_settlement", jdbc.queryForList("SELECT order_ref, position_id, session_id, session_kind, sold_qty::text, "
                + "net_proceeds::text, realized_pnl::text FROM dynamic_sell_settlement ORDER BY settled_at"));
        m.put("orders", jdbc.queryForList("SELECT id, session_kind, session_id, side, state, filled_quantity::text, "
                + "applied_executed_funds::text, applied_paid_fee::text, reported_executed_funds::text, reported_paid_fee::text, "
                + "sell_applied_at IS NOT NULL AS sell_applied FROM \"order\" ORDER BY id"));
        m.put("live_sessions", jdbc.queryForList("SELECT id, available_krw::text FROM live_trading_session ORDER BY id"));
        m.put("dynamic_sessions", jdbc.queryForList("SELECT id, available_krw::text FROM dynamic_session ORDER BY id"));
        return m;
    }

    // ── 대역 응답·엔진 호출 ───────────────────────────────────────────────────
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
        report(r);
        Mockito.doReturn(r).when(upbitOrderClient).getOrder(uuid);
        orderExecutionEngine.checkOrderStatus(orderId);
    }

    private OrderSettlementService.Outcome settle(Long orderId, OrderResponse r) {
        r.setUuid(orderRepository.findById(orderId).orElseThrow().getExchangeOrderId());
        report(r);
        return settlement.settle(orderId, r);
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
        List<com.cryptoautotrader.strategy.Candle> out = new ArrayList<>();
        Instant t0 = Instant.now().minus(300, java.time.temporal.ChronoUnit.HOURS);
        for (int i = 0; i < 300; i++) {
            BigDecimal p = new BigDecimal(300 + i % 7);
            out.add(com.cryptoautotrader.strategy.Candle.builder().time(t0.plus(i, java.time.temporal.ChronoUnit.HOURS))
                    .open(p).high(p.add(BigDecimal.ONE)).low(p.subtract(BigDecimal.ONE)).close(p)
                    .volume(new BigDecimal("1000")).build());
        }
        return out;
    }

    private BigDecimal available(String kind, Long sid) {
        return "LIVE".equals(kind) ? liveRepo.findById(sid).orElseThrow().getAvailableKrw()
                : dynamicRepo.findById(sid).orElseThrow().getAvailableKrw();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private PositionEntity position(String kind, Long sid) {
        return positionRepository.findBySessionKindAndSessionId(kind, sid).stream().findFirst().orElseThrow();
    }

    private List<OrderEntity> orders(String kind, Long sid, String side) {
        return orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, sid).stream()
                .filter(x -> side.equals(x.getSide())).toList();
    }

    /** 실제 매도 경로로 매도 의도·제출. 반환: 새 매도 주문 id. */
    private Long sell(String kind, Long sid, int before) throws Exception {
        PositionEntity pos = position(kind, sid);
        boolean submitted;
        if ("LIVE".equals(kind)) {
            Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(liveTradingService);
            submitted = Boolean.TRUE.equals(org.springframework.test.util.ReflectionTestUtils.invokeMethod(target, "executeSessionSell",
                    liveRepo.findById(sid).orElseThrow(), pos, new BigDecimal("310"), "P7 매도 PG", ExitReason.TAKE_PROFIT));
        } else {
            submitted = dynamicTradingService.executeSell(dynamicRepo.findById(sid).orElseThrow(), pos, new BigDecimal("310"),
                    "P7 매도 PG", ExitReason.TAKE_PROFIT);
        }
        assertThat(submitted).as("%s 실제 매도 경로 — CLOSING 전환·매도 의도", kind).isTrue();
        List<OrderEntity> sells = orders(kind, sid, "SELL");
        assertThat(sells).hasSize(before + 1);
        Long id = sells.get(0).getId();
        awaitSubmitted(id);
        return id;
    }

    private void reconcileClosing(String kind) {
        if ("LIVE".equals(kind)) liveTradingService.reconcileClosingPositions();
        else dynamicTradingService.reconcileDynamicClosingPositions();
    }

    /** 한 종류의 흐름 — 보유 만들기(매수 전체 체결) → 매도 제출 → 부분 체결 → 체결 있는 취소(잔량 유지) → 재청산 → 수수료 정정 → 같은 응답 반복. */
    private void flow(String p, String kind) throws Exception {
        Long sid;
        BigDecimal init;
        if ("LIVE".equals(kind)) {
            init = new BigDecimal("16240.37");
            sid = liveRepo.save(LiveTradingSessionEntity.builder()
                    .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair("KRW-RND").timeframe("H1")
                    .initialCapital(init).availableKrw(init).totalAssetKrw(init)
                    .investRatio(new BigDecimal("0.995")).maxInvestment(new BigDecimal("20000"))
                    .stopLossPct(new BigDecimal("5.00")).maxHoldHours(0)
                    .status("RUNNING").startedAt(Instant.now()).build()).getId();
        } else {
            init = new BigDecimal("10000.37");
            sid = dynamicRepo.saveAndFlush(DynamicSessionEntity.builder()
                    .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                    .initialCapital(init).availableKrw(init).totalAssetKrw(init)
                    .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                    .status("RUNNING").scanState("SCANNING").tradingMode("REAL")
                    .maxCandidateSize(30).targetWatchSize(10)
                    .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                    .watchlistRefreshMin(60).build()).getId();
        }
        markPriceBook.record("KRW-RND", new BigDecimal("300"), Instant.now());

        // 보유 만들기 — 실제 매수 경로 + 폴러 전체 체결 1회(수용된 매수 시험의 부분 체결·취소·정정은 반복하지 않음)
        String result;
        if ("LIVE".equals(kind)) {
            Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(liveTradingService);
            result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(target, "executeSessionBuy",
                    liveRepo.findById(sid).orElseThrow(), "KRW-RND", new BigDecimal("300"), "P7 매도 PG 보유",
                    null, null, candles(), null);
        } else {
            Long dsid = sid;
            result = new org.springframework.transaction.support.TransactionTemplate(txManager).execute(st ->
                    dynamicTradingService.executeBuy(dynamicRepo.findById(dsid).orElseThrow(), "KRW-RND", candles(),
                            com.cryptoautotrader.strategy.StrategySignal.buy(new BigDecimal("80"), "P7 매도 PG 보유"), BigDecimal.ONE));
        }
        assertThat(result).as("%s 매수 경로(null = 예약·제출)", kind).isNull();
        Long buyId = orders(kind, sid, "BUY").get(0).getId();
        awaitSubmitted(buyId);
        poll(buyId, resp("bid", "done", "25", "7500", "3.75"));
        assertThat(position(kind, sid).getSize()).isEqualByComparingTo("25");
        snap(p + "1-holding");

        // 매도 제출(CLOSING·진행 중)
        Long s1 = sell(kind, sid, 0);
        assertThat(position(kind, sid).getStatus()).isEqualTo("CLOSING");
        snap(p + "2-sell-submitted");

        // 부분 체결(활성)
        poll(s1, resp("ask", "wait", "10", "3100", "1.55"));
        reconcileClosing(kind);
        snap(p + "3-sell-partial");

        // 체결 있는 취소 → 청산 확정이 판 수량만 반영하고 잔량 15 를 OPEN 으로 유지
        poll(s1, resp("ask", "cancel", "10", "3100", "1.55"));
        reconcileClosing(kind);
        BigDecimal avail4 = available(kind, sid);
        PositionEntity pos4 = position(kind, sid);
        System.out.println("[P7 매도 PG] " + kind + " 부분 매도 취소 뒤 pos=" + pos4.getStatus() + " size=" + pos4.getSize()
                + " realized=" + pos4.getRealizedPnl() + " fee=" + pos4.getPositionFee() + " avg=" + pos4.getAvgPrice()
                + " available=" + avail4 + " sellState=" + orderRepository.findById(s1).orElseThrow().getState());
        assertThat(pos4.getStatus()).isEqualTo("OPEN");
        assertThat(pos4.getSize()).isEqualByComparingTo("15");
        snap(p + "4-sell-cancel-partial");

        // 잔량 재청산 — 같은 실제 매도 경로
        Long s2 = sell(kind, sid, 1);
        poll(s2, resp("ask", "done", "15", "4650", "2.325"));
        reconcileClosing(kind);
        PositionEntity pos5 = position(kind, sid);
        System.out.println("[P7 매도 PG] " + kind + " 재청산 뒤 pos=" + pos5.getStatus() + " realized=" + pos5.getRealizedPnl()
                + " fee=" + pos5.getPositionFee() + " available=" + available(kind, sid));
        assertThat(pos5.getStatus()).isEqualTo("CLOSED");
        snap(p + "5-reliquidated");

        // 늦은 매도 수수료 정정 2회(+0.003씩)
        List<String> outs = new ArrayList<>();
        BigDecimal fee = new BigDecimal("2.325");
        for (int i = 0; i < 2; i++) {
            fee = fee.add(new BigDecimal("0.003"));
            outs.add(settle(s2, resp("ask", "done", "15", "4650", fee.toPlainString())).name());
        }
        System.out.println("[P7 매도 PG] " + kind + " 매도 정정 2회 결과 " + outs + " available=" + available(kind, sid));
        snap(p + "6-sell-corrected");

        String again = settle(s2, resp("ask", "done", "15", "4650", fee.toPlainString())).name();
        System.out.println("[P7 매도 PG] " + kind + " 같은 응답 반복 결과 " + again + " available=" + available(kind, sid));
        snap(p + "7-repeated");
    }

    @Test
    @DisplayName("LIVE·DYNAMIC 실자금 매도 현금흐름 스냅샷 — 매도 제출·부분 체결·체결 있는 취소(잔량 유지)·재청산·수수료 정정·반복")
    void sellCashflowSnapshots() throws Exception {
        String stage = limits.getStage();
        BigDecimal total = limits.getTotalCapKrw();
        BigDecimal per = limits.getPerPositionCapKrw();
        Integer slots = limits.getMaxConcurrentPositions();
        limits.setStage("P7S_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        poolLossGuard.setEnabledSetting(true);
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("p7s-" + UUID.randomUUID());
                    r.setSide(inv.getArgument(1));
                    r.setOrdType(inv.getArgument(4));
                    r.setState("wait");
                    r.setMarket(inv.getArgument(0));
                    report(r);
                    return new UpbitOrderClient.ExchangeResult(r, "{}");
                });
        try {
            snap("00-start");
            flow("L", "LIVE");
            flow("D", "DYNAMIC");
        } finally {
            limits.setStage(stage);
            limits.setTotalCapKrw(total);
            limits.setPerPositionCapKrw(per);
            limits.setMaxConcurrentPositions(slots);
        }
        assertThat(labels).hasSize(15);
    }
}
