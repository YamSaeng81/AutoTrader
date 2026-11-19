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
 * <b>사전 SQL v3 ↔ 제품 판정값 대조</b> (총괄 v1.194 §2-4) — 배포 전 읽기안 v3(predeploy_read.v3.sql) 원문의 R1·R2·R3 을 그대로 실행하고,
 * 같은 시점의 제품 값(countRealSessionCostUndeterminedSince·두 손실 합·checkCircuitBreaker·연속 손실 입력 조회)과 입력마다 나란히 남긴다.
 * 헬퍼는 AbcCrossPgTest(=CostBasisBoundaryPgTest 이식)에서 그대로 옮겼다. 행 직접 쓰기는 미보고·확정 0·복구·시각 이동 재현에만 쓰고 라벨에 적는다.
 * 운영 DB 는 쓰지 않는다(SR_PG_URL 은 localhost 만).
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
@DisplayName("사전 SQL v3 ↔ 제품 판정값 (격리 PG)")
class PredeploySqlParityPgTest extends IntegrationTestBase {

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


    // ── 사전 SQL v3 원문(PRE_SQL 파일)에서 SELECT 문 3개(R1·R2·R3)를 그대로 꺼내 실행한다 ──
    private List<String> preSql() throws Exception {
        String raw = Files.readString(Path.of(System.getenv("PRE_SQL")), StandardCharsets.UTF_8);
        StringBuilder b = new StringBuilder();
        for (String line : raw.split("\\R")) if (!line.startsWith("--")) b.append(line).append('\n');
        List<String> st = new ArrayList<>();
        for (String s : b.toString().split(";\\s*\\n")) if (s.trim().startsWith("SELECT")) st.add(s.trim());
        assertThat(st).as("R1·R2·R3").hasSize(3);
        return st;
    }

    private static String num(Object o) {
        return o == null ? "null" : new BigDecimal(o.toString()).stripTrailingZeros().toPlainString();
    }

    /** R3 열을 사전 SQL 주석의 읽기 규칙대로 판정(newest_rank 1 부터, 한도 안). 반환: TRIGGERED / UNDETERMINED posId / PASS. */
    private static String sqlVerdict(List<Map<String, Object>> rows, int limit) {
        int losses = 0;
        for (Map<String, Object> r : rows) {
            long buyN = ((Number) r.get("c1")).longValue(), buyUnknown = ((Number) r.get("c2")).longValue();
            long sellN = ((Number) r.get("c5")).longValue(), sellMissing = ((Number) r.get("c8")).longValue();
            boolean noTrade = ((Number) r.get("c9")).intValue() == 1;
            BigDecimal buyCost = r.get("c3") == null ? null : new BigDecimal(r.get("c3").toString());
            BigDecimal buyQty = r.get("c4") == null ? null : new BigDecimal(r.get("c4").toString());
            BigDecimal sellNet = r.get("c6") == null ? null : new BigDecimal(r.get("c6").toString());
            BigDecimal sellQty = r.get("c7") == null ? null : new BigDecimal(r.get("c7").toString());
            Object id = r.get("c0");
            if (buyN == 0 && sellN == 0 && sellMissing == 0) {
                if (noTrade) break;   // 무거래 확정 = 0 → 줄 끊김
                return losses >= limit ? "TRIGGERED" : "UNDETERMINED " + id;
            }
            if (buyN == 0 || buyUnknown > 0 || sellMissing > 0 || sellNet == null || buyCost == null || buyQty == null
                    || buyQty.signum() <= 0 || sellQty == null || sellQty.compareTo(buyQty) < 0) {
                return losses >= limit ? "TRIGGERED" : "UNDETERMINED " + id;
            }
            BigDecimal pnl = sellNet.subtract(sellQty.multiply(buyCost).divide(buyQty, 8, java.math.RoundingMode.HALF_UP));
            if (pnl.signum() < 0) losses++; else break;
        }
        return losses >= limit ? "TRIGGERED" : "PASS";
    }

