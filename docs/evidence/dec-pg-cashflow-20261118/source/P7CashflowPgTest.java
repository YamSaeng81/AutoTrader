package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>P7 격리 PG 실제 현금흐름 대조 입력 만들기</b> (총괄 v1.188 §3-1, v1.187 §3-2) — 시험 전용, 제품 변경 없음.
 *
 * <p>운영 반영본 0e1d2a02 의 실제 엔진 경로로 실자금 매수 한 건의 현금흐름을 LIVE·DYNAMIC 각각 만든다.
 * 예약·제출은 실제 매수 경로(LIVE {@code executeSessionBuy}, DYNAMIC {@code executeBuy}), 체결·취소는 폴러 진입점
 * {@code checkOrderStatus}, 늦은 수수료 정정·같은 응답 반복은 정산 {@code settle}. <b>거래소 응답만 대역</b>이며
 * PG 행은 모두 엔진이 쓴다(세션 행 생성만 시험이 한다 — 새 배정 경계).</p>
 *
 * <p>시점마다 P7 v2 {@code fingerprint.sql}·{@code extract.sql} 원문을 그대로 실행해 DB 스냅샷을, 대역 거래소가 지금까지 돌려준
 * 마지막 응답으로 계산한 계좌·미체결·완료 목록을 함께 {@code P7_OUT} 에 남긴다. 판정은 파이썬 대조기(v4)가 한다.
 * 대역 거래소 계좌 = 시작 KRW 1,000,000.00 · 기존 보유 XRP 5(어느 세션 것도 아님) + 응답별 체결 효과.</p>
 *
 * <pre>
 * docker exec cat-rt-pg psql -U trader -d postgres -c "DROP DATABASE IF EXISTS p7_cashflow" -c "CREATE DATABASE p7_cashflow"
 * SR_PG_URL=jdbc:postgresql://localhost:15432/p7_cashflow P7_SQL_DIR=... P7_OUT=... ./gradlew :web-api:test --tests '*P7CashflowPgTest'
 * </pre>
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
@DisplayName("P7 격리 PG 실제 현금흐름 — 대조기 입력 스냅샷")
class P7CashflowPgTest extends IntegrationTestBase {

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

    // ── 대역 거래소 장부 ─────────────────────────────────────────────────────
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

    // ── 스냅샷(P7 v2 SQL 원문) ───────────────────────────────────────────────
    private static String sqlBody(String file) throws Exception {
        String text = Files.readString(Path.of(System.getenv("P7_SQL_DIR"), file), StandardCharsets.UTF_8);
        String body = text.lines()
                .filter(l -> !l.startsWith("\\") && !l.startsWith("BEGIN") && !l.startsWith("COMMIT"))
                .collect(Collectors.joining("\n")).trim();
        return body.endsWith(";") ? body.substring(0, body.length() - 1) : body;
    }

    private final List<String> labels = new ArrayList<>();

    /** 수집 단계 시각 — 수집기(JVM) 시계와 DB 시계(clock_timestamp)를 함께 남긴다(총괄 v1.188 §3-2: 두 시계를 같은 축으로 가정하지 않음).
     *  at_db 는 at 직후 한 번의 왕복 조회 값이고, rtt_ms 는 그 조회의 왕복 시간이다(차이 추정의 불확실성 상한). */
    private Map<String, Object> step(String name) {
        Instant t0 = Instant.now();
        String dbAt = jdbc.queryForObject(
                "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')", String.class);
        Instant t1 = Instant.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", name);
        m.put("at", t0.toString());
        m.put("at_db", dbAt);
        m.put("rtt_ms", java.time.Duration.between(t0, t1).toNanos() / 1e6);
        return m;
    }