    private static String productVerdict(Map<String, Object> br) {
        if (Boolean.TRUE.equals(br.get("triggered"))) return "TRIGGERED";
        String reason = String.valueOf(br.get("reason"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("posId=(\\d+)").matcher(reason);
        if (undet(reason) && m.find()) return "UNDETERMINED " + m.group(1);
        return "PASS";
    }

    /** 제품 연속 손실 입력 범위·순서(제품 조회 + DYNAMIC started_at 중단 규칙 그대로). */
    private List<Long> productRange(String kind, Long sid) {
        Instant after = "DYNAMIC".equals(kind) ? dynamicRepo.findById(sid).orElseThrow().getStartedAt() : null;
        List<Long> ids = new ArrayList<>();
        for (PositionEntity p : positionRepository.findBySessionKindAndSessionIdAndStatusOrderByClosedAtDesc(kind, sid, "CLOSED")) {
            if (after != null && (p.getClosedAt() == null || !p.getClosedAt().isAfter(after))) break;
            ids.add(p.getId());
        }
        return ids;
    }

    private final List<Map<String, Object>> table = new ArrayList<>();

    /** 열 위치로 읽는다 — 이름 없는 하위 조회 열(?column?)은 Map 으로 읽으면 하나로 합쳐진다. */
    private List<Object[]> rows(String q) {
        return jdbc.query(q, (rs, i) -> {
            Object[] a = new Object[rs.getMetaData().getColumnCount()];
            for (int c = 0; c < a.length; c++) a[c] = rs.getObject(c + 1);
            return a;
        });
    }

    /** 한 입력 시점의 기대(사전 SQL v3) 대 실제(제품) — 전역 두 종류 + 지정 세션. */
    private void compare(String input, List<String> sql, int limit, Object[]... sessions) {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        Map<String, String> r1 = new LinkedHashMap<>(), r2 = new LinkedHashMap<>();
        for (Object[] r : rows(sql.get(0))) r1.put(String.valueOf(r[0]), num(r[1]));
        for (Object[] r : rows(sql.get(1))) r2.put(String.valueOf(r[0]), num(r[1]));
        for (String kind : List.of("LIVE", "DYNAMIC")) {
            row(input, kind + " 전역 30일 판정 불가 수", r1.get(kind), String.valueOf(orderRepository.countRealSessionCostUndeterminedSince(kind, since)));
            row(input, kind + " 전역 30일 손실 합", r2.get(kind), num("LIVE".equals(kind) ? liveLoss(since) : dynLoss(since)));
        }
        List<Map<String, Object>> r3 = new ArrayList<>();
        for (Object[] v : rows(sql.get(2))) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < 10; i++) m.put("c" + i, v[i]);
            m.put("kind", v[10]);
            m.put("sid", v[11]);
            m.put("rank", v[15]);
            r3.add(m);
        }
        for (Object[] s : sessions) {
            String kind = (String) s[0];
            Long sid = (Long) s[1];
            List<Map<String, Object>> mine = r3.stream()
                    .filter(m -> kind.equals(m.get("kind")) && sid.toString().equals(String.valueOf(m.get("sid")))).toList();
            List<Long> sqlIds = mine.stream().map(m -> ((Number) m.get("c0")).longValue()).toList();
            row(input, kind + "#" + sid + " 연속 손실 입력 범위·최신순 id", sqlIds.toString(), productRange(kind, sid).toString());
            row(input, kind + "#" + sid + " 세션 판정", sqlVerdict(mine, limit), productVerdict(breaker(kind, sid)));
        }
    }

    private void row(String input, String item, String expected, String actual) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("input", input);
        m.put("item", item);
        m.put("expected_sql_v3", expected);
        m.put("actual_product", actual);
        boolean ok = java.util.Objects.equals(expected, actual);
        m.put("match", ok);
        table.add(m);
        if (!ok) failures.add(input + " / " + item + " : SQL " + expected + " ≠ 제품 " + actual);
    }

    @Test
    @DisplayName("사전 SQL v3 ↔ 제품 판정값 — 전역 30일 판정 불가·손실 합, 세션 입력 범위·최신순, NULL/확정 0, 해제, 표시 없는 빈 청산")
    void parity() throws Exception {
        List<String> sql = preSql();
        String stage = limits.getStage();
        BigDecimal total = limits.getTotalCapKrw();
        BigDecimal per = limits.getPerPositionCapKrw();
        Integer slots = limits.getMaxConcurrentPositions();
        limits.setStage("PAR_" + UUID.randomUUID().toString().substring(0, 8));
        limits.setMaxConcurrentPositions((int) positionRepository.countPoolSlots(-1L) + 30);
        limits.setPerPositionCapKrw(new BigDecimal("20000"));
        limits.setTotalCapKrw(positionRepository.sumPoolExposureKrw(-1L).add(new BigDecimal("10000000")));
        poolLossGuard.setEnabledSetting(true);
        Mockito.when(upbitOrderClient.createOrder(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    OrderResponse r = new OrderResponse();
                    r.setUuid("par-" + UUID.randomUUID());
                    r.setSide(inv.getArgument(1));
                    r.setOrdType(inv.getArgument(4));
                    r.setState("wait");
                    r.setMarket(inv.getArgument(0));
                    return new UpbitOrderClient.ExchangeResult(r, "{}");
                });
        final int limit = 2;
        try {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"order\"", Long.class)).as("새 DB").isZero();
            setRisk("90", limit);
            for (String kind : List.of("LIVE", "DYNAMIC")) {
                // P1 확정 손실 1건 → 손실 합·입력 1건·PASS
                Long s1 = newSession(kind);
                trip(kind, s1, "7400", "3.70");
                compare(kind + " P1 확정 손실 1건", sql, limit, new Object[]{kind, s1});
                // P2 확정 손실 2건(한도 2) → 최신순 2건·TRIGGERED
                trip(kind, s1, "7300", "3.65");
                compare(kind + " P2 확정 손실 2건 = 한도", sql, limit, new Object[]{kind, s1});
                ageAll();

                // P3 매도 수수료 NULL(미보고, 행 직접) / P4 확정 0(행 직접) / P5 확정 수수료 복구 → 해제
                Long s2 = newSession(kind);
                Long[] t = trip(kind, s2, "7400", "3.70");
                jdbc.update("UPDATE \"order\" SET applied_paid_fee = NULL WHERE id = ?", t[1]);
                compare(kind + " P3 매도 수수료 NULL(행 직접)", sql, limit, new Object[]{kind, s2});
                jdbc.update("UPDATE \"order\" SET applied_paid_fee = 0 WHERE id = ?", t[1]);
                compare(kind + " P4 매도 수수료 확정 0(행 직접)", sql, limit, new Object[]{kind, s2});
                jdbc.update("UPDATE \"order\" SET applied_paid_fee = 3.70 WHERE id = ?", t[1]);
                compare(kind + " P5 확정 수수료 3.70 복구 → 해제", sql, limit, new Object[]{kind, s2});
                ageAll();

                // P6 표시 없는 빈 청산(주문 원천 없음, ENTRY_REJECTED 아님) / P7 그 뒤 확정 손실 → 최신순
                Long s3 = newSession(kind);
                noSourceClosed(kind, s3);
                compare(kind + " P6 표시 없는 빈 청산", sql, limit, new Object[]{kind, s3});
                ageAll();
                // P7 새 세션: 확정 손실 1건 뒤, 그보다 이른 시각의 빈 청산(행 직접 시각 이동) → 최신순 [손실, 빈 청산]
                //    빈 청산이 최신이면 제품이 매수 자체를 막으므로(정상) 손실 왕복을 먼저 하고 빈 청산을 과거에 둔다.
                Long s3b = newSession(kind);
                trip(kind, s3b, "7400", "3.70");
                Long empty = noSourceClosed(kind, s3b);
                jdbc.update("UPDATE position SET closed_at = now() - interval '10 minutes', opened_at = now() - interval '2 hours' WHERE id = ?", empty);
                if ("DYNAMIC".equals(kind)) jdbc.update("UPDATE dynamic_session SET started_at = now() - interval '1 hour' WHERE id = ?", s3b);
                compare(kind + " P7 확정 손실 뒤 더 이른 빈 청산(최신순)", sql, limit, new Object[]{kind, s3b});
                ageAll();

                // P8 가동 시작 전·30일 밖 빈 청산 — DYNAMIC 은 범위 밖, LIVE 는 시간 제한 없이 범위 안
                Long s4 = newSession(kind);
                Long old = noSourceClosed(kind, s4);
                jdbc.update("UPDATE position SET closed_at = now() - interval '45 days', opened_at = now() - interval '46 days' WHERE id = ?", old);
                if ("DYNAMIC".equals(kind)) jdbc.update("UPDATE dynamic_session SET started_at = now() - interval '1 day' WHERE id = ?", s4);
                else jdbc.update("UPDATE live_trading_session SET started_at = now() - interval '1 day' WHERE id = ?", s4);
                compare(kind + " P8 가동 시작 전·30일 밖 빈 청산", sql, limit, new Object[]{kind, s4});
                ageAll();
            }
            out.put("parity", table);
        } finally {
            limits.setStage(stage);
            limits.setTotalCapKrw(total);
            limits.setPerPositionCapKrw(per);
            limits.setMaxConcurrentPositions(slots);
            out.put("failures", failures);
            String dir = System.getenv("COSTB_OUT");
            if (dir != null) {
                Files.createDirectories(Path.of(dir));
                Files.writeString(Path.of(dir, "predeploy_v3_parity.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
            }
            System.out.println("[사전 SQL v3 대조] " + objectMapper.writeValueAsString(out));
        }
        assertThat(failures).as("사전 SQL v3 와 제품 판정값이 다른 입력").isEmpty();
    }
}