    private void snap(String label) throws Exception {
        String fpSql = sqlBody("fingerprint.sql");
        String exSql = sqlBody("extract.sql");
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("db_fp_pre"));
        String fp1 = jdbc.queryForObject(fpSql, String.class);
        Thread.sleep(5);
        steps.add(step("exchange_before"));
        Map<String, Object> ex1 = exchangeView();
        Thread.sleep(5);
        steps.add(step("db_extract"));
        String db = jdbc.queryForObject(exSql, String.class);
        Thread.sleep(5);
        steps.add(step("exchange_after"));
        Map<String, Object> ex2 = exchangeView();
        Thread.sleep(5);
        steps.add(step("db_fp_post"));
        String fp2 = jdbc.queryForObject(fpSql, String.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("steps", steps);
        out.put("db_fp_pre", fp1);
        out.put("exchange_before", ex1);
        out.put("db", objectMapper.readTree(db));
        out.put("exchange_after", ex2);
        out.put("db_fp_post", fp2);
        Path dir = Path.of(System.getenv("P7_OUT"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(label + ".json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n",
                StandardCharsets.UTF_8);
        labels.add(label);
        System.out.println("[P7 PG] 스냅샷 " + label + " fp=" + fp1 + "/" + fp2);
    }

    // ── 엔진 호출 ─────────────────────────────────────────────────────────────
    private static OrderResponse buy(String state, String vol, String funds, String fee) {
        OrderResponse r = new OrderResponse();
        r.setSide("bid");
        r.setOrdType("price");
        r.setState(state);
        r.setMarket("KRW-RND");
        r.setExecutedVolume(new BigDecimal(vol));
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPaidFee(new BigDecimal(fee));
        return r;
    }

    private void poll(Long orderId, OrderResponse r) throws Exception {
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

    /** 한 종류(LIVE/DYNAMIC)의 흐름 — 새 배정 → 예약·제출 → 부분 체결 → 취소 종결(환급) → 수수료 정정 3회 → 같은 응답 반복 → 무거래. */
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
        snap(p + "1-allocated");

        // 풀 손실 가드(켜 둠)가 기존 보유를 평가할 대역 실시간 시세 — 다른 시험(AlignedV148FixesTest 등)과 같은 방식
        markPriceBook.record("KRW-RND", new BigDecimal("300"), Instant.now());
        String result;
        if ("LIVE".equals(kind)) {
            Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(liveTradingService);
            result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(target, "executeSessionBuy",
                    liveRepo.findById(sid).orElseThrow(), "KRW-RND", new BigDecimal("300"), "P7 PG 현금흐름",
                    null, null, candles(), null);
        } else {
            Long dsid = sid;
            result = new org.springframework.transaction.support.TransactionTemplate(txManager).execute(st ->
                    dynamicTradingService.executeBuy(dynamicRepo.findById(dsid).orElseThrow(), "KRW-RND", candles(),
                            com.cryptoautotrader.strategy.StrategySignal.buy(new BigDecimal("80"), "P7 PG 현금흐름"), BigDecimal.ONE));
        }
        assertThat(result).as("%s 매수 경로 결과(null = 예약·제출)", kind).isNull();
        OrderEntity o = orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc(kind, sid).stream()
                .filter(x -> "BUY".equals(x.getSide())).findFirst().orElseThrow();
        awaitSubmitted(o.getId());
        o = orderRepository.findById(o.getId()).orElseThrow();
        BigDecimal request = o.getQuantity();
        assertThat(last).as("대역 거래소가 접수한 uuid").containsKey(o.getExchangeOrderId());
        assertThat(available(kind, sid)).isEqualByComparingTo(init.subtract(request));
        snap(p + "2-submitted");

        poll(o.getId(), buy("wait", "20", "6000.004", "3.000002"));
        assertThat(orderRepository.findById(o.getId()).orElseThrow().getState()).isIn("SUBMITTED", "PARTIAL_FILLED");
        snap(p + "3-partial");

        poll(o.getId(), buy("cancel", "25.5", "7650.0037", "3.82500185"));
        o = orderRepository.findById(o.getId()).orElseThrow();
        BigDecimal want = init.subtract(request).add(request.subtract(new BigDecimal("7650.0037")).subtract(new BigDecimal("3.82500185"))
                .setScale(2, RoundingMode.HALF_UP));
        System.out.println("[P7 PG] " + kind + " 취소 종결 state=" + o.getState() + " available=" + available(kind, sid) + " 기대=" + want);
        assertThat(available(kind, sid)).isEqualByComparingTo(want);
        snap(p + "4-cancelled");

        BigDecimal fee = new BigDecimal("3.82500185");
        List<String> outs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            fee = fee.add(new BigDecimal("0.003"));
            outs.add(settle(o.getId(), buy("cancel", "25.5", "7650.0037", fee.toPlainString())).name());
        }
        System.out.println("[P7 PG] " + kind + " 정정 3회 결과 " + outs + " available=" + available(kind, sid));
        snap(p + "5-corrected");

        String again = settle(o.getId(), buy("cancel", "25.5", "7650.0037", fee.toPlainString())).name();
        System.out.println("[P7 PG] " + kind + " 같은 응답 반복 결과 " + again + " available=" + available(kind, sid));
        snap(p + "6-repeated");
        snap(p + "7-idle");
    }

    @Test
    @DisplayName("LIVE·DYNAMIC 실자금 매수 현금흐름 스냅샷 — 새 배정·제출·부분 체결·취소 환급·수수료 정정·반복·무거래")
    void cashflowSnapshots() throws Exception {
        String stage = limits.getStage();
        BigDecimal total = limits.getTotalCapKrw();
        BigDecimal per = limits.getPerPositionCapKrw();
        Integer slots = limits.getMaxConcurrentPositions();
        limits.setStage("P7_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 10);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("1000000")));
        poolLossGuard.setEnabledSetting(true);
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("p7-" + UUID.randomUUID());
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
